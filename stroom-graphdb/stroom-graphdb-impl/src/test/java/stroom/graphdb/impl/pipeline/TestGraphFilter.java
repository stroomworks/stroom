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
import stroom.query.language.functions.ValDouble;
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
        // Retyping a property from string "42" to long 42 DOES need a rewrite: numbers are keyed by value under
        // a distinct tag, so the two spell the same but key differently. The decision is about the anchor key,
        // and asking the encoder is the only way to be right about it.
        assertThat(GraphFilter.anchorNeedsReindexing(
                true, ValString.create("42"), ValLong.create(42L))).isTrue();
        // The converse, and the case that makes this more than a type comparison: 42 and 42.0 are different
        // types that render differently and key identically, so retyping between them needs no rewrite.
        assertThat(GraphFilter.anchorNeedsReindexing(
                true, ValLong.create(42L), ValDouble.create(42.0))).isFalse();
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

    /**
     * Lenient mode must say how much it lost. A per-record error among thousands of log lines is easy to miss,
     * so a stream that lost anything gets one line stating the graph is incomplete - which is what turns silent
     * partial loss into something an operator would notice.
     *
     * <p>The count comes from {@code error}, not from {@code perRecord}'s catch, because a handler's own
     * validation reports and returns normally without ever reaching that catch - and those are the common
     * failures.
     */
    @Test
    void lenientMode_reportsHowMuchItLost(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("skipcount"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node validFrom="2026-01-01T00:00:00.000Z"><label>Thing</label></node>
                        <node id="n2" validFrom="not-a-timestamp"><label>Thing</label></node>
                        <node id="n3" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n3</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            // Three errors for two bad records: the unparsable timestamp is reported once when parsed and again
            // when the record is found to have no validFrom. The summary counts reported errors, not records.
            assertThat(capturedErrors)
                    .as("a single summary naming the count")
                    .anyMatch(message -> message.contains("3 ingest error(s) were reported"));
            // The good record still loaded - the summary reports loss, it does not cause it.
            assertThat(query(stores, "MATCH (g:Thing {id: 'n3'}) RETURN g.id"))
                    .extracting(row -> row[0].toString()).containsExactly("n3");
        }
    }

    /**
     * A clean stream must say nothing. A summary that always fired would be noise, and noise is how a real one
     * gets ignored.
     */
    @Test
    void cleanStream_reportsNothing(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("noskip"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Thing</label>
                            <property name="id">n1</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            assertThat(capturedErrors).isEmpty();
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
     * on: if the encoding a value is anchored under and the encodings a literal seeks do not overlap, the node is
     * silently not found rather than an error being raised.
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
     * The headline case for typed doubles, and the one that used to be impossible. A value ingested as
     * {@code 42.0} renders as {@code 42}, so an anchor keyed on rendered text could never be found by a query
     * for {@code 42.0}. Anchoring numbers by value rather than by text is what closes that, and all four
     * spellings below must reach the same node.
     *
     * <p>Like every test here this ingests into a fragment and merges it, so a pass also means ingest and merge
     * derived identical anchors from the decoded value.</p>
     */
    @Test
    void typedDouble_isFoundByEverySpellingOfTheSameNumber(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed5"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="score" type="double">42.0</property>
                            <property name="name">widget</property>
                        </node>
                    </graph>
                    """);

            // Exponent form is deliberately absent: the Cypher grammar's NUMBER rule has no exponent, so
            // 4.2e1 is not a literal a query can contain. The encoder accepts one anyway, which
            // TestGraphAnchorEncoding covers, so the grammar can gain exponents without touching the index.
            for (final String literal : List.of("42.0", "42", "42.00")) {
                assertThat(query(stores, "MATCH (n:Item {score: " + literal + "}) RETURN n.name"))
                        .as("anchored on " + literal)
                        .extracting(row -> row[0].toString()).containsExactly("widget");
            }
        }
    }

    /**
     * A double keeps its fractional part, and a query for a nearby but different value must not match it. Pinned
     * because the encoding buckets numbers, and a bucket too coarse would turn a wrong answer into a plausible
     * one.
     */
    @Test
    void typedDouble_doesNotMatchANeighbouringValue(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed6"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="score" type="double">42.5</property>
                        </node>
                    </graph>
                    """);

            assertThat(query(stores, "MATCH (n:Item {score: 42.5}) RETURN n.score"))
                    .extracting(row -> row[0].toString()).containsExactly("42.5");
            assertThat(query(stores, "MATCH (n:Item {score: 42.6}) RETURN n.score")).isEmpty();
            assertThat(query(stores, "MATCH (n:Item {score: 42}) RETURN n.score")).isEmpty();
        }
    }

    /**
     * A timestamp is one instant however it is spelled, so an ingested value must be found by its epoch form as
     * well as its rendered one. This is the date half of the same bug doubles had.
     */
    @Test
    void typedDateTime_isFoundBySpellingAndByEpoch(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed7"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Event</label>
                            <property name="occurred" type="dateTime">2026-03-04T05:06:07.008Z</property>
                            <property name="name">login</property>
                        </node>
                    </graph>
                    """);

            final long epochMs = Instant.parse("2026-03-04T05:06:07.008Z").toEpochMilli();
            assertThat(query(stores, "MATCH (n:Event {occurred: '2026-03-04T05:06:07.008Z'}) RETURN n.name"))
                    .extracting(row -> row[0].toString()).containsExactly("login");
            assertThat(query(stores, "MATCH (n:Event {occurred: " + epochMs + "}) RETURN n.name"))
                    .as("the same instant, written as an epoch")
                    .extracting(row -> row[0].toString()).containsExactly("login");
        }
    }

    /**
     * A string property whose text happens to look like a number must still be found by that text. Numbers and
     * text are keyed under different tags, so this is the case where a seek that only tried the numeric encoding
     * would return nothing at all.
     */
    @Test
    void stringPropertyThatLooksNumeric_isStillFound(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed8"), DOC)) {
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="ref">42</property>
                            <property name="name">widget</property>
                        </node>
                    </graph>
                    """);

            assertThat(query(stores, "MATCH (n:Item {ref: '42'}) RETURN n.name"))
                    .extracting(row -> row[0].toString()).containsExactly("widget");
            assertThat(query(stores, "MATCH (n:Item {ref: 42}) RETURN n.name"))
                    .as("an unquoted literal must reach a string property too")
                    .extracting(row -> row[0].toString()).containsExactly("widget");
        }
    }

    /**
     * A double that does not parse is a bad record, like every other declared type that does not parse.
     */
    @Test
    void typedDoubleAndDateThatDoNotParse_areReported(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("typed9"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            ingest(stores, """
                    <graph xmlns="graph-mutation:1" version="1.0">
                        <node id="n1" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="score" type="double">not-a-number</property>
                        </node>
                        <node id="n2" validFrom="2026-01-01T00:00:00.000Z">
                            <label>Item</label>
                            <property name="occurred" type="dateTime">last Tuesday</property>
                        </node>
                    </graph>
                    """, new AtomicReference<>(stores), capturedErrors);

            assertThat(capturedErrors.getFirst()).contains("score", "not a number");
            assertThat(capturedErrors.get(1)).contains("occurred", "not a timestamp");
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

    // ------------------------------------------------------------------------------------------------------
    // Phase 5.4: a failed stream must not ship its partial fragment. GraphFilter commits each record into the
    // fragment as it goes, and endProcessing runs from the stream task's finally whether the stream failed or
    // not - so these drive that exact shape (the plain harness below cannot: ProcessorUtil has no finally, so
    // a strict throw used to skip endProcessing entirely and the shipping path was never exercised).
    // ------------------------------------------------------------------------------------------------------

    private static final String GOOD_RECORD_THEN_BAD_RECORD_XML = """
            <graph xmlns="graph-mutation:1" version="1.0">
                <node id="good" validFrom="2026-01-01T00:00:00.000Z">
                    <label>Thing</label>
                    <property name="id">good</property>
                </node>
                <node id="bad" validFrom="not-a-timestamp">
                    <label>Thing</label>
                </node>
            </graph>
            """;

    /**
     * A strict-mode failure part-way through a stream ships <b>no</b> fragment. By the time record two fails,
     * record one is already committed into the fragment - and {@code endProcessing} still runs from the task's
     * {@code finally}. Its close used to zip and send that prefix, inverting strict mode's contract ("rather
     * have no data than quietly incomplete data"); worse, merge is a union of versions, so the prefix would
     * have survived the operator's corrected reprocess forever.
     */
    @Test
    void strict_failurePartWayThroughAStream_shipsNoFragment_andLeavesNoDirectoryBehind(
            @TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("failship1"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            final ExecutorStyleIngestOutcome outcome =
                    ingestAsTheExecutorDoes(stores, GOOD_RECORD_THEN_BAD_RECORD_XML, capturedErrors, true);

            assertThat(outcome.streamFailed()).as("the strict throw reached the executor").isTrue();
            assertThat(capturedErrors).anyMatch(message -> message.contains("Unable to parse validFrom"));
            assertThat(outcome.shippedFragmentCount()).as("a failed stream must ship nothing").isZero();
            assertThat(outcome.leftoverWriterDirs())
                    .as("the discarded fragment's directory must be deleted").isEmpty();
            // The already-committed prefix (record one) must not have reached the authoritative store.
            assertThat(query(stores, "MATCH (g:Thing {id: 'good'}) RETURN g.id")).isEmpty();
        }
    }

    /**
     * The boundary the discard path must not cross: lenient mode's record-level errors are not stream failure,
     * so the same input still ships one fragment, minus the skipped record - the documented and intended
     * behaviour ("one bad record cannot cost a whole stream").
     */
    @Test
    void lenient_runWithBadRecords_stillShipsItsFragment(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("failship2"), DOC)) {
            final List<String> capturedErrors = new ArrayList<>();
            final ExecutorStyleIngestOutcome outcome =
                    ingestAsTheExecutorDoes(stores, GOOD_RECORD_THEN_BAD_RECORD_XML, capturedErrors, false);

            assertThat(outcome.streamFailed()).isFalse();
            assertThat(capturedErrors).anyMatch(message -> message.contains("Unable to parse validFrom"));
            assertThat(outcome.shippedFragmentCount()).as("lenient mode still ships its fragment").isEqualTo(1);
            assertThat(outcome.leftoverWriterDirs()).isEmpty();
            assertThat(query(stores, "MATCH (g:Thing {id: 'good'}) RETURN g.id"))
                    .extracting(row -> row[0].toString()).containsExactly("good");
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
        ingest(stores, xml, currentStores, capturedErrors, strict, stubDocCache());
    }

    private static GraphDbDocCache stubDocCache() {
        return new GraphDbDocCache() {
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
        };
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
     * Drives ingest the way {@code AbstractProcessorTaskExecutor} does when the pipeline throws: the exception
     * from processing is handled (a {@link LoggedException} is already reported, so the executor swallows it)
     * and {@code endProcessing} <b>still runs</b>, from a {@code finally}, with no exception passed to it. The
     * plain {@link #ingest} harness cannot reach that path - {@code ProcessorUtil} has no {@code finally} of
     * its own, so a strict throw skips {@code endProcessing} entirely - and that path is exactly where a failed
     * stream used to ship its partial fragment (item 5.4).
     *
     * @return what the run shipped, whether it failed, and anything left in the writer directory.
     */
    private static ExecutorStyleIngestOutcome ingestAsTheExecutorDoes(final GraphStores stores,
                                                                      final String xml,
                                                                      final List<String> capturedErrors,
                                                                      final boolean strict) {
        final List<Path> shippedFragments = new ArrayList<>();
        final Path fragmentRoot;
        try {
            fragmentRoot = Files.createTempDirectory("graph-filter-fragments");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final GraphFileTransferClient fileTransferClient = (fileDescriptor, path, synchroniseMerge) -> {
            try {
                final Path kept = fragmentRoot.resolve("shipped-" + shippedFragments.size() + ".zip");
                Files.copy(path, kept);
                shippedFragments.add(kept);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        final GraphPaths graphPaths = new GraphPaths(fragmentRoot.resolve("paths"));
        final GraphShardWriters graphShardWriters =
                new GraphShardWriters(graphPaths, fileTransferClient, GraphDbConfig::new);
        final MetaHolder metaHolder = new MetaHolder();
        metaHolder.setMeta(Meta.builder().id(1L).build());

        final GraphFilter graphFilter = new GraphFilter(
                new ErrorReceiverProxy((severity, location, elementId, message, errorType, e) ->
                        capturedErrors.add(message)),
                new LocationFactoryProxy(),
                stubDocCache(),
                graphShardWriters,
                metaHolder);
        graphFilter.setGraphDb(DOC_REF);
        graphFilter.setStrict(strict);

        final ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        boolean streamFailed = false;
        try {
            ProcessorUtil.processXml(input, new ErrorReceiverProxy(new FatalErrorReceiver()), graphFilter,
                    new LocationFactoryProxy());
        } catch (final LoggedException e) {
            // AbstractProcessorTaskExecutor.handleProcessingException: already logged, so ignored...
            streamFailed = true;
            // ...and then its finally still ends processing, exception or not.
            graphFilter.endProcessing();
        }
        mergeShippedFragments(shippedFragments, fragmentRoot, stores);

        final List<Path> leftoverWriterDirs = new ArrayList<>();
        if (Files.isDirectory(graphPaths.getWriterDir())) {
            try (final Stream<Path> leftovers = Files.list(graphPaths.getWriterDir())) {
                leftoverWriterDirs.addAll(leftovers.toList());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ExecutorStyleIngestOutcome(streamFailed, shippedFragments.size(), leftoverWriterDirs);
    }

    /**
     * What one {@link #ingestAsTheExecutorDoes} run left behind.
     *
     * @param streamFailed          whether the pipeline threw (and the executor-style harness swallowed it).
     * @param shippedFragmentCount  how many fragment zips reached the transfer client.
     * @param leftoverWriterDirs    whatever the run left in the shard writers' directory; must be empty.
     */
    private record ExecutorStyleIngestOutcome(boolean streamFailed,
                                              int shippedFragmentCount,
                                              List<Path> leftoverWriterDirs) {

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
