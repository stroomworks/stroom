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

package stroom.query.planner.bind;

import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.parse.StroomQlParser;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.GraphJoinSource;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.FieldInfoSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2.2: table-driven {@code (query, fake field metadata) -> expected LogicalPlan | expected BindException}
 * tests for {@link Binder}.
 */
class TestBinder {

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FakeFieldInfoSource(Map.of(
            "Events", List.of(
                    QueryField.builder().fldName("StreamId").fldType(FieldType.LONG).build(),
                    QueryField.builder().fldName("EventTime").fldType(FieldType.DATE).build(),
                    QueryField.builder().fldName("UserId").fldType(FieldType.LONG).domainType("User.id").build()),
            "Users", List.of(
                    QueryField.builder().fldName("Id").fldType(FieldType.LONG).domainType("User.id").build(),
                    QueryField.builder().fldName("Name").fldType(FieldType.TEXT).domainType("Person.name").build())));

    private LogicalPlan bind(final String query) {
        final AstQuery ast = StroomQlParser.parse(query);
        return new Binder(FIELD_INFO_SOURCE).bind(ast);
    }

    @Test
    void simpleQuery_bindsToFilterOverScan() {
        final LogicalPlan plan = bind("from \"Events\" where StreamId = 1 select StreamId");

        assertThat(plan).isInstanceOf(Project.class);
        final Project project = (Project) plan;
        assertThat(project.input()).isInstanceOf(Filter.class);
        final Filter filter = (Filter) project.input();
        assertThat(filter.input()).isEqualTo(new Scan("Events", "Events", filter.input().position()));
        assertThat(filter.filterPredicate()).isNull();
        assertThat(filter.wherePredicate()).isEqualTo(
                ExpressionOperator.builder()
                        .children(List.of(ExpressionTerm.builder()
                                .field("StreamId")
                                .condition(Condition.EQUALS)
                                .value("1")
                                .build()))
                        .build());
    }

    @Test
    void whereOnlyQuery_filterNodeReportsTheWhereClausePosition_notTheFromClause() {
        // `from "Events" where StreamId = 1 ...` - the `where` keyword is at 0-based column 14 on line 1. The
        // Filter node must report that, not the `from` clause position (which would misdirect an EXPLAIN/error).
        final LogicalPlan plan = bind("from \"Events\" where StreamId = 1 select StreamId");
        final Filter filter = (Filter) ((Project) plan).input();

        assertThat(filter.position().line()).isEqualTo(1);
        assertThat(filter.position().column()).isEqualTo(14);
        // ...and specifically NOT the Scan's (from-clause) position.
        assertThat(filter.position()).isNotEqualTo(filter.input().position());
    }

    @Test
    void unknownField_throwsBindException() {
        assertThatThrownBy(() -> bind("from \"Events\" where Bogus = 1 select StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown field 'Bogus'");
    }

    @Test
    void unknownFieldOnKnownAlias_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e join \"Users\" as u on e.UserId = u.Id select e.Bogus"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown field 'Bogus' on 'e'");
    }

    @Test
    void unknownFieldOnKnownJoinAlias_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e join \"Users\" as u on e.Bogus = u.Id select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown field 'Bogus' on 'e'");
    }

    @Test
    void ambiguousUnqualifiedField_throwsBindException() {
        // Both Events and Users expose a field usable unqualified only if unique; give them a shared name via a
        // self-join of Events (alias e and f) so an unqualified StreamId is present on two sources.
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e join \"Events\" as f on e.StreamId = f.StreamId where StreamId = 1 "
                + "select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Ambiguous field 'StreamId'");
    }

    @Test
    void paramTermFieldInMultiSourceQuery_bindsCleanly_notARawIllegalStateException() {
        // A PARAM used as a where-term field resolves to an unqualified (alias == null) reference. In a
        // multi-source query this used to reach Scope.onlyScan and throw a raw IllegalStateException; a param
        // is a runtime value with no datasource field metadata, so it must simply bind without validation.
        final LogicalPlan plan = bind(
                "from \"Events\" as e join \"Users\" as u on e.UserId = u.Id where ${p} = 1 select e.StreamId");

        assertThat(plan).isInstanceOf(Project.class);
        assertThat(((Project) plan).input()).isInstanceOf(Filter.class);
    }

    @Test
    void unsupportedCondition_throwsBindException() {
        // LONG's default ConditionSet (DEFAULT_NUMERIC) does not include IN.
        assertThatThrownBy(() -> bind("from \"Events\" where StreamId in (1, 2) select StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Condition IN is not supported for field 'StreamId'");
    }

    @Test
    void unknownJoinAlias_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e join \"Users\" as u on x.UserId = u.Id select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown alias 'x'");
    }

    @Test
    void unqualifiedJoinConditionField_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e join \"Users\" as u on UserId = u.Id select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("must be qualified with a source alias");
    }

    @Test
    void incompatibleJoinKeyDomainTypes_throwsBindException() {
        // e.UserId is User.id, u.Name is Person.name - neither accepts the other.
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e join \"Users\" as u on e.UserId = u.Name select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Join key domain types are incompatible");
    }

    @Test
    void validMultiJoinQuery_bindsToJoin() {
        final LogicalPlan plan = bind(
                "from \"Events\" as e join \"Users\" as u on e.UserId = u.Id select e.StreamId, u.Name");

        assertThat(plan).isInstanceOf(Project.class);
        final Project project = (Project) plan;
        assertThat(project.input()).isInstanceOf(Join.class);
        final Join join = (Join) project.input();
        assertThat(join.joinType()).isEqualTo(JoinType.INNER);
        assertThat(join.left()).isEqualTo(new Scan("e", "Events", join.left().position()));
        assertThat(join.right()).isEqualTo(new Scan("u", "Users", join.right().position()));
        assertThat(join.equiKeys()).hasSize(1);
        assertThat(join.equiKeys().getFirst().left().alias()).isEqualTo("e");
        assertThat(join.equiKeys().getFirst().left().field()).isEqualTo("UserId");
        assertThat(join.equiKeys().getFirst().right().alias()).isEqualTo("u");
        assertThat(join.equiKeys().getFirst().right().field()).isEqualTo("Id");
    }

    @Test
    void leftJoin_bindsToLeftJoinType() {
        final LogicalPlan plan = bind(
                "from \"Events\" as e left join \"Users\" as u on e.UserId = u.Id select e.StreamId");

        final Join join = (Join) ((Project) plan).input();
        assertThat(join.joinType()).isEqualTo(JoinType.LEFT);
    }

    @Test
    void evalDefinedFieldIsVisibleToLaterClauses() {
        final LogicalPlan plan = bind(
                "from \"Events\" eval doubled = StreamId * 2 sort by doubled select doubled");

        // Must not throw "Unknown field 'doubled'" for the sort-by or select reference - proves eval-defined
        // names introduced earlier are visible to later clauses (see Binder's class Javadoc).
        assertThat(plan).isInstanceOf(stroom.query.planner.logical.Sort.class);
    }

    @Test
    void whereClauseDoesNotAllowEvalDefinedFields() {
        // where is pushed to the datasource, which has no notion of an eval-computed column - unlike filter/
        // having/sort/group/select, an eval name must NOT be resolvable here.
        assertThatThrownBy(() -> bind(
                "from \"Events\" eval doubled = StreamId * 2 where doubled = 4 select StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown field 'doubled'");
    }

    @Test
    void paramFieldReference_doesNotThrowUnknownField() {
        final LogicalPlan plan = bind(
                "from \"Events\" where StreamId = 1 group by ${MyParam} select StreamId");

        assertThat(plan).isInstanceOf(Aggregate.class);
        final Aggregate aggregate = (Aggregate) plan;
        assertThat(aggregate.groupFields()).hasSize(1);
        assertThat(aggregate.groupFields().getFirst().alias()).isNull();
        assertThat(aggregate.groupFields().getFirst().field()).isEqualTo("MyParam");
    }

    @Test
    void showClause_throwsBindException() {
        assertThatThrownBy(() -> bind("from \"Events\" select StreamId show as \"vis\""))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("show is not yet supported");
    }

    @Test
    void limitValue_isParsedAtBindTime() {
        final LogicalPlan plan = bind("from \"Events\" select StreamId limit 10");

        assertThat(plan).isInstanceOf(stroom.query.planner.logical.Limit.class);
        assertThat(((stroom.query.planner.logical.Limit) plan).values()).containsExactly(10L);
    }

    @Test
    void nonNumericLimitValue_throwsBindException() {
        assertThatThrownBy(() -> bind("from \"Events\" select StreamId limit notANumber"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("not a number");
    }

    // ------------------------------------------------------------------------------------------------------
    // Workstream C - a Cypher sub-query as a join source (
    // Phase P2). The graph side's schema is derived from its own RETURN ... AS list (CypherJoinSchema's C0
    // contract) - Events/Users' FieldInfoSource metadata plays no part in resolving it.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void graphJoinSource_derivesSchemaFromReturnAsAliases_bindsAliasFieldOnBothSides() {
        final LogicalPlan plan = bind(
                "from \"Events\" as e inner join ( from \"CorpGraph\" match (u:User)-[:MEMBER_OF]->(g:Group) "
                + "return u.id as userId, g.name as groupName ) as ident on e.UserId = ident.userId "
                + "select e.StreamId, ident.groupName");

        assertThat(plan).isInstanceOf(Project.class);
        final Join join = (Join) ((Project) plan).input();
        assertThat(join.joinType()).isEqualTo(JoinType.INNER);
        assertThat(join.left()).isEqualTo(new Scan("e", "Events", join.left().position()));
        assertThat(join.right()).isInstanceOf(GraphJoinSource.class);
        final GraphJoinSource graphSource = (GraphJoinSource) join.right();
        assertThat(graphSource.alias()).isEqualTo("ident");
        assertThat(graphSource.cypherText()).contains("match (u:User)-[:MEMBER_OF]->(g:Group)");

        assertThat(join.equiKeys()).hasSize(1);
        assertThat(join.equiKeys().getFirst().left().alias()).isEqualTo("e");
        assertThat(join.equiKeys().getFirst().left().field()).isEqualTo("UserId");
        assertThat(join.equiKeys().getFirst().right().alias()).isEqualTo("ident");
        assertThat(join.equiKeys().getFirst().right().field()).isEqualTo("userId");
    }

    @Test
    void graphJoinSource_leftJoin_bindsToLeftJoinType() {
        final LogicalPlan plan = bind(
                "from \"Events\" as e left join ( from \"FraudGraph\" match (a:Account)-[:FLAGGED_BY]->(r:Rule) "
                + "return a.number as acct, r.name as rule ) as flag on e.UserId = flag.acct "
                + "select e.StreamId, flag.rule");

        final Join join = (Join) ((Project) plan).input();
        assertThat(join.joinType()).isEqualTo(JoinType.LEFT);
        assertThat(join.right()).isInstanceOf(GraphJoinSource.class);
    }

    @Test
    void graphJoinSource_temporalAsOfInsideTheSubQuery_bindsCleanly() {
        // Mirrors the design doc's §5.1 reachability example - a variable-length, temporal traversal inside the
        // graph side must not disturb schema derivation (a single projected column, "hostId").
        final LogicalPlan plan = bind(
                "from \"Events\" as e inner join ( "
                + "from \"HostGraph\" match (seed:Host {id: 'compromised-1'})-[:CONNECTED_TO*1..3]->(h:Host) "
                + "as of datetime('2026-07-01T09:00:00Z') return distinct h.id as hostId "
                + ") as reach on e.UserId = reach.hostId select e.StreamId");

        final Join join = (Join) ((Project) plan).input();
        assertThat(join.equiKeys().getFirst().right().field()).isEqualTo("hostId");
    }

    @Test
    void graphJoinSource_missingAsAlias_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e inner join ( from \"G\" match (u:User) return u.id ) as ident "
                + "on e.UserId = ident.userId select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Join side 'ident'")
                .hasMessageContaining("AS alias");
    }

    @Test
    void graphJoinSource_bareVariableReturn_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e inner join ( from \"G\" match (n:Foo) return n as node ) as ident "
                + "on e.UserId = ident.node select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Join side 'ident'")
                .hasMessageContaining("whole matched node/edge");
    }

    @Test
    void graphJoinSource_joinKeyNamesAColumnTheReturnDoesNotProject_throwsPositionedBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e inner join ( from \"G\" match (u:User) return u.id as userId ) as ident "
                + "on e.UserId = ident.bogus select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown field 'bogus' on 'ident'");
    }

    @Test
    void graphJoinSource_invalidCypherBody_throwsClearBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e inner join ( this is not cypher at all ) as ident "
                + "on e.UserId = ident.userId select e.StreamId"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("not a valid Cypher sub-query");
    }

    @Test
    void graphJoinSource_selectingAnUnprojectedColumn_throwsBindException() {
        assertThatThrownBy(() -> bind(
                "from \"Events\" as e inner join ( from \"G\" match (u:User) return u.id as userId ) as ident "
                + "on e.UserId = ident.userId select ident.bogus"))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("Unknown field 'bogus' on 'ident'");
    }

    @Test
    void constructorRejectsNullFieldInfoSource() {
        assertThatThrownBy(() -> new Binder(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void bindRejectsNullQuery() {
        assertThatThrownBy(() -> new Binder(FIELD_INFO_SOURCE).bind(null))
                .isInstanceOf(NullPointerException.class);
    }
}
