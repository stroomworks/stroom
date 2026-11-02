/*
 * Copyright 2026 Crown Copyright
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


package stroom.pipelinefactory.client.component;

import stroom.docref.DocRef;
import stroom.pipelinefactory.shared.PipelineFactoryCombination;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestCombinationText {

    private static final DocRef PIPELINE = new DocRef("Pipeline", "uuid-1", "FEED_A Events");

    @Test
    void testDescribeJoinsValuesInKeyOrder() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("Feed", "FEED_A");
        values.put("Type", "Events");
        assertThat(CombinationText.describe(combination(values, PipelineFactoryCombination.MADE, true)))
                .isEqualTo("FEED_A / Events");
    }

    @Test
    void testDescribeNoValues() {
        assertThat(CombinationText.describe(combination(null, PipelineFactoryCombination.PENDING, false)))
                .isEmpty();
    }

    @Test
    void testStateMade() {
        assertThat(CombinationText.state(combination(Map.of(), PipelineFactoryCombination.MADE, true)))
                .isEqualTo("Made");
    }

    @Test
    void testStateMadeThenDeleted() {
        assertThat(CombinationText.state(combination(Map.of(), PipelineFactoryCombination.MADE, false)))
                .isEqualTo("Made, then deleted");
    }

    @Test
    void testStateFailed() {
        assertThat(CombinationText.state(combination(Map.of(), PipelineFactoryCombination.FAILED, false)))
                .isEqualTo("Failed");
    }

    @Test
    void testStatePendingOrUnknownIsBeingMade() {
        assertThat(CombinationText.state(combination(Map.of(), PipelineFactoryCombination.PENDING, false)))
                .isEqualTo("Being made");
        assertThat(CombinationText.state(combination(Map.of(), null, false)))
                .isEqualTo("Being made");
    }

    @Test
    void testNewestFirstReversesWithoutChangingTheGivenList() {
        final PipelineFactoryCombination first =
                combination(Map.of("Feed", "A"), PipelineFactoryCombination.MADE, true);
        final PipelineFactoryCombination second =
                combination(Map.of("Feed", "B"), PipelineFactoryCombination.MADE, true);
        final List<PipelineFactoryCombination> given = List.of(first, second);

        assertThat(CombinationText.newestFirst(given)).containsExactly(second, first);
        assertThat(given).containsExactly(first, second);
    }

    @Test
    void testNewestFirstEmpty() {
        assertThat(CombinationText.newestFirst(List.of())).isEmpty();
    }

    private static PipelineFactoryCombination combination(final Map<String, String> values,
                                                          final String state,
                                                          final boolean pipelineExists) {
        return new PipelineFactoryCombination(values, state, 1, PIPELINE, pipelineExists, null, null, 0L, 0L);
    }
}
