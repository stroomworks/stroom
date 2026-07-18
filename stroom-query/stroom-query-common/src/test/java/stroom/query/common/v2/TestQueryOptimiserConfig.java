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

import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code stroom.query.optimiser.mode} feature flag (see
 * {@code docs/query-optimiser-implementation-plan.md}, Tasks 0.3 and 5.4) defaults to {@link
 * QueryOptimiserMode#OFF}, both as a bare object and when nested under {@link QueryConfig} the way
 * {@code AppConfig} nests it.
 */
class TestQueryOptimiserConfig {

    @Test
    void defaultConstructor_isOff() {
        assertThat(new QueryOptimiserConfig().getMode()).isEqualTo(QueryOptimiserMode.OFF);
    }

    @Test
    void queryConfigDefaultConstructor_nestsAnOffOptimiser() {
        assertThat(new QueryConfig().getOptimiserConfig().getMode()).isEqualTo(QueryOptimiserMode.OFF);
    }

    @Test
    void jsonDeserialisation_missingMode_defaultsToOff() {
        final QueryOptimiserConfig config = JsonUtil.readValue("{}", QueryOptimiserConfig.class);
        assertThat(config.getMode()).isEqualTo(QueryOptimiserMode.OFF);
    }

    @Test
    void jsonDeserialisation_explicitShadow_isRespected() {
        final QueryOptimiserConfig config = JsonUtil.readValue("{\"mode\":\"SHADOW\"}", QueryOptimiserConfig.class);
        assertThat(config.getMode()).isEqualTo(QueryOptimiserMode.SHADOW);
    }

    @Test
    void jsonDeserialisation_explicitOn_isRespected() {
        final QueryOptimiserConfig config = JsonUtil.readValue("{\"mode\":\"ON\"}", QueryOptimiserConfig.class);
        assertThat(config.getMode()).isEqualTo(QueryOptimiserMode.ON);
    }

    @Test
    void jsonSerialisation_roundTrips() {
        final QueryOptimiserConfig config = JsonUtil.readValue(
                JsonUtil.writeValueAsString(new QueryOptimiserConfig()), QueryOptimiserConfig.class);
        assertThat(config.getMode()).isEqualTo(QueryOptimiserMode.OFF);
    }
}
