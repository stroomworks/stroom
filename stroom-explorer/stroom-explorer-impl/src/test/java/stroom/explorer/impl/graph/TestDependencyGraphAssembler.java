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
import stroom.dependency.shared.EdgeCertainty;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;
import stroom.docstore.api.DependencyEdge;
import stroom.explorer.shared.ExplorerConstants;
import stroom.util.json.JsonUtil;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestDependencyGraphAssembler {

    private static final DocRef FOLDER = new DocRef(ExplorerConstants.FOLDER_TYPE, "folder", "Events");

    private static final DocRef RAW = new DocRef("Feed", "raw", "FW:RAW_EVENTS");
    private static final DocRef EVENTS = new DocRef("Feed", "events", "FW:EVENTS");
    private static final DocRef PARSER = new DocRef("Pipeline", "parser", "FW Parser");
    private static final DocRef TEMPLATE = new DocRef("Pipeline", "template", "Parser Template");
    private static final DocRef XSLT = new DocRef("XSLT", "xslt", "FW Translation");
    private static final DocRef DICTIONARY = new DocRef("Dictionary", "dict", "Bad Hosts");
    private static final DocRef INDEX = new DocRef("Index", "index", "Events Index");
    private static final DocRef DASHBOARD = new DocRef("Dashboard", "dash", "Overview");
    private static final DocRef SUB_FOLDER = new DocRef(ExplorerConstants.FOLDER_TYPE, "sub", "Sub");
    private static final DocRef SECRET = new DocRef("XSLT", "secret", "Secret Translation");

    @Test
    void testProcessorFilterBecomesFlowFromFeedToPipeline() {
        final FakeSource source = new FakeSource()
                .holding(RAW, PARSER, EVENTS)
                .filter("filter-1", PARSER, RAW)
                .edge(PARSER, EVENTS)
                .facts(PARSER, outputs(EVENTS));

        final DependencyGraph graph = assemble(source);

        assertThat(edges(graph))
                .containsExactlyInAnyOrder(
                        tuple("raw", "inst:parser:raw:events", EdgeKind.FLOW),
                        tuple("inst:parser:raw:events", "events", EdgeKind.FLOW));
        // The filter itself is not a node
        assertThat(ids(graph)).containsExactly("raw", "inst:parser:raw:events", "events");
    }

    @Test
    void testFilterOnAHiddenFeedDrawsNoFlow() {
        // The user can see the pipeline but not the feed its filter reads
        final FakeSource source = new FakeSource()
                .holding(RAW, PARSER)
                .hidden(RAW)
                .filter("filter-1", PARSER, RAW)
                .facts(PARSER, outputs());

        final DependencyGraph graph = assemble(source);

        assertThat(ids(graph)).containsExactly("parser");
        assertThat(graph.getEdges()).isEmpty();
    }

    @Test
    void testPipelineWithAnInputAndNoOutputIsAnInstanceToNowhere() {
        final FakeSource source = new FakeSource()
                .holding(RAW, PARSER)
                .filter("filter-1", PARSER, RAW)
                .facts(PARSER, outputs());

        assertThat(edges(assemble(source)))
                .containsExactly(tuple("raw", "inst:parser:raw:", EdgeKind.FLOW));
    }

    @Test
    void testFilterFeedsAnAnalyticRule() {
        // A streaming analytic's filter names the rule rather than a pipeline
        final DocRef rule = new DocRef("AnalyticRule", "rule", "Bad Logins");
        final FakeSource source = new FakeSource()
                .holding(RAW, rule)
                .filter("filter-1", rule, RAW);

        final DependencyGraph graph = assemble(source);

        assertThat(edges(graph)).containsExactly(tuple("raw", "rule", EdgeKind.FLOW));
        assertThat(node(graph, "rule").getRole()).isEqualTo(NodeRole.FLOW);
    }

    @Test
    void testEdgesBetweenContextDocumentsAreDrawn() {
        // The folder holds the parser; its translation and its dictionary are both outside it, one hop
        // away, and the translation reads the dictionary
        final FakeSource source = new FakeSource()
                .holding(PARSER)
                .edge(PARSER, XSLT)
                .edge(PARSER, DICTIONARY)
                .edge(XSLT, DICTIONARY)
                .facts(PARSER, outputs());

        assertThat(edges(assemble(source)))
                .contains(tuple("xslt", "dict", EdgeKind.REFERENCE));
    }

    @Test
    void testPipelineIsDrawnOncePerSourceAndDestination() {
        final DocRef other = new DocRef("Feed", "other", "IDS:RAW_EVENTS");
        final FakeSource source = new FakeSource()
                .holding(RAW, other, PARSER, EVENTS, XSLT)
                .filter("filter-1", PARSER, RAW, other)
                .edge(PARSER, EVENTS)
                .edge(PARSER, XSLT)
                .facts(PARSER, outputs(EVENTS));

        final DependencyGraph graph = assemble(source);

        assertThat(ids(graph)).contains("inst:parser:raw:events", "inst:parser:other:events");
        assertThat(ids(graph)).doesNotContain("parser");
        // Every instance uses the XSLT
        assertThat(edges(graph))
                .contains(tuple("inst:parser:raw:events", "xslt", EdgeKind.REFERENCE),
                        tuple("inst:parser:other:events", "xslt", EdgeKind.REFERENCE));
        // Each instance carries the pipeline's document, for acting on
        assertThat(node(graph, "inst:parser:raw:events").getDocRef()).isEqualTo(PARSER);
    }

    @Test
    void testPipelineToFeedIsOutputOrReferenceLoaderInput() {
        final DocRef lookupFeed = new DocRef("Feed", "lookup", "HOSTS:REFERENCE");
        final FakeSource source = new FakeSource()
                .holding(PARSER, EVENTS, lookupFeed)
                .edge(PARSER, EVENTS)
                .edge(PARSER, lookupFeed)
                .facts(PARSER, new PipelineFacts(null, Set.of("events"), 6));

        final DependencyGraph graph = assemble(source);

        assertThat(edgesWithCertainty(graph))
                .containsExactlyInAnyOrder(
                        tuple("inst:parser::events", "events", EdgeKind.FLOW, EdgeCertainty.CONFIGURED),
                        tuple("inst:parser::events", "lookup", EdgeKind.REFERENCE, EdgeCertainty.CONFIGURED));
    }

    @Test
    void testPipelineToFeedIsInferredWhenThePipelineCannotBeRead() {
        final FakeSource source = new FakeSource()
                .holding(PARSER, EVENTS)
                .edge(PARSER, EVENTS);

        assertThat(edgesWithCertainty(assemble(source)))
                .containsExactly(tuple("parser", "events", EdgeKind.REFERENCE, EdgeCertainty.INFERRED));
    }

    @Test
    void testParentPipelineIsInheritanceDrawnParentToChild() {
        final DocRef loader = new DocRef("Pipeline", "loader", "Reference Loader");
        final FakeSource source = new FakeSource()
                .holding(TEMPLATE, PARSER, loader)
                .edge(PARSER, TEMPLATE)
                .edge(PARSER, loader)
                .facts(PARSER, new PipelineFacts(TEMPLATE, Set.of(), 6));

        assertThat(edges(assemble(source)))
                .containsExactlyInAnyOrder(
                        tuple("template", "parser", EdgeKind.INHERITANCE),
                        tuple("parser", "loader", EdgeKind.REFERENCE));
    }

    @Test
    void testPipelineToPipelineIsInferredWhenThePipelineCannotBeRead() {
        final FakeSource source = new FakeSource()
                .holding(TEMPLATE, PARSER)
                .edge(PARSER, TEMPLATE);

        assertThat(edgesWithCertainty(assemble(source)))
                .containsExactly(tuple("parser", "template", EdgeKind.REFERENCE, EdgeCertainty.INFERRED));
    }

    @Test
    void testOnlyKeptNodesAreRead() {
        // Ten feeds, of which the ceiling keeps two: only their stream types are read
        final List<DocRef> feeds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            feeds.add(new DocRef("Feed", "f" + i, "FEED_" + i));
        }
        final FakeSource source = new FakeSource().holding(feeds.toArray(new DocRef[0]));

        DependencyGraphAssembler.assemble(FOLDER, 1, 2, source);

        assertThat(source.streamTypeReads).containsExactly("f0", "f1");
    }

    @Test
    void testInheritedOutputIsFlow() {
        // The child records no edge to the feed its parent's appender writes to
        final FakeSource source = new FakeSource()
                .holding(RAW, PARSER, EVENTS)
                .filter("filter-1", PARSER, RAW)
                .facts(PARSER, new PipelineFacts(TEMPLATE, Set.of("events"), 6));

        assertThat(edges(assemble(source)))
                .containsExactlyInAnyOrder(
                        tuple("raw", "inst:parser:raw:events", EdgeKind.FLOW),
                        tuple("inst:parser:raw:events", "events", EdgeKind.FLOW));
    }

    @Test
    void testDashboardEdgesPointTheWayTheDataGoes() {
        final FakeSource source = new FakeSource()
                .holding(INDEX, DASHBOARD)
                .edge(DASHBOARD, INDEX);

        assertThat(edgesWithCertainty(assemble(source)))
                .containsExactly(tuple("index", "dash", EdgeKind.QUERY, EdgeCertainty.STATIC));
    }

    @Test
    void testIndexingPipelineWritesToItsIndex() {
        final FakeSource source = new FakeSource()
                .holding(EVENTS, PARSER, INDEX)
                .filter("filter-1", PARSER, EVENTS)
                .edge(PARSER, INDEX)
                .facts(PARSER, outputs(INDEX));

        assertThat(edges(assemble(source)))
                .contains(tuple("inst:parser:events:index", "index", EdgeKind.FLOW));
    }

    @Test
    void testReferencesBetweenDocumentsAreStatic() {
        final FakeSource source = new FakeSource()
                .holding(PARSER, XSLT, DICTIONARY)
                .edge(PARSER, XSLT)
                .edge(XSLT, DICTIONARY)
                .facts(PARSER, outputs());

        assertThat(edgesWithCertainty(assemble(source)))
                .containsExactlyInAnyOrder(
                        tuple("parser", "xslt", EdgeKind.REFERENCE, EdgeCertainty.STATIC),
                        tuple("xslt", "dict", EdgeKind.REFERENCE, EdgeCertainty.STATIC));
    }

    @Test
    void testRolesAndStreamTypes() {
        final FakeSource source = new FakeSource()
                .holding(RAW, XSLT, DICTIONARY)
                .streamType(RAW, "Raw Events");

        final DependencyGraph graph = assemble(source);

        assertThat(node(graph, "raw").getRole()).isEqualTo(NodeRole.FLOW);
        assertThat(node(graph, "raw").getStreamType()).isEqualTo("Raw Events");
        assertThat(node(graph, "xslt").getRole()).isEqualTo(NodeRole.REFERENCE);
        assertThat(node(graph, "dict").getRole()).isEqualTo(NodeRole.FLOW);
        assertThat(node(graph, "xslt").getStreamType()).isNull();
    }

    @Test
    void testFoldersAreLeftOut() {
        final FakeSource source = new FakeSource().holding(SUB_FOLDER, RAW);

        assertThat(ids(assemble(source))).containsExactly("raw");
    }

    @Test
    void testUnviewableDocumentIsAbsentFromTheResponse() {
        // The parser uses a translation the user cannot see: it must leave no trace at all
        final FakeSource source = new FakeSource()
                .holding(PARSER, SECRET, XSLT)
                .hidden(SECRET)
                .edge(PARSER, SECRET)
                .edge(PARSER, XSLT)
                .facts(PARSER, outputs());

        final DependencyGraph graph = assemble(source);
        final String json = JsonUtil.getMapper().writeValueAsString(graph);

        assertThat(json).doesNotContain(SECRET.getUuid(), SECRET.getName());
        assertThat(ids(graph)).containsExactly("parser", "xslt");
        assertThat(node(graph, "parser").getUnresolvedRefs())
                .describedAs("an unviewable document exists, so is not a broken reference")
                .isNull();
    }

    @Test
    void testUnviewableContextIsAbsent() {
        // A dashboard outside the folder that the user cannot see reads the folder's index
        final FakeSource source = new FakeSource()
                .holding(INDEX)
                .hidden(DASHBOARD)
                .edge(DASHBOARD, INDEX);

        final DependencyGraph graph = assemble(source);

        assertThat(ids(graph)).containsExactly("index");
        assertThat(graph.getEdges()).isEmpty();
        assertThat(JsonUtil.getMapper().writeValueAsString(graph)).doesNotContain(DASHBOARD.getUuid());
    }

    @Test
    void testAmbiguityShowsWhenTheOtherCandidateIsHidden() {
        // The XSLT imports a name two XSLTs have; the user can see only one of them
        final DocRef common1 = new DocRef("XSLT", "common1", "Common");
        final DocRef common2 = new DocRef("XSLT", "common2", "Common");
        final FakeSource source = new FakeSource()
                .holding(XSLT, common1, common2)
                .hidden(common2)
                .ambiguousEdge(XSLT, common1)
                .ambiguousEdge(XSLT, common2);

        final DependencyGraph graph = assemble(source);

        assertThat(graph.getEdges())
                .extracting(GraphEdge::getTo, GraphEdge::isAmbiguous)
                .containsExactly(tuple("common1", true));
        assertThat(JsonUtil.getMapper().writeValueAsString(graph)).doesNotContain("common2");
    }

    @Test
    void testMissingTargetsAreCountedNotDrawn() {
        final DocRef gone = new DocRef("XSLT", "gone", "Deleted Translation");
        final DocRef searchable = new DocRef("Searchable", "meta", "Meta Store");
        final FakeSource source = new FakeSource()
                .holding(PARSER)
                .missingEdge(PARSER, gone)
                // Not a document type, so not a broken reference
                .missingEdge(PARSER, searchable)
                .facts(PARSER, outputs());

        final DependencyGraph graph = assemble(source);

        assertThat(ids(graph)).containsExactly("parser");
        assertThat(node(graph, "parser").getUnresolvedRefs()).isEqualTo(1);
    }

    @Test
    void testContextIsOneHopByDefault() {
        // The folder holds the parser; the feeds either side and the XSLT are outside it
        final FakeSource source = new FakeSource()
                .holding(PARSER)
                .filter("filter-1", PARSER, RAW)
                .edge(PARSER, EVENTS)
                .edge(PARSER, XSLT)
                .edge(XSLT, DICTIONARY)
                .facts(PARSER, outputs(EVENTS));

        final DependencyGraph graph = assemble(source);

        assertThat(graph.getNodes())
                .extracting(GraphNode::getId, GraphNode::isInScope, GraphNode::getHop)
                .containsExactlyInAnyOrder(
                        tuple("inst:parser:raw:events", true, null),
                        tuple("raw", false, 1),
                        tuple("events", false, 1),
                        tuple("xslt", false, 1));
        // Two hops away
        assertThat(ids(graph)).doesNotContain("dict");

        final DependencyGraph twoHops = DependencyGraphAssembler.assemble(FOLDER, 2, 100, source);
        assertThat(node(twoHops, "dict").getHop()).isEqualTo(2);

        final DependencyGraph noContext = DependencyGraphAssembler.assemble(FOLDER, 0, 100, source);
        assertThat(ids(noContext)).containsExactly("parser");
    }

    @Test
    void testNodeCeilingKeepsTheFolderFirst() {
        final List<DocRef> contents = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            contents.add(new DocRef("XSLT", "x" + i, "Translation " + i));
        }
        final FakeSource source = new FakeSource()
                .holding(contents.toArray(new DocRef[0]))
                .edge(contents.get(0), DICTIONARY);

        final DependencyGraph graph = DependencyGraphAssembler.assemble(FOLDER, 1, 5, source);

        assertThat(graph.isNodeLimitReached()).isTrue();
        assertThat(ids(graph)).containsExactly("x0", "x1", "x2", "x3", "x4");
        // The edge to the dictionary, which was cut, is gone too
        assertThat(graph.getEdges()).isEmpty();
        assertThat(assemble(source).isNodeLimitReached()).isFalse();
    }

    @Test
    void testGraphCarriesRootAndHash() {
        final FakeSource source = new FakeSource().holding(RAW);

        final DependencyGraph first = assemble(source);
        final DependencyGraph second = assemble(source);

        assertThat(first.getRoot()).isEqualTo(FOLDER);
        assertThat(first.getTopologyHash()).isEqualTo(second.getTopologyHash());
        assertThat(first.getGeneratedAt()).isNotNull();
    }

    private static DependencyGraph assemble(final FakeSource source) {
        return DependencyGraphAssembler.assemble(FOLDER, 1, 100, source);
    }

    private static PipelineFacts outputs(final DocRef... outputs) {
        final Set<String> uuids = new HashSet<>();
        for (final DocRef output : outputs) {
            uuids.add(output.getUuid());
        }
        return new PipelineFacts(null, uuids, 6);
    }

    private static List<String> ids(final DependencyGraph graph) {
        return graph.getNodes().stream().map(GraphNode::getId).toList();
    }

    private static GraphNode node(final DependencyGraph graph, final String id) {
        return graph.getNodes().stream().filter(node -> node.getId().equals(id)).findFirst().orElseThrow();
    }

    private static List<Tuple> edges(final DependencyGraph graph) {
        return graph.getEdges().stream()
                .map(edge -> tuple(edge.getFrom(), edge.getTo(), edge.getKind()))
                .toList();
    }

    private static List<Tuple> edgesWithCertainty(final DependencyGraph graph) {
        return graph.getEdges().stream()
                .map(edge -> tuple(edge.getFrom(), edge.getTo(), edge.getKind(), edge.getCertainty()))
                .toList();
    }

    // A folder's contents and recorded edges, held in memory.
    private static final class FakeSource implements DependencyGraphAssembler.GraphSource {

        private final List<DocRef> contents = new ArrayList<>();
        private final Set<String> hidden = new HashSet<>();
        private final List<DependencyEdge> edges = new ArrayList<>();
        private final Map<String, PipelineFacts> facts = new HashMap<>();
        private final Map<String, String> streamTypes = new HashMap<>();
        private final List<String> streamTypeReads = new ArrayList<>();

        FakeSource holding(final DocRef... docRefs) {
            contents.addAll(List.of(docRefs));
            return this;
        }

        FakeSource hidden(final DocRef docRef) {
            hidden.add(docRef.getUuid());
            return this;
        }

        FakeSource edge(final DocRef from, final DocRef to) {
            edges.add(new DependencyEdge(from, to, false, true));
            return this;
        }

        FakeSource ambiguousEdge(final DocRef from, final DocRef to) {
            edges.add(new DependencyEdge(from, to, true, true));
            return this;
        }

        FakeSource missingEdge(final DocRef from, final DocRef to) {
            edges.add(new DependencyEdge(from, to, false, false));
            return this;
        }

        FakeSource filter(final String uuid, final DocRef pipeline, final DocRef... feeds) {
            final DocRef filter = new DocRef("ProcessorFilter", uuid);
            edges.add(new DependencyEdge(filter, pipeline, false, true));
            for (final DocRef feed : feeds) {
                edges.add(new DependencyEdge(filter, feed, false, true));
            }
            return this;
        }

        FakeSource facts(final DocRef pipeline, final PipelineFacts pipelineFacts) {
            facts.put(pipeline.getUuid(), pipelineFacts);
            return this;
        }

        FakeSource streamType(final DocRef feed, final String streamType) {
            streamTypes.put(feed.getUuid(), streamType);
            return this;
        }

        @Override
        public List<DocRef> contents(final DocRef folder) {
            return contents;
        }

        @Override
        public boolean canView(final DocRef docRef) {
            return !hidden.contains(docRef.getUuid());
        }

        @Override
        public boolean isDocumentType(final String type) {
            return !"Searchable".equals(type);
        }

        @Override
        public List<DependencyEdge> edgesFrom(final Set<String> uuids) {
            return edges.stream().filter(edge -> uuids.contains(edge.from().getUuid())).toList();
        }

        @Override
        public List<DependencyEdge> edgesTo(final Set<String> uuids) {
            return edges.stream().filter(edge -> uuids.contains(edge.to().getUuid())).toList();
        }

        @Override
        public PipelineFacts pipelineFacts(final DocRef pipeline) {
            return facts.get(pipeline.getUuid());
        }

        @Override
        public String streamType(final DocRef feed) {
            streamTypeReads.add(feed.getUuid());
            return streamTypes.get(feed.getUuid());
        }
    }
}
