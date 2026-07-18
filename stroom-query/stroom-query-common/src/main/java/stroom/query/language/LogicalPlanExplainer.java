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

import stroom.query.api.ExplainPlan;
import stroom.query.api.ExpressionTerm;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.cost.CostModel;
import stroom.query.planner.cost.CostedAccessPath;
import stroom.query.planner.cost.JoinCostModel;
import stroom.query.planner.cost.JoinPlan;
import stroom.query.planner.cost.StateLookup;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;
import stroom.query.planner.logical.Window;
import stroom.query.planner.port.FieldInfoSource;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns a bound, rewritten {@link LogicalPlan} into an {@link ExplainPlan} wire-tree, costing each {@code Scan}
 * via {@link CostModel} - see {@code docs/query-optimiser-implementation-plan.md}, Task 4.1. Package-private:
 * an implementation detail of {@link OptimisingQueryCompiler#explain}, not part of this package's public API.
 *
 * <p><b>Scope note</b>: extracting a time range and selectivity-relevant terms for a {@code Scan}'s cost is
 * only done for the common shape - a {@link Filter} directly above that {@code Scan}. A deeper/more complex
 * shape (a {@code Filter} above something other than a bare {@code Scan}) still produces a correct plan
 * <i>tree</i>, just without that extra refinement on the affected {@code Scan}'s cost - a conservative
 * simplification (an under-refined but not wrong estimate), not a defect. Likewise, join cost annotation
 * ({@link JoinCostModel}) is only attempted when both sides of a {@code Join} are themselves direct
 * {@code Scan}s; a nested join still produces a correct tree, just without its own cardinality/algorithm note.</p>
 */
final class LogicalPlanExplainer {

    private final CostModel costModel;
    private final FieldInfoSource fieldInfoSource;
    private final ExpressionContext expressionContext;

    LogicalPlanExplainer(
            final CostModel costModel, final FieldInfoSource fieldInfoSource,
            final ExpressionContext expressionContext) {
        this.costModel = Objects.requireNonNull(costModel, "costModel");
        this.fieldInfoSource = Objects.requireNonNull(fieldInfoSource, "fieldInfoSource");
        this.expressionContext = Objects.requireNonNull(expressionContext, "expressionContext");
    }

    ExplainPlan explain(final LogicalPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return toNode(plan).explainPlan();
    }

    /** A node's wire-ready {@link ExplainPlan}, plus (for a {@code Scan} only) the internal cost result a
     *  parent {@code Join} needs to choose its algorithm - not itself part of the wire shape. */
    private record Node(ExplainPlan explainPlan, @Nullable CostedAccessPath costedAccessPath) {
    }

    private Node toNode(final LogicalPlan plan) {
        return switch (plan) {
            case final Scan scan -> costScan(scan, null, null, List.of());
            case final Filter f -> explainFilter(f);
            case final Project p -> wrap("Project", toNode(p.input()));
            case final Join j -> explainJoin(j);
            case final Aggregate a -> wrap(
                    "Group by " + a.groupFields().stream().map(this::describeField)
                            .collect(Collectors.joining(", ")),
                    toNode(a.input()));
            case final Having h -> wrap("Having", toNode(h.input()));
            case final Window w -> wrap("Window by " + describeField(w.field()), toNode(w.input()));
            case final Sort s -> wrap("Sort", toNode(s.input()));
            case final Limit l -> wrap("Limit " + l.values(), toNode(l.input()));
            case final NodeScan ns -> new Node(
                    ExplainPlan.builder()
                            .description("NodeScan " + ns.variable()
                                    + (ns.labels().isEmpty() ? "" : ":" + String.join(":", ns.labels())))
                            .build(),
                    null);
            case final Expand e -> wrap(
                    "Expand " + e.direction() + " " + e.edgeType() + " as " + e.targetVariable(),
                    toNode(e.input()));
            case final VarLengthExpand vle -> wrap(
                    "VarLengthExpand " + vle.direction() + " " + vle.edgeType()
                            + "*" + vle.minHops() + ".." + vle.maxHops() + " as " + vle.targetVariable(),
                    toNode(vle.input()));
        };
    }

    private Node wrap(final String description, final Node child) {
        return new Node(
                ExplainPlan.builder().description(description).children(List.of(child.explainPlan())).build(),
                null);
    }

    private Node costScan(
            final Scan scan, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs,
            final List<ExpressionTerm> selectivityTerms) {
        final CostedAccessPath costed = costModel.estimate(scan, fromTimeMs, toTimeMs, selectivityTerms);
        final ExplainPlan explainPlan = ExplainPlan.builder()
                .description("Scan " + scan.dataSourceName() + " as " + scan.alias()
                             + " (" + costed.accessPath().getClass().getSimpleName() + ")")
                .estimatedRows(costed.estimate().rows())
                .estimatedDurationMs(costed.estimate().durationMs())
                .confidence(costed.estimate().confidence())
                .notes(costed.estimate().notes())
                .build();
        return new Node(explainPlan, costed);
    }

    private Node explainFilter(final Filter filter) {
        if (filter.input() instanceof final Scan scan) {
            final ScanTimeBounds bounds = ScanTimeRangeExtractor.extract(
                    scan, filter, fieldInfoSource, expressionContext);
            final Node scanNode = costScan(scan, bounds.fromTimeMs(), bounds.toTimeMs(), bounds.selectivityTerms());
            return wrap("Filter", scanNode);
        }
        return wrap("Filter", toNode(filter.input()));
    }

    private Node explainJoin(final Join join) {
        final Node left = toNode(join.left());
        final Node right = toNode(join.right());
        final List<ExplainPlan> children = List.of(left.explainPlan(), right.explainPlan());

        if (left.costedAccessPath() == null || right.costedAccessPath() == null) {
            // A nested join (a Join whose own side is itself a Join/Filter/...) - still a correct tree, just
            // without a cardinality/algorithm note on this level. See class Javadoc.
            return new Node(ExplainPlan.builder().description("Join").children(children).build(), null);
        }

        final JoinPlan joinPlan = JoinCostModel.chooseAlgorithm(left.costedAccessPath(), right.costedAccessPath());
        final long cardinality = JoinCostModel.estimateCardinality(
                left.costedAccessPath().estimate().rows(), right.costedAccessPath().estimate().rows(), 0, 0);
        final boolean leftIsLookup = left.costedAccessPath().accessPath() instanceof StateLookup;
        final boolean rightIsLookup = right.costedAccessPath().accessPath() instanceof StateLookup;
        final ExplainPlan explainPlan = ExplainPlan.builder()
                .description("Join (" + joinPlan.algorithm() + ", build side: " + joinPlan.buildSide() + ")")
                .children(children)
                .estimatedRows(cardinality)
                .notes(List.of("distinct-key counts unknown - cardinality is the pessimistic upper bound "
                               + "(full cross-product divided by 1)"))
                .build();
        return new Node(explainPlan, null);
        // Deliberately not propagating a CostedAccessPath upward for the join itself yet - no AccessPath
        // variant represents "the result of a join" (leftIsLookup/rightIsLookup above are only used to choose
        // the algorithm, not to describe this node's own access path), and nothing needs a join-of-joins
        // estimate in this pass (see class Javadoc's nested-join scope note).
    }

    private String describeField(final QualifiedField field) {
        return field.alias() == null ? field.field() : field.alias() + "." + field.field();
    }
}
