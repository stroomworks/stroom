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
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Singleton
class UpdatableTemporalStoreDaoImpl implements UpdatableTemporalStoreDao {

    private final SqlStoreDbConnProvider sqlStoreDbConnProvider;
    private final ExpressionMapper expressionMapper;

    @Inject
    UpdatableTemporalStoreDaoImpl(final SqlStoreDbConnProvider sqlStoreDbConnProvider,
                                  final ExpressionMapperFactory expressionMapperFactory) {
        this.sqlStoreDbConnProvider = sqlStoreDbConnProvider;
        this.expressionMapper = expressionMapperFactory.create();
        expressionMapper.map(UpdatableTemporalStore.MAP_FIELD,
                stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.MAP_NAME,
                String::valueOf);
        expressionMapper.map(UpdatableTemporalStore.KEY_FIELD,
                stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.KEY_,
                String::valueOf);
        expressionMapper.map(UpdatableTemporalStore.TIME_FIELD,
                stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME,
                Long::valueOf);
        expressionMapper.map(UpdatableTemporalStore.VALUE_FIELD,
                stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.VALUE_,
                String::valueOf);
    }

    @Override
    public TemporalEntry create(final TemporalEntry entry) {
        JooqUtil.context(sqlStoreDbConnProvider, context -> context
                .insertInto(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE)
                .set(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.MAP_NAME,
                        entry.getMap())
                .set(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.KEY_,
                        entry.getKey())
                .set(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.EFFECTIVE_TIME,
                        entry.getEffectiveTimeMs())
                .set(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.VALUE_,
                        entry.getValue())
                .onDuplicateKeyUpdate()
                .set(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.VALUE_,
                        entry.getValue())
                .execute());
        return entry;
    }

    @Override
    public TemporalEntry update(final TemporalEntry entry) {
        return create(entry);
    }

    @Override
    public Optional<TemporalEntry> fetch(final TemporalEntryId id) {
        final var table = stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE;
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .selectFrom(table)
                .where(table.MAP_NAME.eq(id.getMap()))
                .and(table.KEY_.eq(id.getKey()))
                .and(table.EFFECTIVE_TIME.eq(id.getEffectiveTimeMs()))
                .fetchOptional()
                .map(record -> new TemporalEntry(
                        record.getMapName(),
                        record.getKey_(),
                        record.getEffectiveTime(),
                        record.getValue_())));
    }

    @Override
    public boolean delete(final TemporalEntryId id) {
        final var table = stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE;
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .deleteFrom(table)
                .where(table.MAP_NAME.eq(id.getMap()))
                .and(table.KEY_.eq(id.getKey()))
                .and(table.EFFECTIVE_TIME.eq(id.getEffectiveTimeMs()))
                .execute() > 0);
    }

    @Override
    public ResultPage<TemporalEntry> find(final ExpressionCriteria criteria) {
        final Long queryTime = getQueryTime(criteria);
        if (queryTime != null) {
            final ExpressionOperator filteredExpression = getFilteredExpression(criteria);
            final Condition condition = expressionMapper.apply(filteredExpression);

            final var t1 = stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.as("t1");
            final var t2 = stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.as("t2");

            final var subquery = DSL.select(
                            t2.MAP_NAME.as("sub_map"),
                            t2.KEY_.as("sub_key"),
                            DSL.max(t2.EFFECTIVE_TIME).as("max_time"))
                    .from(t2)
                    .where(condition)
                    .and(t2.EFFECTIVE_TIME.le(queryTime))
                    .groupBy(t2.MAP_NAME, t2.KEY_);

            final var subTable = subquery.asTable("sub");
            final var subMap = subTable.field("sub_map", String.class);
            final var subKey = subTable.field("sub_key", String.class);
            final var maxTime = subTable.field("max_time", Long.class);

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
                    .selectFrom(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE)
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
                .deleteFrom(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE)
                .where(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(
                        mapName))
                .execute());
    }

    @Override
    public long count(final String mapName) {
        return JooqUtil.contextResult(sqlStoreDbConnProvider, context -> context
                .selectCount()
                .from(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE)
                .where(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.MAP_NAME.eq(
                        mapName))
                .fetchOne(0, Long.class));
    }

    @Override
    public void search(final ExpressionCriteria criteria, final Consumer<TemporalEntry> consumer) {
        final Long queryTime = getQueryTime(criteria);
        if (queryTime != null) {
            final ExpressionOperator filteredExpression = getFilteredExpression(criteria);
            final Condition condition = expressionMapper.apply(filteredExpression);

            final var t1 = stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.as("t1");
            final var t2 = stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE.as("t2");

            final var subquery = DSL.select(
                            t2.MAP_NAME.as("sub_map"),
                            t2.KEY_.as("sub_key"),
                            DSL.max(t2.EFFECTIVE_TIME).as("max_time"))
                    .from(t2)
                    .where(condition)
                    .and(t2.EFFECTIVE_TIME.le(queryTime))
                    .groupBy(t2.MAP_NAME, t2.KEY_);

            final var subTable = subquery.asTable("sub");
            final var subMap = subTable.field("sub_map", String.class);
            final var subKey = subTable.field("sub_key", String.class);
            final var maxTime = subTable.field("max_time", Long.class);

            JooqUtil.context(sqlStoreDbConnProvider, context -> context
                    .select(t1.MAP_NAME, t1.KEY_, t1.EFFECTIVE_TIME, t1.VALUE_)
                    .from(t1)
                    .innerJoin(subTable)
                    .on(t1.MAP_NAME.eq(subMap))
                    .and(t1.KEY_.eq(subKey))
                    .and(t1.EFFECTIVE_TIME.eq(maxTime))
                    .fetch()
                    .forEach(record -> consumer.accept(new TemporalEntry(
                            record.get(t1.MAP_NAME),
                            record.get(t1.KEY_),
                            record.get(t1.EFFECTIVE_TIME),
                            record.get(t1.VALUE_)))));
        } else {
            final Condition condition = expressionMapper.apply(criteria.getExpression());
            JooqUtil.context(sqlStoreDbConnProvider, context -> context
                    .selectFrom(stroom.sqlstore.impl.db.jooq.tables.UpdatableTemporalStore.UPDATABLE_TEMPORAL_STORE)
                    .where(condition)
                    .fetch()
                    .forEach(record -> consumer.accept(new TemporalEntry(
                            record.getMapName(),
                            record.getKey_(),
                            record.getEffectiveTime(),
                            record.getValue_()))));
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
                } catch (NumberFormatException e) {
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
                    if (item instanceof ExpressionTerm) {
                        return !UpdatableTemporalStore.TIME_FIELD.getFldName()
                                .equals(((ExpressionTerm) item).getField());
                    }
                    return true;
                });
    }
}
