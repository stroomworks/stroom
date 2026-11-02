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

package stroom.shapeshifter.ai.stage;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.shared.Binding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 06 A130: the learning job's sweep, per learner. A stream that waited against a transform no
/// longer in force is not waiting on anything, so its pipeline is woken; what it reads is each learner's
/// stamps, and what it wakes is each pipeline once, however many streams and learners wait on it.
class TestTheSweepWakesEachPipelineOnce {

    private static final String DOC = "doc-1";
    private static final DocRef FRAGMENT = DocRef.builder().type("Pipeline").uuid("fragment-1").name("f").build();

    @Test
    void eachPipelineWithAStreamWaitingOnAReplacedTransformIsWokenOnce() {
        final Scenarios scenarios = new Scenarios();
        // Two learners on one pipeline, both served by a transform that has moved on from the one their
        // streams waited against: one pipeline to wake, not one per learner, nor one per stream.
        final Learner first = new Learner("pipeline-1", "shapeshifterAi");
        final Learner second = new Learner("pipeline-1", "anotherShapeshifterAi");
        bind(scenarios, first, "rule-1", "v2", false);
        bind(scenarios, second, "rule-2", "v2", false);
        waitOn(scenarios, first, 1L, "v1");
        waitOn(scenarios, first, 2L, "v1");
        waitOn(scenarios, second, 3L, "v1");
        // Waiting against the transform still in force: it is waiting on something, and is left.
        final Learner current = new Learner("pipeline-2", "shapeshifterAi");
        bind(scenarios, current, "rule-3", "v2", false);
        waitOn(scenarios, current, 4L, "v2");
        // Behind a draft: it waits for the review, as its streams do.
        final Learner reviewed = new Learner("pipeline-3", "shapeshifterAi");
        bind(scenarios, reviewed, "rule-4", "v2", true);
        waitOn(scenarios, reviewed, 5L, "v1");
        // Nothing serves it: there is no transform to have replaced the one it waited against.
        waitOn(scenarios, new Learner("pipeline-4", "shapeshifterAi"), 6L, "v1");

        final int woken = scenarios.stage(Script.of()).wakeSuperseded();

        assertThat(scenarios.wake.pipelines()).describedAs("one wake, of the one pipeline whose streams "
                                                           + "waited on a replaced transform")
                .containsExactly("pipeline-1");
        assertThat(woken).describedAs("its three streams, across both its learners").isEqualTo(3);
    }

    @Test
    void nothingWaitingWakesNothing() {
        final Scenarios scenarios = new Scenarios();

        assertThat(scenarios.stage(Script.of()).wakeSuperseded()).isZero();
        assertThat(scenarios.wake.pipelines()).isEmpty();
    }

    private static void bind(final Scenarios scenarios,
                             final Learner learner,
                             final String bindingUuid,
                             final String snapshot,
                             final boolean draft) {
        scenarios.learnerBindings.append(learner, DOC, Binding.builder()
                .uuid(bindingUuid)
                .pipeline(FRAGMENT)
                .snapshot(snapshot)
                .draft(draft)
                .build());
    }

    private static void waitOn(final Scenarios scenarios,
                               final Learner learner,
                               final long inputId,
                               final String failedAgainst) {
        scenarios.deferrals.deferred(learner, DOC, inputId, "DOOR-ACCESS", "Raw Events",
                failedAgainst, "Fell short of the bar", 1);
    }
}
