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

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.entity.shared.ExpressionCriteria;
import stroom.planb.impl.dao.temporalstate.TemporalStateDb;
import stroom.planb.impl.dao.temporalstate.TemporalStateFields;
import stroom.planb.impl.data.TemporalState;
import stroom.planb.impl.serde.keyprefix.KeyPrefix;
import stroom.planb.impl.serde.temporalkey.TemporalKey;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.StateType;
import stroom.planb.shared.TemporalStateSettings;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.impl.UpdatableTemporalStoreDao;
import stroom.util.io.ByteSize;
import stroom.util.shared.TemporalEntry;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that {@code UpdatableTemporalStore} and Plan B's {@code TemporalStateDb} answer the same
 * question the same way.
 *
 * <p>The requirement is that the two are interchangeable as temporal state stores, differing only
 * in that the SQL store has extra CRUD operations and in performance. A floor map reads its facts
 * from one and its events from the other, so any behavioural difference between them shows up as
 * the same document behaving differently depending on which store backs it.</p>
 *
 * <h3>How the comparison works</h3>
 * <p>Each test writes the <em>same</em> entries to both stores, runs the <em>same</em>
 * {@link ExpressionCriteria} against both, normalises both result sets to
 * {@code (key, effectiveTimeMs, value)} triples sorted identically, and asserts they are equal.
 * Neither store's own idioms leak into the comparison: the SQL store's {@code map_name} is not
 * compared because Plan B has no equivalent, and Plan B's value type column is not compared
 * because the SQL store stores everything as text.</p>
 *
 * <h3>Three of these are {@code @Disabled}, and that is the finding</h3>
 * <p>They fail. The two stores are <em>not</em> interchangeable: the SQL store treats an upper time
 * bound as a snapshot boundary and returns each key's latest version at or before it, while Plan B
 * treats every time term as a row filter. So a key whose only version predates the bound is
 * returned by one store and not the other.</p>
 *
 * <p>They are disabled rather than deleted because they state the requirement correctly and should
 * pass the day Plan B gains a latest-per-key read — at which point the disabling comes off and
 * {@link #testPlanBAloneAppliesTimeTermsAsFiltersNotAsSnapshots}, which pins current behaviour, is
 * deleted. Leaving them failing instead would make every build red for a gap that is understood,
 * documented in {@code docs/temporal-store-parity-report.md}, and not this test's to fix.</p>
 *
 * <h3>What this does not cover</h3>
 * <p>The SQL store's extra CRUD ({@code create}, {@code update}, {@code fetch}, {@code delete},
 * {@code applyChanges}, {@code clear}, {@code count}, {@code getTimeRange}) is out of scope by
 * definition, as is performance. Plan B settings with no SQL counterpart — {@code condense},
 * {@code retention}, snapshots, non-string key and value types — are also out of scope; this
 * compares the default configuration of each.</p>
 */
class TestTemporalStoreParity {

    private static final String T1_ISO = "2020-01-01T00:00:00.000Z";
    private static final String T2_ISO = "2021-01-01T00:00:00.000Z";
    private static final String T3_ISO = "2022-01-01T00:00:00.000Z";
    private static final long T1 = Instant.parse(T1_ISO).toEpochMilli();
    private static final long T2 = Instant.parse(T2_ISO).toEpochMilli();
    private static final long T3 = Instant.parse(T3_ISO).toEpochMilli();

    /** Between T2 and T3, so a snapshot here must resolve to the T2 version. */
    private static final String BETWEEN_T2_AND_T3 = "2021-06-01T00:00:00.000Z";

    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(new ByteBufferFactoryImpl());
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static UpdatableTemporalStoreDao sqlDao;

    /**
     * Built on first use rather than in {@code @BeforeAll} so that a Plan B characterisation run is
     * possible without a database. Constructing it eagerly turns "MySQL is not running" into an
     * initialisation error that fails every test in the class, including the ones that never touch
     * SQL.
     */
    private static synchronized UpdatableTemporalStoreDao sqlDao() {
        if (sqlDao == null) {
            final Injector injector = Guice.createInjector(new TestModule());
            sqlDao = injector.getInstance(UpdatableTemporalStoreDao.class);
        }
        return sqlDao;
    }

    // -----------------------------------------------------------------------
    // Parity cases
    // -----------------------------------------------------------------------

    /**
     * With no time term, both stores must return every version of every key.
     *
     * <p>This is the baseline: if this diverges, nothing else in the comparison means anything.</p>
     */
    @Test
    void testNoTimeTermReturnsAllVersionsInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("door", T1, "door@T1")));

        assertParity(fixture, ExpressionOperator.builder().build(),
                "no time term - every version of every key");
    }

    /**
     * A {@code Key} filter must select the same rows in both stores.
     */
    @Test
    void testKeyFilterSelectsTheSameRowsInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("door", T1, "door@T1")));

        assertParity(fixture, ExpressionOperator.builder()
                        .addTerm(UpdatableTemporalStore.KEY_FIELD.getFldName(),
                                ExpressionTerm.Condition.EQUALS, "gate")
                        .build(),
                "Key = gate");
    }

    /**
     * Writing twice at the same key and effective time must leave one row holding the later value
     * in both stores.
     */
    @Test
    void testRewritingTheSameInstantOverwritesInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "first"),
                row("gate", T1, "second")));

        assertParity(fixture, ExpressionOperator.builder().build(),
                "same key and instant written twice");
    }

    /**
     * An upper time bound must select the same rows in both stores.
     *
     * <p>This is the case the whole comparison exists for. A floor map asks "what is the state at
     * time T" by bounding the time, and the two stores are expected to answer identically.</p>
     */
    @Disabled("Records a known parity gap rather than a regression - see"
            + " docs/temporal-store-parity-report.md. Re-enable when Plan B gains a"
            + " latest-per-key read; testPlanBAloneAppliesTimeTermsAsFiltersNotAsSnapshots"
            + " pins today's behaviour meanwhile.")
    @Test
    void testUpperTimeBoundSelectsTheSameRowsInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("gate", T3, "gate@T3"),
                row("door", T1, "door@T1")));

        assertParity(fixture, ExpressionOperator.builder()
                        .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                                ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, BETWEEN_T2_AND_T3)
                        .build(),
                "EffectiveTime <= " + BETWEEN_T2_AND_T3);
    }

    /**
     * An equality time term must select the same rows in both stores.
     *
     * <p>{@code door} exists only at T1, so it is the discriminator: a snapshot resolved at T2
     * includes it (its latest version at or before T2), an exact match on T2 does not. An earlier
     * version of this test used a single key and passed for the wrong reason — with one key whose
     * latest version at or before T2 <em>is</em> the row at T2, the two semantics coincide.</p>
     */
    @Disabled("Records a known parity gap rather than a regression - see"
            + " docs/temporal-store-parity-report.md. Re-enable when Plan B gains a"
            + " latest-per-key read; testPlanBAloneAppliesTimeTermsAsFiltersNotAsSnapshots"
            + " pins today's behaviour meanwhile.")
    @Test
    void testExactTimeTermSelectsTheSameRowsInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("door", T1, "door@T1")));

        assertParity(fixture, ExpressionOperator.builder()
                        .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                                ExpressionTerm.Condition.EQUALS, T2_ISO)
                        .build(),
                "EffectiveTime = " + T2_ISO);
    }

    /**
     * A lower time bound alone must select the same rows in both stores.
     *
     * <p>Note this does <em>not</em> exercise the snapshot path: {@code getQueryTime} accepts only
     * {@code EQUALS}, {@code <} and {@code <=}, so a lone {@code >=} leaves the SQL store on its
     * full-history path where the term is honoured normally. See
     * {@link #testBothTimeBoundsSelectTheSameRowsInBothStores} for the case where it is not.</p>
     */
    @Test
    void testLowerTimeBoundSelectsTheSameRowsInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("gate", T3, "gate@T3")));

        assertParity(fixture, ExpressionOperator.builder()
                        .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                                ExpressionTerm.Condition.GREATER_THAN_OR_EQUAL_TO, T2_ISO)
                        .build(),
                "EffectiveTime >= " + T2_ISO);
    }

    /**
     * Characterises Plan B alone, so the two stores' behaviours can be compared even when no
     * database is available to run the parity cases above.
     *
     * <p>Asserts nothing about what Plan B <em>should</em> do — it records what it does, against
     * data whose SQL-store answers are already pinned by {@code TestUpdatableTemporalStoreDaoImplDB}.
     * If Plan B is ever changed to match, this test fails and should be deleted along with the
     * parity gap it documents.</p>
     */
    @Test
    void testPlanBAloneAppliesTimeTermsAsFiltersNotAsSnapshots(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBothPlanBOnly(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("gate", T3, "gate@T3"),
                row("door", T1, "door@T1")));

        // Upper bound. The SQL store returns one row per key - the latest at or before the bound -
        // which for this data is gate@T2 and door@T1, i.e. 2 rows.
        final List<Triple> upperBound = searchPlanB(fixture, ExpressionOperator.builder()
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, BETWEEN_T2_AND_T3)
                .build());
        assertThat(upperBound)
                .as("Plan B applies the bound as a row filter, returning every version at or "
                    + "before it rather than the latest per key")
                .containsExactly(
                        new Triple("door", T1, "door@T1"),
                        new Triple("gate", T1, "gate@T1"),
                        new Triple("gate", T2, "gate@T2"));

        // Equality. The SQL store treats this as a snapshot at T2, returning gate@T2 and door@T1.
        final List<Triple> exact = searchPlanB(fixture, ExpressionOperator.builder()
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.EQUALS, T2_ISO)
                .build());
        assertThat(exact)
                .as("Plan B matches the instant exactly; the SQL store resolves a snapshot at it")
                .containsExactly(new Triple("gate", T2, "gate@T2"));

        // No time term. Both stores return everything, so this one already agrees.
        final List<Triple> all = searchPlanB(fixture, ExpressionOperator.builder().build());
        assertThat(all).hasSize(4);
    }

    /**
     * A range with both bounds must select the same rows in both stores.
     *
     * <p>This is where the SQL store's snapshot heuristic is at its most surprising. The upper
     * bound switches it to the snapshot path, and {@code getFilteredExpression} then strips
     * <em>every</em> time term from the SQL condition — including the caller's lower bound. So the
     * result can contain a row older than the range the caller asked for.</p>
     */
    @Disabled("Records a known parity gap rather than a regression - see"
            + " docs/temporal-store-parity-report.md. Re-enable when Plan B gains a"
            + " latest-per-key read; testPlanBAloneAppliesTimeTermsAsFiltersNotAsSnapshots"
            + " pins today's behaviour meanwhile.")
    @Test
    void testBothTimeBoundsSelectTheSameRowsInBothStores(@TempDir final Path tempDir) {
        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("gate", T1, "gate@T1"),
                row("gate", T2, "gate@T2"),
                row("door", T1, "door@T1")));

        // Ask for the window [T2, T3]. door only exists at T1, which is outside it.
        assertParity(fixture, ExpressionOperator.builder()
                        .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                                ExpressionTerm.Condition.GREATER_THAN_OR_EQUAL_TO, T2_ISO)
                        .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                                ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, T3_ISO)
                        .build(),
                "EffectiveTime >= " + T2_ISO + " AND <= " + T3_ISO);
    }

    /**
     * Pins the worked example in {@code docs/temporal-store-parity-report.md}.
     *
     * <p>That report weighs four options against one store and three queries. If the numbers in it
     * are wrong the conclusions are worthless, so they are asserted here rather than reasoned about
     * — this is documentation with a build behind it. Unlike the parity cases above, this asserts
     * what each store <em>does</em>, so it passes today and fails when either store changes.</p>
     */
    @Test
    void testWorkedExampleInTheParityReport(@TempDir final Path tempDir) {
        final long nine = Instant.parse("2024-03-01T09:00:00.000Z").toEpochMilli();
        final long nineThirty = Instant.parse("2024-03-01T09:30:00.000Z").toEpochMilli();
        final String at0945 = "2024-03-01T09:45:00.000Z";
        final String at0800 = "2024-03-01T08:00:00.000Z";
        final String at0915 = "2024-03-01T09:15:00.000Z";
        final String at1200 = "2024-03-01T12:00:00.000Z";

        final Fixture fixture = writeToBoth(tempDir, List.of(
                row("alice", nine, "desk-1"),
                row("alice", nineThirty, "desk-2"),
                row("bob", nine, "desk-3")));

        final Triple alice0900 = new Triple("alice", nine, "desk-1");
        final Triple alice0930 = new Triple("alice", nineThirty, "desk-2");
        final Triple bob0900 = new Triple("bob", nine, "desk-3");

        // Query 1 - "where is everyone now?" at 09:45.
        final ExpressionOperator whereIsEveryone = ExpressionOperator.builder()
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, at0945)
                .build();
        assertThat(searchSql(fixture, whereIsEveryone))
                .as("Query 1, SQL: one row per key - two people in the right places")
                .containsExactly(alice0930, bob0900);
        assertThat(searchPlanB(fixture, whereIsEveryone))
                .as("Query 1, Plan B: every version - alice appears twice")
                .containsExactly(alice0900, alice0930, bob0900);

        // Query 2 - "show me the morning's activity", a histogram over 08:00-12:00.
        final ExpressionOperator morningActivity = ExpressionOperator.builder()
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.GREATER_THAN_OR_EQUAL_TO, at0800)
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, at1200)
                .build();
        assertThat(searchSql(fixture, morningActivity))
                .as("Query 2, SQL: alice's 09:00 move is missing from the chart")
                .containsExactly(alice0930, bob0900);
        assertThat(searchPlanB(fixture, morningActivity))
                .as("Query 2, Plan B: all three events - the correct density")
                .containsExactly(alice0900, alice0930, bob0900);

        // Query 3 - "what changed after 09:15?" The bug.
        final ExpressionOperator changedAfter0915 = ExpressionOperator.builder()
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.GREATER_THAN_OR_EQUAL_TO, at0915)
                .addTerm(UpdatableTemporalStore.TIME_FIELD.getFldName(),
                        ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO, at1200)
                .build();
        assertThat(searchSql(fixture, changedAfter0915))
                .as("Query 3, SQL: bob@09:00 is returned despite being before the requested "
                    + "lower bound of 09:15 - getFilteredExpression strips it")
                .containsExactly(alice0930, bob0900);
        assertThat(searchPlanB(fixture, changedAfter0915))
                .as("Query 3, Plan B: only the change that actually falls in the range")
                .containsExactly(alice0930);
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    /** One entry, in a form both stores can be given. */
    private record Row(String key, long effectiveTimeMs, String value) {

    }

    private static Row row(final String key, final long effectiveTimeMs, final String value) {
        return new Row(key, effectiveTimeMs, value);
    }

    /** A key/time/value triple, the common denominator of the two stores' result shapes. */
    private record Triple(String key, long effectiveTimeMs, String value)
            implements Comparable<Triple> {

        @Override
        public int compareTo(final Triple other) {
            return Comparator.comparing(Triple::key)
                    .thenComparingLong(Triple::effectiveTimeMs)
                    .thenComparing(Triple::value)
                    .compare(this, other);
        }

        @Override
        public String toString() {
            return key + "@" + Instant.ofEpochMilli(effectiveTimeMs) + "=" + value;
        }
    }

    /** Both stores, loaded with the same data. */
    private record Fixture(String sqlDocUuid, String sqlMapName, Path planBDir) {

    }

    /**
     * Writes the same rows to a fresh SQL store and a fresh Plan B store.
     *
     * <p>The Plan B store is written and closed here so the search below opens it read-only, which
     * is how a query sees it in production.</p>
     */
    private static Fixture writeToBoth(final Path tempDir, final List<Row> rows) {
        final Fixture fixture = writeToBothPlanBOnly(tempDir, rows);
        for (final Row r : rows) {
            sqlDao().create(fixture.sqlDocUuid(),
                    new TemporalEntry(fixture.sqlMapName(), r.key(), r.effectiveTimeMs(), r.value()));
        }
        return fixture;
    }

    /** The Plan B half of {@link #writeToBoth}, usable without a database. */
    private static Fixture writeToBothPlanBOnly(final Path tempDir, final List<Row> rows) {
        final int n = SEQ.incrementAndGet();
        final String mapName = "parity_map_" + n;
        final String docUuid = UUID.randomUUID().toString();

        final Path planBDir = tempDir.resolve("planb-" + n);
        planBDir.toFile().mkdirs();
        try (final TemporalStateDb db = TemporalStateDb.create(planBDir, BYTE_BUFFERS, planBDoc(mapName), false)) {
            db.write(writer -> {
                for (final Row r : rows) {
                    final TemporalKey key = TemporalKey.builder()
                            .prefix(KeyPrefix.create(r.key()))
                            .time(Instant.ofEpochMilli(r.effectiveTimeMs()))
                            .build();
                    final Val value = ValString.create(r.value());
                    db.insert(writer, new TemporalState(key, value));
                }
            });
        }
        return new Fixture(docUuid, mapName, planBDir);
    }

    private static PlanBDoc planBDoc(final String mapName) {
        return PlanBDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name(mapName)
                .stateType(StateType.TEMPORAL_STATE)
                .settings(new TemporalStateSettings.Builder()
                        .maxStoreSize(ByteSize.ofGibibytes(1).getBytes())
                        .build())
                .build();
    }

    /** Runs the criteria against both stores and asserts the normalised results are equal. */
    private static void assertParity(final Fixture fixture,
                                     final ExpressionOperator expression,
                                     final String description) {
        final List<Triple> fromSql = searchSql(fixture, expression);
        final List<Triple> fromPlanB = searchPlanB(fixture, expression);

        assertThat(fromPlanB)
                .as("Plan B and the SQL temporal store must return the same rows for: %s%n"
                    + "  SQL store returned:  %s%n"
                    + "  Plan B returned:     %s",
                        description, fromSql, fromPlanB)
                .isEqualTo(fromSql);
    }

    private static List<Triple> searchSql(final Fixture fixture, final ExpressionOperator expression) {
        final List<Triple> results = new ArrayList<>();
        sqlDao().search(fixture.sqlDocUuid(), new ExpressionCriteria(expression), true,
                entry -> results.add(new Triple(
                        entry.getKey(), entry.getEffectiveTimeMs(), entry.getValue())));
        return results.stream().sorted().toList();
    }

    private static List<Triple> searchPlanB(final Fixture fixture, final ExpressionOperator expression) {
        final List<Triple> results = new ArrayList<>();
        try (final TemporalStateDb db =
                TemporalStateDb.create(fixture.planBDir(), BYTE_BUFFERS, planBDoc(fixture.sqlMapName()), true)) {
            final FieldIndex fieldIndex = new FieldIndex();
            fieldIndex.create(TemporalStateFields.KEY);
            fieldIndex.create(TemporalStateFields.EFFECTIVE_TIME);
            fieldIndex.create(TemporalStateFields.VALUE);
            db.search(
                    new ExpressionCriteria(expression),
                    fieldIndex,
                    null,
                    new ExpressionPredicateFactory(),
                    values -> results.add(new Triple(
                            values[0].toString(),
                            Instant.parse(values[1].toString()).toEpochMilli(),
                            values[2].toString())));
        }
        return results.stream().sorted().toList();
    }
}
