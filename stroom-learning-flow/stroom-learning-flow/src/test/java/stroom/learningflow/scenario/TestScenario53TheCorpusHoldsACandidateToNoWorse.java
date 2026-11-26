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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.RegressionSet.Kind;
import stroom.learningflow.stage.Stage;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that a binding's regression corpus stops a later candidate from doing worse on any record the
/// binding was accepted on.
///
/// The corpus holds one entry per record: a binding learned from six records holds six entries, each holding
/// the record as the parser split it, the output it was accepted with, and what kind of output that is (a
/// translation or an admitted Unknown). A later candidate is checked against every entry: its output for
/// each must clear the bar, and a record that was once translated may never become an Unknown. Output may
/// otherwise change, and a marked Unknown may become a real translation without anyone approving it, since
/// that makes the corpus better.
///
/// The later candidate here comes from an improvement requested by hand, which is a session with no stream
/// of its own that learns from the records in the corpus. It is the simplest way to put a second candidate
/// through the gate. The document allows Unknown in `EventDetail`, so that an admitted Unknown clears the
/// bar and it is the corpus, not the score threshold, that refuses the candidate.
class TestScenario53TheCorpusHoldsACandidateToNoWorse {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String ADMITS_LOGOFF = Scenarios.resource("csv-logon-unknown.xsl");

    @Test
    void aBindingIsAcceptedOnEachRecordItLearnedFromWithWhatItMadeOfIt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();

        final Binding binding = learn(scenarios, doc, ADMITS_LOGOFF);

        final List<Accepted> corpus = scenarios.regressionSet.accepted(binding.getUuid());
        assertThat(corpus).describedAs("one entry per record of the sample (A56)").hasSize(6);
        assertThat(corpus).extracting(Accepted::kind).containsExactly(
                Kind.TRANSLATION, Kind.TRANSLATION, Kind.UNKNOWN, Kind.TRANSLATION, Kind.UNKNOWN, Kind.TRANSLATION);
        assertThat(corpus).extracting(Accepted::text).containsExactly(MIXED.lines().skip(1).toArray(String[]::new));
        assertThat(corpus).allSatisfy(entry -> {
            assertThat(entry.record()).contains("<record>").contains("xmlns=\"records:2\"");
            assertThat(entry.preamble()).isEqualTo("dt,who,where,what\n");
            assertThat(entry.output()).contains("<Event>");
            assertThat(entry.attemptId()).isNotNull();
        });
        assertThat(corpus.get(2).output()).contains("<Unknown/>");
        assertThat(corpus.get(0).output()).doesNotContain("<Unknown/>");
    }

    @Test
    void aCandidateThatTurnsATranslatedRecordIntoAnUnknownIsRefusedAndTheEntryIsNamed() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final Binding binding = learn(scenarios, doc, TRANSLATES_BOTH);
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .extracting(Accepted::kind)
                .containsOnly(Kind.TRANSLATION);

        final StageRun improved = improve(scenarios, doc, binding, ADMITS_LOGOFF);

        assertThat(improved.decision()).describedAs(improved.decision().toString()).isInstanceOf(Kept.class);
        assertThat(((Kept) improved.decision()).reason())
                .contains("turns a record that translated into an Unknown")
                .contains("record 3 of 6 in the corpus");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getPipeline())
                .describedAs("the incumbent serves on")
                .isEqualTo(binding.getPipeline());
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .describedAs("and the corpus is as it was")
                .hasSize(6);
    }

    @Test
    void aCandidateThatTurnsAnUnknownIntoATranslationIsAcceptedAndTheCorpusGrows() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        final Binding binding = learn(scenarios, doc, ADMITS_LOGOFF);

        final StageRun improved = improve(scenarios, doc, binding, TRANSLATES_BOTH);

        assertThat(improved.decision()).describedAs(improved.decision().toString()).isInstanceOf(Rebound.class);
        final List<Accepted> corpus = scenarios.regressionSet.accepted(binding.getUuid());
        assertThat(corpus)
                .describedAs("the six records again, with what the candidate now makes of them, after the six "
                             + "they were first accepted with")
                .hasSize(12);
        assertThat(corpus.subList(6, 12)).extracting(Accepted::kind).containsOnly(Kind.TRANSLATION);
        assertThat(corpus.get(8).output())
                .describedAs("the third record was an admission and is now a translation")
                .doesNotContain("<Unknown/>")
                .contains("<Action>Logoff</Action>");
    }

    @Test
    void theCapLetsTheOldestEntriesGo() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc().copy().regressionCap(8).build();
        final Binding binding = learn(scenarios, doc, ADMITS_LOGOFF);

        improve(scenarios, doc, binding, TRANSLATES_BOTH);

        final List<Accepted> corpus = scenarios.regressionSet.accepted(binding.getUuid());
        assertThat(corpus).hasSize(8);
        assertThat(corpus.subList(0, 2))
                .describedAs("what remains of the first acceptance is its last two records")
                .extracting(Accepted::kind)
                .containsExactly(Kind.UNKNOWN, Kind.TRANSLATION);
    }

    private static Binding learn(final Scenarios scenarios, final LearningFlowDoc doc, final String xslt) {
        final Script script = script(scenarios, xslt);
        final StageRun run = scenarios.process(script, doc, stream(1L)).learned();
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).binding();
    }

    private static StageRun improve(final Scenarios scenarios,
                                    final LearningFlowDoc doc,
                                    final Binding binding,
                                    final String xslt) {
        final Script script = script(scenarios, xslt);
        // The improvement is requested and then resumed by the worker, because it is not run in the web
        // request that asks for it.
        final StageRun improved = scenarios.improved(document -> script, doc, binding.getUuid(), null, "jo");
        script.verifyExhausted();
        return improved;
    }

    private static Script script(final Scenarios scenarios, final String xslt) {
        return scenarios.script(CSV.configuration(), xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /// Builds the demo document, allowing Unknown in EventDetail so that an admitted Unknown clears the bar,
    /// and holding no records out, so that the sample the model is shown is the whole six-record stream.
    private static LearningFlowDoc doc() {
        final LearningFlowDoc demo = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").build();
        return demo.copy()
                .heldOutFraction(0.0)
                .checks(demo.getChecks().stream()
                        .map(setting -> setting.getCheck() == Check.QUALITY
                                ? new CheckSetting(setting.getCheck(), setting.getState(), setting.getThreshold(),
                                        new ExtractionQualityParameters(true, List.of()))
                                : setting)
                        .toList())
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), MIXED, "pipeline-1");
    }
}
