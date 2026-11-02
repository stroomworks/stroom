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

import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Parked;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.BusinessRulesParameters;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design 02 §5, scenarios 41 and 42 (ruling A37): the flow is a graph. A flow that starts direct asks for a
 * target only when the transform stays short; a shortfall the transform cannot fix — a value its input
 * lacks — goes back to the parser by the transition the flow declares, and a transition is taken once.
 */
class TestScenariosFlow {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TWO_FIELDS = Scenarios.resource("csv-two-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final String DOC = "doc-1";

    private static ShapeshifterAiDoc doc(final LearningFlow flow) {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(flow)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.9, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new ScorerSetting(ScorerType.BUSINESS_RULES, 1.0, 1.0, false,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                        "interactive events name the user",
                                        "not(EventDetail/Authenticate) "
                                        + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                        true))))
                .build();
    }

    private static Input stream(final long id, final String data) {
        return new Input(id, "DOOR-ACCESS", "Raw Events", Map.of("Format", "CSV"), data);
    }

    private static List<String> steps(final StageRun run) {
        return run.transcript().stream().map(Exchange::step).toList();
    }

    private static List<StepOutcome> outcomes(final StageRun run) {
        return run.transcript().stream().map(Exchange::outcome).toList();
    }

    @Test
    void scenario41ACleanFeedIsLearnedDirectUnderTheEscalatingFlow() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withTargets(0)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(0)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(LearningFlow.of(FlowExample.ESCALATING)),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // Direct's cost plus the split (A39): four questions, no target.
        assertThat(script.asked()).hasSize(4);
        assertThat(script.asked()).noneMatch(TargetFor.class::isInstance);
        assertThat(script.asked().get(1)).isInstanceOf(Split.class);
        assertThat(steps(run)).containsExactly("chain", "split", "parser", "first");
        assertThat(outcomes(run)).containsOnly(StepOutcome.PASSED);
    }

    @Test
    void scenario41ATargetIsAskedForOnlyWhenTheTransformStaysShort() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withTargets(0)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter").withFeedbackMentioning("Extraction quality scored"))
                .reply(Scenarios.fenced(DEGENERATE))
                // The transform's two candidates are spent: 'on spent goto target' fires, a target is proposed
                // per kind (the structure answers), and the parser is asked again, now against the targets.
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(LearningFlow.of(FlowExample.ESCALATING)),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(script.asked()).filteredOn(TargetFor.class::isInstance).hasSize(1);
        assertThat(script.asked()).filteredOn(Split.class::isInstance).hasSize(1);
        assertThat(steps(run)).containsExactly("chain", "split", "parser", "first", "first", "target", "again",
                "transform");
        assertThat(outcomes(run)).containsExactly(StepOutcome.PASSED, StepOutcome.PASSED, StepOutcome.PASSED,
                StepOutcome.QUALITY_SHORT, StepOutcome.QUALITY_SHORT, StepOutcome.PASSED, StepOutcome.PASSED,
                StepOutcome.PASSED);
        assertThat(run.transcript().get(4).candidate()).isEqualTo(2);
    }

    @Test
    void aSplitThatEmitsNoRecordsIsRefusedWhateverChecksTheStepNames() {
        // A splitter that consumes every line and emits nothing passes coverage and is no split at all: it is
        // refused as a reply, not judged short on a check the step left out.
        final String consumesAll = Structure.LINE_SPLIT.replace("<data name=\"record\" value=\"$1\"/>", "");
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(consumesAll))
                .expect(QuestionMatcher.split().withFeedbackMentioning("The split produced no records"))
                .reply(Scenarios.fenced(Structure.LINE_SPLIT))
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final LearningFlow flow = LearningFlow.of(FlowExample.TARGET_FIRST).withSteps(List.of(
                FlowStep.parse("CHAIN"),
                FlowStep.parse("SPLIT checks coverage on wholeness-short abandon"),
                FlowStep.parse("TARGET kinds 3"),
                FlowStep.parse("CONFIGURE parser"),
                FlowStep.parse("CONFIGURE transform")));

        final StageRun run = scenarios.process(script, doc(flow), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(run.transcript().get(1).outcome()).isEqualTo(StepOutcome.REFUSED);
    }

    /**
     * Target-first, with preservation not judged at the parser so that the transform meets the gap: the
     * transform's checks are fidelity alone, so the stream-level scorers do not fail it first on the fields
     * it cannot find.
     */
    private static LearningFlow preservationAtTheTransform() {
        return LearningFlow.of(FlowExample.TARGET_FIRST).withSteps(List.of(
                FlowStep.parse("CHAIN"),
                FlowStep.parse("SPLIT"),
                FlowStep.parse("TARGET kinds 3"),
                FlowStep.parse("CONFIGURE parser checks coverage,yield"),
                FlowStep.parse("CONFIGURE transform checks fidelity on preservation-short goto parser")));
    }

    @Test
    void scenario42AShortfallAtTheTransformSendsTheParserBack() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for [logon, office]"))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(preservationAtTheTransform()),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The parser passed its own checks; the transform's fidelity check found the value absent from its
        // input and said so as the parser's shortfall, which the flow routed at once.
        final List<Question> scripted = script.scripted();
        assertThat(scripted).hasSize(5);
        final List<Exchange> configured = run.transcript().stream()
                .filter(turn -> turn.question() instanceof Question.Configuration)
                .toList();
        assertThat(configured).extracting(Exchange::step)
                .containsExactly("parser", "transform", "parser", "transform");
        assertThat(configured).extracting(Exchange::outcome).containsExactly(StepOutcome.PASSED,
                StepOutcome.PRESERVATION_SHORT, StepOutcome.PASSED, StepOutcome.PASSED);
    }

    @Test
    void scenario42ATransitionIsTakenOnce() {
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(preservationAtTheTransform()),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        // The transform's step leaves the flow by its default exit, park (A83).
        assertThat(run.decision()).isInstanceOf(Parked.class);
        assertThat(((Parked) run.decision()).reason())
                .contains("'on preservation-short goto parser' from step 'transform' a second time");
    }

    @Test
    void feedbackCarriedByATransitionReachesTheNextQuestionAskedWhenTheStepItNamesIsSkipped() {
        // 'goto split' over raw text where the split step is guarded 'when xml': the step is skipped, and the
        // preservation shortfall the jump carried reaches the parser, which is what is asked next. The targets
        // are asked before the split so that the parser is the first question after it.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback()).reply(Scenarios.fenced(XSLT))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for [logon, office]"))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.process(script, doc(LearningFlow.of(FlowExample.TARGET_FIRST).withSteps(List.of(
                FlowStep.parse("CHAIN"),
                FlowStep.parse("TARGET kinds 3"),
                FlowStep.parse("SPLIT when xml"),
                FlowStep.parse("CONFIGURE parser checks coverage,yield"),
                FlowStep.parse("CONFIGURE transform checks fidelity on preservation-short goto split")))),
                stream(1, CsvLines.lines(6))).learned();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(script.asked()).noneMatch(Split.class::isInstance);
    }

    @Test
    void onPassedGoesWhereTheStepSaysOnceTheWholeStepHasPassed() {
        // A CONFIGURE over the whole chain passes when every element has, not at its first; a pass may also
        // abandon, where the flow says so.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.process(script, doc(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE on passed goto end"),
                FlowStep.parse("never: CONFIGURE transform")))), stream(1, CsvLines.lines(6))).learned();
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(steps(run)).containsExactly("chain", "configure", "configure");

        // Its own scenarios: bindings are rows now (A41) and outlive a stage, so a second run against the same
        // feed would bind what the first promoted instead of learning.
        final Scenarios afresh = new Scenarios();
        final Script refused = afresh.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS));
        final StageRun abandoned = afresh.process(refused, doc(LearningFlow.of(FlowExample.DIRECT).withSteps(
                List.of(FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE parser on passed abandon"),
                        FlowStep.parse("CONFIGURE transform")))), stream(2, CsvLines.lines(6))).learned();
        refused.verifyExhausted();
        assertThat(abandoned.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) abandoned.decision()).reason()).contains("on a pass of step 'parser'");

        // An at-once abandon names the outcome and the step, not candidates that were never spent. A fresh
        // stage: the learner above was given up, and a given-up learner asks nothing (scenario 11).
        final Scenarios fresh = new Scenarios();
        final Script prose = fresh.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply("not a configuration");
        final StageRun atOnce = fresh.process(prose, doc(LearningFlow.of(FlowExample.DIRECT).withSteps(
                List.of(FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE on refused abandon")))),
                stream(3, CsvLines.lines(6))).learned();
        prose.verifyExhausted();
        assertThat(((GivenUp) atOnce.decision()).reason())
                .contains("Step 'configure' leaves the flow on refused")
                .doesNotContain("after 5 attempts");
    }
}
