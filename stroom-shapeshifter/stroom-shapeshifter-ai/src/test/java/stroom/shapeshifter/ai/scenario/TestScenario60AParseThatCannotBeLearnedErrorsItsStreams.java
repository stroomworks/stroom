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

/// Design 02 §5, scenario 60: **a parse that cannot be learned errors its streams** (A52, A54; design 04
/// §4 and its §8 scenario 5) — the one terminal failure.
///
/// The parser's candidates are spent short of the document's coverage gate. Nothing can be scored or
/// served: the session gives the learner up, the streams waiting on it are woken, and each errors with the
/// reason — no session opens again for the learner.
class TestScenario60AParseThatCannotBeLearnedErrorsItsStreams {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A splitter that cuts one record per separator-delimited block: over a CSV with no separators, one
    /// record for the whole stream, which is a yield far short of the gate.
    private static final String ONE_BLOCK = Scenarios.resource("block-split.ds3.xml");

    @Test
    void theParsersCandidatesSpentShortOfTheGatesGiveTheLearnerUpAndErrorEveryWaitingStream() {
        final Scenarios scenarios = new Scenarios();
        // The demo document with its yield counted per line, which is the gate a parser that cuts a CSV as
        // one record fails; the demo's own yield is per record, which says nothing of a parser over text.
        final List<ScorerSetting> scorers = DemoDocument.scorers().stream()
                .map(setting -> setting.getType() == ScorerType.YIELD
                        ? new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.LINES))
                        : setting)
                .toList();
        final ShapeshifterAiDoc doc = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .scorers(scorers)
                .flow(new LearningFlow(List.of(
                        FlowStep.parse("CHAIN"),
                        FlowStep.parse("CONFIGURE parser candidates 1"),
                        FlowStep.parse("CONFIGURE transform")), null, null))
                .build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        scenarios.inputs.put(input(2L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(2L)).decision()).isInstanceOf(Deferred.class);

        // The one parser candidate cuts the whole stream as one record: yield short of the gate (A54), and
        // nothing else to try.
        final Script script = scenarios.script(CSV.configuration(), null)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(ONE_BLOCK));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final Recorded session = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isPresent()
                .get().asString().contains("No passing configuration for DSParser");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("every waiting stream is woken to error").contains(1L, 2L);

        // Each woken task errors with the reason, its row goes, and no session opens again.
        for (final long id : List.of(1L, 2L)) {
            final StageRun run = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, input(id));
            assertThat(run.decision()).isInstanceOf(Sentinel.class);
            assertThat(((Sentinel) run.decision()).reason()).contains("Given up");
            assertThat(scenarios.deferrals.of(Scenarios.LEARNER, id)).isEmpty();
        }
        assertThat(scenarios.attempts.forDocument(DOC, 10)).hasSize(1);
        assertThat(silent.asked()).isEmpty();
    }

    private static Input input(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), CSV.input(), "pipeline-1");
    }
}
