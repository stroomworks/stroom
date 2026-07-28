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

package stroom.graphdb.impl;

import stroom.util.time.StroomDuration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the path from configuration to the ceilings a traversal actually enforces.
 *
 * <p>This is the test that stops the guardrails becoming the next inert setting. Temporal Precision was editable,
 * persisted and read by nothing for a whole release; the failure was invisible because nothing asserted that a
 * configured value reached the code that would use it. These assertions are deliberately about plumbing rather
 * than about traversal behaviour - the ceilings' behaviour is covered by {@code TestGraphTraversalEngine}'s own
 * seam-constructor tests, which must keep using the defaults so they stay independent of a deployment's
 * settings.</p>
 */
class TestGraphTraversalLimits {

    /**
     * Every configured value must arrive, and arrive in the right field. Distinct values throughout, because five
     * same-typed numbers are exactly the shape where a transposed pair passes a weaker test.
     */
    @Test
    void from_carriesEveryConfiguredValueIntoTheRightField() {
        final GraphDbConfig config = new GraphDbConfig(
                "graphdb",
                List.of(),
                1L,
                11,
                22L,
                StroomDuration.ofSeconds(33),
                44L,
                55);

        final GraphTraversalLimits limits = GraphTraversalLimits.from(config);

        assertThat(limits.maxVarLengthHops()).isEqualTo(11);
        assertThat(limits.maxVarLengthPathStates()).isEqualTo(22L);
        assertThat(limits.maxTraversalDuration()).isEqualTo(Duration.ofSeconds(33));
        assertThat(limits.maxAccumulatedRows()).isEqualTo(44L);
        assertThat(limits.wholeGraphNodeCap()).isEqualTo(55);
    }

    /**
     * The defaults must equal what the engine's constants held before they moved, or promoting them to
     * configuration would silently change every existing deployment's behaviour.
     */
    @Test
    void defaults_matchTheHistoricalHardCodedCeilings() {
        final GraphTraversalLimits limits = GraphTraversalLimits.defaults();

        assertThat(limits.maxVarLengthHops()).isEqualTo(50);
        assertThat(limits.maxVarLengthPathStates()).isEqualTo(200_000L);
        assertThat(limits.maxTraversalDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(limits.maxAccumulatedRows()).isEqualTo(1_000_000L);
        assertThat(limits.wholeGraphNodeCap()).isEqualTo(100);
    }

    /**
     * An unconfigured deployment must behave exactly as it did before the settings existed.
     */
    @Test
    void anUnconfiguredDeployment_getsTheDefaults() {
        assertThat(GraphTraversalLimits.from(new GraphDbConfig()))
                .isEqualTo(GraphTraversalLimits.defaults());
    }

    /**
     * Each wither changes one field and leaves the rest alone - the property the engine's four test-only
     * constructors now depend on to keep their historical meaning.
     */
    @Test
    void eachWither_changesOnlyItsOwnField() {
        final GraphTraversalLimits base = GraphTraversalLimits.defaults();

        assertThat(base.withMaxVarLengthPathStates(7L))
                .isEqualTo(new GraphTraversalLimits(50, 7L, Duration.ofSeconds(30), 1_000_000L, 100));
        assertThat(base.withMaxTraversalDuration(Duration.ZERO))
                .isEqualTo(new GraphTraversalLimits(50, 200_000L, Duration.ZERO, 1_000_000L, 100));
        assertThat(base.withMaxAccumulatedRows(7L))
                .isEqualTo(new GraphTraversalLimits(50, 200_000L, Duration.ofSeconds(30), 7L, 100));
        assertThat(base.withWholeGraphNodeCap(7))
                .isEqualTo(new GraphTraversalLimits(50, 200_000L, Duration.ofSeconds(30), 1_000_000L, 7));
    }

    /**
     * The store size is not a traversal limit, so it is not on the record - but it must still be readable, and
     * default to the 10GiB the storage layer previously hard-coded.
     */
    @Test
    void maxStoreSize_defaultsToTenGibibytes() {
        assertThat(new GraphDbConfig().getMaxStoreSize()).isEqualTo(10L * 1024 * 1024 * 1024);
    }
}
