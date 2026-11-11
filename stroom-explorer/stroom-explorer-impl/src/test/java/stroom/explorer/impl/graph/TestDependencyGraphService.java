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

import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.DependencyGraphRequest;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.docref.DocRef;
import stroom.docstore.api.DependencyEdge;
import stroom.docstore.api.DocDependencyService;
import stroom.docstore.api.DocumentStore;
import stroom.docstore.api.DocumentStoreRegistry;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.feed.shared.FeedDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.pipeline.shared.data.PipelineReference;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDependencyGraphService {

    private static final DocRef FOLDER = new DocRef(ExplorerConstants.FOLDER_TYPE, "folder", "Events");
    private static final DocRef RAW = new DocRef(FeedDoc.TYPE, "raw", "FW:RAW_EVENTS");
    private static final DocRef EVENTS = new DocRef(FeedDoc.TYPE, "events", "FW:EVENTS");
    private static final DocRef LOOKUP = new DocRef(FeedDoc.TYPE, "lookup", "HOSTS:REFERENCE");
    private static final DocRef PARSER = new DocRef(PipelineDoc.TYPE, "parser", "FW Parser");
    private static final DocRef TEMPLATE = new DocRef(PipelineDoc.TYPE, "template", "Parser Template");

    private final SecurityContext securityContext = mock(SecurityContext.class);
    private final DocDependencyService docDependencyService = mock(DocDependencyService.class);
    private final ExplorerNodeService explorerNodeService = mock(ExplorerNodeService.class);
    private final DocumentStoreRegistry documentStoreRegistry = mock(DocumentStoreRegistry.class);
    private final Map<String, Object> documents = new HashMap<>();
    private final Set<String> viewable = new HashSet<>();
    private final List<DependencyEdge> edges = new ArrayList<>();
    private final List<String> reads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(securityContext.hasDocumentPermission(any(DocRef.class), eq(DocumentPermission.VIEW)))
                .thenAnswer(invocation -> viewable.contains(invocation.<DocRef>getArgument(0).getUuid()));
        when(docDependencyService.getEdgesFrom(ArgumentMatchers.anySet())).thenAnswer(invocation -> {
            final Set<String> uuids = invocation.getArgument(0);
            return edges.stream().filter(edge -> uuids.contains(edge.from().getUuid())).toList();
        });
        when(docDependencyService.getEdgesTo(ArgumentMatchers.anySet())).thenAnswer(invocation -> {
            final Set<String> uuids = invocation.getArgument(0);
            return edges.stream().filter(edge -> uuids.contains(edge.to().getUuid())).toList();
        });
        for (final String type : List.of(FeedDoc.TYPE, PipelineDoc.TYPE)) {
            final DocumentStore<?> store = mock(DocumentStore.class);
            when(store.readDocument(any(DocRef.class))).thenAnswer(invocation -> {
                final DocRef docRef = invocation.getArgument(0);
                reads.add(docRef.getUuid());
                // As a document store does: the user must be able to view it
                if (!viewable.contains(docRef.getUuid())) {
                    throw new PermissionException(null, "Not allowed");
                }
                return documents.get(docRef.getUuid());
            });
            when(documentStoreRegistry.getDocumentStore(type)).thenAnswer(invocation -> store);
        }
        when(documentStoreRegistry.getDocumentStore(ArgumentMatchers.argThat(type ->
                !FeedDoc.TYPE.equals(type) && !PipelineDoc.TYPE.equals(type))))
                .thenThrow(new RuntimeException("No document store"));
        viewable.add(FOLDER.getUuid());
    }

    @Test
    void testFolderTheUserCannotViewIsRefused() {
        viewable.remove(FOLDER.getUuid());

        assertThatThrownBy(() -> service().fetchGraph(new DependencyGraphRequest(FOLDER, null, null)))
                .isInstanceOf(PermissionException.class);
    }

    @Test
    void testPermissionCheckThatThrowsLeavesTheDocumentOut() {
        contents(RAW, EVENTS);
        viewable.add(RAW.getUuid());
        when(securityContext.hasDocumentPermission(EVENTS, DocumentPermission.VIEW))
                .thenThrow(new RuntimeException("Unknown type"));

        final DependencyGraph graph = service().fetchGraph(new DependencyGraphRequest(FOLDER, null, null));

        assertThat(graph.getNodes()).extracting(GraphNode::getId).containsExactly("raw");
    }

    @Test
    void testPipelineConfigurationClassifiesItsEdges() {
        // The parser inherits its appender from its template, and reads a reference feed itself
        contents(RAW, PARSER, EVENTS, LOOKUP, TEMPLATE);
        viewable.addAll(Set.of("raw", "parser", "events", "lookup", "template"));
        documents.put(TEMPLATE.getUuid(), appenderTemplate());
        documents.put(PARSER.getUuid(), PipelineDoc.builder()
                .uuid(PARSER.getUuid())
                .name(PARSER.getName())
                .parentPipeline(TEMPLATE)
                .pipelineData(new PipelineDataBuilder()
                        .addElement(new PipelineElement("translation", "XSLTFilter"))
                        .addPipelineReference(new PipelineReference.Builder()
                                .element("translation")
                                .name("pipelineReference")
                                .feed(LOOKUP)
                                .build())
                        .build())
                .build());
        documents.put(RAW.getUuid(), FeedDoc.builder().uuid(RAW.getUuid()).name(RAW.getName())
                .streamType("Raw Events").build());
        final DocRef filter = new DocRef("ProcessorFilter", "filter-1");
        edges.add(new DependencyEdge(filter, PARSER, false, true));
        edges.add(new DependencyEdge(filter, RAW, false, true));
        edges.add(new DependencyEdge(PARSER, TEMPLATE, false, true));
        edges.add(new DependencyEdge(PARSER, LOOKUP, false, true));

        final DependencyGraph graph = service().fetchGraph(new DependencyGraphRequest(FOLDER, null, null));

        assertThat(graph.getEdges())
                .extracting(GraphEdge::getFrom, GraphEdge::getTo, GraphEdge::getKind)
                .containsExactlyInAnyOrder(
                        tuple("raw", "inst:parser:raw:events", EdgeKind.FLOW),
                        tuple("inst:parser:raw:events", "events", EdgeKind.FLOW),
                        tuple("inst:parser:raw:events", "lookup", EdgeKind.REFERENCE),
                        // The template is configured to write to the events feed too, so is drawn doing
                        // so: the graph shows configured flow (decision 2), not observed
                        tuple("inst:template::events", "events", EdgeKind.FLOW),
                        tuple("inst:template::events", "inst:parser:raw:events", EdgeKind.INHERITANCE));
        final GraphNode raw = graph.getNodes().stream().filter(node -> node.getId().equals("raw")).findFirst()
                .orElseThrow();
        assertThat(raw.getStreamType()).isEqualTo("Raw Events");
        final GraphNode parser = graph.getNodes().stream()
                .filter(node -> node.getId().equals("inst:parser:raw:events"))
                .findFirst()
                .orElseThrow();
        // The template's appender and the parser's own translation
        assertThat(parser.getElementCount()).isEqualTo(2);
    }

    @Test
    void testUnviewableParentLeavesOnlyThePipelinesOwnConfiguration() {
        // The template writes to the events feed, but the user cannot view the template, so what it
        // configures must not reach the graph
        contents(PARSER, EVENTS);
        viewable.addAll(Set.of("parser", "events"));
        documents.put(TEMPLATE.getUuid(), appenderTemplate());
        documents.put(PARSER.getUuid(), PipelineDoc.builder()
                .uuid(PARSER.getUuid())
                .name(PARSER.getName())
                .parentPipeline(TEMPLATE)
                .pipelineData(new PipelineDataBuilder()
                        .addElement(new PipelineElement("translation", "XSLTFilter"))
                        .build())
                .build());

        final DependencyGraph graph = service().fetchGraph(new DependencyGraphRequest(FOLDER, null, null));

        assertThat(graph.getEdges()).isEmpty();
        assertThat(graph.getNodes())
                .extracting(GraphNode::getId, GraphNode::getElementCount)
                .contains(tuple("parser", 1));

        // Once the template can be viewed, its appender is the parser's output
        viewable.add(TEMPLATE.getUuid());
        assertThat(service().fetchGraph(new DependencyGraphRequest(FOLDER, null, null)).getEdges())
                .extracting(GraphEdge::getFrom, GraphEdge::getTo, GraphEdge::getKind)
                .contains(tuple("inst:parser::events", "events", EdgeKind.FLOW));
    }

    @Test
    void testSharedParentIsReadOncePerRequest() {
        final DocRef other = new DocRef(PipelineDoc.TYPE, "other", "IDS Parser");
        contents(PARSER, other, TEMPLATE);
        viewable.addAll(Set.of("parser", "other", "template"));
        documents.put(TEMPLATE.getUuid(), appenderTemplate());
        for (final DocRef child : List.of(PARSER, other)) {
            documents.put(child.getUuid(), PipelineDoc.builder()
                    .uuid(child.getUuid())
                    .name(child.getName())
                    .parentPipeline(TEMPLATE)
                    .pipelineData(new PipelineDataBuilder().build())
                    .build());
        }

        service().fetchGraph(new DependencyGraphRequest(FOLDER, null, null));

        assertThat(reads.stream().filter(TEMPLATE.getUuid()::equals).count()).isOne();
    }

    @Test
    void testRequestIsClamped() {
        assertThat(DependencyGraphService.clamp(null, 1, 0, 2)).isEqualTo(1);
        assertThat(DependencyGraphService.clamp(9, 1, 0, 2)).isEqualTo(2);
        assertThat(DependencyGraphService.clamp(-3, 1, 0, 2)).isZero();
        assertThat(DependencyGraphService.clamp(100_000, DependencyGraphService.DEFAULT_MAX_NODES, 1,
                DependencyGraphService.NODE_CEILING)).isEqualTo(DependencyGraphService.NODE_CEILING);
    }

    @Test
    void testSystemRootNeedsNoPermissionOfItsOwn() {
        when(explorerNodeService.getDescendants(ExplorerConstants.SYSTEM_DOC_REF)).thenReturn(List.of());

        final DependencyGraph graph = service().fetchGraph(
                new DependencyGraphRequest(ExplorerConstants.SYSTEM_DOC_REF, null, null));

        assertThat(graph.getNodes()).isEmpty();
    }

    // A template pipeline whose stream appender writes to the events feed
    private static PipelineDoc appenderTemplate() {
        return PipelineDoc.builder()
                .uuid(TEMPLATE.getUuid())
                .name(TEMPLATE.getName())
                .pipelineData(new PipelineDataBuilder()
                        .addElement(new PipelineElement("appender", "StreamAppender"))
                        .addProperty(PipelineProperty.builder()
                                .element("appender")
                                .name("feed")
                                .value(new PipelinePropertyValue(EVENTS))
                                .build())
                        .build())
                .build();
    }

    private void contents(final DocRef... docRefs) {
        final List<ExplorerNode> nodes = new ArrayList<>();
        nodes.add(ExplorerNode.builder().docRef(FOLDER).build());
        for (final DocRef docRef : docRefs) {
            nodes.add(ExplorerNode.builder().docRef(docRef).build());
        }
        when(explorerNodeService.getDescendants(FOLDER)).thenReturn(nodes);
    }

    private DependencyGraphService service() {
        return new DependencyGraphService(() -> docDependencyService,
                () -> explorerNodeService,
                () -> documentStoreRegistry,
                securityContext);
    }
}
