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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapLayerPreset {

    @Test
    void testCapture_snapshotsHiddenAndOpacity() {
        final FloorMapLayerPreset preset = FloorMapLayerPreset.capture(
                "Coverage", Set.of("desk", "event"), Map.of("background", 0.3), false);

        assertThat(preset.getName()).isEqualTo("Coverage");
        assertThat(preset.getHiddenTypes()).containsExactlyInAnyOrder("desk", "event");
        assertThat(preset.getOpacity()).containsEntry("background", 0.3);
        assertThat(preset.isDefaultOnOpen()).isFalse();
    }

    @Test
    void testCapture_nullsBecomeEmpty() {
        final FloorMapLayerPreset preset = FloorMapLayerPreset.capture("Empty", null, null, true);
        assertThat(preset.getHiddenTypes()).isEmpty();
        assertThat(preset.getOpacity()).isEmpty();
        assertThat(preset.isDefaultOnOpen()).isTrue();
    }

    @Test
    void testAsSetAndAsMap_areDefensiveCopies() {
        final FloorMapLayerPreset preset = FloorMapLayerPreset.capture(
                "v", Set.of("a"), Map.of("b", 0.5), false);
        final Set<String> hidden = preset.hiddenTypesAsSet();
        final Map<String, Double> opacity = preset.opacityAsMap();
        hidden.add("mutated");
        opacity.put("mutated", 0.1);
        // Mutating the copies must not affect the preset.
        assertThat(preset.getHiddenTypes()).containsExactly("a");
        assertThat(preset.getOpacity()).containsOnlyKeys("b");
    }

    @Test
    void testFindDefault() {
        final FloorMapLayerPreset a = FloorMapLayerPreset.capture("A", null, null, false);
        final FloorMapLayerPreset b = FloorMapLayerPreset.capture("B", null, null, true);
        assertThat(FloorMapLayerPreset.findDefault(List.of(a, b))).isSameAs(b);
        assertThat(FloorMapLayerPreset.findDefault(List.of(a))).isNull();
        assertThat(FloorMapLayerPreset.findDefault(null)).isNull();
    }

    @Test
    void testFindByName() {
        final FloorMapLayerPreset a = FloorMapLayerPreset.capture("A", null, null, false);
        final FloorMapLayerPreset b = FloorMapLayerPreset.capture("B", null, null, false);
        assertThat(FloorMapLayerPreset.findByName(List.of(a, b), "B")).isSameAs(b);
        assertThat(FloorMapLayerPreset.findByName(List.of(a, b), "Z")).isNull();
        assertThat(FloorMapLayerPreset.findByName(List.of(a, b), null)).isNull();
    }

    @Test
    void testEquals() {
        final FloorMapLayerPreset a = FloorMapLayerPreset.capture("A", Set.of("x"), Map.of("y", 0.4), false);
        final FloorMapLayerPreset a2 = new FloorMapLayerPreset(
                "A", a.getHiddenTypes(), a.getOpacity(), false);
        final FloorMapLayerPreset diff = FloorMapLayerPreset.capture("A", Set.of("x"), Map.of("y", 0.4), true);
        assertThat(a).isEqualTo(a2);
        assertThat(a).isNotEqualTo(diff);
    }
}
