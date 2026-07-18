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

package stroom.graphdb.shared;

import stroom.planb.shared.TemporalPrecision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGraphDbDoc {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTripsThroughJackson_withAllFieldsSet() throws Exception {
        final GraphDbDoc doc = GraphDbDoc.builder()
                .uuid("test-uuid")
                .name("MyGraph")
                .version("1")
                .description("A test graph")
                .temporalPrecision(TemporalPrecision.MILLISECOND)
                .nodeTypeMappings(List.of(new GraphNodeTypeMapping("User", "User.id")))
                .build();

        final String json = MAPPER.writeValueAsString(doc);
        final GraphDbDoc roundTripped = MAPPER.readValue(json, GraphDbDoc.class);

        assertThat(roundTripped).isEqualTo(doc);
        assertThat(roundTripped.getDescription()).isEqualTo("A test graph");
        assertThat(roundTripped.getTemporalPrecision()).isEqualTo(TemporalPrecision.MILLISECOND);
        assertThat(roundTripped.getNodeTypeMappings())
                .containsExactly(new GraphNodeTypeMapping("User", "User.id"));
    }

    @Test
    void roundTripsThroughJackson_withOnlyRequiredFieldsSet() throws Exception {
        final GraphDbDoc doc = GraphDbDoc.builder()
                .uuid("test-uuid")
                .name("MyGraph")
                .build();

        final String json = MAPPER.writeValueAsString(doc);
        final GraphDbDoc roundTripped = MAPPER.readValue(json, GraphDbDoc.class);

        assertThat(roundTripped).isEqualTo(doc);
        assertThat(roundTripped.getDescription()).isNull();
        assertThat(roundTripped.getTemporalPrecision()).isNull();
        assertThat(roundTripped.getNodeTypeMappings()).isNull();
    }

    @Test
    void exposesOnlyTheUserConfigurableFields() throws Exception {
        final GraphDbDoc doc = GraphDbDoc.builder()
                .uuid("test-uuid")
                .name("MyGraph")
                .description("A test graph")
                .temporalPrecision(TemporalPrecision.SECOND)
                .build();

        final String json = MAPPER.writeValueAsString(doc);

        // No physical-store config field (byte layout, interning, sharding, anchor-index tech) is present -
        // only the genuine user choices (design doc D8).
        assertThat(json).doesNotContain("nodeUidWidth", "propertyIndex", "shard", "partition", "interning");
    }

    @Test
    void constructor_rejectsNullUuid() {
        assertThatThrownBy(() -> new GraphDbDoc(
                null, "name", null, null, null, null, null,
                null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
