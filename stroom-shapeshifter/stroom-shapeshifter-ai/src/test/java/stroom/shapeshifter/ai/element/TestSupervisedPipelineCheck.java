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
import stroom.docstore.api.DocumentNotFoundException;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.shapeshifter.shared.ShapeshifterAiElements;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/// Whether a filter's pipeline holds a supervised stage, as the processor asks where a filter is made
/// (design 04 §7). An import makes filters before the pipelines they name, and pipelines before their
/// parents: neither may fail the import.
class TestSupervisedPipelineCheck {

    private static final DocRef PIPELINE = new DocRef(PipelineDoc.TYPE, "pipeline-1", "DOOR-ACCESS");
    private static final PipelineDoc DOC = PipelineDoc.builder().uuid("pipeline-1").name("DOOR-ACCESS").build();

    private final PipelineStore store = Mockito.mock(PipelineStore.class);
    private final PipelineDataCache cache = Mockito.mock(PipelineDataCache.class);
    private final SupervisedPipelineCheck check = new SupervisedPipelineCheck(() -> store, () -> cache);

    @Test
    void aPipelineHoldingTheStageIsSupervised() {
        Mockito.when(store.readDocument(PIPELINE)).thenReturn(DOC);
        Mockito.when(cache.get(DOC)).thenReturn(new PipelineDataBuilder()
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiElements.PARSER))
                .build());

        assertThat(check.isSupervised(PIPELINE)).isTrue();
    }

    @Test
    void aPipelineWithoutItIsNot() {
        Mockito.when(store.readDocument(PIPELINE)).thenReturn(DOC);
        Mockito.when(cache.get(DOC)).thenReturn(new PipelineDataBuilder()
                .addElement(new PipelineElement("dsParser", "DSParser"))
                .build());

        assertThat(check.isSupervised(PIPELINE)).isFalse();
    }

    @Test
    void aPipelineThatHasNotArrivedIsNotJudged() {
        Mockito.when(store.readDocument(PIPELINE)).thenThrow(new DocumentNotFoundException(PIPELINE));

        assertThat(check.isSupervised(PIPELINE)).isFalse();
    }

    @Test
    void aPipelineWhoseParentHasNotArrivedIsNotJudged() {
        // Read, but merged with a parent the import has not brought yet: the merge throws.
        Mockito.when(store.readDocument(PIPELINE)).thenReturn(DOC);
        Mockito.when(cache.get(DOC)).thenThrow(new DocumentNotFoundException(
                new DocRef(PipelineDoc.TYPE, "parent-1")));

        assertThat(check.isSupervised(PIPELINE)).isFalse();
    }
}
