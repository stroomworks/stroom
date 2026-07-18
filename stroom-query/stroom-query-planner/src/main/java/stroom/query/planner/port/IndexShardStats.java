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

package stroom.query.planner.port;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * The Lucene index cost signal {@code CostModel} (Task 3.2) tries for a {@code Scan} - wraps
 * {@code IndexShardService.find(FindIndexShardCriteria)} (summing {@code documentCount}/{@code fileSize} over
 * shards whose {@code partitionTimeRange} overlaps the query's time range - "partition pruning") in the real
 * adapter (see {@code docs/query-optimiser-implementation-plan.md}, Task 3.1).
 *
 * <p><b>No adapter exists yet.</b> {@code stroom-index-impl} (home of {@code IndexShardService}) already
 * depends on {@code stroom-query-common}, which already depends on {@code stroom-query-planner} - so an
 * adapter placed in either of those modules would close a dependency cycle. The real adapter must instead live
 * inside {@code stroom-index-impl} itself (which can safely add a one-directional dependency on
 * {@code stroom-query-planner} to implement this interface) and self-register its binding in
 * {@code IndexModule}, the same inversion already used for {@code DataSourceProvider}. Tracked as follow-up
 * work; {@code CostModel}'s own tests use a fake.</p>
 */
public interface IndexShardStats {

    /**
     * @param indexName  never null; the index name as it would appear in a {@code from} clause.
     * @param fromTimeMs the inclusive lower bound of the query's time range, or null if unbounded below.
     * @param toTimeMs   the exclusive upper bound of the query's time range, or null if unbounded above.
     * @return empty if {@code indexName} is not a known index, or it has no shards overlapping the given range.
     */
    Optional<IndexCostSignal> estimate(String indexName, @Nullable Long fromTimeMs, @Nullable Long toTimeMs);
}
