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

package stroom.learningflow.stage;

import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.Template;
import stroom.learningflow.stage.Attempts.Attempt;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Decision N9, option D extended: the worker carries a parked run on **in the words it began in**.
///
/// A session is a conversation. Every turn before the one being put was asked in the templates the run
/// opened with, and the model is shown its own history as it resumes. Rendering that history from
/// templates somebody edited while the attempt was parked — or from built-ins an upgrade has moved
/// under it — shows the model a conversation it never had.
class TestAParkedRunResumesInItsOwnWords {

    private static final String DOC = "doc-1";

    @Test
    void theWorkerAsksInTheWordsTheRunWasOpenedWith() {
        final Scenarios scenarios = new Scenarios();
        final AskedWith opened = new AskedWith(9,
                Map.of(Template.CHAIN, "Pick one:${elements}${sample}${feedback}"),
                "Badge readers.", "7.13-beta.3");
        parked(scenarios, opened);

        // The Learning Flow is edited while the attempt waits. What the worker asks with must not move
        // with it.
        scenarios.documents.put(doc("Turnstiles now."));
        final Capturing advisors = new Capturing();
        worker(scenarios, advisors).advance(10);

        assertThat(advisors.asked)
                .describedAs("the worker handed the resume the run's own words, not the document's")
                .containsExactly(opened);
    }

    @Test
    void aRunOpenedBeforeItsWordsWereKeptIsAskedFromTheFlow() {
        // Every attempt on an instance upgraded into N9: there is nothing recorded to resume from, so the
        // Learning Flow as it stands is the only honest answer.
        final Scenarios scenarios = new Scenarios();
        parked(scenarios, AskedWith.UNKNOWN);

        final Capturing advisors = new Capturing();
        worker(scenarios, advisors).advance(10);

        assertThat(advisors.asked).containsExactly(AskedWith.UNKNOWN);
    }

    /// An attempt waiting for the model, as a processing task leaves one (A48, A60), with the words it
    /// was opened with against it.
    private static void parked(final Scenarios scenarios, final AskedWith askedWith) {
        scenarios.documents.put(doc("Badge readers."));
        scenarios.inputs.put(new Input(1L, "DOOR-ACCESS", "Raw Events", Map.of(), "a,b,c\n"));
        final long id = scenarios.attempts.opened(new Attempt(Scenarios.LEARNER, DOC, "DOOR-ACCESS",
                "Raw Events", 1L, "node-1", PromotionMode.AUTOMATIC, Long.MAX_VALUE, askedWith),
                Scenarios.NOW.toEpochMilli()).orElseThrow();
        scenarios.attempts.parked(id, AttemptStatus.AWAITING_MODEL, Long.MAX_VALUE, 0L);
    }

    private static DeferredWorker worker(final Scenarios scenarios, final Advisors advisors) {
        return new DeferredWorker(scenarios.stage(advisors, scenarios.learnerBindings), scenarios.attempts,
                scenarios.documents, scenarios.inputs, scenarios.deletedPipelines, advisors,
                Clock.fixed(Scenarios.NOW, ZoneOffset.UTC));
    }

    private static LearningFlowDoc doc(final String instructions) {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .instructions(instructions)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .build();
    }


    // --------------------------------------------------------------------------------


    /// An advisor source that records what it was asked to ask with, and answers nothing: the point of
    /// these tests is the hand-off, not the conversation.
    private static final class Capturing implements Advisors {

        private final List<AskedWith> asked = new ArrayList<>();
        private final Advisor advisor = Script.of();

        @Override
        public Advisor of(final LearningFlowDoc doc) {
            asked.add(AskedWith.UNKNOWN);
            return advisor;
        }

        @Override
        public Advisor of(final LearningFlowDoc doc, final AskedWith askedWith) {
            asked.add(askedWith);
            return advisor;
        }
    }
}
