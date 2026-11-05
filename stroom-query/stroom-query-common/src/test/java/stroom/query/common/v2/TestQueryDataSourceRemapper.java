/*
 * Copyright 2026 Crown Copyright
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

package stroom.query.common.v2;

import stroom.docref.DocRef;
import stroom.docstore.api.DependencyRemapper;
import stroom.query.language.SearchRequestFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class TestQueryDataSourceRemapper {

    private static final DocRef EVENTS = DocRef.builder()
            .type("Index")
            .uuid("events-uuid")
            .name("Events")
            .build();
    private static final DocRef NEW_EVENTS = DocRef.builder()
            .type("Index")
            .uuid("new-events-uuid")
            .name("New Events")
            .build();

    private SearchRequestFactory searchRequestFactory;
    private DataSourceProviderRegistry dataSourceProviderRegistry;
    private QueryDataSourceRemapper remapper;

    @BeforeEach
    void setUp() {
        searchRequestFactory = Mockito.mock(SearchRequestFactory.class);
        dataSourceProviderRegistry = Mockito.mock(DataSourceProviderRegistry.class);
        Mockito.when(dataSourceProviderRegistry.getDataSourceDocRefs()).thenReturn(List.of(EVENTS));
        remapper = new QueryDataSourceRemapper(searchRequestFactory, () -> dataSourceProviderRegistry);
    }

    @Test
    void remap_notRemapped_recordsDependency() {
        final String query = "from Events select Count";
        queryNames(EVENTS);
        final DependencyRemapper dependencyRemapper = new DependencyRemapper();

        final String result = remapper.remap(query, dependencyRemapper);

        assertThat(result)
                .isEqualTo(query);
        assertThat(dependencyRemapper.getDependencies())
                .containsExactly(EVENTS);
        assertThat(dependencyRemapper.isChanged())
                .isFalse();
    }

    @Test
    void remap_remappedByName() {
        queryNames(EVENTS);
        final DependencyRemapper dependencyRemapper = new DependencyRemapper(Map.of(EVENTS, NEW_EVENTS));

        final String result = remapper.remap("from Events select Count", dependencyRemapper);

        assertThat(result)
                .isEqualTo("from New Events select Count");
        assertThat(dependencyRemapper.getDependencies())
                .containsExactly(NEW_EVENTS);
    }

    @Test
    void remap_remappedByUuid() {
        queryNames(EVENTS);
        final DependencyRemapper dependencyRemapper = new DependencyRemapper(Map.of(EVENTS, NEW_EVENTS));

        final String result = remapper.remap("from events-uuid select Count", dependencyRemapper);

        assertThat(result)
                .isEqualTo("from new-events-uuid select Count");
    }

    @Test
    void remap_nameWithRegexCharacters() {
        // Regression test: the name was replaced as a regular expression, so a name holding characters that
        // have a meaning in one, such as brackets, was never found and the query kept the old name
        final DocRef dated = DocRef.builder().type("Index").uuid("dated-uuid").name("Events (2024)").build();
        final DocRef newDated = DocRef.builder().type("Index").uuid("new-dated-uuid").name("Events (2025)").build();
        Mockito.when(dataSourceProviderRegistry.getDataSourceDocRefs()).thenReturn(List.of(dated));
        queryNames(dated);
        final DependencyRemapper dependencyRemapper = new DependencyRemapper(Map.of(dated, newDated));

        final String result = remapper.remap("from \"Events (2024)\" select Count", dependencyRemapper);

        assertThat(result)
                .isEqualTo("from \"Events (2025)\" select Count");
    }

    @Test
    void remap_unknownDataSource() {
        final String query = "from Unknown select Count";
        queryNames(DocRef.builder().type("Index").uuid("unknown-uuid").name("Unknown").build());
        final DependencyRemapper dependencyRemapper = new DependencyRemapper(Map.of(EVENTS, NEW_EVENTS));

        final String result = remapper.remap(query, dependencyRemapper);

        assertThat(result)
                .isEqualTo(query);
        assertThat(dependencyRemapper.getDependencies())
                .isEmpty();
    }

    @Test
    void remap_noDataSource() {
        final String query = "select Count";
        queryNames(null);
        final DependencyRemapper dependencyRemapper = new DependencyRemapper();

        assertThat(remapper.remap(query, dependencyRemapper))
                .isEqualTo(query);
        assertThat(dependencyRemapper.getDependencies())
                .isEmpty();
    }

    @Test
    void remap_noQuery() {
        final DependencyRemapper dependencyRemapper = new DependencyRemapper();

        assertThat(remapper.remap(null, dependencyRemapper))
                .isNull();
        assertThat(remapper.remap(" ", dependencyRemapper))
                .isEqualTo(" ");
        Mockito.verifyNoInteractions(searchRequestFactory);
    }

    @Test
    void remap_queryCantBeRead() {
        final String query = "from from from";
        Mockito.doThrow(new RuntimeException("Unexpected token"))
                .when(searchRequestFactory).extractDataSourceOnly(Mockito.eq(query), Mockito.any());

        assertThat(remapper.remap(query, new DependencyRemapper()))
                .isEqualTo(query);
    }

    // Makes the query factory find the given data source in any query
    @SuppressWarnings("unchecked")
    private void queryNames(final DocRef dataSource) {
        Mockito.doAnswer(invocation -> {
            ((Consumer<DocRef>) invocation.getArgument(1)).accept(dataSource);
            return null;
        }).when(searchRequestFactory).extractDataSourceOnly(Mockito.anyString(), Mockito.any());
    }
}
