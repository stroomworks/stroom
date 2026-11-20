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

package stroom.shapeshifter.shared;

import stroom.docref.DocRef;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.util.json.JsonUtil;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class TestShapeshifterAiDoc {

    private static final DocRef EVENTS_V4 = DocRef.builder()
            .type("XMLSchema")
            .uuid("befcc474-36e4-4db4-a610-bd1fe6825cb1")
            .name("event-logging v4.1.0")
            .build();

    @Test
    void fullyPopulatedDocRoundTrips() {
        final ShapeshifterAiDoc doc = populated();

        final String json = JsonUtil.writeValueAsString(doc);
        final ShapeshifterAiDoc read = JsonUtil.readValue(json, ShapeshifterAiDoc.class);

        assertThat(read).isEqualTo(doc);
        assertThat(read.getChecks()).containsExactlyElementsOf(doc.getChecks());
        assertThat(read.getFlow().getSteps()).extracting(FlowStep::format)
                .containsExactly("CHAIN", "TARGET when text kinds 1", "CONFIGURE candidates 2");
    }

    @Test
    void aLearningFlowKnowsWhatIsWrongWithItsSteps() {
        assertThat(LearningFlow.of(FlowExample.TARGET_FIRST).problems()).isEmpty();
        assertThat(new LearningFlow(null, null, null).getSteps()).isEqualTo(FlowExample.DIRECT.steps());
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.of(QuestionKind.CONFIGURE), FlowStep.of(QuestionKind.CHAIN))).problems())
                .containsExactly("The first step must be 'Choose the elements'",
                        "The last step must write the translation, or be the fallback");
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.SPLIT),
                FlowStep.of(QuestionKind.SPLIT), FlowStep.of(QuestionKind.CONFIGURE))).problems())
                .containsExactly("The flow has 2 'Find what one record is' steps; it may have only one");
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of()).problems())
                .containsExactly("The flow has no steps");
        // A transition out of CHAIN would leave the flow with no chain to run: refused at the door, not at a
        // null chain in the interpreter.
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN on refused goto end"), FlowStep.of(QuestionKind.CONFIGURE))).problems())
                .containsExactly("'Choose the elements' can't have rules: nothing else can run until the elements "
                                 + "are chosen");
        // Two transitions on one outcome, or two on spent, contradict each other: refused rather than the
        // first silently winning.
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE on spent goto end on spent abandon"))).problems())
                .containsExactly("Step 2 (Write every element) has two rules for when it runs out of tries; only "
                                 + "the first would be followed");
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"),
                FlowStep.parse("CONFIGURE on refused goto end on refused abandon on spent abandon"))).problems())
                .containsExactly("Step 2 (Write every element) has two rules for when the answer isn't in the "
                                 + "reply format; only the first would be followed");
    }

    @Test
    void aFlowNamesItsExitsByTheirEffect() {
        // A83: park and give-up beside end; abandon is the step's own default and names no exit itself;
        // none of the three may name a step.
        final FlowStep parks = FlowStep.parse("CONFIGURE parser candidates 1 on spent goto park");
        assertThat(parks.format()).isEqualTo("CONFIGURE parser candidates 1 on spent goto park");
        assertThat(parks.transitionOnSpent().exits()).isTrue();
        assertThat(parks.transitionOnSpent().namedExit()).isEqualTo(FlowExit.PARK);
        final FlowStep givesUp = FlowStep.parse("CONFIGURE transform on run-failed goto give-up");
        assertThat(givesUp.transitionOn(StepOutcome.RUN_FAILED).namedExit()).isEqualTo(FlowExit.GIVE_UP);
        final FlowStep abandons = FlowStep.parse("CONFIGURE transform on spent abandon");
        assertThat(abandons.transitionOnSpent().exits()).isTrue();
        assertThat(abandons.transitionOnSpent().namedExit()).isNull();
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE parser on spent goto park"),
                FlowStep.parse("CONFIGURE transform on spent goto give-up"))).problems()).isEmpty();
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("park: CONFIGURE"))).problems())
                .containsExactly("Step 2 (Write every element) has the id 'park', which the flow keeps for "
                                 + "itself; delete the step and add it again");
    }

    @Test
    void theChainStepNamesTheElementsTheChainQuestionChoosesFrom() {
        // A95: elements are the Chain step's, written as a word of its line; the document derives what it
        // allows from them, every learnable one where the step names none; no other step may name any.
        final FlowStep chain = FlowStep.parse("CHAIN elements DSParser,XSLTFilter candidates 2");
        assertThat(chain.getElements()).containsExactly("DSParser", "XSLTFilter");
        assertThat(chain.format()).isEqualTo("CHAIN candidates 2 elements DSParser,XSLTFilter");
        assertThat(FlowStep.parse("CHAIN").getElements()).isEmpty();
        assertThat(FlowStep.parse("CHAIN").effectiveElements()).isEqualTo(FlowStep.LEARNABLE_ELEMENTS);
        assertThatThrownBy(() -> FlowStep.parse("CONFIGURE parser elements DSParser"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only CHAIN names elements");
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").name("n")
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(chain, FlowStep.parse("CONFIGURE"))))
                .build();
        assertThat(doc.getAllowedElements()).containsExactly("DSParser", "XSLTFilter");
        assertThat(ShapeshifterAiDoc.builder().uuid("d").name("n").build().getAllowedElements())
                .isEqualTo(FlowStep.LEARNABLE_ELEMENTS);
        assertThat(ShapeshifterAiDoc.builder().uuid("d").name("n").allowedElements(List.of("XMLParser")).build()
                .getFlow().getSteps().get(0).getElements()).containsExactly("XMLParser");
    }

    @Test
    void aProblemNamesTheStepItIsAbout() {
        // A88: the check says which step, where it is about one, so an editor can paint it.
        final List<FlowProblem> problems = LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CONFIGURE on refused goto nowhere"), FlowStep.parse("SPLIT"))).check();
        assertThat(problems).extracting(FlowProblem::getStep, FlowProblem::getMessage).containsExactly(
                tuple(0, "The first step must be 'Choose the elements'"),
                tuple(1, "The last step must write the translation, or be the fallback"),
                tuple(null, "The flow needs a 'Choose the elements' step"),
                tuple(0, "The rule 'if the answer isn't in the reply format' on step 1 (Write every element) "
                         + "goes to 'nowhere', which isn't a step"));
    }

    @Test
    void anIdIsAWordHoweverItArrives() {
        // The line form checks the id; the JSON form must too, or a saved id the line form cannot read back
        // breaks the round trip.
        assertThatThrownBy(() -> new FlowStep("my step", QuestionKind.CONFIGURE, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'my step' is not a step id");
        assertThat(new FlowStep(" Again ", QuestionKind.CONFIGURE, null, null, null, null, null, null).getId())
                .isEqualTo("again");
    }

    @Test
    void aFlowIsAGraphOverTypedOutcomes() {
        // A37: every example holds; ids default to the kind or the role; a goto must name a step, once.
        for (final FlowExample example : FlowExample.values()) {
            assertThat(LearningFlow.of(example).problems()).describedAs(example.name()).isEmpty();
        }
        assertThat(LearningFlow.of(FlowExample.ESCALATING).getSteps()).extracting(FlowStep::effectiveId)
                .containsExactly("chain", "split", "parser", "first", "target", "again", "transform", "admit");
        assertThat(LearningFlow.of(FlowExample.ESCALATING).step("split").getWhen()).isEqualTo(StepGuard.ALWAYS);
        assertThat(LearningFlow.of(FlowExample.ESCALATING).step("parser").getTransitions())
                .extracting(Transition::format).containsExactly("on yield-short goto split");
        assertThat(LearningFlow.of(FlowExample.ESCALATING).step("first").getTransitions())
                .extracting(Transition::format).containsExactly("on passed goto end", "on spent goto target");
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("end: CONFIGURE"))).problems())
                .containsExactly("Step 2 (Write every element) has the id 'end', which the flow keeps for "
                                 + "itself; delete the step and add it again");
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE parser"), FlowStep.parse("CONFIGURE parser")))
                .problems())
                .containsExactly("Step 3 (Write the parser again) has the same id as an earlier step, 'parser'; "
                                 + "delete it and add it again");
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE on refused goto nowhere on spent abandon")))
                .problems())
                .containsExactly("The rule 'if the answer isn't in the reply format' on step 2 (Write every "
                                 + "element) goes to 'nowhere', which isn't a step");
        // A step's name is matched without regard to case, as everything else on the line is.
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"),
                FlowStep.parse("First: CONFIGURE parser on spent goto Target on passed goto END"),
                FlowStep.parse("TARGET"), FlowStep.parse("CONFIGURE transform"))).problems()).isEmpty();
        assertThat(FlowStep.parse("First: CONFIGURE parser on spent goto Target").format())
                .isEqualTo("first: CONFIGURE parser on spent goto target");
        // An id given on purpose that shadows another step's default name is a collision too.
        assertThat(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                FlowStep.parse("CHAIN"), FlowStep.parse("target: CONFIGURE parser"), FlowStep.parse("TARGET"),
                FlowStep.parse("CONFIGURE transform"))).problems())
                .containsExactly("Step 3 (Agree the target events) has the same id as an earlier step, 'target'; "
                                 + "delete it and add it again");
    }

    @Test
    void aStepLineCarriesItsIdRoleAndTransitions() {
        final String line = "first: CONFIGURE transform candidates 2 "
                            + "on preservation-short goto parser on spent abandon";
        final FlowStep step = FlowStep.parse(line);
        assertThat(step.format()).isEqualTo(line);
        assertThat(step.getId()).isEqualTo("first");
        assertThat(step.getRole()).isEqualTo(ConfigureRole.TRANSFORM);
        assertThat(step.transitionOn(StepOutcome.PRESERVATION_SHORT).getGoTo()).isEqualTo("parser");
        assertThat(step.transitionOn(StepOutcome.REFUSED)).isNull();
        assertThat(step.transitionOnSpent().exits()).isTrue();
        assertThat(FlowStep.parse("CONFIGURE parser").effectiveId()).isEqualTo("parser");
        assertThat(FlowStep.parse("split").effectiveId()).isEqualTo("split");
        assertThatThrownBy(() -> FlowStep.parse("SPLIT parser")).hasMessageContaining("Only CONFIGURE takes a role");
        // 'checks' was a step's own list of checks; the one model of checks took it away, so the word is
        // no longer part of the grammar
        assertThatThrownBy(() -> FlowStep.parse("CONFIGURE checks conformance"))
                .hasMessageContaining("'checks' is not when, candidates, kinds, elements or on");
        assertThatThrownBy(() -> FlowStep.parse("CONFIGURE on flimsy goto parser"))
                .hasMessageContaining("'on flimsy' is not an outcome");
        assertThatThrownBy(() -> FlowStep.parse("CONFIGURE on spent"))
                .hasMessageContaining("'on' needs an outcome");
        assertThatThrownBy(() -> FlowStep.parse("CONFIGURE on spent goto"))
                .hasMessageContaining("must be followed by 'goto <step>' or 'abandon'");
        assertThatThrownBy(() -> FlowStep.parse("1st: CHAIN")).hasMessageContaining("is not a step id");
        // The check's shortfall is its outcome, and the outcome reads back from its word.
        assertThat(Check.FIDELITY.shortfall()).isEqualTo(StepOutcome.FIDELITY_SHORT);
        assertThat(StepOutcome.parse("coverage-short")).isEqualTo(StepOutcome.COVERAGE_SHORT);
        assertThat(Check.COMPILE.shortfall()).describedAs("compile's own outcome")
                .isEqualTo(StepOutcome.COMPILE_FAILED);
        assertThat(Check.REGRESSION.shortfall()).describedAs("judges promotion, not a step").isNull();
    }

    @Test
    void aStepReadsBackFromItsLine() {
        assertThat(FlowStep.parse("target when xml candidates 4 kinds 2").format())
                .isEqualTo("TARGET when xml candidates 4 kinds 2");
        assertThat(FlowStep.parse("  CHAIN ").format()).isEqualTo("CHAIN");
        assertThat(FlowStep.parse("SPLIT when json").getWhen()).isEqualTo(StepGuard.JSON);
        assertThatThrownBy(() -> FlowStep.parse("SPLIT when sometimes"))
                .hasMessageContaining("'when sometimes' is not one of always, text, xml or json");
        assertThatThrownBy(() -> FlowStep.parse("SPLIT candidates"))
                .hasMessageContaining("'candidates' needs a value");
        assertThatThrownBy(() -> FlowStep.parse("SPLIT kinds 0"))
                .hasMessageContaining("'kinds' must be at least 1");
        assertThatThrownBy(() -> FlowStep.parse("ASK")).hasMessageContaining("'ASK' is not a question kind");
        // The limits hold however the step is made, not only from its line.
        assertThatThrownBy(() -> new FlowStep(QuestionKind.TARGET, null, null, 0))
                .hasMessageContaining("'kinds' must be at least 1");
    }

    @Test
    void aBlankTemplateOverrideIsNoOverride() {
        final LearningFlow flow = LearningFlow.of(FlowExample.DIRECT)
                .withTemplates(Map.of(Template.CHAIN, "  \n", Template.SPLIT, "Cut it: ${sample}"));
        assertThat(flow.getTemplates()).containsOnlyKeys(Template.SPLIT);
    }

    @Test
    void creatorAppliesDefaultsToNullArguments() {
        // The creator sees nulls for every field a stored doc predates, so the defaults must come from it
        // rather than only from the builder.
        final ShapeshifterAiDoc doc = new ShapeshifterAiDoc(
                "uuid", "name", "1", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        assertThat(doc.getLearningMode()).isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getTargetSchema()).describedAs("unset is the default target, event-logging v3.0.0")
                .isNull();
        assertThat(doc.getRelearnThreshold()).isEqualTo(0.8);
        assertThat(doc.getAllowedElements())
                .describedAs("every learnable element (A10) until a document narrows it")
                .containsExactly("DSParser", "JSONParser", "XMLFragmentParser", "XSLTFilter");
        assertThat(doc.getFlow()).isEqualTo(LearningFlow.of(FlowExample.DIRECT));
        assertThat(doc.getMaxAttempts()).describedAs("five since the first live run, design 02 §6.2").isEqualTo(5);
        assertThat(doc.getAttemptBudgetMs()).isEqualTo(60_000L);
        assertThat(doc.getTokenBudget()).isNull();
        assertThat(doc.getSampleSizeLimit()).isEqualTo(8_192);
        assertThat(doc.getPromotionMode()).describedAs("A9").isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(doc.getHeldOutFraction()).isEqualTo(0.2);
        assertThat(doc.getMinRecordsToLearn()).isEqualTo(10);
        assertThat(doc.getRegressionCap()).isEqualTo(100);
        assertThat(doc.getRegressionRetentionDays()).isNull();
    }

    @Test
    void builderDefaultsMatchCreatorDefaults() {
        // Client dirty-tracking compares the doc as read with the doc as written, so the two sources of
        // defaults must agree.
        final ShapeshifterAiDoc built = ShapeshifterAiDoc.builder().uuid("uuid").name("name").build();
        final ShapeshifterAiDoc created = new ShapeshifterAiDoc(
                "uuid", "name", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);

        assertThat(built).isEqualTo(created);
    }

    /// A document written before the learning key went (design 06 phase J slice 2a) still reads, the key
    /// dropped: a stored or imported document must not fail to open because of a setting that no longer
    /// exists.
    @Test
    void aDocumentWithALearningKeyStillReads() {
        final String json = "{\"uuid\":\"uuid\",\"name\":\"old\",\"learningMode\":\"AUTOMATIC\","
                            + "\"learningKey\":[\"Feed\",\"Type\",\"Format\"]}";

        final ShapeshifterAiDoc read = JsonUtil.readValue(json, ShapeshifterAiDoc.class);

        assertThat(read.getUuid()).isEqualTo("uuid");
        assertThat(read.getLearningMode()).isEqualTo(LearningMode.AUTOMATIC);
        assertThat(JsonUtil.writeValueAsString(read)).doesNotContain("learningKey");
    }

    @Test
    void emptyDocOmitsNullKeys() {
        final String json = JsonUtil.writeValueAsString(ShapeshifterAiDoc.builder().uuid("uuid").build());

        assertThat(json).doesNotContain("\"model\"", "\"instructions\"", "\"tokenBudget\"", "\"description\"");
        assertThat(json).contains("\"type\" : \"ShapeshifterAi\"");
    }

    @Test
    void aCheckTakesItsOwnDefaultsWhenParametersAreAbsent() {
        // A doc written before a check had parameters, or a hand-written one, reads back with them.
        assertThat(new CheckSetting(Check.YIELD, CheckState.ADVICE, null, null).getParameters())
                .isEqualTo(new YieldParameters(1.0, YieldBasis.RECORDS));
        assertThat(new CheckSetting(Check.COMPILE, CheckState.GATE, null, null).getParameters()).isNull();
    }

    @Test
    void aCheckRefusesAnotherChecksParameters() {
        assertThatThrownBy(() -> new CheckSetting(Check.YIELD, CheckState.ADVICE, null,
                new SchemaConformanceParameters("EVENTS")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Schema conformance")
                .hasMessageContaining("yield");
    }

    @Test
    void aBindingDefaultsToActiveWithNoUuid() {
        // The store assigns the uuid on save and the supervisor on creation; the client never does, so a
        // binding built without one is not yet identified and is neither a draft nor provisional.
        final Binding binding = Binding.builder().build();

        assertThat(binding.getUuid()).isNull();
        assertThat(binding.isDraft()).isFalse();
        assertThat(binding.isProvisional()).isFalse();
    }

    /// A copy keeps the target schema: every tab of the editor writes the document through a copy, and a
    /// field a copy dropped would be lost whenever another tab saved.
    @Test
    void aCopyKeepsTheTargetSchema() {
        final ShapeshifterAiDoc doc = populated();

        final ShapeshifterAiDoc copied = doc.copy().build();

        assertThat(copied.getTargetSchema()).isEqualTo(EVENTS_V4);
        assertThat(copied).isEqualTo(doc);
    }

    /// The target schema is part of what makes two documents differ, so a change to it alone marks the
    /// document changed in the editor, which decides by comparing the document read with the one written.
    @Test
    void theTargetSchemaIsPartOfEquality() {
        final ShapeshifterAiDoc doc = populated();

        final ShapeshifterAiDoc unset = doc.copy().targetSchema(null).build();

        assertThat(unset).isNotEqualTo(doc);
        assertThat(unset.hashCode()).isNotEqualTo(doc.hashCode());
        assertThat(JsonUtil.writeValueAsString(unset)).doesNotContain("\"targetSchema\"");
        assertThat(JsonUtil.writeValueAsString(doc)).contains("\"targetSchema\"");
    }

    /// A check saved before D1 with the default group, as every check was, is the check a new document gets:
    /// the starting sets of checks are found by comparing a document's checks with theirs, and a document
    /// saved then must still be seen to use the set it was given.
    @Test
    void aConformanceCheckSavedWithTheDefaultGroupIsANewOne() {
        final String json = "{\"type\":\"schemaConformance\",\"schemaGroup\":\"EVENTS\"}";

        final ScorerParameters read = JsonUtil.readValue(json, ScorerParameters.class);

        assertThat(read).isEqualTo(new SchemaConformanceParameters());
        assertThat(read).isEqualTo(ScorerParameters.defaultsFor(Check.CONFORMANCE));
        assertThat(JsonUtil.writeValueAsString(read)).doesNotContain("schemaGroup");
    }

    /// A check saved before D1 with a group of its own drops it: a group is not one version, and the check
    /// validates against the document's target schema, which is one.
    @Test
    void aConformanceCheckSavedWithAnotherGroupDropsIt() {
        final String json = "{\"type\":\"schemaConformance\",\"schemaGroup\":\" MY_EVENTS \"}";

        final ScorerParameters read = JsonUtil.readValue(json, ScorerParameters.class);

        assertThat(read).isEqualTo(new SchemaConformanceParameters());
        assertThat(JsonUtil.writeValueAsString(read)).doesNotContain("schemaGroup");
    }

    static ShapeshifterAiDoc populated() {
        final DocRef model = OpenAIModelDoc.buildDocRef().uuid("model-uuid").name("local-model").build();
        return ShapeshifterAiDoc.builder()
                .uuid("doc-uuid")
                .name("syslog-ai")
                .version("1")
                .createTimeMs(1L)
                .updateTimeMs(2L)
                .createUser("creator")
                .updateUser("updater")
                .description("A document")
                .learningMode(LearningMode.AUTOMATIC)
                .model(model)
                .targetSchema(EVENTS_V4)
                .relearnThreshold(0.75)
                .allowedElements(List.of("XSLTFilter"))
                .instructions("Prefer named fields.")
                .flow(new LearningFlow(
                        List.of(FlowStep.parse("CHAIN"), FlowStep.parse("TARGET when text kinds 1"),
                                FlowStep.parse("CONFIGURE candidates 2")),
                        Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}"), 3))
                .maxAttempts(5)
                .attemptBudgetMs(120_000L)
                .tokenBudget(4_096L)
                .sampleSizeLimit(1_024)
                .promotionMode(PromotionMode.REVIEW)
                .heldOutFraction(0.25)
                .minRecordsToLearn(20)
                .regressionCap(50)
                .regressionRetentionDays(30)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(0.9, YieldBasis.LINES)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0,
                                new SchemaConformanceParameters("EVENTS")),
                        new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                                new ExtractionQualityParameters(true,
                                                List.of("/Event/EventTime", "/Event/EventSource/User/Id"))),
                        new CheckSetting(Check.RULES, CheckState.ADVICE, 1.0,
                                new BusinessRulesParameters(List.of(
                                        new XPathAssertion("interactive events name the user",
                                                "not(EventDetail/Authenticate) "
                                                + "or EventDetail/Authenticate/User/Id")),
                                        false)),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }
}
