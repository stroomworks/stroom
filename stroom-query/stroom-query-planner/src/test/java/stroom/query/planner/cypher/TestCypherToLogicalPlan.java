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
import stroom.query.grammar.ast.cypher.AstAggregateFunction;
import stroom.query.grammar.ast.cypher.AstComparisonOp;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.planner.logical.Direction;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
    void whereStartsWith_compilesToStartsWithCondition() {
        assertWhereCondition("MATCH (a:Account) WHERE a.name STARTS WITH 'Pow' RETURN a.id",
                "a.name", Condition.STARTS_WITH, "Pow");
    }

    @Test
    void whereContains_compilesToContainsCondition() {
        assertWhereCondition("MATCH (a:Account) WHERE a.name CONTAINS 'ow' RETURN a.id",
                "a.name", Condition.CONTAINS, "ow");
    }

    @Test
    void whereEndsWith_compilesToEndsWithCondition() {
        assertWhereCondition("MATCH (a:Account) WHERE a.name ENDS WITH 'ell' RETURN a.id",
                "a.name", Condition.ENDS_WITH, "ell");
    }

    @Test
    void whereRegex_compilesToMatchesRegexCondition() {
        assertWhereCondition("MATCH (a:Account) WHERE a.name =~ 'Pow.*' RETURN a.id",
                "a.name", Condition.MATCHES_REGEX, "Pow.*");
    }

    @Test
    void whereRegex_tooLong_throwsCompileException() {
        final String longPattern = "a".repeat(1001);
        assertThatThrownBy(() -> compile(
                "MATCH (a:Account) WHERE a.name =~ '" + longPattern + "' RETURN a.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("regular expression is too long");
    }

    private void assertWhereCondition(final String cypher,
                                      final String field,
                                      final Condition condition,
                                      final String value) {
        final ExpressionTerm term = whereTerm(cypher);
        assertThat(term.getField()).isEqualTo(field);
        assertThat(term.getCondition()).isEqualTo(condition);
        assertThat(term.getValue()).isEqualTo(value);
    }

    private ExpressionTerm whereTerm(final String cypher) {
        final CompiledCypherPlan compiled = compile(cypher);
        final Project project = (Project) compiled.plan();
        assertThat(project.input()).isInstanceOf(Filter.class);
        final Filter filter = (Filter) project.input();
        return (ExpressionTerm) filter.wherePredicate().getChildren().getFirst();
    }

    @Test
    void whereIn_compilesToInConditionWithCommaJoinedValue() {
        assertWhereCondition("MATCH (a:Account) WHERE a.id IN ['account-a', 'account-b'] RETURN a.id",
                "a.id", Condition.IN, "account-a, account-b");
    }

    @Test
    void whereInEmptyList_compilesToInConditionWithEmptyValue() {
        assertWhereCondition("MATCH (a:Account) WHERE a.id IN [] RETURN a.id",
                "a.id", Condition.IN, "");
    }

    @Test
    void whereIsNull_compilesToIsNullConditionWithNoValue() {
        final ExpressionTerm term = whereTerm("MATCH (a:Account) WHERE a.closed IS NULL RETURN a.id");
        assertThat(term.getField()).isEqualTo("a.closed");
        assertThat(term.getCondition()).isEqualTo(Condition.IS_NULL);
        assertThat(term.getValue()).isNull();
    }

    @Test
    void whereIsNotNull_compilesToIsNotNullCondition() {
        final ExpressionTerm term = whereTerm("MATCH (a:Account) WHERE a.closed IS NOT NULL RETURN a.id");
        assertThat(term.getCondition()).isEqualTo(Condition.IS_NOT_NULL);
    }

    @Test
    void whereInWithNonListRightSide_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) WHERE a.id IN 'account-a' RETURN a.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("right side of IN must be a literal list");
    }

    @Test
    void whereBareVariableIsNull_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) WHERE a IS NULL RETURN a.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("IS NULL is only supported on a property");
    }

    @Test
    void limitWithoutOrderBy_wrapsTheProject() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN a.id LIMIT 10");

        assertThat(compiled.plan()).isInstanceOf(Limit.class);
        final Limit limit = (Limit) compiled.plan();
        assertThat(limit.values()).containsExactly(10L);
        assertThat(limit.input()).isInstanceOf(Project.class);
    }

    @Test
    void orderBy_compilesToASortWrappingTheProject() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN a.id ORDER BY a.id");

        assertThat(compiled.plan()).isInstanceOf(Sort.class);
        final Sort sort = (Sort) compiled.plan();
        assertThat(sort.keys()).hasSize(1);
        // The pattern variable "a" is the alias, "id" the field - the split every planner consumer relies on.
        assertThat(sort.keys().getFirst().field().alias()).isEqualTo("a");
        assertThat(sort.keys().getFirst().field().field()).isEqualTo("id");
        assertThat(sort.keys().getFirst().descending()).isFalse();
        assertThat(sort.input()).isInstanceOf(Project.class);
    }

    @Test
    void orderByDescCombinedWithLimit_compilesToLimitWrappingSortWrappingProject() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) RETURN a.id ORDER BY a.id DESC LIMIT 10");

        assertThat(compiled.plan()).isInstanceOf(Limit.class);
        final Limit limit = (Limit) compiled.plan();
        assertThat(limit.values()).containsExactly(10L);
        assertThat(limit.input()).isInstanceOf(Sort.class);
        final Sort sort = (Sort) limit.input();
        assertThat(sort.keys().getFirst().descending()).isTrue();
        assertThat(sort.input()).isInstanceOf(Project.class);
    }

    @Test
    void returnDistinct_setsTheDistinctFlagOnTheCompiledPlan() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN DISTINCT a.id");

        assertThat(compiled.distinct()).isTrue();
        // DISTINCT is not a plan node (the sealed shared IR has none); the plan itself is just the Project.
        assertThat(compiled.plan()).isInstanceOf(Project.class);
    }

    @Test
    void returnWithoutDistinct_leavesTheDistinctFlagFalse() {
        assertThat(compile("MATCH (a:Account) RETURN a.id").distinct()).isFalse();
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

    // ------------------------------------------------------------------------------------------------------
    // aggregation (Task 1.1 of docs/graphdb-analytic-functions-implementation-plan.md)
    // ------------------------------------------------------------------------------------------------------

    @Test
    void nonAggregatedReturn_leavesAggregationNull() {
        assertThat(compile("MATCH (a:Account) RETURN a.id").aggregation()).isNull();
    }

    @Test
    void countStarAlone_compilesToASingleAggregateColumnWithNoGroupKeys() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN count(*) AS total");

        assertThat(compiled.aggregation()).isNotNull();
        assertThat(compiled.aggregation().columns()).containsExactly(
                new AggregateColumn(AstAggregateFunction.COUNT, null, true, false, false));
    }

    @Test
    void groupKeyPlusCount_compilesToGroupKeyColumnThenAggregateColumn() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer) RETURN o.surname, count(c) AS caseload");

        assertThat(compiled.aggregation()).isNotNull();
        assertThat(compiled.aggregation().columns()).containsExactly(
                new GroupKeyColumn("o.surname"),
                new AggregateColumn(AstAggregateFunction.COUNT, null, false, true, false));

        // The aggregation columns align 1:1, in order, with the compiled Project's fields.
        final Project project = (Project) compiled.plan();
        assertThat(project.fields()).hasSize(2);
        assertThat(project.fields().get(0).name()).isEqualTo("o.surname");
        assertThat(project.fields().get(1).name()).isEqualTo("caseload");
    }

    @Test
    void sumOverProperty_compilesToAggregateColumnWithArgRowKey() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN sum(a.balance) AS total");

        assertThat(compiled.aggregation().columns()).containsExactly(
                new AggregateColumn(AstAggregateFunction.SUM, "a.balance", false, false, false));
    }

    @Test
    void countDistinctOverProperty_setsTheDistinctFlag() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer) RETURN o.surname, count(DISTINCT c.type) AS types");

        assertThat(compiled.aggregation().columns()).containsExactly(
                new GroupKeyColumn("o.surname"),
                new AggregateColumn(AstAggregateFunction.COUNT, "c.type", false, false, true));
    }

    @Test
    void countDistinct_getsADistinctColumnNameToAvoidCollisionWithPlainCount() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) RETURN count(a.balance), count(DISTINCT a.balance)");
        final Project project = (Project) compiled.plan();
        assertThat(project.fields()).hasSize(2);
        assertThat(project.fields().get(0).name()).isEqualTo("count(a.balance)");
        assertThat(project.fields().get(1).name()).isEqualTo("count(distinct a.balance)");
    }

    @Test
    void distinctOnNonCountAggregate_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) RETURN sum(DISTINCT a.balance)"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("DISTINCT is only supported on count");
    }

    @Test
    void countDistinctStar_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) RETURN count(DISTINCT *)"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("count(DISTINCT *)");
    }

    @Test
    void countDistinctBareVariable_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer) RETURN o.surname, count(DISTINCT c)"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("whole");
    }

    @Test
    void collectOverProperty_compilesToCollectAggregateColumn() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (o:Officer)-[:INVESTIGATED]->(c:Crime) RETURN o.id, collect(c.type) AS types");
        assertThat(compiled.aggregation().columns()).containsExactly(
                new GroupKeyColumn("o.id"),
                new AggregateColumn(AstAggregateFunction.COLLECT, "c.type", false, false, false));
    }

    @Test
    void collectDistinct_setsTheDistinctFlag() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (o:Officer)-[:INVESTIGATED]->(c:Crime) RETURN o.id, collect(DISTINCT c.type) AS types");
        assertThat(compiled.aggregation().columns()).containsExactly(
                new GroupKeyColumn("o.id"),
                new AggregateColumn(AstAggregateFunction.COLLECT, "c.type", false, false, true));
    }

    @Test
    void collectStar_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) RETURN collect(*)"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("collect(*)");
    }

    @Test
    void collectBareVariable_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (o:Officer)-[:INVESTIGATED]->(c:Crime) RETURN o.id, collect(c)"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("whole");
    }

    @Test
    void fieldVsFieldOnly_producesAFieldComparisonAndNoFilter() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account)-[:TRANSFER]->(b:Account) WHERE a.balance > b.balance RETURN a.id");
        assertThat(compiled.fieldComparisons()).containsExactly(
                new FieldComparison("a.balance", AstComparisonOp.GT, "b.balance"));
        // The only WHERE term was field-vs-field, so no literal Filter is inserted.
        assertThat(((Project) compiled.plan()).input()).isNotInstanceOf(Filter.class);
    }

    @Test
    void fieldVsFieldAndedWithLiteral_splitsBetweenFilterAndFieldComparison() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account)-[:TRANSFER]->(b:Account) "
                + "WHERE a.balance > b.balance AND a.active = true RETURN a.id");
        assertThat(compiled.fieldComparisons()).containsExactly(
                new FieldComparison("a.balance", AstComparisonOp.GT, "b.balance"));
        final Filter filter = (Filter) ((Project) compiled.plan()).input();
        final ExpressionTerm term = (ExpressionTerm) filter.wherePredicate().getChildren().getFirst();
        assertThat(term.getField()).isEqualTo("a.active");
        assertThat(term.getCondition()).isEqualTo(Condition.EQUALS);
    }

    @Test
    void fieldVsFieldInsideOr_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (a:Account)-[:TRANSFER]->(b:Account) "
                + "WHERE a.balance > b.balance OR a.active = true RETURN a.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("top-level conjunct");
    }

    @Test
    void fieldVsFieldWithStringOperator_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (a:Account)-[:TRANSFER]->(b:Account) WHERE a.name STARTS WITH b.prefix RETURN a.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("not string operators");
    }

    @Test
    void optionalMatch_lowersToAnOptionalExpand() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (p:Person {id: 'p1'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.id");
        final Expand expand = (Expand) ((Project) compiled.plan()).input();
        assertThat(expand.optional()).isTrue();
        assertThat(expand.targetVariable()).isEqualTo("c");
    }

    @Test
    void countOverOptionalVariable_countsBoundRowsViaTheMarkerKey() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (p:Person {id: 'p1'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime) RETURN p.id, count(c) AS n");
        assertThat(compiled.aggregation().columns()).containsExactly(
                new GroupKeyColumn("p.id"),
                new AggregateColumn(
                        AstAggregateFunction.COUNT, OptionalMatchSupport.boundKey("c"), false, false, false));
    }

    @Test
    void leadingOptionalMatch_throwsCompileException() {
        assertThatThrownBy(() -> compile("OPTIONAL MATCH (p:Person {id: 'p1'})-[:PARTY_TO]->(c:Crime) RETURN c.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("cannot begin with OPTIONAL MATCH");
    }

    @Test
    void twoMandatoryMatches_throwCompileException() {
        assertThatThrownBy(() -> compile("MATCH (p:Person {id: 'p1'}) MATCH (q:Person) RETURN p.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("OPTIONAL MATCH");
    }

    @Test
    void optionalMatchNotExtendingTerminalVariable_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (p:Person {id: 'p1'}) OPTIONAL MATCH (x)-[:PARTY_TO]->(c:Crime) RETURN p.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("final variable");
    }

    @Test
    void multiHopOptionalMatch_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (p:Person {id: 'p1'}) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime)-[:AT]->(l:Location) "
                + "RETURN p.id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("exactly one hop");
    }

    @Test
    void scalarFunction_rendersToMappedStroomExpression() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN toUpper(a.name)");
        // toUpper is aliased to Stroom's upperCase; the property becomes a ${...} field reference.
        assertThat(((Project) compiled.plan()).fields().getFirst().rawExpression())
                .isEqualTo("upperCase(${a.name})");
    }

    @Test
    void nestedScalarFunctions_renderRecursively() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) RETURN concat(upperCase(a.first), lowerCase(a.last))");
        assertThat(((Project) compiled.plan()).fields().getFirst().rawExpression())
                .isEqualTo("concat(upperCase(${a.first}), lowerCase(${a.last}))");
    }

    @Test
    void unknownFunction_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) RETURN bogusFn(a.name)"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("not available in a Cypher RETURN");
    }

    @Test
    void aggregateAsFunctionArgument_throwsCompileException() {
        assertThatThrownBy(() -> compile("MATCH (a:Account) RETURN upperCase(count(a))"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("aggregate or before()/after()");
    }

    @Test
    void cypherSubstring_adaptsLengthToAnEndIndex() {
        // Cypher substring(s, start, length) -> Stroom substring(s, start, start + length).
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN substring(a.name, 1, 3)"))
                .isEqualTo("substring(${a.name}, 1, add(1, 3))");
        // Stroom's own end-index substring stays reachable under stroom_substring (unadapted).
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN stroom_substring(a.name, 1, 3)"))
                .isEqualTo("substring(${a.name}, 1, 3)");
    }

    @Test
    void cypherLeftRightSize_composeFromStroomFunctions() {
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN left(a.name, 2)"))
                .isEqualTo("substring(${a.name}, 0, 2)");
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN right(a.name, 2)"))
                .isEqualTo("substring(${a.name}, add(stringLength(${a.name}), negate(2)), stringLength(${a.name}))");
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN size(a.name)"))
                .isEqualTo("stringLength(${a.name})");
    }

    @Test
    void cypherCoalesce_composesFromIfIsNull() {
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN coalesce(a.x, a.y, 'none')"))
                .isEqualTo("if(isNull(${a.x}), if(isNull(${a.y}), 'none', ${a.y}), ${a.x})");
    }

    @Test
    void cypherCeilAlias_mapsToCeiling() {
        assertThat(renderedReturnExpression("MATCH (a:Account) RETURN ceil(a.x)"))
                .isEqualTo("ceiling(${a.x})");
    }

    private String renderedReturnExpression(final String cypher) {
        return ((Project) compile(cypher).plan()).fields().getFirst().rawExpression();
    }

    @Test
    void unaliasedCountStar_defaultsToACleanFunctionStarName() {
        // Code-review fix: previously an unaliased aggregate's default name was renderExpression's "${...}"-laden
        // text (e.g. "count()"), unusable as a FieldIndex/column identifier - see defaultAggregateName's Javadoc.
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN count(*)");

        assertThat(((Project) compiled.plan()).fields().getFirst().name()).isEqualTo("count(*)");
    }

    @Test
    void unaliasedSumOverProperty_defaultsToACleanFunctionArgumentName() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN sum(a.balance)");

        assertThat(((Project) compiled.plan()).fields().getFirst().name()).isEqualTo("sum(a.balance)");
    }

    @Test
    void sumOfStar_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse("MATCH (a:Account) RETURN sum(*) AS total");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("sum(*)");
    }

    @Test
    void sumOfBareVariable_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse("MATCH (a:Account) RETURN sum(a) AS total");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("whole matched node/edge");
    }

    @Test
    void countOfBareVariable_isAcceptedAsEquivalentToCountStar() {
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) RETURN count(a) AS total");

        assertThat(compiled.aggregation().columns()).containsExactly(
                new AggregateColumn(AstAggregateFunction.COUNT, null, false, true, false));
    }

    @Test
    void bareVariableGroupKey_throwsNotInPoCSubset() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (a:Account) RETURN a, count(*) AS total");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("GROUP BY key");
    }

    @Test
    void orderByAggregateAlias_isAcceptedWhenReturned() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer) "
                + "RETURN o.surname, count(c) AS caseload ORDER BY caseload DESC LIMIT 5");

        assertThat(compiled.aggregation()).isNotNull();
        assertThat(compiled.plan()).isInstanceOf(Limit.class);
    }

    @Test
    void orderByNonReturnedColumn_throwsNotInPoCSubsetOnceAggregated() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer) "
                + "RETURN o.surname, count(c) AS caseload ORDER BY c.type");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("not a returned column");
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
    void betweenClause_rejectsAReversedWindow() {
        final AstCypherQuery reversed = CypherQueryParser.parse(
                "MATCH (a:Account) BETWEEN datetime('2026-02-01T00:00:00Z') "
                + "AND datetime('2026-01-01T00:00:00Z') RETURN a.id");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(reversed))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("from <= to");
    }

    @Test
    void aroundClause_rejectsANegativeDuration() {
        final AstCypherQuery negativeDuration = CypherQueryParser.parse(
                "MATCH (a:Account) AROUND datetime('2026-07-01T09:00:00Z') "
                + "+/- duration('-PT1H') RETURN a.id");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(negativeDuration))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("from <= to");
    }

    @Test
    void diffClause_resolvesToDiffContext_notATemporalContext() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') RETURN changeKind, a.id");

        assertThat(compiled.temporalContext()).isNull();
        assertThat(compiled.diffContext()).isNotNull();
        assertThat(compiled.diffContext().baseline()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(compiled.diffContext().comparison()).isEqualTo(Instant.parse("2026-07-08T00:00:00Z"));
    }

    @Test
    void diffClause_rejectsEqualOrReversedInstants() {
        final AstCypherQuery equal = CypherQueryParser.parse(
                "MATCH (a:Account) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-01T00:00:00Z') RETURN changeKind, a.id");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(equal))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("baseline < comparison");

        final AstCypherQuery reversed = CypherQueryParser.parse(
                "MATCH (a:Account) DIFF FROM datetime('2026-07-08T00:00:00Z') "
                + "TO datetime('2026-07-01T00:00:00Z') RETURN changeKind, a.id");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(reversed))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("baseline < comparison");
    }

    @Test
    void diffClause_rejectsVariableLengthPattern() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO*1..3]->(a:Account) "
                + "DIFF FROM datetime('2026-07-01T00:00:00Z') TO datetime('2026-07-08T00:00:00Z') "
                + "RETURN changeKind, a.id");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("variable-length");
    }

    @Test
    void beforeAccessorOutsideDiff_isRejected() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (u:User) AS OF datetime('2026-07-01T00:00:00Z') RETURN before(u.department)");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("DIFF");
    }

    @Test
    void changeKindOutsideDiff_isRejected() {
        final AstCypherQuery ast = CypherQueryParser.parse("MATCH (a:Account) RETURN changeKind");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("DIFF");
    }

    @Test
    void diffProjection_rendersChangeKindAndBeforeAfterAccessors() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account {id: 'x'}) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') "
                + "RETURN changeKind, a.id, before(a.balance), after(a.balance)");

        final Project project = (Project) compiled.plan();
        assertThat(project.fields())
                .extracting(ProjectField::name, ProjectField::rawExpression)
                .containsExactly(
                        tuple("changeKind", "${changeKind}"),
                        tuple("a.id", "${a.id}"),
                        tuple("before(a.balance)", "${before.a.balance}"),
                        tuple("after(a.balance)", "${after.a.balance}"));
    }

    @Test
    void diffClause_rejectsDiffConstructInWhere() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (a:Account {id: 'x'}) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') WHERE changeKind = 'ADDED' RETURN a.id");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("not supported in this version");
    }

    @Test
    void diffClause_rejectsAggregateInReturn() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (a:Account {id: 'x'}) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') RETURN count(*)");
        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("diff-aggregation");
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
    void withHaving_compilesToAStageOneAggregationAndASecondStage() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (p:Person {id: 'p1'})-[:PARTY_TO]->(c:Crime) "
                + "WITH p.id AS pid, count(c) AS n WHERE n > 1 RETURN pid, n");
        // Stage one is the WITH's projection/aggregation (group by pid, count).
        assertThat(compiled.aggregation()).isNotNull();
        // Stage two carries the WITH's columns, the HAVING, and the final RETURN projection.
        assertThat(compiled.secondStage()).isNotNull();
        assertThat(compiled.secondStage().stageColumns()).containsExactly("pid", "n");
        assertThat(compiled.secondStage().having()).isNotNull();
        assertThat(compiled.secondStage().finalFields()).extracting(f -> f.name()).containsExactly("pid", "n");
    }

    @Test
    void referenceToDroppedVariableAfterWith_throwsCompileException() {
        // c is dropped by the WITH (only pid, n survive), so referencing c.type in the RETURN is out of scope.
        assertThatThrownBy(() -> compile(
                "MATCH (p:Person {id: 'p1'})-[:PARTY_TO]->(c:Crime) "
                + "WITH p.id AS pid, count(c) AS n RETURN pid, c.type"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("out of scope");
    }

    @Test
    void unknownColumnAfterWith_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (p:Person {id: 'p1'})-[:PARTY_TO]->(c:Crime) "
                + "WITH p.id AS pid, count(c) AS n RETURN pid, bogus"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("not a column produced by the WITH");
    }

    @Test
    void unaliasedWithItem_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (p:Person {id: 'p1'})-[:PARTY_TO]->(c:Crime) WITH p.id, count(c) AS n RETURN n"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("must be aliased");
    }

    @Test
    void orderByOnWith_throwsCompileException() {
        assertThatThrownBy(() -> compile(
                "MATCH (a:Account) WITH a.id AS id ORDER BY id RETURN id"))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("ORDER BY / SKIP / LIMIT on a WITH");
    }

    @Test
    void comparingTwoFieldReferences_compilesToAFieldComparison() {
        // Field-vs-field is now supported as a top-level WHERE conjunct (Phase 5), carried as a FieldComparison
        // rather than rejected.
        final CompiledCypherPlan compiled = compile("MATCH (a:Account) WHERE a.balance = a.cap RETURN a.id");
        assertThat(compiled.fieldComparisons()).containsExactly(
                new FieldComparison("a.balance", AstComparisonOp.EQ, "a.cap"));
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN GRAPH (Workstream D): the element-row output mode.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void returnGraph_compilesToProjectWithTheFrozenElementRowSchema() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN GRAPH");

        assertThat(compiled.returnGraph()).isTrue();
        assertThat(compiled.diffContext()).isNull();
        assertThat(compiled.aggregation()).isNull();
        assertThat(compiled.distinct()).isFalse();

        assertThat(compiled.plan()).isInstanceOf(Project.class);
        final Project project = (Project) compiled.plan();
        assertThat(project.fields())
                .extracting(ProjectField::name)
                .containsExactly("kind", "id", "labels", "source", "target", "properties");
        assertThat(project.fields()).allMatch(ProjectField::visible);

        // The pattern still compiles underneath, unaffected by the terminal RETURN form.
        assertThat(project.input()).isInstanceOf(Expand.class);
        final Expand expand = (Expand) project.input();
        assertThat(expand.input()).isInstanceOf(NodeScan.class);
    }

    @Test
    void diffWithReturnGraph_appendsChangeKindAsA7thColumnAndSetsDiffContext() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                + "DIFF FROM datetime('2026-07-01T00:00:00Z') TO datetime('2026-07-08T00:00:00Z') "
                + "RETURN GRAPH");

        assertThat(compiled.returnGraph()).isTrue();
        assertThat(compiled.diffContext()).isNotNull();
        assertThat(compiled.diffContext().baseline()).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(compiled.temporalContext()).isNull();

        final Project project = (Project) compiled.plan();
        assertThat(project.fields())
                .extracting(ProjectField::name)
                .containsExactly("kind", "id", "labels", "source", "target", "properties", "changeKind");
    }

    @Test
    void returnGraphWithWhere_compilesToFilterBetweenNodeScanAndProject() {
        final CompiledCypherPlan compiled = compile(
                "MATCH (a:Account) WHERE a.balance > 100 RETURN GRAPH");

        final Project project = (Project) compiled.plan();
        assertThat(project.input()).isInstanceOf(Filter.class);
        final Filter filter = (Filter) project.input();
        final ExpressionTerm term = (ExpressionTerm) filter.wherePredicate().getChildren().getFirst();
        assertThat(term.getField()).isEqualTo("a.balance");
    }

    @Test
    void returnGraph_rejectsVariableLengthPattern() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (u:User {id: 'u-1'})-[:MEMBER_OF*1..3]->(g:Group) RETURN GRAPH");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("variable-length");
    }

    @Test
    void diffWithReturnGraph_rejectsVariableLengthPattern() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO*1..3]->(a:Account) "
                + "DIFF FROM datetime('2026-07-01T00:00:00Z') TO datetime('2026-07-08T00:00:00Z') "
                + "RETURN GRAPH");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("variable-length");
    }

    @Test
    void diffWithReturnGraph_rejectsDiffConstructInWhere() {
        final AstCypherQuery ast = CypherQueryParser.parse(
                "MATCH (a:Account {id: 'x'}) DIFF FROM datetime('2026-07-01T00:00:00Z') "
                + "TO datetime('2026-07-08T00:00:00Z') WHERE changeKind = 'ADDED' RETURN GRAPH");

        assertThatThrownBy(() -> new CypherToLogicalPlan().compile(ast))
                .isInstanceOf(CypherCompileException.class)
                .hasMessageContaining("not supported in this version");
    }

    @Test
    void plainReturnItems_leaveTheReturnGraphFlagFalse() {
        assertThat(compile("MATCH (a:Account) RETURN a.id").returnGraph()).isFalse();
    }
}
