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

package stroom.sqlstore.impl.db;

import stroom.db.util.ExpressionMapper;
import stroom.db.util.ExpressionMapperFactory;
import stroom.db.util.JooqUtil;
import stroom.entity.shared.ExpressionCriteria;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionUtil;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.impl.UpdatableTemporalStoreDao;
import stroom.sqlstore.shared.ChangeOperation;
import stroom.sqlstore.shared.TemporalStoreTimeRange;
import stroom.util.date.DateUtil;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.SelectHavingStep;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Singleton
class UpdatableTemporalStoreDaoImpl implements UpdatableTemporalStoreDao {

    private static final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore UPDATABLE_TEMPORAL_STORE =
            stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE;

    private final SqlStoreDbConnProvider sqlStoreDbConnProvider;
    private final ExpressionMapperFactory expressionMapperFactory;
    private final ExpressionMapper expressionMapper;

    @Inject
    UpdatableTemporalStoreDaoImpl(final SqlStoreDbConnProvider sqlStoreDbConnProvider,
                                  final ExpressionMapperFactory expressionMapperFactory) {
        this.sqlStoreDbConnProvider = sqlStoreDbConnProvider;
        this.expressionMapperFactory = expressionMapperFactory;
        this.expressionMapper = expressionMapperFactory.create();
        expressionMapper.map(UpdatableTemporalStore.MAP_FIELD,
                UPDATABLE_TEMPORAL_STORE.MAP_NAME,
                String::valueOf);
        expressionMapper.map(UpdatableTemporalStore.KEY_FIELD,
                UPDATABLE_TEMPORAL_STORE.KEY_,
                String::valueOf);
        expressionMapper.map(UpdatableTemporalStore.TIME_FIELD,
                UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME,
                DateUtil::parseUnknownString);
        expressionMapper.map(UpdatableTemporalStore.VALUE_FIELD,
                UPDATABLE_TEMPORAL_STORE.VALUE_,
                String::valueOf);
    }

    @Override
    public TemporalEntry create(final TemporalEntry entry) {
        validateTemporalEntry(entry);
        JooqUtil.context(sqlStoreDbConnProvider, context -> context
                .insertInto(UPDATABLE_TEMPORAL_STORE)
                .set(UPDATABLE_TEMPORAL_STORE.MAP_NAME, entry.getMap())
                .set(UPDATABLE_TEMPORAL_STORE.KEY_, entry.getKey())
                .set(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME, entry.getEffectiveTimeMs())
                .set(UPDATABLE_TEMPORAL_STORE.VALUE_, entry.getValue())
                .onDuplicateKeyUpdate()
                .set(UPDATABLE_TEMPORAL_STORE.VALUE_, entry.getValue())
                .execute());
        return entry;
    }

    @Override
    public TemporalEntry update(final TemporalEntry entry) {
        return create(entry);
    }

    @Override
    public Optional<TemporalEntry> fetch(final TemporalEntryId id) {
        validateTemporalEntryId(id);
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .selectFrom(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(id.getMap()))
                .and(UPDATABLE_TEMPORAL_STORE.KEY_.eq(id.getKey()))
                .and(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME.eq(id.getEffectiveTimeMs()))
                .fetchOptional()
                .map(record -> new TemporalEntry(
                        record.getMapName(),
                        record.getKey_(),
                        record.getEffectiveTime(),
                        record.getValue_())));
    }

    @Override
    public boolean delete(final TemporalEntryId id) {
        validateTemporalEntryId(id);
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .deleteFrom(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(id.getMap()))
                .and(UPDATABLE_TEMPORAL_STORE.KEY_.eq(id.getKey()))
                .and(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME.eq(id.getEffectiveTimeMs()))
                .execute() > 0);
    }

    @Override
    public ResultPage<TemporalEntry> find(final ExpressionCriteria criteria) {
        validateCriteriaMapName(criteria);
        final Long queryTime = getQueryTime(criteria);
        if (queryTime != null) {
            final ExpressionOperator filteredExpression = getFilteredExpression(criteria);

            final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore t1 =
                    UPDATABLE_TEMPORAL_STORE.as("t1");
            final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore t2 =
                    UPDATABLE_TEMPORAL_STORE.as("t2");

            final ExpressionMapper localExpressionMapper = expressionMapperFactory.create();
            localExpressionMapper.map(UpdatableTemporalStore.MAP_FIELD,
                    t2.MAP_NAME,
                    String::valueOf);
            localExpressionMapper.map(UpdatableTemporalStore.KEY_FIELD,
                    t2.KEY_,
                    String::valueOf);
            localExpressionMapper.map(UpdatableTemporalStore.TIME_FIELD,
                    t2.EFFECTIVE_TIME,
                    DateUtil::parseUnknownString);
            localExpressionMapper.map(UpdatableTemporalStore.VALUE_FIELD,
                    t2.VALUE_,
                    String::valueOf);

            final Condition condition = localExpressionMapper.apply(filteredExpression);

            final SelectHavingStep<Record3<String, String, Long>> subquery = DSL.select(
                            t2.MAP_NAME.as("sub_map"),
                            t2.KEY_.as("sub_key"),
                            DSL.max(t2.EFFECTIVE_TIME).as("max_time"))
                    .from(t2)
                    .where(condition)
                    .and(t2.EFFECTIVE_TIME.le(queryTime))
                    .groupBy(t2.MAP_NAME, t2.KEY_);

            final Table<Record3<String, String, Long>> subTable = subquery.asTable("sub");
            final Field<String> subMap = subTable.field("sub_map", String.class);
            final Field<String> subKey = subTable.field("sub_key", String.class);
            final Field<Long> maxTime = subTable.field("max_time", Long.class);

            final List<TemporalEntry> list = JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                    .select(t1.MAP_NAME, t1.KEY_, t1.EFFECTIVE_TIME, t1.VALUE_)
                    .from(t1)
                    .innerJoin(subTable)
                    .on(t1.MAP_NAME.eq(subMap))
                    .and(t1.KEY_.eq(subKey))
                    .and(t1.EFFECTIVE_TIME.eq(maxTime))
                    .limit(JooqUtil.getLimit(criteria.getPageRequest(), true))
                    .offset(JooqUtil.getOffset(criteria.getPageRequest()))
                    .fetch()
                    .map(record -> new TemporalEntry(
                            record.get(t1.MAP_NAME),
                            record.get(t1.KEY_),
                            record.get(t1.EFFECTIVE_TIME),
                            record.get(t1.VALUE_))));
            return ResultPage.createPageLimitedList(list, criteria.getPageRequest());
        } else {
            final Condition condition = expressionMapper.apply(criteria.getExpression());
            final List<TemporalEntry> list = JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                    .selectFrom(UPDATABLE_TEMPORAL_STORE)
                    .where(condition)
                    .limit(JooqUtil.getLimit(criteria.getPageRequest(), true))
                    .offset(JooqUtil.getOffset(criteria.getPageRequest()))
                    .fetch()
                    .map(record -> new TemporalEntry(
                            record.getMapName(),
                            record.getKey_(),
                            record.getEffectiveTime(),
                            record.getValue_())));
            return ResultPage.createPageLimitedList(list, criteria.getPageRequest());
        }
    }

    @Override
    public void clear(final String mapName) {
        validateMapName(mapName);
        JooqUtil.context(sqlStoreDbConnProvider, context -> context
                .deleteFrom(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(mapName))
                .execute());
    }

    @Override
    public long count(final String mapName) {
        validateMapName(mapName);
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .selectCount()
                .from(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(mapName))
                .fetchOne(0, Long.class));
    }

    /**
     * Returns the absolute latest version of every key in the specified map
     * with no upper-time constraint.
     *
     * <p>Uses a SQL subquery that computes {@code MAX(effective_time)} per key
     * and joins back to the main table to fetch the full row — the same
     * approach as {@link #find(ExpressionCriteria)}.
     * All deduplication happens in the database; no
     * historical rows are transferred across the wire.
     * The result is ordered by key (case-insensitive, ascending).</p>
     *
     * @param mapName name of the map to query; must not be {@code null} or blank
     * @return deduplicated list — one entry per key — sorted by key ascending;
     *         never {@code null}, may be empty
     */
    @Override
    public List<TemporalEntry> fetchAll(final String mapName) {
        validateMapName(mapName);

        // Outer table alias
        final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore t1 =
                UPDATABLE_TEMPORAL_STORE.as("t1");
        // Inner table alias used in the subquery
        final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore t2 =
                UPDATABLE_TEMPORAL_STORE.as("t2");

        // Subquery: for each key, find the greatest effective_time in this map.
        final SelectHavingStep<Record3<String, String, Long>> subquery = DSL.select(
                        t2.MAP_NAME.as("sub_map"),
                        t2.KEY_.as("sub_key"),
                        DSL.max(t2.EFFECTIVE_TIME).as("max_time"))
                .from(t2)
                .where(t2.MAP_NAME.eq(mapName))
                .groupBy(t2.MAP_NAME, t2.KEY_);

        final Table<Record3<String, String, Long>> subTable = subquery.asTable("sub");
        final Field<String> subMap  = subTable.field("sub_map",  String.class);
        final Field<String> subKey  = subTable.field("sub_key",  String.class);
        final Field<Long>   maxTime = subTable.field("max_time", Long.class);

        // Join the outer table to the subquery to retrieve the full rows.
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .select(t1.MAP_NAME, t1.KEY_, t1.EFFECTIVE_TIME, t1.VALUE_)
                .from(t1)
                .innerJoin(subTable)
                .on(t1.MAP_NAME.eq(subMap))
                .and(t1.KEY_.eq(subKey))
                .and(t1.EFFECTIVE_TIME.eq(maxTime))
                .orderBy(t1.KEY_.asc())
                .fetch()
                .map(record -> new TemporalEntry(
                        record.get(t1.MAP_NAME),
                        record.get(t1.KEY_),
                        record.get(t1.EFFECTIVE_TIME),
                        record.get(t1.VALUE_))));
    }

    /**
     * Returns the minimum and maximum {@code effective_time} values in the store
     * for the given map name via a single aggregation query.
     *
     * <p>Both values in the returned {@link TemporalStoreTimeRange} are
     * {@code null} if the map contains no entries.</p>
     *
     * @param mapName name of the map to query; must not be {@code null} or blank
     * @return time range DTO; never {@code null}
     */
    @Override
    public TemporalStoreTimeRange getTimeRange(final String mapName) {
        validateMapName(mapName);

        // Single aggregation query: SELECT MIN(effective_time), MAX(effective_time)
        // WHERE map_name = ?  No row-level deduplication needed.
        final Record2<Long, Long> record = JooqUtil.contextResult(
                sqlStoreDbConnProvider, context -> context
                        .select(
                                DSL.min(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME),
                                DSL.max(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME))
                        .from(UPDATABLE_TEMPORAL_STORE)
                        .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(mapName))
                        .fetchOne());

        if (record == null) {
            return new TemporalStoreTimeRange(null, null);
        }
        return new TemporalStoreTimeRange(record.value1(), record.value2());
    }

    /**
     * Applies a list of upsert and delete operations atomically within a
     * single JOOQ-managed database transaction.
     *
     * <p>Each operation is applied in list order inside
     * {@link DSLContext#transaction(org.jooq.TransactionalRunnable)}.
     * If any step throws, JOOQ rolls back the entire transaction automatically
     * and the exception propagates to the caller.</p>
     *
     * <h3>UPSERT semantics</h3>
     * <p>The upsert uses the same {@code INSERT ... ON DUPLICATE KEY UPDATE}
     * SQL as {@link #create(TemporalEntry)}: if a row with the same
     * {@code (map_name, key, effective_time)} already exists its {@code value}
     * column is overwritten; otherwise a new row is inserted.</p>
     *
     * @param operations ordered list of operations; must not be {@code null}
     */
    @Override
    public void applyChanges(final List<ChangeOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        JooqUtil.context(sqlStoreDbConnProvider, outerContext ->
                outerContext.transaction(config -> {
                    final DSLContext trx = DSL.using(config);
                    for (final ChangeOperation op : operations) {
                        if (op == null || op.getType() == null) {
                            throw new IllegalArgumentException(
                                    "ChangeOperation and its type must not be null.");
                        }
                        switch (op.getType()) {
                            case UPSERT -> {
                                final TemporalEntry entry = op.getEntry();
                                if (entry == null) {
                                    throw new IllegalArgumentException(
                                            "UPSERT operation must have a non-null entry.");
                                }
                                // Mirror create(): insert or update on duplicate natural key.
                                trx.insertInto(UPDATABLE_TEMPORAL_STORE)
                                        .set(UPDATABLE_TEMPORAL_STORE.MAP_NAME,
                                                entry.getMap())
                                        .set(UPDATABLE_TEMPORAL_STORE.KEY_,
                                                entry.getKey())
                                        .set(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME,
                                                entry.getEffectiveTimeMs())
                                        .set(UPDATABLE_TEMPORAL_STORE.VALUE_,
                                                entry.getValue())
                                        .onDuplicateKeyUpdate()
                                        .set(UPDATABLE_TEMPORAL_STORE.VALUE_,
                                                entry.getValue())
                                        .execute();
                            }
                            case DELETE -> {
                                final TemporalEntryId id = op.getId();
                                if (id == null) {
                                    throw new IllegalArgumentException(
                                            "DELETE operation must have a non-null id.");
                                }
                                trx.deleteFrom(UPDATABLE_TEMPORAL_STORE)
                                        .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME
                                                .eq(id.getMap()))
                                        .and(UPDATABLE_TEMPORAL_STORE.KEY_
                                                .eq(id.getKey()))
                                        .and(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME
                                                .eq(id.getEffectiveTimeMs()))
                                        .execute();
                            }
                        }
                    }
                }));
    }

    /**
     * Executes a search query on the updatable temporal store and streams matching entries to the consumer.
     * The method distinguishes between two query paths based on the presence of a query/lookup time:
     *
     * <p><b>1. QueryTime Path (Temporal Lookup):</b>
     * If a temporal query time boundary is present (e.g., from a lookup request at a specific point in time),
     * the search retrieves the single most recent (effective) entry for each map and key that is valid at or
     * before the specified time.
     * <ul>
     *   <li>A subquery is constructed over the aliased table <code>t2</code> to find the maximum effective
     *       time (<code>max(effective_time)</code>) for each map and key combination that is less than or
     *       equal to the query time (<code>t2.effective_time &lt;= queryTime</code>).</li>
     *   <li>The subquery is joined back onto the main table <code>t1</code> on matching keys, maps, and
     *       effective times to fetch the corresponding value.</li>
     *   <li>The returned {@link TemporalEntry} contains the actual <code>effective_time</code> matching the latest
     *       available point in time resolved by the lookup.</li>
     * </ul>
     *
     * <p><b>2. Standard Path (Bulk/Unbounded Search):</b>
     * If no query time is specified, a standard search is performed.
     * <ul>
     *   <li>It executes a direct SELECT query against the <code>UPDATABLE_TEMPORAL_STORE</code> table, filtering
     *       using the expression criteria.</li>
     *   <li>This returns all matching records across all historical time points.</li>
     * </ul>
     *
     * @param criteria The criteria containing filters and optional query time.
     * @param consumer A consumer to receive the mapped {@link TemporalEntry} results.
     */
    @Override
    public void search(final ExpressionCriteria criteria, final Consumer<TemporalEntry> consumer) {
        validateCriteriaMapName(criteria);
        final Long queryTime = getQueryTime(criteria);
        if (queryTime != null) {
            final ExpressionOperator filteredExpression = getFilteredExpression(criteria);

            final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore t1 =
                    UPDATABLE_TEMPORAL_STORE.as("t1");
            final stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore t2 =
                    UPDATABLE_TEMPORAL_STORE.as("t2");

            final ExpressionMapper localExpressionMapper = expressionMapperFactory.create();
            localExpressionMapper.map(UpdatableTemporalStore.MAP_FIELD,
                    t2.MAP_NAME,
                    String::valueOf);
            localExpressionMapper.map(UpdatableTemporalStore.KEY_FIELD,
                    t2.KEY_,
                    String::valueOf);
            localExpressionMapper.map(UpdatableTemporalStore.TIME_FIELD,
                    t2.EFFECTIVE_TIME,
                    DateUtil::parseUnknownString);
            localExpressionMapper.map(UpdatableTemporalStore.VALUE_FIELD,
                    t2.VALUE_,
                    String::valueOf);

            final Condition condition = localExpressionMapper.apply(filteredExpression);
            final SelectHavingStep<Record3<String, String, Long>> subquery = DSL.select(
                            t2.MAP_NAME.as("sub_map"),
                            t2.KEY_.as("sub_key"),
                            DSL.max(t2.EFFECTIVE_TIME).as("max_time"))
                    .from(t2)
                    .where(condition)
                    .and(t2.EFFECTIVE_TIME.le(queryTime))
                    .groupBy(t2.MAP_NAME, t2.KEY_);

            final Table<Record3<String, String, Long>> subTable = subquery.asTable("sub");
            final Field<String> subMap = subTable.field("sub_map", String.class);
            final Field<String> subKey = subTable.field("sub_key", String.class);
            final Field<Long> maxTime = subTable.field("max_time", Long.class);

            //noinspection CodeBlock2Expr
            JooqUtil.context(sqlStoreDbConnProvider, context -> context
                    .select(t1.MAP_NAME, t1.KEY_, t1.EFFECTIVE_TIME, t1.VALUE_)
                    .from(t1)
                    .innerJoin(subTable)
                    .on(t1.MAP_NAME.eq(subMap))
                    .and(t1.KEY_.eq(subKey))
                    .and(t1.EFFECTIVE_TIME.eq(maxTime))
                    .fetch()
                    .forEach(record -> {
                        consumer.accept(new TemporalEntry(
                                record.get(t1.MAP_NAME),
                                record.get(t1.KEY_),
                                record.get(t1.EFFECTIVE_TIME),
                                record.get(t1.VALUE_)));
                    }));
        } else {
            final Condition condition = expressionMapper.apply(criteria.getExpression());

            //noinspection CodeBlock2Expr
            JooqUtil.context(sqlStoreDbConnProvider, context -> context
                    .selectFrom(UPDATABLE_TEMPORAL_STORE)
                    .where(condition)
                    .fetch()
                    .forEach(record -> {
                        consumer.accept(new TemporalEntry(
                                record.getMapName(),
                                record.getKey_(),
                                record.getEffectiveTime(),
                                record.getValue_()));
                    }));
        }
    }

    private Long getQueryTime(final ExpressionCriteria criteria) {
        if (criteria == null || criteria.getExpression() == null) {
            return null;
        }
        final List<ExpressionTerm> timeTerms = ExpressionUtil.terms(
                criteria.getExpression(),
                List.of(UpdatableTemporalStore.TIME_FIELD.getFldName()));

        for (final ExpressionTerm term : timeTerms) {
            if (UpdatableTemporalStore.TIME_FIELD.getFldName().equals(term.getField()) &&
                    (term.getCondition() == ExpressionTerm.Condition.EQUALS ||
                    term.getCondition() == ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO ||
                    term.getCondition() == ExpressionTerm.Condition.LESS_THAN)) {
                try {
                    return DateUtil.parseUnknownString(term.getValue());
                } catch (final RuntimeException e) {
                    // Ignore and keep checking
                }
            }
        }
        return null;
    }

    private ExpressionOperator getFilteredExpression(final ExpressionCriteria criteria) {
        if (criteria == null || criteria.getExpression() == null) {
            return null;
        }
        return ExpressionUtil.copyOperator(
                criteria.getExpression(),
                item -> {
                    if (item instanceof final ExpressionTerm term) {
                        return term.getField() != null
                               && !UpdatableTemporalStore.TIME_FIELD.getFldName().equals(term.getField());
                    }
                    return true;
                });
    }

    private void validateMapName(final String mapName) {
        if (mapName == null || mapName.isBlank()) {
            throw new IllegalArgumentException("Map name must be defined and not empty.");
        }
    }

    private void validateTemporalEntry(final TemporalEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("Entry cannot be null.");
        }
        if (entry.getMap() == null || entry.getMap().isBlank()) {
            throw new IllegalArgumentException("Map name must be defined and not empty.");
        }
        if (entry.getKey() == null || entry.getKey().isBlank()) {
            throw new IllegalArgumentException("Key must be defined and not empty.");
        }
        if (entry.getEffectiveTimeMs() == null) {
            throw new IllegalArgumentException("Effective time must be defined.");
        }
    }

    private void validateTemporalEntryId(final TemporalEntryId id) {
        if (id == null) {
            throw new IllegalArgumentException("Id cannot be null.");
        }
        if (id.getMap() == null || id.getMap().isBlank()) {
            throw new IllegalArgumentException("Map name must be defined and not empty.");
        }
        if (id.getKey() == null || id.getKey().isBlank()) {
            throw new IllegalArgumentException("Key must be defined and not empty.");
        }
        if (id.getEffectiveTimeMs() == null) {
            throw new IllegalArgumentException("Effective time must be defined.");
        }
    }

    private void validateCriteriaMapName(final ExpressionCriteria criteria) {
        if (criteria == null || criteria.getExpression() == null) {
            throw new IllegalArgumentException("Query criteria and expression must be defined.");
        }
        final List<ExpressionTerm> mapTerms = ExpressionUtil.terms(
                criteria.getExpression(),
                List.of(UpdatableTemporalStore.MAP_FIELD.getFldName()));

        if (mapTerms.isEmpty()) {
            throw new IllegalArgumentException("Map name must be defined in the query criteria.");
        }

        for (final ExpressionTerm term : mapTerms) {
            if (term != null
                    && term.getField() != null
                    && term.getField().equals(UpdatableTemporalStore.MAP_FIELD.getFldName())) {
                final String value = term.getValue();
                validateMapName(value);
            }
        }
    }
}
