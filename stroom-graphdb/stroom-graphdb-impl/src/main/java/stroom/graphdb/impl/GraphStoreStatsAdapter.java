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

import stroom.docstore.api.DocumentNotFoundException;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.planner.port.GraphStoreStats;
import stroom.query.planner.port.RowCountSignal;

import jakarta.inject.Inject;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * The real {@link GraphStoreStats} adapter (Task P5.1) - resolves {@code graphName} the exact way
 * {@link GraphSearchProvider} already does (via {@link GraphDbDocCache#get}), reads its stores via
 * {@link GraphStoreManager#useForQuery}, and answers with {@link GraphNodeDb#count}.
 */
public class GraphStoreStatsAdapter implements GraphStoreStats {

    private final GraphDbDocCache graphDbDocCache;
    private final GraphStoreManager graphStoreManager;

    @Inject
    public GraphStoreStatsAdapter(final GraphDbDocCache graphDbDocCache, final GraphStoreManager graphStoreManager) {
        this.graphDbDocCache = Objects.requireNonNull(graphDbDocCache, "graphDbDocCache");
        this.graphStoreManager = Objects.requireNonNull(graphStoreManager, "graphStoreManager");
    }

    /**
     * <b>Postconditions:</b> empty if {@code graphName} names no known {@link GraphDbDoc} (the cache's own
     * "no doc found for name" failure, caught here and translated to this port's "unknown store" contract - see
     * {@link GraphStoreStats#estimate}). A {@link stroom.util.shared.PermissionException} from the cache's own
     * permission check is deliberately not caught - an access-control signal, not an "unknown store" one.
     *
     * <p>Code-review fix: previously caught the broad {@link NullPointerException} to mean "unknown graph",
     * which would also have silently swallowed an unrelated genuine NPE bug anywhere in this call chain. Now
     * catches the two specific, dedicated exception types {@link GraphDbDocCacheImpl#create} actually throws for
     * this case.</p>
     */
    @Override
    public Optional<RowCountSignal> estimate(final String graphName) {
        Objects.requireNonNull(graphName, "graphName");

        final GraphDbDoc doc;
        try {
            doc = graphDbDocCache.get(graphName);
        } catch (final NoSuchElementException | DocumentNotFoundException e) {
            return Optional.empty();
        }

        final long count = graphStoreManager.useForQuery(doc, stores -> stores.read(stores.getNodes()::count));
        return Optional.of(new RowCountSignal(count));
    }
}
