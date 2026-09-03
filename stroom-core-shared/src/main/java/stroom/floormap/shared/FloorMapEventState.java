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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Map tab's knowledge of where every entity is, and the decision of what to query next.
 *
 * <h3>Why this exists</h3>
 * <p>The Map tab used to re-read a 20-second window of the events store on every playback tick and
 * replace everything it knew. An entity with no events in that window was simply absent from the
 * result, which lost it from area membership, group counts and the accessible summary — and from
 * the canvas too whenever the animator took its teleport path (paused, or after a scrub, step or
 * loop). Plan B's {@code condense} setting made that reach data still being emitted: it keeps the
 * earliest entry of an identical run, so a stationary entity's surviving row falls outside every
 * future window and it vanishes.</p>
 *
 * <p>So positions are held here instead, updated by a cheap <b>delta</b> query per tick and
 * corrected by a bounded full <b>baseline</b> read. The window is gone; an entity idle up to
 * {@link #HORIZON_MS} stays on the map.</p>
 *
 * <h3>The two reads</h3>
 * <dl>
 *   <dt>Delta — {@code [prevT, T]}</dt>
 *   <dd>Only what changed since the last read. Upserted, so an entity absent from it keeps its
 *       known position.</dd>
 *   <dt>Baseline — {@code [T - HORIZON_MS, T]}</dt>
 *   <dd>Everything active within the horizon. Replaces wholesale, so an entity idle beyond the
 *       horizon drops out — which is what keeps
 *       {@link FloorMapGroupSnapshot}'s positioned count honest rather than letting it become a
 *       presence claim.</dd>
 * </dl>
 *
 * <h3>Why no lower bound would be wrong</h3>
 * <p>"State as at T" with no lower bound is the semantically correct read, and unusable: Plan B has
 * no server-side reduction, so it iterates the whole store in (key, time) order and a row cap
 * truncates in <em>key</em> order — returning every event of the alphabetically-first entities
 * rather than the latest event of all of them. A horizon bounds it with a failure mode an operator
 * can act on: "an entity with no events in the last six hours is not shown."</p>
 *
 * <h3>GWT-free on purpose</h3>
 * <p>No GWT types, and the wall clock is passed in rather than read, so the whole decision machine
 * is unit-testable on the JVM without waiting. Same reason and same shape as
 * {@link FloorMapQueryThrottle#shouldQuery(double)} and
 * {@link FloorMapEntityAnimator#advanceFrame(double, double)}.</p>
 */
public final class FloorMapEventState {

    /**
     * How far back a baseline reaches.
     *
     * <p>An entity with no events in this period is not shown. Six hours is longer than a working
     * session and short enough that a day's data does not arrive in one query.</p>
     */
    public static final long HORIZON_MS = 6L * 60 * 60 * 1000;

    /** How much wall clock must pass before a routine baseline is due. */
    public static final long BASELINE_INTERVAL_MS = 60_000L;

    /**
     * The shortest gap between baselines forced by the timeline jumping.
     *
     * <p>Much shorter than {@link #BASELINE_INTERVAL_MS} because it is guarding a different thing.
     * A jump makes the state wrong <em>now</em>: the cursor points somewhere the timeline has left,
     * so there is no delta that would fix it and nothing to show but stale positions until a
     * baseline runs. Holding those back for a minute would freeze the map through most of every
     * loop pass, and looping is the default.</p>
     *
     * <p>What it does guard is the degenerate wrap. Over a very short range, or at high speed, the
     * playback loop passes the end time on <em>every</em> frame — the hazard the throttle comment
     * on the wrap describes — so without a floor a wrap would mean a whole-store scan per frame.
     * A second caps that at something survivable, and the caller's in-flight check absorbs the
     * rest.</p>
     */
    public static final long JUMP_INTERVAL_MS = 1_000L;

    /**
     * Entity id to its last known <em>raw</em> event — the one carrying {@code locationRef} rather
     * than a resolved position, because the caller re-resolves refs against the current facts on
     * every facts poll. That is what lets a moved desk take its occupants with it.
     *
     * <p>{@link LinkedHashMap} so the surviving entities keep the order they were first seen,
     * which keeps the canvas's draw order stable between polls rather than reshuffling.
     * {@code latestPerEntity} uses one for the same reason.</p>
     */
    private final Map<String, FloorMapObject> known = new LinkedHashMap<>();

    /** The upper bound of the last read that landed. */
    private long lastQueriedTime;

    /** Whether any read has landed at all. Until one has, only a baseline makes sense. */
    private boolean started;

    /** When a baseline was last <em>issued</em> — see {@link #nextRead(long, double)}. */
    private double lastBaselineIssuedAt;

    /** Set by {@link #requestBaseline()}; overrides the not-before. */
    private boolean baselineRequested = true;

    /** So a persistently truncating store is reported once rather than every minute. */
    private boolean truncationWarned;

    // -----------------------------------------------------------------------
    // Deciding what to query
    // -----------------------------------------------------------------------

    /**
     * Forces a baseline on the next tick, immediately, ignoring the interval.
     *
     * <p>For discontinuities the caller knows about and the timeline cannot self-report: opening or
     * re-reading the document, a scrub commit, a step, and stopping at the end. Deliberately
     * <b>not</b> for a loop wrap, which fires per frame and is detected here instead by
     * {@code t <= lastQueriedTime}.</p>
     */
    public void requestBaseline() {
        baselineRequested = true;
    }

    /**
     * What to read for timeline position {@code t}, and stamps the decision.
     *
     * <p>Three outcomes, and the third is load-bearing. Only an <em>apply</em> stamps
     * {@link #lastQueriedTime}, so a baseline that never applies — one that failed — leaves
     * {@link #started} false and the conditions below armed. If "wait" could not be expressed
     * distinctly from "read a baseline", every tick would issue another whole-store read:
     * back-to-back scans, a permanently empty map, and no delta ever running. So a condition that
     * is not yet stale enough returns {@link Read.Kind#NONE}.</p>
     *
     * <p><b>How stale is stale enough depends on what is being held back</b>, and the two cases are
     * deliberately far apart.</p>
     * <ul>
     *   <li><b>Nothing has landed yet</b> — usually because the baseline is failing, since one that
     *       merely truncates still applies. Retrying a broken store faster than
     *       {@link #BASELINE_INTERVAL_MS} buys nothing and scans the whole store to find out.</li>
     *   <li><b>The timeline jumped</b> — a loop wrap, a backward scrub, or a gap wider than the
     *       horizon. Here the state is wrong right now and no delta can fix it, so waiting a minute
     *       would leave the map frozen; {@link #JUMP_INTERVAL_MS} applies instead.</li>
     * </ul>
     *
     * <p>{@link #requestBaseline()} overrides both, because a user who scrubs must not wait at all.
     * </p>
     *
     * @param t     the timeline position being moved to
     * @param nowMs the wall clock, passed in so this is testable
     */
    public Read nextRead(final long t, final double nowMs) {
        if (baselineRequested) {
            return issueBaseline(t, nowMs, true);
        }

        final double sinceBaseline = nowMs - lastBaselineIssuedAt;

        if (!started) {
            return sinceBaseline > BASELINE_INTERVAL_MS
                    ? issueBaseline(t, nowMs, false)
                    : Read.none();
        }

        // Backward, repeated, or a loop wrap: a delta range would be empty or inverted. Or beyond
        // the horizon, where a delta is no cheaper than the baseline it would replace.
        final boolean jumped = t <= lastQueriedTime || t - lastQueriedTime > HORIZON_MS;
        if (jumped) {
            return sinceBaseline > JUMP_INTERVAL_MS
                    ? issueBaseline(t, nowMs, false)
                    : Read.none();
        }

        if (sinceBaseline > BASELINE_INTERVAL_MS) {
            return issueBaseline(t, nowMs, false);
        }
        return Read.delta(lastQueriedTime, t);
    }

    private Read issueBaseline(final long t, final double nowMs, final boolean forced) {
        lastBaselineIssuedAt = nowMs;
        baselineRequested = false;
        return Read.baseline(t - HORIZON_MS, t, forced);
    }

    /**
     * What {@link #nextRead} decided: a range to read, or nothing to do this tick.
     *
     * <p>A plain class rather than a record because this package is GWT-compiled and nothing else
     * in it uses records.</p>
     */
    public static final class Read {

        /** Which read, or none. */
        public enum Kind {
            /** Everything active within the horizon. Replaces what is known. */
            BASELINE,
            /** Only what changed since the last read. Upserted over what is known. */
            DELTA,
            /** Nothing — a baseline is structurally due but was attempted too recently. */
            NONE
        }

        private static final Read NONE = new Read(Kind.NONE, 0, 0, false);

        private final Kind kind;
        private final long from;
        private final long to;
        private final boolean forced;

        private Read(final Kind kind, final long from, final long to, final boolean forced) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.forced = forced;
        }

        static Read baseline(final long from, final long to, final boolean forced) {
            return new Read(Kind.BASELINE, from, to, forced);
        }

        static Read delta(final long from, final long to) {
            return new Read(Kind.DELTA, from, to, false);
        }

        static Read none() {
            return NONE;
        }

        public Kind kind() {
            return kind;
        }

        /**
         * Whether this baseline came from an explicit {@link #requestBaseline()} rather than from
         * the interval or a structural condition.
         *
         * <p>The caller needs the distinction when a baseline is already in flight. A forced one
         * answers a jump the user just made, so the read in flight is for a position they have
         * already left and is worth abandoning. An unforced one is routine, and abandoning the
         * in-flight read to start an identical one would be the livelock this class exists to
         * avoid — so the caller skips it and lets the interval come round again.</p>
         */
        public boolean forced() {
            return forced;
        }

        /** Inclusive lower bound. Meaningless for {@link Kind#NONE}. */
        public long from() {
            return from;
        }

        /** Inclusive upper bound; the caller adds 1 because the generated term is exclusive. */
        public long to() {
            return to;
        }

        @Override
        public String toString() {
            return kind == Kind.NONE
                    ? "NONE"
                    : kind + "[" + from + ", " + to + "]" + (forced ? " forced" : "");
        }
    }

    // -----------------------------------------------------------------------
    // Applying what came back
    // -----------------------------------------------------------------------

    /**
     * Applies a complete baseline, replacing everything known.
     *
     * <p>Wholesale replacement is the point: an entity idle beyond the horizon is absent from
     * {@code all} and therefore drops out, so the positioned count falls honestly rather than
     * claiming a presence the store cannot support.</p>
     *
     * <p>The caller must only reach here for a baseline that <b>completed without errors</b>. A
     * failed Plan B scan reports its error and still signals completion, so a broken or
     * never-written store arrives looking exactly like a legitimately empty horizon; applying that
     * would blank the map and hide the fault. See {@link #onBaselineFailed()}.</p>
     */
    public void applyBaseline(final Collection<FloorMapObject> all, final long t) {
        known.clear();
        upsert(all);
        landed(t);
    }

    /**
     * Applies a baseline that was truncated by the row cap: upserts the rows and stamps, but does
     * <b>not</b> prune.
     *
     * <p>Two deliberate differences from {@link #applyBaseline}. It applies rather than discards,
     * because only an apply stamps {@code lastQueriedTime} — discarding leaves the caller asking
     * for another full read on the very next tick, for ever, with nothing on the map. And it does
     * not prune, because a truncated read is no evidence that an absent entity is gone; the rows
     * are complete per key in key order except possibly the boundary key, so one stale boundary
     * entity is the whole cost.</p>
     *
     * @return {@code true} the first time only, so the caller warns once and recommends enabling
     *         {@code condense} rather than repeating itself every minute
     */
    public boolean applyTruncatedBaseline(final Collection<FloorMapObject> rows, final long t) {
        upsert(rows);
        landed(t);
        final boolean warn = !truncationWarned;
        truncationWarned = true;
        return warn;
    }

    /**
     * Applies a delta, upserting it over what is already known.
     *
     * <p>An entity absent from the delta simply did not move; it keeps its known position. That is
     * the whole reason this class exists.</p>
     */
    public void applyDelta(final Collection<FloorMapObject> changed, final long t) {
        upsert(changed);
        landed(t);
    }

    /**
     * A baseline failed — it reported errors, or was destroyed in flight.
     *
     * <p>Keeps everything known and <b>clears the request</b>. Leaving it set would re-issue on the
     * very next tick, so a never-written store would be queried three times a second instead of
     * once a minute. The interval in {@link #nextRead(long, double)} is the retry.</p>
     */
    public void onBaselineFailed() {
        baselineRequested = false;
    }

    /** Records that a read landed at {@code t}, so the next tick can be a delta. */
    private void landed(final long t) {
        lastQueriedTime = t;
        started = true;
    }

    private void upsert(final Collection<FloorMapObject> objects) {
        if (objects == null) {
            return;
        }
        for (final FloorMapObject object : objects) {
            if (object != null && object.getId() != null) {
                known.put(object.getId(), copy(object));
            }
        }
    }

    /**
     * Copies the state that belongs to this class and nothing else.
     *
     * <p>Copying is necessary rather than tidy: {@link FloorMapLocationResolver#resolve} passes
     * coordinate-bearing entities through by identity, and the canvas then calls
     * {@code setImageFact(...)} on every drawn object once a frame. Holding the caller's instances
     * would mean the render loop mutating state owned by this class, sixty times a second, for as
     * long as the document stays open.</p>
     *
     * <p>{@code trail} and {@code imageFact} are deliberately <b>not</b> carried: they are
     * downstream render decorations attached to the animator's own copies, and pulling them in here
     * is exactly the aliasing this avoids.</p>
     */
    private static FloorMapObject copy(final FloorMapObject source) {
        final FloorMapObject copy = new FloorMapObject(
                source.getId(), source.getType(), source.getX(), source.getY());
        copy.setLocationRef(source.getLocationRef());
        return copy;
    }

    // -----------------------------------------------------------------------
    // Reading it back
    // -----------------------------------------------------------------------

    /**
     * Everything known, in first-seen order, as fresh copies.
     *
     * <p>Always the complete set, never a delta — which is what lets every downstream consumer
     * ({@code FloorMapAreaMembership}, {@code FloorMapGroupSnapshot}, the entity roster, the
     * canvas) keep assuming a complete list and stay unchanged.</p>
     */
    public List<FloorMapObject> known() {
        final List<FloorMapObject> list = new ArrayList<>(known.size());
        for (final FloorMapObject object : known.values()) {
            list.add(copy(object));
        }
        return list;
    }

    /** Forgets everything, including that anything was ever warned about. */
    public void clear() {
        known.clear();
        lastQueriedTime = 0;
        started = false;
        lastBaselineIssuedAt = 0;
        baselineRequested = true;
        truncationWarned = false;
    }
}
