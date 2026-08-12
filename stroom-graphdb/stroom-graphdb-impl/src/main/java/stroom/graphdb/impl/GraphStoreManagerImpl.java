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
import stroom.util.io.FileUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.metrics.Metrics;

import com.codahale.metrics.Counter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
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

    /** Where a compacted copy is built. A sibling of the live store, so the swap is a rename on one filesystem. */
    private static final String COMPACTING_SUFFIX = ".compacting";
    /** Where the original is parked during the swap, so a failed second rename can be undone. */
    private static final String SUPERSEDED_SUFFIX = ".superseded";

    private final GraphPaths graphPaths;
    private final Provider<GraphDbConfig> configProvider;
    private final Provider<GraphDbDocStore> graphDbDocStoreProvider;
    private final Counter missingStoreQueries;
    private final ConcurrentMap<String, GraphStores> openStores = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReadWriteLock> locks = new ConcurrentHashMap<>();

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
    public <R> R useForQuery(final GraphDbDoc doc, final Function<GraphStores, R> function) {
        Objects.requireNonNull(doc, "doc");

        // Checked before opening, because opening creates the directory - after which the evidence is gone.
        final boolean absent = !Files.isDirectory(directoryFor(doc.getUuid()));
        if (absent && !configProvider.get().getNodeList().isEmpty()) {
            missingStoreQueries.inc();
            LOGGER.error(() -> "This node holds no data for graph '" + doc.getName() + "' (" + doc.getUuid()
                               + ") but is being queried for it, so the answer will be empty rather than wrong-"
                               + "looking. Either this node was added to graphdb.nodeList without being "
                               + "backfilled - joining does not backfill automatically - or graphdb.path has "
                               + "changed without the data being moved. If the graph has genuinely never been "
                               + "loaded, ignore this.");
        }
        return use(doc, function);
    }

    /**
     * Holds the graph's read lock for the duration of {@code function}, so nothing can close or replace the store
     * under it. Several callers may hold it at once; only {@link #compact} and {@link #delete} exclude them.
     */
    @Override
    public <R> R use(final GraphDbDoc doc, final Function<GraphStores, R> function) {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(function, "function");

        final ReadWriteLock lock = lockFor(doc.getUuid());
        lock.readLock().lock();
        try {
            return function.apply(getOrOpenUnguarded(doc));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Opens and caches the store without taking the lock.
     *
     * <p><b>Named for what it is missing.</b> Nothing outside this class may hold a store, and inside it only
     * {@link #use} and {@link #compact} may call this - both hold a lock already. Anything else calling it gets
     * a reference that {@link #compact} can invalidate, which is precisely the hazard the lending design
     * removes. It is not private only so tests can assert on the cache directly.</p>
     */
    GraphStores getOrOpenUnguarded(final GraphDbDoc doc) {
        return openStores.computeIfAbsent(doc.getUuid(), uuid ->
                GraphStores.open(directoryFor(uuid), doc, false, configProvider.get().getMaxStoreSize()));
    }

    /**
     * Compacts by copy-and-swap, because LMDB has no in-place compaction: the environment is copied minus its
     * free pages, and the copy replaces the original.
     *
     * <p>The ordering is chosen so that <b>every failure leaves a usable store</b>, which matters more here than
     * anywhere else in this class - the failure being guarded against is one that loses a whole graph.</p>
     *
     * <ol>
     *   <li>Copy first, with the original still open and serving. A failed copy changes nothing.</li>
     *   <li>Compare sizes and abandon if the copy is not smaller. LMDB writes at least a root page and a meta
     *       page, so an already-compact store copies to roughly its own size; swapping then costs a reopen and
     *       buys nothing.</li>
     *   <li>Close, then move the original aside rather than deleting it. If the copy cannot be moved into place
     *       the original is moved back, so the window in which no store exists is two renames on one
     *       filesystem.</li>
     *   <li>Delete the original last. A failure there leaves a stale directory, which
     *       {@link #cleanupOrphanedStores} reclaims because no document resolves its name.</li>
     * </ol>
     *
     * <p>The store is dropped from the cache rather than reopened here, so the next {@link #use} opens the new
     * file. Reopening eagerly would mean holding an environment open for a graph nobody has asked for.</p>
     */
    @Override
    public long compact(final GraphDbDoc doc) {
        Objects.requireNonNull(doc, "doc");

        final String uuid = doc.getUuid();
        final ReadWriteLock lock = lockFor(uuid);
        lock.writeLock().lock();
        try {
            final Path live = directoryFor(uuid);
            if (!Files.isDirectory(live)) {
                // Nothing on disk yet, so nothing to reclaim. Opening one purely to compact it would create the
                // very directory this node may be right not to have.
                return 0;
            }

            // Checked before the copy, not after. The size comparison below can also abandon a pointless
            // compaction, but only once the whole store has already been rewritten - which is the expensive part
            // and the part that blocks every query on this graph.
            if (!getOrOpenUnguarded(doc).isCompactionPending()) {
                LOGGER.debug(() -> LogUtil.message(
                        "Nothing has been removed from graph '{}' since it was last compacted", doc.getName()));
                return 0;
            }

            final Path working = live.resolveSibling(uuid + COMPACTING_SUFFIX);
            final Path previous = live.resolveSibling(uuid + SUPERSEDED_SUFFIX);
            // Anything left by an interrupted earlier run, which would otherwise make the copy below fail.
            deleteQuietly(working);
            deleteQuietly(previous);

            final long sizeBefore = FileUtil.getByteSize(live);
            try {
                Files.createDirectories(working);
                // Directly, not through use() - the write lock is already held, and the copy is taken under an
                // LMDB read transaction so the environment being open is not a problem.
                getOrOpenUnguarded(doc).copyTo(working);

                final long sizeAfter = FileUtil.getByteSize(working);
                if (sizeAfter >= sizeBefore) {
                    LOGGER.debug(() -> LogUtil.message(
                            "Graph '{}' is already compact ({} -> {} bytes); leaving it alone",
                            doc.getName(), sizeBefore, sizeAfter));
                    return 0;
                }

                swapIn(uuid, live, working, previous);
                // On the reopened store, so it lands in the file that survived rather than the one just deleted.
                getOrOpenUnguarded(doc).clearCompactionPending();

                LOGGER.info(() -> LogUtil.message("Compacted graph '{}', reclaiming {} bytes",
                        doc.getName(), sizeBefore - sizeAfter));
                return sizeBefore - sizeAfter;

            } catch (final IOException e) {
                throw new UncheckedIOException("Unable to compact graph '" + doc.getName() + "'", e);

            } finally {
                deleteQuietly(working);
                deleteQuietly(previous);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Closes the live store and puts {@code working} in its place. Rolls back if the second move fails, which is
     * the only step that can leave a graph with no directory at all.
     */
    private void swapIn(final String uuid, final Path live, final Path working, final Path previous)
            throws IOException {
        final GraphStores open = openStores.remove(uuid);
        if (open != null) {
            open.close();
        }

        Files.move(live, previous);
        try {
            Files.move(working, live);
        } catch (final IOException e) {
            Files.move(previous, live);
            throw e;
        }
    }

    /**
     * Code-review fix: previously {@code remove(uuid)} then the physical {@link GraphStores#delete} ran as two
     * separate steps with no lock between them - a concurrent {@link #getOrOpenUnguarded} for the same UUID could
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
        final ReadWriteLock lock = lockFor(uuid);
        // Excludes in-flight use() of this graph. The ConcurrentMap reasoning below still stands and still does
        // the work for the map itself; the lock is what stops a traversal reading an environment being closed
        // here, which no amount of map atomicity can prevent.
        lock.writeLock().lock();
        try {
            deleteUnderLock(uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void deleteUnderLock(final String uuid) {
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

    /**
     * The lock guarding one graph, created on demand and never removed.
     *
     * <p>Keyed on UUID rather than held on the store, because it must exist while no store does - {@link #delete}
     * and {@link #compact} both need to exclude other callers across the moment the store is closed. Never
     * removed because the map holds one small object per graph a node has touched, and reclaiming them would
     * need exactly the coordination the lock is there to provide.</p>
     */
    private ReadWriteLock lockFor(final String uuid) {
        return locks.computeIfAbsent(uuid, key -> new ReentrantReadWriteLock());
    }

    /** Removes a working directory if it is there, reporting failure without derailing the caller. */
    private void deleteQuietly(final Path directory) {
        try {
            if (Files.isDirectory(directory)) {
                GraphStores.delete(directory);
            }
        } catch (final RuntimeException e) {
            LOGGER.error(() -> "Unable to remove " + FileUtil.getCanonicalPath(directory), e);
        }
    }

    private Path directoryFor(final String uuid) {
        return graphPaths.getShardDir().resolve(uuid);
    }
}
