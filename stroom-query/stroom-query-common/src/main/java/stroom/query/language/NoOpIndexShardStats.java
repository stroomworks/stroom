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

package stroom.query.language;

import stroom.query.planner.port.IndexCostSignal;
import stroom.query.planner.port.IndexShardStats;

import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A placeholder {@link IndexShardStats} that always answers empty - exists solely so
 * {@link OptimisingQueryCompiler}'s {@code CostModel} has something to inject (see
 * Task 3.1: a real adapter can't live in
 * {@code stroom-query-common}/{@code stroom-query-planner} without closing a dependency cycle back through
 * {@code stroom-index-impl} - it must live inside that module instead). Every index-backed {@code Scan} shows
 * {@code confidence=0.0} and a "no cost signal available" note in {@code explain} output until the real
 * adapter lands there and replaces this binding in {@code QueryModule}.
 */
public final class NoOpIndexShardStats implements IndexShardStats {

    @Inject
    public NoOpIndexShardStats() {
    }

    @Override
    public Optional<IndexCostSignal> estimate(
            final String indexName, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs) {
        return Optional.empty();
    }
}
