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
import stroom.docstore.api.StoreFactory;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
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

    private static final DocRef EVENTS_V3 = XmlSchemaDoc.buildDocRef()
            .uuid("4fe14042-770e-4297-8711-d92e607bc4d5")
            .name("event-logging v3.0.0")
            .build();
    private static final DocRef EVENTS_V4 = XmlSchemaDoc.buildDocRef()
            .uuid("befcc474-36e4-4db4-a610-bd1fe6825cb1")
            .name("event-logging v4.1.0")
            .build();
    /// Event-logging v3.0.0 and v4.1.0 installed.
    private static final EventLoggingSchemas INSTALLED = new EventLoggingSchemas() {
        @Override
        public Optional<DocRef> latest() {
            return Optional.of(EVENTS_V4);
        }

        @Override
        public Optional<DocRef> v3() {
            return Optional.of(EVENTS_V3);
        }
    };

    private final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store();

    @Test
    void createAppliesTheDefaults() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final ShapeshifterAiDoc doc = store.readDocument(docRef);

        assertThat(doc.getName()).isEqualTo("syslog-ai");
        assertThat(doc.getPromotionMode()).isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(doc.getLearningMode())
                .describedAs("Shapeshifter AI is opt-in per document (§11), so a new document must not call the model")
                .isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getModel()).isNull();
        assertThat(doc.getScorers()).isEmpty();
        assertThat(doc.getTargetSchema())
                .describedAs("no event-logging schema is installed")
                .isNull();
    }

    /// A new Learning Flow starts with the latest event-logging version installed, saved on it, so that a later
    /// version being installed does not move it: moving up is a deliberate edit.
    @Test
    void aNewDocumentIsGivenTheLatestEventLoggingVersionInstalled() {
        final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store(INSTALLED);

        final DocRef docRef = store.createDocument("syslog-ai");

        final ShapeshifterAiDoc doc = store.readDocument(docRef);
        assertThat(doc.getTargetSchema()).isEqualTo(EVENTS_V4);
        assertThat(doc.getFlow().getBuiltInVersion())
                .describedAs("stamped as made since the target schema existed, so not read as an older one")
                .isEqualTo(Templates.VERSION);
    }

    /// A Learning Flow saved before it could name a target schema reads as event-logging v3.0.0, which it was
    /// learned against, and keeps it once saved.
    @Test
    void aDocumentSavedBeforeTheTargetSchemaReadsAsV3() {
        final StoreFactory storeFactory = ShapeshifterAiFixture.storeFactory();
        final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store(INSTALLED, storeFactory);
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
        final StoreFactory storeFactory = ShapeshifterAiFixture.storeFactory();
        final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store(INSTALLED, storeFactory);
        final DocRef docRef = savedBefore(storeFactory, "since", Templates.VERSION);

        assertThat(store.readDocument(docRef).getTargetSchema()).isNull();
    }

    @Test
    void aDocumentSavedBeforeTheTargetSchemaWithoutV3InstalledHasNone() {
        final StoreFactory storeFactory = ShapeshifterAiFixture.storeFactory();
        final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store(EventLoggingSchemas.NONE, storeFactory);
        final DocRef docRef = savedBefore(storeFactory, "old", 6);

        assertThat(store.readDocument(docRef).getTargetSchema()).isNull();
    }

    @Test
    void aDocumentsOwnTargetSchemaIsKept() {
        final StoreFactory storeFactory = ShapeshifterAiFixture.storeFactory();
        final ShapeshifterAiStoreImpl store = ShapeshifterAiFixture.store(INSTALLED, storeFactory);
        final DocRef docRef = ShapeshifterAiFixture.raw(storeFactory).createDocument("old",
                (uuid, name, version, createTime, updateTime, createUser, updateUser) -> ShapeshifterAiDoc.builder()
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
        final ShapeshifterAiDoc written = store.writeDocument(
                ShapeshifterAiFixture.configured(store.readDocument(docRef)).build());

        final ShapeshifterAiDoc read = store.readDocument(docRef);

        assertThat(read).isEqualTo(written);
        assertThat(read.getScorers()).containsExactlyElementsOf(ShapeshifterAiFixture.SCORERS);
        assertThat(read.getScorers().get(2).getParameters())
                .describedAs("polymorphic parameters survive the store")
                .isEqualTo(ShapeshifterAiFixture.SCORERS.get(2).getParameters());
    }

    @Test
    void saveRefusesAFlowTheStageCannotHoldAndStampsTheBuiltInVersion() {
        // Scenarios 39 and 40 (A33): the binding and the variable are named where the editor cannot check them;
        // a definition that holds is saved against the built-in text of the day.
        final DocRef docRef = store.createDocument("syslog-ai");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CONFIGURE), FlowStep.of(QuestionKind.CHAIN))))
                .build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The first step must be 'Choose the elements'")
                .hasMessageContaining("The last step must write the translation");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.SPLIT),
                        FlowStep.of(QuestionKind.SPLIT), FlowStep.of(QuestionKind.CONFIGURE))))
                .build()))
                .hasMessageContaining("The flow has 2 'Find what one record is' steps");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE parser on spent goto nowhere"),
                        FlowStep.parse("CONFIGURE parser"))))
                .build()))
                .hasMessageContaining("Step 3 (Write the parser again) has the same id as an earlier step, 'parser'")
                .hasMessageContaining("The rule 'if it runs out of tries' on step 2 (Write the parser) goes to "
                                      + "'nowhere', which isn't a step");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.DIRECT)
                        .withTemplates(Map.of(Template.TARGET, "Become ${nothing}")))
                .build()))
                .hasMessageContaining("The target question template names ${nothing}");

        store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.TARGET_FIRST)
                        .withTemplates(Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}")))
                .build());
        final LearningFlow saved = store.readDocument(docRef).getFlow();
        assertThat(saved.getBuiltInVersion()).isEqualTo(Templates.VERSION);
        assertThat(saved.getTemplates()).containsOnlyKeys(Template.CHAIN);
        assertThat(saved.getSteps()).isEqualTo(FlowExample.TARGET_FIRST.steps());
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
        assertThat(read.getScorers()).isEqualTo(ShapeshifterAiFixture.SCORERS);
    }

    /// The target schema (D1) is a dependency, as the model is: an import re-points it at the imported copy,
    /// so a Learning Flow and the schema it names travel together.
    @Test
    void remapDependenciesRewritesTheTargetSchema() {
        final DocRef docRef = store.createDocument("syslog-ai");
        final DocRef schema = DocRef.builder().type("XMLSchema").uuid("schema-1").name("event-logging v4.1.0")
                .build();
        store.writeDocument(ShapeshifterAiFixture.configured(store.readDocument(docRef)).targetSchema(schema)
                .build());

        final DocRef importedSchema = schema.copy().uuid("schema-2").build();
        store.remapDependencies(docRef, Map.of(schema, importedSchema));

        final ShapeshifterAiDoc read = store.readDocument(docRef);
        assertThat(read.getTargetSchema()).isEqualTo(importedSchema);
        assertThat(read.getModel()).isEqualTo(ShapeshifterAiFixture.MODEL);
    }

    // A document written as a store before the target schema would have: stamped with the built-in text's
    // version it was saved against, or never saved, and naming no target
    private static DocRef savedBefore(final StoreFactory storeFactory,
                                      final String name,
                                      final Integer builtInVersion) {
        return ShapeshifterAiFixture.raw(storeFactory).createDocument(name,
                (uuid, docName, version, createTime, updateTime, createUser, updateUser) -> {
                    final LearningFlow flow = LearningFlow.of(FlowExample.DIRECT);
                    return ShapeshifterAiDoc.builder()
                            .uuid(uuid)
                            .name(docName)
                            .version(version)
                            .flow(builtInVersion == null
                                    ? flow
                                    : flow.withBuiltInVersion(builtInVersion))
                            .build();
                });
    }
}
