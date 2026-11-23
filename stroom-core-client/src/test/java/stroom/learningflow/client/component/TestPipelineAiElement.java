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
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowElements;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPipelineAiElement {

    private static final DocRef FIREWALL = new DocRef(LearningFlowDoc.TYPE, "doc-1", "Firewall");
    private static final DocRef PROXY = new DocRef(LearningFlowDoc.TYPE, "doc-2", "Proxy");
    private static final DocRef PARENT = new DocRef(PipelineDoc.TYPE, "parent", "Parent");
    private static final DocRef CHILD = new DocRef(PipelineDoc.TYPE, "child", "Child");

    @Test
    void aPipelineWithoutLayersOrAiElementsHasNone() {
        assertThat(PipelineAiElement.of(List.of())).isEmpty();
        assertThat(PipelineAiElement.of(null)).isEmpty();
        assertThat(PipelineAiElement.of(List.of(new PipelineLayer(CHILD, new PipelineDataBuilder()
                .addElement(element("xsltFilter", "XSLTFilter", null))
                .build())))).isEmpty();
    }

    @Test
    void anAiElementIsFoundWithTheDocumentItLearnsUnder() {
        final List<PipelineAiElement> found = PipelineAiElement.of(List.of(new PipelineLayer(CHILD,
                new PipelineDataBuilder()
                        .addElement(element("learningFlow", LearningFlowElements.PARSER, "Learn the feed"))
                        .addElement(element("xsltFilter", "XSLTFilter", null))
                        .addProperty(PipelineDataUtil.createProperty("learningFlow",
                                LearningFlowElements.DOCUMENT_PROPERTY, FIREWALL))
                        .build())));

        assertThat(found).singleElement().satisfies(element -> {
            assertThat(element.getId()).isEqualTo("learningFlow");
            assertThat(element.getDocument()).isEqualTo(FIREWALL);
            assertThat(element.getDisplayValue()).isEqualTo("Learn the feed (Firewall)");
        });
    }

    @Test
    void anInheritedElementCountsAndItsDocumentMayBeOverridden() {
        final PipelineLayer parent = new PipelineLayer(PARENT, new PipelineDataBuilder()
                .addElement(element("learningFlow", LearningFlowElements.PARSER, null))
                .addProperty(PipelineDataUtil.createProperty("learningFlow",
                        LearningFlowElements.DOCUMENT_PROPERTY, FIREWALL))
                .build());

        assertThat(PipelineAiElement.of(List.of(parent, new PipelineLayer(CHILD, new PipelineDataBuilder().build()))))
                .describedAs("inherited as it is")
                .singleElement()
                .satisfies(element -> assertThat(element.getDocument()).isEqualTo(FIREWALL));
        assertThat(PipelineAiElement.of(List.of(parent, new PipelineLayer(CHILD, new PipelineDataBuilder()
                        .addProperty(PipelineDataUtil.createProperty("learningFlow",
                                LearningFlowElements.DOCUMENT_PROPERTY, PROXY))
                        .build()))))
                .describedAs("or learning under another document the child names")
                .singleElement()
                .satisfies(element -> assertThat(element.getDocument()).isEqualTo(PROXY));
    }

    @Test
    void anElementWithoutADocumentOrNameIsShownByItsId() {
        assertThat(PipelineAiElement.of(List.of(new PipelineLayer(CHILD, new PipelineDataBuilder()
                .addElement(element("learner", LearningFlowElements.PARSER, null))
                .build()))))
                .singleElement()
                .satisfies(element -> {
                    assertThat(element.getDocument()).isNull();
                    assertThat(element.getDisplayValue()).isEqualTo("learner");
                });
    }

    @Test
    void severalAreOrderedByWhatTheyAreCalled() {
        final List<PipelineAiElement> found = PipelineAiElement.of(List.of(new PipelineLayer(CHILD,
                new PipelineDataBuilder()
                        .addElement(element("b", LearningFlowElements.PARSER, "Second"))
                        .addElement(element("a", LearningFlowElements.PARSER, "First"))
                        .build())));

        assertThat(found).extracting(PipelineAiElement::getId).containsExactly("a", "b");
    }

    private static PipelineElement element(final String id, final String type, final String name) {
        return PipelineDataUtil.createElement(id, type, name, null);
    }
}
