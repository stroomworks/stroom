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

import stroom.graphdb.impl.GraphRootMarker.Outcome;
import stroom.graphdb.impl.GraphRootMarker.Result;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers detection of a {@code graphdb.path} changed without the data being moved with it.
 *
 * <p>That edit is the last of the config changes that silently produce wrong answers: the new root does not
 * exist, so it is created, and every graph is provisioned empty. Nothing at the new path distinguishes that
 * from a deployment nobody has loaded a graph into yet - which is why the evidence has to be kept outside the
 * path, and why one of these tests is about <b>where the marker lives</b> rather than what it says.</p>
 *
 * <p>The discriminating pair is {@link #pathChangedLeavingGraphsBehind_isReported} against
 * {@link #pathChangedWithTheDataMovedWithIt_isAccepted}. A check that fired on both would fire on the
 * documented procedure for changing the setting, and an operator told off for doing the right thing stops
 * reading the log.</p>
 */
class TestGraphRootMarker {

    private static final String MARKER_FILE_NAME = "graphdb-root.txt";

    /**
     * A fresh install records the root and concludes nothing. There is nothing to compare against, and guessing
     * would mean every new deployment started with an error about data it never had.
     */
    @Test
    void firstRun_recordsTheRootAndReportsNothing(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);

        final Result result = fixture.check("graphdb");

        assertThat(result.outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(result.previousRoot()).isNull();
        assertThat(fixture.recordedRoot()).isEqualTo(fixture.graphRoot("graphdb"));
    }

    /** The overwhelmingly common case, and it must conclude nothing. */
    @Test
    void unchangedPath_isUnremarkable(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        fixture.check("graphdb");

        assertThat(fixture.check("graphdb").outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    /**
     * The case this class exists for: the path moved, graphs are still at the old one, and nothing will ever
     * look there again.
     */
    @Test
    void pathChangedLeavingGraphsBehind_isReported(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        fixture.check("graphdb");
        fixture.createGraphStore("graphdb", "graph-a");
        fixture.createGraphStore("graphdb", "graph-b");

        final Result result = fixture.check("graphdb-new");

        assertThat(result.outcome()).isEqualTo(Outcome.STRANDED);
        assertThat(result.strandedGraphs()).containsExactly("graph-a", "graph-b");
        assertThat(result.previousRoot()).isEqualTo(fixture.graphRoot("graphdb"));
    }

    /**
     * And it must keep saying so. A stranded graph is not a transient condition, so advancing the marker after
     * reporting once would let the next restart bury it.
     */
    @Test
    void strandedGraphs_areReportedOnEveryStartup(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        fixture.check("graphdb");
        fixture.createGraphStore("graphdb", "graph-a");

        assertThat(fixture.check("graphdb-new").outcome()).isEqualTo(Outcome.STRANDED);
        assertThat(fixture.check("graphdb-new").outcome()).as("second startup").isEqualTo(Outcome.STRANDED);
        assertThat(fixture.recordedRoot()).as("marker still records the old root")
                .isEqualTo(fixture.graphRoot("graphdb"));
    }

    /**
     * The documented procedure - stop the node, move the data, change the setting - must be accepted. A check
     * that could not tell this from the case above would be worse than no check at all.
     */
    @Test
    void pathChangedWithTheDataMovedWithIt_isAccepted(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        fixture.check("graphdb");
        fixture.createGraphStore("graphdb", "graph-a");

        fixture.moveShards("graphdb", "graphdb-new");
        final Result result = fixture.check("graphdb-new");

        assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
        assertThat(fixture.recordedRoot()).as("marker advanced").isEqualTo(fixture.graphRoot("graphdb-new"));
    }

    /** A path changed before any graph existed strands nothing, so it is not an error either. */
    @Test
    void pathChangedWithNoGraphsAnywhere_isAccepted(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);
        fixture.check("graphdb");

        assertThat(fixture.check("graphdb-new").outcome()).isEqualTo(Outcome.MOVED);
    }

    /**
     * The marker lives under {@code stroom.home}, not under the graph root. A marker in the root could only be
     * found by looking in the root, and not looking at the old root any more is the entire failure.
     */
    @Test
    void marker_isNotStoredUnderTheGraphRoot(@TempDir final Path root) {
        final Fixture fixture = new Fixture(root);

        fixture.check("graphdb");

        assertThat(fixture.home.resolve(MARKER_FILE_NAME)).exists();
        assertThat(fixture.graphRoot("graphdb").resolve(MARKER_FILE_NAME)).doesNotExist();
    }

    /**
     * An unusable marker must not stop the node. Losing the ability to detect a future change is a far smaller
     * problem than refusing to start, so it is treated as a first run and re-recorded.
     */
    @Test
    void unusableMarker_doesNotPreventStartup(@TempDir final Path root) throws IOException {
        final Fixture fixture = new Fixture(root);
        Files.createDirectories(fixture.home);
        Files.writeString(fixture.home.resolve(MARKER_FILE_NAME), "   ", StandardCharsets.UTF_8);

        assertThat(fixture.check("graphdb").outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(fixture.recordedRoot()).isEqualTo(fixture.graphRoot("graphdb"));
    }

    // ------------------------------------------------------------------------------------------------------

    /** One node: a fixed home directory, and a graph root that the test can point somewhere else. */
    private static final class Fixture {

        private final Path root;
        private final Path home;

        private Fixture(final Path root) {
            this.root = root;
            this.home = root.resolve("home");
        }

        private Result check(final String graphDirName) {
            return new GraphRootMarker(new GraphPaths(graphRoot(graphDirName)), () -> home).check();
        }

        private Path graphRoot(final String graphDirName) {
            return root.resolve(graphDirName).toAbsolutePath().normalize();
        }

        private Path recordedRoot() {
            final Path markerFile = home.resolve(MARKER_FILE_NAME);
            if (!Files.isRegularFile(markerFile)) {
                return null;
            }
            try {
                return Path.of(Files.readString(markerFile, StandardCharsets.UTF_8).trim())
                        .toAbsolutePath()
                        .normalize();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** An empty directory under {@code shards/} is enough - the check counts directories, not contents. */
        private void createGraphStore(final String graphDirName, final String uuid) {
            try {
                Files.createDirectories(graphRoot(graphDirName).resolve("shards").resolve(uuid));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void moveShards(final String from, final String to) {
            try {
                final Path destination = graphRoot(to).resolve("shards");
                Files.createDirectories(destination.getParent());
                Files.move(graphRoot(from).resolve("shards"), destination);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
