/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.language;

import stroom.query.api.DateTimeSettings;
import stroom.query.api.ExplainPlan;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.QueryField;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.cost.CostModel;
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.FieldInfoSource;
import stroom.query.planner.port.RowCountSignal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 4.1/6.3: direct tests for {@link LogicalPlanExplainer}'s join annotation - the leaf hash-join
 * (cross-product upper bound), the keyed State-lookup join (cardinality uses the lookup side's unique-key
 * count), and the nested join (annotated, no cardinality). Exercises the explainer with a real {@link CostModel}
 * over lambda cost ports.
 */
class TestLogicalPlanExplainer {

    private static final AstPosition POS = new AstPosition(1, 0);

    // "Events" and "Other" answer via meta stats (-> FullScan); "Lookup" answers via state stats (-> StateLookup).
    private static final CostModel COST_MODEL = new CostModel(
            (feedName, from, to) -> switch (feedName) {
                case "Events" -> Optional.of(new RowCountSignal(100));
                case "Other" -> Optional.of(new RowCountSignal(50));
                default -> Optional.empty();
            },
            (indexName, from, to) -> Optional.empty(),
            storeName -> "Lookup".equals(storeName) ? Optional.of(new RowCountSignal(10)) : Optional.empty());

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return List.of();
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    private static ExplainPlan explain(final LogicalPlan plan) {
        return new LogicalPlanExplainer(
                COST_MODEL,
                FIELD_INFO_SOURCE,
                ExpressionContext.builder()
                        .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                        .maxStringLength(100)
                        .build())
                .explain(plan);
    }

    private static EquiKey equiKey() {
        return new EquiKey(new QualifiedField("a", "Id"), new QualifiedField("b", "Id"));
    }

    @Test
    void leafHashJoin_annotatesCrossProductUpperBound() {
        final LogicalPlan join = new Join(
                new Scan("a", "Events", POS), new Scan("b", "Other", POS),
                JoinType.INNER, List.of(equiKey()), POS);

        final ExplainPlan plan = explain(join);

        assertThat(plan.getDescription()).contains("HASH_JOIN");
        // Both sides are non-keyed scans, so distinct keys are unknown -> full cross-product: 100 * 50 = 5000.
        assertThat(plan.getEstimatedRows()).isEqualTo(5_000L);
        assertThat(plan.getNotes()).anySatisfy(note -> assertThat(note).contains("cross-product"));
        assertThat(plan.getChildren()).hasSize(2);
    }

    @Test
    void keyedStateLookupJoin_usesTheLookupSidesUniqueKeyCount() {
        final LogicalPlan join = new Join(
                new Scan("a", "Events", POS), new Scan("b", "Lookup", POS),
                JoinType.INNER, List.of(equiKey()), POS);

        final ExplainPlan plan = explain(join);

        assertThat(plan.getDescription()).contains("BROADCAST_LOOKUP");
        // Right side is a keyed State lookup (10 rows, unique keys): 100 * 10 / max(1, 10) = 100, i.e. ~the
        // probe (left) side's row count - not the 1000 the old hard-coded 0,0 cross-product would have given.
        assertThat(plan.getEstimatedRows()).isEqualTo(100L);
        assertThat(plan.getNotes()).anySatisfy(note -> assertThat(note).contains("keyed"));
    }

    @Test
    void filteredJoin_stillGetsAJoinLevelCostAnnotation() {
        // F10: Join(Filter(Scan), Scan) is the shape produced for essentially every filtered join - the Filter's
        // inner Scan cost must still propagate up so this isn't mistaken for an un-annotated "nested join".
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .addTerm(ExpressionTerm.builder().field("Id").condition(Condition.EQUALS).value("1").build())
                .build();
        final LogicalPlan join = new Join(
                new Filter(new Scan("a", "Events", POS), where, null, POS),
                new Scan("b", "Other", POS),
                JoinType.INNER, List.of(equiKey()), POS);

        final ExplainPlan plan = explain(join);

        // Before the fix, wrap() hardcoded costedAccessPath=null for the Filter-over-Scan branch, so this shape
        // always fell into the "nested join" branch below with no cardinality/algorithm annotation at all.
        assertThat(plan.getDescription()).contains("HASH_JOIN");
        assertThat(plan.getEstimatedRows()).isNotNull();
        assertThat(plan.getNotes()).noneMatch(note -> note.contains("nested join"));
        assertThat(plan.getChildren()).hasSize(2);
    }

    @Test
    void nestedJoin_isAnnotatedWithoutACardinalityEstimate() {
        // One side is a Limit(Scan), not a direct Scan, so it has no compile-time CostedAccessPath to combine.
        final LogicalPlan join = new Join(
                new Limit(new Scan("a", "Events", POS), 0L, List.of(10L), POS),
                new Scan("b", "Other", POS),
                JoinType.INNER, List.of(equiKey()), POS);

        final ExplainPlan plan = explain(join);

        assertThat(plan.getDescription()).isEqualTo("Join");
        assertThat(plan.getEstimatedRows()).isNull();
        assertThat(plan.getNotes()).anySatisfy(note -> assertThat(note).contains("nested join"));
        assertThat(plan.getChildren()).hasSize(2);
    }
}
