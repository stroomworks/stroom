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

package stroom.pathways.impl;

import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.PlanBConstants;
import stroom.planb.impl.fs.SharedFileStorePublisher;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.util.io.FileUtil;
import stroom.util.io.PathCreator;
import stroom.util.io.PathSegmentUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Predicate;

/**
 * Lends out one shard of a Pathways document's learnt model, as a local copy.
 *
 * <p>The authoritative copy lives on the shared filesystem and is never opened there — no LMDB
 * environment is ever opened on the shared mount, which is an invariant of the whole shared file
 * store. So a caller is handed a throwaway local directory, works in that, and the finished file is
 * copied back up.
 *
 * <p><b>Call only while holding the shard's cluster lock.</b> That lock is the whole of the mutual
 * exclusion: the push does not check that the shared copy is still the one it handed down, so it
 * would overwrite whatever is there. In normal running that cannot bite, because the lock is taken
 * by a conditional update of one database row and so admits one holder at a time, on one node or
 * across all of them. The one case it does not cover is a lease that expires while its holder still
 * believes it holds the lock — then two processes can both be in here, and the later push discards
 * the earlier one's work silently. Plan B's own two shared-store write paths have the same exposure,
 * and it is accepted here for the same reason: a hold bounded to seconds against a lease measured in
 * minutes makes it rare.
 */
@Singleton
public class PathwaysShardStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysShardStore.class);

    /** Where a Pathways document's model lives, beside the {@code queue} its traces arrive in. */
    public static final String SHARDS_DIR_NAME = "shards";

    private final SharedFileStorePublisher publisher;
    private final Path localRoot;

    @Inject
    public PathwaysShardStore(final SharedFileStorePublisher publisher,
                              final PathCreator pathCreator) {
        this.publisher = publisher;
        this.localRoot = pathCreator.toAppPath("${stroom.home}/pathways").resolve("shards");
    }

    /**
     * Copies the shard down, runs {@code work} against the local copy, and copies it back up if
     * {@code work} says it changed anything.
     *
     * <p>Nothing is pushed for a shard that was only read, which matters because the push moves the
     * whole file: a cycle that applied nothing would otherwise rewrite the model to say so.
     *
     * @param work given the local directory, returns whether it changed what is in it. Anything it
     *             throws propagates, and the shard is left as it was on the shared store.
     * @return whether the model was pushed back.
     */
    public boolean withShard(final PathwaysDoc doc,
                             final int shardIndex,
                             final Predicate<Path> work) throws IOException {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null) {
            throw new IOException("Pathways document " + doc.getName() + " names no shared file store");
        }
        final Path sharedDocDir = Path.of(settings.getSharedPath())
                .resolve(SHARDS_DIR_NAME)
                .resolve(PathSegmentUtil.requireSafeSegment(doc.getUuid()));

        // Undo the half-finished state an interrupted push leaves behind, before anything reads the
        // shard — otherwise the copy down below could take a directory mid-swap.
        publisher.recoverOrphaned(sharedDocDir, shardIndex);

        final Path localDir = localRoot
                .resolve(PathSegmentUtil.requireSafeSegment(doc.getUuid())
                         + "_" + PlanBConstants.formatShardIndex(shardIndex));
        FileUtil.deleteDir(localDir);
        Files.createDirectories(localDir);
        try {
            copyDown(sharedDocDir.resolve(PlanBConstants.formatShardIndex(shardIndex)), localDir);

            if (!work.test(localDir)) {
                LOGGER.debug(() -> LogUtil.message("Shard {} of {} was not changed, so not pushed",
                        shardIndex, doc.getName()));
                return false;
            }
            publisher.push(localDir, sharedDocDir, shardIndex);
            return true;

        } finally {
            try {
                FileUtil.deleteDir(localDir);
            } catch (final RuntimeException e) {
                // A leftover working copy costs disk, not correctness: the next cycle clears it before
                // copying down again.
                LOGGER.warn(() -> "Could not clean up " + localDir + ": " + e.getMessage());
            }
        }
    }

    // A shard nothing has written yet has no data file, and the caller then starts from an empty
    // directory. Replace rather than create: an interrupted cycle can leave a partial file behind, and
    // failing on it would stop this shard being worked until the process restarts.
    private static void copyDown(final Path sharedShardDir, final Path localDir) throws IOException {
        final Path sharedData = sharedShardDir.resolve(PlanBConstants.DATA_FILE_NAME);
        if (Files.exists(sharedData)) {
            Files.copy(sharedData, localDir.resolve(PlanBConstants.DATA_FILE_NAME),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
