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
import stroom.query.api.ExpressionOperator;
import stroom.query.api.JoinSpec;
import stroom.query.api.JoinSpec.JoinEquiKey;
import stroom.query.api.OffsetRange;
import stroom.query.api.SearchRequest;
import stroom.query.api.TimeFilter;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.CoprocessorsImpl;
import stroom.query.common.v2.DataStore;
import stroom.query.common.v2.DataStoreSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactories;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactory;
import stroom.query.common.v2.IdentityItemMapper;
import stroom.query.common.v2.JoinDataSourceType;
import stroom.query.common.v2.OpenGroups;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.query.common.v2.SearchProviderRegistry;
import stroom.query.common.v2.ValuesFunctionFactory;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.Values;
import stroom.query.planner.cost.JoinAlgorithm;
import stroom.query.planner.join.JoinExecutor;
import stroom.query.planner.join.JoinExecutor.Side;
import stroom.query.planner.logical.JoinType;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

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
 * <p><b>Scope (Task 6.1d / 6.1x / where-across-joins - see the plan doc's Phase 6 section)</b>: a two-source
 * {@code INNER}/{@code LEFT} join executes end-to-end and returns real rows, including one with a {@code where}
 * clause. Each side is run as its own {@code select *} sub-query, the rows are combined by {@link JoinExecutor},
 * the outer {@code where} clause is applied across each combined row (see {@link #whereRowPredicate}), and each
 * surviving row is placed at the outer coprocessor's {@link FieldIndex} positions (which key on the outer
 * {@code select} columns' alias-qualified expression text, e.g. {@code "a.field"} - see
 * {@link #buildFieldMapping}) and fed via {@code accept(Val[])}. From there the existing coprocessor/
 * {@link ResultStore} machinery applies {@code select}/{@code group}/{@code having}/{@code sort}/{@code limit}
 * exactly as for any other query.</p>
 *
 * <p><b>Simplifications (later optimisations, not correctness concerns)</b>: each side is realised in full and
 * the {@code where} clause applied post-join, rather than pushing single-side terms down into each side's
 * sub-query to pre-filter (which needs alias-stripping the pushed predicate). And {@link #createResultStore}
 * realises both sides and feeds all rows <i>synchronously</i> before returning an already-complete
 * {@link ResultStore} - unlike {@link SearchableSearchProvider}, which runs its feed asynchronously on an
 * {@code Executor}.</p>
 */
class JoinSearchProvider implements SearchProvider {

    private final Provider<SearchProviderRegistry> searchProviderRegistryProvider;
    private final CoprocessorsFactory coprocessorsFactory;
    private final ResultStoreFactory resultStoreFactory;
    private final ExpressionPredicateFactory expressionPredicateFactory;

    @Inject
    JoinSearchProvider(final Provider<SearchProviderRegistry> searchProviderRegistryProvider,
                       final CoprocessorsFactory coprocessorsFactory,
                       final ResultStoreFactory resultStoreFactory,
                       final ExpressionPredicateFactory expressionPredicateFactory) {
        this.searchProviderRegistryProvider =
                Objects.requireNonNull(searchProviderRegistryProvider, "searchProviderRegistryProvider");
        this.coprocessorsFactory = Objects.requireNonNull(coprocessorsFactory, "coprocessorsFactory");
        this.resultStoreFactory = Objects.requireNonNull(resultStoreFactory, "resultStoreFactory");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
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

        // Realise the left side first; if realising the right side then fails, the left side's already-open
        // ResultStore must still be destroyed (the try/finally below only covers both sides once both exist).
        final RealisedSide left = realiseSide(joinSpec.getLeft());
        final RealisedSide right;
        try {
            right = realiseSide(joinSpec.getRight());
        } catch (final RuntimeException e) {
            left.resultStore.destroy();
            throw e;
        }
        final List<Val[]> joinedRows;
        try {
            final Side leftSide = new Side(left.rows, keyPositions(left.columns, joinSpec.getEquiKeys(), true),
                    left.columns.size());
            final Side rightSide = new Side(right.rows, keyPositions(right.columns, joinSpec.getEquiKeys(), false),
                    right.columns.size());
            final JoinType joinType = joinSpec.getJoinType() == JoinSpec.JoinType.LEFT
                    ? JoinType.LEFT
                    : JoinType.INNER;
            joinedRows = JoinExecutor.join(leftSide, rightSide, joinType, JoinAlgorithm.HASH_JOIN);
        } finally {
            left.resultStore.destroy();
            right.resultStore.destroy();
        }

        // Apply the outer where clause across the joined rows (see whereRowPredicate) - the join's "where" can't
        // be applied by either single-source side (it references both aliases), so it's evaluated here on the
        // combined row, then only matching rows are fed to the coprocessor.
        final Predicate<Values> whereRowPredicate = whereRowPredicate(searchRequest, left.columns, right.columns,
                joinSpec.getEquiKeys().getFirst().getLeftAlias(),
                joinSpec.getEquiKeys().getFirst().getRightAlias());

        // Build the outer query's coprocessors and feed each surviving joined row at the FieldIndex positions its
        // alias-qualified select-column expressions claimed.
        final CoprocessorsImpl coprocessors = coprocessorsFactory.create(
                searchRequest, DataStoreSettings.createBasicSearchResultStoreSettings());
        final int[] mapping = buildFieldMapping(
                coprocessors.getFieldIndex(),
                left.columns,
                right.columns,
                joinSpec.getEquiKeys().getFirst().getLeftAlias(),
                joinSpec.getEquiKeys().getFirst().getRightAlias());

        final ResultStore resultStore = resultStoreFactory.create(
                searchRequest.getSearchRequestSource(), coprocessors);
        try {
            for (final Val[] joinedRow : joinedRows) {
                if (whereRowPredicate.test(Values.of(joinedRow))) {
                    coprocessors.accept(assembleRow(joinedRow, mapping));
                }
            }
        } catch (final RuntimeException e) {
            resultStore.addError(e);
        } finally {
            resultStore.signalComplete();
        }
        return resultStore;
    }

    /**
     * Builds a predicate over the <i>combined</i> joined row (left columns then right columns) from the outer
     * query's {@code where} clause ({@code Query.expression}) - see
     * {@code docs/query-optimiser-implementation-plan.md}, Phase 6 "where across joins". The where clause
     * references alias-qualified fields ({@code a.field}/{@code b.field}); each is resolved to its combined-row
     * position (left columns at their own index, right columns offset by the left width) and extracted via the
     * same {@link ValuesFunctionFactory} the coprocessor's own {@code valueFilter} uses, so numeric/date/text
     * comparison semantics match. A trivial/absent where clause yields an always-true predicate.
     *
     * <p>Evaluating the where clause here, on the combined row, is the correct physical position for a join: a
     * single side can't evaluate a predicate that references both aliases, and the combined row is where every
     * referenced field resolves. Per-side push-down (pre-filtering each side before the join) is a later
     * efficiency optimisation, not done here - so each side is realised in full and filtered post-join.</p>
     */
    private Predicate<Values> whereRowPredicate(
            final SearchRequest searchRequest,
            final List<Column> leftColumns,
            final List<Column> rightColumns,
            final String leftAlias,
            final String rightAlias) {
        final ExpressionOperator where = searchRequest.getQuery() == null
                ? null
                : searchRequest.getQuery().getExpression();
        if (where == null || where.getChildren() == null || where.getChildren().isEmpty()) {
            return values -> true;
        }

        final Map<String, ValueFunctionFactory<Values>> accessors = new HashMap<>();
        for (int i = 0; i < leftColumns.size(); i++) {
            accessors.put(leftAlias + "." + leftColumns.get(i).getName(),
                    new ValuesFunctionFactory(leftColumns.get(i), i));
        }
        final int leftWidth = leftColumns.size();
        for (int j = 0; j < rightColumns.size(); j++) {
            accessors.put(rightAlias + "." + rightColumns.get(j).getName(),
                    new ValuesFunctionFactory(rightColumns.get(j), leftWidth + j));
        }
        final ValueFunctionFactories<Values> factories = accessors::get;

        return expressionPredicateFactory
                .createOptional(where, factories, searchRequest.getDateTimeSettings())
                .orElse(values -> true);
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
        // Once the store is open, any failure completing it or reading it back must destroy it here - the store
        // is only handed to the caller (and thus only becomes destroyable by the caller's finally) on the fully
        // successful return, so a failure between here and that return would otherwise leak this side's store.
        try {
            resultStore.awaitCompletion();

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
        } catch (final InterruptedException e) {
            resultStore.destroy();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while realising a join side", e);
        } catch (final RuntimeException e) {
            resultStore.destroy();
            throw e;
        }
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

    /**
     * Maps each position in the outer coprocessor's {@code fieldIndex} to a position in the <i>combined</i> joined
     * row (left side's columns then right side's columns). The {@code fieldIndex} keys on the outer {@code select}
     * columns' alias-qualified expression text ({@code "a.field"}); this splits that into alias + field, decides
     * which side the alias names, and finds that field's position within that side's own columns. A
     * {@code fieldIndex} entry that isn't an {@code alias.field} naming one of the two join sides (e.g. an
     * auto-added special {@code StreamId}/{@code EventId} navigation column) maps to {@code -1} - {@link
     * #assembleRow} fills those with {@link ValNull} rather than a wrong value.
     *
     * @return an array of length {@code fieldIndex.size()}; entry {@code i} is the combined-row position feeding
     *         outer field position {@code i}, or {@code -1} for "no source".
     */
    static int[] buildFieldMapping(
            final FieldIndex fieldIndex,
            final List<Column> leftColumns,
            final List<Column> rightColumns,
            final String leftAlias,
            final String rightAlias) {
        final int leftWidth = leftColumns.size();
        final int[] mapping = new int[fieldIndex.size()];
        for (int outerPos = 0; outerPos < mapping.length; outerPos++) {
            final String name = fieldIndex.getField(outerPos);
            mapping[outerPos] = -1;
            final int dot = name == null ? -1 : name.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            final String alias = name.substring(0, dot);
            final String field = name.substring(dot + 1);
            if (alias.equals(leftAlias)) {
                final int col = indexOfColumn(leftColumns, field);
                if (col >= 0) {
                    mapping[outerPos] = col;
                }
            } else if (alias.equals(rightAlias)) {
                final int col = indexOfColumn(rightColumns, field);
                if (col >= 0) {
                    mapping[outerPos] = leftWidth + col;
                }
            }
        }
        return mapping;
    }

    private static int indexOfColumn(final List<Column> columns, final String fieldName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(fieldName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reorders one combined joined row into the {@code Val[]} the outer coprocessor expects, per the {@code
     * mapping} from {@link #buildFieldMapping}. Unmapped outer positions ({@code -1}) become {@link ValNull}.
     */
    static Val[] assembleRow(final Val[] combinedRow, final int[] mapping) {
        final Val[] out = new Val[mapping.length];
        for (int i = 0; i < mapping.length; i++) {
            out[i] = mapping[i] < 0 ? ValNull.INSTANCE : combinedRow[mapping[i]];
        }
        return out;
    }

    private record RealisedSide(ResultStore resultStore, List<Column> columns, List<Val[]> rows) {
    }
}
