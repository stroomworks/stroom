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
import stroom.planb.impl.serde.time.TimeSerde;
import stroom.planb.shared.TemporalPrecision;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers Temporal Precision, which decides how many bytes of every node and edge key the {@code validFrom}
 * occupies.
 *
 * <p>The setting used to be inert - editable, persisted, and read by nothing. These tests exist because "inert"
 * and "working" look identical from the outside for a single-precision store: everything passes at the default
 * either way. What distinguishes them is whether a coarser precision actually changes the encoding, whether a
 * store refuses to be reopened under a different one, and whether the "latest" sentinel tracks the encoding's
 * ceiling rather than a hard-coded millisecond value.</p>
 */
class TestGraphTemporalPrecision {

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00.000Z");

    /**
     * Every supported precision must round-trip a node through storage and retrieval. Parameterised because a
     * per-precision bug would otherwise only show up for whichever one a hand-written test happened to pick, and
     * the default is the one least likely to be wrong.
     */
    @ParameterizedTest
    @EnumSource(value = TemporalPrecision.class, names = {"MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY"})
    void everySupportedPrecision_roundTripsANodeVersion(final TemporalPrecision precision,
                                                        @TempDir final Path root) {
        final GraphDbDoc doc = docWith(precision);
        try (GraphStores stores = GraphStores.provision(root.resolve("g-" + precision), doc)) {
            final long nodeUid = writeNode(stores, VALID_FROM);

            final Optional<GraphNodeDb.NodeVersion> version = stores.read(txn ->
                    stores.getNodes().getNode(txn, nodeUid, VALID_FROM.plus(1, ChronoUnit.DAYS)));

            assertThat(version).isPresent();
        }
    }

    /**
     * The point of a coarser precision is a narrower key. Asserted on the serde rather than on file size, which is
     * dominated by LMDB page granularity at this scale and would make the test meaningless.
     */
    @ParameterizedTest
    @EnumSource(value = TemporalPrecision.class, names = {"MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY"})
    void coarserPrecision_usesFewerKeyBytes(final TemporalPrecision precision) {
        final TimeSerde serde = GraphTimeSerdes.forPrecision(precision);

        final int expected = switch (precision) {
            case MILLISECOND -> 6;
            case SECOND, MINUTE -> 4;
            case HOUR -> 3;
            case DAY -> 2;
            case NANOSECOND -> throw new AssertionError("not covered by this test");
        };
        assertThat(serde.getSize()).isEqualTo(expected);
    }

    /**
     * The "latest" sentinel must be encodable by the store's own serde. A hard-coded millisecond ceiling
     * (&asymp; year 10920) silently wraps in a 2-byte day key, which would make a "latest" lookup resolve to the
     * wrong version rather than fail - the class of bug this whole increment exists to remove.
     */
    @ParameterizedTest
    @EnumSource(value = TemporalPrecision.class, names = {"MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY"})
    void theLatestSentinel_isEncodableByItsOwnSerde(final TemporalPrecision precision) {
        final TimeSerde serde = GraphTimeSerdes.forPrecision(precision);
        final Instant latest = GraphTimeSerdes.latestRepresentable(precision);

        final ByteBuffer buffer = ByteBuffer.allocateDirect(serde.getSize());
        serde.write(buffer, latest);
        buffer.flip();

        // Round-tripping must land in the same encoding unit, not merely not throw - a wrapped value would also
        // not throw.
        assertThat(serde.read(buffer)).isBetween(latest.minus(1, ChronoUnit.DAYS), latest);
    }

    /**
     * A "latest" lookup must find the newest version at every precision. This is the behavioural counterpart of
     * the sentinel test above: it goes through the real engine path rather than the serde directly.
     */
    @ParameterizedTest
    @EnumSource(value = TemporalPrecision.class, names = {"MILLISECOND", "SECOND", "MINUTE", "HOUR", "DAY"})
    void latestLookup_findsTheNewestVersion(final TemporalPrecision precision, @TempDir final Path root) {
        final GraphDbDoc doc = docWith(precision);
        try (GraphStores stores = GraphStores.provision(root.resolve("g-latest-" + precision), doc)) {
            final long nodeUid = writeNode(stores, Instant.parse("2020-06-01T00:00:00.000Z"));
            // The two versions carry different property values, which is what makes "did latest resolve to the
            // newer one" assertable - a NodeVersion does not expose its own validFrom.
            writeNodeFor(stores, nodeUid, Instant.parse("2020-06-01T00:00:00.000Z"), "older");
            writeNodeFor(stores, nodeUid, Instant.parse("2024-06-01T00:00:00.000Z"), "newer");

            final Optional<GraphNodeDb.NodeVersion> version = stores.read(txn ->
                    stores.getNodes().getNode(txn, nodeUid, stores.getLatestRepresentableInstant()));

            assertThat(version).isPresent();
            assertThat(version.get().properties().get("era").toString()).isEqualTo("newer");
        }
    }

    /**
     * Precision is part of the key layout, so it cannot change under an existing store. Reopening at a different
     * precision must be refused rather than silently reinterpreting every key - which would return wrong answers
     * with nothing to indicate it.
     */
    @Test
    void reopeningAtADifferentPrecision_isRefused(@TempDir final Path root) {
        final Path directory = root.resolve("g-immutable");
        try (GraphStores stores = GraphStores.provision(directory, docWith(TemporalPrecision.MILLISECOND))) {
            writeNode(stores, VALID_FROM);
        }

        // Asserted on the message, not merely on "something threw": open() has plenty of other failure modes, and
        // a test that accepted any of them would keep passing if the precision stopped being part of the stamp.
        assertThatThrownBy(() -> GraphStores.open(directory, docWith(TemporalPrecision.DAY), false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Key schema mismatch")
                .hasMessageContaining("millisecond6")
                .hasMessageContaining("day2");
    }

    /**
     * Reopening at the <i>same</i> precision must of course still work - otherwise the check above would be
     * indistinguishable from a store that never reopens at all.
     */
    @Test
    void reopeningAtTheSamePrecision_succeeds(@TempDir final Path root) {
        final Path directory = root.resolve("g-reopen");
        try (GraphStores stores = GraphStores.provision(directory, docWith(TemporalPrecision.HOUR))) {
            writeNode(stores, VALID_FROM);
        }

        try (GraphStores reopened = GraphStores.open(directory, docWith(TemporalPrecision.HOUR), false)) {
            assertThat(reopened.getLatestRepresentableInstant())
                    .isEqualTo(GraphTimeSerdes.latestRepresentable(TemporalPrecision.HOUR));
        }
    }

    /**
     * A document with no precision keeps the historical encoding, so an existing millisecond store still opens.
     */
    @Test
    void anUnsetPrecision_defaultsToMillisecond() {
        assertThat(GraphTimeSerdes.resolve(null)).isEqualTo(TemporalPrecision.MILLISECOND);
        assertThat(GraphTimeSerdes.forPrecision(GraphTimeSerdes.resolve(null)).getSize()).isEqualTo(6);
    }

    /**
     * {@code NANOSECOND} is rejected with a reason. The encoding could carry nanoseconds; the ingest vocabulary
     * cannot express them, so it would spend the widest key of any option storing guaranteed zeros.
     */
    @Test
    void nanosecondPrecision_isRejectedWithAReason() {
        assertThatThrownBy(() -> GraphTimeSerdes.forPrecision(TemporalPrecision.NANOSECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three fractional digits");
    }

    private static GraphDbDoc docWith(final TemporalPrecision precision) {
        return GraphDbDoc
                .builder()
                .uuid("precision-" + precision)
                .name("Graph-" + precision)
                .temporalPrecision(precision)
                .build();
    }

    private static long writeNode(final GraphStores stores, final Instant validFrom) {
        final long nodeUid = stores.write(writer -> stores.getNodeUids().put(
                writer.getWriteTxn(),
                directBuffer("n1"),
                uidBuffer -> stroom.lmdb.serde.UnsignedBytesInstances
                        .ofLength(GraphStores.NODE_UID_WIDTH)
                        .get(uidBuffer.duplicate())));
        writeNodeFor(stores, nodeUid, validFrom);
        return nodeUid;
    }

    private static void writeNodeFor(final GraphStores stores, final long nodeUid, final Instant validFrom) {
        stores.write(writer -> {
            stores.getNodes().insert(writer, nodeUid, validFrom, List.of(), Map.of());
            writer.commit();
            return null;
        });
    }

    private static void writeNodeFor(final GraphStores stores,
                                     final long nodeUid,
                                     final Instant validFrom,
                                     final String era) {
        stores.write(writer -> {
            stores.getNodes().insert(
                    writer, nodeUid, validFrom, List.of(), Map.of("era", ValString.create(era)));
            writer.commit();
            return null;
        });
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }
}
