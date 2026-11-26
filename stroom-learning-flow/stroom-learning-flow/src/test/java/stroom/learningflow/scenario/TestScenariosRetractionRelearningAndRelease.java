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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;
import stroom.processor.api.DeferTaskException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests what happens to a binding after it is made.
///
/// A bound learner whose rolling score falls is marked for relearning by a person, and relearns while the
/// existing binding keeps serving. A marked learner waits for a stream with enough records to judge a
/// candidate on. Binding a learner wakes the streams that waited for it. The feed is a CSV of four fields
/// with no header, so that a regex splitter can be right about it and then wrong when the feed changes.
class TestScenariosRetractionRelearningAndRelease {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String FOUR_FIELDS_AND_ALARMS = Scenarios.resource("csv-fields-and-alarms.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DOC = "doc-1";

    private static LearningFlowDoc doc(final int minRecordsToLearn) {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(minRecordsToLearn)
                .relearnThreshold(0.8)
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

    private static String access(final int i) {
        return "2020-06-17T08:" + String.format("%02d", i) + ":00.000Z,user" + i + ",office,logon\n";
    }

    /// Builds `records` access lines, with every `alarmEvery`th line replaced by an alarm line of three
    /// fields.
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

    // There is no test of a provisional binding being retracted: a first result learned from too few records
    // is always a draft, whatever the promotion mode, so nothing serves without having been judged. A stream
    // the translation cannot read is deferred rather than recorded as clean, which is tested in
    // TestScenario64TheStreamTheTranslationStoppedReading.

    @Test
    void scenario27AFallingRollingScoreIsKeptForTheListAndAPersonMarksTheLearner() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc(5);

        // Seven clean records: learned and promoted at a perfect score.
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun first = scenarios.process(script, doc, stream(1, lines(7, 0))).learned();
        script.verifyExhausted();
        assertThat(first.decision()).isInstanceOf(Promoted.class);
        final Binding incumbent = ((Promoted) first.decision()).binding();
        assertThat(((Promoted) first.decision()).score()).isEqualTo(1.0);

        // Twenty records, of which six are a second kind the splitter does not match. The binding still
        // serves: the fourteen that parse become events, and the six are reported as processing errors
        // rather than the stream being errored. The learner's rolling score falls below the threshold.
        final String withAlarms = lines(20, 3);
        final Script silent = Script.of();
        final StageRun second = scenarios.process(silent, first.doc(), stream(2, withAlarms)).learned();

        assertThat(silent.asked()).isEmpty();
        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(events(second.output())).isEqualTo(14);
        // The score does not trigger relearning itself. It is shown in the Supervisor's list of what could be
        // better, and a person reading the stream's errors marks the learner.
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isEmpty();
        assertThat(second.doc()).isEqualTo(first.doc());
        scenarios.states.markForRelearning(Scenarios.LEARNER, DOC, "Rolling score below the threshold: improve it");

        // The next stream starts an attempt whose questions open with why the current binding fell short: the
        // mark's reason and the coverage shortfall. The current binding serves the stream meanwhile. The
        // candidate handles both kinds of record, does better on this stream and is no worse on the records
        // the binding was accepted on, so the binding is rebound to it: the same binding and fragment, edited
        // in place under a new snapshot.
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

        // The stream after that is served by the updated fragment, with every record becoming an event.
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

        // A blank stream after promotion gives nothing to judge the learner on: no score and no mark.
        final Script silent = Script.of();
        final StageRun blank = scenarios.process(silent, first.doc(), stream(2, "\n")).learned();
        assertThat(blank.decision()).isInstanceOf(Bound.class);
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER)).isEmpty();

        // The score falls and a person marks the learner. A stream of three records has too few records to
        // judge a candidate on, so the current binding serves it, the model is not asked, and the mark stays.
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
        // Two streams arrive while learning is disabled. Each waits for a person on a deferral row, with no
        // session opened.
        final LearningFlowDoc disabled = doc(5).copy().learningMode(LearningMode.DISABLED).build();
        final Script silent = Script.of();
        for (long id = 11; id <= 12; id++) {
            final StageRun run = scenarios.process(silent, disabled, stream(id, lines(6, 0))).learned();
            assertThat(run.decision()).isInstanceOf(Deferred.class);
            assertThat(scenarios.deferrals.of(Scenarios.LEARNER, id)).isPresent();
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).isEmpty();

        // Learning is switched on and the next stream is learned from. The promotion wakes the two that
        // waited, without anything needing to be reprocessed.
        final Script script = learning(scenarios, FOUR_FIELDS);
        final StageRun run = scenarios.process(script, doc(5), stream(13, lines(6, 0))).learned();
        script.verifyExhausted();

        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(11L, 12L);
    }
}
