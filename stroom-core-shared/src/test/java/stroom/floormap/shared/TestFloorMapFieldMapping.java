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

package stroom.floormap.shared;

import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FloorMapFieldMapping} — construction, equality,
 * and JSON serialisation round-trip.
 */
class TestFloorMapFieldMapping {

    @Test
    void testConstruction() {
        final FloorMapFieldMapping mapping = new FloorMapFieldMapping(
                ".type", Role.TYPE, "Type", "gates");

        assertThat(mapping.getPath()).isEqualTo(".type");
        assertThat(mapping.getRole()).isEqualTo(Role.TYPE);
        assertThat(mapping.getDisplayName()).isEqualTo("Type");
        assertThat(mapping.getDefaultValue()).isEqualTo("gates");
    }

    @Test
    void testNullFields() {
        final FloorMapFieldMapping mapping = new FloorMapFieldMapping(
                null, null, null, null);

        assertThat(mapping.getPath()).isNull();
        assertThat(mapping.getRole()).isNull();
        assertThat(mapping.getDisplayName()).isNull();
        assertThat(mapping.getDefaultValue()).isNull();
    }

    @Test
    void testEquality() {
        final FloorMapFieldMapping a = new FloorMapFieldMapping(
                ".type", Role.TYPE, "Type", "gates");
        final FloorMapFieldMapping b = new FloorMapFieldMapping(
                ".type", Role.TYPE, "Type", "gates");
        final FloorMapFieldMapping c = new FloorMapFieldMapping(
                ".coords", Role.POSITION, "Position", null);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void testJsonRoundTrip_singleMapping() {
        final FloorMapFieldMapping original = new FloorMapFieldMapping(
                ".type", Role.TYPE, "Type", "gates");

        final String json = JsonUtil.writeValueAsString(original);
        assertThat(json).isNotNull();
        assertThat(json).contains("\"path\"");
        assertThat(json).contains(".type");
        assertThat(json).contains("\"role\"");
        assertThat(json).contains("TYPE");

        final FloorMapFieldMapping deserialized =
                JsonUtil.readValue(json, FloorMapFieldMapping.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testJsonRoundTrip_nullFieldsOmitted() {
        final FloorMapFieldMapping original = new FloorMapFieldMapping(
                ".coords", Role.POSITION, "Position", null);

        final String json = JsonUtil.writeValueAsString(original);

        // null fields should be omitted (@JsonInclude(Include.NON_NULL))
        assertThat(json).doesNotContain("defaultValue");

        final FloorMapFieldMapping deserialized =
                JsonUtil.readValue(json, FloorMapFieldMapping.class);
        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void testJsonRoundTrip_schemaList() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        ".type", Role.TYPE, "Type", "gates"),
                new FloorMapFieldMapping(
                        ".coords", Role.POSITION, "Position", null),
                new FloorMapFieldMapping(
                        ".img", Role.IMAGE, "Image", null),
                new FloorMapFieldMapping(
                        ".tm-world-to-map", Role.WORLD_TO_MAP,
                        "World to Map", null),
                new FloorMapFieldMapping(
                        ".tm-map-to-screen", Role.MAP_TO_SCREEN,
                        "Map to Screen", null));

        // Embed in a FloorMapDoc to test full round-trip
        final FloorMapDoc doc = FloorMapDoc.builder()
                .uuid("test-uuid")
                .name("TestMap")
                .valueSchema(schema)
                .build();

        final String json = JsonUtil.writeValueAsString(doc);
        assertThat(json).isNotNull();

        final FloorMapDoc deserialized =
                JsonUtil.readValue(json, FloorMapDoc.class);
        assertThat(deserialized.getValueSchema())
                .hasSize(5)
                .isEqualTo(schema);
    }

    @Test
    void testJsonRoundTrip_xmlPaths() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        "/entry/type", Role.TYPE, "Type", null),
                new FloorMapFieldMapping(
                        "/entry/coords", Role.POSITION, "Position", null),
                new FloorMapFieldMapping(
                        "/entry/@id", null, "ID", null));

        final FloorMapDoc doc = FloorMapDoc.builder()
                .uuid("test-uuid")
                .name("TestMap")
                .valueFormat(ValueFormat.XML)
                .valueSchema(schema)
                .build();

        final String json = JsonUtil.writeValueAsString(doc);
        final FloorMapDoc deserialized =
                JsonUtil.readValue(json, FloorMapDoc.class);

        assertThat(deserialized.getValueFormat())
                .isEqualTo(ValueFormat.XML);
        assertThat(deserialized.getValueSchema())
                .hasSize(3)
                .isEqualTo(schema);
        assertThat(deserialized.getValueSchema().get(2).getPath())
                .isEqualTo("/entry/@id");
    }

    @Test
    void testAllRoles() {
        // Verify all Role enum values can be serialised/deserialised
        for (final Role role : Role.values()) {
            final FloorMapFieldMapping original =
                    new FloorMapFieldMapping(
                            ".test", role, role.name(), null);

            final String json =
                    JsonUtil.writeValueAsString(original);
            final FloorMapFieldMapping deserialized =
                    JsonUtil.readValue(json,
                            FloorMapFieldMapping.class);

            assertThat(deserialized.getRole())
                    .isEqualTo(role);
        }
    }

    @Test
    void testInitialValueSchema() {
        // Verify the static initialValueSchema() helper
        final List<FloorMapFieldMapping> schema =
                FloorMapFieldMapping.initialValueSchema();

        assertThat(schema).isNotNull().isNotEmpty();
        // Should contain at least TYPE and POSITION
        assertThat(schema.stream()
                .anyMatch(m -> m.getRole() == Role.TYPE))
                .isTrue();
        assertThat(schema.stream()
                .anyMatch(m -> m.getRole() == Role.POSITION))
                .isTrue();
    }
}
