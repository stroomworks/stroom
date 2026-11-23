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

import stroom.dependency.shared.GraphElement;
import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelineReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/// What a pipeline's configuration says about where its data goes, which `doc_dependency` does not
/// record: a pipeline's edge to a feed may be its output or a reference loader's input, and its edge to
/// another pipeline may be its parent or a reference loader.
///
/// Read from the pipeline's data merged with its ancestors', so outputs it inherits count too.
///
/// A feed edge that is not an output is a reference loader's input, and a pipeline edge that is not the
/// parent is a reference loader's pipeline, so those need no facts of their own.
///
/// Its elements are listed too, in the order data flows through them, each saying which ancestor defines
/// it if it is inherited, so an expanded pipeline can name each one; and which of them names each
/// document it refers to, so a reference can be drawn from the element that uses it.
///
/// @param parent        The pipeline it inherits from, or null.
/// @param outputs       The UUIDs of the feeds and stores it writes to.
/// @param elements      Its elements, in the order data flows through them, without the merger's `Source`.
/// @param elementsUsing The ids of the elements that name each document, by the document's UUID, in the
///                      order of [#elements]. A document no element names (e.g. its parent) has none.
public record PipelineFacts(DocRef parent,
                            Set<String> outputs,
                            List<GraphElement> elements,
                            Map<String, List<String>> elementsUsing) {

    /// Facts that say no element names any document.
    ///
    /// @param parent   The pipeline it inherits from, or null.
    /// @param outputs  The UUIDs of the feeds and stores it writes to.
    /// @param elements Its elements, in the order data flows through them.
    public PipelineFacts(final DocRef parent, final Set<String> outputs, final List<GraphElement> elements) {
        this(parent, outputs, elements, Map.of());
    }

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

        final List<GraphElement> ordered = inFlowOrder(elements, merger.getLinks(), definedIn(layers));
        return new PipelineFacts(parent, outputs, ordered, elementsUsing(ordered, merger));
    }

    /// @return how many elements the pipeline has.
    public int elementCount() {
        return elements.size();
    }

    /// @param documentUuid A document's UUID.
    /// @return the ids of the elements that name the document, in the order data flows through them; empty
    /// if none does.
    public List<String> elementsUsing(final String documentUuid) {
        return elementsUsing.getOrDefault(documentUuid, List.of());
    }

    // Which ancestor added each inherited element: every layer but the last, which is the pipeline itself.
    // The merger refuses an element added twice, so each element has one layer.
    private static Map<String, DocRef> definedIn(final List<PipelineLayer> layers) {
        final Map<String, DocRef> definedIn = new HashMap<>();
        for (int i = 0; i < layers.size() - 1; i++) {
            final PipelineLayer layer = layers.get(i);
            if (layer != null && layer.getPipelineData() != null) {
                for (final PipelineElement element : layer.getPipelineData().getAddedElements()) {
                    definedIn.put(element.getId(), layer.getSourcePipeline());
                }
            }
        }
        return definedIn;
    }

    // The elements naming each document, through a property (e.g. an XSLT filter's xslt) or a pipeline
    // reference (a reference loader's pipeline and feed), in the order of the elements given.
    private static Map<String, List<String>> elementsUsing(final List<GraphElement> ordered,
                                                           final PipelineDataMerger merger) {
        final Map<String, List<String>> using = new HashMap<>();
        for (final GraphElement element : ordered) {
            final Set<String> named = new LinkedHashSet<>();
            final Map<String, PipelineProperty> properties = merger.getProperties().get(element.getId());
            if (properties != null) {
                for (final PipelineProperty property : properties.values()) {
                    if (property.getValue() != null) {
                        addUuid(named, property.getValue().getEntity());
                    }
                }
            }
            final Map<String, List<PipelineReference>> references =
                    merger.getPipelineReferences().get(element.getId());
            if (references != null) {
                for (final List<PipelineReference> list : references.values()) {
                    for (final PipelineReference reference : list) {
                        addUuid(named, reference.getPipeline());
                        addUuid(named, reference.getFeed());
                    }
                }
            }
            for (final String uuid : named) {
                using.computeIfAbsent(uuid, k -> new ArrayList<>()).add(element.getId());
            }
        }
        return using;
    }

    private static void addUuid(final Set<String> uuids, final DocRef docRef) {
        if (docRef != null && docRef.getUuid() != null) {
            uuids.add(docRef.getUuid());
        }
    }

    /// Lists a pipeline's elements in the order data flows through them: depth first along the links from
    /// `Source`, a branch's links in the order they were added. Elements `Source` doesn't reach (e.g. an
    /// unlinked chain) follow, each chain from its first element, the chains in order of that element's
    /// id; any left, e.g. in a loop, follow by id. `Source` itself is left out: the merger adds it, and
    /// the user doesn't.
    ///
    /// @param elements  The merged elements, by id.
    /// @param links     The merged links, by the id of the element they leave.
    /// @param definedIn The ancestor that defines each inherited element, by id.
    /// @return the elements, each once.
    static List<GraphElement> inFlowOrder(final Map<String, PipelineElement> elements,
                                          final Map<String, List<PipelineLink>> links,
                                          final Map<String, DocRef> definedIn) {
        final Set<String> ordered = new LinkedHashSet<>();
        visit(SOURCE_ELEMENT_ID, links, elements, ordered);

        // Chains Source doesn't reach start from an element nothing links to
        final Set<String> linkedTo = new HashSet<>();
        links.values().forEach(list -> list.forEach(link -> linkedTo.add(link.getTo())));
        final Set<String> remaining = new TreeSet<>(elements.keySet());
        for (final String id : remaining) {
            if (!linkedTo.contains(id)) {
                visit(id, links, elements, ordered);
            }
        }
        for (final String id : remaining) {
            visit(id, links, elements, ordered);
        }

        final List<GraphElement> result = new ArrayList<>(ordered.size());
        for (final String id : ordered) {
            if (!SOURCE_ELEMENT_ID.equals(id)) {
                final PipelineElement element = elements.get(id);
                result.add(new GraphElement(id, element.getName(), element.getType(), definedIn.get(id)));
            }
        }
        return result;
    }

    // Adds an element, then those it links to, depth first, once each. Iterative, so a long chain can't
    // overflow the stack.
    private static void visit(final String startId,
                              final Map<String, List<PipelineLink>> links,
                              final Map<String, PipelineElement> elements,
                              final Set<String> ordered) {
        final List<String> stack = new ArrayList<>();
        stack.add(startId);
        while (!stack.isEmpty()) {
            final String id = stack.removeLast();
            if (elements.containsKey(id) && ordered.add(id)) {
                final List<PipelineLink> out = links.getOrDefault(id, List.of());
                // Pushed in reverse, so the first link added is followed first
                for (int i = out.size() - 1; i >= 0; i--) {
                    stack.add(out.get(i).getTo());
                }
            }
        }
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
