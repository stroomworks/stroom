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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.scoring.Judgement;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
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

/**
 * Design 02 §5, scenario 1: a CSV feed whose learner is bound to nothing. The stage learns a
 * splitter and a transform in one conversation, judges them over the whole stream, promotes, and the
 * learner gains the learned binding.
 * <p>
 * The corpus case has six records under a header. The coverage threshold sits at 0.8 because the
 * golden splitter reads the header into a variable, which coverage counts as discarded (design 01
 * §9.1 found exactly this), and the minimum records per learner is lowered to fit the case.
 */
class TestScenario01LearnsACsvFeed {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

    private static ShapeshifterAiDoc policy() {
        return Scenarios.document()
                .uuid("policy-1")
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                // The golden splitter reads the header line into a variable, which coverage counts as
                // discarded — design 01 §9.1's finding — so the split scores 6/7 and the floor sits below it.
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
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
        // The transform question carried the parser's real output over the learning prefix (A21 step 2).
        final Configuration transform = (Configuration) script.scripted().get(2);
        assertThat(transform.input()).startsWith("<?xml").contains("<records").contains("<record>");
        // A31: the split and one target per record kind — the header, which becomes nothing, and a data
        // line — were asked between the chain and the parser, and every configuration carried the targets.
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
                        promoted.binding().getPipeline(), promoted.binding().getRecordBoundary(), false,
                        promoted.score(), promoted.binding().getSnapshot()));

        // Scored over the whole stream: coverage and yield on the split, yield on the transform.
        assertThat(run.verdicts()).hasSize(2);
        assertThat(run.verdicts().get(0).judgements()).extracting(j -> j.setting().getType())
                .containsExactly(ScorerType.COMPILE, ScorerType.INPUT_COVERAGE);
        assertThat(run.verdicts().get(1).judgements()).extracting(j -> j.setting().getType())
                .containsExactly(ScorerType.COMPILE, ScorerType.YIELD);
        assertThat(run.verdicts()).allMatch(verdict -> verdict.passed() && verdict.gatesPassed());
        assertThat(run.verdicts().get(1).judgements()).extracting(Judgement::score)
                .extracting(score -> score.value())
                .containsExactly(1.0, 1.0);
        assertThat(promoted.score()).isGreaterThanOrEqualTo(policy().getPromotionFloor());

        // The document gained exactly the learned binding, the binding of the learner it was learned for: its feed
        // and type (A49).
        assertThat(scenarios.learnerBindings.forLearner(Scenarios.LEARNER)).hasSize(1);
        final Binding binding = scenarios.learnerBindings.forLearner(Scenarios.LEARNER).get(0);
        assertThat(binding).isEqualTo(promoted.binding());
        assertThat(binding.getUuid()).describedAs("A26: a learned binding is named stably").isNotNull();
        assertThat(binding.isDraft()).isFalse();
        assertThat(binding.getPromotedTimeMs()).isEqualTo(Scenarios.NOW.toEpochMilli());

        // The fragment is real content: a Source → DSParser → SplitFilter → XSLTFilter pipeline over two new
        // documents, the split giving the transform one record at a time (design 04 §3).
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

        // Same learner again, this time with a script that must not be consulted.
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
