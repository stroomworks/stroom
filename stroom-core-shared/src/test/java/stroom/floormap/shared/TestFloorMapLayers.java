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

class TestFloorMapLayers {

    private static Fact fact(final String key, final String type) {
        return new Fact(key, type, null, null, new double[]{0, 0});
    }

    private static List<String> keys(final List<Fact> facts) {
        return facts.stream().map(Fact::getKey).toList();
    }

    private final List<Fact> facts = List.of(
            fact("d1", "desk"),
            fact("g1", "gate"),
            fact("c1", "camera"),
            fact("d2", "desk"));

    /** A hidden type is dropped; other types remain. */
    @Test
    void testVisibleFacts_hidesHiddenType() {
        final List<Fact> visible = FloorMapLayers.visibleFacts(facts, Set.of("desk"), null);
        assertThat(keys(visible)).containsExactly("g1", "c1");
    }

    /** Several hidden types are all dropped. */
    @Test
    void testVisibleFacts_hidesMultipleTypes() {
        final List<Fact> visible = FloorMapLayers.visibleFacts(facts, Set.of("desk", "gate"), null);
        assertThat(keys(visible)).containsExactly("c1");
    }

    /** With no hidden set, everything is visible. */
    @Test
    void testVisibleFacts_noHidden_allVisible() {
        assertThat(keys(FloorMapLayers.visibleFacts(facts, null, null)))
                .containsExactly("d1", "g1", "c1", "d2");
    }

    /** Solo shows only the soloed type, ignoring the hidden set. */
    @Test
    void testVisibleFacts_soloShowsOnlyThatType() {
        // desk is hidden, but solo on desk overrides that and suppresses everything else.
        final List<Fact> visible = FloorMapLayers.visibleFacts(facts, Set.of("desk"), "desk");
        assertThat(keys(visible)).containsExactly("d1", "d2");
    }

    /** A blank solo type behaves as "no solo". */
    @Test
    void testVisibleFacts_blankSoloIsNoSolo() {
        assertThat(keys(FloorMapLayers.visibleFacts(facts, null, "")))
                .containsExactly("d1", "g1", "c1", "d2");
    }

    @Test
    void testIsTypeVisible_notHiddenIsVisible() {
        assertThat(FloorMapLayers.isTypeVisible("camera", Set.of("desk"), null)).isTrue();
        assertThat(FloorMapLayers.isTypeVisible("desk", Set.of("desk"), null)).isFalse();
    }

    @Test
    void testVisibleFacts_nullFactsReturnsEmpty() {
        assertThat(FloorMapLayers.visibleFacts(null, null, null)).isEmpty();
    }

    @Test
    void testIsLocked() {
        assertThat(FloorMapLayers.isLocked("desk", Set.of("desk", "gate"))).isTrue();
        assertThat(FloorMapLayers.isLocked("camera", Set.of("desk"))).isFalse();
        assertThat(FloorMapLayers.isLocked("desk", null)).isFalse();
        assertThat(FloorMapLayers.isLocked(null, Set.of("desk"))).isFalse();
    }

    @Test
    void testResolveOpacity() {
        final Map<String, Double> op = Map.of("bg", 0.3, "over", 2.0, "under", -1.0);
        assertThat(FloorMapLayers.resolveOpacity("bg", op)).isEqualTo(0.3);
        // Unset type → fully opaque.
        assertThat(FloorMapLayers.resolveOpacity("desk", op)).isEqualTo(1.0);
        // Out-of-range values are clamped.
        assertThat(FloorMapLayers.resolveOpacity("over", op)).isEqualTo(1.0);
        assertThat(FloorMapLayers.resolveOpacity("under", op)).isEqualTo(0.0);
        // Null map / type → fully opaque.
        assertThat(FloorMapLayers.resolveOpacity("bg", null)).isEqualTo(1.0);
        assertThat(FloorMapLayers.resolveOpacity(null, op)).isEqualTo(1.0);
    }
}
