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

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.dao.HashClashCommitRunnable;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.planb.impl.dao.UidLookupDb.StaticUnsignedBytesFactory;

import org.lmdbjava.Txn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Owns and provisions every internal store a {@link GraphDbDoc} needs, as a single unit (design doc &sect;2.1;
 * implementation plan Tasks PoC.0/PoC.4). A graph's user never addresses these stores directly — only through the
 * document's {@link GraphDbDoc#getUuid()}-named on-disk directory, opened via this class.
 *
 * <p>The owned stores are the four interning namespaces the frozen physical model (design doc &sect;5.1) fixes
 * (node-external-id, label, edge-type, property-key — each a {@link UidLookupDb} with a <b>fixed-width</b> UID
 * per the class constants, rather than {@code UidLookupDb}'s variable-width default, which would silently break
 * the composite-key prefix scans below) plus the node store ({@link GraphNodeDb}), out-edge adjacency
 * ({@link GraphAdjacencyDb}), in-edge adjacency ({@link GraphInEdgeDb}, Task P1.1), and property-value index
 * ({@link GraphPropertyIndex}) — all sharing one owned {@link PlanBEnv}.
 */
public final class GraphStores implements AutoCloseable {

    /**
     * Fixed byte-width for interned node-external-id UIDs (P0.1 frozen model: &le;2.8&times;10^14 distinct nodes).
     */
    public static final int NODE_UID_WIDTH = 6;

    /**
     * Fixed byte-width for interned label, edge-type and property-key UIDs (P0.1 frozen model:
     * &le;4.29&times;10^9 distinct values per namespace).
     */
    public static final int TYPE_UID_WIDTH = 4;

    private static final int MAX_DBS = 32;

    private final PlanBEnv env;
    private final UidLookupDb nodeUids;
    private final UidLookupDb labelUids;
    private final UidLookupDb edgeTypeUids;
    private final UidLookupDb propertyKeyUids;
    private final GraphNodeDb nodes;
    private final GraphAdjacencyDb outEdges;
    private final GraphInEdgeDb inEdges;
    private final GraphPropertyIndex propertyIndex;

    private GraphStores(final PlanBEnv env,
                        final UidLookupDb nodeUids,
                        final UidLookupDb labelUids,
                        final UidLookupDb edgeTypeUids,
                        final UidLookupDb propertyKeyUids,
                        final GraphNodeDb nodes,
                        final GraphAdjacencyDb outEdges,
                        final GraphInEdgeDb inEdges,
                        final GraphPropertyIndex propertyIndex) {
        this.env = env;
        this.nodeUids = nodeUids;
        this.labelUids = labelUids;
        this.edgeTypeUids = edgeTypeUids;
        this.propertyKeyUids = propertyKeyUids;
        this.nodes = nodes;
        this.outEdges = outEdges;
        this.inEdges = inEdges;
        this.propertyIndex = propertyIndex;
    }

    /**
     * Provisions a new, empty set of internal stores for {@code doc} under {@code directory} for read/write use
     * (creating {@code directory} if it does not already exist).
     *
     * <p><b>Preconditions:</b> neither parameter is null.
     * <b>Postconditions:</b> the returned instance owns an open, writable LMDB environment under {@code directory}
     * with all four interning namespaces open.
     * <b>Null status:</b> no parameter, nor the return value, is nullable.
     *
     * @param directory the on-disk directory to provision the stores under; created if absent.
     * @param doc       the owning document.
     * @return an open, writable {@link GraphStores}.
     */
    public static GraphStores provision(final Path directory, final GraphDbDoc doc) {
        return open(directory, doc, false);
    }

    /**
     * Opens the internal stores for {@code doc} under {@code directory}.
     *
     * <p><b>Preconditions:</b> neither {@code directory} nor {@code doc} is null.
     * <b>Postconditions:</b> the returned instance owns an open LMDB environment under {@code directory}; if
     * {@code readOnly} is false and {@code directory} did not previously exist, it is created empty and opened for
     * writing.
     * <b>Null status:</b> no parameter, nor the return value, is nullable.
     *
     * @param directory the on-disk directory the stores live under.
     * @param doc       the owning document.
     * @param readOnly  whether to open the environment read-only.
     * @return an open {@link GraphStores}.
     */
    public static GraphStores open(final Path directory, final GraphDbDoc doc, final boolean readOnly) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(doc, "doc must not be null");

        if (!readOnly) {
            try {
                Files.createDirectories(directory);
            } catch (final IOException e) {
                throw new UncheckedIOException("Unable to create graph store directory: " + directory, e);
            }
        }

        final ByteBuffers byteBuffers = new ByteBuffers(new ByteBufferFactoryImpl());
        final PlanBEnv env = new PlanBEnv(
                directory,
                null,
                MAX_DBS,
                readOnly,
                new HashClashCommitRunnable());
        try {
            final UidLookupDb nodeUids = new UidLookupDb(
                    env, byteBuffers, "node-uid",
                    new StaticUnsignedBytesFactory(UnsignedBytesInstances.ofLength(NODE_UID_WIDTH)));
            final UidLookupDb labelUids = new UidLookupDb(
                    env, byteBuffers, "label-uid",
                    new StaticUnsignedBytesFactory(UnsignedBytesInstances.ofLength(TYPE_UID_WIDTH)));
            final UidLookupDb edgeTypeUids = new UidLookupDb(
                    env, byteBuffers, "edge-type-uid",
                    new StaticUnsignedBytesFactory(UnsignedBytesInstances.ofLength(TYPE_UID_WIDTH)));
            final UidLookupDb propertyKeyUids = new UidLookupDb(
                    env, byteBuffers, "property-key-uid",
                    new StaticUnsignedBytesFactory(UnsignedBytesInstances.ofLength(TYPE_UID_WIDTH)));
            final GraphNodeDb nodes = new GraphNodeDb(env);
            final GraphAdjacencyDb outEdges = new GraphAdjacencyDb(env);
            final GraphInEdgeDb inEdges = new GraphInEdgeDb(env);
            final GraphPropertyIndex propertyIndex = new GraphPropertyIndex(env);
            return new GraphStores(
                    env, nodeUids, labelUids, edgeTypeUids, propertyKeyUids, nodes, outEdges, inEdges,
                    propertyIndex);
        } catch (final RuntimeException e) {
            env.close();
            throw e;
        }
    }

    /**
     * @return the node-external-id interning namespace (fixed {@link #NODE_UID_WIDTH}-byte UIDs).
     */
    public UidLookupDb getNodeUids() {
        return nodeUids;
    }

    /**
     * @return the label interning namespace (fixed {@link #TYPE_UID_WIDTH}-byte UIDs).
     */
    public UidLookupDb getLabelUids() {
        return labelUids;
    }

    /**
     * @return the edge-type interning namespace (fixed {@link #TYPE_UID_WIDTH}-byte UIDs).
     */
    public UidLookupDb getEdgeTypeUids() {
        return edgeTypeUids;
    }

    /**
     * @return the property-key interning namespace (fixed {@link #TYPE_UID_WIDTH}-byte UIDs).
     */
    public UidLookupDb getPropertyKeyUids() {
        return propertyKeyUids;
    }

    /**
     * @return the node store.
     */
    public GraphNodeDb getNodes() {
        return nodes;
    }

    /**
     * @return the out-edge adjacency store.
     */
    public GraphAdjacencyDb getOutEdges() {
        return outEdges;
    }

    /**
     * @return the in-edge adjacency store (Task P1.1) - the reverse mirror {@link #getOutEdges()} lacks; callers
     * writing an edge must write both.
     */
    public GraphInEdgeDb getInEdges() {
        return inEdges;
    }

    /**
     * @return the property-value anchor index.
     */
    public GraphPropertyIndex getPropertyIndex() {
        return propertyIndex;
    }

    /**
     * Runs {@code function} inside a single write transaction against the owned LMDB environment, committing on
     * return. Every {@link UidLookupDb} exposed by this class requires a transaction obtained this way (or via
     * {@link #read}) to {@code put}/{@code get} against.
     *
     * <p><b>Preconditions:</b> {@code function} is not null. <b>Postconditions:</b> the transaction is committed
     * before this method returns. <b>Null status:</b> {@code function} is not nullable; its result may be null only
     * if {@code T} is a reference type and {@code function} chooses to return null.
     *
     * @param function the write operation; receives an {@link LmdbWriter} to obtain the write {@code Txn} from.
     * @param <T>       the result type.
     * @return whatever {@code function} returns.
     */
    public <T> T write(final Function<LmdbWriter, T> function) {
        Objects.requireNonNull(function, "function must not be null");
        return env.write(function);
    }

    /**
     * Runs {@code function} inside a single read transaction against the owned LMDB environment.
     *
     * <p><b>Preconditions:</b> {@code function} is not null. <b>Null status:</b> {@code function} is not nullable.
     *
     * @param function the read operation; receives the read {@code Txn}.
     * @param <T>       the result type.
     * @return whatever {@code function} returns.
     */
    public <T> T read(final Function<Txn<ByteBuffer>, T> function) {
        Objects.requireNonNull(function, "function must not be null");
        return env.read(function);
    }

    /**
     * Closes the owned LMDB environment.
     *
     * <p><b>Preconditions:</b> none. <b>Postconditions:</b> the LMDB environment is closed; no further reads or
     * writes are possible through this instance. Calling this more than once, or using this instance afterwards, is
     * a programming error.
     */
    @Override
    public void close() {
        env.close();
    }

    /**
     * Deletes every internal store under {@code directory}, recursively. Used both for a document delete and as
     * the first half of {@link #rebuild}.
     *
     * <p><b>Preconditions:</b> {@code directory} is not null; the caller must have already closed any
     * {@link GraphStores} open on this directory (an open LMDB environment holds file handles that would make
     * deletion fail or corrupt the store).
     * <b>Postconditions:</b> {@code directory} and everything under it no longer exists. A no-op if
     * {@code directory} did not exist.
     * <b>Null status:</b> {@code directory} is not nullable.
     *
     * @param directory the on-disk directory to delete.
     */
    public static void delete(final Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (final IOException e) {
                            throw new UncheckedIOException("Unable to delete: " + path, e);
                        }
                    });
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to walk graph store directory: " + directory, e);
        }
    }

    /**
     * Drops every internal store under {@code directory} and re-provisions them empty, for reprocessing (design
     * doc &sect;5.2: the graph is a materialized projection, rebuildable by re-running ingest over stored streams).
     *
     * <p><b>Preconditions:</b> neither {@code directory} nor {@code doc} is null.
     * <b>Postconditions:</b> this instance is closed; the stores under {@code directory} are deleted and
     * re-provisioned empty; the returned instance is open and owns the new environment.
     * <b>Null status:</b> no parameter, nor the return value, is nullable.
     *
     * @param directory the on-disk directory the stores live under.
     * @param doc       the owning document.
     * @return a new, empty, open {@link GraphStores} for the same directory.
     */
    public GraphStores rebuild(final Path directory, final GraphDbDoc doc) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        close();
        delete(directory);
        return provision(directory, doc);
    }
}
