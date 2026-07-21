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

package stroom.query.planner.logical;

import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.grammar.ast.AstPosition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2.1: proves every {@link LogicalPlan} node builds a usable tree, and that the constructor contracts
 * (non-null required fields, non-empty required lists, defensive copying) are actually enforced, not just
 * documented - per this project's code standards.
 */
class TestLogicalPlan {

    private static final AstPosition POS = new AstPosition(1, 0);

    @Test
    void oneTreeExercisesEveryNodeKind() {
        final Scan events = new Scan("e", "Events", POS);
        final Scan users = new Scan("u", "Users", POS);

        final ExpressionOperator wherePredicate = ExpressionOperator.builder()
                .children(List.of(ExpressionTerm.builder()
                        .field("StreamId")
                        .condition(Condition.GREATER_THAN)
                        .value("0")
                        .build()))
                .build();
        final Filter filtered = new Filter(events, wherePredicate, null, POS);

        final Join joined = new Join(
                filtered,
                users,
                JoinType.INNER,
                List.of(new EquiKey(new QualifiedField("e", "UserId"), new QualifiedField("u", "Id"))),
                POS);

        final Project projected = new Project(
                joined,
                List.of(
                        new ProjectField("upper", "upperCase(${u.Name})", false, null, POS),
                        new ProjectField("UserName", "upper", true, "UserName", POS)),
                POS);

        final Aggregate aggregated = new Aggregate(
                projected, List.of(new QualifiedField(null, "UserName")), POS);

        final Having having = new Having(
                aggregated,
                ExpressionOperator.builder()
                        .op(Op.AND)
                        .children(List.of(ExpressionTerm.builder()
                                .field("UserName")
                                .condition(Condition.NOT_EQUALS)
                                .value("")
                                .build()))
                        .build(),
                POS);

        final Window windowed = new Window(
                having, new QualifiedField(null, "EventTime"), "1h", "10m", "sum", POS);

        final Sort sorted = new Sort(
                windowed, List.of(new SortKey(new QualifiedField(null, "UserName"), false)), POS);

        final Limit limited = new Limit(sorted, List.of(100L), POS);

        assertThat(limited.input()).isSameAs(sorted);
        assertThat(sorted.input()).isSameAs(windowed);
        assertThat(windowed.input()).isSameAs(having);
        assertThat(having.input()).isSameAs(aggregated);
        assertThat(aggregated.input()).isSameAs(projected);
        assertThat(projected.input()).isSameAs(joined);
        assertThat(joined.left()).isSameAs(filtered);
        assertThat(joined.right()).isSameAs(users);
        assertThat(filtered.input()).isSameAs(events);

        assertThat(limited.values()).containsExactly(100L);
        assertThat(sorted.keys()).extracting(SortKey::descending).containsExactly(false);
        assertThat(windowed.advanceSize()).isEqualTo("10m");
        assertThat(aggregated.groupFields()).extracting(QualifiedField::field).containsExactly("UserName");
        assertThat(projected.fields()).hasSize(2);
        assertThat(joined.equiKeys()).hasSize(1);
        assertThat(filtered.wherePredicate()).isSameAs(wherePredicate);
        assertThat(filtered.filterPredicate()).isNull();

        // Every node reports its position (needed for later error/EXPLAIN reporting).
        final List<LogicalPlan> allNodes = List.of(
                events, filtered, joined, projected, aggregated, having, windowed, sorted, limited);
        assertThat(allNodes).allSatisfy(node -> assertThat(node.position()).isSameAs(POS));
    }

    @Test
    void requiredFieldsRejectNull() {
        final Scan scan = new Scan("e", "Events", POS);

        assertThatNullPointerException().isThrownBy(() -> new Scan(null, "Events", POS));
        assertThatNullPointerException().isThrownBy(() -> new Scan("e", null, POS));
        assertThatNullPointerException().isThrownBy(() -> new Filter(null, null, null, POS));
        assertThatNullPointerException().isThrownBy(() -> new Filter(scan, null, null, null));
        assertThatNullPointerException().isThrownBy(() -> new Project(null, List.of(), POS));
        assertThatNullPointerException().isThrownBy(() -> new Join(null, scan, JoinType.INNER, List.of(
                new EquiKey(new QualifiedField(null, "a"), new QualifiedField(null, "b"))), POS));
        assertThatNullPointerException().isThrownBy(() -> new Having(scan, null, POS));
        assertThatNullPointerException().isThrownBy(() -> new Window(
                scan, null, "1h", null, null, POS));
        assertThatThrownBy(() -> new QualifiedField(null, null)).isInstanceOf(NullPointerException.class);
        assertThatNullPointerException().isThrownBy(() -> new EquiKey(null, new QualifiedField(null, "b")));
    }

    @Test
    void requiredNonEmptyListsRejectEmpty() {
        final Scan scan = new Scan("e", "Events", POS);

        assertThatIllegalArgumentException().isThrownBy(() -> new Join(
                scan, scan, JoinType.INNER, List.of(), POS));
        assertThatIllegalArgumentException().isThrownBy(() -> new Aggregate(scan, List.of(), POS));
        assertThatIllegalArgumentException().isThrownBy(() -> new Sort(scan, List.of(), POS));
        assertThatIllegalArgumentException().isThrownBy(() -> new Limit(scan, List.of(), POS));
    }

    @Test
    void listsAreDefensivelyCopiedAndImmutable() {
        final Scan scan = new Scan("e", "Events", POS);
        final List<Long> mutableValues = new ArrayList<>(List.of(1L, 2L));

        final Limit limit = new Limit(scan, mutableValues, POS);
        mutableValues.add(3L);

        assertThat(limit.values()).containsExactly(1L, 2L);
        assertThatThrownBy(() -> limit.values().add(4L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Task PoC.2: the graph front-end's leaf/hop nodes build a usable tree and enforce the same contracts as
     * the relational nodes above.
     */
    @Test
    void graphNodes_buildAUsableTree() {
        final ExpressionOperator propertyAnchor = ExpressionOperator.builder()
                .children(List.of(ExpressionTerm.builder()
                        .field("id")
                        .condition(Condition.EQUALS)
                        .value("d-42")
                        .build()))
                .build();
        final NodeScan device = new NodeScan("d", List.of("Device"), propertyAnchor, POS);
        final Expand toAccount = new Expand(
                device, "CONNECTED_TO", Direction.OUT, null, "a", List.of("Account"), propertyAnchor, POS);
        final VarLengthExpand toGroups = new VarLengthExpand(
                toAccount, "MEMBER_OF", Direction.OUT, 1, 3, "g", List.of("Group"), null, POS);

        assertThat(toAccount.input()).isSameAs(device);
        assertThat(toGroups.input()).isSameAs(toAccount);
        assertThat(device.variable()).isEqualTo("d");
        assertThat(device.labels()).containsExactly("Device");
        assertThat(device.propertyAnchor()).isSameAs(propertyAnchor);
        assertThat(toAccount.edgeType()).isEqualTo("CONNECTED_TO");
        assertThat(toAccount.direction()).isEqualTo(Direction.OUT);
        assertThat(toAccount.targetVariable()).isEqualTo("a");
        assertThat(toAccount.targetLabels()).containsExactly("Account");
        assertThat(toAccount.targetPropertyPredicate()).isSameAs(propertyAnchor);
        assertThat(toGroups.minHops()).isEqualTo(1);
        assertThat(toGroups.maxHops()).isEqualTo(3);
        assertThat(toGroups.targetLabels()).containsExactly("Group");
        assertThat(toGroups.targetPropertyPredicate()).isNull();

        final List<LogicalPlan> allNodes = List.of(device, toAccount, toGroups);
        assertThat(allNodes).allSatisfy(node -> assertThat(node.position()).isSameAs(POS));
    }

    @Test
    void graphNodes_requiredFieldsRejectNull() {
        final NodeScan device = new NodeScan("d", List.of("Device"), null, POS);

        assertThatNullPointerException().isThrownBy(() -> new NodeScan(null, List.of(), null, POS));
        assertThatNullPointerException().isThrownBy(() -> new NodeScan("d", null, null, POS));
        assertThatNullPointerException().isThrownBy(() -> new NodeScan("d", List.of(), null, null));
        assertThatNullPointerException().isThrownBy(
                () -> new Expand(null, "T", Direction.OUT, null, "a", List.of(), null, POS));
        assertThatNullPointerException().isThrownBy(
                () -> new Expand(device, "T", null, null, "a", List.of(), null, POS));
        assertThatNullPointerException().isThrownBy(
                () -> new Expand(device, "T", Direction.OUT, null, null, List.of(), null, POS));
        assertThatNullPointerException().isThrownBy(
                () -> new Expand(device, "T", Direction.OUT, null, "a", null, null, POS));
        assertThatNullPointerException().isThrownBy(
                () -> new VarLengthExpand(null, "T", Direction.OUT, 1, 2, "a", List.of(), null, POS));
        assertThatNullPointerException().isThrownBy(
                () -> new VarLengthExpand(device, "T", null, 1, 2, "a", List.of(), null, POS));
        assertThatNullPointerException().isThrownBy(
                () -> new VarLengthExpand(device, "T", Direction.OUT, 1, 2, "a", null, null, POS));
    }

    @Test
    void varLengthExpand_rejectsInvalidHopBounds() {
        final NodeScan device = new NodeScan("d", List.of("Device"), null, POS);

        assertThatIllegalArgumentException().isThrownBy(
                () -> new VarLengthExpand(device, "T", Direction.OUT, -1, 2, "a", List.of(), null, POS));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new VarLengthExpand(device, "T", Direction.OUT, 3, 2, "a", List.of(), null, POS));
    }

    @Test
    void expand_targetLabelsAreDefensivelyCopiedAndImmutable() {
        final NodeScan device = new NodeScan("d", List.of("Device"), null, POS);
        final List<String> mutableLabels = new ArrayList<>(List.of("Account"));

        final Expand expand = new Expand(device, "T", Direction.OUT, null, "a", mutableLabels, null, POS);
        mutableLabels.add("Other");

        assertThat(expand.targetLabels()).containsExactly("Account");
        assertThatThrownBy(() -> expand.targetLabels().add("Other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nodeScan_labelsAreDefensivelyCopiedAndImmutable() {
        final List<String> mutableLabels = new ArrayList<>(List.of("Device"));

        final NodeScan device = new NodeScan("d", mutableLabels, null, POS);
        mutableLabels.add("Sensor");

        assertThat(device.labels()).containsExactly("Device");
        assertThatThrownBy(() -> device.labels().add("Other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Workstream C (docs/graphdb-stroomql-join-implementation-plan.md, Phase P1/P2): {@link GraphJoinSource} is a
     * further, narrower leaf - a Cypher sub-query used as one side of a {@link Join} - builds a usable tree and
     * enforces the same null contracts as every other leaf above.
     */
    @Test
    void graphJoinSource_buildsAUsableJoinOperand() {
        final Scan events = new Scan("e", "Events", POS);
        final GraphJoinSource graphSide = new GraphJoinSource(
                "ident", "from \"CorpGraph\" match (u:User) return u.id as userId", POS);
        final Join joined = new Join(
                events, graphSide, JoinType.INNER,
                List.of(new EquiKey(new QualifiedField("e", "UserId"), new QualifiedField("ident", "userId"))),
                POS);

        assertThat(joined.right()).isSameAs(graphSide);
        assertThat(graphSide.alias()).isEqualTo("ident");
        assertThat(graphSide.cypherText()).contains("return u.id as userId");
        assertThat(graphSide.position()).isSameAs(POS);
    }

    @Test
    void graphJoinSource_requiredFieldsRejectNull() {
        assertThatNullPointerException().isThrownBy(() -> new GraphJoinSource(null, "match (n) return n", POS));
        assertThatNullPointerException().isThrownBy(() -> new GraphJoinSource("ident", null, POS));
        assertThatNullPointerException().isThrownBy(() -> new GraphJoinSource("ident", "match (n) return n", null));
    }
}
