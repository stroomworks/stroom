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
}
