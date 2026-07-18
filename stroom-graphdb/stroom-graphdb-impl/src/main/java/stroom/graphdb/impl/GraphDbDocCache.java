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
 * A by-name cache of {@link GraphDbDoc}s, mirroring {@code stroom.planb.impl.PlanBDocCache} — the pattern the
 * search provider added in PoC.6 will use to resolve the document for a query without re-reading it from the store
 * on every request.
 */
public interface GraphDbDocCache {

    /**
     * <b>Preconditions:</b> {@code name} is not null.
     * <b>Postconditions:</b> returns the current {@link GraphDbDoc} for {@code name}; throws if none exists or the
     * caller lacks {@code USE} permission on it.
     * <b>Null status:</b> {@code name} is not nullable; the return value is never null (an exception is thrown
     * instead of returning null for a missing document).
     *
     * @param name the document's name.
     * @return the cached (or freshly loaded) {@link GraphDbDoc}.
     */
    GraphDbDoc get(String name);

    /**
     * Evicts {@code name} from the cache, so the next {@link #get(String)} reloads it from the store.
     *
     * <b>Preconditions:</b> {@code name} is not null. <b>Null status:</b> {@code name} is not nullable.
     *
     * @param name the document's name.
     */
    void remove(String name);
}
