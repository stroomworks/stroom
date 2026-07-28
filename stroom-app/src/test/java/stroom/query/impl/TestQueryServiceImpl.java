/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.query.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.graphdb.impl.GraphCypherQueryCompiler;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.api.Query;
import stroom.query.api.QueryNodeResolver;
import stroom.query.api.SearchRequest;
import stroom.query.api.SearchRequestSource;
import stroom.query.common.v2.DataSourceProviderRegistry;
import stroom.query.common.v2.ExpressionContextFactory;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.language.AlternativeQueryCompiler;
import stroom.query.language.DataSourceResolver;
import stroom.query.language.QueryCompiler;
import stroom.query.shared.QueryHelpType;
import stroom.query.shared.QuerySearchRequest;
import stroom.security.mock.MockSecurityContext;
import stroom.test.common.TestUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import com.google.inject.TypeLiteral;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestQueryServiceImpl {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestQueryServiceImpl.class);

    private static final Set<QueryHelpType> FIELDS_AND_FUNCS = Set.of(QueryHelpType.FIELD, QueryHelpType.FUNCTION);

    @TestFactory
    Stream<DynamicTest> testGetQueryHelpContext() {
        final QueryServiceImpl queryService = new QueryServiceImpl(
                null,
                null,
                null,
                new MockSecurityContext(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ExpressionPredicateFactory(),
                null,
                null,
                null,
                null);

        return TestUtil.buildDynamicTestStream()
                .withInputType(String.class)
                .withWrappedOutputType(new TypeLiteral<Set<QueryHelpType>>() {
                })
                .withTestFunction(testCase -> {
                    final String partialQuery = testCase.getInput();
                    final ContextualQueryHelp contextualQueryHelp = queryService.getQueryHelpContext(partialQuery);
                    final Set<QueryHelpType> types = contextualQueryHelp.queryHelpTypes();

                    LOGGER.debug("Types: {}, Query:\n{}",
                            types, partialQuery);
                    LOGGER.debug("helpTypes: {}", contextualQueryHelp.queryHelpTypes());
                    LOGGER.debug("structureItems: {}", contextualQueryHelp.applicableStructureItems());
                    return types;
                })
                .withSimpleEqualityAssertion()
                .addCase("", Set.of(QueryHelpType.STRUCTURE))
                .addCase("from", Set.of())
                .addCase("from ", Set.of(QueryHelpType.DATA_SOURCE))
                .addCase("from Dual1", Set.of(QueryHelpType.DATA_SOURCE))
                .addCase("""
                        from Dual2
                        """, Set.of(QueryHelpType.STRUCTURE))
                .addCase("""
                        from Dual
                        select Dummy1""", FIELDS_AND_FUNCS)
                .addCase("""
                        from Dual
                        select Dummy2
                        """, Set.of(QueryHelpType.FIELD, QueryHelpType.FUNCTION, QueryHelpType.STRUCTURE))
                .addCase("""
                        from Dual
                        select Dummy2,""", FIELDS_AND_FUNCS)
                .addCase("""
                        from Dual
                        select Dummy2,\s""", FIELDS_AND_FUNCS)
                .addCase("""
                        from Dual
                        eval""", Set.of(QueryHelpType.STRUCTURE))
                .addCase("""
                        from Dual
                        eval\s""", Set.of())
                .addCase("""
                        from Dual
                        eval =""", Set.of())
                .addCase("""
                        from Dual
                        eval =\s""", FIELDS_AND_FUNCS)
                .build();
    }

    // ------------------------------------------------------------------------------------------------------
    // Workstream A: mapRequest's text-driven
    // dispatch, exercised through the public getBestNode(String, QuerySearchRequest) entry point - the
    // narrowest public seam onto the private mapRequest(...) these route through, since it surfaces exactly
    // the resolved Query.dataSource (via a mocked QueryNodeResolver) that dispatch decided on.
    // ------------------------------------------------------------------------------------------------------

    private static final DocRef GRAPH_DB_REF = new DocRef(GraphDbDoc.TYPE, "graph-uuid", "MyGraph");
    private static final DocRef INDEX_REF = new DocRef("Index", "index-uuid", "SomeIndex");

    @Test
    void mapRequest_routesALeadingFromGraphDbClauseToCypherWithoutOwnerDocRef() {
        final DataSourceProviderRegistry registry = registryResolving(GRAPH_DB_REF);
        final QueryServiceImpl queryService = queryService(
                null,
                Set.of(new GraphCypherQueryCompiler(mock(DocFinder.class))),
                new DataSourceResolver(() -> mock(DocFinder.class), () -> registry));

        final QuerySearchRequest request = QuerySearchRequest.builder()
                .query("from \"MyGraph\" MATCH (n:Account) RETURN n.id")
                .build();

        assertThat(queryService.getBestNode(null, request)).isEqualTo(GraphDbDoc.TYPE);
    }

    @Test
    void mapRequest_routesALeadingFromNonGraphDbClauseToStroomQl() {
        final DataSourceProviderRegistry registry = registryResolving(INDEX_REF);
        final DocFinder graphDocFinder = mock(DocFinder.class);
        final QueryCompiler queryCompiler = mock(QueryCompiler.class);
        when(queryCompiler.create(any(), any(), any()))
                .thenReturn(SearchRequest.builder().query(Query.builder().dataSource(INDEX_REF).build()).build());

        final QueryServiceImpl queryService = queryService(
                queryCompiler,
                Set.of(new GraphCypherQueryCompiler(graphDocFinder)),
                new DataSourceResolver(() -> mock(DocFinder.class), () -> registry));

        final QuerySearchRequest request = QuerySearchRequest.builder()
                .query("from \"SomeIndex\" select val")
                .build();

        assertThat(queryService.getBestNode(null, request)).isEqualTo("Index");
        // Proves the Cypher path was never entered: its own resolver was never consulted.
        verifyNoInteractions(graphDocFinder);
    }

    @Test
    void mapRequest_ownerDocRefStillWinsOverALeadingFromClause() {
        final DataSourceProviderRegistry registry = mock(DataSourceProviderRegistry.class);
        final QueryServiceImpl queryService = queryService(
                null,
                Set.of(new GraphCypherQueryCompiler(mock(DocFinder.class))),
                new DataSourceResolver(() -> mock(DocFinder.class), () -> registry));

        // No `from` clause at all - only the pre-set ownerDocRef should decide routing, exactly as before this
        // workstream.
        final QuerySearchRequest request = QuerySearchRequest.builder()
                .searchRequestSource(SearchRequestSource.createBasic().copy().ownerDocRef(GRAPH_DB_REF).build())
                .query("MATCH (n:Account) RETURN n.id")
                .build();

        assertThat(queryService.getBestNode(null, request)).isEqualTo(GraphDbDoc.TYPE);
        // The leading-from resolution path is never even attempted when ownerDocRef is already set.
        verifyNoInteractions(registry);
    }

    private static QueryServiceImpl queryService(final QueryCompiler queryCompiler,
                                                  final Set<AlternativeQueryCompiler> alternativeQueryCompilers,
                                                  final DataSourceResolver dataSourceResolver) {
        final QueryNodeResolver queryNodeResolver = mock(QueryNodeResolver.class);
        when(queryNodeResolver.getNode(any())).thenAnswer(invocation ->
                invocation.<DocRef>getArgument(0).getType());
        return new QueryServiceImpl(
                null,
                null,
                null,
                new MockSecurityContext(),
                null,
                null,
                null,
                null,
                null,
                null,
                queryCompiler,
                new ExpressionContextFactory(),
                null,
                new ExpressionPredicateFactory(),
                null,
                queryNodeResolver,
                alternativeQueryCompilers,
                dataSourceResolver);
    }

    private static DataSourceProviderRegistry registryResolving(final DocRef docRef) {
        final DataSourceProviderRegistry registry = mock(DataSourceProviderRegistry.class);
        when(registry.findDataSourceByName(docRef.getName())).thenReturn(List.of(docRef));
        return registry;
    }
}
