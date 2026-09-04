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

package stroom.floormap.shared;

import stroom.floormap.shared.FloorMapEventState.Read;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the decision machine and the merge, which is the whole of the class worth testing.
 *
 * <p>Everything here is reachable only because the class is GWT-free and takes the wall clock as a
 * parameter — the same arrangement, for the same reason, as {@link FloorMapQueryThrottle} and
 * {@link FloorMapEntityAnimator}. A wall-clock timer would have been testable only by waiting.</p>
 */
class TestFloorMapEventState {

    private static final long T0 = 1_700_000_000_000L;
    private static final double NOW = 10_000.0;

    // -----------------------------------------------------------------------
    // Deciding what to query
    // -----------------------------------------------------------------------

    /** Nothing is known yet, so the only sensible read is a full one. */
    @Test
    void testFirstCallAsksForABaseline() {
        final FloorMapEventState state = new FloorMapEventState();
        final Read read = state.nextRead(T0, NOW);
        assertThat(read.kind()).isEqualTo(Read.Kind.BASELINE);
        assertThat(read.from()).isEqualTo(T0 - FloorMapEventState.HORIZON_MS);
        assertThat(read.to()).isEqualTo(T0);
    }

    @Test
    void testAfterABaselineAnAdvancingTimeYieldsADelta() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        final Read read = state.nextRead(T0 + 300, NOW + 300);
        assertThat(read.kind()).isEqualTo(Read.Kind.DELTA);
        assertThat(read.from()).as("the delta tiles from the last read").isEqualTo(T0);
        assertThat(read.to()).isEqualTo(T0 + 300);
    }

    /** A scrub, step or re-read: the caller knows the cursor is meaningless and says so. */
    @Test
    void testRequestBaselineForcesOneOnTheNextCall() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        state.requestBaseline();
        assertThat(state.nextRead(T0 + 300, NOW + 300).kind()).isEqualTo(Read.Kind.BASELINE);
    }

    /**
     * An explicit request must not wait for the interval — a user who scrubs expects the map to
     * follow immediately, not up to a minute later.
     */
    @Test
    void testRequestBaselineOverridesTheNotBeforeInterval() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        // Well inside the interval, so staleness alone would not trigger one.
        state.requestBaseline();
        assertThat(state.nextRead(T0 + 100, NOW + 100).kind()).isEqualTo(Read.Kind.BASELINE);
    }

    /**
     * An explicit request is marked forced; every other route to a baseline is not.
     *
     * <p>Load-bearing rather than informational. The caller uses it to decide whether a baseline
     * already in flight may be abandoned: a forced one answers a jump the user has just made, so
     * the read in flight covers a position they have left, while a routine one asks the same
     * question the in-flight read is already answering. Getting this backwards gives either a
     * scrub that waits up to a minute for the map to follow, or a baseline that destroys and
     * reissues itself on every tick.</p>
     */
    @Test
    void testOnlyAnExplicitlyRequestedBaselineIsForced() {
        final FloorMapEventState state = new FloorMapEventState();

        // Opening: the initial request counts as explicit, because nothing is known.
        assertThat(state.nextRead(T0, NOW).forced()).isTrue();
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        state.requestBaseline();
        assertThat(state.nextRead(T0 + 100, NOW + 100).forced())
                .as("a scrub, step or re-read")
                .isTrue();
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0 + 100);

        // The interval coming round is routine, not a jump.
        final Read periodic = state.nextRead(
                T0 + 200, NOW + 100 + FloorMapEventState.BASELINE_INTERVAL_MS + 1);
        assertThat(periodic.kind()).isEqualTo(Read.Kind.BASELINE);
        assertThat(periodic.forced()).as("the periodic baseline").isFalse();
    }

    /** A structural condition that has gone stale is still routine, not a user jump. */
    @Test
    void testAStructurallyDueBaselineIsNotForced() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        // Backward time — structurally due — and stale enough to be issued rather than held.
        final Read read = state.nextRead(
                T0 - 5_000, NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1);
        assertThat(read.kind()).isEqualTo(Read.Kind.BASELINE);
        assertThat(read.forced()).isFalse();
    }

    /** Nothing but a baseline is ever forced, so a caller cannot read the flag off a delta. */
    @Test
    void testADeltaAndAHeldReadAreNeverForced() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        assertThat(state.nextRead(T0 + 300, NOW + 300).forced()).isFalse();

        // A structural condition inside the interval is held, not issued.
        final FloorMapEventState held = new FloorMapEventState();
        held.nextRead(T0, NOW);
        final Read none = held.nextRead(T0 + 300, NOW + 300);
        assertThat(none.kind()).isEqualTo(Read.Kind.NONE);
        assertThat(none.forced()).isFalse();
    }

    /**
     * A loop wrap gets its baseline within a second, not within a minute.
     *
     * <p>The case that makes the two not-before intervals worth having separately. Looping is the
     * default, and a wrap puts the timeline behind the cursor — so no delta can correct it and the
     * map shows positions from the end of the range until a baseline runs. Holding that back for
     * {@link FloorMapEventState#BASELINE_INTERVAL_MS} would freeze the map through most of every
     * pass.</p>
     */
    @Test
    void testALoopWrapIsBaselinedWithinTheJumpIntervalNotTheBaselineInterval() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0 + 60_000);

        // The wrap itself, immediately after the baseline landed: too soon, so hold.
        final double wrapAt = NOW + 10;
        assertThat(state.nextRead(T0, wrapAt).kind()).isEqualTo(Read.Kind.NONE);

        // A second later it is issued, rather than waiting out the routine interval.
        final Read read = state.nextRead(T0 + 20, NOW + FloorMapEventState.JUMP_INTERVAL_MS + 1);
        assertThat(read.kind()).isEqualTo(Read.Kind.BASELINE);
        assertThat(read.forced()).as("a wrap is rate-limited, not forced").isFalse();
    }

    /**
     * A degenerate wrap — a range so short, or a speed so high, that playback passes the end time
     * on every frame — is capped rather than issuing a whole-store read per frame.
     */
    @Test
    void testAPerFrameWrapIsCappedAtTheJumpInterval() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0 + 60_000);

        int issued = 0;
        // 120 frames at 60 fps: just under two seconds of wall clock, wrapping on every one.
        for (int frame = 1; frame <= 120; frame++) {
            if (state.nextRead(T0, NOW + frame * 16.0).kind() == Read.Kind.BASELINE) {
                issued++;
            }
        }
        assertThat(issued)
                .as("two seconds of per-frame wrapping must not mean 120 whole-store reads")
                .isEqualTo(1);
    }

    /**
     * A store that has never answered backs off to the routine interval, not the jump interval.
     *
     * <p>The distinction matters because the two conditions mean different things. A jump means
     * the state is wrong now and only a baseline can fix it. Nothing having landed at all means the
     * baseline itself is failing — a truncated one still applies — and retrying a broken store
     * every second scans the whole store each time to learn nothing new.</p>
     */
    @Test
    void testNothingHavingLandedBacksOffFurtherThanAJumpDoes() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.onBaselineFailed();

        assertThat(state.nextRead(T0 + 20, NOW + FloorMapEventState.JUMP_INTERVAL_MS + 1).kind())
                .as("the jump interval must not shorten the retry for a failing store")
                .isEqualTo(Read.Kind.NONE);
        assertThat(state.nextRead(
                        T0 + 40, NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1).kind())
                .isEqualTo(Read.Kind.BASELINE);
    }

    /**
     * A repeated tick at the same instant reads nothing, and does not re-baseline every second.
     *
     * <p>The regression this pins. {@code t <= lastQueriedTime} classified a repeated tick as a
     * jump, putting it on {@link FloorMapEventState#JUMP_INTERVAL_MS} — so a timeline sitting still
     * re-read the whole store every second for as long as the document stayed open. A new document
     * opens in exactly that state: {@code selectedTime} starts at 0, so the first baseline stamps
     * 0 and every subsequent read is at 0 too.</p>
     *
     * <p>Nothing has been skipped at the same instant, so there is nothing to fetch — and a delta
     * would be the zero-width range that matches no rows on any store.</p>
     */
    @Test
    void testTheSameInstantAgainReadsNothingRatherThanReBaselining() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        int baselines = 0;
        // Ten seconds of ticks at a standstill: ten chances to re-baseline on the 1 s jump interval.
        for (int tick = 1; tick <= 33; tick++) {
            if (state.nextRead(T0, NOW + tick * 300.0).kind() == Read.Kind.BASELINE) {
                baselines++;
            }
        }
        assertThat(baselines)
                .as("a standstill must not re-read the whole store every second")
                .isZero();
    }

    /** The routine interval still reaches a standstill, so a late arrival is not stranded. */
    @Test
    void testAStandstillStillGetsTheRoutineBaseline() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        assertThat(state.nextRead(T0, NOW + 5_000).kind()).isEqualTo(Read.Kind.NONE);
        assertThat(state.nextRead(T0, NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1).kind())
                .as("the 60 s cadence is the standstill's catch-up")
                .isEqualTo(Read.Kind.BASELINE);
    }

    /** A loop wrap or a backward scrub: a delta range would be inverted or empty. */
    @Test
    void testNonAdvancingTimeYieldsABaselineNotANegativeRange() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        assertThat(state.nextRead(T0 - 5_000,
                        NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1).kind())
                .as("backward time must never produce a range with from > to")
                .isEqualTo(Read.Kind.BASELINE);
    }

    /** Past the horizon a delta is no cheaper than the baseline it would replace. */
    @Test
    void testADeltaWiderThanTheHorizonYieldsABaseline() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        final long farAhead = T0 + FloorMapEventState.HORIZON_MS + 1;
        assertThat(state.nextRead(farAhead,
                        NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1).kind())
                .isEqualTo(Read.Kind.BASELINE);
    }

    @Test
    void testTheWallClockIntervalTriggersABaseline() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        assertThat(state.nextRead(T0 + 300, NOW + 300).kind())
                .as("inside the interval, an advancing timeline gives deltas")
                .isEqualTo(Read.Kind.DELTA);
        state.applyDelta(List.of(), T0 + 300);

        assertThat(state.nextRead(
                        T0 + 600, NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1).kind())
                .as("past the interval, the same advancing timeline gives a baseline")
                .isEqualTo(Read.Kind.BASELINE);
    }

    /**
     * The tab was hidden for a while. On return the correction must run at once, not on whatever
     * tick happens to fall due next.
     */
    @Test
    void testALongClockGapYieldsABaselineOnTheVeryNextCall() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);
        state.applyDelta(List.of(), T0 + 300);

        assertThat(state.nextRead(T0 + 600, NOW + 600_000).kind())
                .isEqualTo(Read.Kind.BASELINE);
    }

    // -----------------------------------------------------------------------
    // The blocker: a baseline that never applies must not re-arm every tick
    // -----------------------------------------------------------------------

    /**
     * A failed baseline must fall back to the interval, not retry per tick.
     *
     * <p>This is the defect that got through four review rounds. Only an <em>apply</em> stamps
     * {@code lastQueriedTime}, so a baseline that fails leaves nothing known and every structural
     * condition armed. Without the not-before, the next tick asks for another full read, and the
     * next, for ever — back-to-back whole-store scans, a permanently empty map, and no delta ever
     * running. A never-written Plan B store would be queried three times a second.</p>
     */
    @Test
    void testAFailedBaselineIsNotReissuedOnTheNextTick() {
        final FloorMapEventState state = new FloorMapEventState();
        assertThat(state.nextRead(T0, NOW).kind()).isEqualTo(Read.Kind.BASELINE);
        state.onBaselineFailed();

        assertThat(state.nextRead(T0 + 300, NOW + 300).kind())
                .as("nothing known and a baseline structurally due, but it must WAIT rather than "
                    + "re-issue a whole-store read on every tick")
                .isEqualTo(Read.Kind.NONE);
        assertThat(state.known()).isEmpty();

        // ...and it does retry, once the interval has passed.
        assertThat(state.nextRead(
                        T0 + 600, NOW + FloorMapEventState.BASELINE_INTERVAL_MS + 1).kind())
                .isEqualTo(Read.Kind.BASELINE);
    }

    /**
     * A truncated baseline applies its rows rather than being discarded, so the map shows real
     * positions and the tick after is a delta.
     */
    @Test
    void testATruncatedBaselineAppliesAndTheNextTickIsADelta() {
        final FloorMapEventState state = new FloorMapEventState();
        state.nextRead(T0, NOW);

        assertThat(state.applyTruncatedBaseline(List.of(entity("alice", "desk-1")), T0))
                .as("warns the first time")
                .isTrue();
        assertThat(state.known()).hasSize(1);

        final Read next = state.nextRead(T0 + 300, NOW + 300);
        assertThat(next.kind())
                .as("a truncated baseline still counts as a read that landed")
                .isEqualTo(Read.Kind.DELTA);
        assertThat(next.from()).isEqualTo(T0);
    }

    /** Truncation on a busy store is permanent, so it must be reported once, not every minute. */
    @Test
    void testTruncationWarnsOnlyOnce() {
        final FloorMapEventState state = new FloorMapEventState();
        assertThat(state.applyTruncatedBaseline(List.of(entity("alice", "desk-1")), T0)).isTrue();
        assertThat(state.applyTruncatedBaseline(List.of(entity("alice", "desk-1")), T0 + 1)).isFalse();
        assertThat(state.applyTruncatedBaseline(List.of(entity("alice", "desk-1")), T0 + 2)).isFalse();
    }

    /** A truncated read is no evidence that an absent entity is gone, so it must not prune. */
    @Test
    void testATruncatedBaselineDoesNotPrune() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1"), entity("bob", "desk-3")), T0);

        state.applyTruncatedBaseline(List.of(entity("alice", "desk-9")), T0 + 1);

        assertThat(state.known())
                .extracting(FloorMapObject::getId)
                .as("bob is absent from a truncated read, which says nothing about bob")
                .containsExactly("alice", "bob");
        assertThat(locationOf(state, "alice")).isEqualTo("desk-9");
    }

    // -----------------------------------------------------------------------
    // Merging
    // -----------------------------------------------------------------------

    /** The whole point: an entity that did not move keeps its position. */
    @Test
    void testADeltaLeavesAbsentEntitiesInPlace() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1"), entity("bob", "desk-3")), T0);

        state.applyDelta(List.of(entity("alice", "desk-9")), T0 + 300);

        assertThat(locationOf(state, "alice")).isEqualTo("desk-9");
        assertThat(locationOf(state, "bob")).isEqualTo("desk-3");
    }

    @Test
    void testADeltaAddsAnEntityNeverSeenBefore() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        state.applyDelta(List.of(entity("carol", "desk-7")), T0 + 300);

        assertThat(state.known()).extracting(FloorMapObject::getId)
                .containsExactly("alice", "carol");
    }

    /**
     * A baseline replaces wholesale, so an entity idle beyond the horizon drops out.
     *
     * <p>Required, not incidental: {@link FloorMapGroupSnapshot} documents that its positioned
     * count is not a head-count of who is present. State that never shrank would quietly make it
     * one.</p>
     */
    @Test
    void testABaselinePrunesAnEntityItDoesNotMention() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1"), entity("bob", "desk-3")), T0);

        state.applyBaseline(List.of(entity("alice", "desk-1")), T0 + 1);

        assertThat(state.known()).extracting(FloorMapObject::getId).containsExactly("alice");
    }

    /**
     * An empty baseline that completed cleanly is the truth and must be applied.
     *
     * <p>The caller is responsible for only reaching here on an error-free completion: a failed
     * Plan B scan reports its error and <em>still</em> signals completion, so a broken store
     * arrives looking exactly like an empty horizon.</p>
     */
    @Test
    void testAnEmptyBaselineEmptiesTheKnownSet() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        state.applyBaseline(List.of(), T0 + 1);

        assertThat(state.known()).isEmpty();
    }

    /** Draw order must not reshuffle when a position changes, or the canvas flickers. */
    @Test
    void testOrderIsStableAcrossAnUpsert() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(
                List.of(entity("alice", "a"), entity("bob", "b"), entity("carol", "c")), T0);

        state.applyDelta(List.of(entity("alice", "moved")), T0 + 300);

        assertThat(state.known()).extracting(FloorMapObject::getId)
                .containsExactly("alice", "bob", "carol");
    }

    // -----------------------------------------------------------------------
    // Ownership
    // -----------------------------------------------------------------------

    /**
     * The state must hand out copies, because the render loop mutates what it is given.
     *
     * <p>{@code FloorMapLocationResolver.resolve} passes coordinate-bearing entities through by
     * identity and the canvas then calls {@code setImageFact(...)} on every drawn object once a
     * frame. Sharing instances would mean the render loop writing into this class's state sixty
     * times a second.</p>
     */
    @Test
    void testKnownReturnsCopiesSoCallersCannotMutateState() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);

        final FloorMapObject handedOut = state.known().getFirst();
        handedOut.setX(999);
        handedOut.setLocationRef("tampered");

        final FloorMapObject stillHeld = state.known().getFirst();
        assertThat(stillHeld.getX()).isNotEqualTo(999);
        assertThat(stillHeld.getLocationRef()).isEqualTo("desk-1");
    }

    /** Copying must not drag render decorations into state — that is the aliasing being avoided. */
    @Test
    void testTrailAndImageFactAreNotCarriedIntoState() {
        final FloorMapEventState state = new FloorMapEventState();
        final FloorMapObject decorated = entity("alice", "desk-1");
        decorated.setTrail(List.of(new double[]{1, 2, 0.5}));

        state.applyBaseline(List.of(decorated), T0);

        assertThat(state.known().getFirst().getTrail()).isNull();
    }

    @Test
    void testClearForgetsEverythingAndAsksForABaselineAgain() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(List.of(entity("alice", "desk-1")), T0);
        state.applyTruncatedBaseline(List.of(entity("alice", "desk-1")), T0 + 1);

        state.clear();

        assertThat(state.known()).isEmpty();
        assertThat(state.nextRead(T0 + 300, NOW + 300).kind()).isEqualTo(Read.Kind.BASELINE);
        assertThat(state.applyTruncatedBaseline(List.of(entity("alice", "a")), T0 + 2))
                .as("a fresh document warns about truncation again")
                .isTrue();
    }

    @Test
    void testNullAndIdlessObjectsAreIgnoredRatherThanThrowing() {
        final FloorMapEventState state = new FloorMapEventState();
        state.applyBaseline(null, T0);
        assertThat(state.known()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** An event object as the parser produces one: a location reference, not yet resolved. */
    private static FloorMapObject entity(final String id, final String locationRef) {
        final FloorMapObject object = new FloorMapObject(id, "person", 0, 0);
        object.setLocationRef(locationRef);
        return object;
    }

    private static String locationOf(final FloorMapEventState state, final String id) {
        return state.known().stream()
                .filter(o -> id.equals(o.getId()))
                .map(FloorMapObject::getLocationRef)
                .findFirst()
                .orElse(null);
    }
}
