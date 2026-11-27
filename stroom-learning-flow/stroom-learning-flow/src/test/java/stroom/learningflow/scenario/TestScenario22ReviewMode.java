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

package stroom.learningflow.scenario;

import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests review mode, where a person must approve what is learned before it serves.
///
/// In review mode a learned binding is a draft that serves nothing. The learner's streams wait behind it,
/// each with a deferral row naming the draft. Approving the draft promotes it and wakes those streams.
/// Rejecting it gives the learner up with the reason, and the streams that waited are woken so that they
/// error. A candidate learned while a binding is already serving is a draft behind that existing binding,
/// which keeps serving until a person decides.
class TestScenario22ReviewMode {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String FOUR_FIELDS_AND_ALARMS = Scenarios.resource("csv-fields-and-alarms.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    private static LearningFlowDoc review() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .promotionMode(PromotionMode.REVIEW)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
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

    private static Script learning(final Scenarios scenarios, final String splitter) {
        return scenarios.script(splitter, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(splitter))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static String lines(final int records, final int alarmEvery) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < records; i++) {
            final String time = "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z";
            text.append(alarmEvery > 0 && i % alarmEvery == alarmEvery - 1
                    ? time + ",ALARM,door " + i + " forced\n"
                    : time + ",user" + i + ",office,logon\n");
        }
        return text.toString();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static int events(final String output) {
        return output.split("<Event>", -1).length - 1;
    }

    /// Processes the learner's first two streams: the first learns a draft, and the second is deferred
    /// behind it.
    ///
    /// @return The run of the second stream, which waits for the draft to be reviewed.
    private static StageRun drafted(final Scenarios scenarios) {
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, review(), stream(1, lines(7, 0))).learned();
        script.verifyExhausted();

        assertThat(first.decision()).isInstanceOf(Drafted.class);
        final Binding draft = ((Drafted) first.decision()).binding();
        assertThat(draft.isDraft()).isTrue();
        assertThat(draft.getPromotedTimeMs()).isNull();
        assertThat(draft.getUuid()).isNotNull();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(draft);
        assertThat(first.output()).describedAs("nothing is produced under a draft").isNull();
        assertThat(first.produced()).isNull();
        assertThat(scenarios.stores.pipelines.list()).describedAs("the fragment is written all the same").hasSize(1);

        final Script silent = Script.of();
        final StageRun second = scenarios.process(silent, first.doc(), stream(2, lines(4, 0))).learned();
        assertThat(second.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) second.decision()).reason())
                .startsWith("Awaiting review: draft binding " + draft.getUuid())
                .contains(draft.getPipeline().getName());
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER,
                2L)).describedAs("the stream waits behind the draft").isPresent();
        return second;
    }

    @Test
    void aDraftWaitsAndApprovePromotesIt() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        scenarios.promotion().approve(waiting.doc(), draft.getUuid(), null);

        // Approval changes only the binding's rows, never the document, which holds only what a person
        // authors.
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        final Binding live = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(live.getUuid()).isEqualTo(draft.getUuid());
        assertThat(live.isDraft()).isFalse();
        assertThat(live.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(live.getPipeline()).isEqualTo(draft.getPipeline());
        assertThat(live.getScore()).isEqualTo(draft.getScore());
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("and the stream that waited behind it, woken").contains(2L);
        assertThat(scenarios.regressionSet.accepted(live.getUuid())).isNotEmpty();

        final Script silent = Script.of();
        final StageRun third = scenarios.process(silent, waiting.doc(), stream(3, lines(7, 0))).learned();
        assertThat(third.decision()).isInstanceOf(Bound.class);
        assertThat(events(third.output())).isEqualTo(7);
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void rejectDiscardsTheDraftAndGivesTheLearnerUp() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        scenarios.promotion().reject(waiting.doc(), draft.getUuid(), "the wrong parser", null);

        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.regressionSet.accepted(draft.getUuid())).isEmpty();
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).contains("Rejected: the wrong parser");
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("the stream that waited behind the draft is woken, to error as a given-up "
                             + "learner's streams do")
                .contains(2L);

        // The model is not asked again: the learner is given up until an operator says otherwise.
        final Script silent = Script.of();
        final StageRun third = scenarios.process(silent, waiting.doc(), stream(3, lines(7, 0))).learned();
        assertThat(third.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) third.decision()).reason()).contains("Rejected: the wrong parser");
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void onlyADraftCanBeApprovedOrRejected() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        scenarios.promotion().approve(waiting.doc(), draft.getUuid(), null);

        assertThatThrownBy(() -> scenarios.promotion().approve(waiting.doc(), draft.getUuid(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft");
        assertThatThrownBy(() -> scenarios.promotion().reject(waiting.doc(), "no-such-rule", "whatever", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A draft can only be approved or rejected through the document it belongs to. Its UUID is unique across
    /// all learners, but permission to decide is checked against the document, so a draft of another document
    /// cannot be decided through this one, whatever UUID is sent.
    @Test
    void aDraftOfAnotherDocumentIsNotThisOnesToDecide() {
        final Scenarios scenarios = new Scenarios();
        drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final LearningFlowDoc other = review().copy().uuid("doc-2").name("gate-access").build();

        assertThatThrownBy(() -> scenarios.promotion().approve(other, draft.getUuid(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft on document gate-access");
        assertThatThrownBy(() -> scenarios.promotion().reject(other, draft.getUuid(), "whatever", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft on document gate-access");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("and the draft is still there, awaiting a decision from its own document")
                .containsExactly(draft);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();
    }

    /// Retracting the binding a draft would replace leaves the draft awaiting review. It is still the
    /// learner's candidate, and approving it is how the learner is served again. If it were forgotten, its
    /// streams would wait for a review that neither approve nor reject would accept.
    @Test
    void retractingTheIncumbentLeavesItsDraftAwaitingReview() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        scenarios.promotion().approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid(), null);
        final LearningFlowDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        final StageRun relearned = scenarios.process(learning(scenarios, FOUR_FIELDS_AND_ALARMS), bound,
                stream(4, withAlarms)).learned();
        final Binding draft = ((Drafted) relearned.decision()).binding();

        scenarios.promotion().retract(bound, incumbent.getUuid(), "The timestamps are wrong.", "jo");

        assertThat(scenarios.states.draftAwaiting(Scenarios.LEARNER))
                .describedAs("the draft is still what awaits review")
                .contains(draft.getUuid());
        scenarios.promotion().approve(bound, draft.getUuid(), null);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("and approving it makes it what serves, there being nothing to rebind")
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.getUuid()).isEqualTo(draft.getUuid());
                    assertThat(binding.isDraft()).isFalse();
                });
    }

    /// A stream that the serving binding falls short on while a draft awaits review waits for the review and
    /// opens no session. A session would relearn without the reviewer knowing, and resetting the learner for
    /// it would lose the draft awaiting review.
    @Test
    void aStreamThatFallsShortWhileADraftAwaitsReviewWaitsForTheReview() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        scenarios.promotion().approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid(), null);
        final LearningFlowDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        final StageRun relearned = scenarios.process(learning(scenarios, FOUR_FIELDS_AND_ALARMS), bound,
                stream(4, withAlarms)).learned();
        final Binding draft = ((Drafted) relearned.decision()).binding();
        final int attemptsBefore = scenarios.attempts.forDocument(DOC, 100).size();
        // A stream that fell short with the serving binding before is tried again. Its deferral row records
        // the snapshot of the serving binding, which is what would normally make a task open a session.
        scenarios.deferrals.deferred(Scenarios.LEARNER, DOC, 7L, "DOOR-ACCESS", "Raw Events",
                incumbent.getSnapshot(), "Record 3 fell short of the bar", 1);
        scenarios.inputs.put(stream(7, withAlarms));

        final StageRun tried = scenarios.stage(Script.of()).decide(bound, Scenarios.LEARNER, stream(7, withAlarms));

        assertThat(tried.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) tried.decision()).reason()).contains("Awaiting review").contains(draft.getUuid());
        assertThat(scenarios.attempts.forDocument(DOC, 100))
                .describedAs("no session opened behind the person reviewing")
                .hasSize(attemptsBefore);
        assertThat(scenarios.states.draftAwaiting(Scenarios.LEARNER)).contains(draft.getUuid());
    }

    @Test
    void aRelearnedCandidateIsADraftBehindTheIncumbentUntilApproved() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        scenarios.promotion().approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid(), null);
        final LearningFlowDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);

        // The serving binding still serves the stream with alarms in it, but its score falls.
        final Script silent = Script.of();
        final StageRun marked = scenarios.process(silent, bound, stream(3, withAlarms)).learned();
        assertThat(marked.decision()).isInstanceOf(Bound.class);
        // Serving does not mark the learner itself: a person marks it for relearning from the serving list.
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isPresent();

        // While relearning, the serving binding serves the stream, and the candidate is written as a draft
        // behind it.
        final Script relearn = learning(scenarios, FOUR_FIELDS_AND_ALARMS);
        final StageRun relearned = scenarios.process(relearn, bound, stream(4, withAlarms)).learned();
        relearn.verifyExhausted();
        assertThat(relearned.decision()).isInstanceOf(Drafted.class);
        final Binding draft = ((Drafted) relearned.decision()).binding();
        assertThat(draft.getUuid()).isNotEqualTo(incumbent.getUuid());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(incumbent, draft);
        assertThat(events(relearned.output())).describedAs("the incumbent served").isEqualTo(14);
        assertThat(relearned.produced().fragment()).isEqualTo(incumbent.getPipeline());

        // While the draft waits, the serving binding keeps serving, the model is not asked and no second
        // draft is made.
        final StageRun meanwhile = scenarios.process(silent, relearned.doc(), stream(5, withAlarms)).learned();
        assertThat(meanwhile.decision()).isInstanceOf(Bound.class);
        assertThat(((Bound) meanwhile.decision()).binding()).isEqualTo(incumbent);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(2);
        assertThat(silent.asked()).isEmpty();

        // Approving copies the draft into the serving fragment, because a learner has one fragment that is
        // improved in place. The result is one binding with the serving binding's UUID, its own fragment
        // holding what the draft learned, and a regression corpus holding the records of both.
        final int incumbentHeld = scenarios.regressionSet.accepted(incumbent.getUuid()).size();
        scenarios.promotion().approve(relearned.doc(), draft.getUuid(), null);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        final Binding rebound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(rebound.getUuid()).isEqualTo(incumbent.getUuid());
        assertThat(rebound.getPipeline())
                .describedAs("one fragment per learner (A58): the draft's documents were copied into it")
                .isEqualTo(incumbent.getPipeline());
        assertThat(scenarios.stores.pipelines.list())
                .describedAs("the draft's fragment went with the draft")
                .hasSize(1);
        assertThat(rebound.isDraft()).isFalse();
        assertThat(scenarios.regressionSet.accepted(incumbent.getUuid()))
                .describedAs("the draft's records, learned for in its session, now follow the incumbent's")
                .hasSizeGreaterThan(incumbentHeld);
        assertThat(scenarios.regressionSet.accepted(draft.getUuid())).isEmpty();

        final StageRun after = scenarios.process(silent, bound, stream(6, withAlarms)).learned();
        assertThat(events(after.output())).isEqualTo(20);
    }

    @Test
    void rejectingARelearnedDraftLeavesTheIncumbentServing() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        scenarios.promotion().approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid(), null);
        final LearningFlowDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);
        scenarios.process(Script.of(), bound, stream(3, withAlarms)).learned();
        // Serving does not mark the learner itself: a person marks it for relearning.
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        final StageRun relearned = scenarios.process(learning(scenarios, FOUR_FIELDS_AND_ALARMS), bound, stream(4,
                withAlarms)).learned();
        final Binding draft = ((Drafted) relearned.decision()).binding();

        scenarios.promotion().reject(relearned.doc(), draft.getUuid(), "no", null);

        // The serving binding carries on serving. The rejection is recorded against the learner as a give-up,
        // so the same falling score neither marks it nor produces the same draft again until a person sends
        // it back.
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(incumbent);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).contains("Rejected: no");
        final Script silent = Script.of();
        for (long id = 5; id <= 7; id++) {
            final StageRun after = scenarios.process(silent, bound, stream(id, withAlarms)).learned();
            assertThat(after.decision()).isInstanceOf(Bound.class);
            assertThat(events(after.output())).isEqualTo(14);
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isEmpty();
    }

    @Test
    void approvingADraftLearnedFromTooFewRecordsStartsItsRegressionSet() {
        // The stream has fewer records than the minimum needed to learn, so the candidate could not be
        // promoted automatically. A person approving the draft is the exception to that minimum, and the
        // binding goes live with the records it was judged on as its regression corpus.
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, review().copy().minRecordsToLearn(10).build(),
                stream(1, lines(6, 0))).learned();
        final Binding draft = ((Drafted) first.decision()).binding();
        assertThat(draft.isDraft()).isTrue();

        scenarios.promotion().approve(first.doc(), draft.getUuid(), null);

        final Binding live = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(live.isDraft()).isFalse();
        assertThat(live.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.regressionSet.accepted(live.getUuid()))
                .describedAs("one entry per record of the sample it was learned from, however few (A56)")
                .hasSize(5);
    }

    @Test
    void approvingOrRejectingADraftEndsItsAttempt() {
        // An attempt that wrote a draft is awaiting review until a person approves or rejects the draft.
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.attempts.forDocument(DOC, 10).get(0).status())
                .isEqualTo(AttemptStatus.AWAITING_REVIEW);

        scenarios.promotion().approve(waiting.doc(), draft.getUuid(), null);

        final Recorded approved = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(approved.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(approved.decision()).isEqualTo("Approved");

        // And a rejected one says who refused it and why.
        final Scenarios other = new Scenarios();
        final StageRun second = drafted(other);
        final Binding rejectable = other.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        other.promotion().reject(second.doc(), rejectable.getUuid(), "the wrong parser", null);

        final Recorded rejected = other.attempts.forDocument(DOC, 10).get(0);
        assertThat(rejected.status()).isEqualTo(AttemptStatus.REJECTED);
        assertThat(rejected.decision()).contains("the wrong parser");
    }

    @Test
    void aDraftCarriesTheEvidenceItWasJudgedOnAndKeepsWhoDecided() {
        // A person reviewing a draft is shown how it did on each check, and their decision is kept with their
        // name and the time.
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        final Recorded awaiting = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(awaiting.review().evidence())
                .describedAs("the compile gate, at least, judged it and it passed")
                .anySatisfy(result -> {
                    assertThat(result.getCheck()).isEqualTo(Check.COMPILE);
                    assertThat(result.getState()).isEqualTo(CheckState.GATE);
                    assertThat(result.isMet()).isTrue();
                });
        assertThat(awaiting.review().reviewedBy()).isNull();

        scenarios.promotion().approve(waiting.doc(), draft.getUuid(), "jo");

        final Recorded approved = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(approved.review().reviewedBy()).isEqualTo("jo");
        assertThat(approved.review().reviewedTimeMs()).isNotNull();
        assertThat(approved.review().evidence()).describedAs("and what it was shown stays")
                .isEqualTo(awaiting.review().evidence());

        final Scenarios other = new Scenarios();
        final StageRun second = drafted(other);
        final Binding rejectable = other.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        other.promotion().reject(second.doc(), rejectable.getUuid(), "the wrong parser", "ann");

        assertThat(other.attempts.forDocument(DOC, 10).get(0).review().reviewedBy()).isEqualTo("ann");
    }
}
