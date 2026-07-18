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
import stroom.query.api.OffsetRange;
import stroom.query.api.SearchRequest;
import stroom.query.api.TimeFilter;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.DataStore;
import stroom.query.common.v2.IdentityItemMapper;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.OpenGroups;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.SearchProvider;
import stroom.query.common.v2.SearchProviderRegistry;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.Val;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.join.JoinExecutor;
import stroom.query.planner.join.JoinExecutor.Side;
import stroom.query.planner.logical.JoinType;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Routes execution for a query compiled with a {@code join} clause - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 6.1. Registered under the sentinel
 * {@link JoinDataSourceType#TYPE}, the same way {@link SearchableSearchProvider} registers once per
 * {@code Searchable} - {@link SearchProviderRegistryImpl} resolves either purely by {@code DocRef.getType()}, no
 * special-casing needed there for this to work.
 *
 * <p>Depends on {@link SearchProviderRegistry} via a lazy {@link Provider} rather than directly: {@code
 * SearchProviderRegistryImpl} is itself constructed from {@code Set<SearchProvider>}, which this class is a
 * member of - injecting the registry directly would be a construction-time cycle; {@code Provider.get()} defers
 * the lookup until {@link #createResultStore} actually runs, by which point both are fully constructed.</p>
 *
 * <p><b>Task 6.1d scope (see the plan doc's Phase 6 section for the full finding)</b>: realises each side as its
 * own sub-query and combines the rows via {@link JoinExecutor} - both genuinely ready, tested via {@link
 * #realiseSide}/{@code JoinExecutor} directly. <b>Not yet implemented</b>: feeding the combined rows into a fresh
 * {@code Coprocessors} built from the outer request's {@code TableSettings}. That step needs each joined row's
 * values placed at the exact positions the outer coprocessor's {@code FieldIndex} assigns as it compiles the
 * outer query's {@code where}/{@code select}/{@code group}/{@code having} column expressions - and nothing
 * compiles an alias-qualified ({@code a.field}) expression to a wire type yet (tracked as Task 6.1x, a
 * prerequisite for this method to return real results instead of throwing).</p>
 */
class JoinSearchProvider implements SearchProvider {

    private final Provider<SearchProviderRegistry> searchProviderRegistryProvider;

    @Inject
    JoinSearchProvider(final Provider<SearchProviderRegistry> searchProviderRegistryProvider) {
        this.searchProviderRegistryProvider =
                Objects.requireNonNull(searchProviderRegistryProvider, "searchProviderRegistryProvider");
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
        final JoinSpec joinSpec = searchRequest.getQuery() == null ? null : searchRequest.getQuery().getJoinSpec();
        if (joinSpec == null) {
            throw new IllegalArgumentException(
                    "SearchRequest routed to " + JoinDataSourceType.TYPE + " must carry a JoinSpec");
        }

        final RealisedSide left = realiseSide(joinSpec.getLeft());
        final RealisedSide right = realiseSide(joinSpec.getRight());
        try {
            final Side leftSide = new Side(left.rows, keyPositions(left.columns, joinSpec.getEquiKeys(), true),
                    left.columns.size());
            final Side rightSide = new Side(right.rows, keyPositions(right.columns, joinSpec.getEquiKeys(), false),
                    right.columns.size());
            final JoinType joinType = joinSpec.getJoinType() == JoinSpec.JoinType.LEFT
                    ? JoinType.LEFT
                    : JoinType.INNER;

            // T6.1c: the combined rows are correct - see JoinExecutor's own tests. What's missing is placing
            // them at the outer coprocessor's FieldIndex positions (Task 6.1x - see class Javadoc).
            final List<Val[]> joinedRows = JoinExecutor.join(leftSide, rightSide, joinType, JoinAlgorithm.HASH_JOIN);

            throw new UnsupportedOperationException(
                    "Join execution produced " + joinedRows.size() + " combined row(s), but feeding them into the "
                    + "outer query's Coprocessors is not yet implemented - see "
                    + "docs/query-optimiser-implementation-plan.md, Task 6.1x (alias-aware outer-expression "
                    + "compilation is a prerequisite: the outer FieldIndex positions these rows must be placed "
                    + "at don't exist until that's built).");
        } finally {
            left.resultStore.destroy();
            right.resultStore.destroy();
        }
    }

    /**
     * Runs {@code sideRequest} (an ordinary, already-compiled single-source request - see {@code
     * OptimisingQueryCompiler#compileJoinSide}, Task 6.1b) to completion via its own {@link SearchProvider}, and
     * reads back every row - the same "run a sub-query, then read every row of the completed store" shape {@code
     * QueryServiceImpl#getColumnValues} already uses, not new machinery.
     */
    private RealisedSide realiseSide(final SearchRequest sideRequest) {
        final DocRef dataSourceRef = sideRequest.getQuery().getDataSource();
        final SearchProvider searchProvider = searchProviderRegistryProvider.get()
                .getSearchProvider(dataSourceRef)
                .orElseThrow(() -> new IllegalStateException(
                        "No SearchProvider registered for join side datasource type '"
                        + dataSourceRef.getType() + "'"));

        final ResultStore resultStore = searchProvider.createResultStore(sideRequest);
        try {
            resultStore.awaitCompletion();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while realising a join side", e);
        }

        final DataStore dataStore = resultStore.getData(SearchRequestFactory.TABLE_COMPONENT_ID);
        final List<Column> columns = dataStore.getColumns();
        final List<Val[]> rows = new ArrayList<>();
        dataStore.fetch(
                columns,
                OffsetRange.UNBOUNDED,
                OpenGroups.ALL,
                new TimeFilter(0, Long.MAX_VALUE),
                IdentityItemMapper.INSTANCE,
                item -> rows.add(item.toArray()),
                count -> {
                });

        return new RealisedSide(resultStore, columns, rows);
    }

    /** One equi-key's field name, resolved to its position among {@code columns} (composite keys supported). */
    private static int[] keyPositions(
            final List<Column> columns, final List<JoinEquiKey> equiKeys, final boolean forLeftSide) {
        final int[] positions = new int[equiKeys.size()];
        for (int i = 0; i < equiKeys.size(); i++) {
            final String fieldName = forLeftSide ? equiKeys.get(i).getLeftField() : equiKeys.get(i).getRightField();
            positions[i] = positionOf(columns, fieldName);
        }
        return positions;
    }

    private static int positionOf(final List<Column> columns, final String fieldName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(fieldName)) {
                return i;
            }
        }
        throw new IllegalStateException("Equi-key field '" + fieldName + "' not found among compiled columns "
                                         + columns.stream().map(Column::getName).toList());
    }

    private record RealisedSide(ResultStore resultStore, List<Column> columns, List<Val[]> rows) {
    }
}
