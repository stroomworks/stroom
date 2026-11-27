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

package stroom.learningflow.shared;

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

class TestLearningFlowDoc {

    private static final DocRef EVENTS_V4 = DocRef.builder()
            .type("XMLSchema")
            .uuid("befcc474-36e4-4db4-a610-bd1fe6825cb1")
            .name("event-logging v4.1.0")
            .build();

    @Test
    void fullyPopulatedDocRoundTrips() {
        final LearningFlowDoc doc = populated();

        final String json = JsonUtil.writeValueAsString(doc);
        final LearningFlowDoc read = JsonUtil.readValue(json, LearningFlowDoc.class);

        assertThat(read).isEqualTo(doc);
        assertThat(read.getChecks()).containsExactlyElementsOf(doc.getChecks());
        assertThat(read.getFlow().getSteps()).isEqualTo(doc.getFlow().getSteps());
        assertThat(read.getFlow().getSteps()).extracting(FlowStep::getKind)
                .containsExactly(QuestionKind.CHAIN, QuestionKind.TARGET, QuestionKind.CONFIGURE);
        assertThat(read.getTargetSchema())
                .describedAs("task 7: the one schema this document's translations must write")
                .isEqualTo(doc.getTargetSchema());
    }

    @Test
    void aLearningFlowKnowsWhatIsWrongWithItsSteps() {
        assertThat(flowOf(FlowFixtures.targetFirst()).problems()).isEmpty();
        assertThat(new LearningFlow(null, null, null).getSteps())
                .describedAs("no steps is no steps (Track 2 step 1c): a flow given none says so, and "
                             + "[#problems] reports it, rather than quietly becoming Direct")
                .isEmpty();
        assertThat(new LearningFlow(null, null, null).problems())
                .containsExactly("The flow has no steps");
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CONFIGURE), FlowStep.of(QuestionKind.CHAIN))).problems())
                .containsExactly("The first step must be 'Choose the elements'",
                        "The last step must write the translation, or be the fallback");
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.SPLIT),
                FlowStep.of(QuestionKind.SPLIT), FlowStep.of(QuestionKind.CONFIGURE))).problems())
                .containsExactly("The flow has 2 'Find what one record is' steps; it may have only one");
        assertThat(flowOf(List.of()).problems())
                .containsExactly("The flow has no steps");
        // A transition out of CHAIN would leave the flow with no chain to run, so it is refused when the flow
        // is checked rather than failing later on a null chain while a session runs.
        assertThat(flowOf(List.of(
                FlowStep.builder(QuestionKind.CHAIN)
                        .goTo(StepOutcome.REFUSED, "end")
                        .build(), FlowStep.of(QuestionKind.CONFIGURE))).problems())
                .containsExactly("'Choose the elements' can't have rules: nothing else can run until the elements "
                                 + "are chosen");
        // Two transitions on one outcome, or two for spent candidates, contradict each other, so they are
        // refused rather than letting the first silently win.
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .whenSpentGoTo("end")
                        .abandonWhenSpent()
                        .build())).problems())
                .containsExactly("Step 2 (Write every element) has two rules for when it runs out of tries; only "
                                 + "the first would be followed");
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .goTo(StepOutcome.REFUSED, "end")
                        .abandonOn(StepOutcome.REFUSED)
                        .abandonWhenSpent()
                        .build())).problems())
                .containsExactly("Step 2 (Write every element) has two rules for when the answer isn't in the "
                                 + "reply format; only the first would be followed");
    }

    @Test
    void aFlowNamesItsExitsByTheirEffect() {
        // The exits park and give-up sit beside end; a transition that names no step takes the step's own
        // default exit; and no step may use any of the three names.
        final FlowStep parks = FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.PARSER)
                .candidates(1)
                .whenSpentGoTo("park")
                .build();
        assertThat(parks.transitionOnSpent().exits()).isTrue();
        assertThat(parks.transitionOnSpent().namedExit()).isEqualTo(FlowExit.PARK);
        final FlowStep givesUp = FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.TRANSFORM)
                .goTo(StepOutcome.RUN_FAILED, "give-up")
                .build();
        assertThat(givesUp.transitionOn(StepOutcome.RUN_FAILED).namedExit()).isEqualTo(FlowExit.GIVE_UP);
        final FlowStep abandons = FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.TRANSFORM)
                .abandonWhenSpent()
                .build();
        assertThat(abandons.transitionOnSpent().exits()).isTrue();
        assertThat(abandons.transitionOnSpent().namedExit()).isNull();
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.PARSER)
                        .whenSpentGoTo("park")
                        .build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.TRANSFORM)
                        .whenSpentGoTo("give-up")
                        .build())).problems()).isEmpty();
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .id("park")
                        .build())).problems())
                .containsExactly("Step 2 (Write every element) has the id 'park', which the flow keeps for "
                                 + "itself; delete the step and add it again");
    }

    @Test
    void theChainStepNamesTheElementsTheChainQuestionChoosesFrom() {
        // Elements belong to the Chain step; the document derives its allowed elements from them, or uses
        // every learnable one where the step names none; no other step may name any.
        final FlowStep chain = FlowStep.builder(QuestionKind.CHAIN)
                .candidates(2)
                .elements("DSParser", "XSLTFilter")
                .build();
        assertThat(chain.getElements()).containsExactly("DSParser", "XSLTFilter");
        assertThat(FlowStep.of(QuestionKind.CHAIN).getElements()).isEmpty();
        assertThat(FlowStep.of(QuestionKind.CHAIN).effectiveElements()).isEqualTo(FlowStep.LEARNABLE_ELEMENTS);
        assertThatThrownBy(() -> FlowStep.builder(QuestionKind.CONFIGURE)
                .role(ConfigureRole.PARSER)
                .elements("DSParser")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only CHAIN names elements");
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").name("n")
                .flow(flowOf(List.of(chain, FlowStep.of(QuestionKind.CONFIGURE))))
                .build();
        assertThat(doc.getAllowedElements()).containsExactly("DSParser", "XSLTFilter");
        assertThat(LearningFlowDoc.builder().uuid("d").name("n").build().getAllowedElements())
                .isEqualTo(FlowStep.LEARNABLE_ELEMENTS);
        assertThat(LearningFlowDoc.builder().uuid("d").name("n")
                // Give it steps, since a document built here has none of its own, and the allowed elements
                // go onto its Chain step.
                .flow(flowOf(FlowFixtures.direct()))
                .allowedElements(List.of("XMLParser")).build()
                .getFlow().getSteps().get(0).getElements()).containsExactly("XMLParser");
    }

    @Test
    void aProblemNamesTheStepItIsAbout() {
        // The check says which step a problem is about, where it is about one, so the editor can mark it.
        final List<FlowProblem> problems = flowOf(List.of(
                FlowStep.builder(QuestionKind.CONFIGURE).goTo(StepOutcome.REFUSED, "nowhere").build(),
                FlowStep.of(QuestionKind.SPLIT))).check();
        assertThat(problems).extracting(FlowProblem::getStep, FlowProblem::getMessage).containsExactly(
                tuple(0, "The first step must be 'Choose the elements'"),
                tuple(1, "The last step must write the translation, or be the fallback"),
                tuple(null, "The flow needs a 'Choose the elements' step"),
                tuple(0, "The rule 'if the answer isn't in the reply format' on step 1 (Write every element) "
                         + "goes to 'nowhere', which isn't a step"));
    }

    @Test
    void anIdIsAWordHoweverItArrives() {
        // An id read from JSON is checked in the constructor just as one set in code is.
        assertThatThrownBy(() -> new FlowStep("my step", QuestionKind.CONFIGURE, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'my step' is not a step id");
        assertThat(new FlowStep(" Again ", QuestionKind.CONFIGURE, null, null, null, null, null, null).getId())
                .isEqualTo("again");
    }

    @Test
    void aFlowIsAGraphOverTypedOutcomes() {
        // Ids default to the kind or the role, and a transition must go to a step that exists. The built-in
        // example flows are JSON on the server, and `TestFlowExamples` checks their shape, including that
        // each is a valid flow.
        final LearningFlow escalating = flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN),
                FlowStep.of(QuestionKind.SPLIT),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.PARSER)
                        .goTo(StepOutcome.YIELD_SHORT, "split")
                        .build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .id("first")
                        .role(ConfigureRole.TRANSFORM)
                        .candidates(2)
                        .goTo(StepOutcome.PASSED, "end")
                        .whenSpentGoTo("target")
                        .build(),
                FlowStep.builder(QuestionKind.TARGET).kinds(3).build(),
                FlowStep.builder(QuestionKind.CONFIGURE).id("again").role(ConfigureRole.PARSER).build(),
                FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.TRANSFORM)
                        .goTo(StepOutcome.PRESERVATION_SHORT, "again")
                        .whenSpentGoTo("admit")
                        .build(),
                FlowStep.of(QuestionKind.ADMIT)));
        assertThat(escalating.problems()).isEmpty();
        assertThat(escalating.getSteps()).extracting(FlowStep::effectiveId)
                .containsExactly("chain", "split", "parser", "first", "target", "again", "transform", "admit");
        assertThat(escalating.step("split").getWhen()).isEqualTo(StepGuard.ALWAYS);
        assertThat(escalating.step("parser").getTransitions())
                .containsExactly(new Transition(StepOutcome.YIELD_SHORT, "split"));
        assertThat(escalating.step("first").getTransitions())
                .containsExactly(new Transition(StepOutcome.PASSED, Transition.END),
                        new Transition(null, "target"));
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .id("end")
                        .build())).problems())
                .containsExactly("Step 2 (Write every element) has the id 'end', which the flow keeps for "
                                 + "itself; delete the step and add it again");
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .role(ConfigureRole.PARSER)
                        .build(), FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build()))
                .problems())
                .containsExactly("Step 3 (Write the parser again) has the same id as an earlier step, 'parser'; "
                                 + "delete it and add it again");
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .goTo(StepOutcome.REFUSED, "nowhere")
                        .abandonWhenSpent()
                        .build()))
                .problems())
                .containsExactly("The rule 'if the answer isn't in the reply format' on step 2 (Write every "
                                 + "element) goes to 'nowhere', which isn't a step");
        // A step's name is matched without regard to case, because ids and transition targets are kept in
        // lower case.
        final FlowStep named = FlowStep.builder(QuestionKind.CONFIGURE)
                .id("First")
                .role(ConfigureRole.PARSER)
                .whenSpentGoTo("Target")
                .goTo(StepOutcome.PASSED, "END")
                .build();
        assertThat(flowOf(List.of(FlowStep.of(QuestionKind.CHAIN), named, FlowStep.of(QuestionKind.TARGET),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build()))
                .problems()).isEmpty();
        assertThat(named.getId()).isEqualTo("first");
        assertThat(named.transitionOnSpent().getGoTo()).isEqualTo("target");
        // An explicit id that matches another step's default name is a collision too.
        assertThat(flowOf(List.of(
                FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                        .id("target")
                        .role(ConfigureRole.PARSER)
                        .build(), FlowStep.of(QuestionKind.TARGET),
                FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.TRANSFORM).build())).problems())
                .containsExactly("Step 3 (Agree the target events) has the same id as an earlier step, 'target'; "
                                 + "delete it and add it again");
    }

    @Test
    void aStepCarriesItsIdRoleAndTransitions() {
        final FlowStep step = FlowStep.builder(QuestionKind.CONFIGURE)
                .id("first")
                .role(ConfigureRole.TRANSFORM)
                .candidates(2)
                .goTo(StepOutcome.PRESERVATION_SHORT, "parser")
                .abandonWhenSpent()
                .build();

        assertThat(step.getId()).isEqualTo("first");
        assertThat(step.getRole()).isEqualTo(ConfigureRole.TRANSFORM);
        assertThat(step.getCandidates()).isEqualTo(2);
        assertThat(step.transitionOn(StepOutcome.PRESERVATION_SHORT).getGoTo()).isEqualTo("parser");
        assertThat(step.transitionOn(StepOutcome.REFUSED)).isNull();
        assertThat(step.transitionOnSpent().exits()).isTrue();
        assertThat(FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build().effectiveId())
                .isEqualTo("parser");
        assertThat(FlowStep.of(QuestionKind.SPLIT).effectiveId()).isEqualTo("split");
        // The check's shortfall is its outcome.
        assertThat(Check.FIDELITY.shortfall()).isEqualTo(StepOutcome.FIDELITY_SHORT);
        assertThat(Check.COMPILE.shortfall()).describedAs("compile's own outcome")
                .isEqualTo(StepOutcome.COMPILE_FAILED);
        assertThat(Check.REGRESSION.shortfall()).describedAs("judges promotion, not a step").isNull();
    }

    @Test
    void aStepsLimitsHoldHoweverItIsMade() {
        // The step's constructor enforces these limits, so a step built in code and one read from JSON are
        // held to the same rules.
        assertThatThrownBy(() -> FlowStep.builder(QuestionKind.SPLIT).role(ConfigureRole.PARSER).build())
                .hasMessageContaining("Only CONFIGURE takes a role");
        assertThatThrownBy(() -> FlowStep.builder(QuestionKind.CHAIN).id("1st").build())
                .hasMessageContaining("is not a step id");
        assertThatThrownBy(() -> FlowStep.builder(QuestionKind.SPLIT).elements("DSParser").build())
                .hasMessageContaining("Only CHAIN names elements");
        assertThatThrownBy(() -> new FlowStep(QuestionKind.TARGET, null, null, 0))
                .hasMessageContaining("'kinds' must be at least 1");
        assertThatThrownBy(() -> new FlowStep(QuestionKind.TARGET, null, 0, null))
                .hasMessageContaining("'candidates' must be at least 1");
        // A builder and the constructor the JSON is read through make the same step.
        assertThat(FlowStep.of(QuestionKind.SPLIT, StepGuard.JSON).getWhen()).isEqualTo(StepGuard.JSON);
        assertThat(FlowStep.builder(QuestionKind.TARGET).when(StepGuard.XML).candidates(4).kinds(2).build())
                .isEqualTo(new FlowStep(QuestionKind.TARGET, StepGuard.XML, 4, 2));
    }

    @Test
    void aBlankTemplateOverrideIsNoOverride() {
        final LearningFlow flow = flowOf(List.of(FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.CONFIGURE)))
                .withTemplates(Map.of(Template.CHAIN, "  \n", Template.SPLIT, "Cut it: ${sample}"));
        assertThat(flow.getTemplates()).containsOnlyKeys(Template.SPLIT);
    }

    @Test
    void creatorAppliesDefaultsToNullArguments() {
        // The JSON creator sees null for every field that is newer than a stored document, so the defaults
        // must be applied there rather than only in the builder.
        final LearningFlowDoc doc = new LearningFlowDoc(
                "uuid", "name", "1", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(doc.getLearningMode()).isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getTargetSchema())
                .describedAs("unset is no target; the store reads one saved before D1 as event-logging v3.0.0")
                .isNull();
        assertThat(doc.getRelearnThreshold()).isEqualTo(0.8);
        assertThat(doc.getAllowedElements())
                .describedAs("every learnable element (A10) until a document narrows it")
                .containsExactly("DSParser", "JSONParser", "XMLFragmentParser", "XSLTFilter");
        assertThat(doc.getFlow().getSteps())
                .describedAs("no steps, not Direct's (Track 2 step 1c): the built-in setups are the "
                             + "server's, and the store fills a new document's steps when it creates it")
                .isEmpty();
        assertThat(doc.getMaxAttempts()).describedAs("five since the first live run, design 02 §6.2").isEqualTo(5);
        assertThat(doc.getAttemptBudgetMs()).isEqualTo(60_000L);
        assertThat(doc.getTokenBudget()).isNull();
        assertThat(doc.getSampleSizeLimit()).isEqualTo(8_192);
        assertThat(doc.getPromotionMode())
                .describedAs("review by default (SIM-1), not A9's automatic: a Learning Flow nobody has "
                             + "calibrated does not change production content unwatched")
                .isEqualTo(PromotionMode.REVIEW);
        assertThat(doc.getHeldOutFraction()).isEqualTo(0.2);
        assertThat(doc.getMinRecordsToLearn()).isEqualTo(10);
        assertThat(doc.getRegressionCap()).isEqualTo(100);
        assertThat(doc.getSpendTokensPerHour())
                .describedAs("no ceiling of its own: the cluster's applies (A24)").isNull();
        assertThat(doc.getSpendCallsPerHour()).isNull();
    }

    @Test
    void builderDefaultsMatchCreatorDefaults() {
        // Client dirty-tracking compares the doc as read with the doc as written, so the two sources of
        // defaults must agree.
        final LearningFlowDoc built = LearningFlowDoc.builder().uuid("uuid").name("name").build();
        final LearningFlowDoc created = new LearningFlowDoc(
                "uuid", "name", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        assertThat(built).isEqualTo(created);
    }

    /// A document saved by an earlier version that had a learning key setting still reads, with the key
    /// dropped: a stored or imported document must not fail to open because of a setting that no longer
    /// exists.
    @Test
    void aDocumentWithALearningKeyStillReads() {
        final String json = "{\"uuid\":\"uuid\",\"name\":\"old\",\"learningMode\":\"AUTOMATIC\","
                            + "\"learningKey\":[\"Feed\",\"Type\",\"Format\"]}";

        final LearningFlowDoc read = JsonUtil.readValue(json, LearningFlowDoc.class);

        assertThat(read.getUuid()).isEqualTo("uuid");
        assertThat(read.getLearningMode()).isEqualTo(LearningMode.AUTOMATIC);
        assertThat(JsonUtil.writeValueAsString(read)).doesNotContain("learningKey");
    }

    @Test
    void emptyDocOmitsNullKeys() {
        final String json = JsonUtil.writeValueAsString(LearningFlowDoc.builder().uuid("uuid").build());

        assertThat(json).doesNotContain("\"model\"", "\"instructions\"", "\"tokenBudget\"", "\"description\"");
        assertThat(json).contains("\"type\" : \"LearningFlow\"");
    }

    @Test
    void aCheckTakesItsOwnDefaultsWhenParametersAreAbsent() {
        // A document with a check that has no parameters, such as a hand-written one, reads back with the
        // check's defaults.
        assertThat(new CheckSetting(Check.YIELD, CheckState.ADVICE, null, null).getParameters())
                .isEqualTo(new YieldParameters(1.0, YieldBasis.RECORDS));
        assertThat(new CheckSetting(Check.COMPILE, CheckState.GATE, null, null).getParameters()).isNull();
    }

    @Test
    void aCheckRefusesAnotherChecksParameters() {
        assertThatThrownBy(() -> new CheckSetting(Check.YIELD, CheckState.ADVICE, null,
                new ExtractionQualityParameters(false, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Extraction quality")
                .hasMessageContaining("yield");
    }

    @Test
    void aBindingDefaultsToActiveWithNoUuid() {
        // The server assigns the uuid; the client never does, so a binding built without one is not yet
        // identified, and is not a draft.
        final Binding binding = Binding.builder().build();

        assertThat(binding.getUuid()).isNull();
        assertThat(binding.isDraft()).isFalse();
    }

    /// A copy keeps the target schema: every tab of the editor writes the document through a copy, and a
    /// field a copy dropped would be lost whenever another tab saved.
    @Test
    void aCopyKeepsTheTargetSchema() {
        final LearningFlowDoc doc = populated();

        final LearningFlowDoc copied = doc.copy().build();

        assertThat(copied.getTargetSchema()).isEqualTo(EVENTS_V4);
        assertThat(copied).isEqualTo(doc);
    }

    /// The target schema is part of what makes two documents differ, so a change to it alone marks the
    /// document changed in the editor, which decides by comparing the document read with the one written.
    @Test
    void theTargetSchemaIsPartOfEquality() {
        final LearningFlowDoc doc = populated();

        final LearningFlowDoc unset = doc.copy().targetSchema(null).build();

        assertThat(unset).isNotEqualTo(doc);
        assertThat(unset.hashCode()).isNotEqualTo(doc.hashCode());
        assertThat(JsonUtil.writeValueAsString(unset)).doesNotContain("\"targetSchema\"");
        assertThat(JsonUtil.writeValueAsString(doc)).contains("\"targetSchema\"");
    }

    static LearningFlowDoc populated() {
        final DocRef model = OpenAIModelDoc.buildDocRef().uuid("model-uuid").name("local-model").build();
        return LearningFlowDoc.builder()
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
                        List.of(FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.TARGET)
                                .when(StepGuard.TEXT)
                                .kinds(1)
                                .build(),
                                FlowStep.builder(QuestionKind.CONFIGURE).candidates(2).build()),
                        Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}"), 3))
                .maxAttempts(5)
                .attemptBudgetMs(120_000L)
                .tokenBudget(4_096L)
                .sampleSizeLimit(1_024)
                .promotionMode(PromotionMode.REVIEW)
                .heldOutFraction(0.25)
                .minRecordsToLearn(20)
                .regressionCap(50)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(0.9, YieldBasis.LINES)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
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

    /// Builds a flow of the given steps, with no template overrides. The example flows are defined on the
    /// server, so a test in this module builds the flow it means rather than starting from one of them.
    ///
    /// @param steps Its steps.
    /// @return The flow.
    private static LearningFlow flowOf(final List<FlowStep> steps) {
        return new LearningFlow(steps, null, null);
    }
}
