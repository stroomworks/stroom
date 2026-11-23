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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.SupervisorLearner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFeedFilter {

    @Test
    void everyFeedOfPipelinesThatStillExistIsNotFiltering() {
        assertThat(FeedFilter.ALL.isFiltering(false)).isFalse();
    }

    @Test
    void deletedPipelinesLetInIsFiltering() {
        assertThat(FeedFilter.ALL.isFiltering(true)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FeedFilter.class, names = "ALL", mode = EnumSource.Mode.EXCLUDE)
    void anyNarrowerChoiceIsFiltering(final FeedFilter filter) {
        assertThat(filter.isFiltering(false)).isTrue();
        assertThat(filter.isFiltering(true)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(LearnerState.class)
    void allListsEveryState(final LearnerState state) {
        assertThat(FeedFilter.ALL.test(learner(state))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(LearnerState.class)
    void eachStateIsListedByExactlyOneNarrowerChoice(final LearnerState state) {
        final List<FeedFilter> listing = Arrays.stream(FeedFilter.values())
                .filter(filter -> filter != FeedFilter.ALL)
                .filter(filter -> filter.test(learner(state)))
                .toList();
        assertThat(listing).hasSize(1);
    }

    @Test
    void eachChoiceListsTheStatesItNames() {
        assertThat(FeedFilter.NEEDS_YOU.test(learner(LearnerState.AWAITING_PERSON))).isTrue();
        assertThat(FeedFilter.LEARNING.test(learner(LearnerState.LEARNING))).isTrue();
        assertThat(FeedFilter.TRANSLATING.test(learner(LearnerState.SERVING))).isTrue();
        assertThat(FeedFilter.STOPPED.test(learner(LearnerState.GIVEN_UP))).isTrue();
        assertThat(FeedFilter.STOPPED.test(learner(LearnerState.LEARNING_OFF))).isTrue();
        assertThat(FeedFilter.TRANSLATING.test(learner(LearnerState.LEARNING))).isFalse();
    }

    private static SupervisorLearner learner(final LearnerState state) {
        return new SupervisorLearner(new LearnerRef("p-1", "learningFlow"),
                new DocRef("Pipeline", "p-1", "Firewall pipeline"), null, state, null, null, null, null,
                null, 0, 0, null, false, null, 0, false, false);
    }
}
