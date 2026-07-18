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

package stroom.searchable.impl;

import stroom.docref.DocRef;
import stroom.query.api.Column;
import stroom.query.api.JoinSpec;
import stroom.query.api.JoinSpec.JoinEquiKey;
import stroom.query.api.Query;
import stroom.query.api.SearchRequest;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.DataStore;
import stroom.query.common.v2.Item;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.query.common.v2.SearchProviderRegistry;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.security.api.SecurityContext;
import stroom.task.api.TaskContextFactory;
import stroom.task.api.TaskManager;
import stroom.ui.config.shared.UiConfig;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 6.1a/6.1d: proves the sentinel-type registration route works, and that {@link
 * JoinSearchProvider#createResultStore} correctly realises both join sides (via each side's own {@link
 * SearchProvider}) and invokes {@code JoinExecutor} with the right equi-key positions - the orchestration
 * mechanics Task 6.1d scoped as ready. The final "feed joined rows into the outer query's Coprocessors" step is
 * deliberately not implemented yet (see {@link JoinSearchProvider}'s class Javadoc, Task 6.1x) - proven by the
 * {@link UnsupportedOperationException} every successful-realisation test expects.
 */
class TestJoinSearchProvider {

    private static final DocRef LEFT_DATA_SOURCE = new DocRef("LeftType", "left-uuid", "Left");
    private static final DocRef RIGHT_DATA_SOURCE = new DocRef("RightType", "right-uuid", "Right");

    private SearchProviderRegistryImpl registry(final SearchProvider... providers) {
        return new SearchProviderRegistryImpl(
                mock(Executor.class),
                mock(TaskManager.class),
                mock(TaskContextFactory.class),
                mock(UiConfig.class),
                mock(CoprocessorsFactory.class),
                mock(ResultStoreFactory.class),
                mock(SecurityContext.class),
                Set.of(providers),
                Map.of());
    }

    @Test
    void getDataSourceType_isTheSentinelType() {
        assertThat(new JoinSearchProvider(() -> mock(SearchProviderRegistry.class)).getDataSourceType())
                .isEqualTo(JoinDataSourceType.TYPE);
    }

    @Test
    void searchProviderRegistry_resolvesItForTheSentinelType_noRegistryChangeNeeded() {
        final JoinSearchProvider joinSearchProvider = new JoinSearchProvider(() -> mock(SearchProviderRegistry.class));
        final SearchProviderRegistryImpl registry = registry(joinSearchProvider);

        final DocRef joinDocRef = new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B");

        assertThat(registry.getSearchProvider(joinDocRef)).contains(joinSearchProvider);
    }

    @Test
    void missingJoinSpec_rejectedClearly() {
        final JoinSearchProvider joinSearchProvider = new JoinSearchProvider(() -> mock(SearchProviderRegistry.class));
        final SearchRequest requestWithNoJoinSpec = SearchRequest.builder()
                .query(Query.builder().dataSource(new DocRef(JoinDataSourceType.TYPE, "u", "n")).build())
                .build();

        assertThatThrownBy(() -> joinSearchProvider.createResultStore(requestWithNoJoinSpec))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noSearchProviderForASide_rejectedClearly() {
        final SearchProviderRegistry emptyRegistry = registry();
        final JoinSearchProvider joinSearchProvider = new JoinSearchProvider(() -> emptyRegistry);

        assertThatThrownBy(() -> joinSearchProvider.createResultStore(searchRequestWithJoinSpec()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realisesBothSides_andInvokesJoinExecutor_thenThrowsAtTheDocumentedGap() {
        final SearchProvider leftProvider = fakeSideProvider(
                LEFT_DATA_SOURCE,
                List.of("id", "name"),
                List.of(new Val[]{ValLong.create(1), ValString.create("a")},
                        new Val[]{ValLong.create(2), ValString.create("b")}));
        final SearchProvider rightProvider = fakeSideProvider(
                RIGHT_DATA_SOURCE,
                List.of("id", "amount"),
                List.<Val[]>of(new Val[]{ValLong.create(2), ValLong.create(200)}));

        final SearchProviderRegistry testRegistry = registry(leftProvider, rightProvider);
        final JoinSearchProvider joinSearchProvider = new JoinSearchProvider(() -> testRegistry);

        assertThatThrownBy(() -> joinSearchProvider.createResultStore(searchRequestWithJoinSpec()))
                .isInstanceOf(UnsupportedOperationException.class)
                // Proves it reached the end (2 rows realised, joined on id=id, 1 match) rather than failing earlier.
                .hasMessageContaining("1 combined row");
    }

    private static SearchRequest searchRequestWithJoinSpec() {
        final SearchRequest left = SearchRequest.builder()
                .query(Query.builder().dataSource(LEFT_DATA_SOURCE).build())
                .build();
        final SearchRequest right = SearchRequest.builder()
                .query(Query.builder().dataSource(RIGHT_DATA_SOURCE).build())
                .build();
        final JoinSpec joinSpec = JoinSpec.builder()
                .left(left)
                .right(right)
                .joinType(JoinSpec.JoinType.INNER)
                .addEquiKey(new JoinEquiKey("a", "id", "b", "id"))
                .build();
        return SearchRequest.builder()
                .query(Query.builder()
                        .dataSource(new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A join B"))
                        .joinSpec(joinSpec)
                        .build())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static SearchProvider fakeSideProvider(
            final DocRef dataSource, final List<String> columnNames, final List<Val[]> rows) {
        final List<Column> columns = columnNames.stream()
                .map(name -> Column.builder().id(name).name(name).expression(name).build())
                .toList();

        final DataStore dataStore = mock(DataStore.class);
        when(dataStore.getColumns()).thenReturn(columns);
        doAnswer(invocation -> {
            final Consumer<Item> resultConsumer = invocation.getArgument(5);
            for (final Val[] row : rows) {
                final Item item = mock(Item.class);
                when(item.toArray()).thenReturn(row);
                resultConsumer.accept(item);
            }
            return null;
        }).when(dataStore).fetch(any(), any(), any(), any(), any(), any(), any());

        final ResultStore resultStore = mock(ResultStore.class);
        when(resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID)).thenReturn(dataStore);

        final SearchProvider provider = mock(SearchProvider.class);
        when(provider.getDataSourceType()).thenReturn(dataSource.getType());
        when(provider.createResultStore(any())).thenReturn(resultStore);
        return provider;
    }
}
