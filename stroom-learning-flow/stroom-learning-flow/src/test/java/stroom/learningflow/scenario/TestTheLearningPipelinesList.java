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

package stroom.learningflow.scenario;

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearningPipelines;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Tests the Supervisor's learning pipelines list, which has one row per pipeline's learner showing its
/// state, what serves it, how many streams wait on it, and how much guidance it has been given.
///
/// The list is how a person finds a learner to act on. A binding serving at 0.93 is above every threshold
/// and nothing will ever flag it, so a person who wants it improved finds it here, as they find a learner
/// that has given up or a draft that waits for them.
class TestTheLearningPipelinesList {

    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// Two feeds' pipelines, and so two learners.
    private static final Learner DOOR = Scenarios.LEARNER;
    private static final Learner LIFT = new Learner("pipeline-lift", Scenarios.LEARNER.elementId());

    /// What the stage learns is what the list shows. A binding promoted by a stream appears in the list
    /// without anything else being written, because the list joins each binding to the learner it serves.
    @Test
    void whatTheStageLearnsIsWhatAPersonBrowses() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();

        final Scenarios.Processed learned = scenarios.process(learn(scenarios), doc, stream("DOOR-ACCESS", 1L));
        assertThat(learned.served()).describedAs("the stream it was learned on, served once the wake came")
                .isNotNull();

        final LearningPipelines.Page page = scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, 0L, 100);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.learners())
                .extracting(SupervisorLearner::getLearner, SupervisorLearner::getRecords)
                .describedAs("the promotion resets the learner, so its rolling memory starts from the streams it "
                             + "serves: the first is the one it was learned on, which the wake serves")
                .containsExactly(tuple(new LearnerRef(DOOR.pipelineUuid(), DOOR.elementId()),
                        learned.served().records()));
        assertThat(page.learners().get(0).getRollingScore())
                .describedAs("and a rolling score, from that stream").isNotNull();
        assertThat(page.learners().get(0).getPromotedScore())
                .describedAs("what it scored when it took over is what a rolling score is read against")
                .isNotNull();
        assertThat(page.learners().get(0).getFragment()).isNotNull();
        assertThat(page.learners().get(0).getState()).isEqualTo(LearnerState.SERVING);
    }

    /// Asking for one pipeline's learners, as that pipeline's editor does, leaves out other pipelines'
    /// learners, and the total counts that pipeline's learners alone.
    @Test
    void onePipelinesLearnersAreAskedForAlone() {
        final Scenarios scenarios = new Scenarios();
        scenarios.learnerBindings.append(DOOR, "doc-1", binding("door"));
        scenarios.deferrals.deferred(LIFT, "doc-1", 1L, "LIFT", "Raw Events", null, "Learning.", 0);

        final LearningPipelines.Page lift = scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, LIFT.pipelineUuid(), 0L, 100);

        assertThat(lift.total()).isEqualTo(1L);
        assertThat(lift.learners()).extracting(row -> row.getLearner().getPipelineUuid())
                .containsExactly(LIFT.pipelineUuid());
        assertThat(scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, "pipeline-none", 0L, 100)
                .learners()).isEmpty();
    }

    /// The list is ordered with the most waiting streams first: the learner holding back the most streams is
    /// the one a person should look at first, whatever its score.
    @Test
    void theLearnerWithTheMostWaitingComesFirst() {
        final Scenarios scenarios = new Scenarios();
        scenarios.learnerBindings.append(DOOR, "doc-1", binding("busy"));
        scenarios.states.scored(DOOR, "doc-1", 0.93, 40, 100);
        scenarios.deferrals.deferred(LIFT, "doc-1", 1L, "LIFT", "Raw Events", null, "Learning.", 0);
        scenarios.deferrals.deferred(LIFT, "doc-1", 2L, "LIFT", "Raw Events", null, "Learning.", 0);

        assertThat(scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, 0L, 100)
                .learners())
                .extracting(row -> row.getLearner().getPipelineUuid(), SupervisorLearner::getState,
                        SupervisorLearner::getWaiting)
                .containsExactly(
                        tuple(LIFT.pipelineUuid(), LearnerState.LEARNING, 2),
                        tuple(DOOR.pipelineUuid(), LearnerState.SERVING, 0));
        assertThat(scenarios.learningPipelines.learners(List.of("doc-1"), Set.of(),
                List.of(LearnerState.SERVING), false, 0L, 100).learners())
                .describedAs("and a state narrows it")
                .extracting(SupervisorLearner::getBindingUuid)
                .containsExactly("busy");
    }

    /// Each row shows how much guidance its learner has been given, so that a person can see whether the last
    /// hint was acted on before giving another.
    @Test
    void aRowSaysHowMuchHasBeenSaidToItsLearner() {
        final Scenarios scenarios = new Scenarios();
        scenarios.learnerBindings.append(DOOR, "doc-1", binding("busy"));
        scenarios.guidance.given(DOOR, "doc-1", "Timestamps are local, not UTC.", "jo");
        scenarios.guidance.given(DOOR, "doc-1", "Column four is a terminal id.", "sam");
        scenarios.guidance.given(LIFT, "doc-1", "Said to another learner.", "jo");

        assertThat(scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, 0L, 100).learners())
                .extracting(SupervisorLearner::getGuidance)
                .containsExactly(2);
    }

    /// A draft awaiting review puts its learner in the waiting-for-a-person state, while the binding the row
    /// shows is the one that serves.
    @Test
    void aDraftMakesItsLearnerWaitForAPerson() {
        final Scenarios scenarios = new Scenarios();
        scenarios.learnerBindings.append(DOOR, "doc-1", binding("serving"));
        scenarios.learnerBindings.append(DOOR, "doc-1", binding("draft").copy().draft(true).build());
        scenarios.states.awaitReview(DOOR, "doc-1", "draft");

        assertThat(scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, 0L, 100)
                .learners())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getState()).isEqualTo(LearnerState.AWAITING_PERSON);
                    assertThat(row.getBindingUuid()).isEqualTo("serving");
                });
    }

    /// The query is limited to the documents a person may read, as it is for attempts. Naming no documents
    /// returns nothing rather than everything.
    @Test
    void aDocumentNobodyNamedIsNotInTheAnswer() {
        final Scenarios scenarios = new Scenarios();
        scenarios.learnerBindings.append(DOOR, "doc-1", binding("ours"));
        scenarios.learnerBindings.append(LIFT, "doc-2", binding("theirs"));

        assertThat(scenarios.learningPipelines
                .learners(List.of("doc-1"), Set.of(), List.of(), false, 0L, 100).learners())
                .extracting(SupervisorLearner::getBindingUuid).containsExactly("ours");
        assertThat(scenarios.learningPipelines
                .learners(List.of(), Set.of(), List.of(), false, 0L, 100).learners()).isEmpty();
    }

    private static Binding binding(final String uuid) {
        return Binding.builder()
                .uuid(uuid)
                .pipeline(stroom.docref.DocRef.builder()
                        .type("Pipeline")
                        .uuid(uuid + "-fragment")
                        .name(uuid + " fragment")
                        .build())
                .score(0.95)
                .promotedTimeMs(Scenarios.NOW.toEpochMilli())
                .build();
    }

    private Script learn(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                // The checks are set explicitly, because a document with no scorers scores every chain 1.0.
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.8,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final String feed, final long id) {
        return new Input(id, feed, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
