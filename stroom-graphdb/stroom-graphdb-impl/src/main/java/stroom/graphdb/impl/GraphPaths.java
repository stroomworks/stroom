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

import stroom.util.io.PathCreator;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/**
 * The on-disk locations Graph DB uses, resolved once from {@link GraphDbConfig#getPath()}.
 *
 * <p>Mirrors {@code stroom.planb.impl.dao.StatePaths}, including the directory names, because Graph DB follows
 * the same fragment lifecycle: a node writes a fragment per stream, ships it, and the receiving node stages,
 * unzips and merges it into the authoritative store.</p>
 *
 * <p><b>These directories are separate from Plan B's</b> even though the layout matches. That is deliberate and
 * not cosmetic: Plan B's merge loop deletes a queued fragment whose owning document cannot be resolved, and a
 * graph document is not resolvable as a Plan B document - so sharing directories would let Plan B silently
 * discard graph fragments. Separate roots make that impossible rather than dependent on resolution order.</p>
 */
@Singleton
public class GraphPaths {

    // The root directory for all graph data on this node.
    private final Path rootDir;
    // Each node writes a fragment per processed stream here, before shipping it.
    private final Path writerDir;
    // Fragments arriving from other nodes land here.
    private final Path receiveDir;
    // Received fragments move here to await merge, in arrival order.
    private final Path stagingDir;
    // Staged zips are expanded here before their parts are queued.
    private final Path unzipDir;
    // Per-graph queues of fragments waiting to be merged.
    private final Path mergingDir;
    // The authoritative store for each graph.
    private final Path shardDir;
    // Reserved for read-only whole-store copies; unused until snapshots are implemented.
    private final Path snapshotDir;

    @Inject
    public GraphPaths(final Provider<GraphDbConfig> configProvider,
                      final PathCreator pathCreator) {
        this(pathCreator.toAppPath(configProvider.get().getPath()));
    }

    /**
     * <p><b>Preconditions:</b> {@code rootDir} is not null.
     * <b>Postconditions:</b> every directory below is resolved but not created; callers create what they use.
     * <b>Null status:</b> {@code rootDir} is not nullable.
     *
     * @param rootDir the root to resolve everything else against.
     */
    public GraphPaths(final Path rootDir) {
        this.rootDir = rootDir;
        writerDir = rootDir.resolve("writer");
        receiveDir = rootDir.resolve("receive");
        stagingDir = rootDir.resolve("staging");
        unzipDir = rootDir.resolve("unzip");
        mergingDir = rootDir.resolve("merging");
        shardDir = rootDir.resolve("shards");
        snapshotDir = rootDir.resolve("snapshots");
    }

    public Path getRootDir() {
        return rootDir;
    }

    public Path getWriterDir() {
        return writerDir;
    }

    public Path getReceiveDir() {
        return receiveDir;
    }

    public Path getStagingDir() {
        return stagingDir;
    }

    public Path getUnzipDir() {
        return unzipDir;
    }

    public Path getMergingDir() {
        return mergingDir;
    }

    public Path getShardDir() {
        return shardDir;
    }

    public Path getSnapshotDir() {
        return snapshotDir;
    }
}
