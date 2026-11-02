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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 63: **the flow names its exits** (A83; design 05 §2).
///
/// A step whose candidates are spent leaves the flow by the exit it names: `goto park` parks the session
/// for a person, `goto give-up` gives the learner up and errors its streams. The defaults stand where the
/// flow names none, a parser's being give-up (scenario 60) and any other step's park (scenario 57), and
/// the flow may say the other: a parser that parks, a transform that gives up.
class TestScenario63TheFlowNamesItsExits {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String ONE_BLOCK = Scenarios.resource("block-split.ds3.xml");
    private static final String BROKEN = "<xsl:stylesheet version=\"2.0\">not a stylesheet";

    @Test
    void aParserWhoseFlowSaysParkParksInsteadOfGivingTheLearnerUp() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = perLine(DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .flow(new LearningFlow(List.of(
                        FlowStep.parse("CHAIN"),
                        FlowStep.parse("CONFIGURE parser candidates 1 on spent goto park"),
                        FlowStep.parse("CONFIGURE transform")), null, null)))
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
        final ShapeshifterAiDoc doc = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .flow(new LearningFlow(List.of(
                        FlowStep.parse("CHAIN"),
                        FlowStep.parse("CONFIGURE parser"),
                        FlowStep.parse("CONFIGURE transform candidates 1 on spent goto give-up")), null, null))
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
                .get().asString().contains("No passing configuration for XSLTFilter");
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId).contains(1L, 2L);
        for (final long id : List.of(1L, 2L)) {
            final StageRun run = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, input(id));
            assertThat(run.decision()).isInstanceOf(Sentinel.class);
            assertThat(((Sentinel) run.decision()).reason()).contains("Given up");
        }
    }

    /// The demo document with its yield counted per line, the gate a parser that cuts a CSV as one record
    /// fails (scenario 60).
    private static ShapeshifterAiDoc.Builder perLine(final ShapeshifterAiDoc.Builder builder) {
        final List<ScorerSetting> scorers = DemoDocument.scorers().stream()
                .map(setting -> setting.getType() == ScorerType.YIELD
                        ? new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.LINES))
                        : setting)
                .toList();
        return builder.scorers(scorers);
    }

    private static Input input(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), CSV.input(), "pipeline-1");
    }
}
