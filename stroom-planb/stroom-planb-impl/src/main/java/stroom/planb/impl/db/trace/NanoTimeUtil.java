/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.planb.impl.db.trace;

import stroom.pathways.shared.otel.trace.NanoTime;

import java.time.Instant;

public class NanoTimeUtil {

    /** Epoch seconds at 2000-01-01T00:00:00Z. Timestamps are stored relative to this so the
     * common "recent" range stays small and positive. */
    public static final long EPOCH_2000_SECONDS = 946684800L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public static NanoTime now() {
        return fromInstant(Instant.now());
    }

    public static NanoTime fromInstant(final Instant instant) {
        return new NanoTime(instant.getEpochSecond(), instant.getNano());
    }

    public static Instant toInstant(final NanoTime nanoTime) {
        return Instant.ofEpochSecond(nanoTime.getSeconds(), nanoTime.getNanos());
    }

    /**
     * Encodes a {@link NanoTime} as a single long: nanoseconds since 2000-01-01. This is the
     * canonical encoding shared by the pathway-events value serde and the event LMDB key, so both
     * must use this method (rather than reimplementing the arithmetic) to stay byte-compatible.
     */
    public static long toEpoch2000Nanos(final NanoTime nanoTime) {
        return ((nanoTime.getSeconds() - EPOCH_2000_SECONDS) * NANOS_PER_SECOND) + nanoTime.getNanos();
    }

    /**
     * Inverse of {@link #toEpoch2000Nanos}. Uses floor division so pre-2000 (negative) values
     * decode correctly.
     */
    public static NanoTime fromEpoch2000Nanos(final long totalNanos) {
        final long seconds = EPOCH_2000_SECONDS + Math.floorDiv(totalNanos, NANOS_PER_SECOND);
        final int nanos = (int) Math.floorMod(totalNanos, NANOS_PER_SECOND);
        return new NanoTime(seconds, nanos);
    }
}
