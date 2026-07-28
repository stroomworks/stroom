/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.graphdb.impl;

import stroom.docref.DocRef;
import stroom.graphdb.shared.GraphDbDoc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers where a graph query is allowed to run.
 *
 * <p>The case that matters is the negative one: a resolver that claimed every document type would divert Plan B and
 * index queries to a graph node, and one that claimed none would let a graph query run on a node holding no graph
 * data at all.</p>
 */
class TestGraphQueryNodeResolverImpl {

    @Test
    void getNode_forAGraph_returnsTheFirstConfiguredNode() {
        final GraphQueryNodeResolverImpl resolver = resolverFor("node2", "node3");

        assertThat(resolver.getNode(graphDocRef())).isEqualTo("node2");
    }

    /**
     * With no nodes configured, only the local node holds graph data, so an unconstrained (local) query is right.
     */
    @Test
    void getNode_withNoConfiguredNodes_imposesNoConstraint() {
        final GraphQueryNodeResolverImpl resolver = resolverFor();

        assertThat(resolver.getNode(graphDocRef())).isNull();
    }

    /**
     * Other features' documents must be left to their own resolvers - the composite takes the first non-null answer,
     * so claiming a type this resolver does not own would hijack it.
     */
    @Test
    void getNode_forAnotherDocumentType_imposesNoConstraint() {
        final GraphQueryNodeResolverImpl resolver = resolverFor("node2");

        assertThat(resolver.getNode(DocRef.builder().type("PlanB").uuid("u").name("n").build())).isNull();
        assertThat(resolver.getNode(null)).isNull();
    }

    private static GraphQueryNodeResolverImpl resolverFor(final String... nodes) {
        // Nulls take each setting's default; this test cares only about path and nodeList.
        final GraphDbConfig config = new GraphDbConfig(
                "graphdb", List.of(nodes), null, null, null, null, null, null);
        return new GraphQueryNodeResolverImpl(() -> config);
    }

    private static DocRef graphDocRef() {
        return DocRef.builder().type(GraphDbDoc.TYPE).uuid("graph-uuid").name("Graph1").build();
    }
}
