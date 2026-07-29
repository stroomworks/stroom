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
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.test.common.MockMetrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Covers compaction: returning a store's freed pages to the filesystem.
 *
 * <p>Condensing and retention both remove data, but LMDB keeps the freed pages on an internal list and reuses
 * them for later writes, so the file never shrinks. On a graph that shrank once and is not growing again those
 * pages are simply held - which is why "we enabled retention and the directory is the same size" is a real
 * report rather than a misreading.</p>
 *
 * <p>Compaction is a copy-and-swap, so what these tests mostly guard is the swap. <b>Every failure must leave a
 * usable store</b>, because the thing being risked is a whole graph. The size assertion is almost the least
 * interesting one here.</p>
 *
 * <p>The other thing they guard is <b>how often it runs</b>, which is not a performance concern but an
 * availability one: a compaction excludes every query on its graph for as long as it takes to rewrite the
 * store.</p>
 *
 * <p>One branch is deliberately uncovered: the size comparison that abandons a copy which came out no smaller.
 * It is unreachable through the public API now that the pending-work flag gates the copy - only a removal too
 * small to change the file could reach it - so it stands as a backstop rather than a tested path.</p>
 */
class TestGraphCompaction {

    private static final String DOC_UUID = "compaction-graph";
    private static final GraphDbDoc DOC = GraphDbDoc
            .builder()
            .uuid(DOC_UUID)
            .name("CompactionGraph")
            .build();

    private static final Instant DAY_1 = Instant.parse("2026-01-01T00:00:00.000Z");

    /** Enough versions that the pages freed by condensing them are a visible fraction of the file. */
    private static final int VERSIONS = 20_000;

    /**
     * The headline case: condensing frees pages, and compacting hands them back to the filesystem.
     *
     * <p>The intermediate assertion is the one that makes this test worth having - it pins that condensing alone
     * does <b>not</b> shrink the file, which is the fact that makes compaction necessary and the one an operator
     * is most likely to disbelieve. Removing 20,000 versions here in fact leaves the file a page <em>larger</em>,
     * because the deletions themselves are writes.</p>
     */
    @Test
    void condensingFreesPages_andCompactingReturnsThem(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            fixture.writeManyIdenticalVersions();
            final long sizeAfterWriting = fixture.storeSize();

            assertThat(fixture.use(GraphStores::condense)).isEqualTo(VERSIONS - 1);
            final long sizeAfterCondensing = fixture.storeSize();
            assertThat(sizeAfterCondensing).as("condensing alone does not shrink the file")
                    .isGreaterThanOrEqualTo(sizeAfterWriting);

            final long reclaimed = fixture.manager.compact(DOC);

            assertThat(reclaimed).as("bytes reclaimed").isPositive();
            // Not asserted as exactly (sizeAfterCondensing - reclaimed): compaction reopens the store to record
            // that it has nothing left to reclaim, and opening an LMDB environment writes to it. The reclaimed
            // figure is what the copy saved; what a later `du` shows is that, plus whatever the next open costs.
            assertThat(fixture.storeSize()).as("file size").isLessThan(sizeAfterCondensing / 2);
            assertThat(fixture.storeSize()).isLessThan(sizeAfterWriting / 2);
        } finally {
            fixture.close();
        }
    }

    /**
     * The data must survive the swap intact. A compaction that lost rows would satisfy the size assertion above
     * perfectly, which is exactly why that assertion cannot stand alone.
     */
    @Test
    void compacting_preservesEveryRow(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            // A run to collapse and a distinct value either side of it, so condensing genuinely removes
            // something - without which compaction is skipped and this test would assert nothing.
            fixture.writeVersions(DAY_1, 3, "a");
            fixture.writeVersions(DAY_1.plusSeconds(300), 3, "b");
            assertThat(fixture.use(GraphStores::condense)).isPositive();

            final List<String> before = fixture.statuses();
            final long countBefore = fixture.nodeVersionCount();

            assertThat(fixture.manager.compact(DOC)).isPositive();

            assertThat(fixture.statuses()).isEqualTo(before);
            assertThat(fixture.nodeVersionCount()).isEqualTo(countBefore);
        } finally {
            fixture.close();
        }
    }

    /**
     * A store nothing has been removed from must not be copied <b>at all</b>.
     *
     * <p>This is the gate that matters operationally. Compaction rewrites the whole store and holds a lock that
     * excludes every query on that graph, so deciding it was pointless <em>after</em> the copy - which the size
     * comparison does - has already spent the cost this exists to avoid.</p>
     *
     * <p>Asserted on the data file's identity rather than its size, because size does not hold still: opening an
     * environment writes to it, so the file is bigger after any use regardless of what compaction did. The
     * question is whether the file was <b>replaced</b>, and an inode answers that directly.</p>
     */
    @Test
    void storeWithNothingToReclaim_isNotCopiedAtAll(@TempDir final Path root) throws IOException {
        final Fixture fixture = new Fixture(root);
        try {
            fixture.writeVersions(DAY_1, 3, "a");
            final Object fileKeyBefore = fixture.dataFileKey();

            assertThat(fixture.manager.compact(DOC)).as("nothing removed, so nothing to reclaim").isZero();

            assertThat(fixture.dataFileKey()).as("data file identity").isEqualTo(fileKeyBefore);
        } finally {
            fixture.close();
        }
    }

    /**
     * A second compaction with nothing removed in between must do nothing, which means the first one cleared the
     * flag. Without that, every scheduled run would rewrite every graph that had ever had anything removed.
     */
    @Test
    void compactingTwice_reclaimsNothingTheSecondTime(@TempDir final Path root) throws IOException {
        final Fixture fixture = new Fixture(root);
        try {
            fixture.writeManyIdenticalVersions();
            fixture.use(GraphStores::condense);
            assertThat(fixture.manager.compact(DOC)).isPositive();
            final Object fileKeyAfterFirst = fixture.dataFileKey();

            assertThat(fixture.manager.compact(DOC)).as("second compaction").isZero();

            assertThat(fixture.dataFileKey()).as("data file identity").isEqualTo(fileKeyAfterFirst);
        } finally {
            fixture.close();
        }
    }

    /**
     * The flag lives in the store rather than in memory, and these are the transitions the schedule depends on.
     *
     * <p>It has to be durable because the two operations run days apart: removal every few minutes, compaction
     * nightly. A restart in between is ordinary, and an in-memory flag would forget that a graph which shed a
     * lot of data and then went quiet is owed a compaction - which is precisely the graph most worth
     * compacting.</p>
     */
    @Test
    void theCompactionFlag_isSetByRemovalAndClearedByCompaction(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            assertThat(fixture.use(GraphStores::isCompactionPending)).as("a fresh store").isFalse();

            fixture.writeManyIdenticalVersions();
            assertThat(fixture.use(GraphStores::isCompactionPending)).as("writing alone").isFalse();

            fixture.use(GraphStores::condense);
            assertThat(fixture.use(GraphStores::isCompactionPending)).as("after condensing").isTrue();

            fixture.manager.compact(DOC);
            assertThat(fixture.use(GraphStores::isCompactionPending)).as("after compacting").isFalse();
        } finally {
            fixture.close();
        }
    }

    /**
     * Compacting a graph this node holds nothing for must not create it. Opening a store to compact it would
     * conjure the very directory whose absence {@code useForQuery} reports as a misconfiguration.
     */
    @Test
    void compactingAGraphWithNoStore_createsNothing(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            assertThat(fixture.manager.compact(DOC)).isZero();
            assertThat(Files.exists(fixture.storeDir())).isFalse();
        } finally {
            fixture.close();
        }
    }

    /**
     * No working directory may survive a compaction, successful or not. They are the size of the graph, so one
     * left behind per maintenance cycle fills the volume the store itself needs.
     */
    @Test
    void compacting_leavesNoWorkingDirectories(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        try {
            fixture.writeManyIdenticalVersions();
            fixture.use(GraphStores::condense);

            fixture.manager.compact(DOC);

            assertThat(fixture.shardDirEntries()).containsExactly(DOC_UUID);
        } finally {
            fixture.close();
        }
    }

    /**
     * The load-bearing concurrency property, and the whole reason the manager lends stores rather than handing
     * them out: compaction must not replace the file under a caller that is reading it.
     *
     * <p>A reader is parked inside {@code use} and compaction started from another thread. It must still be
     * waiting when the reader finishes - if it had swapped the file out first, the reader would have been
     * reading a deleted environment.</p>
     */
    @Test
    void compaction_waitsForAnInFlightReader(@TempDir final Path root) throws Exception {
        final Fixture fixture = new Fixture(root);
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            fixture.writeManyIdenticalVersions();
            fixture.use(GraphStores::condense);

            final CountDownLatch readerStarted = new CountDownLatch(1);
            final CountDownLatch releaseReader = new CountDownLatch(1);
            final AtomicBoolean compactionFinished = new AtomicBoolean();

            final Future<Boolean> reader = executor.submit(() -> fixture.manager.use(DOC, stores -> {
                readerStarted.countDown();
                await(releaseReader);
                // Read the store while holding it, so a swap underneath would be a use-after-free rather than
                // merely a stale reference.
                stores.read(stores.getNodes()::count);
                return compactionFinished.get();
            }));

            assertThat(readerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            final Future<Long> compaction = executor.submit(() -> {
                final long reclaimed = fixture.manager.compact(DOC);
                compactionFinished.set(true);
                return reclaimed;
            });

            // Give compaction every chance to run ahead of the reader; it must not.
            Thread.sleep(250);
            assertThat(compactionFinished).as("compaction ran while a reader held the store").isFalse();

            releaseReader.countDown();
            assertThat(reader.get(10, TimeUnit.SECONDS))
                    .as("compaction had not finished when the reader did").isFalse();
            assertThat(compaction.get(10, TimeUnit.SECONDS)).isPositive();

            // And the store is still usable afterwards, from a caller that obtained it after the swap.
            assertThat(fixture.statuses()).containsExactly("same");
        } finally {
            executor.shutdownNow();
            fixture.close();
        }
    }

    // ------------------------------------------------------------------------------------------------------

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A manager over a real temp-directory store, so compaction is exercised against real files. */
    private static final class Fixture {

        private final GraphPaths graphPaths;
        private final GraphStoreManagerImpl manager;

        private Fixture(final Path root) {
            graphPaths = new GraphPaths(root.resolve("graphdb"));
            manager = new GraphStoreManagerImpl(
                    graphPaths, GraphDbConfig::new, () -> mock(GraphDbDocStore.class), new MockMetrics());
        }

        private <R> R use(final java.util.function.Function<GraphStores, R> function) {
            return manager.use(DOC, function);
        }

        /** One node, re-asserted unchanged many times - the shape a scheduled reload produces. */
        private void writeManyIdenticalVersions() {
            writeVersions(DAY_1, VERSIONS, "same");
        }

        private void writeVersions(final Instant from, final int count, final String status) {
            use(stores -> {
                for (int i = 0; i < count; i++) {
                    final Instant validFrom = from.plusSeconds(i);
                    final Map<String, Val> properties = new LinkedHashMap<>();
                    properties.put("status", ValString.create(status));
                    stores.write(writer -> {
                        stores.getNodes().insert(writer, 1L, validFrom, List.of(), properties);
                        return null;
                    });
                }
                return null;
            });
        }

        /** The surviving versions' statuses, oldest first - enough to tell a lossy compaction from a clean one. */
        private List<String> statuses() {
            return use(stores -> stores.read(txn -> {
                final List<String> found = new ArrayList<>();
                stores.getNodes().forEachVersion(txn, (uid, validFrom, version) ->
                        found.add(Optional.ofNullable(version.properties().get("status"))
                                .map(Val::toString)
                                .orElse(null)));
                return found;
            }));
        }

        private long nodeVersionCount() {
            final Long count = use(stores -> stores.read(stores.getNodes()::count));
            return count;
        }

        private Path storeDir() {
            return graphPaths.getShardDir().resolve(DOC_UUID);
        }

        /** The live data file's identity, which changes if and only if the file was replaced. */
        private Object dataFileKey() throws IOException {
            return Files.readAttributes(storeDir().resolve("data.mdb"), BasicFileAttributes.class).fileKey();
        }

        private long storeSize() {
            return stroom.util.io.FileUtil.getByteSize(storeDir());
        }

        private List<String> shardDirEntries() {
            try (var children = Files.list(graphPaths.getShardDir())) {
                return children.map(path -> path.getFileName().toString()).sorted().toList();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void close() {
            manager.delete(DOC_UUID);
        }
    }
}
