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

package stroom.explorer.impl.graph;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelineProperty;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// What a pipeline's configuration says about where its data goes, which `doc_dependency` does not
/// record: a pipeline's edge to a feed may be its output or a reference loader's input, and its edge to
/// another pipeline may be its parent or a reference loader.
///
/// Read from the pipeline's data merged with its ancestors', so outputs it inherits count too.
///
/// A feed edge that is not an output is a reference loader's input, and a pipeline edge that is not the
/// parent is a reference loader's pipeline, so those need no facts of their own.
///
/// @param parent       The pipeline it inherits from, or null.
/// @param outputs      The UUIDs of the feeds and stores it writes to.
/// @param elementCount How many elements it has.
public record PipelineFacts(DocRef parent,
                            Set<String> outputs,
                            int elementCount) {

    /// Document types a pipeline writes to through whatever element names them, e.g. an index named by an
    /// indexing filter.
    static final Set<String> STORE_TYPES = Set.of(
            "Index", "ElasticIndex", "SolrIndex", "PlanB", "StatisticStore", "StroomStatsStore");

    private static final String FEED_TYPE = "Feed";
    private static final String DICTIONARY_TYPE = "Dictionary";
    // An element that writes what it is given somewhere, e.g. a StreamAppender to a feed.
    private static final String APPENDER_SUFFIX = "Appender";
    // The merger adds an element of its own for where data enters the pipeline.
    private static final String SOURCE_ELEMENT_ID = "Source";

    /// @param parent The pipeline's parent, or null.
    /// @param layers The pipeline's data and its ancestors', oldest ancestor first, as
    ///               [PipelineDataMerger#merge(List)] takes them.
    /// @return what the merged layers say.
    /// @throws stroom.pipeline.shared.PipelineModelException if the layers cannot be merged.
    public static PipelineFacts of(final DocRef parent, final List<PipelineLayer> layers) {
        final PipelineDataMerger merger = new PipelineDataMerger().merge(layers);
        final Map<String, PipelineElement> elements = merger.getElements();

        final Set<String> outputs = new HashSet<>();
        for (final Map.Entry<String, Map<String, PipelineProperty>> entry : merger.getProperties().entrySet()) {
            final PipelineElement element = elements.get(entry.getKey());
            final String elementType = element == null
                    ? ""
                    : element.getType();
            for (final PipelineProperty property : entry.getValue().values()) {
                final DocRef entity = property.getValue() == null
                        ? null
                        : property.getValue().getEntity();
                if (entity != null && isOutput(elementType, entity.getType())) {
                    outputs.add(entity.getUuid());
                }
            }
        }

        int elementCount = 0;
        for (final String id : elements.keySet()) {
            if (!SOURCE_ELEMENT_ID.equals(id)) {
                elementCount++;
            }
        }
        return new PipelineFacts(parent, outputs, elementCount);
    }

    // A store is written to by whatever element names it; a feed or dictionary only by an appender, since
    // other elements name feeds they read, e.g. a reference loader's.
    private static boolean isOutput(final String elementType, final String entityType) {
        if (STORE_TYPES.contains(entityType)) {
            return true;
        }
        return (FEED_TYPE.equals(entityType) || DICTIONARY_TYPE.equals(entityType))
               && elementType != null
               && elementType.endsWith(APPENDER_SUFFIX);
    }
}
