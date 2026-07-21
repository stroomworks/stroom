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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code stroom.query.join} memory guardrails (see
 * {@code docs/join-scalability-implementation-plan.md}, decision D1) default correctly, nest correctly under
 * {@link QueryConfig}, round-trip through JSON, and reject a negative configured value.
 */
class TestJoinConfig {

    @Test
    void defaultConstructor_usesDocumentedDefaults() {
        final JoinConfig config = new JoinConfig();
        assertThat(config.getMaxSideRows()).isEqualTo(JoinConfig.DEFAULT_MAX_SIDE_ROWS);
        assertThat(config.getMaxOutputRows()).isEqualTo(JoinConfig.DEFAULT_MAX_OUTPUT_ROWS);
        assertThat(config.getMaxHeapBuildRows()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_ROWS);
        assertThat(config.getMaxHeapBuildBytes()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_BYTES);
    }

    @Test
    void defaults_areSanelyOrdered() {
        // The heap threshold must sit below the absolute side ceiling, or spilling could never fire.
        assertThat(JoinConfig.DEFAULT_MAX_HEAP_BUILD_ROWS).isLessThanOrEqualTo(JoinConfig.DEFAULT_MAX_SIDE_ROWS);
    }

    @Test
    void queryConfigDefaultConstructor_nestsDefaultJoinConfig() {
        final JoinConfig joinConfig = new QueryConfig().getJoinConfig();
        assertThat(joinConfig.getMaxSideRows()).isEqualTo(JoinConfig.DEFAULT_MAX_SIDE_ROWS);
        assertThat(joinConfig.getMaxOutputRows()).isEqualTo(JoinConfig.DEFAULT_MAX_OUTPUT_ROWS);
        assertThat(joinConfig.getMaxHeapBuildRows()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_ROWS);
        assertThat(joinConfig.getMaxHeapBuildBytes()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_BYTES);
    }

    @Test
    void jsonCreator_allNull_fallsBackToDefaults() {
        final JoinConfig config = new JoinConfig(null, null, null, null);
        assertThat(config.getMaxSideRows()).isEqualTo(JoinConfig.DEFAULT_MAX_SIDE_ROWS);
        assertThat(config.getMaxOutputRows()).isEqualTo(JoinConfig.DEFAULT_MAX_OUTPUT_ROWS);
        assertThat(config.getMaxHeapBuildRows()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_ROWS);
        assertThat(config.getMaxHeapBuildBytes()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_BYTES);
    }

    @Test
    void jsonCreator_explicitValues_areRespectedIndependently() {
        final JoinConfig config = new JoinConfig(42L, null, 7L, 99L);
        assertThat(config.getMaxSideRows()).isEqualTo(42L);
        assertThat(config.getMaxOutputRows()).isEqualTo(JoinConfig.DEFAULT_MAX_OUTPUT_ROWS);
        assertThat(config.getMaxHeapBuildRows()).isEqualTo(7L);
        assertThat(config.getMaxHeapBuildBytes()).isEqualTo(99L);
    }

    @Test
    void jsonCreator_zero_isAcceptedAndMeansDisabled() {
        final JoinConfig config = new JoinConfig(0L, 0L, 0L, 0L);
        assertThat(config.getMaxSideRows()).isZero();
        assertThat(config.getMaxOutputRows()).isZero();
        assertThat(config.getMaxHeapBuildRows()).isZero();
        assertThat(config.getMaxHeapBuildBytes()).isZero();
    }

    @Test
    void jsonCreator_negativeMaxSideRows_throws() {
        assertThatThrownBy(() -> new JoinConfig(-1L, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSideRows");
    }

    @Test
    void jsonCreator_negativeMaxOutputRows_throws() {
        assertThatThrownBy(() -> new JoinConfig(null, -1L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutputRows");
    }

    @Test
    void jsonCreator_negativeMaxHeapBuildRows_throws() {
        assertThatThrownBy(() -> new JoinConfig(null, null, -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHeapBuildRows");
    }

    @Test
    void jsonCreator_negativeMaxHeapBuildBytes_throws() {
        assertThatThrownBy(() -> new JoinConfig(null, null, null, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxHeapBuildBytes");
    }

    @Test
    void jsonDeserialisation_missingSection_defaultsAll() {
        final JoinConfig config = JsonUtil.readValue("{}", JoinConfig.class);
        assertThat(config.getMaxSideRows()).isEqualTo(JoinConfig.DEFAULT_MAX_SIDE_ROWS);
        assertThat(config.getMaxOutputRows()).isEqualTo(JoinConfig.DEFAULT_MAX_OUTPUT_ROWS);
        assertThat(config.getMaxHeapBuildRows()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_ROWS);
        assertThat(config.getMaxHeapBuildBytes()).isEqualTo(JoinConfig.DEFAULT_MAX_HEAP_BUILD_BYTES);
    }

    @Test
    void jsonDeserialisation_explicitValues_areRespected() {
        final JoinConfig config = JsonUtil.readValue(
                "{\"maxSideRows\":10,\"maxOutputRows\":20,\"maxHeapBuildRows\":5,\"maxHeapBuildBytes\":99}",
                JoinConfig.class);
        assertThat(config.getMaxSideRows()).isEqualTo(10L);
        assertThat(config.getMaxOutputRows()).isEqualTo(20L);
        assertThat(config.getMaxHeapBuildRows()).isEqualTo(5L);
        assertThat(config.getMaxHeapBuildBytes()).isEqualTo(99L);
    }

    @Test
    void jsonSerialisation_roundTrips() {
        final JoinConfig original = new JoinConfig(10L, 20L, 5L, 99L);
        final JoinConfig roundTripped = JsonUtil.readValue(
                JsonUtil.writeValueAsString(original), JoinConfig.class);
        assertThat(roundTripped.getMaxSideRows()).isEqualTo(10L);
        assertThat(roundTripped.getMaxOutputRows()).isEqualTo(20L);
        assertThat(roundTripped.getMaxHeapBuildRows()).isEqualTo(5L);
        assertThat(roundTripped.getMaxHeapBuildBytes()).isEqualTo(99L);
    }
}
