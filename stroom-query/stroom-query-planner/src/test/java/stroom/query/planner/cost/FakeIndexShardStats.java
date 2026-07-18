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

import stroom.query.planner.port.IndexCostSignal;
import stroom.query.planner.port.IndexShardStats;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;

/** A fake {@link IndexShardStats} for {@link TestCostModel} - always answers for {@code knownIndex} with a
 *  fixed signal, regardless of the requested time range (partition-pruning behaviour isn't under test here). */
final class FakeIndexShardStats implements IndexShardStats {

    private final String knownIndex;
    private final IndexCostSignal signal;

    FakeIndexShardStats(final String knownIndex, final IndexCostSignal signal) {
        this.knownIndex = knownIndex;
        this.signal = signal;
    }

    static FakeIndexShardStats withThroughput(
            final String knownIndex, final long documentCount, final long byteSize,
            final double documentsPerSecond) {
        return new FakeIndexShardStats(
                knownIndex, new IndexCostSignal(documentCount, byteSize, OptionalDouble.of(documentsPerSecond)));
    }

    static FakeIndexShardStats withoutThroughput(
            final String knownIndex, final long documentCount, final long byteSize) {
        return new FakeIndexShardStats(
                knownIndex, new IndexCostSignal(documentCount, byteSize, OptionalDouble.empty()));
    }

    @Override
    public Optional<IndexCostSignal> estimate(
            final String indexName, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs) {
        return knownIndex.equals(indexName) ? Optional.of(signal) : Optional.empty();
    }
}
