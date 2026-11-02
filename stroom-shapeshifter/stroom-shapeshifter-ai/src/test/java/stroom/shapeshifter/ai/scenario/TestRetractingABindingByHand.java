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
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Stage;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.AttemptStatus;
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

/// Ruling A28, design 01 §11.6: **taking a binding back by hand.**
///
/// The gate retracts a provisional binding that fails it (§6). This is the same act for a reason no gate
/// can see — a person has read what the binding is producing and decided it should not be — and what
/// follows is deliberately identical: the binding goes, the learner knows nothing again, and everything the
/// binding produced is asked to be processed again as it would be now (A12).
///
/// It is neither of the two things that look like it. Removing the binding's row is exactly what it says:
/// a binding taken out of the table, leaving the learner thinking it is bound and the streams it produced
/// standing as though they were right. `reject` is A25's decision about a draft that served nothing, and
/// gives the learner up so it does not learn again. A retracted learner *does* learn again — the binding
/// was wrong, the feed is not.
class TestRetractingABindingByHand {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void theBindingGoesTheLearnerLearnsAgainAndWhatItProducedIsCounted() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        // A second stream, so that the binding has produced something to ask for again.
        scenarios.process(learn(scenarios), doc, stream(2L)).learned();
        assertThat(scenarios.outputs.boundBy(bound.getUuid(), bound.getPipeline().getUuid()))
                .describedAs("the binding has produced output rows to take back")
                .isNotEmpty();

        final int produced = stage.retract(doc, bound.getUuid(),
                "The timestamps are being read as UTC and the feed is local.", "jo");

        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("the binding is gone")
                .isEmpty();
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("and the learner knows nothing again rather than giving up: the binding was wrong, "
                             + "the feed is not, so the learner's next stream is learned afresh")
                .isEmpty();
        assertThat(scenarios.states.rollingScore(Scenarios.LEARNER))
                .describedAs("with nothing of what the retracted binding scored carried into what comes next")
                .isZero();
        assertThat(produced)
                .describedAs("and what the binding produced is counted for the person to reprocess, not asked "
                             + "for (A129): both streams it served")
                .isEqualTo(2);
        assertThat(scenarios.regressionSet.accepted(bound.getUuid()))
                .describedAs("the records it was accepted on go with it: they are what it was judged "
                             + "against, and it is not being judged again")
                .isEmpty();
    }

    /// The next stream of a retracted learner is learned afresh. That is the whole difference from
    /// rejecting, and the reason retracting is safe to offer for a binding that is merely wrong.
    @Test
    void theNextStreamOfTheLearnerLearnsItAgain() {
        final Scenarios scenarios = new Scenarios();
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings)
                .retract(doc, bound.getUuid(), "Wrong.", "jo");

        final Script asked = learn(scenarios);
        scenarios.process(asked, doc, stream(3L)).learned();

        assertThat(asked.asked()).describedAs("the model is asked again, from nothing").isNotEmpty();
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("and the learner is bound again")
                .hasSize(1);
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0).getUuid())
                .describedAs("a new one: the retracted binding is gone, not rebound")
                .isNotEqualTo(bound.getUuid());
    }

    /// The two states in which retracting must refuse rather than proceed, each of which would undo
    /// something somebody else decided.
    @Test
    void whatCannotBeRetractedSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        assertThatThrownBy(() -> stage.retract(doc, "not-a-rule", "Wrong.", "jo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a binding of this document");

        scenarios.learnerBindings.append(Scenarios.LEARNER, "doc-1", bound.copy().uuid("a-draft").draft(true).build());
        assertThatThrownBy(() -> stage.retract(doc, "a-draft", "Wrong.", "jo"))
                .describedAs("a draft served nothing, so deciding it is Approve or Reject (A25)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft awaiting review");
    }

    /// The attempt that wrote the binding says it has been taken back.
    ///
    /// Not through the door A25's decisions use: that one closes the attempt *awaiting review* for a
    /// binding, and a retraction is about a binding that was **serving** — whose attempt closed promoted a
    /// long time ago. Sent through it, the call matched nothing and the attempt went on reading
    /// "Promoted 0.93" for a binding that no longer exists.
    @Test
    void theAttemptThatBoundItSaysSo() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of("doc-1")).attempts())
                .describedAs("the attempt that bound it closed promoted")
                .anySatisfy(attempt -> assertThat(attempt.status()).isEqualTo(AttemptStatus.PROMOTED));

        stage.retract(doc, bound.getUuid(), "The timestamps are wrong.", "jo");

        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of("doc-1")).attempts())
                .describedAs("and now says it was retracted, with who asked and why")
                .anySatisfy(attempt -> {
                    assertThat(attempt.status()).isEqualTo(AttemptStatus.RETRACTED);
                    assertThat(attempt.decision()).contains("Retracted by jo").contains("timestamps");
                });
    }

    /// A learner that was given up learns again when somebody asks, from its next stream (A28, design 01
    /// §11.6).
    ///
    /// This is the door the ruling leans on — "they raise an attempt for a given-up learner from the same
    /// screen, so no on-request learning mode is needed". Marking the learner is not enough on its own:
    /// every path reads the give-up first and sentinels the stream before it ever looks at the mark, so
    /// a mark on a given-up learner says nothing at all.
    @Test
    void aLearnerThatGaveUpLearnsAgainWhenSomebodyAsks() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        scenarios.states.giveUp(Scenarios.LEARNER, "doc-1", "The model could not make sense of it");

        // Sent back from the attempt that gave up (A28). The streams that errored are ordinary error
        // streams, reprocessed on the pipeline as any others are (A129, A131): this counts none of them.
        stage.relearn(doc(), Scenarios.LEARNER, "Sent back to be learned again by jo: the feed has been fixed");

        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("the give-up goes, or the mark below it would never be reached")
                .isEmpty();
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("and the learner is marked, with who asked and why, which opens the next attempt")
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("Sent back to be learned again by jo")
                        .contains("feed has been fixed"));
    }

    /// A provisional binding may be accepted rather than waited out (design 01 §6, §11.6).
    ///
    /// It cleared the promotion floor — that is what made it bindable — and what it has not had is
    /// enough records for a held-out judgement. Accepting skips the wait, not the floor.
    @Test
    void aProvisionalBindingCanBeAcceptedRatherThanWaitedOut() {
        final Scenarios scenarios = new Scenarios();
        final Stage stage = scenarios.stage(document -> learn(scenarios), scenarios.learnerBindings);
        final ShapeshifterAiDoc doc = doc();
        scenarios.learnerBindings.append(Scenarios.LEARNER, "doc-1", Binding.builder()
                .uuid("provisional")
                .pipeline(stroom.docref.DocRef.builder()
                        .type("Pipeline").uuid("fragment-1").name("door-v1").build())
                .provisional(true)
                .score(0.91)
                .build());

        stage.accept(doc, "provisional", "jo");

        final Binding accepted = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(accepted.isProvisional())
                .describedAs("it serves on the score it was bound at, no longer marked as waiting")
                .isFalse();
        assertThat(accepted.getPromotedTimeMs()).describedAs("and says when it took over").isNotNull();
        assertThat(accepted.getScore())
                .describedAs("on the score it cleared the floor with, which is not being re-judged")
                .isEqualTo(0.91);

        assertThatThrownBy(() -> stage.accept(doc, "provisional", "jo"))
                .describedAs("a binding already serving on a judgement has nothing to accept")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a provisional binding");
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
                // Named, because a document with no scorers scores every chain 1.0.
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.8, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
