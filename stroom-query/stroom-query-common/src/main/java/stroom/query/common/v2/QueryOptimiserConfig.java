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
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Feature flag for the grammar-driven parser and cost-based query optimiser
 * (Tasks 0.3 and 5.4). Resolves to the property path
 * {@code stroom.query.optimiser.mode}. Defaults to {@link QueryOptimiserMode#OFF}: the legacy
 * {@code SearchRequestFactory} compiler serves every query until this is explicitly changed. No shipped YAML has
 * ever set this (it has only ever run at this Java-side default), so the {@code enabled} (boolean) property name
 * used before Task 5.4 was renamed to {@code mode} (three-value) rather than overloading a boolean-shaped name -
 * there was no deployed config to migrate.
 */
@JsonPropertyOrder(alphabetic = true)
public class QueryOptimiserConfig extends AbstractConfig implements IsStroomConfig {

    private static final QueryOptimiserMode DEFAULT_MODE = QueryOptimiserMode.OFF;

    private final QueryOptimiserMode mode;

    public QueryOptimiserConfig() {
        mode = DEFAULT_MODE;
    }

    @JsonCreator
    public QueryOptimiserConfig(@JsonProperty("mode") final QueryOptimiserMode mode) {
        this.mode = Objects.requireNonNullElse(mode, DEFAULT_MODE);
    }

    @JsonProperty("mode")
    @JsonPropertyDescription("Controls how StroomQL is compiled. OFF (default): the legacy factory compiles and " +
                             "serves every query, the optimiser never runs. SHADOW: legacy compiles and serves " +
                             "every query exactly as in OFF, but the optimiser also compiles the same query, " +
                             "best-effort, purely to log any divergence and an actual-vs-estimated duration " +
                             "comparison - zero risk to served results, but NOT free: it adds a second compile, " +
                             "two whole-request JSON serialisations and a cost estimate that queries the meta " +
                             "store, all synchronously on the thread submitting the search, so expect added " +
                             "submission latency and meta-store load on a busy cluster. ON: the optimiser " +
                             "compiles and serves every query, legacy never runs.")
    public QueryOptimiserMode getMode() {
        return mode;
    }
}
