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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.factory.PipelineDataCache;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.processor.api.SupervisedPipelines;
import stroom.shapeshifter.shared.ShapeshifterAiElements;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// What the processor asks where a filter is made (design 04 §7, phase 6): whether a pipeline holds a
/// Shapeshifter AI stage, whose tasks may be deferred and woken, and which a node processes correctly
/// only when it claims its own tasks. Read from the pipeline as it is merged, so a stage inherited from a
/// parent counts.
public class SupervisedPipelineCheck implements SupervisedPipelines {

    private final Provider<PipelineStore> pipelineStoreProvider;
    private final Provider<PipelineDataCache> pipelineDataCacheProvider;

    @Inject
    public SupervisedPipelineCheck(final Provider<PipelineStore> pipelineStoreProvider,
                                   final Provider<PipelineDataCache> pipelineDataCacheProvider) {
        this.pipelineStoreProvider = pipelineStoreProvider;
        this.pipelineDataCacheProvider = pipelineDataCacheProvider;
    }

    /// A pipeline that cannot be read, or merged with the pipelines it inherits from, cannot be judged and
    /// is taken as not supervised: an import makes a filter before the pipeline it names has arrived, or
    /// before that pipeline's parent has, and a filter a person makes names one that is there.
    @Override
    public boolean isSupervised(final DocRef pipeline) {
        try {
            final PipelineDoc doc = pipelineStoreProvider.get().readDocument(pipeline);
            if (doc == null) {
                return false;
            }
            final PipelineData merged = pipelineDataCacheProvider.get().get(doc);
            return merged != null && merged.getAddedElements().stream()
                    .anyMatch(element -> ShapeshifterAiElements.PARSER.equals(element.getType()));
        } catch (final RuntimeException e) {
            return false;
        }
    }
}
