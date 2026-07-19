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
import stroom.query.api.SearchRequest;
import stroom.query.language.functions.ExpressionContext;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task P6.1: {@link AlternativeQueryCompilerResolver} tested in isolation, exactly the point of extracting it -
 * no {@code QueryServiceImpl} construction needed.
 */
class TestAlternativeQueryCompilerResolver {

    private static final DocRef GRAPH_DB_REF = new DocRef("GraphDb", "graph-uuid", "MyGraph");
    private static final DocRef OTHER_REF = new DocRef("PlanB", "planb-uuid", "MyStore");

    @Test
    void resolve_returnsEmptyWhenDataSourceRefIsNull() {
        final AlternativeQueryCompiler compiler = fakeCompiler("GraphDb");
        assertThat(AlternativeQueryCompilerResolver.resolve(null, List.of(compiler))).isEmpty();
    }

    @Test
    void resolve_returnsEmptyWhenNoCompilerSupportsTheType() {
        final AlternativeQueryCompiler compiler = fakeCompiler("GraphDb");
        assertThat(AlternativeQueryCompilerResolver.resolve(OTHER_REF, List.of(compiler))).isEmpty();
    }

    @Test
    void resolve_returnsEmptyForAnEmptyCompilerSet() {
        assertThat(AlternativeQueryCompilerResolver.resolve(GRAPH_DB_REF, List.of())).isEmpty();
    }

    @Test
    void resolve_returnsTheMatchingCompiler() {
        final AlternativeQueryCompiler nonMatching = fakeCompiler("PlanB");
        final AlternativeQueryCompiler matching = fakeCompiler("GraphDb");

        final Optional<AlternativeQueryCompiler> result =
                AlternativeQueryCompilerResolver.resolve(GRAPH_DB_REF, List.of(nonMatching, matching));

        assertThat(result).contains(matching);
    }

    private static AlternativeQueryCompiler fakeCompiler(final String supportedType) {
        return new AlternativeQueryCompiler() {
            @Override
            public boolean supports(final DocRef dataSourceRef) {
                return supportedType.equals(dataSourceRef.getType());
            }

            @Override
            public SearchRequest create(final String query, final SearchRequest in,
                                        final ExpressionContext expressionContext) {
                throw new UnsupportedOperationException("not needed by this test");
            }
        };
    }
}
