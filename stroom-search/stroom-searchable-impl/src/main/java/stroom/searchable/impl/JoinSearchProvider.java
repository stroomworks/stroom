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
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.SearchProvider;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;

import java.util.List;

/**
 * Routes execution for a query compiled with a {@code join} clause - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 6.1. Registered under the sentinel
 * {@link JoinDataSourceType#TYPE}, the same way {@link SearchableSearchProvider} registers once per
 * {@code Searchable} - {@link SearchProviderRegistryImpl} resolves either purely by {@code DocRef.getType()}, no
 * special-casing needed there for this to work.
 *
 * <p>Task 6.1a scope only: proves the wire type ({@code JoinSpec}) and this registration route compile and
 * resolve correctly. Actual join execution ({@code JoinExecutor}, sub-query realisation, feeding rows into a
 * fresh {@code Coprocessors}) is Tasks 6.1b-6.1d - not implemented yet, see {@link #createResultStore}.</p>
 */
class JoinSearchProvider implements SearchProvider {

    @Inject
    JoinSearchProvider() {
    }

    @Override
    public String getDataSourceType() {
        return JoinDataSourceType.TYPE;
    }

    @Override
    public List<DocRef> getDataSourceDocRefs() {
        // A join has no discoverable datasource docs of its own - its two sides are ordinary datasources,
        // each already discoverable via their own SearchProvider.
        return List.of();
    }

    @Override
    public ResultPage<QueryField> getFieldInfo(final FindFieldCriteria criteria) {
        // Field info for a join's outer query comes from its sides' own datasources via AstToSearchRequestMapper
        // (Task 6.1b), not from this sentinel type - nothing to report here.
        return ResultPage.empty();
    }

    @Override
    public int getFieldCount(final DocRef docRef) {
        return 0;
    }

    @Override
    public ResultStore createResultStore(final SearchRequest searchRequest) {
        throw new UnsupportedOperationException(
                "Join execution is not yet implemented - see docs/query-optimiser-implementation-plan.md, "
                + "Tasks 6.1b-6.1d.");
    }
}
