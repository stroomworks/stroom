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

/**
 * Resolves the open {@link GraphStores} for a {@link GraphDbDoc}, opening and caching it on first use (Task
 * PoC.6 - {@link GraphSearchProvider} needs this to turn a resolved doc into the physical stores
 * {@link GraphTraversalEngine} reads).
 *
 * <p>Deliberately minimal next to {@code stroom.planb.impl.data.ShardManager} (which this otherwise mirrors):
 * no snapshotting, cross-node file transfer, or LRU eviction - a single node keeping every opened graph's LMDB
 * environment open indefinitely is P5 hardening, not a PoC concern.</p>
 */
public interface GraphStoreManager {

    /**
     * <b>Preconditions:</b> {@code doc} is not null.
     * <b>Postconditions:</b> returns an open {@link GraphStores} for {@code doc}, opening and caching it (keyed
     * by {@link GraphDbDoc#getUuid()}) if not already open. The returned instance must not be closed by the
     * caller - this manager owns its lifecycle.
     * <b>Null status:</b> neither the parameter nor the return value is nullable.
     *
     * @param doc the document to resolve stores for.
     * @return the (possibly newly opened) {@link GraphStores}.
     */
    GraphStores getOrOpen(GraphDbDoc doc);

    /**
     * Resolves a graph's stores for <b>reading</b>, reporting loudly if this node holds no data for it.
     *
     * <p>Same result as {@link #getOrOpen} - a query against a graph with no store still returns no rows rather
     * than failing, because a graph that has genuinely not been loaded yet is not an error. What differs is that
     * on a cluster, a node being asked to serve a graph it has never received a fragment for is reported.</p>
     *
     * <p>That case is the visible end of two configuration mistakes neither of which the code can prevent.
     * Adding a node to {@code graphdb.nodeList} does not backfill it, and queries route to the first node in the
     * list - so a node added at the front answers from nothing. Changing {@code graphdb.path} provisions empty
     * graphs rather than failing. Both produce answers that are wrong and silent; this makes them merely wrong
     * and loud, which is the difference between a bug someone finds and a bug nobody finds.</p>
     *
     * <p>Only reported when a node list is configured. On a single-node deployment an absent store means nothing
     * has been ingested yet, which is unremarkable and would otherwise log an error every time someone opened a
     * new graph.</p>
     *
     * <p><b>Preconditions:</b> {@code doc} is not null.
     * <b>Postconditions:</b> returns the graph's stores, as {@link #getOrOpen} would.
     * <b>Null status:</b> neither the parameter nor the return value is nullable.
     *
     * @param doc the graph being queried.
     * @return the (possibly empty) stores.
     */
    GraphStores getForQuery(GraphDbDoc doc);

    /**
     * Task P5.3: permanently removes the physical stores for the {@link GraphDbDoc} identified by {@code uuid} -
     * closing the cached {@link GraphStores} first if one is currently open, then deleting its on-disk directory.
     * The counterpart to {@link #getOrOpen} that a {@link GraphDbDoc} delete must call, or its physical data is
     * orphaned on disk forever.
     *
     * <p><b>Preconditions:</b> {@code uuid} is not null. <b>Postconditions:</b> the store for {@code uuid} is no
     * longer open or cached, and its directory no longer exists; a subsequent {@link #getOrOpen} for the same
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
     * that store shards are long-lived - and the hazard is concrete: {@link #getOrOpen} hands back a reference the
     * caller uses afterwards, so closing a store because it looked idle can close it underneath a traversal that
     * has already obtained it. Doing that safely needs the manager to hand out a lease rather than a raw
     * reference, which is a larger change than the cost it would save: an idle store holds file descriptors and
     * reserved address space, not heap.</p>
     *
     * <p><b>Postconditions:</b> every open store and on-disk directory whose document cannot be resolved has been
     * closed and deleted. A directory that cannot be removed is logged and left for the next run rather than
     * aborting the sweep.
     *
     * @return the number of graphs reclaimed.
     */
    long cleanupOrphanedStores();
}
