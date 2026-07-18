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

import java.util.Objects;

/**
 * {@link CostModel}'s result for one {@code Scan}: which {@link AccessPath} it chose, and the
 * {@link CostEstimate} for that choice.
 *
 * @param accessPath never null.
 * @param estimate   never null.
 */
public record CostedAccessPath(AccessPath accessPath, CostEstimate estimate) {

    public CostedAccessPath {
        Objects.requireNonNull(accessPath, "accessPath");
        Objects.requireNonNull(estimate, "estimate");
    }
}
