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
import stroom.query.api.SearchRequest;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.security.api.SecurityContext;
import stroom.task.api.TaskContextFactory;
import stroom.task.api.TaskManager;
import stroom.ui.config.shared.UiConfig;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Task 6.1a: proves the sentinel-type registration route actually works - {@link SearchProviderRegistryImpl}
 * resolves {@link JoinSearchProvider} for a {@link DocRef} of type {@link JoinDataSourceType#TYPE}, with no
 * change to the registry needed (it already resolves purely by {@code DocRef.getType()}).
 */
class TestJoinSearchProvider {

    private SearchProviderRegistryImpl registry(final JoinSearchProvider joinSearchProvider) {
        return new SearchProviderRegistryImpl(
                mock(Executor.class),
                mock(TaskManager.class),
                mock(TaskContextFactory.class),
                mock(UiConfig.class),
                mock(CoprocessorsFactory.class),
                mock(ResultStoreFactory.class),
                mock(SecurityContext.class),
                Set.of(joinSearchProvider),
                Map.of());
    }

    @Test
    void getDataSourceType_isTheSentinelType() {
        assertThat(new JoinSearchProvider().getDataSourceType()).isEqualTo(JoinDataSourceType.TYPE);
    }

    @Test
    void searchProviderRegistry_resolvesItForTheSentinelType_noRegistryChangeNeeded() {
        final JoinSearchProvider joinSearchProvider = new JoinSearchProvider();
        final SearchProviderRegistryImpl registry = registry(joinSearchProvider);

        final DocRef joinDocRef = new DocRef(JoinDataSourceType.TYPE, "join-uuid", "A ⋈ B");

        assertThat(registry.getSearchProvider(joinDocRef)).contains(joinSearchProvider);
    }

    @Test
    void createResultStore_notYetImplemented_throwsClearly() {
        final JoinSearchProvider joinSearchProvider = new JoinSearchProvider();

        assertThatThrownBy(() -> joinSearchProvider.createResultStore(mock(SearchRequest.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
