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

package stroom.dependency.shared;

import stroom.docref.DocRef;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestDependencyGraph {

    private static final DocRef FOLDER = new DocRef("Folder", "folder-uuid", "Events");
    private static final DocRef FEED = new DocRef("Feed", "feed-uuid", "FW-LOGS:RAW_EVENTS");
    private static final DocRef PIPELINE = new DocRef("Pipeline", "pipe-uuid", "FW Parser");

    @Test
    void testRoundTrip() {
        final DependencyGraph graph = new DependencyGraph(
                FOLDER,
                List.of(
                        new GraphNode("feed-uuid", FEED, NodeRole.FLOW, "Raw Events", true, null, null, null),
                        new GraphNode("inst:pipe-uuid:feed-uuid:x",
                                PIPELINE,
                                NodeRole.FLOW,
                                null,
                                false,
                                1,
                                6,
                                2)),
                List.of(new GraphEdge("feed-uuid",
                        "inst:pipe-uuid:feed-uuid:x",
                        EdgeKind.FLOW,
                        EdgeCertainty.CONFIGURED,
                        "translationFilter",
                        true)),
                List.of(new GraphTruncation("feed-uuid", EdgeKind.QUERY, "Dashboard", 9)),
                true,
                "abc123",
                1_700_000_000_000L);

        final JsonMapper jsonMapper = JsonUtil.getMapper();
        final String json1 = jsonMapper.writeValueAsString(graph);
        final DependencyGraph graph2 = jsonMapper.readValue(json1, DependencyGraph.class);
        final String json2 = jsonMapper.writeValueAsString(graph2);

        assertThat(graph2)
                .isEqualTo(graph);
        assertThat(json2)
                .isEqualTo(json1);
    }

    @Test
    void testNoPositionsInJson() {
        // The server sends a graph, not a drawing, so no layout decision may appear in what it sends.
        final DependencyGraph graph = new DependencyGraph(
                FOLDER,
                List.of(new GraphNode("feed-uuid", FEED, NodeRole.FLOW, null, true, null, null, null)),
                null,
                null,
                null,
                null,
                null);

        final String json = JsonUtil.getMapper().writeValueAsString(graph);

        assertThat(json)
                .doesNotContain("\"x\"", "\"y\"", "\"lane\"", "\"col\"", "\"rank\"");
    }

    @Test
    void testNullsBecomeDefaults() {
        final DependencyGraph graph = JsonUtil.getMapper().readValue("{}", DependencyGraph.class);

        assertThat(graph.getNodes())
                .isEmpty();
        assertThat(graph.getEdges())
                .isEmpty();
        assertThat(graph.getTruncations())
                .isEmpty();
        assertThat(graph.isNodeLimitReached())
                .isFalse();

        final GraphEdge edge = JsonUtil.getMapper().readValue(
                "{\"from\":\"a\",\"to\":\"b\",\"kind\":\"FLOW\"}", GraphEdge.class);

        assertThat(edge.isAmbiguous())
                .isFalse();
        assertThat(edge.getCertainty())
                .isNull();

        final GraphTruncation truncation = JsonUtil.getMapper().readValue("{}", GraphTruncation.class);

        assertThat(truncation.getOmitted())
                .isZero();
    }

    @Test
    void testRequestRoundTrip() {
        final DependencyGraphRequest request = new DependencyGraphRequest(FOLDER, 1, 400);

        final JsonMapper jsonMapper = JsonUtil.getMapper();
        final String json = jsonMapper.writeValueAsString(request);

        assertThat(jsonMapper.readValue(json, DependencyGraphRequest.class))
                .isEqualTo(request);
    }
}
