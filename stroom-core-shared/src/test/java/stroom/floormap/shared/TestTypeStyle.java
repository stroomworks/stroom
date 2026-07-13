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

import stroom.floormap.shared.TypeStyle.Shape;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestTypeStyle {

    private static List<String> types(final List<TypeStyle> styles) {
        return styles.stream().map(TypeStyle::getType).toList();
    }

    /** Discovering into an empty config adds every type, alphabetically. */
    @Test
    void testMerge_emptyExisting_addsAlphabetically() {
        final List<TypeStyle> merged =
                TypeStyle.merge(null, List.of("room", "background", "gate"));
        assertThat(types(merged)).containsExactly("background", "gate", "room");
    }

    /** Existing entries keep their (user-arranged) order; new types append after. */
    @Test
    void testMerge_preservesExistingOrder_appendsNew() {
        final List<TypeStyle> existing = List.of(
                new TypeStyle("gate", Shape.SQUARE, "#111"),
                new TypeStyle("background", Shape.CIRCLE, "#222"));

        final List<TypeStyle> merged =
                TypeStyle.merge(existing, List.of("background", "gate", "person", "desk"));

        // gate, background stay first in their existing order; new ones (desk,
        // person) are appended alphabetically.
        assertThat(types(merged)).containsExactly("gate", "background", "desk", "person");
    }

    /** A re-discovery of already-known types leaves the config unchanged. */
    @Test
    void testMerge_existingTypeSettingsPreserved() {
        final List<TypeStyle> existing = List.of(new TypeStyle("gate", Shape.DIAMOND, "#abc"));
        final List<TypeStyle> merged = TypeStyle.merge(existing, List.of("gate"));

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().getShape()).isEqualTo(Shape.DIAMOND);
        assertThat(merged.getFirst().getColour()).isEqualTo("#abc");
    }

    /** Null / blank discovered names are ignored. */
    @Test
    void testMerge_ignoresBlankNames() {
        final List<TypeStyle> merged = TypeStyle.merge(null,
                java.util.Arrays.asList("gate", "", null, "desk"));
        assertThat(types(merged)).containsExactly("desk", "gate");
    }

    /** Null discovered set leaves existing untouched. */
    @Test
    void testMerge_nullDiscovered_returnsExisting() {
        final List<TypeStyle> existing = List.of(new TypeStyle("gate", null, null));
        final List<TypeStyle> merged = TypeStyle.merge(existing, null);
        assertThat(types(merged)).containsExactly("gate");
    }
}
