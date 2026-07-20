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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layer-visibility logic for the floor map (see the Layers implementation plan,
 * Phase&nbsp;1). A "layer" is a fact {@code type}.
 *
 * <p>Visibility is <strong>transient client state</strong>, not part of the
 * persisted document: the caller supplies the set of currently-hidden types and
 * the soloed type. This keeps the filter here purely about drawing — it is
 * applied at the render choke-point <em>before</em> {@link FloorMapZOrder#sort},
 * so hidden layers are never built into the SVG (the per-frame redraw rebuilds
 * the whole DOM, so "not built" genuinely means "not drawn"). Pure logic with no
 * GWT dependencies, so it can be unit-tested directly.</p>
 *
 * <p><strong>Solo</strong> is a transient isolate override: when a solo type is
 * set, only that type is shown regardless of the hidden set.</p>
 */
public final class FloorMapLayers {

    private FloorMapLayers() {
        // Utility class
    }

    /**
     * Returns a new list containing only the facts whose type-layer is currently
     * shown, per {@link #isTypeVisible(String, Set, String)}.
     *
     * @param facts       the facts to filter (unchanged; a new list is returned)
     * @param hiddenTypes the set of currently-hidden layer types (may be {@code null})
     * @param soloType    the type being soloed, or {@code null}/blank for none
     * @return a new, filtered list; never {@code null}
     */
    public static List<Fact> visibleFacts(final List<Fact> facts,
                                          final Set<String> hiddenTypes,
                                          final String soloType) {
        final List<Fact> result = new ArrayList<>();
        if (facts != null) {
            for (final Fact fact : facts) {
                if (fact != null && isTypeVisible(fact.getType(), hiddenTypes, soloType)) {
                    result.add(fact);
                }
            }
        }
        return result;
    }

    /**
     * Whether facts of the given type are currently shown.
     *
     * <ul>
     *   <li>If a {@code soloType} is set, only that type is visible (solo overrides
     *       the hidden set, so a soloed type always shows).</li>
     *   <li>Otherwise the type is visible unless it is in {@code hiddenTypes}. An
     *       unknown type (not hidden) is visible — matching {@link FloorMapZOrder},
     *       where unconfigured types paint on top.</li>
     * </ul>
     */
    public static boolean isTypeVisible(final String type,
                                        final Set<String> hiddenTypes,
                                        final String soloType) {
        if (soloType != null && !soloType.isEmpty()) {
            return soloType.equals(type);
        }
        return hiddenTypes == null || !hiddenTypes.contains(type);
    }

    /**
     * Whether facts of the given type are on a locked layer (transient, per session).
     * Locked facts are excluded from hit-testing and the selection set.
     */
    public static boolean isLocked(final String type, final Set<String> lockedTypes) {
        return lockedTypes != null && type != null && lockedTypes.contains(type);
    }

    /**
     * The resolved draw opacity for a type: the configured value clamped to
     * {@code [0, 1]}, or {@code 1.0} (fully opaque) when unset. Transient — the
     * opacity map is session state, not part of the document.
     */
    public static double resolveOpacity(final String type,
                                        final Map<String, Double> opacityByType) {
        if (opacityByType == null || type == null) {
            return 1.0;
        }
        final Double opacity = opacityByType.get(type);
        if (opacity == null) {
            return 1.0;
        }
        if (opacity < 0.0) {
            return 0.0;
        }
        if (opacity > 1.0) {
            return 1.0;
        }
        return opacity;
    }
}
