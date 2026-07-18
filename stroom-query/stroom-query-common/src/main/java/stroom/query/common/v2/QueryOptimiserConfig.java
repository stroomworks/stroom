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
 * (see {@code docs/query-optimiser-implementation-plan.md}, Task 0.3). Resolves to the property path
 * {@code stroom.query.optimiser.enabled}. Defaults to {@code false}: the legacy {@code SearchRequestFactory}
 * compiler is used until this is explicitly enabled.
 */
@JsonPropertyOrder(alphabetic = true)
public class QueryOptimiserConfig extends AbstractConfig implements IsStroomConfig {

    private static final boolean DEFAULT_ENABLED = false;

    private final boolean enabled;

    public QueryOptimiserConfig() {
        enabled = DEFAULT_ENABLED;
    }

    @JsonCreator
    public QueryOptimiserConfig(@JsonProperty("enabled") final Boolean enabled) {
        this.enabled = Objects.requireNonNullElse(enabled, DEFAULT_ENABLED);
    }

    @JsonProperty("enabled")
    @JsonPropertyDescription("Route StroomQL through the experimental grammar+optimiser compiler instead of the " +
                             "legacy factory. Default false.")
    public boolean isEnabled() {
        return enabled;
    }
}
