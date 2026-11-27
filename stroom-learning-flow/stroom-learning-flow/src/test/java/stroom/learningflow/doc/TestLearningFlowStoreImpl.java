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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.StoreFactory;
import stroom.learningflow.doc.LearningFlowFixture.Restricted;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.learning.Templates;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.Template;
import stroom.security.shared.AppPermission;
import stroom.util.shared.PermissionException;
import stroom.xmlschema.shared.XmlSchemaDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests the Learning Flow document store end to end: the serialiser, the defaults for a new document, the
/// validation on save, the dependency function and the permission checks, as the store actually wires them.
class TestLearningFlowStoreImpl {

    private static final DocRef EVENTS_V3 = XmlSchemaDoc.buildDocRef()
            .uuid("4fe14042-770e-4297-8711-d92e607bc4d5")
            .name("event-logging v3.0.0")
            .build();
    private static final DocRef EVENTS_V4 = XmlSchemaDoc.buildDocRef()
            .uuid("befcc474-36e4-4db4-a610-bd1fe6825cb1")
            .name("event-logging v4.1.0")
            .build();
    /// Event-logging v3.0.0 and v4.1.0 installed.
    private static final TargetSchemas INSTALLED = new TargetSchemas() {
        @Override
        public Optional<TargetSchema> resolve(final DocRef docRef) {
            return Optional.empty();
        }

        @Override
        public Optional<TargetSchema> latestEventLogging() {
            return Optional.of(eventLogging(EVENTS_V4, "4.1.0"));
        }

        @Override
        public Optional<TargetSchema> eventLoggingV3() {
            return Optional.of(eventLogging(EVENTS_V3, "3.0.0"));
        }
    };

    private final LearningFlowStoreImpl store = LearningFlowFixture.store();

    @Test
    void createAppliesTheDefaults() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final LearningFlowDoc doc = store.readDocument(docRef);

        assertThat(doc.getName()).isEqualTo("syslog-ai");
        assertThat(doc.getPromotionMode())
                .describedAs("review, not automatic (SIM-1): a model nobody has calibrated does not "
                             + "change production content unwatched")
                .isEqualTo(PromotionMode.REVIEW);
        assertThat(doc.getLearningMode())
                .describedAs("Learning Flow is opt-in per document (§11), so a new document must not call the model")
                .isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getModel()).isNull();
        assertThat(doc.getTargetSchema())
                .describedAs("no event-logging schema is installed here, so there is nothing to start "
                             + "with and the readiness check is what says so")
                .isNull();
    }

    @Test
    void createStartsFromTheLatestEventLoggingInstalled() {
        // The latest installed event-logging schema is chosen when the document is created and saved on it,
        // so that installing a newer version later does not change what this document's translations write.
        final DocRef latest = new DocRef(XmlSchemaDoc.TYPE, "schema-4", "event-logging v4.1.0");
        final LearningFlowStoreImpl store = LearningFlowFixture.store(new TargetSchemas() {
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

        final LearningFlowDoc doc = store.readDocument(store.createDocument("syslog-ai"));

        assertThat(doc.getTargetSchema()).isEqualTo(latest);
    }

    /// A Learning Flow saved before it could name a target schema reads as event-logging v3.0.0, which it was
    /// learned against, and keeps it once saved.
    @Test
    void aDocumentSavedBeforeTheTargetSchemaReadsAsV3() {
        final StoreFactory storeFactory = LearningFlowFixture.storeFactory();
        final LearningFlowStoreImpl store = LearningFlowFixture.store(INSTALLED, storeFactory);
        final DocRef stamped = savedBefore(storeFactory, "stamped", 6);
        final DocRef unstamped = savedBefore(storeFactory, "unstamped", null);

        assertThat(store.readDocument(stamped).getTargetSchema()).isEqualTo(EVENTS_V3);
        assertThat(store.readDocument(unstamped).getTargetSchema()).isEqualTo(EVENTS_V3);

        store.writeDocument(store.readDocument(stamped));
        assertThat(store.readDocument(stamped).getTargetSchema()).isEqualTo(EVENTS_V3);
        assertThat(store.readDocument(stamped).getFlow().getBuiltInVersion()).isEqualTo(Templates.VERSION);
    }

    /// One saved since, with no target, has none: a person emptied the field, or none was installed when it
    /// was made. Its readiness says so, rather than a version being chosen for it.
    @Test
    void aDocumentSavedSinceWithNoTargetSchemaHasNone() {
        final StoreFactory storeFactory = LearningFlowFixture.storeFactory();
        final LearningFlowStoreImpl store = LearningFlowFixture.store(INSTALLED, storeFactory);
        final DocRef docRef = savedBefore(storeFactory, "since", Templates.VERSION);

        assertThat(store.readDocument(docRef).getTargetSchema()).isNull();
    }

    @Test
    void aDocumentSavedBeforeTheTargetSchemaWithoutV3InstalledHasNone() {
        final StoreFactory storeFactory = LearningFlowFixture.storeFactory();
        final LearningFlowStoreImpl store = LearningFlowFixture.store(TargetSchemas.none(), storeFactory);
        final DocRef docRef = savedBefore(storeFactory, "old", 6);

        assertThat(store.readDocument(docRef).getTargetSchema()).isNull();
    }

    @Test
    void aDocumentsOwnTargetSchemaIsKept() {
        final StoreFactory storeFactory = LearningFlowFixture.storeFactory();
        final LearningFlowStoreImpl store = LearningFlowFixture.store(INSTALLED, storeFactory);
        final DocRef docRef = LearningFlowFixture.raw(storeFactory).createDocument("old",
                (uuid, name, version, createTime, updateTime, createUser, updateUser) -> LearningFlowDoc.builder()
                        .uuid(uuid)
                        .name(name)
                        .version(version)
                        .targetSchema(EVENTS_V4)
                        .build());

        assertThat(store.readDocument(docRef).getTargetSchema()).isEqualTo(EVENTS_V4);
    }


    @Test
    void writeThenReadRoundTripsTheNestedLists() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final LearningFlowDoc written = store.writeDocument(
                LearningFlowFixture.configured(store.readDocument(docRef)).build());

        final LearningFlowDoc read = store.readDocument(docRef);

        assertThat(read).isEqualTo(written);
        assertThat(read.getChecks()).containsExactlyElementsOf(LearningFlowFixture.CHECKS);
        assertThat(read.getChecks().get(6).getParameters())
                .describedAs("polymorphic parameters survive the store")
                .isEqualTo(LearningFlowFixture.CHECKS.get(6).getParameters());
    }

    @Test
    void saveRefusesAFlowTheStageCannotHoldAndStampsTheBuiltInVersion() {
        // A flow can reach the store without passing through the editor (for example by import), so the store
        // validates it on save: step order, repeated steps, duplicate ids, transitions to missing steps and
        // unknown template variables. A valid flow is stamped with the version of the built-in question text
        // it was saved against, and only its overridden templates are stored.
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
    }

    @Test
    void remapDependenciesRewritesTheStoredDoc() {
        // Shows the function is reached through StoreImpl.remapDependencies, which is what import calls,
        // and not only that it works when called directly.
        final DocRef docRef = store.createDocument("syslog-ai");
        store.writeDocument(LearningFlowFixture.configured(store.readDocument(docRef)).build());

        final DocRef importedModel = LearningFlowFixture.MODEL.copy().uuid("model-2").build();
        final DocRef importedFragment = LearningFlowFixture.FRAGMENT.copy().uuid("fragment-2").build();
        store.remapDependencies(docRef, Map.of(
                LearningFlowFixture.MODEL, importedModel,
                LearningFlowFixture.FRAGMENT, importedFragment));

        final LearningFlowDoc read = store.readDocument(docRef);
        assertThat(read.getModel()).isEqualTo(importedModel);
        assertThat(read.getChecks()).isEqualTo(LearningFlowFixture.CHECKS);
    }

    @Test
    void usingNewTranslationsAutomaticallyNeedsThePermission() {
        // Automatic promotion approves every future translation, for every feed the Learning Flow serves,
        // without anyone looking at it, so it needs a wider permission than editing the Learning Flow.
        final Restricted restricted = LearningFlowFixture.restricted();
        final DocRef docRef = restricted.store().createDocument("syslog-ai");
        restricted.withhold(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY);
        final LearningFlowDoc automatic = restricted.store().readDocument(docRef).copy()
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
        final Restricted restricted = LearningFlowFixture.restricted();
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
        final Restricted restricted = LearningFlowFixture.restricted();
        final DocRef docRef = restricted.store().createDocument("syslog-ai");
        restricted.store().writeDocument(restricted.store().readDocument(docRef).copy()
                .promotionMode(PromotionMode.AUTOMATIC)
                .build());
        restricted.withhold(AppPermission.APPROVE_SMART_PIPELINES_AUTOMATICALLY);

        restricted.store().writeDocument(restricted.store().readDocument(docRef).copy()
                .instructions("Prefer named fields.")
                .build());

        final LearningFlowDoc read = restricted.store().readDocument(docRef);
        assertThat(read.getPromotionMode()).isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(read.getInstructions()).isEqualTo("Prefer named fields.");
    }

    /// The target schema (D1) is a dependency, as the model is: an import re-points it at the imported copy,
    /// so a Learning Flow and the schema it names travel together.
    @Test
    void remapDependenciesRewritesTheTargetSchema() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final DocRef schema = DocRef.builder().type("XMLSchema").uuid("schema-1").name("event-logging v4.1.0")
                .build();
        store.writeDocument(LearningFlowFixture.configured(store.readDocument(docRef)).targetSchema(schema)
                .build());

        final DocRef importedSchema = schema.copy().uuid("schema-2").build();
        store.remapDependencies(docRef, Map.of(schema, importedSchema));

        final LearningFlowDoc read = store.readDocument(docRef);
        assertThat(read.getTargetSchema()).isEqualTo(importedSchema);
        assertThat(read.getModel()).isEqualTo(LearningFlowFixture.MODEL);
    }

    // A document written as a store before the target schema would have: stamped with the built-in text's
    // version it was saved against, or never saved, and naming no target
    private static DocRef savedBefore(final StoreFactory storeFactory,
                                      final String name,
                                      final Integer builtInVersion) {
        return LearningFlowFixture.raw(storeFactory).createDocument(name,
                (uuid, docName, version, createTime, updateTime, createUser, updateUser) -> {
                    final LearningFlow flow = FlowExamples.flow(FlowExample.DIRECT);
                    return LearningFlowDoc.builder()
                            .uuid(uuid)
                            .name(docName)
                            .version(version)
                            .flow(builtInVersion == null
                                    ? flow
                                    : flow.withBuiltInVersion(builtInVersion))
                            .build();
                });
    }

    // An event-logging schema of a version, as the lookup describes one
    private static TargetSchema eventLogging(final DocRef docRef, final String version) {
        return new TargetSchema(docRef, docRef.getName(), TargetSchema.EVENT_LOGGING,
                "file://event-logging-v" + version + ".xsd", version, "Events", "<xs:schema/>");
    }
}
