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

/// Tests the one failure that ends learning for good: a parser that cannot be learned.
///
/// The parser's candidates are spent without passing the document's yield gate. Without a parser nothing
/// can be scored or served, so the session gives the learner up, the streams waiting on it are woken, and
/// each errors with the reason. No session opens for the learner again.
class TestScenario60AParseThatCannotBeLearnedErrorsItsStreams {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A splitter that makes one record per separator-delimited block. Over a CSV with no separators it makes
    /// one record for the whole stream, a yield far short of the gate.
    private static final String ONE_BLOCK = Scenarios.resource("block-split.ds3.xml");

    @Test
    void theParsersCandidatesSpentShortOfTheGatesGiveTheLearnerUpAndErrorEveryWaitingStream() {
        final Scenarios scenarios = new Scenarios();
        // The demo document with its yield counted per line, which is the gate that a parser treating a CSV
        // as one record fails. The demo's own yield is counted per record, which cannot catch such a parser
        // over text.
        final List<CheckSetting> checks = DemoDocument.checks().stream()
                .map(setting -> setting.getCheck() == Check.YIELD
                        ? new CheckSetting(Check.YIELD, CheckState.GATE, 0.5,
                                new YieldParameters(1.0, YieldBasis.LINES))
                        : setting)
                .toList();
        final LearningFlowDoc doc = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door")
                .heldOutFraction(0.0)
                .checks(checks)
                .flow(new LearningFlow(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).candidates(1).build(),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()), null, null))
                .build();
        scenarios.documents.put(doc);
        scenarios.inputs.put(input(1L));
        scenarios.inputs.put(input(2L));
        final Script silent = Script.of();
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(1L)).decision()).isInstanceOf(Deferred.class);
        assertThat(scenarios.stage(silent).decide(doc, Scenarios.LEARNER,
                input(2L)).decision()).isInstanceOf(Deferred.class);

        // The one parser candidate makes the whole stream one record, so its yield is short of the gate and
        // there is nothing else to try.
        final Script script = scenarios.script(CSV.configuration(), null)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(ONE_BLOCK));
        assertThat(scenarios.worker(script).advance(10)).isEqualTo(1);
        script.verifyExhausted();

        final Recorded session = scenarios.attempts.forDocument(DOC, 10).get(0);
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isPresent()
                .get().asString().isEqualTo("Couldn't write the parser: its 1 answer fell short.");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).isEmpty();
        assertThat(scenarios.wake.woken()).extracting(Deferral::inputId)
                .describedAs("every waiting stream is woken to error").contains(1L, 2L);

        // Each woken task errors with the reason, its deferral row is removed, and no session opens again.
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
