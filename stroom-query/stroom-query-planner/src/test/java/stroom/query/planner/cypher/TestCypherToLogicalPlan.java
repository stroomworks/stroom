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

package stroom.query.planner.cypher;

import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.planner.logical.Direction;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task PoC.3: proves the compiled shapes {@link CypherToLogicalPlan}'s Javadoc promises - a single-hop query
 * compiles to the expected {@code Project(Expand(NodeScan))} tree, a {@code WHERE} predicate lands in a
 * {@link Filter}, and each temporal clause form resolves to the correct {@link TemporalContext} - and that every
 * documented "not in PoC subset" rejection actually throws {@link CypherCompileException}.
 */
class TestCypherToLogicalPlan {

    private static CompiledCypherPlan compile(final String cypher) {
        final AstCypherQuery ast = CypherQueryParser.parse(cypher);
        return new CypherToLogicalPlan().compile(ast);
    }

    @Test
    void singleHop_compilesToProjectExpandNodeScan() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");

        assertThat(compiled.plan()).isInstanceOf(Project.class);
        final Project project = (Project) compiled.plan();
        assertThat(project.fields()).hasSize(1);
        assertThat(project.fields().getFirst().rawExpression()).isEqualTo("${a.id}");
        assertThat(project.fields().getFirst().name()).isEqualTo("a.id");

        assertThat(project.input()).isInstanceOf(Expand.class);
        final Expand expand = (Expand) project.input();
        assertThat(expand.edgeType()).isEqualTo("CONNECTED_TO");
        assertThat(expand.direction()).isEqualTo(Direction.OUT);
        assertThat(expand.targetVariable()).isEqualTo("a");

        assertThat(expand.input()).isInstanceOf(NodeScan.class);
        final NodeScan nodeScan = (NodeScan) expand.input();
        assertThat(nodeScan.variable()).isEqualTo("d");
        assertThat(nodeScan.labels()).containsExactly("Device");
        assertThat(nodeScan.propertyAnchor()).isNotNull();
        assertThat(nodeScan.propertyAnchor().getChildren()).hasSize(1);
        final ExpressionTerm term = (ExpressionTerm) nodeScan.propertyAnchor().getChildren().getFirst();
        assertThat(term.getField()).isEqualTo("id");
        assertThat(term.getCondition()).isEqualTo(Condition.EQUALS);
        assertThat(term.getValue()).isEqualTo("d-42");

        assertThat(compiled.temporalContext()).isNull();
    }

    @Test
    void hopTargetLabelAndProperty_compileOntoTheExpandNode() {
        // Task P3.1: before this, the hop target's own label/property constraint had no slot in the compiled
        // plan at all and was silently dropped - see Expand's Javadoc for why this belongs on Expand, not
        // NodeScan.
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device)-[:CONNECTED_TO]->(a:Account {status: 'active'}) RETURN a.id");

        final Project project = (Project) compiled.plan();
        final Expand expand = (Expand) project.input();
        assertThat(expand.targetLabels()).containsExactly("Account");
        assertThat(expand.targetPropertyPredicate()).isNotNull();
        assertThat(expand.targetPropertyPredicate().getChildren()).hasSize(1);
        final ExpressionTerm term = (ExpressionTerm) expand.targetPropertyPredicate().getChildren().getFirst();
        assertThat(term.getField()).isEqualTo("status");
        assertThat(term.getCondition()).isEqualTo(Condition.EQUALS);
        assertThat(term.getValue()).isEqualTo("active");
    }

    @Test
    void hopTargetWithNoLabelOrProperty_compilesToEmptyConstraint() {
        final CompiledCypherPlan compiled = compile("MATCH (d:Device)-[:CONNECTED_TO]->(a) RETURN a.id");

        final Project project = (Project) compiled.plan();
        final Expand expand = (Expand) project.input();
        assertThat(expand.targetLabels()).isEmpty();
        assertThat(expand.targetPropertyPredicate()).isNull();
    }

    @Test
    void bareAnchorWithNoHop_compilesToProjectNodeScan() {
        final CompiledCypherPlan compiled = compile("MATCH (n:Account) RETURN n");

        assertThat(compiled.plan()).isInstanceOf(Project.class);
        assertThat(((Project) compiled.plan()).input()).isInstanceOf(NodeScan.class);
    }

    @Test
    void whereClause_compilesToFilterBetweenNodeScanAndProject() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) WHERE a.balance > 100 RETURN a.id");

        final Project project = (Project) compiled.plan();
        assertThat(project.input()).isInstanceOf(Filter.class);
        final Filter filter = (Filter) project.input();
        assertThat(filter.input()).isInstanceOf(NodeScan.class);

        final ExpressionOperator where = filter.wherePredicate();
        assertThat(where.getChildren()).hasSize(1);
        final ExpressionTerm term = (ExpressionTerm) where.getChildren().getFirst();
        assertThat(term.getField()).isEqualTo("a.balance");
        assertThat(term.getCondition()).isEqualTo(Condition.GREATER_THAN);
        assertThat(term.getValue()).isEqualTo("100");
    }

    @Test
    void orderByAndLimit_wrapTheProject() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) RETURN a.id ORDER BY a.id LIMIT 10");

        assertThat(compiled.plan()).isInstanceOf(Limit.class);
        final Limit limit = (Limit) compiled.plan();
        assertThat(limit.values()).containsExactly(10L);
        assertThat(limit.input()).isInstanceOf(Sort.class);
        final Sort sort = (Sort) limit.input();
        assertThat(sort.keys()).hasSize(1);
        assertThat(sort.keys().getFirst().field().field()).isEqualTo("a.id");
        assertThat(sort.input()).isInstanceOf(Project.class);
    }

    @Test
    void countStar_compilesToFunctionCallProjectField() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN count(*) AS total");

        final Project project = (Project) compiled.plan();
        assertThat(project.fields().getFirst().rawExpression()).isEqualTo("count()");
        assertThat(project.fields().getFirst().alias()).isEqualTo("total");
    }

    @Test
    void sumOfProperty_compilesToFunctionCallOverFieldReference() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN sum(a.balance) AS total");

        final Project project = (Project) compiled.plan();
        assertThat(project.fields().getFirst().rawExpression()).isEqualTo("sum(${a.balance})");
    }

    @Test
    void asOfClause_resolvesToAsOfTemporalContext() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) AS OF datetime('2026-01-01T00:00:00Z') RETURN a.id");

        assertThat(compiled.temporalContext()).isNotNull();
        assertThat(compiled.temporalContext().mode()).isEqualTo(TemporalContext.Mode.AS_OF);
        assertThat(compiled.temporalContext().instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(compiled.temporalContext().from()).isNull();
        assertThat(compiled.temporalContext().to()).isNull();
    }

    @Test
    void aroundClause_resolvesToAnInclusiveWindowCentredOnTheInstant() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                + "AROUND datetime('2026-07-01T09:00:00Z') +/- duration('PT1H') RETURN a.id");

        final TemporalContext temporal = compiled.temporalContext();
        assertThat(temporal.mode()).isEqualTo(TemporalContext.Mode.AROUND);
        assertThat(temporal.from()).isEqualTo(Instant.parse("2026-07-01T08:00:00Z"));
        assertThat(temporal.to()).isEqualTo(Instant.parse("2026-07-01T10:00:00Z"));
    }

    @Test
    void betweenClause_resolvesToTheExactWindowGiven() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) BETWEEN datetime('2026-01-01T00:00:00Z') "
                + "AND datetime('2026-02-01T00:00:00Z') RETURN a.id");

        final TemporalContext temporal = compiled.temporalContext();
        assertThat(temporal.mode()).isEqualTo(TemporalContext.Mode.BETWEEN);
        assertThat(temporal.from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(temporal.to()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void twoHopChain_compilesToNestedExpandsInSourceOrder() {
        // Task P3.2: before this, any pattern with more than one hop was rejected outright.
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)<-[:OWNS]-(o:Owner) "
                + "RETURN a.id, o.name");

        final Project project = (Project) compiled.plan();
        assertThat(project.input()).isInstanceOf(Expand.class);
        final Expand secondHop = (Expand) project.input();
        assertThat(secondHop.edgeType()).isEqualTo("OWNS");
        assertThat(secondHop.direction()).isEqualTo(Direction.IN);
        assertThat(secondHop.targetVariable()).isEqualTo("o");

        assertThat(secondHop.input()).isInstanceOf(Expand.class);
        final Expand firstHop = (Expand) secondHop.input();
        assertThat(firstHop.edgeType()).isEqualTo("CONNECTED_TO");
        assertThat(firstHop.direction()).isEqualTo(Direction.OUT);
        assertThat(firstHop.targetVariable()).isEqualTo("a");

        assertThat(firstHop.input()).isInstanceOf(NodeScan.class);
        assertThat(((NodeScan) firstHop.input()).variable()).isEqualTo("d");
    }

    @Test
    void threeHopChain_compilesToThreeNestedExpands() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device)-[:CONNECTED_TO]->(a:Account)-[:OWNED_BY]->(p:Person)-[:WORKS_AT]->(c:Company) "
                + "RETURN c.name");

        final Project project = (Project) compiled.plan();
        final Expand thirdHop = (Expand) project.input();
        assertThat(thirdHop.edgeType()).isEqualTo("WORKS_AT");
        final Expand secondHop = (Expand) thirdHop.input();
        assertThat(secondHop.edgeType()).isEqualTo("OWNED_BY");
        final Expand firstHop = (Expand) secondHop.input();
        assertThat(firstHop.edgeType()).isEqualTo("CONNECTED_TO");
        assertThat(firstHop.input()).isInstanceOf(NodeScan.class);
    }

    @Test
    void variableLengthPath_compilesToVarLengthExpandOverTheAnchor() {
        // Task P3.3: before this, any variable-length hop was rejected outright.
        final CompiledCypherPlan compiled = compile(
                "MATCH (u:User {id: 'u-1'})-[:MEMBER_OF*1..3]->(g:Group) RETURN g.id");

        final Project project = (Project) compiled.plan();
        assertThat(project.input()).isInstanceOf(VarLengthExpand.class);
        final VarLengthExpand varLengthExpand = (VarLengthExpand) project.input();
        assertThat(varLengthExpand.edgeType()).isEqualTo("MEMBER_OF");
        assertThat(varLengthExpand.direction()).isEqualTo(Direction.OUT);
        assertThat(varLengthExpand.minHops()).isEqualTo(1);
        assertThat(varLengthExpand.maxHops()).isEqualTo(3);
        assertThat(varLengthExpand.targetVariable()).isEqualTo("g");
        assertThat(varLengthExpand.targetLabels()).containsExactly("Group");
        assertThat(varLengthExpand.input()).isInstanceOf(NodeScan.class);
    }

    @Test
    void variableLengthPathWithNoMinBound_defaultsMinHopsToOne() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (u:User {id: 'u-1'})-[:MEMBER_OF*..3]->(g:Group) RETURN g.id");

        final VarLengthExpand varLengthExpand = (VarLengthExpand) ((Project) compiled.plan()).input();
        assertThat(varLengthExpand.minHops()).isEqualTo(1);
        assertThat(varLengthExpand.maxHops()).isEqualTo(3);
    }

    @Test
    void variableLengthHopChainedWithAnotherHop_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (u:User)-[:MEMBER_OF*1..3]->(g:Group)-[:HAS_OWNER]->(o:Owner) RETURN o.id");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("variable-length");
    }

    @Test
    void skipClause_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse("MATCH (a:Account) RETURN a.id SKIP 5");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("SKIP");
    }

    @Test
    void withClause_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (a:Account) WITH a ORDER BY a.id RETURN a.id");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class);
    }

    @Test
    void comparingTwoFieldReferences_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (a:Account) WHERE a.balance = a.cap RETURN a.id");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("comparing two field references");
    }
}
