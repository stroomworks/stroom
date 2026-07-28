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

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.metrics.Metrics;

import com.codahale.metrics.Counter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Default {@link GraphStoreManager}. Resolves each doc's on-disk directory to {@code <app path>/graphdb/<uuid>}
 * via {@code PathCreator} - the same mechanism {@code stroom.planb.impl.dao.StatePaths} uses for its own root,
 * without yet introducing a dedicated config surface (P5 hardening; see {@link GraphDbDocCacheImpl}'s Javadoc for
 * the same deferral on the cache side).
 */
@Singleton
public class GraphStoreManagerImpl implements GraphStoreManager {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphStoreManagerImpl.class);

    private final GraphPaths graphPaths;
    private final Provider<GraphDbConfig> configProvider;
    private final Provider<GraphDbDocStore> graphDbDocStoreProvider;
    private final Counter missingStoreQueries;
    private final ConcurrentMap<String, GraphStores> openStores = new ConcurrentHashMap<>();

    @Inject
    public GraphStoreManagerImpl(final GraphPaths graphPaths,
                                 final Provider<GraphDbConfig> configProvider,
                                 final Provider<GraphDbDocStore> graphDbDocStoreProvider,
                                 final Metrics metrics) {
        this.graphPaths = Objects.requireNonNull(graphPaths, "graphPaths");
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
        this.graphDbDocStoreProvider =
                Objects.requireNonNull(graphDbDocStoreProvider, "graphDbDocStoreProvider");
        this.missingStoreQueries = Objects.requireNonNull(metrics, "metrics")
                .registrationBuilder(getClass())
                .addNamePart("missingStoreQueries")
                .counter()
                .createAndRegister();
    }

    @Override
    public GraphStores getForQuery(final GraphDbDoc doc) {
        Objects.requireNonNull(doc, "doc");

        // Checked before opening, because opening creates the directory - after which the evidence is gone.
        final boolean absent = !Files.isDirectory(directoryFor(doc.getUuid()));
        if (absent && !configProvider.get().getNodeList().isEmpty()) {
            missingStoreQueries.inc();
            LOGGER.error(() -> "This node holds no data for graph '" + doc.getName() + "' (" + doc.getUuid()
                               + ") but is being queried for it, so the answer will be empty rather than wrong-"
                               + "looking. Either this node was added to graphdb.nodeList without copying "
                               + "existing graph data to it - joining does not backfill - or graphdb.path has "
                               + "changed without the data being moved. If the graph has genuinely never been "
                               + "loaded, ignore this.");
        }
        return getOrOpen(doc);
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

    @Override
    public long cleanupOrphanedStores() {
        long reclaimed = 0;

        // On-disk directories first: this is the case an entity event cannot cover, because a node that was down
        // when the document was deleted never saw the event and will never be asked for that graph again.
        final Path shardDir = graphPaths.getShardDir();
        if (Files.isDirectory(shardDir)) {
            final List<Path> directories;
            try (Stream<Path> stream = Files.list(shardDir)) {
                directories = stream.filter(Files::isDirectory).toList();
            } catch (final IOException e) {
                LOGGER.error(() -> "Unable to list " + shardDir, e);
                return reclaimed;
            }

            for (final Path directory : directories) {
                final String uuid = directory.getFileName().toString();
                if (documentExists(uuid)) {
                    continue;
                }
                try {
                    // Goes through delete() rather than deleting the directory directly, so an open store for the
                    // same UUID is closed first and the map entry cannot be left pointing at deleted files.
                    delete(uuid);
                    reclaimed++;
                    LOGGER.info(() -> "Reclaimed graph store for deleted document " + uuid);
                } catch (final RuntimeException e) {
                    // Left for the next run rather than aborting the sweep, so one undeletable directory does not
                    // stop every other graph being reclaimed.
                    LOGGER.error(() -> "Unable to reclaim graph store " + uuid, e);
                }
            }
        }
        return reclaimed;
    }

    /**
     * Whether a {@link GraphDbDoc} still exists for {@code uuid}. Treats an unreadable document as existing, so a
     * transient failure reading the document store can never cause data to be deleted.
     */
    private boolean documentExists(final String uuid) {
        try {
            return graphDbDocStoreProvider.get().readDocument(
                    DocRef.builder().type(GraphDbDoc.TYPE).uuid(uuid).build()) != null;
        } catch (final DocumentNotFoundException e) {
            LOGGER.debug(e::getMessage, e);
            return false;
        } catch (final RuntimeException e) {
            LOGGER.error(() -> "Unable to read graph document " + uuid + "; assuming it still exists", e);
            return true;
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
