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

package stroom.searchable.impl;

import stroom.docref.DocRef;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.LegacyDocRefConverter;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.query.common.v2.SearchProviderRegistry;
import stroom.searchable.api.Searchable;
import stroom.security.api.SecurityContext;
import stroom.task.api.TaskContextFactory;
import stroom.task.api.TaskManager;
import stroom.ui.config.shared.UiConfig;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Singleton
public class SearchProviderRegistryImpl implements SearchProviderRegistry {

    private final Map<String, SearchProvider> searchProviderMap = new ConcurrentHashMap<>();


    @Inject
    SearchProviderRegistryImpl(final Executor executor,
                               final TaskManager taskManager,
                               final TaskContextFactory taskContextFactory,
                               final UiConfig clientConfig,
                               final CoprocessorsFactory coprocessorsFactory,
                               final ResultStoreFactory resultStoreFactory,
                               final SecurityContext securityContext,
                               final Set<SearchProvider> providers,
                               final Map<String, Searchable> searchables) {
        for (final SearchProvider provider : providers) {
            putOrFail(provider.getDataSourceType(), provider);
        }

        for (final Searchable searchable : searchables.values()) {
            final SearchableSearchProvider searchableSearchProvider =
                    new SearchableSearchProvider(executor,
                            taskManager,
                            taskContextFactory,
                            clientConfig,
                            coprocessorsFactory,
                            resultStoreFactory,
                            securityContext,
                            searchable);
            putOrFail(searchable.getDataSourceType(), searchableSearchProvider);
        }
    }

    /**
     * Registers {@code searchProvider} under {@code dataSourceType}, failing fast rather than silently
     * overwriting a clash - see {@code docs/query-graphdb-review-report.md} finding F12. Two
     * {@link SearchProvider}s (or a {@link SearchProvider} and a {@link Searchable}-backed
     * {@link SearchableSearchProvider}) declaring the same {@link SearchProvider#getDataSourceType()} is a
     * wiring bug: whichever was put second would previously win silently, and queries against the loser's
     * datasource type would be routed to the wrong provider with no indication anything was wrong.
     *
     * @throws IllegalStateException if {@code dataSourceType} is already registered, naming the colliding type
     *                               and both providers' classes.
     */
    private void putOrFail(final String dataSourceType, final SearchProvider searchProvider) {
        final SearchProvider existing = searchProviderMap.putIfAbsent(dataSourceType, searchProvider);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate search provider datasource type '" + dataSourceType + "': both "
                    + existing.getClass().getName() + " and " + searchProvider.getClass().getName()
                    + " declare it - each datasource type must be registered by exactly one provider");
        }
    }

    @Override
    public Optional<SearchProvider> getSearchProvider(final DocRef dataSourceRef) {
        final DocRef docRef = LegacyDocRefConverter.convert(dataSourceRef);
        return Optional.ofNullable(searchProviderMap.get(docRef.getType()));
    }
}
