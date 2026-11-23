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
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineLayer;
import stroom.pipeline.shared.data.PipelineLink;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.pipeline.shared.data.PipelineReference;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestPipelineFacts {

    private static final DocRef TEMPLATE = new DocRef("Pipeline", "template", "Parser Template");
    private static final DocRef PARSER = new DocRef("Pipeline", "parser", "FW Parser");
    private static final DocRef XSLT = new DocRef("XSLT", "xslt", "FW Translation");
    private static final DocRef EVENTS = new DocRef("Feed", "events", "FW:EVENTS");
    private static final DocRef LOOKUP = new DocRef("Feed", "lookup", "HOSTS:REFERENCE");
    private static final DocRef LOADER = new DocRef("Pipeline", "loader", "Reference Loader");

    @Test
    void testElementsFollowTheLinksFromSource() {
        final Elements elements = new Elements()
                .add("writer", "XMLWriter")
                .add("Source", "Source")
                .add("parser", "DSParser")
                .add("translation", "XSLTFilter")
                .link("Source", "parser")
                .link("parser", "translation")
                .link("translation", "writer");

        assertThat(ids(PipelineFacts.inFlowOrder(elements.byId, elements.links, Map.of())))
                .containsExactly("parser", "translation", "writer");
    }

    @Test
    void testABranchIsFollowedToItsEndBeforeTheNextInTheOrderItsLinksWereAdded() {
        final Elements elements = new Elements()
                .add("Source", "Source")
                .add("parser", "DSParser")
                .add("splitter", "SplitFilter")
                .add("eventsWriter", "XMLWriter")
                .add("eventsAppender", "StreamAppender")
                .add("alertsWriter", "XMLWriter")
                .link("Source", "parser")
                .link("parser", "splitter")
                .link("splitter", "eventsWriter")
                .link("splitter", "alertsWriter")
                .link("eventsWriter", "eventsAppender");

        assertThat(ids(PipelineFacts.inFlowOrder(elements.byId, elements.links, Map.of())))
                .containsExactly("parser", "splitter", "eventsWriter", "eventsAppender", "alertsWriter");
    }

    @Test
    void testUnlinkedChainsFollowEachFromItsFirstElementInOrderOfThatId() {
        final Elements elements = new Elements()
                .add("Source", "Source")
                .add("parser", "DSParser")
                .add("zWriter", "XMLWriter")
                .add("zAppender", "StreamAppender")
                .add("aOrphan", "XSLTFilter")
                .link("Source", "parser")
                .link("zWriter", "zAppender");

        assertThat(ids(PipelineFacts.inFlowOrder(elements.byId, elements.links, Map.of())))
                .containsExactly("parser", "aOrphan", "zWriter", "zAppender");
    }

    @Test
    void testALoopIsListedOnceEach() {
        final Elements elements = new Elements()
                .add("Source", "Source")
                .add("b", "XSLTFilter")
                .add("a", "XSLTFilter")
                .link("a", "b")
                .link("b", "a");

        assertThat(ids(PipelineFacts.inFlowOrder(elements.byId, elements.links, Map.of())))
                .containsExactly("a", "b");
    }

    @Test
    void testALinkToAnElementThatIsNotThereIsIgnored() {
        final Elements elements = new Elements()
                .add("Source", "Source")
                .add("parser", "DSParser")
                .link("Source", "parser")
                .link("parser", "gone");

        assertThat(ids(PipelineFacts.inFlowOrder(elements.byId, elements.links, Map.of())))
                .containsExactly("parser");
    }

    @Test
    void testNoElements() {
        final PipelineFacts facts = PipelineFacts.of(null, List.of(new PipelineLayer(PARSER,
                new PipelineDataBuilder().build())));

        assertThat(facts.elements()).isEmpty();
        assertThat(facts.elementCount()).isZero();
    }

    // The merged pipeline: the template's elements and links, then the child's, named as the user named
    // them, without the merger's Source
    @Test
    void testElementsAreMergedFromTheParentAndNamed() {
        final PipelineLayer template = new PipelineLayer(TEMPLATE, new PipelineDataBuilder()
                .addElement(new PipelineElement("parser", "DSParser"))
                .addElement(new PipelineElement("translation", "XSLTFilter", "Translation", null))
                .addLink("parser", "translation")
                .build());
        final PipelineLayer parser = new PipelineLayer(PARSER, new PipelineDataBuilder()
                .addElement(new PipelineElement("appender", "StreamAppender"))
                .addLink("translation", "appender")
                .build());

        final PipelineFacts facts = PipelineFacts.of(TEMPLATE, List.of(template, parser));

        // The template's elements are inherited from it; the child's own appender is not
        assertThat(facts.elements()).containsExactly(
                new GraphElement("parser", null, "DSParser", TEMPLATE),
                new GraphElement("translation", "Translation", "XSLTFilter", TEMPLATE),
                new GraphElement("appender", null, "StreamAppender", null));
        assertThat(facts.elementCount()).isEqualTo(3);
        assertThat(facts.elements().get(1).getDisplayName()).isEqualTo("Translation");
        assertThat(facts.elements().get(0).getDisplayName()).isEqualTo("parser");
    }

    // Each document an element names, through a property or a pipeline reference, is said to be used by
    // that element; one named by two elements, by both, in the order data flows through them
    @Test
    void testTheElementsUsingEachDocument() {
        final PipelineLayer pipeline = new PipelineLayer(PARSER, new PipelineDataBuilder()
                .addElement(new PipelineElement("parser", "DSParser"))
                .addElement(new PipelineElement("translation", "XSLTFilter"))
                .addElement(new PipelineElement("decoration", "XSLTFilter"))
                .addElement(new PipelineElement("appender", "StreamAppender"))
                .addLink("parser", "translation")
                .addLink("translation", "decoration")
                .addLink("decoration", "appender")
                .addProperty(property("translation", "xslt", XSLT))
                .addProperty(property("decoration", "xslt", XSLT))
                .addProperty(property("appender", "feed", EVENTS))
                .addPipelineReference(new PipelineReference.Builder()
                        .element("decoration")
                        .name("pipelineReference")
                        .pipeline(LOADER)
                        .feed(LOOKUP)
                        .build())
                .build());

        final PipelineFacts facts = PipelineFacts.of(null, List.of(pipeline));

        assertThat(facts.elementsUsing(XSLT.getUuid())).containsExactly("translation", "decoration");
        assertThat(facts.elementsUsing(EVENTS.getUuid())).containsExactly("appender");
        assertThat(facts.elementsUsing(LOOKUP.getUuid())).containsExactly("decoration");
        assertThat(facts.elementsUsing(LOADER.getUuid())).containsExactly("decoration");
        assertThat(facts.elementsUsing("nothing")).isEmpty();
        assertThat(facts.elements()).extracting(GraphElement::getInheritedFrom).containsOnlyNulls();
    }

    // A document named by an inherited element is used by it, so a child's reference comes from the
    // element its parent defines
    @Test
    void testAnInheritedElementUsesWhatItNames() {
        final PipelineLayer template = new PipelineLayer(TEMPLATE, new PipelineDataBuilder()
                .addElement(new PipelineElement("translation", "XSLTFilter"))
                .build());
        final PipelineLayer parser = new PipelineLayer(PARSER, new PipelineDataBuilder()
                .addProperty(property("translation", "xslt", XSLT))
                .build());

        final PipelineFacts facts = PipelineFacts.of(TEMPLATE, List.of(template, parser));

        assertThat(facts.elementsUsing(XSLT.getUuid())).containsExactly("translation");
        assertThat(facts.elements().getFirst().getInheritedFrom()).isEqualTo(TEMPLATE);
    }

    private static PipelineProperty property(final String element, final String name, final DocRef value) {
        return PipelineProperty.builder()
                .element(element)
                .name(name)
                .value(new PipelinePropertyValue(value))
                .build();
    }

    private static List<String> ids(final List<GraphElement> elements) {
        return elements.stream().map(GraphElement::getId).toList();
    }

    // A pipeline's merged elements and links, as PipelineDataMerger gives them
    private static final class Elements {

        private final Map<String, PipelineElement> byId = new HashMap<>();
        private final Map<String, List<PipelineLink>> links = new HashMap<>();

        private Elements add(final String id, final String type) {
            byId.put(id, new PipelineElement(id, type));
            return this;
        }

        private Elements link(final String from, final String to) {
            links.computeIfAbsent(from, k -> new ArrayList<>()).add(new PipelineLink(from, to));
            return this;
        }
    }
}
