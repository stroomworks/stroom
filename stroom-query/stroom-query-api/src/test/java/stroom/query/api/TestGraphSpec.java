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

package stroom.query.api;

import stroom.docref.DocRef;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GraphSpec} - the {@code Query.graphSpec} wire payload (implementation plan Task PoC.6) - had no
 * dedicated unit test; it was only touched incidentally inside {@code TestGraphSearchProvider}. Mirrors
 * {@code DocRefBuilderTest}'s simple builder-assertion style, plus a direct JSON round-trip since this is a wire
 * type carried across the search REST boundary.
 */
class TestGraphSpec {

    @Test
    void builder_buildsWithTheGivenCypherText() {
        final GraphSpec graphSpec = GraphSpec.builder().cypher("MATCH (n) RETURN n").build();

        assertThat(graphSpec.getCypher()).isEqualTo("MATCH (n) RETURN n");
    }

    @Test
    void constructor_rejectsNullCypher() {
        assertThatThrownBy(() -> new GraphSpec(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalsAndHashCode_dependOnlyOnCypherText() {
        final GraphSpec a = GraphSpec.builder().cypher("MATCH (n) RETURN n").build();
        final GraphSpec sameText = GraphSpec.builder().cypher("MATCH (n) RETURN n").build();
        final GraphSpec differentText = GraphSpec.builder().cypher("MATCH (m) RETURN m").build();

        assertThat(a).isEqualTo(sameText);
        assertThat(a.hashCode()).isEqualTo(sameText.hashCode());
        assertThat(a).isNotEqualTo(differentText);
    }

    @Test
    void toString_includesTheCypherText() {
        final GraphSpec graphSpec = GraphSpec.builder().cypher("MATCH (n) RETURN n").build();

        assertThat(graphSpec.toString()).contains("MATCH (n) RETURN n");
    }

    @Test
    void jsonRoundTrip_preservesTheCypherText() {
        final GraphSpec graphSpec = GraphSpec.builder().cypher("MATCH (d:Device {id: 'd-42'}) RETURN d.id").build();

        final String json = JsonUtil.writeValueAsString(graphSpec);
        final GraphSpec deserialised = JsonUtil.readValue(json, GraphSpec.class);

        assertThat(deserialised).isEqualTo(graphSpec);
        assertThat(deserialised.getCypher()).isEqualTo("MATCH (d:Device {id: 'd-42'}) RETURN d.id");
    }

    @Test
    void query_graphSpecRoundTripsThroughJsonAlongsideTheRestOfTheQuery() {
        // Query.graphSpec is additive (PoC.6, mirroring how Query.joinSpec was added) - prove it survives a
        // round-trip through the containing Query, not just in isolation.
        final Query query = Query.builder()
                .dataSource(new DocRef("GraphDb", "graph-uuid", "TestGraph"))
                .graphSpec(GraphSpec.builder().cypher("MATCH (n) RETURN n").build())
                .build();

        final String json = JsonUtil.writeValueAsString(query);
        final Query deserialised = JsonUtil.readValue(json, Query.class);

        assertThat(deserialised.getGraphSpec()).isEqualTo(query.getGraphSpec());
        assertThat(deserialised.getGraphSpec().getCypher()).isEqualTo("MATCH (n) RETURN n");
    }
}
