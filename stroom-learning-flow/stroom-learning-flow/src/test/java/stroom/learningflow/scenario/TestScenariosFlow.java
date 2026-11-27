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

import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.Question;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.XPathAssertion;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.StageRun;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the learning flow is a graph of steps whose transitions decide where each outcome goes.
///
/// The escalating flow starts direct and asks for a target only when the transform stays short. A shortfall
/// the transform cannot fix, such as a value missing from its input, goes back to the parser by a transition
/// the flow declares, and each transition is followed at most once.
class TestScenariosFlow {

    private static final String FOUR_FIELDS = Scenarios.resource("csv-fields.ds3.xml");
    private static final String TWO_FIELDS = Scenarios.resource("csv-two-fields.ds3.xml");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final String DOC = "doc-1";

    private static LearningFlowDoc doc(final LearningFlow flow) {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .flow(flow)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.9, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0,
                                new BusinessRulesParameters(List.of(new XPathAssertion(
                                                "interactive events name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id[normalize-space(.) != '']")),
                                                true)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    /// Builds [#doc(LearningFlow)] with one check switched off. Whether a check judges at all is set on the
    /// document, not on a flow step.
    ///
    /// @param flow  The learning flow the document uses.
    /// @param off   The check to switch off.
    /// @return The document.
    private static LearningFlowDoc without(final LearningFlow flow, final Check off) {
        final List<CheckSetting> checks = new ArrayList<>();
        for (final CheckSetting setting : doc(flow).getChecks()) {
            checks.add(setting.getCheck() == off
                    ? setting.withState(CheckState.OFF)
                    : setting);
        }
        return doc(flow).copy().checks(checks).build();
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

        final StageRun run = scenarios.process(script, doc(FlowExamples.flow(FlowExample.ESCALATING)),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The direct flow's questions plus the split, which is asked for every input: four questions and no
        // target.
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
                // The transform's two candidates are spent, so its "when spent" rule applies: a target is
                // proposed per kind of record (answered automatically by the script), and the parser is asked
                // again, now against the targets.
                .expect(QuestionMatcher.configuration("DSParser").withTargets(1)).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter").withTargets(1)).reply(Scenarios.fenced(XSLT));

        final StageRun run = scenarios.process(script, doc(FlowExamples.flow(FlowExample.ESCALATING)),
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
        // A splitter that consumes every line and emits nothing passes coverage but is not a split at all. It
        // is refused as a reply, rather than being judged short on some check.
        final String consumesAll = Structure.LINE_SPLIT.replace("<data name=\"record\" value=\"$1\"/>", "");
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.split().withoutFeedback()).reply(Scenarios.fenced(consumesAll))
                .expect(QuestionMatcher.split().withFeedbackMentioning("The split produced no records"))
                .reply(Scenarios.fenced(Structure.LINE_SPLIT))
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final LearningFlow flow = FlowExamples.flow(FlowExample.TARGET_FIRST).withSteps(List.of(
                FlowStep.of(QuestionKind.CHAIN),
                FlowStep.builder(QuestionKind.SPLIT).abandonOn(StepOutcome.WHOLENESS_SHORT).build(),
                FlowStep.builder(QuestionKind.TARGET).kinds(3).build(),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()));

        final StageRun run = scenarios.process(script, doc(flow), stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        assertThat(run.transcript().get(1).outcome()).isEqualTo(StepOutcome.REFUSED);
    }

    /// Builds a target-first flow in which a preservation shortfall at the transform goes back to the parser.
    /// The callers switch preservation off on the document ([#without]), so that the stream-level scorers do
    /// not fail the parser first on the fields it cannot find.
    private static LearningFlow preservationAtTheTransform() {
        return FlowExamples.flow(FlowExample.TARGET_FIRST).withSteps(List.of(
                FlowStep.of(QuestionKind.CHAIN),
                FlowStep.of(QuestionKind.SPLIT),
                FlowStep.builder(QuestionKind.TARGET).kinds(3).build(),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.TRANSFORM)
                        .goTo(StepOutcome.PRESERVATION_SHORT, "parser")
                        .build()));
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

        final StageRun run = scenarios.process(script, without(preservationAtTheTransform(), Check.PRESERVATION),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        // The parser passed its own checks. The transform's fidelity check found the values missing from its
        // input and reported a preservation shortfall, which the flow sent straight back to the parser.
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

        final StageRun run = scenarios.process(script, without(preservationAtTheTransform(), Check.PRESERVATION),
                stream(1, CsvLines.lines(6))).learned();

        script.verifyExhausted();
        // The transform step leaves the flow by its default exit, which is to park.
        assertThat(run.decision()).isInstanceOf(Parked.class);
        assertThat(((Parked) run.decision()).reason())
                .isEqualTo("The learning steps went round in a loop.");
        assertThat(((Parked) run.decision()).report().getDetails()).first().asString()
                .startsWith("The rule \"if the records lose what the targets need, go back to step 4 (Write the "
                            + "parser)\" on step 5 (Write the transform) would be followed a second time");
    }

    @Test
    void feedbackCarriedByATransitionReachesTheNextQuestionAskedWhenTheStepItNamesIsSkipped() {
        // A 'goto split' over raw text, where the split step is guarded 'when xml': the split step is skipped,
        // and the preservation shortfall carried by the jump reaches the parser, which is asked next. The
        // targets are asked for before the split so that the parser is the first question after it.
        //
        // The rule is on the parser step because every check that can measure a step judges it, and
        // preservation measures the parser first.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser").withoutFeedback()).reply(Scenarios.fenced(TWO_FIELDS))
                .expect(QuestionMatcher.configuration("DSParser")
                        .withFeedbackMentioning("the records carry no value for [logon, office]"))
                .reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.process(script, doc(FlowExamples.flow(FlowExample.TARGET_FIRST)
                .withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.TARGET).kinds(3).build(),
                        FlowStep.of(QuestionKind.SPLIT, StepGuard.XML),
                        FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.PARSER)
                                .goTo(StepOutcome.PRESERVATION_SHORT, "split")
                                .build(),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()))),
                stream(1, CsvLines.lines(6))).learned();
        assertThat(run.decision()).describedAs(run.decision().toString()).isInstanceOf(Promoted.class);
        script.verifyExhausted();
        assertThat(script.asked()).noneMatch(Split.class::isInstance);
    }

    @Test
    void onPassedGoesWhereTheStepSaysOnceTheWholeStepHasPassed() {
        // A CONFIGURE step over the whole chain passes when every element has passed, not at the first. A
        // pass may also stop learning, where the flow says so.
        final Scenarios scenarios = new Scenarios();
        final Script script = scenarios.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        final StageRun run = scenarios.process(script, doc(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .goTo(StepOutcome.PASSED, "end")
                        .build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .id("never")
                        .role(ConfigureRole.TRANSFORM)
                        .build()))), stream(1, CsvLines.lines(6))).learned();
        script.verifyExhausted();
        assertThat(run.decision()).isInstanceOf(Promoted.class);
        assertThat(steps(run)).containsExactly("chain", "configure", "configure");

        // A new `Scenarios`, because bindings are stored rows that outlive a stage: a second run for the same
        // learner would use what the first promoted instead of learning.
        final Scenarios afresh = new Scenarios();
        final Script refused = afresh.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(FOUR_FIELDS));
        final StageRun abandoned = afresh.process(refused, doc(FlowExamples.flow(FlowExample.DIRECT).withSteps(
                List.of(FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.PARSER)
                        .abandonOn(StepOutcome.PASSED)
                        .build(),
                        FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.TRANSFORM)
                                .build()))), stream(2, CsvLines.lines(6))).learned();
        refused.verifyExhausted();
        assertThat(abandoned.decision()).isInstanceOf(GivenUp.class);
        assertThat(((GivenUp) abandoned.decision()).reason())
                .isEqualTo("Stopped at \"Write the parser\", as its rule says to when it passes.");
        assertThat(((GivenUp) abandoned.decision()).report().getDetails())
                .containsExactly("The rule says to stop learning when it passes.");

        // Stopping at once names the outcome and the step, not candidates that were never spent. This needs a
        // new `Scenarios`, because the learner above was given up, and a given-up learner asks nothing.
        final Scenarios fresh = new Scenarios();
        final Script prose = fresh.script(FOUR_FIELDS, XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply("not a configuration");
        final StageRun atOnce = fresh.process(prose, doc(FlowExamples.flow(FlowExample.DIRECT).withSteps(
                List.of(FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .abandonOn(StepOutcome.REFUSED)
                        .build()))),
                stream(3, CsvLines.lines(6))).learned();
        prose.verifyExhausted();
        assertThat(((GivenUp) atOnce.decision()).reason())
                .isEqualTo("Stopped at \"Write every element\", as its rule says to when the answer isn't in the "
                           + "reply format.")
                .doesNotContain("answers fell short");
    }
}
