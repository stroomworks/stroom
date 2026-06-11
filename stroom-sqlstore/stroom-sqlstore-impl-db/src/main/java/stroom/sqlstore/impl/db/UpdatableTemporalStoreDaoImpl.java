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
import stroom.util.date.DateUtil;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Condition;
import org.jooq.Field;
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

            final org.jooq.Query query = DSL.select(
                            t1.MAP_NAME, t1.KEY_, t1.EFFECTIVE_TIME, t1.VALUE_)
                    .from(t1)
                    .innerJoin(subTable)
                    .on(t1.MAP_NAME.eq(subMap))
                    .and(t1.KEY_.eq(subKey))
                    .and(t1.EFFECTIVE_TIME.eq(maxTime));

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
            final org.jooq.Query query = DSL.selectFrom(UPDATABLE_TEMPORAL_STORE)
                    .where(condition);

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
                    term.getCondition() == ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO)) {
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
