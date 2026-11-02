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
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        assertThat(doc.getPromotionMode()).isEqualTo(PromotionMode.AUTOMATIC);
        assertThat(doc.getLearningMode())
                .describedAs("Shapeshifter AI is opt-in per document (§11), so a new document must not call the model")
                .isEqualTo(LearningMode.DISABLED);
        assertThat(doc.getModel()).isNull();
        assertThat(doc.getScorers()).isEmpty();
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
                .hasMessageContaining("The first step must be CHAIN")
                .hasMessageContaining("The last step must be CONFIGURE");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.of(QuestionKind.CHAIN), FlowStep.of(QuestionKind.SPLIT),
                        FlowStep.of(QuestionKind.SPLIT), FlowStep.of(QuestionKind.CONFIGURE))))
                .build()))
                .hasMessageContaining("SPLIT may appear once");
        assertThatThrownBy(() -> store.writeDocument(store.readDocument(docRef).copy()
                .flow(LearningFlow.of(FlowExample.DIRECT).withSteps(List.of(
                        FlowStep.parse("CHAIN"), FlowStep.parse("CONFIGURE parser on spent goto nowhere"),
                        FlowStep.parse("CONFIGURE parser"))))
                .build()))
                .hasMessageContaining("Two steps are named 'parser'")
                .hasMessageContaining("'on spent goto nowhere' in step 'parser' names no step");
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
}
