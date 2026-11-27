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
import stroom.docref.HasDisplayValue;
import stroom.learningflow.shared.LearningFlowElements;
import stroom.pipeline.shared.PipelineDataMerger;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.util.shared.NullSafe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// One Smart Pipeline element in a pipeline, as offered for selection on the pipeline's Learning Status tab
/// ([PipelineAiTab]) and Quality Assurance tab. It holds the element's id, its name in the pipeline, and the
/// Learning Flow document it learns under, if one is set.
///
/// The elements are found in the pipeline merged with all its ancestors, so an element inherited from a
/// parent pipeline is included. This is a plain class so that it can be unit tested without a browser.
public final class PipelineAiElement implements HasDisplayValue {

    private final String id;
    private final String name;
    private final DocRef document;

    PipelineAiElement(final String id, final String name, final DocRef document) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.document = document;
    }

    /// Finds the Smart Pipeline elements of a pipeline, ordered by display name and then id.
    ///
    /// @param layers The pipeline's layers, ancestors first, as returned by the pipeline resource.
    /// @return The pipeline's Smart Pipeline elements; empty if it has none.
    public static List<PipelineAiElement> of(final List<PipelineLayer> layers) {
        if (NullSafe.isEmptyCollection(layers)) {
            return List.of();
        }
        final PipelineDataMerger merged = new PipelineDataMerger().merge(layers);
        final Map<String, Map<String, PipelineProperty>> properties = merged.getProperties();
        final List<PipelineAiElement> found = new ArrayList<>();
        for (final PipelineElement element : merged.getElements().values()) {
            if (LearningFlowElements.PARSER.equals(element.getType())) {
                final PipelineProperty property = NullSafe.get(properties.get(element.getId()),
                        byName -> byName.get(LearningFlowElements.DOCUMENT_PROPERTY));
                final DocRef document = NullSafe.get(property, PipelineProperty::getValue,
                        value -> value.getEntity());
                found.add(new PipelineAiElement(element.getId(), element.getName(), document));
            }
        }
        found.sort(Comparator.comparing(PipelineAiElement::getDisplayValue)
                .thenComparing(PipelineAiElement::getId));
        return found;
    }

    /// @return The element's id in the pipeline. Together with the pipeline's UUID, it identifies the
    ///         element's learner.
    public String getId() {
        return id;
    }

    /// @return The Learning Flow document the element learns under, or null if none is set.
    public DocRef getDocument() {
        return document;
    }

    /// @return The element's name in the pipeline (or its id if it has no name), followed by the name of the
    ///         Learning Flow it learns under in brackets, if one is set.
    @Override
    public String getDisplayValue() {
        final String shown = NullSafe.isBlankString(name)
                ? id
                : name;
        return document == null || NullSafe.isBlankString(document.getName())
                ? shown
                : shown + " (" + document.getName() + ")";
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof final PipelineAiElement that)) {
            return false;
        }
        return id.equals(that.id) && Objects.equals(name, that.name) && Objects.equals(document, that.document);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, document);
    }

    @Override
    public String toString() {
        return getDisplayValue();
    }
}
