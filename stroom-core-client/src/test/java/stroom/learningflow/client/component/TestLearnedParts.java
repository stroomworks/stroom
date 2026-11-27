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
import stroom.learningflow.client.component.LearnedParts.Part;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TestLearnedParts {

    private static final DocRef FRAGMENT = new DocRef(PipelineDoc.TYPE, "f-1", "Firewall pipeline learned");
    private static final DocRef CONVERTER = new DocRef("TextConverter", "tc-1", "Firewall parser");
    private static final DocRef XSLT = new DocRef("XSLT", "x-1", "Firewall transform");
    private static final Map<String, String> TYPES = Map.of(
            "DSParser", "Data Splitter",
            "XSLTFilter", "XSLT Filter");
    private static final Function<PipelineElement, String> TYPE_NAME = element -> TYPES.get(element.getType());

    @Test
    void nothingLearnedHasNoParts() {
        assertThat(LearnedParts.of(null, TYPE_NAME)).isEmpty();
        assertThat(LearnedParts.of(List.of(), TYPE_NAME)).isEmpty();
    }

    @Test
    void thePartsAreInTheOrderRecordsPassThroughThemWithTheirDocuments() {
        final List<Part> parts = LearnedParts.of(List.of(new PipelineLayer(FRAGMENT, new PipelineDataBuilder()
                // Added in a different order from their links, to show that the links decide the order
                .addElement(element("xsltFilter", "XSLTFilter", "Transform"))
                .addElement(element("Source", "Source", null))
                .addElement(element("dsParser", "DSParser", null))
                .addLink("Source", "dsParser")
                .addLink("dsParser", "xsltFilter")
                .addProperty(PipelineDataUtil.createProperty("dsParser", "textConverter", CONVERTER))
                .addProperty(PipelineDataUtil.createProperty("xsltFilter", "xslt", XSLT))
                .addProperty(PipelineDataUtil.createProperty("xsltFilter", "usePool", true))
                .build())), TYPE_NAME);

        assertThat(parts).extracting(Part::getId).containsExactly("dsParser", "xsltFilter");
        assertThat(parts.get(0).getType()).isEqualTo("Data Splitter");
        assertThat(parts.get(0).getName()).describedAs("by its id, having no name").isEqualTo("dsParser");
        assertThat(parts.get(0).getDocuments()).containsExactly(CONVERTER);
        assertThat(parts.get(1).getName()).isEqualTo("Transform");
        assertThat(parts.get(1).getDocuments()).describedAs("documents only, not other properties")
                .containsExactly(XSLT);
    }

    @Test
    void theFragmentsSourceIsNotAPartLearned() {
        final List<Part> parts = LearnedParts.of(List.of(new PipelineLayer(FRAGMENT, new PipelineDataBuilder()
                .addElement(element("Source", "Source", null))
                .build())), TYPE_NAME);
        assertThat(parts).isEmpty();
    }

    @Test
    void aTypeWithNoNameKnownIsShownAsItsType() {
        final List<Part> parts = LearnedParts.of(List.of(new PipelineLayer(FRAGMENT, new PipelineDataBuilder()
                .addElement(element("json", "JSONParser", null))
                .build())), TYPE_NAME);
        assertThat(parts).singleElement().satisfies(part -> assertThat(part.getType()).isEqualTo("JSONParser"));
    }

    @Test
    void partsNotLinkedComeAfterThoseThatAreByIdAndALoopEnds() {
        final List<Part> parts = LearnedParts.of(List.of(new PipelineLayer(FRAGMENT, new PipelineDataBuilder()
                .addElement(element("b", "XSLTFilter", null))
                .addElement(element("a", "XSLTFilter", null))
                .addElement(element("c", "XSLTFilter", null))
                .addElement(element("d", "XSLTFilter", null))
                .addLink("c", "d")
                .addLink("d", "c")
                .build())), TYPE_NAME);
        assertThat(parts).extracting(Part::getId).containsExactly("a", "b", "c", "d");
    }

    private static PipelineElement element(final String id, final String type, final String name) {
        return PipelineDataUtil.createElement(id, type, name, null);
    }
}
