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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Design 02 §5, scenario 22 (A25; design 04 §3): in review mode a learned binding is a draft that serves
 * nothing; streams of the learner wait behind it, each on a deferral row naming it; Approve is the
 * promotion and wakes them; Reject gives the learner up with the reason, and the streams that waited are
 * woken to error. And the case the catalogue leaves implicit: a
 * relearned candidate under review is a draft behind the incumbent, which keeps serving until a person
 * decides.
 */
class TestScenario22ReviewMode {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String FOUR_FIELDS_AND_ALARMS = Scenarios.resource("csv-fields-and-alarms.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    private static ShapeshifterAiDoc review() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .promotionMode(PromotionMode.REVIEW)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
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

    /**
     * The first two streams of the learner: one learns the draft, the next is refused by it.
     */
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

        scenarios.stage(Script.of()).approve(waiting.doc(), draft.getUuid());

        // The document is untouched: what approval changed is rows (A41).
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

        scenarios.stage(Script.of()).reject(waiting.doc(), draft.getUuid(), "the wrong parser");

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
        scenarios.stage(Script.of()).approve(waiting.doc(), draft.getUuid());

        assertThatThrownBy(() -> scenarios.stage(Script.of()).approve(waiting.doc(), draft.getUuid()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft");
        assertThatThrownBy(() -> scenarios.stage(Script.of()).reject(waiting.doc(), "no-such-rule", "whatever"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /// A draft is decided from its own document (design 06 phase J slice 2b). Its uuid names it across
    /// every learner, and the document is what the person deciding was permitted on, so a draft of
    /// another document is not this one's to approve or reject, whatever uuid is sent.
    @Test
    void aDraftOfAnotherDocumentIsNotThisOnesToDecide() {
        final Scenarios scenarios = new Scenarios();
        drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final ShapeshifterAiDoc other = review().copy().uuid("doc-2").name("gate-access").build();

        assertThatThrownBy(() -> scenarios.stage(Script.of()).approve(other, draft.getUuid()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft on document gate-access");
        assertThatThrownBy(() -> scenarios.stage(Script.of()).reject(other, draft.getUuid(), "whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a draft on document gate-access");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("and the draft is still there, awaiting a decision from its own document")
                .containsExactly(draft);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();
    }

    /// Taking back the binding a draft would replace leaves the draft awaiting review. It is still the
    /// learner's candidate, and approving it is how the learner is served again; forgotten, it would be a
    /// draft nothing awaits — its streams waiting on a review that approve and reject both refuse.
    @Test
    void retractingTheIncumbentLeavesItsDraftAwaitingReview() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        scenarios.stage(Script.of()).approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid());
        final ShapeshifterAiDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        final StageRun relearned = scenarios.process(learning(scenarios, FOUR_FIELDS_AND_ALARMS), bound,
                stream(4, withAlarms)).learned();
        final Binding draft = ((Drafted) relearned.decision()).binding();

        scenarios.stage(Script.of()).retract(bound, incumbent.getUuid(), "The timestamps are wrong.", "jo");

        assertThat(scenarios.states.draftAwaiting(Scenarios.LEARNER))
                .describedAs("the draft is still what awaits review")
                .contains(draft.getUuid());
        scenarios.stage(Script.of()).approve(bound, draft.getUuid());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("and approving it makes it what serves, there being nothing to rebind")
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.getUuid()).isEqualTo(draft.getUuid());
                    assertThat(binding.isDraft()).isFalse();
                });
    }

    /// A stream the incumbent falls short on while a draft awaits review waits for the review, as the
    /// learner's marked streams do: it opens no session. One would relearn behind a person's back, and its
    /// reset would forget the draft awaiting them.
    @Test
    void aStreamThatFallsShortWhileADraftAwaitsReviewWaitsForTheReview() {
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        scenarios.stage(Script.of()).approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid());
        final ShapeshifterAiDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        final StageRun relearned = scenarios.process(learning(scenarios, FOUR_FIELDS_AND_ALARMS), bound,
                stream(4, withAlarms)).learned();
        final Binding draft = ((Drafted) relearned.decision()).binding();
        final int attemptsBefore = scenarios.attempts.forDocument(DOC, 100).size();
        // A stream that fell short of the incumbent before, tried again: its row is stamped against the
        // transform in force, which is what sends a task to open a session.
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
        scenarios.stage(Script.of()).approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid());
        final ShapeshifterAiDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);

        // The score falls and the learner is marked, as in scenario 27.
        final Script silent = Script.of();
        final StageRun marked = scenarios.process(silent, bound, stream(3, withAlarms)).learned();
        assertThat(marked.decision()).isInstanceOf(Bound.class);
        // The score fell; the mark is a person's, from the serving list (design 04 §6), as in scenario 27.
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isPresent();

        // Relearned: the incumbent serves, and the candidate is written as a draft behind it.
        final Script relearn = learning(scenarios, FOUR_FIELDS_AND_ALARMS);
        final StageRun relearned = scenarios.process(relearn, bound, stream(4, withAlarms)).learned();
        relearn.verifyExhausted();
        assertThat(relearned.decision()).isInstanceOf(Drafted.class);
        final Binding draft = ((Drafted) relearned.decision()).binding();
        assertThat(draft.getUuid()).isNotEqualTo(incumbent.getUuid());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(incumbent, draft);
        assertThat(events(relearned.output())).describedAs("the incumbent served").isEqualTo(14);
        assertThat(relearned.produced().fragment()).isEqualTo(incumbent.getPipeline());

        // While the draft waits the incumbent keeps serving, the model is not asked, no second draft.
        final StageRun meanwhile = scenarios.process(silent, relearned.doc(), stream(5, withAlarms)).learned();
        assertThat(meanwhile.decision()).isInstanceOf(Bound.class);
        assertThat(((Bound) meanwhile.decision()).binding()).isEqualTo(incumbent);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(2);
        assertThat(silent.asked()).isEmpty();

        // Approve rebinds the incumbent to the draft's fragment: one binding, the incumbent's uuid, the
        // draft's fragment, and a regression set that carries both histories.
        final int incumbentHeld = scenarios.regressionSet.accepted(incumbent.getUuid()).size();
        scenarios.stage(Script.of()).approve(relearned.doc(), draft.getUuid());
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        final Binding rebound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(rebound.getUuid()).isEqualTo(incumbent.getUuid());
        assertThat(rebound.getPipeline()).isEqualTo(draft.getPipeline());
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
        scenarios.stage(Script.of()).approve(waiting.doc(),
                scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid());
        final ShapeshifterAiDoc bound = waiting.doc();
        final Binding incumbent = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final String withAlarms = lines(20, 3);
        scenarios.process(Script.of(), bound, stream(3, withAlarms)).learned();
        // The mark is a person's (design 04 §6).
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        final StageRun relearned = scenarios.process(learning(scenarios, FOUR_FIELDS_AND_ALARMS), bound, stream(4,
                withAlarms)).learned();
        final Binding draft = ((Drafted) relearned.decision()).binding();

        scenarios.stage(Script.of()).reject(relearned.doc(), draft.getUuid(), "no");

        // The incumbent serves on; the rejection is recorded against the learner, and the same falling
        // score does not mark it or produce the same draft again until an operator says otherwise.
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
    void approvingAProvisionalDraftStartsItsRegressionSet() {
        // Too few records for A14; a person approving the draft is design 01 §6's exception, and the binding
        // goes live with the records it was judged on in its regression set (A18).
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, review().copy().minRecordsToLearn(10).build(),
                stream(1, lines(6, 0))).learned();
        final Binding draft = ((Drafted) first.decision()).binding();
        assertThat(draft.isProvisional()).isTrue();

        scenarios.stage(Script.of()).approve(first.doc(), draft.getUuid());

        final Binding live = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(live.isProvisional()).isFalse();
        assertThat(live.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(scenarios.regressionSet.accepted(live.getUuid()))
                .describedAs("one entry per record of the sample it was learned from, however few (A56)")
                .hasSize(5);
    }

    @Test
    void approvingOrRejectingADraftEndsItsAttempt() {
        // A28: an attempt that wrote a draft is awaiting review, and stops being so when a person decides.
        final Scenarios scenarios = new Scenarios();
        final StageRun waiting = drafted(scenarios);
        final Binding draft = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.attempts.forDocument(DOC, 10).get(0).status())
                .isEqualTo(AttemptStatus.AWAITING_REVIEW);

        scenarios.stage(Script.of()).approve(waiting.doc(), draft.getUuid());

        final Recorded approved = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(approved.status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(approved.decision()).isEqualTo("Approved");

        // And a rejected one says who refused it and why.
        final Scenarios other = new Scenarios();
        final StageRun second = drafted(other);
        final Binding rejectable = other.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        other.stage(Script.of()).reject(second.doc(), rejectable.getUuid(), "the wrong parser");

        final Recorded rejected = other.attempts.forDocument(DOC, 10).get(0);
        assertThat(rejected.status()).isEqualTo(AttemptStatus.REJECTED);
        assertThat(rejected.decision()).contains("the wrong parser");
    }
}
