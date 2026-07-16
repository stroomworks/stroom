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

import stroom.entity.shared.ExpressionCriteria;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.impl.UpdatableTemporalStoreDao;
import stroom.sqlstore.shared.ChangeOperation;
import stroom.sqlstore.shared.TemporalStoreTimeRange;
import stroom.test.common.util.db.DbTestModule;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-database tests for {@link UpdatableTemporalStoreDaoImpl}.
 *
 * <p>These exercise behaviour that the mocked unit test
 * ({@code TestUpdatableTemporalStoreDaoImpl}) cannot: actual CRUD round-trips,
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} upsert semantics, the temporal
 * "valid at or before" resolution, and the transactional rollback of
 * {@link UpdatableTemporalStoreDao#applyChanges}.</p>
 *
 * <p>Requires a reachable MySQL (dev default {@code localhost:3307}); the
 * {@link DbTestModule} gives each Gradle fork its own uniquely-named schema.
 * Each test uses a fresh, unique map name so the tests are independent without
 * needing to clear shared state.</p>
 */
class TestUpdatableTemporalStoreDaoImplDB {

    // Three ascending effective times, expressed as ISO-8601 so the query-time
    // strings below read clearly. The store persists the epoch-milli values.
    private static final String T1_ISO = "2020-01-01T00:00:00.000Z";
    private static final String T2_ISO = "2021-01-01T00:00:00.000Z";
    private static final String T3_ISO = "2022-01-01T00:00:00.000Z";
    private static final long T1 = Instant.parse(T1_ISO).toEpochMilli();
    private static final long T2 = Instant.parse(T2_ISO).toEpochMilli();
    private static final long T3 = Instant.parse(T3_ISO).toEpochMilli();

    private static final AtomicInteger MAP_SEQ = new AtomicInteger();

    private static UpdatableTemporalStoreDao dao;

    @BeforeAll
    static void beforeAll() {
        final Injector injector = Guice.createInjector(new TestModule());
        dao = injector.getInstance(UpdatableTemporalStoreDao.class);
    }

    // -----------------------------------------------------------------------
    // CRUD + count
    // -----------------------------------------------------------------------

    @Test
    void testCreateAndFetch() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate1", T1, "{\"name\":\"A\"}"));

        final Optional<TemporalEntry> fetched = dao.fetch(id(map, "gate1"));

        assertThat(fetched).isPresent();
        assertThat(fetched.get().getValue()).isEqualTo("{\"name\":\"A\"}");
        assertThat(dao.count(map)).isEqualTo(1);
    }

    @Test
    void testFetchMissingReturnsEmpty() {
        final String map = uniqueMap();
        assertThat(dao.fetch(id(map, "nope"))).isEmpty();
    }

    /**
     * Two writes to the same natural key must upsert (overwrite value), not
     * insert a duplicate row.
     */
    @Test
    void testUpdateOverwritesValueForSameNaturalKey() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate1", T1, "{\"name\":\"A\"}"));
        dao.update(entry(map, "gate1", T1, "{\"name\":\"B\"}"));

        assertThat(dao.count(map)).isEqualTo(1);
        assertThat(dao.fetch(id(map, "gate1")))
                .map(TemporalEntry::getValue)
                .hasValue("{\"name\":\"B\"}");
    }

    /**
     * The same key at a different effective time is a distinct row, not an
     * overwrite.
     */
    @Test
    void testSameKeyDifferentTimeIsSeparateRow() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate1", T1, "{\"v\":1}"));
        dao.create(entry(map, "gate1", T2, "{\"v\":2}"));

        assertThat(dao.count(map)).isEqualTo(2);
    }

    @Test
    void testDelete() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate1", T1, "{}"));

        assertThat(dao.delete(id(map, "gate1"))).isTrue();
        assertThat(dao.fetch(id(map, "gate1"))).isEmpty();
        // Deleting again reports nothing removed.
        assertThat(dao.delete(id(map, "gate1"))).isFalse();
    }

    @Test
    void testClearRemovesOnlyThatMap() {
        final String mapA = uniqueMap();
        final String mapB = uniqueMap();
        dao.create(entry(mapA, "k1", T1, "{}"));
        dao.create(entry(mapA, "k2", T1, "{}"));
        dao.create(entry(mapB, "k1", T1, "{}"));

        dao.clear(mapA);

        assertThat(dao.count(mapA)).isZero();
        assertThat(dao.count(mapB)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // getTimeRange
    // -----------------------------------------------------------------------

    @Test
    void testGetTimeRange() {
        final String map = uniqueMap();
        dao.create(entry(map, "k1", T1, "{}"));
        dao.create(entry(map, "k1", T3, "{}"));
        dao.create(entry(map, "k2", T2, "{}"));

        final TemporalStoreTimeRange range = dao.getTimeRange(map);

        assertThat(range.getMinEffectiveTimeMs()).isEqualTo(T1);
        assertThat(range.getMaxEffectiveTimeMs()).isEqualTo(T3);
    }

    @Test
    void testGetTimeRangeEmptyMap() {
        final TemporalStoreTimeRange range = dao.getTimeRange(uniqueMap());
        assertThat(range.getMinEffectiveTimeMs()).isNull();
        assertThat(range.getMaxEffectiveTimeMs()).isNull();
    }

    // -----------------------------------------------------------------------
    // fetchAll — latest version per key
    // -----------------------------------------------------------------------

    @Test
    void testFetchAllReturnsLatestVersionPerKeySortedByKey() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate", T1, "{\"v\":1}"));
        dao.create(entry(map, "gate", T2, "{\"v\":2}"));
        dao.create(entry(map, "gate", T3, "{\"v\":3}"));
        dao.create(entry(map, "computer", T1, "{\"v\":9}"));

        final List<TemporalEntry> all = dao.fetchAll(map);

        assertThat(all).hasSize(2);
        // Sorted by key ascending: "computer" before "gate".
        assertThat(all.get(0).getKey()).isEqualTo("computer");
        assertThat(all.get(1).getKey()).isEqualTo("gate");
        // "gate" resolves to its latest version (T3).
        assertThat(all.get(1).getEffectiveTimeMs()).isEqualTo(T3);
        assertThat(all.get(1).getValue()).isEqualTo("{\"v\":3}");
    }

    // -----------------------------------------------------------------------
    // find — temporal "valid at or before" resolution
    // -----------------------------------------------------------------------

    /**
     * The core temporal contract: a query at a given time returns, for each key,
     * the most recent version whose effective time is at or before that time.
     */
    @Test
    void testFindResolvesVersionValidAtOrBeforeQueryTime() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate", T1, "{\"v\":1}"));
        dao.create(entry(map, "gate", T2, "{\"v\":2}"));
        dao.create(entry(map, "gate", T3, "{\"v\":3}"));

        // Query strictly between T2 and T3 -> resolves to the T2 version.
        final String betweenT2AndT3 = "2021-06-01T00:00:00.000Z";
        final ResultPage<TemporalEntry> result = dao.find(criteriaAt(map, betweenT2AndT3));

        assertThat(result.getValues()).hasSize(1);
        assertThat(result.getValues().get(0).getEffectiveTimeMs()).isEqualTo(T2);
        assertThat(result.getValues().get(0).getValue()).isEqualTo("{\"v\":2}");
    }

    @Test
    void testFindAtExactEffectiveTimeIsInclusive() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate", T1, "{\"v\":1}"));
        dao.create(entry(map, "gate", T2, "{\"v\":2}"));

        final ResultPage<TemporalEntry> result = dao.find(criteriaAt(map, T2_ISO));

        assertThat(result.getValues()).hasSize(1);
        assertThat(result.getValues().get(0).getValue()).isEqualTo("{\"v\":2}");
    }

    @Test
    void testFindBeforeAnyVersionReturnsNothing() {
        final String map = uniqueMap();
        dao.create(entry(map, "gate", T2, "{\"v\":2}"));

        final ResultPage<TemporalEntry> result = dao.find(criteriaAt(map, T1_ISO));

        assertThat(result.getValues()).isEmpty();
    }

    @Test
    void testFindIsolatesByMapName() {
        final String mapA = uniqueMap();
        final String mapB = uniqueMap();
        dao.create(entry(mapA, "gate", T1, "{\"map\":\"A\"}"));
        dao.create(entry(mapB, "gate", T1, "{\"map\":\"B\"}"));

        final ResultPage<TemporalEntry> result = dao.find(criteriaAt(mapA, T3_ISO));

        assertThat(result.getValues()).hasSize(1);
        assertThat(result.getValues().get(0).getValue()).isEqualTo("{\"map\":\"A\"}");
    }

    // -----------------------------------------------------------------------
    // applyChanges — atomic batch
    // -----------------------------------------------------------------------

    @Test
    void testApplyChangesAppliesUpsertsAndDeletes() {
        final String map = uniqueMap();
        dao.create(entry(map, "stale", T1, "{}"));

        dao.applyChanges(List.of(
                ChangeOperation.upsert(entry(map, "gate", T1, "{\"v\":1}")),
                ChangeOperation.upsert(entry(map, "gate", T1, "{\"v\":2}")), // overwrites
                ChangeOperation.delete(id(map, "stale"))));

        assertThat(dao.count(map)).isEqualTo(1);
        assertThat(dao.fetch(id(map, "gate")))
                .map(TemporalEntry::getValue)
                .hasValue("{\"v\":2}");
    }

    /**
     * If any operation in the batch fails, the whole transaction must roll back
     * — no partial writes are visible.
     */
    @Test
    void testApplyChangesRollsBackOnError() {
        final String map = uniqueMap();

        assertThatThrownBy(() -> dao.applyChanges(List.of(
                ChangeOperation.upsert(entry(map, "good", T1, "{}")),
                // UPSERT with a null entry trips the guard mid-transaction.
                new ChangeOperation(ChangeOperation.Type.UPSERT, null, null))))
                .isInstanceOf(IllegalArgumentException.class);

        // The earlier valid upsert must have been rolled back.
        assertThat(dao.count(map)).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String uniqueMap() {
        return "testMap_" + MAP_SEQ.incrementAndGet();
    }

    private static TemporalEntry entry(final String map,
                                       final String key,
                                       final long time,
                                       final String value) {
        return new TemporalEntry(map, key, time, value);
    }

    private static TemporalEntryId id(final String map, final String key) {
        return new TemporalEntryId(map, key, TestUpdatableTemporalStoreDaoImplDB.T1);
    }

    /**
     * Builds a find criteria that pins the map name and a temporal lookup time,
     * mirroring the StroomQL the UI issues at a playback timeline position.
     */
    private static ExpressionCriteria criteriaAt(final String map, final String isoTime) {
        return new ExpressionCriteria(ExpressionOperator.builder()
                .addTerm(UpdatableTemporalStore.MAP_FIELD.getFldName(),
                        ExpressionTerm.Condition.EQUALS, map)
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, isoTime)
                .build());
    }
}
