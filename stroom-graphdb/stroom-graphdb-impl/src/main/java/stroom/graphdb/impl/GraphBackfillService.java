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
import stroom.planb.impl.data.FileDescriptor;
import stroom.planb.impl.data.FileHashUtil;
import stroom.planb.impl.data.SequentialFileStore;
import stroom.security.api.SecurityContext;
import stroom.util.io.FileUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.zip.ZipUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Copies a graph this node holds to every other node that should hold it.
 *
 * <p>Replication ships new fragments only, so a node added to {@code graphdb.nodeList} holds nothing from before
 * it joined - and since queries route to the first node in the list, adding one at the front makes every answer
 * silently partial. Nothing in the code prevents that; this is what fixes it after the fact.</p>
 *
 * <p>It needs almost no new machinery, because a graph store and an ingest fragment are the same shape. A
 * fragment is simply a complete graph store holding one stream's mutations, so a <b>whole</b> store can be sent
 * down the identical path: copy, zip, hash, {@code storePart}. The receiving node stages and merges it exactly as
 * it would any fragment.</p>
 *
 * <p>Two properties of merge make this safe rather than merely convenient. It is <b>idempotent</b> - interning is
 * get-or-create and no timestamp is generated during it - so a node that already holds all of this data is
 * unaffected by receiving it again, which matters because the transport sends to every configured node including
 * those that need nothing. And it is a <b>union of versions</b>, so a node holding some of the graph ends up with
 * the union rather than one copy overwriting the other.</p>
 *
 * <p>The copy is taken with LMDB's own copy, which reads under a transaction, so the graph can continue to be
 * written while a backfill runs. What it cannot do is capture writes that land <em>after</em> it starts; those
 * arrive through ordinary replication, so the end state is still complete.</p>
 *
 * <p><b>This is deliberately a manual operation.</b> Detecting that a node has joined and needs backfilling means
 * tracking cluster membership over time, which nothing here does. Triggering it is an administrator's decision,
 * taken as part of the documented procedure for adding a node.</p>
 */
@Singleton
public class GraphBackfillService {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphBackfillService.class);

    /**
     * Backfill sends a whole graph rather than one stream's worth, so it carries no meaningful stream id. Zero
     * distinguishes it in the receiving node's logs from anything produced by ingest.
     */
    private static final long NO_META_ID = 0L;

    private final GraphPaths graphPaths;
    private final GraphStoreManager graphStoreManager;
    private final GraphFileTransferClient fileTransferClient;
    private final SecurityContext securityContext;

    @Inject
    public GraphBackfillService(final GraphPaths graphPaths,
                                final GraphStoreManager graphStoreManager,
                                final GraphFileTransferClient fileTransferClient,
                                final SecurityContext securityContext) {
        this.graphPaths = Objects.requireNonNull(graphPaths, "graphPaths must not be null");
        this.graphStoreManager = Objects.requireNonNull(graphStoreManager, "graphStoreManager must not be null");
        this.fileTransferClient =
                Objects.requireNonNull(fileTransferClient, "fileTransferClient must not be null");
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext must not be null");
    }

    /**
     * Sends this node's copy of {@code doc} to every node that holds graph data.
     *
     * <p><b>Preconditions:</b> {@code doc} is not null, and this node holds the graph - backfilling from a node
     * that holds nothing would send an empty store, which merges to nothing and achieves nothing.
     * <b>Postconditions:</b> every configured node has received and staged a complete copy; it is merged on their
     * next merge cycle. The working copy and zip are removed whether or not sending succeeded.
     * <b>Null status:</b> {@code doc} is not nullable.
     *
     * @param doc the graph to send.
     * @throws RuntimeException if the copy cannot be made or sent; the stream of work is not partially applied,
     *                          because a node either receives a whole store or none of it.
     */
    public void backfill(final GraphDbDoc doc) {
        Objects.requireNonNull(doc, "doc must not be null");

        securityContext.asProcessingUser(() -> {
            // Under the writer root rather than a system temp dir, so a backfill of a large graph fails on the
            // same volume the operator sized for graph data rather than filling /tmp.
            final Path working = graphPaths.getWriterDir()
                    .resolve("backfill_" + doc.getUuid() + "_" + UUID.randomUUID());
            final Path copyDir = working.resolve(doc.getUuid());
            final Path zipFile = working.resolveSibling(working.getFileName() + SequentialFileStore.ZIP_EXTENSION);

            try {
                Files.createDirectories(copyDir);

                LOGGER.info(() -> LogUtil.message("Backfilling graph '{}' - copying store", doc.getName()));
                graphStoreManager.getOrOpen(doc).copyTo(copyDir);

                ZipUtil.zip(zipFile, working);
                final String fileHash = FileHashUtil.hash(zipFile);
                final FileDescriptor fileDescriptor =
                        new FileDescriptor(System.currentTimeMillis(), NO_META_ID, fileHash);

                LOGGER.info(() -> LogUtil.message(
                        "Backfilling graph '{}' - sending {}", doc.getName(), FileUtil.getCanonicalPath(zipFile)));
                fileTransferClient.storePart(fileDescriptor, zipFile, false);
                LOGGER.info(() -> LogUtil.message("Backfilled graph '{}'", doc.getName()));

            } catch (final IOException e) {
                throw new UncheckedIOException("Unable to backfill graph '" + doc.getName() + "'", e);

            } finally {
                try {
                    FileUtil.deleteDir(working);
                    Files.deleteIfExists(zipFile);
                } catch (final Exception e) {
                    LOGGER.error(e::getMessage, e);
                }
            }
        });
    }
}
