/*
 * Copyright 2026 Crown Copyright
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

package stroom.planb.impl.dao.trace;

import stroom.bytebuffer.impl6.ByteBufferFactory;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Optional;

/**
 * Writes a {@link QueueItem} — the spans, roots and lookup tables of a set of traces, copied out of a
 * trace store into an environment of their own.
 *
 * <p>The item is built under a temporary name and renamed into place as the last thing that happens,
 * so a consumer never sees a part-written one and a producer that dies mid-write leaves only a
 * temporary directory behind.
 */
public class QueueItemWriter {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(QueueItemWriter.class);

    private final ByteBuffers byteBuffers;
    private final ByteBufferFactory byteBufferFactory;

    public QueueItemWriter(final ByteBuffers byteBuffers,
                           final ByteBufferFactory byteBufferFactory) {
        this.byteBuffers = byteBuffers;
        this.byteBufferFactory = byteBufferFactory;
    }

    /**
     * Copies the named traces out of {@code source} into a new item under {@code targetDir}.
     *
     * @param source    the store to read from, which must hold the spans and roots of every trace
     *                  named. The caller decides which traces those are.
     * @param traceIds  raw 16 byte trace ids.
     * @param targetDir the directory items are written into; created where absent.
     * @param orderKey  when the producer released these traces, as epoch milliseconds. It leads the
     *                  item's name and is stored inside it, and is what a consumer applies items in
     *                  the order of.
     * @return the finished item's directory, or empty where there was nothing to send. Writing an
     * empty item would cost a whole environment to say so.
     */
    public Optional<Path> write(final TraceDb source,
                                final Collection<byte[]> traceIds,
                                final Path targetDir,
                                final long orderKey) {
        if (traceIds.isEmpty()) {
            return Optional.empty();
        }

        final String name = QueueItem.newName(orderKey);
        final Path tmpDir = targetDir.resolve(QueueItem.tmpName(name));
        final Path itemDir = targetDir.resolve(name);
        try {
            Files.createDirectories(tmpDir);

            try (final TraceDb item = TraceDb.create(
                    tmpDir,
                    byteBuffers,
                    byteBufferFactory,
                    QueueItem.doc(),
                    false,
                    QueueItem.HAS_SECONDARY_INDEXES)) {
                source.copyTracesTo(item, traceIds);
                QueueItem.writeInfo(item.getEnv(), orderKey);
            }

            // Last, and atomic: until this succeeds nothing matches QueueItem.isItem, so a failure
            // above leaves a temporary directory to be tidied rather than a batch half handed over.
            Files.move(tmpDir, itemDir, StandardCopyOption.ATOMIC_MOVE);
            return Optional.of(itemDir);

        } catch (final IOException e) {
            deleteQuietly(tmpDir);
            throw new UncheckedIOException(e);
        } catch (final RuntimeException e) {
            deleteQuietly(tmpDir);
            throw e;
        }
    }

    private static void deleteQuietly(final Path dir) {
        try (final var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException e) {
                    LOGGER.debug(() -> "Could not delete " + path + ": " + e.getMessage(), e);
                }
            });
        } catch (final IOException e) {
            LOGGER.debug(() -> "Could not clean up " + dir + ": " + e.getMessage(), e);
        }
    }
}
