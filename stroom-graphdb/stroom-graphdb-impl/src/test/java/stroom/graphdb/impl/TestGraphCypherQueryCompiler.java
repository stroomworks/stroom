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

/**
 * Task P6.1: {@link GraphCypherQueryCompiler} - the {@code AlternativeQueryCompiler} adapter giving
 * {@link CypherCompiler} its first real caller.
 */
class TestGraphCypherQueryCompiler {

    private static final DocRef GRAPH_DB_REF = new DocRef("GraphDb", "graph-uuid", "MyGraph");
    private static final DocRef OTHER_REF = new DocRef("PlanB", "planb-uuid", "MyStore");

    @Test
    void supports_isTrueOnlyForGraphDbTypedRefs() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler();
        assertThat(compiler.supports(GRAPH_DB_REF)).isTrue();
        assertThat(compiler.supports(OTHER_REF)).isFalse();
    }

    @Test
    void supports_rejectsNullDataSourceRef() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler();
        assertThatThrownBy(() -> compiler.supports(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void create_compilesCypherIntoASearchRequestCarryingTheGraphSpec() {
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler();
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
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler();
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
        final GraphCypherQueryCompiler compiler = new GraphCypherQueryCompiler();
        final SearchRequest in = SearchRequest.builder()
                .query(Query.builder().dataSource(GRAPH_DB_REF).build())
                .build();

        assertThatThrownBy(() -> compiler.create("not cypher at all", in, new ExpressionContext()))
                .isInstanceOf(RuntimeException.class);
    }
}
