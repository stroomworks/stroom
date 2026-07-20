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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A named saved <em>view</em> of the floor map's layers — a snapshot of which
 * layer types are hidden and which are dimmed (see the Layers implementation plan,
 * Phase&nbsp;3). Presets are the <strong>one persisted</strong> piece of layer
 * state: live visibility / lock / opacity are transient, but a preset captures a
 * visibility/opacity snapshot that survives reload and can open by default,
 * especially on the read-only Map tab.
 *
 * <p>Stored on {@link FloorMapDoc#getLayerPresets()} as an ordered list. Immutable.</p>
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public class FloorMapLayerPreset {

    @JsonProperty
    private final String name;
    /** Layer types hidden in this view. */
    @JsonProperty
    private final List<String> hiddenTypes;
    /** Per-type opacity ({@code 0..1}) for dimmed layers; absent = fully opaque. */
    @JsonProperty
    private final Map<String, Double> opacity;
    /** {@code true} if this view should be applied when the map first opens. */
    @JsonProperty
    private final boolean defaultOnOpen;

    @JsonCreator
    public FloorMapLayerPreset(@JsonProperty("name") final String name,
                               @JsonProperty("hiddenTypes") final List<String> hiddenTypes,
                               @JsonProperty("opacity") final Map<String, Double> opacity,
                               @JsonProperty("defaultOnOpen") final boolean defaultOnOpen) {
        this.name = name;
        this.hiddenTypes = hiddenTypes;
        this.opacity = opacity;
        this.defaultOnOpen = defaultOnOpen;
    }

    public String getName() {
        return name;
    }

    public List<String> getHiddenTypes() {
        return hiddenTypes;
    }

    public Map<String, Double> getOpacity() {
        return opacity;
    }

    public boolean isDefaultOnOpen() {
        return defaultOnOpen;
    }

    /** The hidden types as a fresh mutable set (never {@code null}). */
    public java.util.Set<String> hiddenTypesAsSet() {
        return hiddenTypes != null
                ? new java.util.LinkedHashSet<>(hiddenTypes)
                : new java.util.LinkedHashSet<>();
    }

    /** The opacity map as a fresh mutable copy (never {@code null}). */
    public Map<String, Double> opacityAsMap() {
        return opacity != null
                ? new HashMap<>(opacity)
                : new HashMap<>();
    }

    /**
     * Captures the current transient layer state into a named preset.
     *
     * @param name          the view name
     * @param hiddenTypes   the currently-hidden layer types (may be {@code null})
     * @param opacity       the current per-type opacity (may be {@code null})
     * @param defaultOnOpen whether this view opens by default
     */
    public static FloorMapLayerPreset capture(final String name,
                                              final Collection<String> hiddenTypes,
                                              final Map<String, Double> opacity,
                                              final boolean defaultOnOpen) {
        return new FloorMapLayerPreset(
                name,
                hiddenTypes != null ? new ArrayList<>(hiddenTypes) : new ArrayList<>(),
                opacity != null ? new HashMap<>(opacity) : new HashMap<>(),
                defaultOnOpen);
    }

    /**
     * The preset marked {@link #isDefaultOnOpen()} in the list, or {@code null} if
     * none is (the map then opens with everything shown).
     */
    public static FloorMapLayerPreset findDefault(final List<FloorMapLayerPreset> presets) {
        if (presets != null) {
            for (final FloorMapLayerPreset preset : presets) {
                if (preset != null && preset.isDefaultOnOpen()) {
                    return preset;
                }
            }
        }
        return null;
    }

    /** Finds a preset by name, or {@code null}. */
    public static FloorMapLayerPreset findByName(final List<FloorMapLayerPreset> presets,
                                                 final String name) {
        if (presets != null && name != null) {
            for (final FloorMapLayerPreset preset : presets) {
                if (preset != null && name.equals(preset.getName())) {
                    return preset;
                }
            }
        }
        return null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FloorMapLayerPreset that = (FloorMapLayerPreset) o;
        return defaultOnOpen == that.defaultOnOpen
                && Objects.equals(name, that.name)
                && Objects.equals(hiddenTypes, that.hiddenTypes)
                && Objects.equals(opacity, that.opacity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, hiddenTypes, opacity, defaultOnOpen);
    }

    @Override
    public String toString() {
        return "FloorMapLayerPreset{name='" + name + "', hiddenTypes=" + hiddenTypes
                + ", opacity=" + opacity + ", defaultOnOpen=" + defaultOnOpen + "}";
    }
}
