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

package stroom.floormap.impl;

import stroom.floormap.shared.FloorMapEventExpiry;
import stroom.floormap.shared.FloorMapEventStoreDoc;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.StateType;
import stroom.query.api.Param;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read contract: what a caller must say to get a snapshot, and where the expiry floor comes from.
 *
 * <p>This is the part of the design that replaced a read mode inferred from whether a time term
 * happened to be {@code <} rather than {@code >}. The rules are therefore worth pinning precisely —
 * an accidental relaxation here would put the guessing back.</p>
 */
class TestFloorMapEventStoreSearchProvider {

    private static final Instant AS_AT = Instant.parse("2026-01-01T12:00:00.000Z");

    private static List<Param> params(final String... keysAndValues) {
        final List<Param> list = new java.util.ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            list.add(new Param(keysAndValues[i], keysAndValues[i + 1]));
        }
        return list;
    }

    private static FloorMapEventStoreDoc docWithExpiry(final SimpleDuration expiry) {
        return FloorMapEventStoreDoc.eventStoreBuilder()
                .uuid("uuid")
                .name("events")
                .eventExpiry(expiry)
                .build();
    }

    // ------------------------------------------------------------------
    // Neither half of the contract works alone.
    // ------------------------------------------------------------------

    @Test
    void noParametersMeansAnOrdinaryRangeRead() {
        assertThat(FloorMapEventStoreSearchProvider.readAsAt(params()))
                .as("the default is what every other data source gives")
                .isNull();
        assertThat(FloorMapEventStoreSearchProvider.readAsAt(null)).isNull();
    }

    @Test
    void bothParametersTogetherGiveASnapshot() {
        assertThat(FloorMapEventStoreSearchProvider.readAsAt(params(
                "readMode", "snapshot",
                "asAt", String.valueOf(AS_AT.toEpochMilli()))))
                .isEqualTo(AS_AT);
    }

    @Test
    void asAtWithoutAReadModeIsRefused() {
        // Refused rather than assumed: a caller who believes they asked for a snapshot must not
        // silently receive every row instead.
        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.readAsAt(
                params("asAt", String.valueOf(AS_AT.toEpochMilli()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readMode");
    }

    @Test
    void readModeWithoutAnAsAtIsRefused() {
        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.readAsAt(
                params("readMode", "snapshot")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("asAt");
    }

    @Test
    void anUnknownReadModeIsNamedRatherThanIgnored() {
        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.readAsAt(
                params("readMode", "latest", "asAt", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latest");
    }

    @Test
    void theReadModeIsNotCaseSensitive() {
        assertThat(FloorMapEventStoreSearchProvider.readAsAt(params(
                "readMode", "SNAPSHOT",
                "asAt", String.valueOf(AS_AT.toEpochMilli()))))
                .isEqualTo(AS_AT);
    }

    // ------------------------------------------------------------------
    // asAt is epoch millis, and only that.
    // ------------------------------------------------------------------

    @Test
    void dateLiteralIsRefusedRatherThanParsed() {
        // The whole point: no date parser takes part in deciding what a query means, so the answer
        // cannot depend on how a literal is spelled or on the viewer's time zone.
        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.readAsAt(
                params("readMode", "snapshot", "asAt", "2026-01-01T12:00:00.000Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch milliseconds");
    }

    @Test
    void surroundingWhitespaceIsTolerated() {
        assertThat(FloorMapEventStoreSearchProvider.readAsAt(params(
                "readMode", "snapshot",
                "asAt", "  " + AS_AT.toEpochMilli() + " ")))
                .isEqualTo(AS_AT);
    }

    @Test
    void anInstantTheStoreCannotRepresentIsNamedHere() {
        // Rather than surfacing from inside the key encoding as "Negative values are not permitted",
        // several frames from anything the caller wrote.
        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.readAsAt(
                params("readMode", "snapshot", "asAt", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the range");

        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.readAsAt(
                params("readMode", "snapshot", "asAt", String.valueOf(Long.MAX_VALUE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the range");
    }

    // ------------------------------------------------------------------
    // The expiry floor belongs to the store.
    // ------------------------------------------------------------------

    @Test
    void theFloorIsAsAtMinusTheStoresExpiry() {
        final SimpleDuration twoHours = SimpleDuration.builder()
                .time(2)
                .timeUnit(TimeUnit.HOURS)
                .build();

        assertThat(FloorMapEventStoreSearchProvider.expiryFloor(docWithExpiry(twoHours), AS_AT))
                .isEqualTo(AS_AT.minusSeconds(2 * 3600L));
    }

    @Test
    void anUnsetExpiryUsesTheStoresDefault() {
        assertThat(FloorMapEventStoreSearchProvider.expiryFloor(docWithExpiry(null), AS_AT))
                .isEqualTo(Instant.ofEpochMilli(
                        FloorMapEventExpiry.cutoff(AS_AT.toEpochMilli(), null)));
    }

    /**
     * A store of another type is refused rather than read.
     *
     * <p>{@code searchSnapshot} requires a prefix-free key encoding, which only this document type
     * guarantees; over any other encoding it drops keys silently rather than failing. Proceeding
     * without a floor would be the least of the problems.</p>
     */
    @Test
    void storeOfAnotherTypeIsRefused() {
        final PlanBDoc otherType = PlanBDoc.builder()
                .uuid("uuid")
                .name("events")
                .stateType(StateType.TEMPORAL_STATE)
                .build();

        assertThatThrownBy(() -> FloorMapEventStoreSearchProvider.expiryFloor(otherType, AS_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FloorMapEventStoreDoc.TYPE);
    }
}
