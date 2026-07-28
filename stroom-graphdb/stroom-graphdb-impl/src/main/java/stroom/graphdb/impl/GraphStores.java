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
import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.dao.HashClashCommitRunnable;
import stroom.planb.impl.dao.HashLookupDb;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.impl.dao.SchemaInfo;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.planb.impl.dao.UidLookupDb.StaticUnsignedBytesFactory;
import stroom.planb.impl.dao.UidLookupRecorder;
import stroom.planb.impl.dao.VariableUsedLookupsRecorder;
import stroom.planb.impl.serde.hash.HashFactory;
import stroom.planb.impl.serde.hash.HashFactoryFactory;
import stroom.planb.impl.serde.time.TimeSerde;
import stroom.planb.shared.HashLength;
import stroom.planb.shared.RetentionSettings;
import stroom.planb.shared.TemporalPrecision;
import stroom.query.language.functions.Val;
import stroom.util.logging.LogUtil;
import stroom.util.time.SimpleDurationUtil;

import org.lmdbjava.CopyFlags;
import org.lmdbjava.Txn;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>Each of the four interning namespaces also gets a {@link UidLookupRecorder} (Task P1.2) - a mark-and-sweep
 * used-lookups recorder mirroring Plan B's own DAOs (e.g. {@code TemporalStateDb}): a retention pass (Task P1.4)
 * calls {@code recordUsed} for every UID a surviving row still references, then {@code deleteUnused} once every
 * DAO has finished recording, sweeping any UID no longer referenced by anything. Recording and sweeping is not
 * this class's own concern - it only owns and exposes the recorders; the DAOs that reference each namespace call
 * them during their own retention passes.</p>
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

    /**
     * The key layouts this build writes, stamped into {@code graph-info} and validated on every writable open and
     * every merge. Any change to a width, layout or encoding below <b>must</b> be accompanied by a bump to
     * {@link GraphSchemaDb#CURRENT_SCHEMA_VERSION}, because a stale store would otherwise be silently
     * reinterpreted rather than rejected.
     */
    private static final String KEY_SCHEMA_TEMPLATE = """
            {"nodeUidWidth":6,"typeUidWidth":4,"timeSerde":"%s",\
            "node":"[nodeUid][validFrom]",\
            "outEdge":"[srcUid][edgeTypeUid][dstUid][validFrom]",\
            "inEdge":"[dstUid][edgeTypeUid][srcUid][validFrom]",\
            "propertyIndex":"[labelUid][propertyKeyUid][tierTag][tieredValue][nodeUid]",\
            "propertyIndexDirectMaxLength":32,"propertyValueHashLength":"LONG"}""";

    /**
     * The key layout this build writes for {@code precision}.
     *
     * <p>The time serde's name is part of the stamp, which is what makes Temporal Precision immutable after
     * provisioning: a store written at one precision produces a different key schema string and so is refused by
     * {@link GraphSchemaDb} when opened under another. No separate immutability check is needed, and no schema
     * version bump either - {@code MILLISECOND} still yields the exact string earlier builds wrote, so existing
     * millisecond stores keep opening.</p>
     *
     * <p>The name carries the width as well as the unit because minute and second are both four bytes but encode
     * differently - and from different epochs - so width alone would let two incompatible layouts share a stamp.</p>
     */
    private static String keySchema(final TemporalPrecision precision, final TimeSerde timeSerde) {
        return String.format(
                KEY_SCHEMA_TEMPLATE,
                precision.name().toLowerCase(Locale.ROOT) + timeSerde.getSize());
    }

    /**
     * The value encodings this build writes. See {@link #KEY_SCHEMA} for the version-bump obligation.
     */
    private static final String VALUE_SCHEMA = """
            {"node":"[tag][labelCount][labelUids][propsBlob]",\
            "edge":"[tag][propsBlob]","propsCodec":1}""";

    private static final UnsignedBytes NODE_UID_BYTES =
            UnsignedBytesInstances.ofLength(NODE_UID_WIDTH);
    private static final UnsignedBytes TYPE_UID_BYTES =
            UnsignedBytesInstances.ofLength(TYPE_UID_WIDTH);

    private static final int MAX_DBS = 32;

    private final PlanBEnv env;
    private final UidLookupDb nodeUids;
    private final UidLookupDb labelUids;
    private final UidLookupDb edgeTypeUids;
    private final UidLookupDb propertyKeyUids;
    private final UidLookupRecorder nodeUidRecorder;
    private final UidLookupRecorder labelUidRecorder;
    private final UidLookupRecorder edgeTypeUidRecorder;
    private final UidLookupRecorder propertyKeyUidRecorder;
    private final VariableUsedLookupsRecorder propertyValueRecorder;
    private final GraphNodeDb nodes;
    private final GraphAdjacencyDb outEdges;
    private final GraphInEdgeDb inEdges;
    private final GraphPropertyIndex propertyIndex;
    private final GraphSchemaDb schemaDb;
    private final GraphDbDoc doc;

    private GraphStores(final PlanBEnv env,
                        final UidLookupDb nodeUids,
                        final UidLookupDb labelUids,
                        final UidLookupDb edgeTypeUids,
                        final UidLookupDb propertyKeyUids,
                        final UidLookupRecorder nodeUidRecorder,
                        final UidLookupRecorder labelUidRecorder,
                        final UidLookupRecorder edgeTypeUidRecorder,
                        final UidLookupRecorder propertyKeyUidRecorder,
                        final VariableUsedLookupsRecorder propertyValueRecorder,
                        final GraphNodeDb nodes,
                        final GraphAdjacencyDb outEdges,
                        final GraphInEdgeDb inEdges,
                        final GraphPropertyIndex propertyIndex,
                        final GraphSchemaDb schemaDb,
                        final GraphDbDoc doc) {
        this.env = env;
        this.nodeUids = nodeUids;
        this.labelUids = labelUids;
        this.edgeTypeUids = edgeTypeUids;
        this.propertyKeyUids = propertyKeyUids;
        this.nodeUidRecorder = nodeUidRecorder;
        this.labelUidRecorder = labelUidRecorder;
        this.edgeTypeUidRecorder = edgeTypeUidRecorder;
        this.propertyKeyUidRecorder = propertyKeyUidRecorder;
        this.propertyValueRecorder = propertyValueRecorder;
        this.nodes = nodes;
        this.outEdges = outEdges;
        this.inEdges = inEdges;
        this.propertyIndex = propertyIndex;
        this.schemaDb = schemaDb;
        this.doc = doc;
    }

    /**
     * Writes a consistent copy of this graph's whole environment into {@code destination}.
     *
     * <p>Safe to call while the graph is being written: LMDB copies under a read transaction, so the result is
     * the graph as of the moment the copy began rather than a torn file. The copy is compacting, so it contains
     * only live pages.</p>
     *
     * <p>The output is a complete, self-contained graph store - the same shape as an ingest fragment - which is
     * what lets a copy be shipped through the ordinary fragment transport and merged by a node that is missing
     * it. Merge is idempotent, so a node that already holds the data is unaffected by receiving it again.</p>
     *
     * <p><b>Preconditions:</b> {@code destination} is not null and is an existing, empty directory.
     * <b>Postconditions:</b> {@code destination} holds a complete copy of this store.
     * <b>Null status:</b> {@code destination} is not nullable.
     *
     * @param destination the directory to copy into.
     */
    public void copyTo(final Path destination) {
        Objects.requireNonNull(destination, "destination must not be null");
        env.copy(destination.toFile(), CopyFlags.MDB_CP_COMPACT);
    }

    /**
     * Collapses runs of consecutive identical versions across the node and both edge stores.
     *
     * <p>This is the piece retention cannot do. Loading a graph on a schedule re-asserts every node and edge
     * whether or not anything changed, so a week of unchanged data costs seven versions of everything; each is a
     * legitimate version inside the retention window, so nothing ages it out. Condensing is what bounds a graph
     * fed continuously with mostly-static data.</p>
     *
     * <p><b>It changes no query result</b>, at any instant, which is why it needs no cut-off and no opt-in. A
     * point-in-time lookup is a reverse floor scan, so for any instant inside a collapsed run the surviving
     * earliest version is the one the scan reaches - and it holds the same value as those removed. Keeping the
     * earliest rather than the latest is what makes that hold.</p>
     *
     * <p>The property index is untouched: an anchor is keyed on a value rather than a version, so collapsing
     * versions that share a value cannot orphan one.</p>
     *
     * <p><b>Postconditions:</b> every run of consecutive identical versions in the three version stores has been
     * reduced to its earliest member.
     *
     * @return the number of versions removed.
     */
    public long condense() {
        return env.write(writer -> {
            long count = 0;
            count += nodes.condense(writer);
            count += outEdges.condense(writer);
            count += inEdges.condense(writer);
            writer.commit();
            return count;
        });
    }

    /**
     * Rebuilds the property index from the node versions currently in the store.
     *
     * <p>Anchors are re-recorded against the property-key lookup as they are written, so the caller's
     * {@code deleteUnused} sweep afterwards removes only the keys no surviving anchor needs. Getting that wrong is
     * not a space leak but a correctness bug - an anchor whose key id has been swept references nothing, and the
     * query that would have used it silently returns no rows.</p>
     *
     * <p>The property-<b>value</b> lookup is swept too. A value above the inline-length tier is interned by
     * {@link GraphPropertyIndex#insert}, which is the only place that knows which lookup entry an anchor ended up
     * referencing - so the rebuild passes it a recorder and it reports each one as it writes. Recording there
     * rather than here is what makes this safe: an inline value references no entry at all, and guessing which
     * did would delete entries live anchors depend on.</p>
     *
     * <p><b>Preconditions:</b> {@code writer} is not null and holds the write transaction.
     * <b>Postconditions:</b> the property index contains exactly the anchors implied by the stored node versions,
     * and every property key they use has been recorded as in use.
     * <b>Null status:</b> {@code writer} is not nullable.
     *
     * @param writer the write transaction to rebuild under.
     */
    private void reindexProperties(final LmdbWriter writer) {
        // Collected before writing anything: inserting into other tables while a cursor is still open over the
        // node store on the same transaction does not reliably see the whole iteration - the same
        // collect-then-write rule merge follows.
        final List<Anchor> anchors = new ArrayList<>();
        nodes.forEachVersion(writer.getWriteTxn(), (nodeUid, validFrom, version) -> {
            if (version == null) {
                // A tombstone carries no labels or properties, so it anchors nothing.
                return;
            }
            for (final long labelUid : version.labelUids()) {
                for (final Map.Entry<String, Val> property : version.properties().entrySet()) {
                    anchors.add(new Anchor(labelUid, property.getKey(), property.getValue(), nodeUid));
                }
            }
        });

        propertyIndex.clear(writer);
        for (final Anchor anchor : anchors) {
            // Decoded at the buffer's own width rather than TYPE_UID_WIDTH: the lookup returns the uid it
            // assigned, and reading it at an assumed width yields a different number, so anchors would be written
            // under a key no query resolves to.
            final long propKeyUid = propertyKeyUids.put(
                    writer.getWriteTxn(),
                    nameBuffer(anchor.propertyKey()),
                    buffer -> UnsignedBytesInstances.ofLength(buffer.remaining()).get(buffer.duplicate()));
            propertyKeyUidRecorder.recordUsed(writer, propKeyUid);
            propertyIndex.insert(writer, anchor.labelUid(), propKeyUid,
                    GraphAnchorEncoding.anchorValueBytes(anchor.value()), anchor.nodeUid(),
                    propertyValueRecorder);
        }
    }

    /** One anchor to re-derive, held while the node store is still being iterated. */
    private record Anchor(long labelUid, String propertyKey, Val value, long nodeUid) {

    }

    private static ByteBuffer nameBuffer(final String name) {
        final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
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
     * Provisions a new, empty set of stores sized by configuration.
     *
     * <p><b>Preconditions:</b> {@code directory} and {@code doc} are not null.
     * <b>Postconditions:</b> as {@link #provision(Path, GraphDbDoc)}, with the environment's maximum size set.
     * <b>Null status:</b> {@code maxStoreSize} is nullable, meaning "use the storage layer's default".
     *
     * @param directory    the directory to provision under.
     * @param doc          the owning document.
     * @param maxStoreSize the environment's maximum size in bytes, or null for the default.
     * @return an open, writable {@link GraphStores}.
     */
    public static GraphStores provision(final Path directory,
                                        final GraphDbDoc doc,
                                        final Long maxStoreSize) {
        return open(directory, doc, false, maxStoreSize);
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
        return open(directory, doc, readOnly, null);
    }

    /**
     * Opens the stores for {@code doc}, with an explicit maximum environment size.
     *
     * <p>LMDB fixes an environment's map size when it is created, so this bounds how large the graph can grow and
     * a later change applies only to environments opened afterwards. It is a reservation rather than an
     * allocation, so a generous value costs address space rather than disk.</p>
     *
     * <p><b>Preconditions:</b> {@code directory} and {@code doc} are not null.
     * <b>Postconditions:</b> as {@link #open(Path, GraphDbDoc, boolean)}.
     * <b>Null status:</b> {@code maxStoreSize} is nullable, meaning "use the storage layer's default".
     *
     * @param directory    the directory the stores live under.
     * @param doc          the owning document.
     * @param readOnly     whether to open read-only.
     * @param maxStoreSize the environment's maximum size in bytes, or null for the default.
     * @return an open {@link GraphStores}.
     */
    public static GraphStores open(final Path directory,
                                   final GraphDbDoc doc,
                                   final boolean readOnly,
                                   final Long maxStoreSize) {
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
        final HashClashCommitRunnable hashClashCommitRunnable = new HashClashCommitRunnable();
        final PlanBEnv env = new PlanBEnv(
                directory,
                maxStoreSize,
                MAX_DBS,
                readOnly,
                hashClashCommitRunnable);
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
            final UidLookupRecorder nodeUidRecorder = new UidLookupRecorder(env, nodeUids);
            final UidLookupRecorder labelUidRecorder = new UidLookupRecorder(env, labelUids);
            final UidLookupRecorder edgeTypeUidRecorder = new UidLookupRecorder(env, edgeTypeUids);
            final UidLookupRecorder propertyKeyUidRecorder = new UidLookupRecorder(env, propertyKeyUids);

            // Task P1.3: the property index's own value-tiering lookups (UID_LOOKUP/HASH_LOOKUP tiers), separate
            // from the four node/label/edge-type/property-key interning namespaces above. Fixed-width (like
            // those four) for the same P0.1 reason: a variable-width UID here would silently break
            // GraphPropertyIndex's composite-key prefix scan once the UID counter crosses a width boundary.
            final UidLookupDb propertyValueUids = new UidLookupDb(
                    env, byteBuffers, "property-value-uid",
                    new StaticUnsignedBytesFactory(UnsignedBytesInstances.ofLength(TYPE_UID_WIDTH)));
            final HashFactory propertyValueHashFactory = HashFactoryFactory.create(HashLength.LONG);
            final HashLookupDb propertyValueHashes = new HashLookupDb(
                    env, byteBuffers, propertyValueHashFactory, hashClashCommitRunnable, "property-value-hash");
            final VariableUsedLookupsRecorder propertyValueRecorder = new VariableUsedLookupsRecorder(
                    env, propertyValueUids, propertyValueHashes);

            // Temporal Precision decides the width of every key's validFrom, so it has to be resolved before any
            // table opens rather than consulted per write.
            final TemporalPrecision precision = GraphTimeSerdes.resolve(doc.getTemporalPrecision());
            final TimeSerde timeSerde = GraphTimeSerdes.forPrecision(precision);

            final GraphNodeDb nodes = new GraphNodeDb(env, timeSerde);
            final GraphAdjacencyDb outEdges = new GraphAdjacencyDb(env, timeSerde);
            final GraphInEdgeDb inEdges = new GraphInEdgeDb(env, timeSerde);
            final GraphPropertyIndex propertyIndex = new GraphPropertyIndex(
                    env, propertyValueUids, propertyValueHashes);

            // Stamp/validate the on-disk format last, so a mismatch is raised only once every table this build
            // expects has opened cleanly - a store missing a table is a different, louder failure.
            final GraphSchemaDb schemaDb = new GraphSchemaDb(
                    env,
                    byteBuffers,
                    doc,
                    new SchemaInfo(
                            GraphSchemaDb.CURRENT_SCHEMA_VERSION, keySchema(precision, timeSerde), VALUE_SCHEMA),
                    hashClashCommitRunnable);

            return new GraphStores(
                    env, nodeUids, labelUids, edgeTypeUids, propertyKeyUids,
                    nodeUidRecorder, labelUidRecorder, edgeTypeUidRecorder, propertyKeyUidRecorder,
                    propertyValueRecorder,
                    nodes, outEdges, inEdges, propertyIndex, schemaDb, doc);
        } catch (final RuntimeException e) {
            env.close();
            throw e;
        }
    }

    /**
     * Merges a fragment - a complete, self-contained graph store written elsewhere - into this store.
     *
     * <p>This is what makes Graph DB correct on a cluster: whichever node processes a stream writes a private
     * fragment, and every fragment is merged into one authoritative store. It cannot be a byte-level copy,
     * because <b>every</b> graph key embeds interned UIDs that are allocated per-environment, so the same UID
     * means different things in two stores. Each row is therefore rewritten through a per-namespace translation
     * map built from the fragment's own interning tables.</p>
     *
     * <p>Three properties this method must preserve, each easy to break:</p>
     * <ul>
     *   <li><b>Idempotency.</b> Re-merging a fragment is a no-op. Interning is get-or-create, node/edge inserts
     *   carry no wall-clock stamp, and the property index is keyed without a time component, so replaying a
     *   fragment re-writes byte-identical rows. This is what makes a partially-sent fragment safe to resend, so
     *   do not introduce any aggregation or {@code Instant.now()} into this path.</li>
     *   <li><b>Edge pair atomicity.</b> A logical edge lives in two tables with no cross-table transaction, so
     *   both rows are written before {@link LmdbWriter#tryCommit()} is given the chance to flush. A commit
     *   boundary between them would let a crash leave a half-edge, and traversal would then disagree forwards
     *   versus backwards.</li>
     *   <li><b>Buffer lifetime.</b> Buffers handed out by the source environment are valid only for the call
     *   they arrive in, and writing to the target can move pages. Anything crossing from source to target is
     *   copied first.</li>
     * </ul>
     *
     * <p>The property index is <b>rebuilt</b> from the merged node versions rather than copied. Its keys embed
     * both a node UID and a tiered encoding of the value, and the hash tier's keys carry clash-sequence
     * suffixes that are only meaningful in the store that allocated them - so copying its rows would be wrong,
     * not merely slow. Rebuilding also reuses the same insert path ingest uses, so tiering and clash handling
     * cannot drift between the two.</p>
     *
     * <p><b>Preconditions:</b> {@code sourceDir} holds a graph store written by this build for the same document,
     * and is not open elsewhere. This store is writable.
     * <b>Postconditions:</b> every node version, edge version and derived property-index anchor in the fragment
     * is present in this store. The fragment is left on disk for the caller to delete.
     * <b>Null status:</b> {@code sourceDir} is not nullable.
     *
     * @param sourceDir the fragment to merge.
     * @throws RuntimeException if the fragment's format stamp does not match this store's.
     */
    public void merge(final Path sourceDir) {
        Objects.requireNonNull(sourceDir, "sourceDir must not be null");

        try (GraphStores source = GraphStores.open(sourceDir, doc, true)) {
            // Refuse a fragment written by different code before touching this store at all.
            validateForMerge(source.getSchemaInfo());

            write(writer -> {
                source.read(sourceTxn -> {
                    // Interning first: everything below translates through these.
                    final Map<Long, Long> nodeUidMap =
                            translateNamespace(source.nodeUids, nodeUids, sourceTxn, writer, NODE_UID_BYTES);
                    final Map<Long, Long> labelUidMap =
                            translateNamespace(source.labelUids, labelUids, sourceTxn, writer, TYPE_UID_BYTES);
                    final Map<Long, Long> edgeTypeUidMap =
                            translateNamespace(source.edgeTypeUids, edgeTypeUids, sourceTxn, writer, TYPE_UID_BYTES);
                    final Map<Long, Long> propertyKeyUidMap = translateNamespace(
                            source.propertyKeyUids, propertyKeyUids, sourceTxn, writer, TYPE_UID_BYTES);

                    mergeNodes(source, sourceTxn, writer, nodeUidMap, labelUidMap, propertyKeyUidMap);
                    mergeEdges(source, sourceTxn, writer, nodeUidMap, edgeTypeUidMap);
                    return null;
                });
                return null;
            });
        }
    }

    /**
     * Interns every name held by {@code sourceNamespace} into {@code targetNamespace}, returning the resulting
     * source-UID to target-UID mapping.
     *
     * <p>Names are read in the source transaction and copied before being written to the target, because the
     * source buffer is only valid for the duration of the read and the target write may move pages.</p>
     */
    private static Map<Long, Long> translateNamespace(final UidLookupDb sourceNamespace,
                                                      final UidLookupDb targetNamespace,
                                                      final Txn<ByteBuffer> sourceTxn,
                                                      final LmdbWriter writer,
                                                      final UnsignedBytes uidBytes) {
        // Collect the source UIDs first: the buffer the iterator supplies is only valid inside its own callback.
        final List<Long> sourceUids = new ArrayList<>();
        sourceNamespace.forEachUid(sourceTxn, uidBuffer -> sourceUids.add(uidBytes.get(uidBuffer.duplicate())));

        final Map<Long, Long> map = new HashMap<>(sourceUids.size());
        for (final long sourceUid : sourceUids) {
            final ByteBuffer name = copyOf(sourceNamespace.getValue(sourceTxn, sourceUid));
            final long targetUid = targetNamespace.put(
                    writer.getWriteTxn(), name, uidBuffer -> uidBytes.get(uidBuffer.duplicate()));
            map.put(sourceUid, targetUid);
            writer.tryCommit();
        }
        return map;
    }

    private void mergeNodes(final GraphStores source,
                            final Txn<ByteBuffer> sourceTxn,
                            final LmdbWriter writer,
                            final Map<Long, Long> nodeUidMap,
                            final Map<Long, Long> labelUidMap,
                            final Map<Long, Long> propertyKeyUidMap) {
        source.nodes.forEachVersion(sourceTxn, (sourceNodeUid, validFrom, version) -> {
            final long nodeUid = translate(nodeUidMap, sourceNodeUid, "node");
            if (version == null) {
                nodes.delete(writer, nodeUid, validFrom);
            } else {
                final List<Long> targetLabelUids = new ArrayList<>(version.labelUids().size());
                for (final long sourceLabelUid : version.labelUids()) {
                    targetLabelUids.add(translate(labelUidMap, sourceLabelUid, "label"));
                }
                nodes.insert(writer, nodeUid, validFrom, targetLabelUids, version.properties());

                // Rebuild this version's anchors rather than copying index rows - see this class's merge Javadoc.
                for (final long labelUid : targetLabelUids) {
                    for (final Map.Entry<String, Val> property : version.properties().entrySet()) {
                        final long propertyKeyUid = propertyKeyUids.put(
                                writer.getWriteTxn(),
                                directCopyOfUtf8(property.getKey()),
                                uidBuffer -> TYPE_UID_BYTES.get(uidBuffer.duplicate()));
                        propertyIndex.insert(
                                writer,
                                labelUid,
                                propertyKeyUid,
                                GraphAnchorEncoding.anchorValueBytes(property.getValue()),
                                nodeUid);
                    }
                }
            }
            // One commit boundary per node version, after its anchors, so a version and its anchors travel
            // together.
            writer.tryCommit();
        });
        // propertyKeyUidMap is translated for completeness and to intern the fragment's keys eagerly; anchors
        // above re-intern by name so that a key absent from the fragment's own namespace cannot be missed.
        Objects.requireNonNull(propertyKeyUidMap, "propertyKeyUidMap");
    }

    private void mergeEdges(final GraphStores source,
                            final Txn<ByteBuffer> sourceTxn,
                            final LmdbWriter writer,
                            final Map<Long, Long> nodeUidMap,
                            final Map<Long, Long> edgeTypeUidMap) {
        // The fragment's two adjacency tables are mirrors of each other, written together per record by the
        // ingest filter, so the in-edge row is derived here rather than iterated separately. That keeps a
        // logical edge's two rows inside one commit batch.
        final long outCount = source.outEdges.count(sourceTxn);
        final long inCount = source.inEdges.count(sourceTxn);
        if (outCount != inCount) {
            throw new IllegalStateException(LogUtil.message(
                    "Fragment adjacency tables disagree for graph '{}': out-edges={}, in-edges={}. The fragment " +
                    "is corrupt and cannot be merged.",
                    doc.getName(),
                    outCount,
                    inCount));
        }

        source.outEdges.forEachEdge(sourceTxn, (sourceSrcUid, sourceTypeUid, sourceDstUid, validFrom, properties) -> {
            final long srcUid = translate(nodeUidMap, sourceSrcUid, "node");
            final long dstUid = translate(nodeUidMap, sourceDstUid, "node");
            final long edgeTypeUid = translate(edgeTypeUidMap, sourceTypeUid, "edge type");
            if (properties == null) {
                outEdges.delete(writer, srcUid, edgeTypeUid, dstUid, validFrom);
                inEdges.delete(writer, srcUid, edgeTypeUid, dstUid, validFrom);
            } else {
                outEdges.insert(writer, srcUid, edgeTypeUid, dstUid, validFrom, properties);
                inEdges.insert(writer, srcUid, edgeTypeUid, dstUid, validFrom, properties);
            }
            // Deliberately after BOTH rows - see the edge-pair atomicity note on merge.
            writer.tryCommit();
        });
    }

    private long translate(final Map<Long, Long> map, final long sourceUid, final String namespace) {
        final Long targetUid = map.get(sourceUid);
        if (targetUid == null) {
            throw new IllegalStateException(LogUtil.message(
                    "Fragment for graph '{}' references {} UID {}, which its own interning table does not hold. " +
                    "The fragment is corrupt and cannot be merged.",
                    doc.getName(),
                    namespace,
                    sourceUid));
        }
        return targetUid;
    }

    private static ByteBuffer copyOf(final ByteBuffer buffer) {
        final ByteBuffer source = buffer.duplicate();
        final ByteBuffer copy = ByteBuffer.allocateDirect(source.remaining());
        copy.put(source);
        copy.flip();
        return copy;
    }

    private static ByteBuffer directCopyOfUtf8(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    /**
     * @return the on-disk format stamp this store is operating under; never null.
     */
    public SchemaInfo getSchemaInfo() {
        return schemaDb.getSchemaInfo();
    }

    /**
     * Validates an incoming fragment's format stamp against this store's before merging it.
     *
     * <p><b>Preconditions:</b> {@code source} is not null.
     * <b>Postconditions:</b> returns normally only when the stamps match.
     * <b>Null status:</b> {@code source} is not nullable.
     *
     * @param source the fragment's stamp.
     * @throws RuntimeException if the stamps differ.
     */
    public void validateForMerge(final SchemaInfo source) {
        schemaDb.validateForMerge(source);
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
     * @return the {@link #getNodeUids()} namespace's used-lookups recorder (Task P1.2).
     */
    public UidLookupRecorder getNodeUidRecorder() {
        return nodeUidRecorder;
    }

    /**
     * @return the {@link #getLabelUids()} namespace's used-lookups recorder (Task P1.2).
     */
    public UidLookupRecorder getLabelUidRecorder() {
        return labelUidRecorder;
    }

    /**
     * @return the {@link #getEdgeTypeUids()} namespace's used-lookups recorder (Task P1.2).
     */
    public UidLookupRecorder getEdgeTypeUidRecorder() {
        return edgeTypeUidRecorder;
    }

    /**
     * @return the {@link #getPropertyKeyUids()} namespace's used-lookups recorder (Task P1.2).
     */
    public UidLookupRecorder getPropertyKeyUidRecorder() {
        return propertyKeyUidRecorder;
    }

    /**
     * @return the property index's own value-tiering lookups' (Task P1.3) used-lookups recorder - dispatches on
     * each stored value segment's leading tag byte to sweep the UID_LOOKUP or HASH_LOOKUP tier as appropriate.
     */
    public VariableUsedLookupsRecorder getPropertyValueRecorder() {
        return propertyValueRecorder;
    }

    /**
     * The latest instant this graph's {@code validFrom} encoding can represent.
     *
     * <p>Callers needing a "latest" sentinel must use this rather than a constant: Temporal Precision decides the
     * encoding, so the ceiling differs per graph, and a value beyond it wraps rather than saturating.</p>
     *
     * <p><b>Postconditions:</b> returns an instant this store can encode.
     * <b>Null status:</b> the return value is never null.
     *
     * @return the latest representable instant.
     */
    public Instant getLatestRepresentableInstant() {
        return GraphTimeSerdes.latestRepresentable(GraphTimeSerdes.resolve(doc.getTemporalPrecision()));
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
     * Opens an {@link LmdbWriter} the caller holds open and commits/closes manually - the counterpart to
     * {@link #write} for a caller that needs one writer spanning many separate calls rather than a single
     * enclosed transaction (Task P2.2: {@code GraphFilter} holds one writer open across an entire SAX stream,
     * mirroring how Plan B's own {@code ShardWriter}/{@code WriterInstance} hold a writer open across a stream -
     * though unlike those, {@code GraphFilter} commits/{@link LmdbWriter#abort() aborts} per record rather than
     * relying on {@link LmdbWriter#tryCommit()}'s batched threshold, and calls {@link LmdbWriter#close()} once at
     * the end as a final safety net).
     *
     * <p><b>Preconditions:</b> none. <b>Postconditions:</b> the caller is responsible for calling
     * {@link LmdbWriter#close()} exactly once (it commits any pending change) - never both this and {@link #write}
     * concurrently, since only one write transaction is permitted at a time (enforced by {@link LmdbWriter}'s own
     * internal lock).</p>
     *
     * @return a new, open {@link LmdbWriter}.
     */
    public LmdbWriter createWriter() {
        return env.createWriter();
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

    /**
     * Retention (Task P1.4): if {@code doc.getRetention()} is enabled, deletes every node/edge version older
     * than the configured retention window from all three temporally-versioned stores ({@link #getNodes()},
     * {@link #getOutEdges()}, {@link #getInEdges()} - each keeping its own floor version per entity, see their
     * {@code deleteOldData} Javadoc), then sweeps the node/label/edge-type interning namespaces of any UID no
     * longer referenced by a surviving version (Task P1.2's recorders - always run after all three DAOs have
     * finished recording, per that task's documented ordering contract). A no-op (returns 0) if retention is
     * absent or disabled - the graph keeps every version forever by default.
     *
     * <p><b>Scope limit (documented, not a silent gap):</b> the property-value anchor index ({@link
     * #getPropertyIndex()}) does not participate in this pass - it has no {@code validFrom} of its own to
     * retain by, and a stale anchor left behind by a deleted node version is filtered out at query time (not a
     * correctness issue - see {@link GraphPropertyIndex}'s Javadoc), so its own bloat and the
     * {@code property-key-uid} namespace's sweep are deferred; {@link #rebuild} remains the operator-invoked
     * compaction backstop for both. Condense (merging redundant same-value consecutive versions, as Plan B's own
     * stores do) is likewise not built for v1 - a graph's properties change far less often than Plan B state
     * typically does, so the benefit did not appear to justify the extra machinery; revisit if evidence from real
     * usage says otherwise.</p>
     *
     * <p><b>Preconditions:</b> {@code doc} is not null. <b>Postconditions:</b> never corrupts a floor lookup for
     * any instant at or after the retention cutoff.</p>
     *
     * @param doc the owning document, read for its retention policy.
     * @return the total number of versions deleted across all three stores.
     */
    public long deleteOldData(final GraphDbDoc doc) {
        Objects.requireNonNull(doc, "doc must not be null");
        final RetentionSettings retention = doc.getRetention();
        if (retention == null || !retention.isEnabled()) {
            return 0;
        }
        final Instant deleteBefore = SimpleDurationUtil.minus(Instant.now(), retention.getDuration());

        return env.write(writer -> {
            long count = 0;
            count += nodes.deleteOldData(writer, deleteBefore, nodeUidRecorder, labelUidRecorder);
            count += outEdges.deleteOldData(writer, deleteBefore, nodeUidRecorder, edgeTypeUidRecorder);
            count += inEdges.deleteOldData(writer, deleteBefore, nodeUidRecorder, edgeTypeUidRecorder);

            // The property index cannot be aged by time - its keys carry no validFrom - and its values cannot be
            // decoded back out of a stored key, so it cannot be pruned row by row either. It is rebuilt from the
            // versions that survived above, which is the only thing that reclaims what this table actually
            // accumulates: an anchor per distinct value each node has ever held. Retention keeps a node's last
            // version at or before the cut-off, so nodes themselves rarely disappear - it is those per-version
            // value anchors that grew without bound, which is why storage grew even with retention enabled.
            //
            // Only when the node sweep removed something: a rebuild costs a full pass over the surviving
            // versions, and the retention job runs every ten minutes.
            final boolean reindexed = count > 0 && !Thread.currentThread().isInterrupted();
            if (reindexed) {
                reindexProperties(writer);
            }

            if (!Thread.currentThread().isInterrupted()) {
                env.read(readTxn -> {
                    nodeUidRecorder.deleteUnused(readTxn, writer);
                    labelUidRecorder.deleteUnused(readTxn, writer);
                    edgeTypeUidRecorder.deleteUnused(readTxn, writer);

                    // Only sweepable when the rebuild ran, because that is what records which property keys the
                    // surviving anchors use. Sweeping without having recorded usage deletes every entry, and the
                    // anchors then reference key ids nothing resolves to - which does not waste space, it makes
                    // every property-anchored query silently miss.
                    if (reindexed) {
                        propertyKeyUidRecorder.deleteUnused(readTxn, writer);
                        propertyValueRecorder.deleteUnused(readTxn, writer);
                    }
                    return null;
                });
            }
            return count;
        });
    }
}
