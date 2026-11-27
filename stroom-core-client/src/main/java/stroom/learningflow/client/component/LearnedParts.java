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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.util.shared.NullSafe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/// Lists the elements of a Smart Pipeline element's learned translation (its fragment pipeline), for the
/// "What it learned" section of the pipeline's Structure tab. The elements are given in the order a record
/// passes through them, each with the documents it uses (e.g. a parser's text converter, a filter's XSLT),
/// which can be opened from there.
///
/// The fragment's own Source element is left out, because it is only where the Smart Pipeline passes its
/// records in, not something that was learned. It is a plain class so that it can be tested without a
/// browser.
public final class LearnedParts {

    private static final String SOURCE = "Source";

    private LearnedParts() {
        // Static utility
    }

    /// Lists the parts of a learned translation.
    ///
    /// @param layers   The fragment pipeline's layers, its ancestors' first, as the pipeline resource gives
    ///                 them.
    /// @param typeName Gives an element's type as people see it, e.g. "XSLT Filter", or null to use the raw
    ///                 type.
    /// @return The elements in the order records pass through them (branches in order of element id), then any
    ///         not reached that way; empty if there are none.
    public static List<Part> of(final List<PipelineLayer> layers, final Function<PipelineElement, String> typeName) {
        if (NullSafe.isEmptyCollection(layers)) {
            return List.of();
        }
        final PipelineDataMerger merged = new PipelineDataMerger().merge(layers);
        final Map<String, PipelineElement> elements = merged.getElements();
        final Map<String, List<PipelineLink>> links = merged.getLinks();
        final Set<String> ordered = new LinkedHashSet<>();
        for (final String root : roots(elements, links)) {
            follow(root, links, ordered);
        }
        // Add any elements the walk didn't reach, e.g. because they are in a loop, in order of id
        ordered.addAll(new TreeMap<>(elements).keySet());

        final List<Part> parts = new ArrayList<>();
        for (final String id : ordered) {
            final PipelineElement element = elements.get(id);
            if (element == null || SOURCE.equals(element.getType())) {
                continue;
            }
            parts.add(new Part(id,
                    Objects.requireNonNullElse(typeName.apply(element), element.getType()),
                    element.getName(),
                    documents(merged.getProperties().get(id))));
        }
        return parts;
    }

    // Returns the elements that nothing links to, in order of id
    private static List<String> roots(final Map<String, PipelineElement> elements,
                                      final Map<String, List<PipelineLink>> links) {
        final Set<String> targets = new HashSet<>();
        links.values().forEach(from -> from.forEach(link -> targets.add(link.getTo())));
        final List<String> roots = new ArrayList<>();
        for (final String id : new TreeMap<>(elements).keySet()) {
            if (!targets.contains(id)) {
                roots.add(id);
            }
        }
        return roots;
    }

    private static void follow(final String id, final Map<String, List<PipelineLink>> links, final Set<String> seen) {
        if (!seen.add(id)) {
            return;
        }
        final List<PipelineLink> from = new ArrayList<>(NullSafe.list(links.get(id)));
        Collections.sort(from);
        for (final PipelineLink link : from) {
            follow(link.getTo(), links, seen);
        }
    }

    // Returns the documents an element's properties refer to, in order of property name
    private static List<DocRef> documents(final Map<String, PipelineProperty> properties) {
        if (properties == null) {
            return List.of();
        }
        final List<DocRef> documents = new ArrayList<>();
        for (final PipelineProperty property : new TreeMap<>(properties).values()) {
            final DocRef document = NullSafe.get(property, PipelineProperty::getValue, value -> value.getEntity());
            if (document != null) {
                documents.add(document);
            }
        }
        return Collections.unmodifiableList(documents);
    }


    // --------------------------------------------------------------------------------


    /// One element of a learned translation.
    public static final class Part {

        private final String id;
        private final String type;
        private final String name;
        private final List<DocRef> documents;

        Part(final String id, final String type, final String name, final List<DocRef> documents) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.documents = documents;
        }

        /// @return The element's id in the fragment.
        public String getId() {
            return id;
        }

        /// @return The element's type as people see it, e.g. "XSLT Filter".
        public String getType() {
            return type;
        }

        /// @return The element's name in the fragment, or its id if it has no name.
        public String getName() {
            return name == null || name.isEmpty()
                    ? id
                    : name;
        }

        /// @return The documents the element uses, e.g. its XSLT; empty if none.
        public List<DocRef> getDocuments() {
            return documents;
        }
    }
}
