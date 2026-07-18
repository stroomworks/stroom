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

import stroom.query.planner.port.RowCountSignal;
import stroom.query.planner.port.StateStoreStats;

import java.util.Map;
import java.util.Optional;

/** A fake {@link StateStoreStats} for {@link TestCostModel}, backed by a fixed name-&gt;row-count map. */
final class FakeStateStoreStats implements StateStoreStats {

    private final Map<String, Long> rowsByStoreName;

    FakeStateStoreStats(final Map<String, Long> rowsByStoreName) {
        this.rowsByStoreName = rowsByStoreName;
    }

    @Override
    public Optional<RowCountSignal> estimate(final String storeName) {
        final Long rows = rowsByStoreName.get(storeName);
        return rows == null ? Optional.empty() : Optional.of(new RowCountSignal(rows));
    }
}
