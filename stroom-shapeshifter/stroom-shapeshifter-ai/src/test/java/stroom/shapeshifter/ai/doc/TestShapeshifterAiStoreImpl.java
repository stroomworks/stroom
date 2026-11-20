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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.security.shared.AppPermission;
import stroom.shapeshifter.ai.doc.ShapeshifterAiFixture.Restricted;
import stroom.shapeshifter.ai.learning.FlowExamples;
import stroom.shapeshifter.ai.learning.TargetSchema;
import stroom.shapeshifter.ai.learning.TargetSchemas;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckSettings;
import stroom.shapeshifter.shared.CheckState;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.util.shared.PermissionException;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The document store end to end: the serialiser, the defaults and the dependency function as the store
 * actually wires them.
 */
class TestShapeshifterAiStoreImpl {

    private final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store();

    @Test
    void createAppliesTheDefaults() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final ShapeshifterAiDoc doc = store.readDocument(docRef);

        assertThat(doc.getName()).isEqualTo("syslog-ai");
        assertThat(doc.getPromotionMode())
                .describedAs("review, not automatic (SIM-1): a model nobody has calibrated does not "
                             + "change production content unwatched")
                .isEqualTo(PromotionMode.REVIEW);
        assertThat(doc.getLearningMode())
                .describedAs("Shapeshifter AI is opt-in per document (§11), so a new document must not call the model")
                .isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getModel()).isNull();
        assertThat(doc.getTargetSchema())
                .describedAs("no event-logging schema is installed here, so there is nothing to start "
                             + "with and the readiness check is what says so")
                .isNull();
    }

    @Test
    void createStartsFromTheLatestEventLoggingInstalled() {
        // Task 7, decision D1: the latest installed version is settled when the document is made and
        // saved on it, so that installing a newer one later does not change what this document writes.
        final DocRef latest = new DocRef(XmlSchemaDoc.TYPE, "schema-4", "event-logging v4.1.0");
        final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store(new TargetSchemas() {
            @Override
            public Optional<TargetSchema> resolve(final DocRef docRef) {
                return Optional.empty();
            }

            @Override
            public Optional<TargetSchema> latestEventLogging() {
                return Optional.of(new TargetSchema(latest, latest.getName(), TargetSchema.EVENT_LOGGING,
                        "file://event-logging-v4.1.0.xsd", "4.1.0", "Events", "<xs:schema/>"));
            }
        });

        final ShapeshifterAiDoc doc = store.readDocument(store.createDocument("syslog-ai"));

        assertThat(doc.getTargetSchema()).isEqualTo(latest);
    }


    @Test
    void writeThenReadRoundTripsTheNestedLists() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final ShapeshifterAiDoc written = store.writeDocument(
                ShapeshifterAiFixture.configured(store.readDocument(docRef)).build());

        final ShapeshifterAiDoc read = store.readDocument(docRef);

        assertThat(read).isEqualTo(written);
        assertThat(read.getChecks()).containsExactlyElementsOf(ShapeshifterAiFixture.CHECKS);
        assertThat(read.getChecks().get(6).getParameters())
                .describedAs("polymorphic parameters survive the store")
                .isEqualTo(ShapeshifterAiFixture.CHECKS.get(6).getParameters());
    }

    @Test
    void saveRefusesAFlowTheStageCannotHoldAndStampsTheBuiltInVersion() {
        // Scenarios 39 and 40 (A33): the binding and the variable are named where the editor cannot check them;
        // a definition that holds is saved against the built-in text of the day.
        final DocRef docRef = store.createDocument("syslog-ai");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CONFIGURE), FlowStep.of(QuestionKind.CHAIN))))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The first step must be 'Choose the elements'")
                .hasMessageContaining("The last step must write the translation");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.SPLIT),
                        FlowStep.of(QuestionKind.SPLIT), FlowStep.of(QuestionKind.CONFIGURE))))
                .build()))
                .hasMessageContaining("The flow has 2 'Find what one record is' steps");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(FlowExamples.flow(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN), FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.PARSER)
                                .whenSpentGoTo("nowhere")
                                .build(),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build())))
                .build()))
                .hasMessageContaining("Step 3 (Write the parser again) has the same id as an earlier step, 'parser'")
                .hasMessageContaining("The rule 'if it runs out of tries' on step 2 (Write the parser) goes to "
                                      + "'nowhere', which isn't a step");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(FlowExamples.flow(FlowExample.DIRECT)
                        .withTemplates(Map.of(Template.TARGET, "Become ${nothing}")))
                .build()))
                .hasMessageContaining("The target question template names ${nothing}");

        store.writeDocument(store.readDocument(docRef).copy()
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST)
                        .withTemplates(Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}")))
                .build());
        final LearningFlow saved = store.readDocument(docRef).getFlow();
        assertThat(saved.getBuiltInVersion()).isEqualTo(Templates.VERSION);
        assertThat(saved.getTemplates()).containsOnlyKeys(Template.CHAIN);
        assertThat(saved.getSteps()).isEqualTo(FlowExamples.steps(FlowExample.TARGET_FIRST));
    }

    @Test
    void saveRefusesChecksThatBreakTheirRules() {
        final DocRef docRef = store.createDocument("syslog-ai");
        assertThat(store.readDocument(docRef).getChecks())
                .describedAs("a new document has the defaults")
                .isEqualTo(CheckSettings.defaults());
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .checks(List.of(CheckSetting.of(Check.COMPILE, CheckState.ADVICE)))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The checks cannot be held: The compile check is always a gate.");
        // The "isn't built yet" refusal has no case to answer now that the error load and event
        // classification checks are gone (D5) and every check that is left is built. The refusal stays in
        // CheckSettings.problems for a check added before its scorer is written; TestCheckSettings covers it.
    }

    @Test
    void remapDependenciesRewritesTheStoredDoc() {
        // Proves the function is reached through StoreImpl.remapDependencies, which is what import calls,
        // and not only that it works when called directly.
        final DocRef docRef = store.createDocument("syslog-ai");
        store.writeDocument(ShapeshifterAiFixture.configured(store.readDocument(docRef)).build());

        final DocRef importedModel = ShapeshifterAiFixture.MODEL.copy().uuid("model-2").build();
        final DocRef importedFragment = ShapeshifterAiFixture.FRAGMENT.copy().uuid("fragment-2").build();
        store.remapDependencies(docRef, Map.of(
                ShapeshifterAiFixture.MODEL, importedModel,
                ShapeshifterAiFixture.FRAGMENT, importedFragment));

        final ShapeshifterAiDoc read = store.readDocument(docRef);
        assertThat(read.getModel()).isEqualTo(importedModel);
        assertThat(read.getChecks()).isEqualTo(ShapeshifterAiFixture.CHECKS);
    }

    @Test
    void usingNewTranslationsAutomaticallyNeedsThePermission() {
        // SIM-1: setting the mode approves every future translation, for every feed the Learning Flow
        // serves, with nobody looking, so it is a wider act than editing the Learning Flow.
        final Restricted restricted = ShapeshifterAiFixture.restricted();
        final DocRef docRef = restricted.store().createDocument("syslog-ai");
        restricted.withhold(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY);
        final ShapeshifterAiDoc automatic = restricted.store().readDocument(docRef).copy()
                .promotionMode(PromotionMode.AUTOMATIC)
                .build();

        assertThatThrownBy(() -> restricted.store().writeDocument(automatic))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining(
                        AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY.getDisplayValue());

        assertThat(restricted.store().readDocument(docRef).getPromotionMode())
                .describedAs("the refused save changed nothing")
                .isEqualTo(PromotionMode.REVIEW);
    }

    @Test
    void goingBackToWaitingForApprovalDoesNotNeedThePermission() {
        // The safer direction: anyone who can edit the Learning Flow can make it wait for approval again.
        final Restricted restricted = ShapeshifterAiFixture.restricted();
        final DocRef docRef = restricted.store().createDocument("syslog-ai");
        restricted.store().writeDocument(restricted.store().readDocument(docRef).copy()
                .promotionMode(PromotionMode.AUTOMATIC)
                .build());
        restricted.withhold(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY);

        restricted.store().writeDocument(restricted.store().readDocument(docRef).copy()
                .promotionMode(PromotionMode.REVIEW)
                .build());

        assertThat(restricted.store().readDocument(docRef).getPromotionMode())
                .isEqualTo(PromotionMode.REVIEW);
    }

    @Test
    void editingAFlowThatIsAlreadyAutomaticDoesNotNeedThePermission() {
        // Only the move to automatic is the act being guarded: somebody without the permission can still
        // change the instructions of a Learning Flow that is already automatic.
        final Restricted restricted = ShapeshifterAiFixture.restricted();
        final DocRef docRef = restricted.store().createDocument("syslog-ai");
        restricted.store().writeDocument(restricted.store().readDocument(docRef).copy()
                .promotionMode(PromotionMode.AUTOMATIC)
                .build());
        restricted.withhold(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY);

        restricted.store().writeDocument(restricted.store().readDocument(docRef).copy()
                .instructions("Prefer named fields.")
                .build());

        final ShapeshifterAiDoc read = restricted.store().readDocument(docRef);
        assertThat(read.getPromotionMode()).isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(read.getInstructions()).isEqualTo("Prefer named fields.");
    }
}
