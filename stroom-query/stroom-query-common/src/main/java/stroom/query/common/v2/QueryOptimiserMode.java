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

package stroom.query.common.v2;

/**
 * The three states {@code stroom.query.optimiser.mode} can be in - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 5.4.
 */
public enum QueryOptimiserMode {

    /** Legacy {@code SearchRequestFactory} compiles and serves every query. The optimiser never runs. */
    OFF,

    /**
     * Legacy compiles and serves every query, unchanged - identical to {@link #OFF} from the caller's point of
     * view. The optimiser <b>also</b> compiles the same query, best-effort and fail-open, purely to log any
     * divergence from legacy's output and (Task 5.5) an actual-vs-estimated duration comparison. Zero risk to
     * served results: a bug in the optimiser or the shadow-compare logic itself can never affect what's returned.
     */
    SHADOW,

    /** The optimiser compiles and serves every query. Legacy never runs. */
    ON
}
