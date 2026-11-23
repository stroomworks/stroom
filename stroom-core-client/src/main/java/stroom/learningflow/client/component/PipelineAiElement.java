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

/// A Learning Flow element of a pipeline, as its AI Translation tab ([PipelineAiTab]) offers it: its id, the
/// name it is given in the pipeline, and the document it learns under, where one is set. A pipeline's elements
/// are those of its layers merged, so one inherited from a parent pipeline counts. A plain class, so that it
/// is tested without a browser.
public final class PipelineAiElement implements HasDisplayValue {

    private final String id;
    private final String name;
    private final DocRef document;

    PipelineAiElement(final String id, final String name, final DocRef document) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.document = document;
    }

    /// The Learning Flow elements of a pipeline, ordered by name.
    ///
    /// @param layers The pipeline's layers, its ancestors' first, as the pipeline resource gives them.
    /// @return Its AI elements; empty where it has none.
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

    /// @return The element's id in the pipeline, which with the pipeline names its learner.
    public String getId() {
        return id;
    }

    /// @return The document it learns under, or null where none is set.
    public DocRef getDocument() {
        return document;
    }

    /// @return Its name in the pipeline, or its id where it has none, and the document it learns under.
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
