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
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.security.api.SecurityContext;
import stroom.task.api.TaskContextFactory;
import stroom.task.api.TaskManager;
import stroom.ui.config.shared.UiConfig;
import stroom.util.shared.ResultPage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F12: {@link SearchProviderRegistryImpl} must fail fast on a duplicate {@link SearchProvider#getDataSourceType()}
 * rather than silently letting the second registration overwrite the first (see
 * {@code docs/query-graphdb-review-report.md} finding F12).
 */
class TestSearchProviderRegistryImpl {

    private static final Executor EXECUTOR = Runnable::run;

    private static SearchProviderRegistryImpl newRegistry(
            final Set<SearchProvider> providers, final Map<String, stroom.searchable.api.Searchable> searchables) {
        return new SearchProviderRegistryImpl(
                EXECUTOR,
                (TaskManager) null,
                (TaskContextFactory) null,
                (UiConfig) null,
                (CoprocessorsFactory) null,
                (ResultStoreFactory) null,
                (SecurityContext) null,
                providers,
                searchables);
    }

    @Test
    void twoProvidersSharingADataSourceType_throwsAClearException() {
        final SearchProvider first = new StubSearchProvider("Dup");
        final SearchProvider second = new StubSearchProvider("Dup");

        assertThatThrownBy(() -> newRegistry(Set.of(first, second), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Dup");
    }

    @Test
    void distinctDataSourceTypes_wireCleanly() {
        final SearchProvider first = new StubSearchProvider("TypeA");
        final SearchProvider second = new StubSearchProvider("TypeB");

        final SearchProviderRegistryImpl registry = newRegistry(Set.of(first, second), Map.of());

        assertThat(registry.getSearchProvider(new DocRef("TypeA", "uuid-a"))).contains(first);
        assertThat(registry.getSearchProvider(new DocRef("TypeB", "uuid-b"))).contains(second);
    }

    /** A minimal {@link SearchProvider} stub, just enough to exercise datasource-type registration. */
    private static final class StubSearchProvider implements SearchProvider {

        private final String dataSourceType;

        private StubSearchProvider(final String dataSourceType) {
            this.dataSourceType = dataSourceType;
        }

        @Override
        public ResultStore createResultStore(final SearchRequest searchRequest) {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        public String getDataSourceType() {
            return dataSourceType;
        }

        @Override
        public List<DocRef> getDataSourceDocRefs() {
            return List.of();
        }

        @Override
        public ResultPage<QueryField> getFieldInfo(final FindFieldCriteria criteria) {
            return ResultPage.createUnboundedList(List.of());
        }

        @Override
        public int getFieldCount(final DocRef docRef) {
            return 0;
        }
    }
}
