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

package stroom.graphdb.impl.pipeline;

import stroom.docref.DocRef;
import stroom.graphdb.impl.GraphDbConfig;
import stroom.graphdb.impl.GraphDbDocCache;
import stroom.graphdb.impl.GraphFileTransferClient;
import stroom.graphdb.impl.GraphNodeDb;
import stroom.graphdb.impl.GraphPaths;
import stroom.graphdb.impl.GraphShardWriters;
import stroom.graphdb.impl.GraphStores;
import stroom.graphdb.impl.GraphTraversalEngine;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.meta.shared.Meta;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.FatalErrorReceiver;
import stroom.pipeline.errorhandler.LoggedException;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.util.ProcessorUtil;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.query.api.DateTimeSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.Type;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.util.zip.ZipUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task P2.3's Done-when: {@code MATCH (d:Device {id:'d-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id} - the
 * same worked example used throughout PoC.5/PoC.6/P1's own tests - returns the expected rows when the graph is
 * built entirely by {@link GraphFilter} from real graph-mutation:1 XML via real SAX events (via
 * {@link ProcessorUtil}, the established pipeline-element test harness), not by direct DAO calls in test setup
 * code - closing the gap every prior phase's tests left open. Also covers node/edge delete (tombstones),
 * idempotent reprocessing, and rebuild-from-streams (design doc &sect;5.2's closing claim: the graph is a
 * rebuildable materialized projection).
 */
class TestGraphFilter {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();
    private static final DocRef DOC_REF = DOC.asDocRef();

    private static final String DEVICE_CONNECTED_TO_ACCOUNTS_XML = """
            <graph xmlns="graph-mutation:1" version="1.0">
                <node id="d-42" validFrom="2026-01-01T00:00:00.000Z">
                    <label>Device</label>
                    <property name="id">d-42</property>
                </node>
                <node id="account-a" validFrom="2026-01-01T00:00:00.000Z">
                    <label>Account</label>
                    <property name="id">account-a</property>
                </node>
                <node id="account-b" validFrom="2026-01-01T00:00:00.000Z">
                    <label>Account</label>
                    <property name="id">account-b</property>
                </node>
                <edge type="CONNECTED_TO" validFrom="2026-01-01T00:00:00.000Z">
                    <src>d-42</src>
                    <dst>account-a</dst>
                </edge>
                <edge type="CONNECTED_TO" validFrom="2026-06-01T00:00:00.000Z">
                    <src>d-42</src>
                    <dst>account-b</dst>
                </edge>
            </graph>
            """;

    @Test
    void ingestedGraph_answersTheSameMatchQuery_asDirectlySeededFixtures(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph1"), DOC)) {
            ingest(stores, DEVICE_CONNECTED_TO_ACCOUNTS_XML);

            final List<Val[]> rows = query(stores,
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");
            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b");

            // AS OF a time before account-b's edge existed: only account-a is reachable - proves validFrom was
            // actually carried through from the XML attribute, not defaulted or dropped.
            final List<Val[]> earlyRows = query(stores,
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) "
                    + "AS OF datetime('2026-02-01T00:00:00Z') RETURN a.id");
            assertThat(earlyRows).extracting(row -> row[0].toString()).containsExactly("account-a");
        }
    }

    @Test
    void nodeDelete_tombstonesTheNode(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph2"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                        </node>
                        <node-delete id="n1" validFrom="2026-06-01T00:00:00.000Z"/>
                    </graph>
                    """);

            final long nodeUid = stores.write(writer -> stores.getNodeUids().put(
                    writer.getWriteTxn(), directBuffer("n1"), buf -> readUid(buf)));

            final Optional<GraphNodeDb.NodeVersion> beforeDelete = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, Instant.parse("2026-03-01T00:00:00Z")));
            assertThat(beforeDelete).isPresent();

            final Optional<GraphNodeDb.NodeVersion> afterDelete = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, Instant.parse("2026-07-01T00:00:00Z")));
            assertThat(afterDelete).isEmpty();
        }
    }

    @Test
    void edgeDelete_tombstonesBothDirections(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <edge type="LINKS_TO" validFrom="2026-01-01T00:00:00.000Z">
                            <src>src</src>
                            <dst>dst</dst>
                        </edge>
                        <edge-delete type="LINKS_TO" validFrom="2026-06-01T00:00:00.000Z">
                            <src>src</src>
                            <dst>dst</dst>
                        </edge-delete>
                    </graph>
                    """);

            final long srcUid = stores.write(writer -> stores.getNodeUids().put(
                    writer.getWriteTxn(), directBuffer("src"), buf -> readUid(buf)));
            final long dstUid = stores.write(writer -> stores.getNodeUids().put(
                    writer.getWriteTxn(), directBuffer("dst"), buf -> readUid(buf)));
            final long edgeTypeUid = stores.write(writer -> stores.getEdgeTypeUids().put(
                    writer.getWriteTxn(), directBuffer("LINKS_TO"), buf -> readUid(buf)));

            final List<Long> outAfterDelete = stores.read(readTxn -> {
                final List<Long> out = new ArrayList<>();
                stores.getOutEdges().expandOut(readTxn, srcUid, edgeTypeUid,
                        Instant.parse("2026-07-01T00:00:00Z"), n -> out.add(n.dstUid()));
                return out;
            });
            assertThat(outAfterDelete).isEmpty();

            final List<Long> inAfterDelete = stores.read(readTxn -> {
                final List<Long> in = new ArrayList<>();
                stores.getInEdges().expandIn(readTxn, dstUid, edgeTypeUid,
                        Instant.parse("2026-07-01T00:00:00Z"), n -> in.add(n.srcUid()));
                return in;
            });
            assertThat(inAfterDelete).isEmpty();
        }
    }

    @Test
    void reprocessingTheSameXmlTwice_isIdempotent(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4"), DOC)) {
            ingest(stores, DEVICE_CONNECTED_TO_ACCOUNTS_XML);
            ingest(stores, DEVICE_CONNECTED_TO_ACCOUNTS_XML);

            final List<Val[]> rows = query(stores,
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");
            assertThat(rows).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b");
        }
    }

    @Test
    void anchorNeedsReindexing_falseOnlyWhenLabelCarriedBeforeAndValueUnchanged() {
        // Task P8.1: the extracted decision function directly, since GraphFilter itself is awkward to unit-test
        // at this granularity via the real SAX/ingest harness.
        assertThat(GraphFilter.anchorNeedsReindexing(
                true, ValString.create("active"), ValString.create("active"))).isFalse();
        assertThat(GraphFilter.anchorNeedsReindexing(
                true, ValString.create("active"), ValString.create("inactive"))).isTrue();
        assertThat(GraphFilter.anchorNeedsReindexing(true, null, ValString.create("active"))).isTrue();
        // A label the previous version didn't carry always needs (re-)indexing, even if the same value already
        // happens to be anchored under some other, pre-existing label.
        assertThat(GraphFilter.anchorNeedsReindexing(
                false, ValString.create("active"), ValString.create("active"))).isTrue();
        // Retyping a property from string "42" to long 42 keys the same anchor bytes, so no rewrite is needed.
        // The decision is about the anchor key, not about the value's type.
        assertThat(GraphFilter.anchorNeedsReindexing(
                true, ValString.create("42"), ValLong.create(42L))).isFalse();
    }

    @Test
    void propertyMissingNameAttribute_logsACleanErrorInsteadOfNpeing(@TempDir final Path root) {
        // Code-review fix: previously a <property> with no name attribute silently stored a null map key,
        // which later threw an unhandled NullPointerException deep inside intern/directBuffer rather than
        // the class's own documented "logged and skipped" contract for a malformed record.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph8"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property>no name here</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            assertThat(capturedErrors).anyMatch(message -> message.contains("requires a name attribute"));
        }
    }

    @Test
    void labelMisNestedUnderNodeDelete_logsACleanErrorInsteadOfSilentlyMutatingStaleState(
            @TempDir final Path root) {
        // Code-review fix: currentLabels/currentProperties used to be left over from whatever <node>/<edge> was
        // processed last for every element that doesn't itself use them (e.g. <node-delete>), so a <label>
        // mis-nested under one (invalid per the XSD, but SAX still fires the events if nothing upstream
        // validates it) would previously mutate that stale list with no error at all. Now explicitly nulled out
        // for delete elements, so this is caught and logged instead.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph9"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                        </node>
                    </graph>
                    """);

            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node-delete id="n1" validFrom="2026-06-01T00:00:00.000Z">
                            <label>Widget</label>
                        </node-delete>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            assertThat(capturedErrors).anyMatch(message -> message.contains("<label> is only valid"));
        }
    }

    @Test
    void recordFailingAtTheStoreLayer_isLoggedAndSkippedWithoutAbortingTheStream(@TempDir final Path root) {
        // Code-review fix: a well-formed-XML record can still fail once it reaches the stores - here a node with
        // more labels than the fixed-width encoding allows (GraphNodeDb.insert's >255 guard). Previously that
        // RuntimeException propagated straight out and aborted the whole stream; now it is logged and the record
        // skipped, so the valid node that follows it is still written - mirroring PlanBFilter's catchLmdbError.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph10"), DOC)) {
            final StringBuilder tooManyLabels = new StringBuilder();
            for (int i = 0; i < 256; i++) {
                tooManyLabels.append("            <label>L").append(i).append("</label>\n");
            }
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="bad" validFrom="2026-01-01T00:00:00.000Z">
                    """
                    + tooManyLabels
                    + """
                        </node>
                        <node id="good" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">good</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            // The poison record was logged and skipped...
            assertThat(capturedErrors).anyMatch(message -> message.contains("Failed to write <node>"));
            // ...but the stream continued: the following valid node is written and queryable.
            final List<Val[]> rows = query(stores, "MATCH (g:Thing {id: 'good'}) RETURN g.id");
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("good");
        }
    }

    @Test
    void partialEdgeWrite_isAbortedAtomically_precedingAndFollowingRecordsUnaffected(@TempDir final Path root) {
        // Regression test for finding F4 (; "Batch 1" write-up in
        // ): addEdge's dual out-edge/in-edge insert used to share one
        // long-lived, batch-committed LmdbWriter with every other record - if the second of the two writes
        // threw after the first had already succeeded, the one-sided partial write was only logged, never
        // rolled back, so it rode along staged until the writer's next batch-commit threshold silently
        // persisted it as a one-sided edge. GraphFilter.perRecord now owns a commit-on-success/abort-on-failure
        // boundary per record instead.
        //
        // This drives the same GraphStores/LmdbWriter/DAOs GraphFilter itself uses, replicating perRecord's
        // exact protocol by hand, rather than through XML/SAX ingest: a well-formed graph-mutation:1 document
        // can never supply the one input - a null property Val - needed to fail only the *second* of the two
        // dual writes while the first succeeds. GraphFilter.toVals always wraps XML property text as a
        // non-null ValString, and every real store-layer bound (e.g. GraphNodeDb's label-count guard) is
        // otherwise enforced identically for both the out-edge and in-edge call given the same properties, so
        // it always fails both writes or neither. A null Val reliably fails only the in-edge call (the second
        // write) with a NullPointerException deep inside ValSerdeUtil - the same "later write throws after an
        // earlier one already succeeded" shape as any other store-layer failure.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-f4"), DOC)) {
            final long srcUid = internNode(stores, "src");
            final long dstUid = internNode(stores, "dst");
            final long goodType = internEdgeType(stores, "GOOD");
            final long poisonType = internEdgeType(stores, "POISON");
            final long laterType = internEdgeType(stores, "LATER");
            final Instant validFrom = Instant.parse("2026-01-01T00:00:00.000Z");

            try (LmdbWriter writer = stores.createWriter()) {
                // Preceding record: both writes succeed, then committed - mirrors perRecord's success path.
                stores.getOutEdges().insert(writer, srcUid, goodType, dstUid, validFrom, Map.of());
                stores.getInEdges().insert(writer, srcUid, goodType, dstUid, validFrom, Map.of());
                writer.commit();

                // The failing record: the out-edge insert (first write) succeeds; the in-edge insert (second
                // write, same record) throws. perRecord's catch block aborts instead of committing.
                final Map<String, Val> poisonProperties = new LinkedHashMap<>();
                poisonProperties.put("bad", null);
                stores.getOutEdges().insert(writer, srcUid, poisonType, dstUid, validFrom, Map.of());
                assertThatThrownBy(() -> stores.getInEdges()
                        .insert(writer, srcUid, poisonType, dstUid, validFrom, poisonProperties))
                        .isInstanceOf(RuntimeException.class);
                writer.abort();

                // Following record: a fresh write on the same writer, after the abort, must still succeed.
                stores.getOutEdges().insert(writer, srcUid, laterType, dstUid, validFrom, Map.of());
                stores.getInEdges().insert(writer, srcUid, laterType, dstUid, validFrom, Map.of());
                writer.commit();
            }

            assertThat(outNeighbours(stores, srcUid, goodType, validFrom))
                    .as("preceding record's out-edge").containsExactly(dstUid);
            assertThat(inNeighbours(stores, dstUid, goodType, validFrom))
                    .as("preceding record's in-edge").containsExactly(srcUid);

            assertThat(outNeighbours(stores, srcUid, poisonType, validFrom))
                    .as("failing record's out-edge must be rolled back, not left one-sided").isEmpty();
            assertThat(inNeighbours(stores, dstUid, poisonType, validFrom))
                    .as("failing record's in-edge was never written").isEmpty();

            assertThat(outNeighbours(stores, srcUid, laterType, validFrom))
                    .as("following record's out-edge").containsExactly(dstUid);
            assertThat(inNeighbours(stores, dstUid, laterType, validFrom))
                    .as("following record's in-edge").containsExactly(srcUid);
        }
    }

    @Test
    void nodeUpdate_unchangedPropertyAnchorStillResolves(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph6"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n1</property>
                            <property name="status">active</property>
                        </node>
                    </graph>
                    """);
            // Second version: only "status" changes - "id" is carried through unchanged, so Task P8.1's skip
            // applies to it (its P8.1 does NOT re-insert the anchor); this proves that skip didn't break
            // resolution - the surviving prior-version anchor still finds the node.
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-06-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n1</property>
                            <property name="status">inactive</property>
                        </node>
                    </graph>
                    """);

            final List<Val[]> byUnchangedId = query(stores, "MATCH (n:Thing {id: 'n1'}) RETURN n.status");
            assertThat(byUnchangedId).extracting(row -> row[0].toString()).containsExactly("inactive");
        }
    }

    @Test
    void nodeUpdate_newlyAddedLabelIsIndexedEvenWhenTheValueIsAlreadyAnchoredUnderAnotherLabel(
            @TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph7"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="code">shared-code</property>
                        </node>
                    </graph>
                    """);
            // Second version adds label "Widget" - "code"'s value is unchanged, but Widget was never carried
            // before, so Task P8.1 must still index it under Widget (the skip only ever applies to a label
            // already carried by the previous version).
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-06-01T00:00:00.000Z">
                            <label>Thing</label>
                            <label>Widget</label>
                            <property name="code">shared-code</property>
                        </node>
                    </graph>
                    """);

            final List<Val[]> byNewLabel = query(stores, "MATCH (n:Widget {code: 'shared-code'}) RETURN n.code");
            assertThat(byNewLabel).extracting(row -> row[0].toString()).containsExactly("shared-code");
        }
    }

    @Test
    void rebuildFromStreams_reproducesTheSameQueryableState(@TempDir final Path root) {
        final Path dir = root.resolve("graph5");
        final GraphStores original = GraphStores.provision(dir, DOC);
        final AtomicReference<GraphStores> currentStores = new AtomicReference<>(original);
        ingest(currentStores.get(), DEVICE_CONNECTED_TO_ACCOUNTS_XML, currentStores);

        final List<Val[]> beforeRebuild = query(currentStores.get(),
                "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");
        assertThat(beforeRebuild).extracting(row -> row[0].toString())
                .containsExactlyInAnyOrder("account-a", "account-b");

        // rebuild closes the original and re-provisions empty; re-ingesting the same XML must reproduce the
        // same queryable state - proving the graph is genuinely a rebuildable materialized projection (design
        // doc &sect;5.2), not silently dependent on incremental state a rebuild would lose.
        currentStores.set(currentStores.get().rebuild(dir, DOC));
        try {
            ingest(currentStores.get(), DEVICE_CONNECTED_TO_ACCOUNTS_XML, currentStores);

            final List<Val[]> afterRebuild = query(currentStores.get(),
                    "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id");
            assertThat(afterRebuild).extracting(row -> row[0].toString())
                    .containsExactlyInAnyOrder("account-a", "account-b");
        } finally {
            currentStores.get().close();
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------------------------------------------

    /**
     * A graph renamed after the pipeline was configured must still resolve. This is the silent-breakage case: a
     * pipeline property is long-lived configuration, so resolving by name meant a rename stopped ingest with no
     * warning until the next stream ran - and by then the reference looked simply wrong rather than stale.
     */
    @Test
    void renamingTheGraph_doesNotBreakThePipeline(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("renamed"), DOC)) {
            // The cache answers by UUID but no longer recognises the old name at all, exactly as it would after a
            // rename. Resolution must still succeed.
            final GraphDbDocCache renamedCache = new GraphDbDocCache() {
                @Override
                public GraphDbDoc get(final String name) {
                    throw new NoSuchElementException("No graph db doc can be found for name: " + name);
                }

                @Override
                public GraphDbDoc getByUuid(final String uuid) {
                    return DOC_REF.getUuid().equals(uuid)
                            ? DOC
                            : null;
                }

                @Override
                public void remove(final String name) {
                    // Not needed by this test.
                }
            };

            ingestWithCache(stores, renamedCache, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n1</property>
                        </node>
                    </graph>
                    """);

            assertThat(query(stores, "MATCH (g:Thing {id: 'n1'}) RETURN g.id"))
                    .extracting(row -> row[0].toString()).containsExactly("n1");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Typed property values.
    // ------------------------------------------------------------------------------------------------------

    /**
     * A declared type is preserved through storage and query, so a long comes back as a long rather than as text.
     * This - not ordering - is what typing actually buys.
     *
     * <p>Ordering was already mostly right without it: Stroom's string comparator for {@code Type.STRING} is
     * {@code AS_DOUBLE_THEN_..._STRING}, so numeric-looking strings already sorted numerically. The documentation
     * previously claimed {@code "10" < "9"} for ordering, which is not what the comparator does. What was genuinely
     * wrong is that every value's <i>type</i> was {@code STRING} regardless of what it represented, so a consumer
     * reading a value back - JSON output, a downstream function, a type-aware short circuit - saw text.</p>
     */
    @Test
    void declaredTypes_arePreservedThroughStorageAndQuery(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed1"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="a" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="qty" type="long">10</property>
                            <property name="active" type="boolean">true</property>
                            <property name="untyped">10</property>
                        </node>
                    </graph>
                    """);

            final List<Val[]> rows = query(stores, "MATCH (n:Item) RETURN n.qty, n.active, n.untyped");
            assertThat(rows).hasSize(1);
            final Val[] row = rows.getFirst();
            assertThat(row[0].type()).isEqualTo(Type.LONG);
            assertThat(row[1].type()).isEqualTo(Type.BOOLEAN);
            assertThat(row[2].type()).isEqualTo(Type.STRING);
        }
    }

    /**
     * A typed long orders numerically. Worth pinning even though a numeric-looking string would too, because the
     * typed path goes through a different comparator and must not regress.
     */
    @Test
    void typedLongProperty_ordersNumerically(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed1b"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="a" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="id">a</property>
                            <property name="qty" type="long">10</property>
                        </node>
                        <node id="b" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="id">b</property>
                            <property name="qty" type="long">9</property>
                        </node>
                    </graph>
                    """);

            assertThat(query(stores, "MATCH (n:Item) RETURN n.id ORDER BY n.qty"))
                    .extracting(r -> r[0].toString())
                    .containsExactly("b", "a");
        }
    }

    /**
     * A typed value must still be findable through the property index. This is the case the design is most exposed
     * on: the anchor is keyed on a value's rendered text and the query seeks the literal's text, so if the two
     * disagree the node is silently not found rather than an error being raised.
     *
     * <p>This also covers the harder half by construction. Every test in this class ingests into a fragment and
     * <b>merges</b> it before asserting, and merge only ever sees decoded values - so a pass here means ingest and
     * merge derived byte-identical anchors from a typed value. Had ingest kept anchoring on the raw XML text, a
     * merged graph would answer these lookups differently from a directly-ingested one and this test would fail.</p>
     */
    @Test
    void typedProperties_areStillFoundByPropertyAnchor(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed2"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="qty" type="long">42</property>
                            <property name="active" type="boolean">true</property>
                            <property name="name">widget</property>
                        </node>
                    </graph>
                    """);

            assertThat(query(stores, "MATCH (n:Item {qty: 42}) RETURN n.name"))
                    .extracting(row -> row[0].toString()).containsExactly("widget");
            assertThat(query(stores, "MATCH (n:Item {active: true}) RETURN n.name"))
                    .extracting(row -> row[0].toString()).containsExactly("widget");
            assertThat(query(stores, "MATCH (n:Item {name: 'widget'}) RETURN n.name"))
                    .extracting(row -> row[0].toString()).containsExactly("widget");
        }
    }

    /**
     * A leading-zero long is canonicalised, and the anchor follows the canonical form. Worth pinning because the
     * anchor is derived from the decoded value rather than the raw text - which is what lets merge reproduce it -
     * so the raw text is deliberately not what a query has to match.
     */
    @Test
    void typedLong_isCanonicalised_andAnchoredOnTheCanonicalForm(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed3"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="qty" type="long">007</property>
                        </node>
                    </graph>
                    """);

            assertThat(query(stores, "MATCH (n:Item {qty: 7}) RETURN n.qty"))
                    .extracting(row -> row[0].toString()).containsExactly("7");
        }
    }

    /**
     * A value that does not parse as its declared type is a bad record. Falling back to a string would put the
     * lexical-ordering surprise back, but silently and only for the rows that failed - the worst of both.
     */
    @Test
    void valueThatDoesNotParseAsItsDeclaredType_isReported(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed4"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="qty" type="long">not a number</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            assertThat(capturedErrors).anyMatch(message -> message.contains("is not a whole number"));
        }
    }

    /**
     * An unknown type is reported rather than quietly treated as a string, so a typo in a translation surfaces.
     */
    @Test
    void anUnknownPropertyType_isReported(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed5"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="qty" type="integer">42</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            assertThat(capturedErrors).anyMatch(message -> message.contains("unknown type"));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Strict mode. The tests above pin the lenient default - one bad record is logged and skipped - so these
    // pin the opposite contract: the stream fails instead of losing data quietly.
    // ------------------------------------------------------------------------------------------------------

    /**
     * A malformed record fails the stream in strict mode. This is the whole point of the setting: lenient mode
     * would have logged this and carried on, leaving a graph missing one property with nothing to show for it.
     */
    @Test
    void strict_malformedRecord_failsTheStream(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("strict1"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();

            assertThatThrownBy(() -> ingestStrict(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <property>no name here</property>
                        </node>
                    </graph>
                    """, capturedErrors))
                    .isInstanceOf(LoggedException.class);

            assertThat(capturedErrors).anyMatch(message -> message.contains("requires a name attribute"));
        }
    }

    /**
     * A record that only fails once it reaches the store layer must fail the stream too. These failures do not go
     * through the validation path, so they are the case a strict flag is easiest to leave half-wired.
     */
    @Test
    void strict_recordFailingAtTheStoreLayer_failsTheStream(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("strict2"), DOC)) {
            final StringBuilder tooManyLabels = new StringBuilder();
            for (int i = 0; i < 256; i++) {
                tooManyLabels.append("            <label>L").append(i).append("</label>\n");
            }
            final List<String> capturedErrors = new ArrayList<>();

            assertThatThrownBy(() -> ingestStrict(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="bad" validFrom="2026-01-01T00:00:00.000Z">
                    """
                    + tooManyLabels
                    + """
                        </node>
                    </graph>
                    """, capturedErrors))
                    .isInstanceOf(LoggedException.class);

            assertThat(capturedErrors).anyMatch(message -> message.contains("Failed to write <node>"));
        }
    }

    /**
     * An element the vocabulary does not define is reported rather than ignored. Previously a misspelled element
     * contributed nothing and said nothing, which is the quietest possible way to lose data.
     */
    @Test
    void unrecognisedElement_isReported_andFailsTheStreamInStrictMode(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("strict3"), DOC)) {
            final String xml = """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <nodee id="n1" validFrom="2026-01-01T00:00:00.000Z" />
                    </graph>
                    """;

            // Lenient: reported, and the stream continues.
            final List<String> lenientErrors = new ArrayList<>();
            ingest(stores, xml, new AtomicReference<>(stores), lenientErrors);
            assertThat(lenientErrors).anyMatch(message -> message.contains("<nodee> is not a graph-mutation element"));

            // Strict: the same detection, but the stream fails.
            final List<String> strictErrors = new ArrayList<>();
            assertThatThrownBy(() -> ingestStrict(stores, xml, strictErrors))
                    .isInstanceOf(LoggedException.class);
            assertThat(strictErrors).anyMatch(message -> message.contains("is not a graph-mutation element"));
        }
    }

    /**
     * Every element the vocabulary does define must pass, in strict mode, without a word. Worth pinning because
     * the unrecognised-element check is a denylist inversion: get the known set wrong and strict mode rejects
     * valid input, which is far worse than the silence it replaced.
     */
    @Test
    void strict_aFullyValidDocument_ingestsWithNoErrors(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("strict4"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();

            ingestStrict(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n1</property>
                        </node>
                        <node id="n2" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n2</property>
                        </node>
                        <edge type="KNOWS" validFrom="2026-01-01T00:00:00.000Z">
                            <src>n1</src>
                            <dst>n2</dst>
                            <property name="since">2020</property>
                        </edge>
                        <edge-delete type="KNOWS" validFrom="2026-02-01T00:00:00.000Z">
                            <src>n1</src>
                            <dst>n2</dst>
                        </edge-delete>
                        <node-delete id="n2" validFrom="2026-03-01T00:00:00.000Z" />
                    </graph>
                    """, capturedErrors);

            assertThat(capturedErrors).isEmpty();
            final List<Val[]> rows = query(stores, "MATCH (g:Thing {id: 'n1'}) RETURN g.id");
            assertThat(rows).extracting(row -> row[0].toString()).containsExactly("n1");
        }
    }

    private static void ingestStrict(final GraphStores stores,
                                     final String xml,
                                     final List<String> capturedErrors) {
        ingest(stores, xml, new AtomicReference<>(stores), capturedErrors, true);
    }

    private static void ingest(final GraphStores stores, final String xml) {
        ingest(stores, xml, new AtomicReference<>(stores));
    }

    /**
     * Drives a real {@link GraphFilter} instance with real SAX events over {@code xml}, via
     * {@link ProcessorUtil} - the established pipeline-element test-driving harness (mirrors
     * {@code TestPlanBFilter}'s own use of it). {@code currentStores} lets the rebuild test point the fake
     * {@link GraphStoreManager} at a freshly-rebuilt {@link GraphStores} for a second ingest run.
     */
    private static void ingest(final GraphStores stores, final String xml,
                               final AtomicReference<GraphStores> currentStores) {
        ingest(stores, xml, currentStores, new ArrayList<>());
    }

    /**
     * Code-review fix: {@code capturedErrors} collects every message {@link GraphFilter}'s own {@code error}
     * logs (a malformed/mis-nested record) - a plain {@code new ErrorReceiverProxy} (as this harness used
     * unconditionally before) has no delegate {@link ErrorReceiver} set, so any {@code error} call would throw
     * a {@link NullPointerException} from inside {@code ErrorReceiverProxy.log} itself, masking whatever the test
     * actually meant to assert.
     */
    private static void ingest(final GraphStores stores, final String xml,
                               final AtomicReference<GraphStores> currentStores,
                               final List<String> capturedErrors) {
        ingest(stores, xml, currentStores, capturedErrors, false);
    }

    /** Drives ingest with a caller-supplied doc cache, so a test can control how the graph resolves. */
    private static void ingestWithCache(final GraphStores stores,
                                        final GraphDbDocCache graphDbDocCache,
                                        final String xml) {
        ingest(stores, xml, new AtomicReference<>(stores), new ArrayList<>(), false, graphDbDocCache);
    }

    private static void ingest(final GraphStores stores, final String xml,
                               final AtomicReference<GraphStores> currentStores,
                               final List<String> capturedErrors,
                               final boolean strict) {
        ingest(stores, xml, currentStores, capturedErrors, strict, new GraphDbDocCache() {
            @Override
            public GraphDbDoc get(final String name) {
                return DOC;
            }

            @Override
            public GraphDbDoc getByUuid(final String uuid) {
                return DOC;
            }

            @Override
            public void remove(final String name) {
                // Not needed by this test harness.
            }
        });
    }

    private static void ingest(final GraphStores stores, final String xml,
                               final AtomicReference<GraphStores> currentStores,
                               final List<String> capturedErrors,
                               final boolean strict,
                               final GraphDbDocCache graphDbDocCache) {
        // The filter writes a self-contained fragment rather than into the graph's own store, so the harness
        // has to complete the real path - capture the shipped fragment, then merge it - before asserting. That
        // makes every test below an end-to-end check of ingest, fragment and merge together, which is the whole
        // of what a node contributes to a clustered graph.
        final List<Path> shippedFragments = new ArrayList<>();
        final Path fragmentRoot;
        try {
            fragmentRoot = Files.createTempDirectory("graph-filter-fragments");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final GraphFileTransferClient fileTransferClient = (fileDescriptor, path, synchroniseMerge) -> {
            try {
                // The writer deletes its zip as soon as storePart returns, so keep a copy to merge from.
                final Path kept = fragmentRoot.resolve("shipped-" + shippedFragments.size() + ".zip");
                Files.copy(path, kept);
                shippedFragments.add(kept);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        final GraphShardWriters graphShardWriters =
                new GraphShardWriters(
                        new GraphPaths(fragmentRoot.resolve("paths")), fileTransferClient,
                        GraphDbConfig::new);
        final MetaHolder metaHolder = new MetaHolder();
        metaHolder.setMeta(Meta.builder().id(1L).build());

        final GraphFilter graphFilter = new GraphFilter(
                new ErrorReceiverProxy((severity, location, elementId, message, errorType, e) ->
                        capturedErrors.add(message)),
                new LocationFactoryProxy(),
                graphDbDocCache,
                graphShardWriters,
                metaHolder);
        graphFilter.setGraphDb(DOC_REF);
        graphFilter.setStrict(strict);

        final ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        ProcessorUtil.processXml(input, new ErrorReceiverProxy(new FatalErrorReceiver()), graphFilter,
                new LocationFactoryProxy());

        mergeShippedFragments(shippedFragments, fragmentRoot, currentStores.get());
    }

    /**
     * Unzips each shipped fragment and merges the graph directory it contains into {@code target}, which is what
     * {@code GraphMergeProcessor} does on a running node.
     */
    private static void mergeShippedFragments(final List<Path> shippedFragments,
                                              final Path fragmentRoot,
                                              final GraphStores target) {
        try {
            int index = 0;
            for (final Path zip : shippedFragments) {
                final Path unzipped = fragmentRoot.resolve("unzipped-" + index++);
                ZipUtil.unzip(zip, unzipped);
                try (final Stream<Path> stream = Files.list(unzipped)) {
                    for (final Path fragment : stream.toList()) {
                        target.merge(fragment);
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Val[]> query(final GraphStores stores, final String cypher) {
        final CompiledCypherPlan compiled = new CypherToLogicalPlan().compile(CypherQueryParser.parse(cypher));
        final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
        return stores.read(readTxn -> engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                DateTimeSettings.builder().build()));
    }

    private static long internNode(final GraphStores stores, final String id) {
        return stores.write(writer -> stores.getNodeUids().put(
                writer.getWriteTxn(), directBuffer(id), buf -> readUid(buf)));
    }

    private static long internEdgeType(final GraphStores stores, final String type) {
        return stores.write(writer -> stores.getEdgeTypeUids().put(
                writer.getWriteTxn(), directBuffer(type), buf -> readUid(buf)));
    }

    private static List<Long> outNeighbours(final GraphStores stores, final long srcUid, final long edgeTypeUid,
                                            final Instant asOf) {
        return stores.read(readTxn -> {
            final List<Long> out = new ArrayList<>();
            stores.getOutEdges().expandOut(readTxn, srcUid, edgeTypeUid, asOf, n -> out.add(n.dstUid()));
            return out;
        });
    }

    private static List<Long> inNeighbours(final GraphStores stores, final long dstUid, final long edgeTypeUid,
                                           final Instant asOf) {
        return stores.read(readTxn -> {
            final List<Long> in = new ArrayList<>();
            stores.getInEdges().expandIn(readTxn, dstUid, edgeTypeUid, asOf, n -> in.add(n.srcUid()));
            return in;
        });
    }

    private static long readUid(final ByteBuffer uidBuffer) {
        return UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate());
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
