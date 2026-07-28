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
 * The stream-store row-count cost signal {@code CostModel} (Task 3.2) tries first for a {@code Scan} - wraps
 * {@code MetaService.getSelectionSummary(FindMetaCriteria)} in the real adapter (see
 * Task 3.1). The real adapter ({@code MetaStatsAdapter})
 * lives in {@code stroom-query-common}, which can safely depend on {@code stroom-meta-api}; this interface
 * exists so {@code stroom-query-planner} never needs that dependency itself.
 */
public interface MetaStats {

    /**
     * @param feedName    never null; the feed name as it would appear in a {@code from} clause.
     * @param fromTimeMs  the inclusive lower bound of the query's time range, or null if unbounded below.
     * @param toTimeMs    the exclusive upper bound of the query's time range, or null if unbounded above.
     * @return empty if {@code feedName} is not a known feed; otherwise the estimated matching row count.
     */
    Optional<RowCountSignal> estimate(String feedName, @Nullable Long fromTimeMs, @Nullable Long toTimeMs);
}
