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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Per-type presentation settings for a floor map (see the coordinate/rendering
 * redesign, §6 "Type settings").
 *
 * <p>Types are held on the {@link FloorMapDoc} as an <em>ordered</em> list of
 * {@code TypeStyle}s. The list <strong>order is the z-order</strong> — earlier
 * entries paint behind later ones — and each entry also carries the default
 * graphic drawn for a fact of that type when it has no image of its own: either
 * a {@link #shape} filled with {@link #colour}, or an arbitrary image from the
 * document's asset store ({@link #graphic}).</p>
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public class TypeStyle {

    /** Default graphic shape for an imageless fact. */
    public enum Shape {
        CIRCLE,
        SQUARE,
        TRIANGLE,
        DIAMOND,
        PIN
    }

    @JsonProperty
    private final String type;
    @JsonProperty
    private final Shape shape;
    @JsonProperty
    private final String colour;
    @JsonProperty
    private final String graphic;

    @JsonCreator
    public TypeStyle(@JsonProperty("type") final String type,
                     @JsonProperty("shape") final Shape shape,
                     @JsonProperty("colour") final String colour,
                     @JsonProperty("graphic") final String graphic) {
        this.type = type;
        this.shape = shape;
        this.colour = colour;
        this.graphic = graphic;
    }

    /** Convenience for a shape-and-colour style with no image graphic. */
    public TypeStyle(final String type,
                     final Shape shape,
                     final String colour) {
        this(type, shape, colour, null);
    }

    /** The fact type this style applies to. */
    public String getType() {
        return type;
    }

    /** The default graphic shape, or {@code null} to use the render fallback. */
    public Shape getShape() {
        return shape;
    }

    /** The default graphic fill colour (e.g. {@code "#1f77b4"}), or {@code null}. */
    public String getColour() {
        return colour;
    }

    /**
     * The asset-store URL of the image to draw instead of {@link #shape} (e.g.
     * {@code "/assets/<docUuid>/icons/van.svg"}), or {@code null} to draw the
     * shape. Any format the browser can render is allowed.
     *
     * <p>The image is drawn at the same fixed screen size as a shape glyph, so
     * layers stay legible at every zoom level. A fact carrying its own image
     * takes precedence over its layer's graphic.</p>
     */
    public String getGraphic() {
        return graphic;
    }

    /** True if this style draws an image rather than a shape. */
    @JsonIgnore
    public boolean hasGraphic() {
        return graphic != null && !graphic.isEmpty();
    }

    /**
     * Merges the set of discovered type names into an existing ordered list of
     * type styles: existing entries keep their position and settings; any type
     * present in {@code discoveredTypes} but not already configured is appended
     * <strong>alphabetically</strong> with a default (null) shape and colour.
     *
     * <p>This backs the Settings tab's "Discover" button. It never removes a
     * configured type — a type that has disappeared from the data keeps its
     * saved style.</p>
     *
     * @param existing        the current ordered styles (may be {@code null})
     * @param discoveredTypes the distinct type names found in the data (may be
     *                        {@code null}); blank names are ignored
     * @return a new ordered list; never {@code null}
     */
    public static List<TypeStyle> merge(final List<TypeStyle> existing,
                                        final Collection<String> discoveredTypes) {
        final List<TypeStyle> result = new ArrayList<>();
        final Set<String> present = new LinkedHashSet<>();
        if (existing != null) {
            for (final TypeStyle style : existing) {
                result.add(style);
                if (style.getType() != null) {
                    present.add(style.getType());
                }
            }
        }
        if (discoveredTypes != null) {
            // Sort the genuinely-new type names alphabetically before appending.
            final Set<String> fresh = new TreeSet<>();
            for (final String name : discoveredTypes) {
                if (name != null && !name.isEmpty() && !present.contains(name)) {
                    fresh.add(name);
                }
            }
            for (final String name : fresh) {
                result.add(new TypeStyle(name, null, null));
            }
        }
        return result;
    }

    /**
     * Returns a copy of {@code existing} guaranteed to contain a style for the
     * {@link FloorMapJsonKeys#AREA} type.
     *
     * <p>If no {@code "area"} style is present, one is inserted immediately
     * <em>after</em> the last {@code "background"} entry (or at index 0 when
     * there is no background style), so areas paint above the floor plan but
     * beneath every other object. Do not rely on {@link #merge} for this —
     * discovery appends new types at the <em>end</em>, which would paint areas
     * on top of everything.</p>
     *
     * @param existing the current ordered styles (may be {@code null}); never
     *                 mutated
     * @return a new ordered list containing an {@code "area"} style; never
     *         {@code null}
     */
    public static List<TypeStyle> withAreaStyle(final List<TypeStyle> existing) {
        final List<TypeStyle> result = new ArrayList<>();
        int insertAt = 0;
        if (existing != null) {
            for (final TypeStyle style : existing) {
                if (style != null && FloorMapJsonKeys.AREA.equals(style.getType())) {
                    return new ArrayList<>(existing);
                }
                result.add(style);
                if (style != null && FloorMapJsonKeys.BACKGROUND.equals(style.getType())) {
                    insertAt = result.size();
                }
            }
        }
        result.add(insertAt, new TypeStyle(FloorMapJsonKeys.AREA, null, "#1e88e5"));
        return result;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final TypeStyle that = (TypeStyle) o;
        return Objects.equals(type, that.type)
                && shape == that.shape
                && Objects.equals(colour, that.colour)
                && Objects.equals(graphic, that.graphic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, shape, colour, graphic);
    }

    @Override
    public String toString() {
        return "TypeStyle{type='" + type + "', shape=" + shape + ", colour='" + colour
                + "', graphic='" + graphic + "'}";
    }
}
