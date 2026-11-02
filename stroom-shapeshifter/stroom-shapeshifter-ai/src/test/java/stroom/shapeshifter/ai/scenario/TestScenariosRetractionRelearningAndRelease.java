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

import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 28, 27 and 13: what happens to a binding after it is made. A provisional
 * binding that fails the gate is retracted; a bound learner whose rolling score falls relearns while the
 * incumbent serves; binding a learner wakes the streams that waited for it. The feed is a headerless CSV
 * of four fields, so that a regex splitter can be right about it and then wrong when the feed changes.
 */
class TestScenariosRetractionRelearningAndRelease {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String FOUR_FIELDS_AND_ALARMS = Scenarios.resource("csv-fields-and-alarms.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    private static ShapeshifterAiDoc doc(final int minRecordsToLearn) {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(minRecordsToLearn)
                .relearnThreshold(0.8)
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

    private static String access(final int i) {
        return "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z,user" + i + ",office,logon\n";
    }

    /**
     * {@code records} access lines, every {@code alarmEvery}th replaced by an alarm line of three fields.
     */
    private static String lines(final int records, final int alarmEvery) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < records; i++) {
            text.append(alarmEvery > 0 && i % alarmEvery == alarmEvery - 1
                    ? "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z,ALARM,door " + i + " forced\n"
                    : access(i));
        }
        return text.toString();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static int events(final String output) {
        return output.split("<Event>", -1).length - 1;
    }

    @Test
    void scenario28AProvisionalBindingIsNotRetractedByTheFloor() {
        // A101: a provisional binding is held to the record bar like any other, and the floor no longer
        // retracts it. A stream whose records all clear it is served by it; one whose records fall short
        // sends the learner back to learn (scenario 54); and enough clean records promote it in place
        // (scenario 14).
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(10);

        // Six records against a minimum of ten: learned, bound provisionally (scenario 14).
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, doc, stream(1, lines(6, 0))).learned();
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Provisional.class);
        final Binding provisional = ((Provisional) first.decision()).binding();
        assertThat(first.produced().provisional()).isTrue();

        // Four more records: still too few to promote it, so the provisional binding serves them as it is.
        final Script silent = Script.of();
        final StageRun second = scenarios.process(silent, first.doc(), stream(2, lines(4, 0))).learned();
        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(second.produced().provisional()).isTrue();
        assertThat(events(second.output())).isEqualTo(4);

        // Ten records the feed now sends with a fifth field: the splitter matches none of them, so there is
        // no record to fall short and nothing to promote on. The binding stays, provisional, and nothing is
        // retracted, released or asked.
        final String fiveFields = lines(10, 0).replace(",logon\n", ",logon,badge\n");
        final StageRun third = scenarios.process(silent, second.doc(), stream(3, fiveFields)).learned();

        assertThat(silent.asked()).isEmpty();
        assertThat(third.decision()).isInstanceOf(Bound.class);
        assertThat(events(third.output())).isZero();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(provisional);
    }

    @Test
    void scenario27AFallingRollingScoreIsKeptForTheListAndAPersonMarksTheLearner() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc(5);

        // Seven clean records: learned and promoted at a perfect score.
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, doc, stream(1, lines(7, 0))).learned();
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Promoted.class);
        final Binding incumbent = ((Promoted) first.decision()).binding();
        assertThat(((Promoted) first.decision()).score()).isEqualTo(1.0);

        // Twenty records of which six are a second kind the splitter does not match. No new learner: the
        // binding still serves, the fourteen that parse become events, the six are errors on the way — not
        // sentinels — and the learner's rolling score falls below the threshold.
        final String withAlarms = lines(20, 3);
        final Script silent = Script.of();
        final StageRun second = scenarios.process(silent, first.doc(), stream(2, withAlarms)).learned();

        assertThat(silent.asked()).isEmpty();
        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(events(second.output())).isEqualTo(14);
        // The score is no trigger (design 04 §6): it is kept for the Supervisor's list of what could be
        // better (A46), and a person reading the stream's errors is who marks the learner (A100).
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isEmpty();
        assertThat(second.doc()).isEqualTo(first.doc());
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Rolling score below the threshold: improve it");

        // The next stream starts an attempt, opening with why the incumbent fell short: the mark's reason
        // and the coverage shortfall, on the chain question and on each element's first. The incumbent
        // serves it meanwhile; the candidate handles both kinds, beats the incumbent on this stream and
        // is no worse on the record it was accepted on, so the binding is rebound to it — same binding, new
        // fragment.
        final Script relearn = scenarios.script(FOUR_FIELDS_AND_ALARMS, XSLT)
                .expect(QuestionMatcher.chain()
                        .withFeedbackMentioning("Relearning: Rolling score below")
                        .withFeedbackMentioning("The split consumed 14 of 20 lines"))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withFeedbackMentioning("14 of 20 lines"))
                .reply(Scenarios.fenced(FOUR_FIELDS_AND_ALARMS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Relearning"))
                .reply(Scenarios.fenced(XSLT));
        final int held = scenarios.regressionSet.accepted(incumbent.getUuid()).size();
        final Scenarios.Processed relearned = scenarios.process(relearn, second.doc(), stream(3, withAlarms));
        final StageRun third = relearned.learned();
        relearn.verifyExhausted();

        assertThat(third.decision()).isInstanceOf(Rebound.class);
        final Rebound rebound = (Rebound) third.decision();
        assertThat(rebound.incumbent()).isEqualTo(incumbent);
        assertThat(rebound.binding().getUuid()).isEqualTo(incumbent.getUuid());
        assertThat(rebound.binding().getPipeline())
                .describedAs("edited in place (A58)")
                .isEqualTo(incumbent.getPipeline());
        assertThat(rebound.binding().getSnapshot()).isNotEqualTo(incumbent.getSnapshot());
        assertThat(rebound.binding().getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());
        assertThat(rebound.score()).isEqualTo(1.0);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).containsExactly(rebound.binding());
        assertThat(events(relearned.output())).describedAs("the incumbent served this stream").isEqualTo(14);
        assertThat(third.produced().fragment()).isEqualTo(incumbent.getPipeline());
        assertThat(scenarios.regressionSet.accepted(incumbent.getUuid()))
                .describedAs("the records that fell short under the incumbent were learned for, and go in (A56)")
                .hasSizeGreaterThan(held);
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.stores.pipelines.list()).describedAs("one fragment per learner (A58)").hasSize(1);

        // And the stream after that is served by the new fragment, every record an event.
        final StageRun fourth = scenarios.process(silent, third.doc(), stream(4, withAlarms)).learned();
        assertThat(fourth.decision()).isInstanceOf(Bound.class);
        assertThat(fourth.produced().fragment()).isEqualTo(rebound.binding().getPipeline());
        assertThat(events(fourth.output())).isEqualTo(20);
        assertThat(silent.asked()).isEmpty();
    }

    @Test
    void aMarkedLearnerWaitsForAStreamACandidateCanBeJudgedOn() {
        final Scenarios scenarios = new Scenarios();
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, doc(5), stream(1, lines(7, 0))).learned();
        assertThat(first.decision()).isInstanceOf(Promoted.class);

        // A blank stream after promotion is nothing to judge the learner on: no score, no mark.
        final Script silent = Script.of();
        final StageRun blank = scenarios.process(silent, first.doc(), stream(2, "\n")).learned();
        assertThat(blank.decision()).isInstanceOf(Bound.class);
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isEmpty();

        // The score falls and a person marks the learner (design 04 §6); a stream of three records cannot
        // meet A14 for a candidate, so the incumbent serves it, the model is not asked, and the mark waits.
        scenarios.process(silent, first.doc(), stream(3, lines(20, 3))).learned();
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Scored below the threshold: improve it");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isPresent();
        final Script stillSilent = Script.of();
        final StageRun small = scenarios.process(stillSilent, first.doc(), stream(4, lines(3, 3))).learned();
        assertThat(small.decision()).isInstanceOf(Bound.class);
        assertThat(stillSilent.asked()).isEmpty();
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isPresent();

        // A stream with enough records is relearned.
        final Script relearn = learning(scenarios, FOUR_FIELDS_AND_ALARMS);
        final StageRun big = scenarios.process(relearn, first.doc(), stream(5, lines(20, 3))).learned();
        relearn.verifyExhausted();
        assertThat(big.decision()).isInstanceOf(Rebound.class);
    }

    @Test
    void scenario13BindingALearnerWakesTheStreamsThatWaitedForIt() {
        final Scenarios scenarios = new Scenarios();
        // Two streams arrive while learning is disabled: each waits for a person (design 04 §3, the kill
        // switch), on a row, with no session opened.
        final ShapeshifterAiDoc disabled = doc(5).copy().learningMode(LearningMode.DISABLED).build();
        final Script silent = Script.of();
        for (long id = 11; id <= 12; id++) {
            final StageRun run = scenarios.process(silent, disabled, stream(id, lines(6, 0))).learned();
            assertThat(run.decision()).isInstanceOf(Deferred.class);
            assertThat(scenarios.deferrals.of(Scenarios.LEARNER, id)).isPresent();
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).isEmpty();

        // Learning is switched on and the next stream is learned on: the promotion wakes the two that
        // waited (design 04 §5.3), and nothing is asked of reprocessing.
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun run = scenarios.process(script, doc(5), stream(13, lines(6, 0))).learned();
        script.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(11L, 12L);
    }
}
