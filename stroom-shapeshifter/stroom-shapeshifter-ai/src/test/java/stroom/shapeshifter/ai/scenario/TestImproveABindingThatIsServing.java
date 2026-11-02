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
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Ruling A46: **a way in for a binding that is not failing.** A binding serving at 0.93 is above every
/// threshold, nothing has flagged it, and until now there was no door marked "make this better".
///
/// An improvement does not wait for traffic. The records the binding was accepted on are kept per binding
/// (A18) with the score each achieved, so an attempt has both a sample to learn from and the bar to
/// beat without a stream arriving — a feed that ships once a day can be improved at eleven in the
/// morning. The incumbent serves throughout and a candidate takes over only through the ordinary gate.
class TestImproveABindingThatIsServing {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    /// A transform that makes nothing of the records it is given: it compiles and runs, so what it
    /// fails is the gate that counts what came out.
    private static final String YIELDS_NOTHING = """
            <xsl:stylesheet xmlns="event-logging:3" xpath-default-namespace="records:2"
                            xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="records"><Events/></xsl:template>
            </xsl:stylesheet>""";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aBetterCandidateReboundsTheBindingAndTheMessageIsCarriedIntoTheAsking() {
        final Scenarios scenarios = new Scenarios();
        final Script learning = learn(scenarios);
        final Stage stage = scenarios.stage(document -> learning, scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();

        final StageRun learned = scenarios.process(learning, doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(learned.produced()).isNotNull();

        // Asked for out of the blue: no stream arrives, and the regression set is the sample.
        final Script improving = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final Stage improver = scenarios.stage(document -> improving, scenarios.learnerBindings);

        final StageRun improved = improver.improve(doc, bound.getUuid(),
                "The fourth column is a terminal id, not a user.", "jo");

        assertThat(improved.decision())
                .describedAs("a candidate that is no worse on what the binding was accepted on takes over")
                .isInstanceOf(Rebound.class);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the same binding, rebound: it keeps its uuid and its history (§7.3 rule 2)")
                .hasSize(1)
                .first()
                .satisfies(binding -> assertThat(binding.getUuid()).isEqualTo(bound.getUuid()));
        assertThat(scenarios.guidance.standing(Scenarios.LEARNER))
                .describedAs("the message is guidance for the learner, so it is carried into this attempt "
                             + "and stands for the relearning after it")
                .extracting(given -> given.message() + " (" + given.author() + ")")
                .containsExactly("The fourth column is a terminal id, not a user. (jo)");
    }

    @Test
    void aCandidateThatIsWorseOnAnAcceptedRecordIsRefusedAndTheIncumbentServesOn() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        // Seeded by hand. What the gate is about is the entry a candidate is held to — a record the binding
        // was accepted on that the candidate makes less of is not an improvement (A66) — and an entry
        // that says it yielded an event, over a record that holds nothing to make one of, is the cheapest
        // way to have one. Its text is an ordinary line, so the sample the candidate learns from still
        // parses and the candidate still clears the floor; it is the entry that refuses it.
        scenarios.regressionSet.discard(bound.getUuid());
        final String[] lines = CSV.input().split("\n");
        scenarios.regressionSet.accept("doc-1", bound.getUuid(), FEED, "Raw Events", List.of(new Accepted(
                "<records xmlns=\"records:2\"/>", lines[1], lines[0] + "\n", "\n", "<Events><Event/></Events>",
                Kind.TRANSLATION, 1.0, 7L, 0L, null)), doc.getRegressionCap());

        final Stage improver = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final StageRun improved = improver.improve(doc, bound.getUuid(), null, "jo");

        assertThat(improved.decision())
                .describedAs("worse on a record the binding was accepted on is not an improvement, whatever "
                             + "else it does (A18)")
                .isInstanceOf(Kept.class);
        assertThat(((Kept) improved.decision()).reason())
                .contains("makes nothing of a record that yielded an event")
                .contains("record 1 of 1 in the corpus, accepted by attempt 7");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getPipeline())
                .describedAs("and the incumbent is still bound, having served throughout")
                .isEqualTo(bound.getPipeline());
    }

    @Test
    void whatCannotBeImprovedSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        assertThatThrownBy(() -> stage.improve(doc, "not-a-rule", null, "jo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a binding of this document");

        scenarios.regressionSet.discard(bound.getUuid());
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("without a record it was accepted on there is nothing to learn from and "
                             + "nothing to beat: an improvement judged on nothing is a promotion in "
                             + "disguise")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no accepted records");
    }

    /// The four states in which an improvement must refuse rather than proceed, each of which would
    /// undo something somebody else decided.
    @Test
    void anImprovementRefusesWhatOtherDecisionsHaveSettled() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        assertThatThrownBy(() -> stage.improve(doc().copy().learningMode(LearningMode.DISABLED).build(),
                bound.getUuid(), null, "jo"))
                .describedAs("the kill switch is a kill switch: a button that asked anyway would be a "
                             + "way round it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        scenarios.states.awaitReview(Scenarios.LEARNER, "doc-1", "some-draft");
        assertThatThrownBy(() -> stage.improve(doc, bound.getUuid(), null, "jo"))
                .describedAs("one draft per learner: a second would overwrite the pointer that says which "
                             + "is awaiting review, leaving the first undecidable")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already awaiting review");
        scenarios.states.reset(Scenarios.LEARNER);

        // And a learner marked for relearning has learned again by hand: the mark is spent.
        scenarios.states.markForRelearning(Scenarios.LEARNER, "doc-1", "drifting");
        stage.improve(doc, bound.getUuid(), null, "jo");
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("or the next stream would relearn the binding somebody has only now improved")
                .isEmpty();
    }

    /// An improvement is asked about the corpus alone. There is no stream to read a feed and type off,
    /// and a learner does not know its feed (A126), so nothing is shown that might not be true of every
    /// stream the learner serves.
    @Test
    void theQuestionsAreAboutTheCorpusAndNoFeed() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream()).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        final Script asking = learn(scenarios);
        final StageRun improved = scenarios.stage(document -> asking, scenarios.learnerBindings)
                .improve(doc, bound.getUuid(), null, "jo");

        assertThat(improved.transcript()).isNotEmpty();
        assertThat(asking.asked()).first()
                .describedAs("a learner does not know its feed (A126), and an improvement has no stream to "
                             + "read one off, so the corpus is shown with no feed or type")
                .isInstanceOfSatisfying(Chain.class, chain -> assertThat(chain.sample().headers()).isEmpty());
    }

    private Script learn(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .promotionFloor(0.85)
                // Named, because a document with no scorers scores every chain 1.0 and no candidate
                // could ever be worse than what it would replace.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream() {
        return new Input(1L, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
