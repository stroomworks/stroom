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


package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.security.mock.MockSecurityContext;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiElements;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/// The document a learner runs under, as its pipeline names it now (design 06 phase J slice 2b): what a
/// Supervisor request's learner is held to.
class TestLearnerDocuments {

    private static final String PIPELINE = "pipeline-1";
    private static final DocRef DOCUMENT = ShapeshifterAiDoc.buildDocRef().uuid("doc-1").name("door-access").build();

    @Test
    void theStageElementNamesItsDocument() {
        final LearnerDocuments documents = documents(new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("stage", ShapeshifterAiElements.PARSER))
                .addProperty(PipelineDataUtil.createProperty("stage", LearnerDocuments.DOCUMENT_PROPERTY, DOCUMENT))
                .build());

        assertThat(documents.documentOf(new Learner(PIPELINE, "stage"))).contains("doc-1");
    }

    @Test
    void nothingIsNamedWhereThereIsNoSuchStage() {
        final LearnerDocuments documents = documents(new PipelineDataBuilder()
                .addElement(new PipelineElement("stage", ShapeshifterAiElements.PARSER))
                .addElement(new PipelineElement("other", "XSLTFilter"))
                .addProperty(PipelineDataUtil.createProperty("other", LearnerDocuments.DOCUMENT_PROPERTY, DOCUMENT))
                .build());

        assertThat(documents.documentOf(new Learner(PIPELINE, "missing")))
                .describedAs("an element the pipeline does not have")
                .isEmpty();
        assertThat(documents.documentOf(new Learner(PIPELINE, "other")))
                .describedAs("an element that is not a Shapeshifter AI stage, whatever its properties say")
                .isEmpty();
        assertThat(documents.documentOf(new Learner(PIPELINE, "stage")))
                .describedAs("a stage that names no document")
                .isEmpty();
        assertThat(documents.documentOf(new Learner("no-such-pipeline", "stage")))
                .describedAs("a pipeline that is not there")
                .isEmpty();
    }

    private static LearnerDocuments documents(final PipelineData merged) {
        final PipelineDoc pipeline = PipelineDoc.builder().uuid(PIPELINE).name("door-access").build();
        final PipelineStore store = Mockito.mock(PipelineStore.class);
        Mockito.when(store.readDocument(new DocRef(PipelineDoc.TYPE, PIPELINE))).thenReturn(pipeline);
        final PipelineDataCache cache = Mockito.mock(PipelineDataCache.class);
        Mockito.when(cache.get(pipeline)).thenReturn(merged);
        return new LearnerDocuments(() -> store, () -> cache, MockSecurityContext::new);
    }
}
