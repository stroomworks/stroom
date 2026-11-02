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
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.AttemptStatus;
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

/// Design 06 A131, with A28 and A80: a learner that gave up is sent back from its attempt, and learns from
/// the next stream it is given — which, for a feed that ships rarely, is the stream that errored, put
/// through again by a person's reprocess filter on the pipeline. There is no ledger between the two: the
/// attempt is the record a person acts from, and the stream that errored is an ordinary error stream.
class TestALearnerThatGaveUpIsSentBack {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void sentBackFromItsAttemptItLearnsFromTheStreamThatErroredWhenThatIsReprocessed() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        // Given up — how a session gives up is scenario 60's — and a stream errored behind it.
        scenarios.states.giveUp(Scenarios.LEARNER, DOC, "No passing configuration for DSParser");
        final StageRun errored = scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        assertThat(errored.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) errored.decision()).reason()).contains("Given up");

        // A person sends it back from the attempt list, as the Supervisor's Learn again does.
        scenarios.stage(Script.of()).relearn(doc, Scenarios.LEARNER, "Sent back to be learned again by jo");
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();

        // And reprocesses the stream that errored: it is the learner's next stream, and it learns.
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun learned = scenarios.process(script, doc, stream(1L)).learned();

        script.verifyExhausted();
        assertThat(learned.decision()).describedAs(learned.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(scenarios.attempts.forDocument(DOC, 10)).extracting(Recorded::status)
                .describedAs("one session, and it is the record of what was learned")
                .containsExactly(AttemptStatus.PROMOTED);

        // The next stream is served with no model call.
        final Script silent = Script.of();
        final StageRun served = scenarios.stage(silent).decide(doc, Scenarios.LEARNER, stream(2L));
        assertThat(served.decision()).isInstanceOf(Bound.class);
        assertThat(silent.asked()).isEmpty();
    }

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
