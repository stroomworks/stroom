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
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests sending a learner that gave up back to learn again, and having it learn from the stream that errored.
///
/// After the learner is sent back, it learns from the next stream it is given. For a feed that arrives rarely,
/// that is likely to be the stream that errored, processed again by a person's reprocess filter on the
/// pipeline. Nothing else links the two: the attempt is the record a person acts from, and the stream that
/// errored is an ordinary error stream.
class TestALearnerThatGaveUpIsSentBack {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void sentBackFromItsAttemptItLearnsFromTheStreamThatErroredWhenThatIsReprocessed() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        // The learner is given up directly here (TestScenario60AParseThatCannotBeLearnedErrorsItsStreams tests
        // how a session gives up), and a stream is errored because of it.
        scenarios.states.giveUp(Scenarios.LEARNER, DOC, "No passing configuration for DSParser");
        final StageRun errored = scenarios.stage(Script.of()).decide(doc, Scenarios.LEARNER, stream(1L));
        assertThat(errored.decision()).isInstanceOf(Sentinel.class);
        assertThat(((Sentinel) errored.decision()).reason()).contains("Given up");

        // A person sends it back from the attempt list, as the Supervisor's Learn Again action does.
        scenarios.supervisorActions().relearn(doc, Scenarios.LEARNER, "Sent back to be learned again by jo");
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();

        // The person then reprocesses the stream that errored. It is the learner's next stream, so it learns.
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

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
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

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
