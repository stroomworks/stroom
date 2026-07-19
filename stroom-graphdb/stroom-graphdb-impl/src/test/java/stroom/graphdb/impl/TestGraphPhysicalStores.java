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

import stroom.graphdb.shared.GraphDbDoc;
import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task PoC.4: a round-trip test proving {@link GraphNodeDb}/{@link GraphAdjacencyDb}/{@link GraphPropertyIndex}
 * read back the expected as-of neighbours and node version against a real temp-dir LMDB env, using
 * {@link GraphStores} exactly as {@code GraphTraversalEngine} (PoC.5) will: intern names via the interning
 * namespaces, write versioned nodes/edges/anchors, then anchor-seek -&gt; 1-hop expand -&gt; floor-filter.
 */
class TestGraphPhysicalStores {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00.000Z");
    private static final Instant T2 = Instant.parse("2026-06-01T00:00:00.000Z");

    @Test
    void anchorSeek_expand_andFloorFilter_readBackTheExpectedAsOfNeighbours(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph"), DOC)) {
            final long deviceLabel = intern(stores, stores.getLabelUids(), "Device");
            final long accountLabel = intern(stores, stores.getLabelUids(), "Account");
            final long idKey = intern(stores, stores.getPropertyKeyUids(), "id");
            final long connectedTo = intern(stores, stores.getEdgeTypeUids(), "CONNECTED_TO");

            final long deviceUid = intern(stores, stores.getNodeUids(), "d-42");
            final long accountAUid = intern(stores, stores.getNodeUids(), "account-a");
            final long accountBUid = intern(stores, stores.getNodeUids(), "account-b");

            stores.write(writer -> {
                stores.getNodes().insert(
                        writer, deviceUid, T1, List.of(deviceLabel), Map.of("id", ValString.create("d-42")));
                stores.getNodes().insert(writer, accountAUid, T1, List.of(accountLabel), Map.of());
                stores.getNodes().insert(writer, accountBUid, T1, List.of(accountLabel), Map.of());

                // Anchor the device by its external id.
                stores.getPropertyIndex().insert(
                        writer, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8), deviceUid);

                // d-42 -> account-a from T1 (still current); d-42 -> account-b only from T2 onward.
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountAUid, T1, Map.of());
                stores.getOutEdges().insert(writer, deviceUid, connectedTo, accountBUid, T2, Map.of());
                return null;
            });

            // Anchor seek.
            final List<Long> anchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, deviceLabel, idKey, "d-42".getBytes(StandardCharsets.UTF_8)));
            assertThat(anchors).containsExactly(deviceUid);

            // 1-hop expand as-of a time before account-b's edge existed.
            final List<Long> neighboursBeforeT2 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, deviceUid, connectedTo, T1.plusSeconds(1),
                        neighbour -> neighboursBeforeT2.add(neighbour.dstUid()));
                return null;
            });
            assertThat(neighboursBeforeT2).containsExactly(accountAUid);

            // 1-hop expand as-of a time after both edges existed.
            final List<Long> neighboursAtT2 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, deviceUid, connectedTo, T2,
                        neighbour -> neighboursAtT2.add(neighbour.dstUid()));
                return null;
            });
            assertThat(neighboursAtT2).containsExactlyInAnyOrder(accountAUid, accountBUid);

            // Floor-filter: the correct as-of node version for the anchor.
            final Optional<GraphNodeDb.NodeVersion> device = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, deviceUid, T2));
            assertThat(device).isPresent();
            assertThat(device.get().labelUids()).containsExactly(deviceLabel);
            assertThat(device.get().properties()).containsEntry("id", ValString.create("d-42"));
        }
    }

    @Test
    void insert_rejectsMoreThan255Labels(@TempDir final Path root) {
        // Code-review fix: the value format's label count is a single unsigned byte - previously unchecked, a
        // node with 256+ labels would silently wrap the count byte to a smaller, wrong value, corrupting the
        // decode of every label UID and the properties blob that follows them. Now rejected up front.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph-toomanylabels"), DOC)) {
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final List<Long> tooManyLabels = new ArrayList<>();
            for (int i = 0; i < 256; i++) {
                tooManyLabels.add(intern(stores, stores.getLabelUids(), "Label" + i));
            }

            assertThatThrownBy(() -> stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, T1, tooManyLabels, Map.of());
                return null;
            }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("255");
        }
    }

    @Test
    void expandOut_stopsEmittingANeighbourOnceItsEdgeIsTombstoned(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph2"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dst, T1, Map.of());
                stores.getOutEdges().delete(writer, src, edgeType, dst, T2);
                return null;
            });

            final List<Long> beforeDelete = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(
                        readTxn, src, edgeType, T1.plusSeconds(1), n -> beforeDelete.add(n.dstUid()));
                return null;
            });
            assertThat(beforeDelete).containsExactly(dst);

            final List<Long> afterDelete = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, T2, n -> afterDelete.add(n.dstUid()));
                return null;
            });
            assertThat(afterDelete).isEmpty();
        }
    }

    @Test
    void getNode_returnsEmptyForAnInstantBeforeAnyVersionExisted(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3"), DOC)) {
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final long label = intern(stores, stores.getLabelUids(), "Thing");

            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, T2, List.of(label), Map.of());
                return null;
            });

            final Optional<GraphNodeDb.NodeVersion> tooEarly = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, T1));
            assertThat(tooEarly).isEmpty();
        }
    }

    @Test
    void count_isZeroForAnEmptyStore(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3d"), DOC)) {
            final long count = stores.read(readTxn -> stores.getNodes().count(readTxn));
            assertThat(count).isZero();
        }
    }

    @Test
    void count_countsEveryVersionRowNotDistinctNodes(@TempDir final Path root) {
        // Task P5.1's documented approximation: count() is a row count, not a distinct-node count - a node with
        // multiple historical versions is counted once per version.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3e"), DOC)) {
            final long singleVersionNode = intern(stores, stores.getNodeUids(), "n1");
            final long multiVersionNode = intern(stores, stores.getNodeUids(), "n2");
            final long label = intern(stores, stores.getLabelUids(), "Thing");

            stores.write(writer -> {
                stores.getNodes().insert(writer, singleVersionNode, T1, List.of(label), Map.of());
                stores.getNodes().insert(writer, multiVersionNode, T1, List.of(label), Map.of());
                stores.getNodes().insert(writer, multiVersionNode, T2, List.of(label), Map.of());
                return null;
            });

            final long count = stores.read(readTxn -> stores.getNodes().count(readTxn));
            assertThat(count).isEqualTo(3);
        }
    }

    @Test
    void getNodeWindow_returnsTheLatestVersionWhoseIntervalIntersectsTheWindow(@TempDir final Path root) {
        // Task P4.1. Three versions: v1=[t1,t2), v2=[t2,t3), v3=[t3,+inf).
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3b"), DOC)) {
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final long labelV1 = intern(stores, stores.getLabelUids(), "V1");
            final long labelV2 = intern(stores, stores.getLabelUids(), "V2");
            final long labelV3 = intern(stores, stores.getLabelUids(), "V3");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");
            final Instant t3 = Instant.parse("2021-01-01T00:00:00Z");

            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, t1, List.of(labelV1), Map.of());
                stores.getNodes().insert(writer, nodeUid, t2, List.of(labelV2), Map.of());
                stores.getNodes().insert(writer, nodeUid, t3, List.of(labelV3), Map.of());
                return null;
            });

            // A window entirely inside v1's interval: only v1 intersects.
            final Optional<GraphNodeDb.NodeVersion> withinV1 = stores.read(readTxn ->
                    stores.getNodes().getNodeWindow(
                            readTxn, nodeUid, t1.plus(Duration.ofDays(10)), t1.plus(Duration.ofDays(20))));
            assertThat(withinV1).isPresent();
            assertThat(withinV1.get().labelUids()).containsExactly(labelV1);

            // A window whose upper bound lands exactly on v2's validFrom: "validFrom == to includes", so both
            // v1 and v2 intersect - the later one (v2) wins.
            final Optional<GraphNodeDb.NodeVersion> straddlingBoundary = stores.read(readTxn ->
                    stores.getNodes().getNodeWindow(readTxn, nodeUid, t2.minus(Duration.ofDays(1)), t2));
            assertThat(straddlingBoundary).isPresent();
            assertThat(straddlingBoundary.get().labelUids()).containsExactly(labelV2);

            // A window whose lower bound lands exactly on v2's validFrom (== v1's nextValidFrom): "nextValidFrom
            // == from excludes", so v1 is excluded and only v2 intersects.
            final Optional<GraphNodeDb.NodeVersion> atV1sEnd = stores.read(readTxn ->
                    stores.getNodes().getNodeWindow(readTxn, nodeUid, t2, t2.plus(Duration.ofDays(5))));
            assertThat(atV1sEnd).isPresent();
            assertThat(atV1sEnd.get().labelUids()).containsExactly(labelV2);

            // A window entirely before v1 or entirely after v3: nothing intersects.
            final Optional<GraphNodeDb.NodeVersion> beforeEverything = stores.read(readTxn ->
                    stores.getNodes().getNodeWindow(
                            readTxn, nodeUid, t1.minus(Duration.ofDays(20)), t1.minus(Duration.ofDays(10))));
            assertThat(beforeEverything).isEmpty();
        }
    }

    @Test
    void getNodeWindow_aTombstoneAsTheLatestIntersectingVersionMeansAbsent(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph3c"), DOC)) {
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final long label = intern(stores, stores.getLabelUids(), "Thing");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");

            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, t1, List.of(label), Map.of());
                stores.getNodes().delete(writer, nodeUid, t2);
                return null;
            });

            // The window spans both the present version and the later tombstone - the tombstone is the latest
            // intersecting version, so the node counts as absent for this window even though the present
            // version also intersects.
            final Optional<GraphNodeDb.NodeVersion> result = stores.read(readTxn ->
                    stores.getNodes().getNodeWindow(
                            readTxn, nodeUid, t1.plus(Duration.ofDays(5)), t2.plus(Duration.ofDays(20))));
            assertThat(result).isEmpty();
        }
    }

    @Test
    void propertyIndex_supportsMultipleNodesSharingTheSameValue(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph4"), DOC)) {
            final long label = intern(stores, stores.getLabelUids(), "Account");
            final long statusKey = intern(stores, stores.getPropertyKeyUids(), "status");
            final long accountA = intern(stores, stores.getNodeUids(), "a");
            final long accountB = intern(stores, stores.getNodeUids(), "b");

            stores.write(writer -> {
                stores.getPropertyIndex().insert(
                        writer, label, statusKey, "active".getBytes(StandardCharsets.UTF_8), accountA);
                stores.getPropertyIndex().insert(
                        writer, label, statusKey, "active".getBytes(StandardCharsets.UTF_8), accountB);
                return null;
            });

            final List<Long> anchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, label, statusKey, "active".getBytes(StandardCharsets.UTF_8)));
            assertThat(anchors).containsExactlyInAnyOrder(accountA, accountB);
        }
    }

    @Test
    void propertyIndex_resolvesAnchorsAtEveryValueTierBoundary(@TempDir final Path root) {
        // Task P1.3: DIRECT (<=32 bytes), UID_LOOKUP (33-511 bytes), HASH_LOOKUP (>511 bytes) - prove findAnchors
        // resolves correctly right at and either side of both tier boundaries, not just for short values. Each
        // length uses a distinct fill character (not just a distinct length) - belt-and-braces alongside
        // propertyIndex_directTier_prefixValueDoesNotMatchLongerValue, which covers the same-prefix case directly.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph5"), DOC)) {
            final long label = intern(stores, stores.getLabelUids(), "Thing");
            final long key = intern(stores, stores.getPropertyKeyUids(), "value");

            final Map<Integer, Long> nodeUidByLength = new HashMap<>();
            final int[] lengths = {31, 32, 33, 510, 511, 512};
            for (int i = 0; i < lengths.length; i++) {
                final int length = lengths[i];
                final long nodeUid = intern(stores, stores.getNodeUids(), "n-" + length);
                nodeUidByLength.put(length, nodeUid);
                final byte[] valueBytes = valueOfLength(length, i);
                stores.write(writer -> {
                    stores.getPropertyIndex().insert(writer, label, key, valueBytes, nodeUid);
                    return null;
                });
            }

            for (int i = 0; i < lengths.length; i++) {
                final int length = lengths[i];
                final byte[] valueBytes = valueOfLength(length, i);
                final List<Long> anchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                        readTxn, label, key, valueBytes));
                assertThat(anchors)
                        .as("anchors for a %d-byte value", length)
                        .containsExactly(nodeUidByLength.get(length));
            }

            // A value that was never inserted at a lookup-backed tier resolves to no anchors, not an error.
            final List<Long> neverInserted = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, label, key, "y".repeat(100).getBytes(StandardCharsets.UTF_8)));
            assertThat(neverInserted).isEmpty();
        }
    }

    @Test
    void propertyIndex_directTier_prefixValueDoesNotMatchLongerValue(@TempDir final Path root) {
        // Regression test for a bug present since PoC.4 (found via the tier-boundary test above): the DIRECT
        // tier used to inline valueBytes into the key with no length delimiter, so anchoring on a value that is
        // a byte-for-byte prefix of a different, longer DIRECT-tier value on the same (label, propKey) would
        // incorrectly also match the longer value's anchors. Both values here are well within DIRECT_MAX_LENGTH
        // (32 bytes) and "abc" is a literal prefix of "abcdef" - the case the missing length delimiter mishandled.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph5b"), DOC)) {
            final long label = intern(stores, stores.getLabelUids(), "Thing");
            final long key = intern(stores, stores.getPropertyKeyUids(), "value");
            final long shortNodeUid = intern(stores, stores.getNodeUids(), "n-short");
            final long longNodeUid = intern(stores, stores.getNodeUids(), "n-long");

            stores.write(writer -> {
                stores.getPropertyIndex().insert(
                        writer, label, key, "abc".getBytes(StandardCharsets.UTF_8), shortNodeUid);
                stores.getPropertyIndex().insert(
                        writer, label, key, "abcdef".getBytes(StandardCharsets.UTF_8), longNodeUid);
                return null;
            });

            final List<Long> shortAnchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, label, key, "abc".getBytes(StandardCharsets.UTF_8)));
            assertThat(shortAnchors).containsExactly(shortNodeUid);

            final List<Long> longAnchors = stores.read(readTxn -> stores.getPropertyIndex().findAnchors(
                    readTxn, label, key, "abcdef".getBytes(StandardCharsets.UTF_8)));
            assertThat(longAnchors).containsExactly(longNodeUid);
        }
    }

    @Test
    void nodeDb_deleteOldData_keepsFloorVersionAndDeletesOlderOnes(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph6"), DOC)) {
            final long labelA = intern(stores, stores.getLabelUids(), "A");
            final long labelB = intern(stores, stores.getLabelUids(), "B");
            final long labelC = intern(stores, stores.getLabelUids(), "C");
            final long nodeUid = intern(stores, stores.getNodeUids(), "n1");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");
            final Instant t3 = Instant.parse("2021-01-01T00:00:00Z");
            final Instant deleteBefore = t2.plusSeconds(1);

            stores.write(writer -> {
                stores.getNodes().insert(writer, nodeUid, t1, List.of(labelA), Map.of());
                stores.getNodes().insert(writer, nodeUid, t2, List.of(labelB), Map.of());
                stores.getNodes().insert(writer, nodeUid, t3, List.of(labelC), Map.of());
                return null;
            });

            final long deletedCount = stores.write(writer -> stores.getNodes().deleteOldData(
                    writer, deleteBefore, stores.getNodeUidRecorder(), stores.getLabelUidRecorder()));
            assertThat(deletedCount).isEqualTo(1);

            final Optional<GraphNodeDb.NodeVersion> deletedVersion = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, t1.plusSeconds(1)));
            assertThat(deletedVersion)
                    .as("the @t1 version was superseded within the retention window and is now gone")
                    .isEmpty();

            final Optional<GraphNodeDb.NodeVersion> floor = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, deleteBefore));
            assertThat(floor).isPresent();
            assertThat(floor.get().labelUids()).containsExactly(labelB);

            final Optional<GraphNodeDb.NodeVersion> latest = stores.read(
                    readTxn -> stores.getNodes().getNode(readTxn, nodeUid, t3));
            assertThat(latest).isPresent();
            assertThat(latest.get().labelUids()).containsExactly(labelC);

            // deleteOldData recorded the survivors (B, C) as used but did not itself sweep - a separate,
            // explicit deleteUnused pass (GraphStores.deleteOldData's job in production) is needed to remove A.
            stores.write(writer -> {
                stores.read(readTxn -> {
                    stores.getLabelUidRecorder().deleteUnused(readTxn, writer);
                    return null;
                });
                return null;
            });
            assertThat(lookup(stores, stores.getLabelUids(), "A")).isEmpty();
            assertThat(lookup(stores, stores.getLabelUids(), "B")).contains(labelB);
            assertThat(lookup(stores, stores.getLabelUids(), "C")).contains(labelC);
        }
    }

    @Test
    void adjacencyDbs_deleteOldData_keepsFloorVersionAndDeletesOlderOnes(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph7"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");
            final Instant t3 = Instant.parse("2021-01-01T00:00:00Z");
            final Instant deleteBefore = t2.plusSeconds(1);

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dst, t1, Map.of());
                stores.getOutEdges().insert(writer, src, edgeType, dst, t2, Map.of());
                stores.getOutEdges().delete(writer, src, edgeType, dst, t3);
                stores.getInEdges().insert(writer, src, edgeType, dst, t1, Map.of());
                stores.getInEdges().insert(writer, src, edgeType, dst, t2, Map.of());
                stores.getInEdges().delete(writer, src, edgeType, dst, t3);
                return null;
            });

            final long outDeleted = stores.write(writer -> stores.getOutEdges().deleteOldData(
                    writer, deleteBefore, stores.getNodeUidRecorder(), stores.getEdgeTypeUidRecorder()));
            final long inDeleted = stores.write(writer -> stores.getInEdges().deleteOldData(
                    writer, deleteBefore, stores.getNodeUidRecorder(), stores.getEdgeTypeUidRecorder()));
            assertThat(outDeleted).isEqualTo(1);
            assertThat(inDeleted).isEqualTo(1);

            final List<Long> outBeforeFloor = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, t1.plusSeconds(1),
                        n -> outBeforeFloor.add(n.dstUid()));
                return null;
            });
            assertThat(outBeforeFloor)
                    .as("the @t1 out-edge version was superseded and is now gone")
                    .isEmpty();

            final List<Long> outAtFloor = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, deleteBefore, n -> outAtFloor.add(n.dstUid()));
                return null;
            });
            assertThat(outAtFloor).containsExactly(dst);

            final List<Long> inAtFloor = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getInEdges().expandIn(readTxn, dst, edgeType, deleteBefore, n -> inAtFloor.add(n.srcUid()));
                return null;
            });
            assertThat(inAtFloor).containsExactly(src);

            final List<Long> outAtT3 = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOut(readTxn, src, edgeType, t3, n -> outAtT3.add(n.dstUid()));
                return null;
            });
            assertThat(outAtT3)
                    .as("the @t3 tombstone still applies - the edge is absent from t3 onward")
                    .isEmpty();
        }
    }

    @Test
    void expandOutWindow_returnsTheLatestIntersectingVersionPerDestination(@TempDir final Path root) {
        // Task P4.1: src -> dstA has two versions [t1,t2)/[t2,+inf); src -> dstB has one bounded version
        // [t1-100d,t1-90d) (tombstoned so it doesn't extend to +inf), entirely outside the windows under test.
        try (GraphStores stores = GraphStores.provision(root.resolve("graph8"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dstA = intern(stores, stores.getNodeUids(), "dstA");
            final long dstB = intern(stores, stores.getNodeUids(), "dstB");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dstA, t1, Map.of());
                stores.getOutEdges().insert(writer, src, edgeType, dstA, t2, Map.of());
                stores.getOutEdges().insert(writer, src, edgeType, dstB, t1.minus(Duration.ofDays(100)), Map.of());
                stores.getOutEdges().delete(writer, src, edgeType, dstB, t1.minus(Duration.ofDays(90)));
                return null;
            });

            // A window straddling the t1/t2 boundary exactly at t2: both of dstA's versions intersect
            // ("validFrom == to includes"), the later one wins; dstB's single, much-earlier version does not
            // intersect at all.
            final List<Long> neighbours = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOutWindow(
                        readTxn, src, edgeType, t2.minus(Duration.ofDays(1)), t2, n -> neighbours.add(n.dstUid()));
                return null;
            });
            assertThat(neighbours).containsExactly(dstA);

            // A window entirely before dstA's first version and after dstB's version: nothing intersects.
            final List<Long> none = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOutWindow(
                        readTxn, src, edgeType, t1.minus(Duration.ofDays(50)), t1.minus(Duration.ofDays(40)),
                        n -> none.add(n.dstUid()));
                return null;
            });
            assertThat(none).isEmpty();
        }
    }

    @Test
    void expandOutWindow_aTombstoneAsTheLatestIntersectingVersionExcludesTheDestination(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph9"), DOC)) {
            final long src = intern(stores, stores.getNodeUids(), "src");
            final long dst = intern(stores, stores.getNodeUids(), "dst");
            final long edgeType = intern(stores, stores.getEdgeTypeUids(), "LINKS_TO");
            final Instant t1 = Instant.parse("2020-01-01T00:00:00Z");
            final Instant t2 = Instant.parse("2020-06-01T00:00:00Z");

            stores.write(writer -> {
                stores.getOutEdges().insert(writer, src, edgeType, dst, t1, Map.of());
                stores.getOutEdges().delete(writer, src, edgeType, dst, t2);
                return null;
            });

            final List<Long> neighbours = new ArrayList<>();
            stores.read(readTxn -> {
                stores.getOutEdges().expandOutWindow(
                        readTxn, src, edgeType, t1.plus(Duration.ofDays(5)), t2.plus(Duration.ofDays(20)),
                        n -> neighbours.add(n.dstUid()));
                return null;
            });
            assertThat(neighbours).isEmpty();
        }
    }

    private static byte[] valueOfLength(final int length, final int distinguisher) {
        final char fillChar = (char) ('a' + distinguisher);
        return String.valueOf(fillChar).repeat(length).getBytes(StandardCharsets.UTF_8);
    }

    private static long intern(final GraphStores stores, final UidLookupDb db,
                               final String key) {
        return stores.write(writer -> db.put(writer.getWriteTxn(), directBuffer(key), uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate())));
    }

    private static Optional<Long> lookup(final GraphStores stores, final UidLookupDb db, final String key) {
        return stores.read(readTxn -> db.get(
                readTxn,
                directBuffer(key),
                maybeUid -> maybeUid.map(uidBuffer ->
                        UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate()))));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
