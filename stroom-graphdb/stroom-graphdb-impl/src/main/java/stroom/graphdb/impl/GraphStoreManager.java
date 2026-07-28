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

package stroom.graphdb.impl;

import stroom.graphdb.shared.GraphDbDoc;

import java.util.function.Function;

/**
 * Owns the open {@link GraphStores} for each {@link GraphDbDoc}, opening one on first use and lending it to
 * callers for the duration of a call.
 *
 * <p><b>It lends rather than hands over, and that is the whole design.</b> An earlier version returned the
 * store, so a caller held a reference the manager knew nothing about and could not tell when it stopped using
 * it. Anything that needs to close or replace a store - compaction, eviction, deleting a graph - then had no
 * safe moment to do it, because a traversal might be part-way through the environment it was about to close.
 * Every use now happens inside {@link #use}, so the manager knows exactly when a store is in use and can wait
 * for it to be free. This is what makes {@link #compact} possible at all.</p>
 *
 * <p>Still deliberately simpler than {@code stroom.planb.impl.data.ShardManager}, which this otherwise mirrors:
 * no snapshotting and no cross-node file transfer. It also does not evict idle stores, and that is a decision
 * rather than an omission - every graph store is authoritative, and an idle one holds file descriptors and
 * reserved address space rather than heap, so closing it buys nothing worth the reopen.</p>
 */
public interface GraphStoreManager {

    /**
     * Runs {@code function} against the graph's stores, holding them open for exactly that long.
     *
     * <p>The store is opened on first use and kept open afterwards. It may not be closed or replaced while the
     * function is running, so {@link #compact} and {@link #delete} wait for it to return - which means a
     * function that runs for a long time delays them for as long as it runs. Do the work and return; do not
     * park, and <b>do not let the {@link GraphStores} escape</b>, because outside this call nothing keeps it
     * open.</p>
     *
     * <p><b>Preconditions:</b> neither argument is null.
     * <b>Postconditions:</b> returns whatever {@code function} returned. The store remains open and cached.
     * <b>Null status:</b> neither argument is nullable; the result is whatever {@code function} returns.
     *
     * @param doc      the graph to resolve stores for.
     * @param function the work to do against them. Return {@code null} for work with no result, as
     *                 {@code GraphStores.write} expects.
     * @param <R>      the result type.
     * @return the function's result.
     */
    <R> R use(GraphDbDoc doc, Function<GraphStores, R> function);

    /**
     * As {@link #use}, but for <b>reading</b>, and reporting loudly if this node holds no data for the graph.
     *
     * <p>Behaves identically otherwise - a query against a graph with no store still returns no rows rather than
     * failing, because a graph that has genuinely not been loaded yet is not an error. What differs is that on a
     * cluster, a node being asked to serve a graph it has never received a fragment for is reported.</p>
     *
     * <p>That case is the visible end of two configuration mistakes neither of which the code can prevent.
     * Adding a node to {@code graphdb.nodeList} does not backfill it automatically, and queries route to the
     * first node in the list - so a node added at the front answers from nothing. Changing {@code graphdb.path}
     * provisions empty graphs rather than failing. Both produce answers that are wrong and silent; this makes
     * them merely wrong and loud, which is the difference between a bug someone finds and a bug nobody
     * finds.</p>
     *
     * <p>Only reported when a node list is configured. On a single-node deployment an absent store means nothing
     * has been ingested yet, which is unremarkable and would otherwise log an error every time someone opened a
     * new graph.</p>
     *
     * <p><b>Preconditions:</b> neither argument is null.
     * <b>Postconditions:</b> as {@link #use}, plus the report described above where it applies.
     * <b>Null status:</b> neither argument is nullable.
     *
     * @param doc      the graph being queried.
     * @param function the work to do against its stores.
     * @param <R>      the result type.
     * @return the function's result.
     */
    <R> R useForQuery(GraphDbDoc doc, Function<GraphStores, R> function);

    /**
     * Rewrites a graph's store without its free pages, returning the bytes that went back to the filesystem.
     *
     * <p>Retention and condensing both remove data, but LMDB keeps the freed pages on an internal list and
     * reuses them for later writes rather than shrinking the file. On a graph that shrank once and is not
     * growing again - a retention window shortened, a bulk reload condensed - those pages are simply held. This
     * is what returns them.</p>
     *
     * <p>The mechanism is a copy: LMDB writes out a fresh environment containing only live pages, and that file
     * then replaces the original. So it needs <b>room for a second copy of the graph</b> while it runs, and it
     * takes time proportional to the live data rather than to the space being reclaimed.</p>
     *
     * <p><b>It excludes every other use of the graph while it runs</b>, including queries, because the file
     * underneath them is being replaced. That is the cost of doing it in place, and it is why this is
     * maintenance rather than something to call on a schedule of its own.</p>
     *
     * <p><b>Preconditions:</b> {@code doc} is not null.
     * <b>Postconditions:</b> either the store has been replaced by a compacted copy holding identical data, or
     * nothing has changed and the original store is still usable - there is no state in between. Any working
     * copy is removed either way.
     * <b>Null status:</b> {@code doc} is not nullable.
     *
     * @param doc the graph to compact.
     * @return bytes reclaimed. Zero if there was nothing to reclaim; never negative, because a copy that came
     *         out larger is discarded rather than swapped in.
     */
    long compact(GraphDbDoc doc);

    /**
     * Task P5.3: permanently removes the physical stores for the {@link GraphDbDoc} identified by {@code uuid} -
     * closing the cached {@link GraphStores} first if one is currently open, then deleting its on-disk directory.
     * The counterpart to {@link #use} that a {@link GraphDbDoc} delete must call, or its physical data is
     * orphaned on disk forever.
     *
     * <p>Waits for any in-flight {@link #use} of the same graph to return before closing it.</p>
     *
     * <p><b>Preconditions:</b> {@code uuid} is not null. <b>Postconditions:</b> the store for {@code uuid} is no
     * longer open or cached, and its directory no longer exists; a subsequent {@link #use} for the same
     * UUID provisions a fresh, empty store. A no-op (not an error) if {@code uuid} was never opened by this
     * manager and has no on-disk directory.</p>
     *
     * @param uuid the {@link GraphDbDoc#getUuid()} of the doc whose stores should be removed.
     */
    void delete(String uuid);

    /**
     * Reclaims graph data whose document no longer exists.
     *
     * <p>A document delete normally reaches {@link #delete(String)} through an entity event, but only on a node
     * that is running at the time. A node that was down when the delete happened keeps the directory forever:
     * nothing will ever ask for that graph again, so nothing ever notices. This is the sweep that catches it.</p>
     *
     * <p><b>Deliberately not idle eviction.</b> An open store is left open however long it goes unused. Plan B
     * takes the same position for the same reason - its {@code StoreShard.isIdle()} returns false with the note
     * that store shards are long-lived. Closing one is now <em>safe</em>, since {@link #use} makes in-flight work
     * visible, but it is still not worth doing: an idle store holds file descriptors and reserved address space
     * rather than heap, and reopening it costs a real query.</p>
     *
     * <p><b>Postconditions:</b> every open store and on-disk directory whose document cannot be resolved has been
     * closed and deleted. A directory that cannot be removed is logged and left for the next run rather than
     * aborting the sweep.
     *
     * @return the number of graphs reclaimed.
     */
    long cleanupOrphanedStores();
}
