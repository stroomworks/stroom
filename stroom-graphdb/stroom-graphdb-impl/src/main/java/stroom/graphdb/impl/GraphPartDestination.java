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

import stroom.planb.impl.data.FileDescriptor;
import stroom.planb.impl.data.SequentialFileStore;
import stroom.security.api.SecurityContext;
import stroom.util.io.FileUtil;
import stroom.util.io.StreamUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.PermissionException;
import stroom.util.string.StringIdUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The entry point for graph fragments arriving at this node, whether written locally or sent by another node.
 *
 * <p>Mirrors {@code stroom.planb.impl.data.PartDestination}. It exists as a separate class from the merge
 * processor because a fragment sent over HTTP arrives as a stream that must be landed on disk before it can be
 * moved into the staging store, and because that landing area must be cleared at startup - a partly-received file
 * from a killed process is not a fragment, and merging it would be merging a truncated LMDB environment.</p>
 */
@Singleton
public class GraphPartDestination {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphPartDestination.class);

    private final SecurityContext securityContext;
    private final Provider<GraphMergeProcessor> mergeProcessorProvider;

    private final Path receiveDir;
    private final AtomicLong receiveId = new AtomicLong();

    @Inject
    public GraphPartDestination(final SecurityContext securityContext,
                                final GraphPaths graphPaths,
                                final Provider<GraphMergeProcessor> mergeProcessorProvider) {
        this.securityContext = securityContext;
        this.mergeProcessorProvider = mergeProcessorProvider;

        // Create the receive directory. Anything already in it is a partly-received file from a previous run.
        receiveDir = graphPaths.getReceiveDir();
        FileUtil.ensureDirExists(receiveDir);
        if (!FileUtil.deleteContents(receiveDir)) {
            throw new RuntimeException("Unable to delete contents of: " + FileUtil.getCanonicalPath(receiveDir));
        }
    }

    /**
     * Receives a fragment sent by another node.
     *
     * <p><b>Preconditions:</b> the caller is the processing user; {@code inputStream} carries a complete fragment
     * zip whose hash is {@code fileHash}.
     * <b>Postconditions:</b> the fragment has been landed on disk and handed to the merge processor.
     * <b>Null status:</b> no parameter is nullable.
     *
     * @param createTime       when the sending node created the fragment.
     * @param metaId           the stream the fragment was produced from.
     * @param fileHash         the fragment zip's hash, verified when it is staged.
     * @param fileName         the sender's file name, for logging only.
     * @param synchroniseMerge whether the sender is waiting for the merge to complete.
     * @param inputStream      the fragment zip's bytes.
     * @throws IOException if the fragment cannot be written or staged.
     */
    public void receiveRemotePart(final long createTime,
                                  final long metaId,
                                  final String fileHash,
                                  final String fileName,
                                  final boolean synchroniseMerge,
                                  final InputStream inputStream) throws IOException {
        LOGGER.debug(() -> "Graph DB receiving remote part: " + fileName);

        if (!securityContext.isProcessingUser()) {
            throw new PermissionException(securityContext.getUserRef(), "Only processing users can use this resource");
        }

        final FileDescriptor fileDescriptor = new FileDescriptor(createTime, metaId, fileHash);
        final String receiveFileName = StringIdUtil.idToString(receiveId.incrementAndGet()) +
                                       SequentialFileStore.ZIP_EXTENSION;
        final Path receiveFile = receiveDir.resolve(receiveFileName);
        StreamUtil.streamToFile(inputStream, receiveFile);

        mergeProcessorProvider.get().add(fileDescriptor, receiveFile, synchroniseMerge);
    }

    /**
     * Receives a fragment written on this node.
     *
     * <p><b>Preconditions:</b> {@code sourcePath} is a complete fragment zip whose hash matches
     * {@code fileDescriptor}.
     * <b>Postconditions:</b> the fragment has been handed to the merge processor; if {@code allowMove} the file at
     * {@code sourcePath} has been moved rather than copied, and no longer exists.
     * <b>Null status:</b> neither {@code fileDescriptor} nor {@code sourcePath} is nullable.
     *
     * @param fileDescriptor   identifies the fragment and carries its hash.
     * @param sourcePath       the fragment zip.
     * @param allowMove        whether the caller has given up ownership of {@code sourcePath}.
     * @param synchroniseMerge whether to block until the fragment has been merged.
     * @throws IOException if the fragment cannot be copied or staged.
     */
    public void receiveLocalPart(final FileDescriptor fileDescriptor,
                                 final Path sourcePath,
                                 final boolean allowMove,
                                 final boolean synchroniseMerge) throws IOException {
        LOGGER.debug(() -> "Graph DB receiving local part: " + fileDescriptor.getInfo(sourcePath));

        final GraphMergeProcessor mergeProcessor = mergeProcessorProvider.get();
        if (allowMove) {
            // If we allow move then the staging store can move the file directly into itself.
            mergeProcessor.add(fileDescriptor, sourcePath, synchroniseMerge);

        } else {
            // Otherwise copy to a location we own before it is moved into the staging store.
            final String receiveFileName = StringIdUtil.idToString(receiveId.incrementAndGet()) +
                                           SequentialFileStore.ZIP_EXTENSION;
            final Path receiveFile = receiveDir.resolve(receiveFileName);
            Files.copy(sourcePath, receiveFile);
            mergeProcessor.add(fileDescriptor, receiveFile, synchroniseMerge);
        }
    }
}
