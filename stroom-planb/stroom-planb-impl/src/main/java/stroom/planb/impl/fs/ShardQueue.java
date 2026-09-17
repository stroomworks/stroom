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

package stroom.planb.impl.fs;

import stroom.planb.impl.PlanBConstants;
import stroom.planb.impl.dao.trace.QueueItem;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.util.io.PathSegmentUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * One shard's queue folder on the shared filesystem.
 *
 * <p>Where the folder is, what counts as an item in it, what order items come out in, how deep it is,
 * and how an item leaves — applied or set aside. Both ends of the hand-over go through this, so the
 * producer cannot write somewhere the consumer does not read.
 *
 * <p>Deals only in paths and counts. What is inside an item is {@link QueueItem}'s business, and the
 * two split along that line: Plan B owns the item's format, this owns the folder holding them.
 */
public final class ShardQueue {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShardQueue.class);

    /** Items that could not be applied, kept rather than deleted so they can be looked at. */
    public static final String QUARANTINE_DIR_NAME = "quarantine";

    private final Path shardDir;

    private ShardQueue(final Path shardDir) {
        this.shardDir = shardDir;
    }

    public static ShardQueue of(final SharedFileStoreSettings settings,
                         final String pathwaysDocUuid,
                         final int shard) {
        return new ShardQueue(Path.of(settings.getSharedPath())
                .resolve(PlanBConstants.QUEUE_DIR_NAME)
                .resolve(PathSegmentUtil.requireSafeSegment(pathwaysDocUuid))
                .resolve(PlanBConstants.formatShardIndex(shard)));
    }

    /**
     * How many shards a document's queue and model are split across. Clamped, because a document
     * saved with nothing set would otherwise route every trace to shard -1.
     */
    public static int shardCount(final SharedFileStoreSettings settings) {
        return Math.max(1, settings.getShardCount());
    }

    /**
     * Where the folder is. Not created here: the writer creates the item's own temporary directory
     * beneath it, which brings this into being with it.
     */
    public Path dir() {
        return shardDir;
    }

    /**
     * The items waiting, oldest first, so a mutation is attributed to the trace that really caused it.
     * Empty where the folder does not exist or cannot be listed — a shard nothing has been written for
     * is the ordinary case, and a listing that fails is not worth failing the pass for.
     */
    public List<Path> itemsOldestFirst() {
        if (!Files.isDirectory(shardDir)) {
            return List.of();
        }
        try (final Stream<Path> stream = Files.list(shardDir)) {
            return stream.filter(QueueItem::isItem).sorted(QueueItem.BY_ORDER_KEY).toList();
        } catch (final IOException e) {
            LOGGER.error(() -> "Could not list queue items in " + shardDir + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Whether more than {@code max} items are waiting. Counts no further than it has to, because the
     * answer is wanted on every hand-over and the folder is on a shared mount.
     *
     * @param max how deep the caller is prepared to let the folder get. What to do about it is the
     *            caller's: this only measures.
     */
    public boolean isDeeperThan(final int max) throws IOException {
        if (!Files.isDirectory(shardDir)) {
            return false;
        }
        try (final Stream<Path> stream = Files.list(shardDir)) {
            return stream.filter(QueueItem::isItem).limit(max + 1L).count() > max;
        }
    }

    /** When the oldest waiting item was released, or empty where nothing is waiting. */
    public OptionalLong oldestOrderKey() {
        final List<Path> items = itemsOldestFirst();
        return items.isEmpty()
                ? OptionalLong.empty()
                : OptionalLong.of(QueueItem.orderKeyOf(items.getFirst()));
    }

    /** Removes items that have been applied and whose model is safely back on the shared store. */
    public void delete(final List<Path> items) {
        for (final Path item : items) {
            try (final Stream<Path> paths = Files.walk(item)) {
                paths.sorted(Collections.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (final IOException e) {
                        LOGGER.warn(() -> "Could not delete " + path + ": " + e.getMessage());
                    }
                });
            } catch (final IOException e) {
                // A leftover item is applied again next cycle, which the design already tolerates.
                LOGGER.warn(() -> "Could not delete applied queue item " + item + ": " + e.getMessage());
            }
        }
    }

    /**
     * Moves an item that could not be applied into {@link #QUARANTINE_DIR_NAME}, where it stops
     * matching {@link QueueItem#isItem} and so is not offered again.
     */
    public void quarantine(final Path item, final Exception cause) {
        try {
            final Path quarantineDir = shardDir.resolve(QUARANTINE_DIR_NAME);
            Files.createDirectories(quarantineDir);
            Files.move(item, quarantineDir.resolve(item.getFileName().toString()),
                    StandardCopyOption.ATOMIC_MOVE);
            LOGGER.error(() -> LogUtil.message("Could not apply queue item {}, moved to {}: {}",
                    item.getFileName(), quarantineDir, cause.getMessage()), cause);
        } catch (final IOException e) {
            LOGGER.error(() -> LogUtil.message(
                    "Could not apply queue item {} and could not set it aside either: {}",
                    item, e.getMessage()), e);
        }
    }

    @Override
    public String toString() {
        return shardDir.toString();
    }
}
