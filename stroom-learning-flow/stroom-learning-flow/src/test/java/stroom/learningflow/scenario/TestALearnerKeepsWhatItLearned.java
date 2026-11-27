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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Learner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests that what a learner learns belongs to the learner, not to the Learning Flow document it runs under.
///
/// A learner is identified by a pipeline and an element, and two learners may run under one document. A
/// binding, give-up or guidance for one learner does not affect the other, which learns the same feed from
/// scratch. A learner whose element is pointed at another document takes its binding with it, because the
/// document only decides where the Supervisor lists the learner and who may act on it.
class TestALearnerKeepsWhatItLearned {

    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    /// A second pipeline under the same document, with an element of the same id.
    private static final Learner NEIGHBOUR = new Learner("pipeline-2", Scenarios.LEARNER.elementId());

    @Test
    void twoLearnersOfOneDocumentLearnTheSameFeedSeparately() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc doc = doc("doc-1", "door-access");
        scenarios.process(learn(scenarios), doc, stream(1L, Scenarios.LEARNER)).learned();
        final Binding first = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        scenarios.states.giveUp(Scenarios.LEARNER, "doc-1", "Nothing to learn");

        final Script asked = learn(scenarios);
        scenarios.process(asked, NEIGHBOUR, doc, stream(2L, NEIGHBOUR)).learned();

        assertThat(asked.asked())
                .describedAs("the neighbour meets the feed afresh: the first learner's binding is not its own")
                .isNotEmpty();
        assertThat(scenarios.learnerBindings.forLearner(NEIGHBOUR))
                .describedAs("and binds its own")
                .singleElement()
                .satisfies(binding -> assertThat(binding.getUuid()).isNotEqualTo(first.getUuid()));
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("leaving the first learner's as it was")
                .containsExactly(first);
        assertThat(scenarios.states.reasonGivenUp(NEIGHBOUR))
                .describedAs("and what one learner gave up, the other has not")
                .isEmpty();
    }

    @Test
    void aLearnerPointedAtAnotherDocumentTakesWhatItLearnedWithIt() {
        final Scenarios scenarios = new Scenarios();
        final LearningFlowDoc before = doc("doc-1", "door-access");
        final LearningFlowDoc after = doc("doc-2", "site-access");
        scenarios.process(learn(scenarios), before, stream(1L, Scenarios.LEARNER)).learned();
        final Binding bound = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        final long hint = scenarios.guidance.given(Scenarios.LEARNER, "doc-1", "Times are local.", "jo");

        // The element now names another document, and its next stream is served under it.
        final Script silent = Script.of();
        scenarios.process(silent, after, stream(2L, Scenarios.LEARNER));

        assertThat(silent.asked())
                .describedAs("what it learned still serves: the binding is the learner's, not the document's")
                .isEmpty();
        assertThat(scenarios.learnerBindings.byUuid(bound.getUuid()))
                .describedAs("and the binding is kept under the document it runs under now")
                .hasValueSatisfying(held -> assertThat(held.docUuid()).isEqualTo("doc-2"));
        assertThat(scenarios.learningPipelines.learners(List.of("doc-2"), Set.of(), List.of(), false, 0, 10).learners())
                .describedAs("so that document's Supervisor shows what serves its pipeline")
                .extracting(SupervisorLearner::getBindingUuid)
                .containsExactly(bound.getUuid());
        assertThat(scenarios.learningPipelines.learners(List.of("doc-1"), Set.of(), List.of(), false, 0, 10).learners())
                .describedAs("and the old one's no longer does")
                .isEmpty();
        scenarios.guidance.withdraw("doc-2", hint);
        assertThat(scenarios.guidance.standing(Scenarios.LEARNER))
                .describedAs("and what was said about its learners, withdrawn by the document it is under now")
                .isEmpty();

        assertThatThrownBy(() -> scenarios.promotion().retract(before, bound.getUuid(), "Wrong.", "jo"))
                .describedAs("the old document's editors can no longer take back what serves another's "
                             + "pipeline")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a binding of this document");
        scenarios.promotion().retract(after, bound.getUuid(), "Wrong.", "jo");
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER))
                .describedAs("while the new one's can")
                .isEmpty();
    }

    private Script learn(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static LearningFlowDoc doc(final String uuid, final String name) {
        return Scenarios.document()
                .uuid(uuid)
                .name(name)
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

    private static Input stream(final long id, final Learner learner) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), learner.pipelineUuid());
    }
}
