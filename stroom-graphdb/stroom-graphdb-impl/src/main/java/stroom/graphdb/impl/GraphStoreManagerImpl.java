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

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link GraphStoreManager}. Resolves each doc's on-disk directory to {@code <app path>/graphdb/<uuid>}
 * via {@code PathCreator} - the same mechanism {@code stroom.planb.impl.dao.StatePaths} uses for its own root,
 * without yet introducing a dedicated config surface (P5 hardening; see {@link GraphDbDocCacheImpl}'s Javadoc for
 * the same deferral on the cache side).
 */
@Singleton
public class GraphStoreManagerImpl implements GraphStoreManager {

    private final GraphPaths graphPaths;
    private final Provider<GraphDbConfig> configProvider;
    private final ConcurrentMap<String, GraphStores> openStores = new ConcurrentHashMap<>();

    @Inject
    public GraphStoreManagerImpl(final GraphPaths graphPaths,
                                 final Provider<GraphDbConfig> configProvider) {
        this.graphPaths = Objects.requireNonNull(graphPaths, "graphPaths");
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
    }

    @Override
    public GraphStores getOrOpen(final GraphDbDoc doc) {
        Objects.requireNonNull(doc, "doc");
        return openStores.computeIfAbsent(doc.getUuid(), uuid ->
                GraphStores.open(directoryFor(uuid), doc, false, configProvider.get().getMaxStoreSize()));
    }

    /**
     * Code-review fix: previously {@code remove(uuid)} then the physical {@link GraphStores#delete} ran as two
     * separate steps with no lock between them - a concurrent {@link #getOrOpen} for the same UUID could
     * repopulate {@code openStores} via {@code computeIfAbsent} in the gap, and the pending physical delete would
     * then remove that directory's files out from under the freshly-opened, live instance. Using
     * {@link ConcurrentMap#compute} instead makes "close the old instance, then physically delete" atomic with
     * respect to this key: {@code computeIfAbsent}/{@code compute} on the same key in a {@link ConcurrentHashMap}
     * never run concurrently with each other, so a racing {@code getOrOpen} either completes fully before this
     * runs (and its instance is closed and deleted here) or blocks until this finishes (and then correctly opens
     * a fresh store, since the map entry is null again by the time it proceeds).
     *
     * <p>Second code-review fix: {@link ConcurrentMap#compute} leaves the mapping <em>unchanged</em> if the
     * remapping function throws, so an earlier version that let {@link GraphStores#delete}'s
     * {@code UncheckedIOException} (a file that cannot be removed) propagate straight out of the lambda would
     * leave the now-<em>closed</em> {@link GraphStores} cached forever - every later {@code getOrOpen} would hand
     * back a closed store, the exact permanent corruption the first fix set out to remove. So the lambda always
     * returns {@code null} (evicts), capturing any physical-delete failure to rethrow once the entry is safely
     * gone: a failed delete then just leaves files on disk that the next {@code getOrOpen} reopens cleanly.</p>
     */
    @Override
    public void delete(final String uuid) {
        Objects.requireNonNull(uuid, "uuid");
        final RuntimeException[] deleteFailure = new RuntimeException[1];
        openStores.compute(uuid, (key, stores) -> {
            if (stores != null) {
                stores.close();
            }
            try {
                deleteStoreDirectory(directoryFor(uuid));
            } catch (final RuntimeException e) {
                // Remember it but still evict below - never leave a closed store cached.
                deleteFailure[0] = e;
            }
            return null;
        });
        if (deleteFailure[0] != null) {
            throw deleteFailure[0];
        }
    }

    /**
     * Physically removes a store's on-disk directory. Package-private (not inlined) purely as a test seam so a
     * test can simulate a filesystem delete that fails (an undeletable/locked file) without needing to actually
     * lock a file; production always delegates to {@link GraphStores#delete}.
     */
    void deleteStoreDirectory(final Path directory) {
        GraphStores.delete(directory);
    }

    private Path directoryFor(final String uuid) {
        return graphPaths.getShardDir().resolve(uuid);
    }
}
