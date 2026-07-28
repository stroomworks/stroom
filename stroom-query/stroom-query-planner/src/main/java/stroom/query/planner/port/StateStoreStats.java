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

import java.util.Optional;

/**
 * The Plan B / State store row-count cost signal {@code CostModel} (Task 3.2) tries last for a {@code Scan} -
 * wraps {@code ShardManager.get(mapName, Db::count)} in the real adapter (see
 * Task 3.1). No time-range parameter: Plan B/State stores
 * are key-addressed point-lookup stores (verified: {@code GetState}/{@code StateFetcher}/{@code StateProvider}
 * expose only a single-key {@code getState(map, key, effectiveTimeMs)} lookup, no scan/range path), not
 * partitioned scans like a Lucene index.
 *
 * <p><b>No adapter exists yet</b> - same reason as {@link IndexShardStats}: {@code stroom-planb-impl} already
 * depends on {@code stroom-query-common}, which already depends on {@code stroom-query-planner}, so the real
 * adapter must live inside {@code stroom-planb-impl} itself and self-register in {@code PlanBModule}. Tracked
 * as follow-up work; {@code CostModel}'s own tests use a fake.</p>
 */
public interface StateStoreStats {

    /**
     * @param storeName never null; the Plan B/State store name as it would appear in a {@code from} clause.
     * @return empty if {@code storeName} is not a known store.
     */
    Optional<RowCountSignal> estimate(String storeName);
}
