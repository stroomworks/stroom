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

import stroom.docref.DocRef;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.api.DateTimeSettings;
import stroom.query.api.JoinSpec;
import stroom.query.api.SearchRequest;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.api.token.TokenException;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.bind.BindException;
import stroom.query.planner.port.FieldInfoSource;
import stroom.security.mock.MockSecurityContext;
import stroom.util.shared.ResultPage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workstream C, Phase P3 (docs/graphdb-stroomql-join-implementation-plan.md): proves
 * {@link OptimisingQueryCompiler#create} emits a graph join side as a {@link SearchRequest} carrying a
 * {@code GraphSpec} (not a synthesised StroomQL sub-query) whose {@code Query.dataSource} targets the resolved
 * {@code GraphDb} doc - relaxing {@code createJoin}'s "both sides must be plain datasource scans" check to admit
 * exactly this shape, while leaving the non-graph side and every existing rejection unchanged. Mirrors
 * {@link TestOptimisingQueryCompilerJoin}'s style for the plain-scan path.
 */
class TestOptimisingQueryCompilerGraphJoinSide {

    private static final QueryField AUTH_EVENTS_TIME = QueryField.builder()
            .fldName("time").fldType(FieldType.DATE).build();
    private static final QueryField AUTH_EVENTS_USER = QueryField.builder()
            .fldName("user").fldType(FieldType.TEXT).build();
    private static final QueryField TRANSACTIONS_TIME = QueryField.builder()
            .fldName("time").fldType(FieldType.DATE).build();
    private static final QueryField TRANSACTIONS_ACCOUNT = QueryField.builder()
            .fldName("account").fldType(FieldType.TEXT).build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return switch (dataSourceName) {
                case "AuthEvents" -> List.of(AUTH_EVENTS_TIME, AUTH_EVENTS_USER);
                case "Transactions" -> List.of(TRANSACTIONS_TIME, TRANSACTIONS_ACCOUNT);
                default -> List.of();
            };
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    /**
     * Resolves "CorpGraph"/"FraudGraph" to a {@code GraphDb}-typed doc and everything else to a doc whose type is
     * just its own name (mirrors {@link MockDataSourceResolver}'s convention) - {@code MockDataSourceResolver}
     * itself can't be reused unmodified here since its {@code resolveDataSourceRef(name)} sets {@code type = name},
     * which would only ever equal {@link GraphDbDoc#TYPE} for a graph literally named "GraphDb".
     */
    private static final class TestDataSourceResolver extends DataSourceResolver {

        private TestDataSourceResolver() {
            super(() -> null, () -> null);
        }

        @Override
        public DocRef resolveDataSourceRef(final String name) {
            if ("CorpGraph".equals(name) || "FraudGraph".equals(name)) {
                return new DocRef(GraphDbDoc.TYPE, name, name);
            }
            return new DocRef(name, name, name);
        }
    }

    private OptimisingQueryCompiler compiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                new TestDataSourceResolver(),
                () -> criteria -> ResultPage.createUnboundedList(
                        FIELD_INFO_SOURCE.getFields(criteria.getDataSourceRef().getName())),
                MockSecurityContext.getInstance(),
                FIELD_INFO_SOURCE,
                (feedName, from, to) -> Optional.empty(),
                (indexName, from, to) -> Optional.empty(),
                storeName -> Optional.empty());
    }

    private ExpressionContext expressionContext() {
        return ExpressionContext.builder()
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .maxStringLength(100)
                .build();
    }

    private static SearchRequest emptySeedRequest() {
        return new SearchRequest(null, null, null, null, null, false, null);
    }

    @Test
    void innerJoin_graphSide_compilesToGraphSpecRequestTargetingTheResolvedGraphDb() {
        final SearchRequest result = compiler().create(
                "from \"AuthEvents\" as e inner join ( from \"CorpGraph\" match (u:User)-[:MEMBER_OF]->(g:Group) "
                + "return u.id as userId, g.name as groupName ) as ident on e.user = ident.userId "
                + "select e.time, e.user, ident.groupName",
                emptySeedRequest(), expressionContext());

        assertThat(result.getQuery().getDataSource().getType()).isEqualTo(JoinDataSourceType.TYPE);
        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        assertThat(joinSpec.getJoinType()).isEqualTo(JoinSpec.JoinType.INNER);

        // The non-graph side is unchanged - an ordinary compiled single-source request, no GraphSpec.
        assertThat(joinSpec.getLeft().getQuery().getDataSource().getName()).isEqualTo("AuthEvents");
        assertThat(joinSpec.getLeft().getQuery().getGraphSpec()).isNull();

        // The graph side carries a GraphSpec (not a synthesised "select *") targeting the resolved GraphDb doc.
        final SearchRequest graphSide = joinSpec.getRight();
        assertThat(graphSide.getQuery().getDataSource().getType()).isEqualTo(GraphDbDoc.TYPE);
        assertThat(graphSide.getQuery().getDataSource().getName()).isEqualTo("CorpGraph");
        assertThat(graphSide.getQuery().getGraphSpec()).isNotNull();
        assertThat(graphSide.getQuery().getGraphSpec().getCypher())
                .contains("match (u:User)-[:MEMBER_OF]->(g:Group)")
                .contains("return u.id as userId, g.name as groupName");
        // The graph side's own compiled TableSettings expose exactly its RETURN's declared columns.
        final List<String> graphColumnNames = graphSide.getResultRequests().stream()
                .flatMap(rr -> rr.getMappings().stream())
                .flatMap(ts -> ts.getFields().stream())
                .map(stroom.query.api.Column::getName)
                .toList();
        assertThat(graphColumnNames).containsExactlyInAnyOrder("userId", "groupName");

        assertThat(joinSpec.getEquiKeys()).hasSize(1);
        assertThat(joinSpec.getEquiKeys().getFirst().toString()).isEqualTo("e.user = ident.userId");
    }

    @Test
    void leftJoin_graphSide_enrichmentExample_mapsToWireLeftJoinType() {
        final SearchRequest result = compiler().create(
                "from \"Transactions\" as t left join ( from \"FraudGraph\" "
                + "match (a:Account)-[:FLAGGED_BY]->(r:Rule) return a.number as acct, r.name as rule ) as flag "
                + "on t.account = flag.acct select t.time, t.account, flag.rule",
                emptySeedRequest(), expressionContext());

        final JoinSpec joinSpec = result.getQuery().getJoinSpec();
        assertThat(joinSpec.getJoinType()).isEqualTo(JoinSpec.JoinType.LEFT);
        assertThat(joinSpec.getRight().getQuery().getDataSource().getType()).isEqualTo(GraphDbDoc.TYPE);
        assertThat(joinSpec.getRight().getQuery().getGraphSpec().getCypher()).contains("FLAGGED_BY");
    }

    @Test
    void graphSideTargetIsNotAGraphDb_rejectedClearly() {
        assertThatThrownBy(() -> compiler().create(
                "from \"AuthEvents\" as e inner join ( from \"NotAGraph\" match (u:User) return u.id as userId ) "
                + "as ident on e.user = ident.userId select e.time",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("must be a Graph DB");
    }

    @Test
    void graphSideWithNoLeadingFromClause_rejectedClearly() {
        assertThatThrownBy(() -> compiler().create(
                "from \"AuthEvents\" as e inner join ( match (u:User) return u.id as userId ) as ident "
                + "on e.user = ident.userId select e.time",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("no target Graph DB");
    }

    @Test
    void invalidCypherBody_surfacesClearlyThroughBind() {
        // Binder validates the sub-query's Cypher shape before createJoin's own compile step ever runs - surfaces
        // as a BindException, not a TokenException, but still a clear, non-opaque failure either way.
        assertThatThrownBy(() -> compiler().create(
                "from \"AuthEvents\" as e inner join ( this is not cypher ) as ident "
                + "on e.user = ident.userId select e.time",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(BindException.class);
    }

    @Test
    void multiJoinChain_withAGraphSide_isStillRejectedCleanly() {
        // The single-join limit (docs/query-optimiser-implementation-plan.md, Phase 6) applies to a graph side
        // exactly as it does to a plain scan side - checked before either side is even bound.
        assertThatThrownBy(() -> compiler().create(
                "from \"AuthEvents\" as e inner join ( from \"CorpGraph\" match (u:User) return u.id as userId ) "
                + "as ident on e.user = ident.userId "
                + "join \"Transactions\" as t on ident.userId = t.account select e.time",
                emptySeedRequest(), expressionContext()))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("single join");
    }
}
