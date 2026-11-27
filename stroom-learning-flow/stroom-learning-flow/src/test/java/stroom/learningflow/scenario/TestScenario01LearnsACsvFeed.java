/*
 * Copyright 2016-2026 Crown Copyright
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
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.scoring.Judgement;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.stage.RegressionSet.Kind;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the basic learning case: a CSV feed whose learner has no binding yet.
///
/// The stage learns a Data Splitter configuration and a transform in one session, judges them over the whole
/// stream, promotes them, and the learner gains the learned binding. A second stream is then served by that
/// binding without asking the model anything.
///
/// The corpus case has six records under a header. The coverage threshold is 0.8 because the expected
/// splitter reads the header into a variable, which coverage counts as discarded. The minimum number of
/// records needed to learn is lowered to fit the case.
class TestScenario01LearnsACsvFeed {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    private static LearningFlowDoc policy() {
        return Scenarios.document()
                .uuid("policy-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                // The expected splitter reads the header line into a variable, which coverage counts as
                // discarded, so the split scores 6/7 and the coverage threshold is set below that.
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
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

    private static Input input() {
        return new Input(
                1L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV", "System", "Door Access"), CSV.input());
    }

    @Test
    void learnsPromotesAndServes() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()
                        .allowing("DSParser", "XSLTFilter")
                        .withKey("Feed", "DOOR-ACCESS")
                        .withKey("Type", "Raw Events")
                        .withoutFeedback())
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback())
                .reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, policy(), input()).learned();

        script.verifyExhausted();
        // The transform question carried the parser's real output over the learning prefix, not a description
        // of it.
        final Configuration transform = (Configuration) script.scripted().get(2);
        assertThat(transform.input()).startsWith("<?xml").contains("<records").contains("<record>");
        // The target-first flow asks the split question and one target question per kind of record (the
        // header, which becomes no event, and a data line) between the chain and the parser questions, and
        // the transform question carries the targets.
        assertThat(script.asked()).hasSize(6);
        assertThat(script.asked().get(1)).isInstanceOf(Question.Split.class);
        assertThat(script.asked().get(2)).isInstanceOf(Question.TargetFor.class);
        assertThat(script.asked().get(3)).isInstanceOf(Question.TargetFor.class);
        assertThat(transform.targets()).hasSize(2);
        assertThat(transform.targets().get(0).event()).describedAs("the header becomes no event").isEmpty();
        assertThat(transform.targets().get(1).event()).isPresent();
        assertThat(((Chain) script.asked().get(0)).sample().text())
                .describedAs("the model learns from a prefix, header + 5 of 6 records; the whole stream is held out")
                .hasLineCount(6);

        assertThat(run.decision()).isInstanceOf(Promoted.class);
        final Promoted promoted = (Promoted) run.decision();
        assertThat(run.output()).isEqualTo(EXPECTED_EVENTS);
        assertThat(run.transcript()).hasSize(6);
        assertThat(scenarios.states.reasonGivenUp(Scenarios.LEARNER)).isEmpty();
        assertThat(run.produced())
                .describedAs("design 01 §7.3 rule 3: the output records what produced it")
                .isEqualTo(new Produced(run.doc().getUuid(), promoted.binding().getUuid(),
                        promoted.binding().getPipeline(), promoted.binding().getRecordBoundary(),
                        promoted.score(), promoted.binding().getSnapshot()));

        // Scored over the whole stream: compile and coverage on the split, and compile, completeness and
        // yield on the transform.
        assertThat(run.verdicts()).hasSize(2);
        assertThat(run.verdicts().get(0).judgements()).extracting(j -> j.setting().getCheck())
                .containsExactly(Check.COMPILE, Check.COVERAGE);
        assertThat(run.verdicts().get(1).judgements()).extracting(j -> j.setting().getCheck())
                .containsExactly(Check.COMPILE, Check.COMPLETENESS, Check.YIELD);
        assertThat(run.verdicts()).allMatch(verdict -> verdict.judgements().stream().allMatch(Judgement::metThreshold));
        assertThat(run.verdicts().get(1).judgements()).extracting(Judgement::score)
                .extracting(score -> score.value())
                .containsExactly(1.0, 1.0, 1.0);
        // A candidate is promoted when every gate passes, with no overall score threshold to clear. The
        // score is the average of the checks that ran.
        assertThat(promoted.score()).isBetween(0.0, 1.0);

        // The learner it was learned for now has exactly one binding, the one that was promoted.
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(binding).isEqualTo(promoted.binding());
        assertThat(binding.getUuid()).describedAs("A26: a learned binding is named stably").isNotNull();
        assertThat(binding.isDraft()).isFalse();
        assertThat(binding.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());

        // The fragment is real content: a Source → DSParser → SplitFilter → XSLTFilter pipeline using two new
        // documents (a text converter and an XSLT), the split filter giving the transform one record at a time.
        assertThat(binding.getPipeline().getName())
                .describedAs("named where the learner's element says (A113): here, for its pipeline")
                .isEqualTo(Scenarios.LEARNER.pipelineUuid());
        assertThat(run.learner()).isEqualTo(Scenarios.LEARNER);
        assertThat(scenarios.stores.pipelines.readDocument(binding.getPipeline()).getPipelineData()
                .getAddedElements())
                .extracting(element -> element.getType())
                .containsExactly("Source", "DSParser", "SplitFilter", "XSLTFilter");
        assertThat(scenarios.stores.textConverters.list()).hasSize(1);
        assertThat(scenarios.stores.xslts.list()).hasSize(1);
        assertThat(scenarios.regressionSet.accepted(binding.getUuid()))
                .describedAs("A18, A56: the corpus is per binding and per record — the five records of the sample, "
                             + "the held-out fifth of the stream having been judged on and not learned from")
                .hasSize(5)
                .allSatisfy(entry -> {
                    assertThat(entry.kind()).isEqualTo(Kind.TRANSLATION);
                    assertThat(entry.text()).describedAs("the record's own text, by the parser's span").isNotBlank();
                    assertThat(entry.output()).contains("<Event>");
                });
    }

    @Test
    void theSecondStreamOfTheLearnerIsServedWithoutLearning() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun first = scenarios.process(script, policy(), input()).learned();
        script.verifyExhausted();

        // The same learner again, this time with a script that must not be consulted.
        final Script silent = Script.of();
        final Input again = new Input(
                2L, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV", "System", "Door Access"), CSV.input());
        final StageRun second = scenarios.process(silent, first.doc(), again).first();

        assertThat(second.decision()).isInstanceOf(Bound.class);
        assertThat(second.transcript()).isEmpty();
        assertThat(silent.asked()).isEmpty();
        assertThat(scenarios.attempts.forDocument("policy-1", 10)).describedAs("no second session").hasSize(1);
        assertThat(Scenarios.canonical(scenarios.served(first.doc(), again).text()))
                .describedAs("the written fragment, read back and streamed, produces the same translation")
                .isEqualTo(Scenarios.canonical(EXPECTED_EVENTS));
    }
}
