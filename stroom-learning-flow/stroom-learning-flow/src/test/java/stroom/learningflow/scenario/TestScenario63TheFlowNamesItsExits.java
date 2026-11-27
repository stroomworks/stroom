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
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that a flow step can name the exit taken when its candidates are spent.
///
/// `goto park` parks the session for a person, and `goto give-up` gives the learner up and errors its
/// streams. Where the flow names no exit, the defaults apply: a parser step gives up (as in
/// `TestScenario60AParseThatCannotBeLearnedErrorsItsStreams`) and any other step parks (as in
/// `TestScenario57TheSessionRunsTheHeldStreamInFull`). These tests override the defaults: a parser that
/// parks, and a transform that gives up.
class TestScenario63TheFlowNamesItsExits {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String ONE_BLOCK = Scenarios.resource("block-split.ds3.xml");
    private static final String BROKEN = "<xsl:stylesheet version=\"2.0\">not a stylesheet";

    @Test
    void aParserWhoseFlowSaysParkParksInsteadOfGivingTheLearnerUp() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = perLine(DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .flow(new LearningFlow(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.PARSER)
                                .candidates(1)
                                .whenSpentGoTo("park")
                                .build(),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()), null, null)))
                .build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);
        final Script script = scenarios.script(CSV.configuration(), null)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(ONE_BLOCK));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final Recorded session = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.PARKED);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("the learner is not given up")
                .isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).describedAs("the stream goes on waiting").isPresent();
        assertThat(scenarios.wake.woken()).isEmpty();
    }

    @Test
    void aTransformWhoseFlowSaysGiveUpGivesTheLearnerUpAndErrorsItsStreams() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .flow(new LearningFlow(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                        FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.TRANSFORM)
                                .candidates(1)
                                .whenSpentGoTo("give-up")
                                .build()), null, null))
                .build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        scenarios.inputs.put(input(2L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(2L)).decision()).isInstanceOf(Deferred.class);
        final Script script = scenarios.script(CSV.configuration(), null)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(BROKEN));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final Recorded session = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isPresent()
                .get().asString().isEqualTo("Couldn't write the transform: its 1 answer fell short.");
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L, 2L);
        for (final long id : List.of(1L, 2L)) {
            final StageRun run = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, input(id));
            assertThat(run.decision()).isInstanceOf(Sentinel.class);
            assertThat(((Sentinel) run.decision()).reason()).contains("Given up");
        }
    }

    /// Sets the demo document's yield to be counted per line, which is the gate that a parser treating a CSV
    /// as one record fails (as in `TestScenario60AParseThatCannotBeLearnedErrorsItsStreams`).
    private static LearningFlowDoc.Builder perLine(final LearningFlowDoc.Builder builder) {
        final List<CheckSetting> checks = DemoDocument.checks().stream()
                .map(setting -> setting.getCheck() == Check.YIELD
                        ? new CheckSetting(Check.YIELD, CheckState.GATE, 0.5,
                                new YieldParameters(1.0, YieldBasis.LINES))
                        : setting)
                .toList();
        return builder.checks(checks);
    }

    private static Input input(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), CSV.input(), "pipeline-1");
    }
}
