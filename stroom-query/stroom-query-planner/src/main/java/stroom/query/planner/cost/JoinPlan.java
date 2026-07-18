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
 * {@link JoinCostModel#chooseAlgorithm}'s result: which algorithm, and which side is the build side (the one
 * materialised for {@link JoinAlgorithm#HASH_JOIN}, or the point-lookup-capable one for
 * {@link JoinAlgorithm#BROADCAST_LOOKUP}).
 *
 * @param algorithm never null.
 * @param buildSide never null. Arbitrary (always {@link JoinSide#LEFT}) when {@code algorithm} is
 *                  {@link JoinAlgorithm#NESTED_LOOP}, which has no build side.
 */
public record JoinPlan(JoinAlgorithm algorithm, JoinSide buildSide) {

    public JoinPlan {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(buildSide, "buildSide");
    }
}
