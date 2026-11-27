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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.learningflow.client.component.Readiness.Fix;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSetupSummary {

    private static final DocRef EVENTS_V3 = new DocRef("XMLSchema", "4fe14042-770e-4297-8711-d92e607bc4d5",
            "event-logging v3.0.0");

    @Test
    void aDocumentWithEverythingSaysWhatEachSectionIsSetTo() {
        final SetupSummary summary = SetupSummary.of(LearningFlowDoc.builder()
                .uuid("ss-1")
                .name("Firewall logs")
                .learningMode(LearningMode.AUTOMATIC)
                .model(new DocRef("OpenAIModel", "m-1", "GPT-4o"))
                .targetSchema(EVENTS_V3)
                .checks(CheckPreset.STANDARD.settings())
                .flow(new LearningFlow(FlowExampleFixtures.steps(FlowExample.DIRECT), null, null))
                .promotionMode(PromotionMode.REVIEW)
                .relearnThreshold(0.6)
                .build(), FlowExampleFixtures.all());

        assertThat(summary.summary(SetupSection.LEARNING)).isEqualTo("On · GPT-4o · event-logging v3.0.0");
        // The fallback is a step of the flow, but is not counted as a step card
        assertThat(summary.summary(SetupSection.APPROACH)).isEqualTo("Straight to it · 3 steps");
        assertThat(summary.summary(SetupSection.QUALITY)).isEqualTo("Standard · 9 gates, 2 advice checks");
        assertThat(summary.summary(SetupSection.GOING_LIVE)).isEqualTo("Approved first");
        assertThat(summary.summary(SetupSection.IN_SERVICE)).isEqualTo("Flagged below 60%");
        for (final SetupSection section : SetupSection.values()) {
            assertThat(summary.problem(section)).as(section.name()).isNull();
        }
    }

    @Test
    void aNewDocumentsProblemsAreShownAgainstTheSectionsTheyAreFixedIn() {
        // A new document: learning off, no model, the Standard checks and the Direct flow
        final SetupSummary summary = SetupSummary.of(
                LearningFlowDoc.builder().uuid("new").name("New")
                        // The steps the store gives a new document
                        .flow(new LearningFlow(FlowExampleFixtures.steps(FlowExample.DIRECT), null, null))
                        .build(),
                FlowExampleFixtures.all());

        assertThat(summary.summary(SetupSection.LEARNING)).isEqualTo("Off · no model · no target schema");
        // Learning off, no model and no target schema are all fixed in the Learning section, which shows only
        // the first
        assertThat(summary.problem(SetupSection.LEARNING))
                .isEqualTo("Learning is switched off, so streams of new feeds wait.");
        // A new Learning Flow has the Standard checks, so there is no problem with them
        assertThat(summary.summary(SetupSection.QUALITY)).isEqualTo("Standard · 9 gates, 2 advice checks");
        assertThat(summary.problem(SetupSection.QUALITY)).isNull();
        assertThat(summary.problem(SetupSection.APPROACH)).isNull();
        assertThat(summary.problem(SetupSection.GOING_LIVE)).isNull();
        assertThat(summary.problem(SetupSection.IN_SERVICE)).isNull();
    }

    @Test
    void aDocumentsOwnApproachAndChecksAreSaidToBeItsOwn() {
        final SetupSummary summary = SetupSummary.of(LearningFlowDoc.builder()
                .uuid("ss-1")
                .name("Own")
                .checks(List.of(CheckSetting.of(Check.YIELD, CheckState.ADVICE)))
                .flow(new LearningFlow(List.of(FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.CONFIGURE)),
                        null, null))
                .promotionMode(PromotionMode.AUTOMATIC)
                .build(), FlowExampleFixtures.all());

        assertThat(summary.summary(SetupSection.APPROACH)).isEqualTo("Its own approach · 2 steps");
        assertThat(summary.summary(SetupSection.QUALITY)).isEqualTo("Own checks · 9 gates, 3 advice checks");
        assertThat(summary.summary(SetupSection.GOING_LIVE)).isEqualTo("Used once it passes");
    }

    @Test
    void anApproachWithAProblemIsShownAgainstHowItLearns() {
        final SetupSummary summary = SetupSummary.of(LearningFlowDoc.builder()
                .uuid("ss-1")
                .name("Broken")
                .flow(new LearningFlow(List.of(FlowStep.of(QuestionKind.SPLIT)), null, null))
                .build(), FlowExampleFixtures.all());

        assertThat(summary.problem(SetupSection.APPROACH)).startsWith("The approach has a problem: ");
    }

    @Test
    void aModelWithNoNameIsStillSaidToBeChosen() {
        final SetupSummary summary = SetupSummary.of(LearningFlowDoc.builder()
                .uuid("ss-1")
                .name("Unnamed model")
                .learningMode(LearningMode.AUTOMATIC)
                .model(new DocRef("OpenAIModel", "m-1", null))
                .targetSchema(EVENTS_V3)
                .build(), FlowExampleFixtures.all());

        assertThat(summary.summary(SetupSection.LEARNING)).isEqualTo("On · a model · event-logging v3.0.0");
        assertThat(summary.problem(SetupSection.LEARNING)).isNull();
    }

    /// The route map names the target schema (D1), so that what a Learning Flow writes to is seen without
    /// opening the section; where none is chosen, that is said, and is the section's problem.
    @Test
    void theTargetSchemaIsNamedUnderAiModelAndInstructions() {
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("ss-1")
                .name("Version 4")
                .learningMode(LearningMode.AUTOMATIC)
                .model(new DocRef("OpenAIModel", "m-1", "GPT-4o"))
                .targetSchema(new DocRef("XMLSchema", "s-1", "event-logging v4.1.0"))
                .build();

        assertThat(SetupSummary.of(doc, FlowExampleFixtures.all()).summary(SetupSection.LEARNING))
                .isEqualTo("On · GPT-4o · event-logging v4.1.0");
        assertThat(SetupSummary.of(doc.copy().targetSchema(new DocRef("XMLSchema", "s-1", null)).build(),
                        FlowExampleFixtures.all())
                .summary(SetupSection.LEARNING))
                .describedAs("a schema with no name is still said to be chosen")
                .isEqualTo("On · GPT-4o · a schema");
        final SetupSummary none = SetupSummary.of(doc.copy().targetSchema(null).build(), FlowExampleFixtures.all());
        assertThat(none.summary(SetupSection.LEARNING)).isEqualTo("On · GPT-4o · no target schema");
        assertThat(none.problem(SetupSection.LEARNING))
                .isEqualTo("No target schema is chosen, so the AI has no schema to write to.");
    }

    @Test
    void everyFixIsMadeInASection() {
        assertThat(Fix.SWITCH_ON.getSection()).isEqualTo(SetupSection.LEARNING);
        assertThat(Fix.CHOOSE_MODEL.getSection()).isEqualTo(SetupSection.LEARNING);
        assertThat(Fix.USE_STANDARD_CHECKS.getSection()).isEqualTo(SetupSection.QUALITY);
        assertThat(Fix.OPEN_APPROACH.getSection()).isEqualTo(SetupSection.APPROACH);
    }
}
