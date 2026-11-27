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

/// Tests that the background worker resumes a waiting run in the question text the run was opened with.
///
/// A session is a conversation. Every earlier turn was asked in the templates the run opened with, and the model
/// is shown that history when the run resumes. Rendering the history from templates someone edited while the
/// attempt waited, or from built-in text an upgrade has changed, would show the model a conversation it never
/// had.
class TestAParkedRunResumesInItsOwnWords {

    private static final String DOC = "doc-1";

    @Test
    void theWorkerAsksInTheWordsTheRunWasOpenedWith() {
        final Scenarios scenarios = new Scenarios();
        final AskedWith opened = new AskedWith(9,
                Map.of(Template.CHAIN, "Pick one:${elements}${sample}${feedback}"),
                "Badge readers.", "7.13-beta.3");
        parked(scenarios, opened);

        // The Learning Flow is edited while the attempt waits. The worker must not pick up the edit.
        scenarios.documents.put(doc("Turnstiles now."));
        final Capturing advisors = new Capturing();
        worker(scenarios, advisors).advance(10);

        assertThat(advisors.asked)
                .describedAs("the worker handed the resume the run's own words, not the document's")
                .containsExactly(opened);
    }

    @Test
    void aRunOpenedBeforeItsWordsWereKeptIsAskedFromTheFlow() {
        // An attempt opened by an earlier version recorded no question text, so the Learning Flow as it now
        // stands is the only honest choice.
        final Scenarios scenarios = new Scenarios();
        parked(scenarios, AskedWith.UNKNOWN);

        final Capturing advisors = new Capturing();
        worker(scenarios, advisors).advance(10);

        assertThat(advisors.asked).containsExactly(AskedWith.UNKNOWN);
    }

    /// Opens an attempt waiting for the model, as a processing task leaves one for the background worker,
    /// recording the question text it was opened with.
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
                scenarios.documents, scenarios.inputs, StreamHeld.NONE, scenarios.deletedPipelines, advisors,
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


    /// An advisor source that records which question text it was asked to use, and answers nothing, because
    /// these tests are about what the worker hands over, not the conversation.
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
