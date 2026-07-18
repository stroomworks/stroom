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

import stroom.query.planner.port.RowCountSignal;
import stroom.query.planner.port.StateStoreStats;

import jakarta.inject.Inject;

import java.util.Optional;

/**
 * A placeholder {@link StateStoreStats} that always answers empty - see {@link NoOpIndexShardStats}'s Javadoc
 * for why this exists and when it should be removed (the same reasoning applies, for {@code stroom-planb-impl}
 * instead of {@code stroom-index-impl}).
 */
public final class NoOpStateStoreStats implements StateStoreStats {

    @Inject
    public NoOpStateStoreStats() {
    }

    @Override
    public Optional<RowCountSignal> estimate(final String storeName) {
        return Optional.empty();
    }
}
