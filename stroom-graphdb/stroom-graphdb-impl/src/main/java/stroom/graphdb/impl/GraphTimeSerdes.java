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

import stroom.planb.impl.serde.time.DayTimeSerde;
import stroom.planb.impl.serde.time.HourTimeSerde;
import stroom.planb.impl.serde.time.MillisecondTimeSerde;
import stroom.planb.impl.serde.time.MinuteTimeSerde;
import stroom.planb.impl.serde.time.SecondTimeSerde;
import stroom.planb.impl.serde.time.TimeSerde;
import stroom.planb.shared.TemporalPrecision;

import java.time.Instant;
import java.util.Objects;

/**
 * Chooses the {@code validFrom} encoding for a graph from its document's Temporal Precision.
 *
 * <p>Precision is a real setting rather than a label: it decides how many bytes every node and edge key spends on
 * time, from 2 (day) to 6 (millisecond). Since every key in the node, out-edge and in-edge stores ends with a
 * {@code validFrom}, and a graph is mostly keys, this is the single largest lever on a graph's size. It was
 * previously inert - the control was editable and persisted, and nothing read it.</p>
 *
 * <p>Because the width is part of the key layout, precision is <b>fixed when a graph is provisioned</b>. The store
 * records it and refuses to open under a different one; see {@link GraphSchemaDb}. Changing it means rebuilding.</p>
 *
 * <p><b>{@code NANOSECOND} is rejected</b>, and the reason is the ingest vocabulary rather than the encoding.
 * {@code NanoTimeSerde} does carry genuine nanoseconds, but {@code graph-mutation:1} constrains a timestamp to
 * three fractional digits, so a sub-millisecond {@code validFrom} cannot be expressed. Allowing it would spend
 * 8 bytes per key - the widest of any option - storing digits that are guaranteed to be zero. If the vocabulary
 * ever admits finer timestamps this becomes worth supporting, which is why it is rejected here rather than removed
 * from the shared {@link TemporalPrecision} enum that Plan B also uses.</p>
 */
public final class GraphTimeSerdes {

    /** The precision used when a document does not specify one - the widest, and the historical behaviour. */
    public static final TemporalPrecision DEFAULT_PRECISION = TemporalPrecision.MILLISECOND;

    /**
     * The epoch two of Plan B's serdes count from. {@code SecondTimeSerde} and {@code NanoTimeSerde} are
     * 2000-relative while millisecond, minute, hour and day are 1970-relative. That inconsistency is Plan B's and
     * is mirrored rather than corrected, because the encodings must stay byte-compatible with it.
     */
    private static final long YEAR_2000_EPOCH_SECONDS = 946684800L;

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;
    private static final long SECONDS_PER_DAY = 86400L;

    private GraphTimeSerdes() {
        // Static utility.
    }

    /**
     * Resolves a document's precision, substituting {@link #DEFAULT_PRECISION} when it has none.
     *
     * <p><b>Postconditions:</b> returns a supported precision.
     * <b>Null status:</b> {@code precision} is nullable; the return value is never null.
     *
     * @param precision the document's setting, possibly null.
     * @return the precision to use.
     */
    public static TemporalPrecision resolve(final TemporalPrecision precision) {
        return precision == null
                ? DEFAULT_PRECISION
                : precision;
    }

    /**
     * The {@link TimeSerde} for {@code precision}.
     *
     * <p><b>Preconditions:</b> {@code precision} is not null and is not {@link TemporalPrecision#NANOSECOND}.
     * <b>Postconditions:</b> returns the encoding for {@code precision}.
     * <b>Null status:</b> {@code precision} is not nullable; the return value is never null.
     *
     * @param precision the precision to encode at.
     * @return a new serde for {@code precision}.
     * @throws IllegalArgumentException if {@code precision} is {@link TemporalPrecision#NANOSECOND}.
     */
    public static TimeSerde forPrecision(final TemporalPrecision precision) {
        Objects.requireNonNull(precision, "precision must not be null");
        return switch (precision) {
            case MILLISECOND -> new MillisecondTimeSerde();
            case SECOND -> new SecondTimeSerde();
            case MINUTE -> new MinuteTimeSerde();
            case HOUR -> new HourTimeSerde();
            case DAY -> new DayTimeSerde();
            case NANOSECOND -> throw new IllegalArgumentException(
                    "Temporal Precision 'Nanosecond' is not supported for a graph: the graph-mutation vocabulary "
                    + "allows only three fractional digits in a timestamp, so nanoseconds cannot be ingested and "
                    + "the extra key width would store nothing. Use Millisecond or coarser.");
        };
    }

    /**
     * The latest instant {@code precision} can represent, used as the floor-lookup instant for "latest".
     *
     * <p>This has to track the serde exactly. {@link Instant#MAX} cannot be used - encoding it overflows every one
     * of these representations - and a value past the encodable maximum would wrap rather than saturate, so a
     * "latest" lookup would silently resolve to the wrong version.</p>
     *
     * <p><b>Preconditions:</b> {@code precision} is not null and supported.
     * <b>Postconditions:</b> returns an instant that {@code forPrecision(precision)} can encode.
     * <b>Null status:</b> {@code precision} is not nullable; the return value is never null.
     *
     * @param precision the precision to bound.
     * @return the latest representable instant.
     */
    public static Instant latestRepresentable(final TemporalPrecision precision) {
        Objects.requireNonNull(precision, "precision must not be null");
        // Derived from each serde's own unit and epoch rather than from getSize(), because the two 2000-relative
        // serdes would otherwise be off by 30 years and the coarse ones off by their unit multiplier.
        return switch (precision) {
            case MILLISECOND -> Instant.ofEpochMilli(maxUnsigned(Byte.SIZE * 6));
            case SECOND -> Instant.ofEpochSecond(maxUnsigned(Byte.SIZE * 4) + YEAR_2000_EPOCH_SECONDS);
            case MINUTE -> Instant.ofEpochSecond(maxUnsigned(Byte.SIZE * 4) * SECONDS_PER_MINUTE);
            case HOUR -> Instant.ofEpochSecond(maxUnsigned(Byte.SIZE * 3) * SECONDS_PER_HOUR);
            case DAY -> Instant.ofEpochSecond(maxUnsigned(Byte.SIZE * 2) * SECONDS_PER_DAY);
            case NANOSECOND -> throw new IllegalArgumentException(
                    "Temporal Precision 'Nanosecond' is not supported for a graph");
        };
    }

    private static long maxUnsigned(final int bits) {
        return (1L << bits) - 1L;
    }
}
