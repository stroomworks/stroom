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

import stroom.util.io.FileUtil;
import stroom.util.io.HomeDirProvider;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Detects a {@code graphdb.path} that has been changed without the data being moved with it.
 *
 * <p>That edit is silent and its consequences are not: the new root does not exist, so it is created, and every
 * graph is provisioned empty. Queries then answer from nothing. Nothing distinguishes that from a deployment
 * where no graph has been loaded yet, which is why it needs detecting rather than merely reporting.</p>
 *
 * <p><b>The marker lives outside {@code graphdb.path}, and it has to.</b> A marker inside the root could only
 * ever be found by looking in the root - and the whole failure is that nobody is looking at the old one any
 * more. So the last-used root is recorded under {@code stroom.home}, which is node-local state that survives a
 * {@code graphdb.path} edit. Changing <em>that</em> defeats this check, but it is a far more deliberate act and
 * relocates every other piece of node state with it.</p>
 *
 * <p>Only a change that <b>stranded data</b> is an error. Moving the store directory along with the setting is
 * the documented procedure and leaves nothing behind, so it is noted and accepted. The check is therefore not
 * "did the path change" - which would fire on the correct action - but "did the path change and leave graphs
 * where nothing will look for them".</p>
 */
@Singleton
public class GraphRootMarker {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphRootMarker.class);

    /**
     * Under {@code stroom.home} rather than under the graph root, for the reason in this class's Javadoc.
     * A plain text file so an operator can read and correct it without tooling.
     */
    private static final String MARKER_FILE_NAME = "graphdb-root.txt";

    private final GraphPaths graphPaths;
    private final HomeDirProvider homeDirProvider;

    @Inject
    public GraphRootMarker(final GraphPaths graphPaths,
                           final HomeDirProvider homeDirProvider) {
        this.graphPaths = Objects.requireNonNull(graphPaths, "graphPaths must not be null");
        this.homeDirProvider = Objects.requireNonNull(homeDirProvider, "homeDirProvider must not be null");
    }

    /**
     * Compares the configured graph root with the one this node last used, and reports a change that stranded
     * data.
     *
     * <p><b>Postconditions:</b> the marker records the configured root, unless data was stranded - in which case
     * it is left recording the previous root so the error repeats on every startup until the data is moved or
     * removed. A stranded graph is not a transient condition and should not be reported once and forgotten.
     * Never throws: a node that cannot read or write the marker still starts, having said so.</p>
     *
     * @return what was found. The startup task ignores it and relies on the logging; it is returned so the
     *         decision can be asserted on directly rather than inferred from the marker's state afterwards.
     */
    public Result check() {
        final Path configuredRoot = graphPaths.getRootDir().toAbsolutePath().normalize();
        final Path markerFile = homeDirProvider.get().resolve(MARKER_FILE_NAME);

        final Path previousRoot = read(markerFile);
        if (previousRoot == null) {
            // First run, or the first run since this check existed. Nothing to compare against; record and move
            // on rather than guessing, because an unrecorded root is exactly what a fresh install looks like.
            write(markerFile, configuredRoot);
            return new Result(Outcome.RECORDED, null, configuredRoot, List.of());
        }
        if (previousRoot.equals(configuredRoot)) {
            return new Result(Outcome.UNCHANGED, previousRoot, configuredRoot, List.of());
        }

        final List<String> stranded = graphsUnder(previousRoot);
        if (stranded.isEmpty()) {
            LOGGER.info(() -> LogUtil.message(
                    "graphdb.path has changed from '{}' to '{}'. Nothing was left behind, so either the data was "
                    + "moved with it or there was none.",
                    previousRoot, configuredRoot));
            write(markerFile, configuredRoot);
            return new Result(Outcome.MOVED, previousRoot, configuredRoot, List.of());
        }

        LOGGER.error(() -> LogUtil.message(
                "graphdb.path has changed from '{}' to '{}', but {} graph store(s) are still at the old path and "
                + "nothing will read them: {}. Every graph on this node will answer as though it holds no data. "
                + "Either move '{}' to '{}' with this node stopped, or put graphdb.path back. This will be "
                + "reported on every startup until the old directory is moved or removed.",
                previousRoot, configuredRoot, stranded.size(), stranded,
                previousRoot.resolve(shardDirName()), configuredRoot.resolve(shardDirName())));
        return new Result(Outcome.STRANDED, previousRoot, configuredRoot, stranded);
    }

    /** What {@link #check()} concluded. */
    public enum Outcome {
        /** No marker existed, so there was nothing to compare against. Now recorded. */
        RECORDED,
        /** The configured root is the one this node last used. */
        UNCHANGED,
        /** The root changed but nothing was left at the old one, so the data moved with it or there was none. */
        MOVED,
        /** The root changed and graph stores are still at the old one, where nothing will read them. */
        STRANDED
    }

    /**
     * @param outcome         what was concluded.
     * @param previousRoot    the root this node last used, or null on a first run.
     * @param configuredRoot  the root {@code graphdb.path} now resolves to.
     * @param strandedGraphs  the graph store directory names left at {@code previousRoot}; empty unless
     *                        {@code outcome} is {@link Outcome#STRANDED}.
     */
    public record Result(Outcome outcome,
                         Path previousRoot,
                         Path configuredRoot,
                         List<String> strandedGraphs) {

    }

    /** The graph store directories under a root, or empty if it holds none. */
    private List<String> graphsUnder(final Path root) {
        final Path shardDir = root.resolve(shardDirName());
        if (!Files.isDirectory(shardDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(shardDir)) {
            return children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            // Unreadable is not the same as absent. Treating it as absent would silently downgrade the error
            // this class exists to raise, so it is reported and treated as "nothing found" only for counting.
            LOGGER.error(() -> "Unable to list " + FileUtil.getCanonicalPath(shardDir), e);
            return List.of();
        }
    }

    /** The shard directory's name relative to a root, taken from {@link GraphPaths} so the two cannot drift. */
    private String shardDirName() {
        return graphPaths.getShardDir().getFileName().toString();
    }

    private Path read(final Path markerFile) {
        if (!Files.isRegularFile(markerFile)) {
            return null;
        }
        try {
            final String recorded = Files.readString(markerFile, StandardCharsets.UTF_8).trim();
            return recorded.isEmpty()
                    ? null
                    : Path.of(recorded).toAbsolutePath().normalize();
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(() -> "Unable to read " + FileUtil.getCanonicalPath(markerFile)
                               + "; cannot tell whether graphdb.path has changed", e);
            return null;
        }
    }

    private void write(final Path markerFile, final Path root) {
        try {
            Files.createDirectories(markerFile.getParent());
            Files.writeString(markerFile, root.toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            LOGGER.error(() -> "Unable to write " + FileUtil.getCanonicalPath(markerFile)
                               + "; a later graphdb.path change will not be detected", e);
        }
    }
}
