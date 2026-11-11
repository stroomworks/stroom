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
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.importexport.api.ImportExportSerializer;
import stroom.importexport.shared.ImportSettings;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.common.StroomCoreServerTestFileUtil;
import stroom.util.json.JsonUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// The Structure view's graph, built by the real service from Stroom's sample content: the folders the
/// translation tests import, with their feeds, pipelines, processor filters, translations, dashboards
/// and indexes.
class TestDependencyGraphOnSampleContent extends AbstractCoreIntegrationTest {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestDependencyGraphOnSampleContent.class);

    @Inject
    private ImportExportSerializer importExportSerializer;
    @Inject
    private ExplorerNodeService explorerNodeService;
    @Inject
    private DependencyGraphService dependencyGraphService;

    @BeforeEach
    void importSampleContent() {
        final Path configDir = StroomCoreServerTestFileUtil.getTestResourcesDir()
                .resolve("samples")
                .resolve("config");
        importExportSerializer.read(configDir, null, ImportSettings.auto());
    }

    @Test
    void testEveryFolderGivesAWellFormedGraph() {
        final ExplorerNode root = explorerNodeService.getRoot();
        final List<ExplorerNode> folders = explorerNodeService.getChildren(root.getDocRef()).stream()
                .filter(node -> ExplorerConstants.isFolder(node.getDocRef()))
                .toList();
        assertThat(folders).isNotEmpty();

        for (final ExplorerNode folder : folders) {
            assertWellFormed(fetch(folder.getDocRef()));
        }
        assertWellFormed(fetch(ExplorerConstants.SYSTEM_DOC_REF));
    }

    @Test
    void testAppenderPipelineFlowsToItsOutputFeed() {
        final DependencyGraph graph = fetch(folder("HDFS File Appender"));

        // The HDFS pipeline's stream appender writes HDFS_OUTPUT; nothing processes HDFS_INPUT into it
        assertThat(graph.getEdges())
                .extracting(edge -> name(graph, edge.getFrom()), edge -> name(graph, edge.getTo()), GraphEdge::getKind)
                .containsExactly(tuple("HDFS", "HDFS_OUTPUT", EdgeKind.FLOW));
        assertThat(graph.getNodes())
                .extracting(node -> node.getDocRef().getName())
                .containsExactlyInAnyOrder("HDFS", "HDFS_INPUT", "HDFS_OUTPUT");
        assertThat(graph.getNodes()).allSatisfy(node -> assertThat(node.isInScope()).isTrue());
    }

    @Test
    void testPipelineReferencesItsTranslations() {
        final DependencyGraph graph = fetch(folder("Import Export Test"));

        final Set<String> pipelineIds = graph.getNodes().stream()
                .filter(node -> "Imp_exp_test_pipeline".equals(node.getDocRef().getName()))
                .map(GraphNode::getId)
                .collect(Collectors.toSet());
        assertThat(pipelineIds).isNotEmpty();
        assertThat(graph.getEdges())
                .filteredOn(edge -> pipelineIds.contains(edge.getFrom()) && edge.getKind() == EdgeKind.REFERENCE)
                .extracting(edge -> name(graph, edge.getTo()))
                .contains("Imp_exp_test_XSLT", "Imp_exp_test_text_converter");
    }

    @Test
    void testGraphSurvivesTheWire() {
        // As the REST resource sends it, and the client reads it
        final DependencyGraph graph = fetch(ExplorerConstants.SYSTEM_DOC_REF);

        final String json = JsonUtil.getMapper().writeValueAsString(graph);
        final DependencyGraph read = JsonUtil.getMapper().readValue(json, DependencyGraph.class);

        assertThat(read).isEqualTo(graph);
        assertThat(read.getNodes()).isNotEmpty();
    }

    private DependencyGraph fetch(final DocRef folder) {
        final Instant start = Instant.now();
        final DependencyGraph graph = dependencyGraphService.fetchGraph(new DependencyGraphRequest(folder, null, null));
        final Duration took = Duration.between(start, Instant.now());
        final Map<EdgeKind, Long> kinds = graph.getEdges().stream()
                .collect(Collectors.groupingBy(GraphEdge::getKind, Collectors.counting()));
        LOGGER.info("Graph of '{}': {} nodes, {} edges {}, limit reached: {}, fetched in {}",
                folder.getName(), graph.getNodes().size(), graph.getEdges().size(), kinds,
                graph.isNodeLimitReached(), took);
        return graph;
    }

    private void assertWellFormed(final DependencyGraph graph) {
        final Set<String> ids = new HashSet<>();
        for (final GraphNode node : graph.getNodes()) {
            assertThat(ids.add(node.getId()))
                    .describedAs("node ids are unique: %s", node.getId())
                    .isTrue();
            assertThat(node.getDocRef()).isNotNull();
            assertThat(ExplorerConstants.isFolderOrSystem(node.getDocRef())).isFalse();
        }
        assertThat(graph.getEdges()).allSatisfy(edge -> {
            assertThat(ids).contains(edge.getFrom(), edge.getTo());
            assertThat(edge.getKind()).isNotNull();
        });
        assertThat(graph.getNodes().size()).isLessThanOrEqualTo(DependencyGraphService.DEFAULT_MAX_NODES);
    }

    private DocRef folder(final String name) {
        final List<ExplorerNode> nodes = explorerNodeService.getNodesByName(explorerNodeService.getRoot(), name);
        assertThat(nodes).describedAs("folder %s", name).hasSize(1);
        return nodes.getFirst().getDocRef();
    }

    private static String name(final DependencyGraph graph, final String id) {
        return graph.getNodes().stream()
                .filter(node -> node.getId().equals(id))
                .findFirst()
                .map(node -> node.getDocRef().getName())
                .orElseThrow();
    }
}
