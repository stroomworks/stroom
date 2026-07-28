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

package stroom.query.planner.join;

import stroom.query.language.functions.Val;

import java.util.List;
import java.util.function.Consumer;

/**
 * The build (probe target) side of a streaming hash join, as seen by {@link JoinExecutor#streamingHashJoin}: a
 * keyed multimap of build-side rows that the probe side is streamed against. Abstracting it behind this interface
 * lets the pure join algorithm ({@code stroom-query-planner}) stay ignorant of <i>where</i> the build side lives -
 * an on-heap {@link HeapBuildSideLookup}, or an off-heap disk-backed store in {@code stroom-query-common} that this
 * module deliberately does not depend on (items C1/C2).
 *
 * <p>Lifecycle is strictly two-phase: a <b>build phase</b> of {@link #put} calls, then a <b>probe phase</b> of
 * {@link #get} calls. An implementation may commit/finalise its backing store lazily on the first {@link #get},
 * so callers must not interleave {@link #put} and {@link #get}. {@link #close} must always be called (ideally via
 * try-with-resources) to release any backing resource - for a disk-backed implementation this deletes its
 * temporary storage.</p>
 *
 * <p><b>Key semantics:</b> keys are the canonical string tuples produced by {@link JoinExecutor#keyOf} - a
 * non-null, non-empty {@link List} whose elements are each equi-key component's {@link Val#toString}. SQL-null
 * key components never reach this interface: {@link JoinExecutor#keyOf} returns {@code null} for them (SQL
 * {@code NULL != NULL}), and the caller drops such rows before {@link #put} and treats them as an automatic miss
 * before {@link #get} - so no implementation ever has to represent a null key.</p>
 */
public interface BuildSideLookup extends AutoCloseable {

    /**
     * Adds one build-side row under its equi-key during the build phase.
     *
     * <p><b>Preconditions:</b> {@code key} must be non-null and non-empty (a {@link JoinExecutor#keyOf} result for
     * a non-null-keyed row); {@code row} must be non-null. Must not be called after the first {@link #get}.<br>
     * <b>Postconditions:</b> a subsequent {@link #get} with an equal key includes this row. Duplicate identical
     * rows under the same key are all retained (never de-duplicated) - the join emits one output row per build
     * row, so collapsing duplicates would drop results.</p>
     */
    void put(List<String> key, Val[] row);

    /**
     * Streams every build-side row previously {@link #put} under a key equal to {@code key} to
     * {@code matchConsumer}, in insertion order, one row at a time. This is deliberately a <b>streaming</b>
     * primitive rather than a {@code List}-returning one: a highly skewed key can have an enormous number of rows,
     * and materialising them all into an in-heap list would reintroduce the very out-of-memory failure the
     * disk-backed build side exists to prevent (the
     * OOM-reduction plan). A disk-backed implementation reads and hands over one row at a time; the consumer
     * (e.g. {@link JoinExecutor#streamingProbe}) can then apply the output-row cap <i>during</i> a hot key's
     * fan-out instead of after the whole group is resident.
     *
     * <p><b>Preconditions:</b> {@code key} must be non-null and non-empty; {@code matchConsumer} must be
     * non-null.<br>
     * <b>Postconditions:</b> {@code matchConsumer} is invoked once per stored row for {@code key}, in insertion
     * order; returns {@code true} iff at least one row was found (so a caller can distinguish "matched" from "no
     * match" for {@code LEFT}-join null-padding without a separate probe).</p>
     */
    boolean forEachMatch(List<String> key, Consumer<Val[]> matchConsumer);

    /**
     * The number of rows {@link #put} so far - the runtime build-side size signal (see
     * item A6). Counts every {@link #put}, including
     * duplicate rows under the same key.
     *
     * <p><b>Postconditions:</b> {@code >= 0}; equals the number of successful {@link #put} calls.</p>
     */
    long rowCount();

    /**
     * Releases any resource backing this lookup - for a disk-backed implementation, closes and deletes its
     * temporary storage. Idempotent: a second call is a no-op. Never throws {@link Exception} (narrowed from
     * {@link AutoCloseable}) so it is safe to call from a {@code finally} block without masking a primary failure.
     */
    @Override
    void close();
}
