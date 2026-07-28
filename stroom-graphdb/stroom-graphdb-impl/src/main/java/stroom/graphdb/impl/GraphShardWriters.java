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
import stroom.meta.shared.Meta;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.data.FileDescriptor;
import stroom.planb.impl.data.FileHashUtil;
import stroom.planb.impl.data.SequentialFileStore;
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
 * Creates the per-stream fragment each ingest task writes into, instead of letting ingest write straight into a
 * live store.
 *
 * <p>This indirection is the whole point of the cluster work. A fragment is a complete, self-contained graph store
 * holding only one stream's mutations; because it is separate from the authoritative store it can be shipped to
 * whichever nodes hold graph data and merged there, so no node's answer depends on which node happened to run the
 * pipeline. It also means a failed or abandoned stream leaves nothing behind in the real store.</p>
 *
 * <p>Mirrors {@code stroom.planb.impl.dao.ShardWriters}, with one simplification: a graph pipeline names its
 * target graph in a pipeline property rather than per record, so a writer serves exactly one {@link GraphDbDoc}
 * and does not need a doc-keyed map of open environments.</p>
 */
@Singleton
public class GraphShardWriters {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphShardWriters.class);

    private final GraphPaths graphPaths;
    private final GraphFileTransferClient fileTransferClient;

    @Inject
    public GraphShardWriters(final GraphPaths graphPaths,
                             final GraphFileTransferClient fileTransferClient) {
        this.graphPaths = Objects.requireNonNull(graphPaths, "graphPaths must not be null");
        this.fileTransferClient =
                Objects.requireNonNull(fileTransferClient, "fileTransferClient must not be null");

        // Clear the writer dir on startup. Anything still in it belongs to a stream that did not finish, so it was
        // never sent and the stream will be reprocessed.
        if (Files.isDirectory(graphPaths.getWriterDir())) {
            FileUtil.deleteDir(graphPaths.getWriterDir());
        }
    }

    /**
     * Creates a fragment writer for one stream's mutations against one graph.
     *
     * <p><b>Preconditions:</b> neither parameter is null.
     * <b>Postconditions:</b> an empty fragment store has been provisioned on disk and is open for writing. The
     * caller must close the returned writer, which sends the fragment and cleans up.
     * <b>Null status:</b> no parameter, nor the return value, is nullable.
     *
     * @param meta the stream being processed.
     * @param doc  the graph the mutations target.
     * @return an open fragment writer.
     */
    public GraphShardWriter createWriter(final Meta meta, final GraphDbDoc doc) {
        Objects.requireNonNull(meta, "meta must not be null");
        Objects.requireNonNull(doc, "doc must not be null");

        final Path dir;
        try {
            // The random suffix keeps two filters writing the same stream and graph from colliding.
            dir = graphPaths.getWriterDir().resolve(meta.getId() + "_" + UUID.randomUUID());
            Files.createDirectories(dir);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return new GraphShardWriter(fileTransferClient, dir, meta, doc);
    }

    /**
     * One stream's worth of graph mutations, written into a store of its own and sent on close.
     *
     * <p>The fragment directory is named after the graph's UUID because that is how the merge side decides which
     * store to merge it into - the same convention Plan B uses, and the reason the two features must not share
     * staging directories.</p>
     */
    public static class GraphShardWriter implements AutoCloseable {

        private final GraphFileTransferClient fileTransferClient;
        private final Path dir;
        private final Meta meta;
        private final GraphStores stores;
        private final LmdbWriter writer;
        private boolean dirty;

        GraphShardWriter(final GraphFileTransferClient fileTransferClient,
                         final Path dir,
                         final Meta meta,
                         final GraphDbDoc doc) {
            this.fileTransferClient = fileTransferClient;
            this.dir = dir;
            this.meta = meta;
            this.stores = GraphStores.provision(dir.resolve(doc.getUuid()), doc);
            this.writer = stores.createWriter();
        }

        /**
         * The fragment's stores, to write mutations into.
         *
         * @return never null.
         */
        public GraphStores getStores() {
            return stores;
        }

        /**
         * The fragment's writer. Callers keep their own commit and abort discipline; this class does not commit.
         *
         * @return never null.
         */
        public LmdbWriter getWriter() {
            return writer;
        }

        /**
         * Records that at least one mutation has been committed, so the fragment is worth sending.
         *
         * <p>Without this an empty fragment would be zipped, shipped and merged for every stream that reached a
         * graph filter but contained no graph records.</p>
         */
        public void markDirty() {
            dirty = true;
        }

        /**
         * Closes the fragment and, if anything was written to it, sends it for merging.
         *
         * <p><b>Postconditions:</b> the fragment's environment is closed and its working directory and zip are
         * deleted, whether or not sending succeeded. A send failure propagates so the stream task fails rather
         * than silently losing the fragment.</p>
         */
        @Override
        public void close() {
            LOGGER.debug(() -> LogUtil.message("Graph DB finished processing for {}", meta));
            final Path parent = dir.getParent();
            final Path zipFile = parent.resolve(dir.getFileName().toString() + SequentialFileStore.ZIP_EXTENSION);

            try {
                // The environment must be closed even if closing the writer fails, or a failed stream leaks an
                // LMDB environment per attempt.
                try {
                    writer.close();
                } finally {
                    stores.close();
                }

                if (dirty) {
                    LOGGER.debug(() -> LogUtil.message("Graph DB zipping data for {}", meta));
                    ZipUtil.zip(zipFile, dir);
                    final String fileHash = FileHashUtil.hash(zipFile);

                    final FileDescriptor fileDescriptor = new FileDescriptor(
                            System.currentTimeMillis(),
                            meta.getId(),
                            fileHash);
                    LOGGER.debug(() -> LogUtil.message(
                            "Graph DB sending data {} for {}",
                            zipFile.getFileName().toString(),
                            meta));
                    fileTransferClient.storePart(fileDescriptor, zipFile, false);
                }
            } catch (final IOException e) {
                throw new UncheckedIOException(e);

            } finally {
                try {
                    FileUtil.deleteDir(dir);
                    Files.deleteIfExists(zipFile);
                } catch (final Exception e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        }
    }
}
