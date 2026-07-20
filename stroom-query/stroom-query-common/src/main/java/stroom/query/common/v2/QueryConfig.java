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

import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Root configuration node for query compilation, nested into {@code AppConfig} under the property path
 * {@code stroom.query} (see {@code docs/query-optimiser-implementation-plan.md}, Task 0.3).
 *
 * <p>Both nested config objects are always non-null: the no-arg constructor and the {@link JsonCreator}
 * constructor below each default a null/absent nested section to that section's own no-arg default (see
 * {@link QueryOptimiserConfig#QueryOptimiserConfig()}, {@link JoinConfig#JoinConfig()}).</p>
 */
@JsonPropertyOrder(alphabetic = true)
public class QueryConfig extends AbstractConfig implements IsStroomConfig {

    private final QueryOptimiserConfig optimiserConfig;
    private final JoinConfig joinConfig;

    public QueryConfig() {
        optimiserConfig = new QueryOptimiserConfig();
        joinConfig = new JoinConfig();
    }

    /**
     * @param optimiserConfig nullable; a null value is replaced with {@code new QueryOptimiserConfig()} so
     *                         {@link #getOptimiserConfig()} is never null.
     * @param joinConfig       nullable; a null value is replaced with {@code new JoinConfig()} so
     *                         {@link #getJoinConfig()} is never null - see {@code
     *                         docs/join-scalability-implementation-plan.md}, decision D1.
     */
    @JsonCreator
    public QueryConfig(
            @JsonProperty("optimiser") final QueryOptimiserConfig optimiserConfig,
            @JsonProperty("join") final JoinConfig joinConfig) {
        this.optimiserConfig = optimiserConfig == null ? new QueryOptimiserConfig() : optimiserConfig;
        this.joinConfig = joinConfig == null ? new JoinConfig() : joinConfig;
    }

    @JsonProperty("optimiser")
    public QueryOptimiserConfig getOptimiserConfig() {
        return optimiserConfig;
    }

    /**
     * Join memory guardrails - see {@code docs/join-scalability-implementation-plan.md}, decision D1.
     *
     * @return never null.
     */
    @JsonProperty("join")
    public JoinConfig getJoinConfig() {
        return joinConfig;
    }
}
