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
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Input;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests a person retracting a serving binding by hand, having read its output and decided it is wrong.
///
/// The outcome is the same as when the promotion gate retracts a provisional binding that fails it: the
/// binding is removed, the learner is left with nothing learned, and the streams the binding produced are
/// counted so that a person can reprocess them.
///
/// Retracting differs from two similar operations. Simply deleting the binding's row would leave the
/// learner's state as though it were bound, and the streams it produced as though they were right.
/// Rejecting is the decision on a draft that never served anything, and it gives the learner up so that
/// it does not learn again. A retracted learner does learn again, because the binding was wrong, not the
/// feed.
class TestRetractingABindingByHand {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void theBindingGoesTheLearnerLearnsAgainAndWhatItProducedIsCounted() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        // A second stream, so that the binding has served a stream as well as the one it was learned from.
        scenarios.process(learn(scenarios), doc, stream(2L)).learned();
        assertThat(scenarios.outputs.boundBy(bound.getUuid(), bound.getPipeline().getUuid()))
                .describedAs("the binding has produced output rows to take back")
                .isNotEmpty();

        final int produced = scenarios.promotion().retract(doc, bound.getUuid(),
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

    /// The next stream of a retracted learner is learned from scratch. That is the main difference from
    /// rejecting, and the reason retracting is safe to offer for a binding that is merely wrong.
    @Test
    void theNextStreamOfTheLearnerLearnsItAgain() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        scenarios.promotion()
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

    /// Retracting is refused, with a reason, for a UUID that is not a binding of the document and for a draft
    /// awaiting review, which must be approved or rejected instead.
    @Test
    void whatCannotBeRetractedSaysSoRatherThanGuessing() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);

        assertThatThrownBy(() -> scenarios.promotion().retract(doc, "not-a-rule", "Wrong.", "jo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a binding of this document");

        scenarios.learnerBindings.append(Scenarios.LEARNER, "doc-1", bound.copy().uuid("a-draft").draft(true).build());
        assertThatThrownBy(() -> scenarios.promotion().retract(doc, "a-draft", "Wrong.", "jo"))
                .describedAs("a draft served nothing, so deciding it is Approve or Reject (A25)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft awaiting review");
    }

    /// The attempt that promoted the binding is marked as retracted, with who retracted it and why.
    ///
    /// This is a different update from the one approving or rejecting a draft makes, which closes an attempt
    /// that is awaiting review. A retracted binding was serving, so its attempt closed as promoted, possibly
    /// long ago, and it is that promoted attempt that must change. Otherwise the attempt would go on
    /// showing as promoted for a binding that no longer exists.
    @Test
    void theAttemptThatBoundItSaysSo() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc();
        scenarios.process(learn(scenarios), doc, stream(1L)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of("doc-1")).attempts())
                .describedAs("the attempt that bound it closed promoted")
                .anySatisfy(attempt -> assertThat(attempt.status()).isEqualTo(AttemptStatus.PROMOTED));

        scenarios.promotion().retract(doc, bound.getUuid(), "The timestamps are wrong.", "jo");

        assertThat(scenarios.attempts.found(new AttemptCriteria(), List.of("doc-1")).attempts())
                .describedAs("and now says it was retracted, with who asked and why")
                .anySatisfy(attempt -> {
                    assertThat(attempt.status()).isEqualTo(AttemptStatus.RETRACTED);
                    assertThat(attempt.decision()).contains("Retracted by jo").contains("timestamps");
                });
    }

    /// A learner that was given up learns again from its next stream when a person sends it back.
    ///
    /// This is how a person gets a given-up learner to learn again, so no separate on-request learning mode is
    /// needed. Marking the learner for relearning is not enough on its own: every path checks the give-up
    /// first and errors the stream before it looks at the mark, so the give-up must be cleared as well.
    @Test
    void aLearnerThatGaveUpLearnsAgainWhenSomebodyAsks() {
        final Scenarios scenarios = new Scenarios();
        scenarios.states.giveUp(Scenarios.LEARNER, "doc-1", "The model could not make sense of it");

        // The learner is sent back from the attempt that gave up. The streams that errored are ordinary error
        // streams, which a person reprocesses on the pipeline like any others, so sending back counts none
        // of them.
        scenarios.supervisorActions().relearn(doc(), Scenarios.LEARNER,
                "Sent back to be learned again by jo: the feed has been fixed");

        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER))
                .describedAs("the give-up goes, or the mark below it would never be reached")
                .isEmpty();
        assertThat(scenarios.states.relearnReason(Scenarios.LEARNER))
                .describedAs("and the learner is marked, with who asked and why, which opens the next attempt")
                .hasValueSatisfying(reason -> assertThat(reason)
                        .contains("Sent back to be learned again by jo")
                        .contains("feed has been fixed"));
    }

    private Script learn(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid("doc-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                // The checks are set explicitly, because a document with no scorers scores every chain 1.0.
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.8,
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
