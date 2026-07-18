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

/**
 * The physical join algorithm {@link JoinCostModel} chose (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 3.3). Exercised for real in Phase 6 - this task
 * only costs and chooses, it doesn't execute anything.
 */
public enum JoinAlgorithm {
    /** Probe the point-lookup-capable side (a Plan B/State store - see {@link StateLookup}) once per row of
     *  the other side. */
    BROADCAST_LOOKUP,
    /** Materialise the smaller side into a hash table, probe with the larger. */
    HASH_JOIN,
    /** Fallback when neither side has a usable cost signal to compare. */
    NESTED_LOOP
}
