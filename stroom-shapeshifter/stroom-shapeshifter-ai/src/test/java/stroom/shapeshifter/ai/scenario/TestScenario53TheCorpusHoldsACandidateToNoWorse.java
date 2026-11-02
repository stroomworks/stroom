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
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 53: **the corpus holds a candidate to no worse** (design 04 §4.1, A56, A66;
/// phase 3's exit at Tier 1).
///
/// The corpus is per record (A56): a binding learned from six records holds six entries, each the record as
/// the parser cut it with the output it was accepted with and what that output is — a translation, an
/// admission, nothing. A later session is held to every entry, to no worse (A66): its output for each
/// must clear the bar, and a record once translated may never become an Unknown. Output may otherwise
/// change, and a marked Unknown may become a real translation without anybody agreeing, since that is the
/// corpus getting better.
///
/// The later session here is an improvement asked for by hand (A46), which is a session with no stream
/// of its own: it learns from the corpus put back together, and is the cleanest way to put a second
/// candidate to the gate. The document allows Unknown in `EventDetail`, so that an admission clears the
/// bar and what refuses the candidate is the corpus and not the floor.
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
        final ShapeshifterAiDoc doc = doc();

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
        final ShapeshifterAiDoc doc = doc();
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
        final ShapeshifterAiDoc doc = doc();
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
        final ShapeshifterAiDoc doc = doc().copy().regressionCap(8).build();
        final Binding binding = learn(scenarios, doc, ADMITS_LOGOFF);

        improve(scenarios, doc, binding, TRANSLATES_BOTH);

        final List<Accepted> corpus = scenarios.regressionSet.accepted(binding.getUuid());
        assertThat(corpus).hasSize(8);
        assertThat(corpus.subList(0, 2))
                .describedAs("what remains of the first acceptance is its last two records")
                .extracting(Accepted::kind)
                .containsExactly(Kind.UNKNOWN, Kind.TRANSLATION);
    }

    private static Binding learn(final Scenarios scenarios, final ShapeshifterAiDoc doc, final String xslt) {
        final Script script = script(scenarios, xslt);
        final StageRun run = scenarios.process(script, doc, stream(1L)).learned();
        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        return ((Promoted) run.decision()).binding();
    }

    private static StageRun improve(final Scenarios scenarios,
                                    final ShapeshifterAiDoc doc,
                                    final Binding binding,
                                    final String xslt) {
        final Script script = script(scenarios, xslt);
        final Stage improver = scenarios.stage(document -> script, scenarios.learnerBindings);
        final StageRun improved = improver.improve(doc, binding.getUuid(), null, "jo");
        script.verifyExhausted();
        return improved;
    }

    private static Script script(final Scenarios scenarios, final String xslt) {
        return scenarios.script(CSV.configuration(), xslt)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(xslt));
    }

    /// The demo document, allowing Unknown in EventDetail so that an admission clears the bar, and holding
    /// nothing out, so that the sample the model is shown is the whole of the six-record stream.
    private static ShapeshifterAiDoc doc() {
        final ShapeshifterAiDoc demo = DemoDocument.configure(Scenarios.document()).uuid(DOC).name("door").build();
        return demo.copy()
                .heldOutFraction(0.0)
                .scorers(demo.getScorers().stream()
                        .map(setting -> setting.getType() == ScorerType.EXTRACTION_QUALITY
                                ? new ScorerSetting(setting.getType(), setting.getWeight(), setting.getThreshold(),
                                        setting.isGate(), new ExtractionQualityParameters(true, List.of()))
                                : setting)
                        .toList())
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of(), MIXED, "pipeline-1");
    }
}
