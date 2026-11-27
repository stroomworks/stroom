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


package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowElements;
import stroom.learningflow.stage.Learner;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.security.mock.MockSecurityContext;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests how the Learning Flow document a learner runs under is found from its pipeline's current settings.
///
/// The Supervisor uses this to check that a request's learner belongs to the document the request names.
class TestLearnerDocuments {

    private static final String PIPELINE = "pipeline-1";
    private static final DocRef DOCUMENT = LearningFlowDoc.buildDocRef().uuid("doc-1").name("door-access").build();

    @Test
    void theStageElementNamesItsDocument() {
        final LearnerDocuments documents = documents(new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("stage", LearningFlowElements.PARSER))
                .addProperty(PipelineDataUtil.createProperty("stage", LearningFlowElements.DOCUMENT_PROPERTY, DOCUMENT))
                .build());

        assertThat(documents.documentOf(new Learner(PIPELINE, "stage"))).contains("doc-1");
    }

    @Test
    void nothingIsNamedWhereThereIsNoSuchStage() {
        final LearnerDocuments documents = documents(new PipelineDataBuilder()
                .addElement(new PipelineElement("stage", LearningFlowElements.PARSER))
                .addElement(new PipelineElement("other", "XSLTFilter"))
                .addProperty(PipelineDataUtil.createProperty("other", LearningFlowElements.DOCUMENT_PROPERTY, DOCUMENT))
                .build());

        assertThat(documents.documentOf(new Learner(PIPELINE, "missing")))
                .describedAs("an element the pipeline does not have")
                .isEmpty();
        assertThat(documents.documentOf(new Learner(PIPELINE, "other")))
                .describedAs("an element that is not a Learning Flow stage, whatever its properties say")
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
