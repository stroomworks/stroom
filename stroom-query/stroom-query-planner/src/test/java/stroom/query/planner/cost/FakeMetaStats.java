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

package stroom.query.planner.cost;

import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.RowCountSignal;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A fake {@link MetaStats} for {@link TestCostModel} - models a store with {@code totalRows} rows spread evenly
 * from {@code storeFromMs} to {@code storeToMs}, so a narrower requested time range yields a proportionally
 * smaller (never larger) row estimate - enough to exercise {@code CostModel}'s monotonicity property without a
 * real {@code MetaService}.
 */
final class FakeMetaStats implements MetaStats {

    private final String knownFeed;
    private final long totalRows;
    private final long storeFromMs;
    private final long storeToMs;

    FakeMetaStats(final String knownFeed, final long totalRows, final long storeFromMs, final long storeToMs) {
        this.knownFeed = knownFeed;
        this.totalRows = totalRows;
        this.storeFromMs = storeFromMs;
        this.storeToMs = storeToMs;
    }

    @Override
    public Optional<RowCountSignal> estimate(
            final String feedName, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs) {
        if (!knownFeed.equals(feedName)) {
            return Optional.empty();
        }
        final long from = fromTimeMs == null ? storeFromMs : Math.max(fromTimeMs, storeFromMs);
        final long to = toTimeMs == null ? storeToMs : Math.min(toTimeMs, storeToMs);
        final long overlapMs = Math.max(0, to - from);
        final long storeSpanMs = Math.max(1, storeToMs - storeFromMs);
        final long rows = Math.round(totalRows * (overlapMs / (double) storeSpanMs));
        return Optional.of(new RowCountSignal(rows));
    }
}
