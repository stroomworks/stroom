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
import stroom.graphdb.impl.GraphDbDocCache;
import stroom.graphdb.impl.GraphNodeDb;
import stroom.graphdb.impl.GraphStoreManager;
import stroom.graphdb.impl.GraphStores;
import stroom.graphdb.impl.GraphTraversalEngine;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.errorhandler.FatalErrorReceiver;
import stroom.pipeline.util.ProcessorUtil;
import stroom.query.api.DateTimeSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(GraphFilter.anchorNeedsReindexing(true, ValString.create("active"), "active")).isFalse();
        assertThat(GraphFilter.anchorNeedsReindexing(true, ValString.create("active"), "inactive")).isTrue();
        assertThat(GraphFilter.anchorNeedsReindexing(true, null, "active")).isTrue();
        // A label the previous version didn't carry always needs (re-)indexing, even if the same value already
        // happens to be anchored under some other, pre-existing label.
        assertThat(GraphFilter.anchorNeedsReindexing(false, ValString.create("active"), "active")).isTrue();
    }

    @Test
    void propertyMissingNameAttribute_logsACleanErrorInsteadOfNpeing(@TempDir final Path root) {
        // Code-review fix: previously a <property> with no name attribute silently stored a null map key,
        // which later threw an unhandled NullPointerException deep inside intern()/directBuffer() rather than
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

        // rebuild() closes the original and re-provisions empty; re-ingesting the same XML must reproduce the
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
     * Code-review fix: {@code capturedErrors} collects every message {@link GraphFilter}'s own {@code error()}
     * logs (a malformed/mis-nested record) - a plain {@code new ErrorReceiverProxy()} (as this harness used
     * unconditionally before) has no delegate {@link ErrorReceiver} set, so any {@code error()} call would throw
     * a {@link NullPointerException} from inside {@code ErrorReceiverProxy.log} itself, masking whatever the test
     * actually meant to assert.
     */
    private static void ingest(final GraphStores stores, final String xml,
                               final AtomicReference<GraphStores> currentStores,
                               final List<String> capturedErrors) {
        final GraphDbDocCache graphDbDocCache = new GraphDbDocCache() {
            @Override
            public GraphDbDoc get(final String name) {
                return DOC;
            }

            @Override
            public void remove(final String name) {
                // Not needed by this test harness.
            }
        };
        final GraphStoreManager graphStoreManager = new GraphStoreManager() {
            @Override
            public GraphStores getOrOpen(final GraphDbDoc doc) {
                return currentStores.get();
            }

            @Override
            public void delete(final String uuid) {
                // Not needed by this test harness.
            }
        };

        final GraphFilter graphFilter = new GraphFilter(
                new ErrorReceiverProxy((severity, location, elementId, message, errorType, e) ->
                        capturedErrors.add(message)),
                new LocationFactoryProxy(),
                graphDbDocCache,
                graphStoreManager);
        graphFilter.setGraphDb(DOC_REF);

        final ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        ProcessorUtil.processXml(input, new ErrorReceiverProxy(new FatalErrorReceiver()), graphFilter,
                new LocationFactoryProxy());
    }

    private static List<Val[]> query(final GraphStores stores, final String cypher) {
        final CompiledCypherPlan compiled = new CypherToLogicalPlan().compile(CypherQueryParser.parse(cypher));
        final GraphTraversalEngine engine = new GraphTraversalEngine(stores, new ExpressionPredicateFactory());
        return stores.read(readTxn -> engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                DateTimeSettings.builder().build()));
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
