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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE;

@Singleton
class UpdatableTemporalStoreDaoImpl implements UpdatableTemporalStoreDao {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdatableTemporalStoreDaoImpl.class);

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
                Long::valueOf);
        expressionMapper.map(UpdatableTemporalStore.VALUE_FIELD,
                UPDATABLE_TEMPORAL_STORE.VALUE_,
                String::valueOf);
    }

    @Override
    public TemporalEntry create(final TemporalEntry entry) {
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
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .deleteFrom(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(id.getMap()))
                .and(UPDATABLE_TEMPORAL_STORE.KEY_.eq(id.getKey()))
                .and(UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME.eq(id.getEffectiveTimeMs()))
                .execute() > 0);
    }

    @Override
    public ResultPage<TemporalEntry> find(final ExpressionCriteria criteria) {
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
                    Long::valueOf);
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
        JooqUtil.context(sqlStoreDbConnProvider, context -> context
                .deleteFrom(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(mapName))
                .execute());
    }

    @Override
    public long count(final String mapName) {
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .selectCount()
                .from(UPDATABLE_TEMPORAL_STORE)
                .where(UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(mapName))
                .fetchOne(0, Long.class));
    }

    @Override
    public void search(final ExpressionCriteria criteria, final Consumer<TemporalEntry> consumer) {
        LOGGER.info("Starting SQL Store search with criteria expression: {}", criteria.getExpression());
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
                    Long::valueOf);
            localExpressionMapper.map(UpdatableTemporalStore.VALUE_FIELD,
                    t2.VALUE_,
                    String::valueOf);

            final Condition condition = localExpressionMapper.apply(filteredExpression);
            LOGGER.info("QueryTime path. queryTime: {}, condition: {}", queryTime, condition);

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
            LOGGER.info("Executing SQL: {}", query.getSQL());

            JooqUtil.context(sqlStoreDbConnProvider, context -> context
                    .select(t1.MAP_NAME, t1.KEY_, t1.EFFECTIVE_TIME, t1.VALUE_)
                    .from(t1)
                    .innerJoin(subTable)
                    .on(t1.MAP_NAME.eq(subMap))
                    .and(t1.KEY_.eq(subKey))
                    .and(t1.EFFECTIVE_TIME.eq(maxTime))
                    .fetch()
                    .forEach(record -> {
                        LOGGER.info("Found record (QueryTime path) -> Map: {}, Key: {}, Time: {}, Value: {}",
                                record.get(t1.MAP_NAME),
                                record.get(t1.KEY_),
                                record.get(t1.EFFECTIVE_TIME),
                                record.get(t1.VALUE_));
                        consumer.accept(new TemporalEntry(
                                record.get(t1.MAP_NAME),
                                record.get(t1.KEY_),
                                record.get(t1.EFFECTIVE_TIME),
                                record.get(t1.VALUE_)));
                    }));
        } else {
            final Condition condition = expressionMapper.apply(criteria.getExpression());
            LOGGER.info("Standard path. condition: {}", condition);

            final org.jooq.Query query = DSL.selectFrom(UPDATABLE_TEMPORAL_STORE)
                    .where(condition);
            LOGGER.info("Executing SQL: {}", query.getSQL());

            JooqUtil.context(sqlStoreDbConnProvider, context -> context
                    .selectFrom(UPDATABLE_TEMPORAL_STORE)
                    .where(condition)
                    .fetch()
                    .forEach(record -> {
                        LOGGER.info("Found record (Standard path) -> Map: {}, Key: {}, Time: {}, Value: {}",
                                record.getMapName(), record.getKey_(), record.getEffectiveTime(), record.getValue_());
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
                    return Long.valueOf(term.getValue());
                } catch (final NumberFormatException e) {
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
}
