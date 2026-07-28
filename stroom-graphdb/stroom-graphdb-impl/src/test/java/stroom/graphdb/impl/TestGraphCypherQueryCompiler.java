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

package stroom.graphdb.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.query.api.Column;
import stroom.query.api.Query;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
import stroom.query.language.functions.ExpressionContext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Task P6.1: {@link GraphCypherQueryCompiler} - the {@code AlternativeQueryCompiler} adapter giving
 * {@link CypherCompiler} its first real caller.
 */
class TestGraphCypherQueryCompiler {

    private static final DocRef GRAPH_DB_REF = new DocRef("GraphDb", "graph-uuid", "MyGraph");
    private static final DocRef OTHER_REF = new DocRef("PlanB", "planb-uuid", "MyStore");

    @Test
    void supports_isTrueOnlyForGraphDbTypedRefs() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(mock(DocFinder.class));
        assertThat(compiler.supports(GRAPH_DB_REF)).isTrue();
        assertThat(compiler.supports(OTHER_REF)).isFalse();
    }

    @Test
    void supports_rejectsNullDataSourceRef() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(mock(DocFinder.class));
        assertThatThrownBy(() -> compiler.supports(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_compilesCypherIntoASearchRequestCarryingTheGraphSpec() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(mock(DocFinder.class));
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().dataSource(GRAPH_DB_REF).build())
                .build();

        final SearchRequest result = compiler.create(
                "MATCH (n:Account) RETURN n.id", in, new ExpressionContext());

        assertThat(result.getQuery().getDataSource()).isEqualTo(GRAPH_DB_REF);
        assertThat(result.getQuery().getGraphSpec()).isNotNull();
        assertThat(result.getQuery().getGraphSpec().getCypher()).isEqualTo("MATCH (n:Account) RETURN n.id");
    }

    @Test
    void create_derivesResultRequestsFromTheReturnClauseColumns() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(mock(DocFinder.class));
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().dataSource(GRAPH_DB_REF).build())
                .build();

        final SearchRequest result = compiler.create(
                "MATCH (n:Account) RETURN n.id AS accountId, n.name", in, new ExpressionContext());

        final List<ResultRequest> resultRequests = result.getResultRequests();
        assertThat(resultRequests).hasSize(1);
        final ResultRequest resultRequest = resultRequests.get(0);
        assertThat(resultRequest.getMappings()).hasSize(1);

        final TableSettings tableSettings = resultRequest.getMappings().get(0);
        final List<Column> columns = tableSettings.getColumns();
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getName()).isEqualTo("accountId");
        assertThat(columns.get(0).getExpression()).isEqualTo("${accountId}");
        assertThat(columns.get(1).getName()).isEqualTo("n.name");
        assertThat(columns.get(1).getExpression()).isEqualTo("${n.name}");
    }

    @Test
    void create_throwsForAQueryOutsideTheSubset() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(mock(DocFinder.class));
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().dataSource(GRAPH_DB_REF).build())
                .build();

        assertThatThrownBy(() -> compiler.create("not cypher at all", in, new ExpressionContext()))
                .isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------------------------------------------------
    // Workstream A: resolving the target graph from
    // the Cypher text's own leading `from "X"` clause when no data source is pre-set on the incoming request.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void create_resolvesTheGraphFromTheCyphersOwnFromClauseWhenNoDataSourceIsPreSet() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName("GraphDb", "MyGraph", false)).thenReturn(List.of(GRAPH_DB_REF));
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(docFinder);
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().build())
                .build();

        final SearchRequest result = compiler.create(
                "from \"MyGraph\" MATCH (n:Account) RETURN n.id", in, new ExpressionContext());

        assertThat(result.getQuery().getDataSource()).isEqualTo(GRAPH_DB_REF);
        assertThat(result.getQuery().getGraphSpec().getCypher())
                .isEqualTo("from \"MyGraph\" MATCH (n:Account) RETURN n.id");
    }

    @Test
    void create_prefersAPreSetDataSourceOverTheCyphersOwnFromClause() {
        final DocFinder docFinder = mock(DocFinder.class);
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(docFinder);
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().dataSource(GRAPH_DB_REF).build())
                .build();

        final SearchRequest result = compiler.create(
                "from \"SomeOtherGraph\" MATCH (n:Account) RETURN n.id", in, new ExpressionContext());

        assertThat(result.getQuery().getDataSource()).isEqualTo(GRAPH_DB_REF);
        verifyNoInteractions(docFinder);
    }

    @Test
    void create_throwsWhenNeitherADataSourceNorAFromClauseIsPresent() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(mock(DocFinder.class));
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().build())
                .build();

        assertThatThrownBy(() -> compiler.create("MATCH (n:Account) RETURN n.id", in, new ExpressionContext()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("No target GraphDb");
    }

    @Test
    void create_throwsAClearErrorWhenTheFromClauseNameIsUnknown() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName("GraphDb", "NoSuchGraph", false)).thenReturn(List.of());
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler(docFinder);
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().build())
                .build();

        assertThatThrownBy(() -> compiler.create(
                "from \"NoSuchGraph\" MATCH (n:Account) RETURN n.id", in, new ExpressionContext()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not found");
    }
}
