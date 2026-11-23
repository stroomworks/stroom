/*
 * Copyright 2026 Crown Copyright
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

package stroom.dependency.client.component;

import stroom.dependency.client.layout.Dims;
import stroom.dependency.client.layout.GraphViewport;
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LayoutConstants;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.GraphElement;

/// The text drawn on each node of the Structure view, which depends on the zoom.
///
/// Labels are counter-scaled below actual size so they stay legible, so fewer characters fit as the graph
/// is zoomed out, and names are cut short with an ellipsis to fit. Ported from the reference harness's
/// renderer (`fits()`, `fitsCaps()` and the name and type lines).
public final class StructureGraphText {

    /// Font size of a flow node's name, before counter-scaling.
    static final double NAME_SIZE = 15;
    /// Font size of a referenced document's or endpoint's name, before counter-scaling.
    static final double SMALL_NAME_SIZE = 13.5;
    /// Font size of a flow node's type line, before counter-scaling.
    static final double META_SIZE = 12;
    /// Font size of a referenced document's or endpoint's type line, before counter-scaling.
    static final double SMALL_META_SIZE = 11;
    /// Font size of an element's name on an expanded pipeline, before counter-scaling.
    static final double ELEMENT_NAME_SIZE = 12;
    /// Font size of an element's type on an expanded pipeline, before counter-scaling.
    static final double ELEMENT_TYPE_SIZE = 11;
    /// How far an element's name and type sit in from the sides of its box, and apart.
    static final double ELEMENT_PAD = 10;
    /// The most of an element's box its type may take, right-aligned; its name has the rest. A type is
    /// short and says what the element does, so it gets the room it needs up to this, and the name
    /// (often a long id) is cut short first.
    static final double ELEMENT_TYPE_MOST_SHARE = 0.45;

    // A monospace character is about this wide, as a fraction of its font size; the type line's sans
    // capitals a little narrower.
    private static final double MONO_CHAR_WIDTH = 0.6;
    private static final double SANS_CHAR_WIDTH = 0.55;
    // The space a flow node's name leaves for its glyph and toggle, and the fewest characters at which a
    // name is still worth drawing.
    private static final int NAME_PAD = 94;
    private static final int MIN_NAME_CHARS = 8;
    // At or below this zoom the shared-copies badge is hidden, so leaves the name its space.
    private static final double BADGE_HIDDEN_AT_OR_BELOW = 0.62;
    private static final int BADGE_WIDTH = 30;
    private static final String ELLIPSIS = "…";
    private static final double FIT_TOLERANCE = 1e-9;
    // The width of an element's box the name and type share, less the padding at each side and between.
    private static final double ELEMENT_TEXT_WIDTH = LayoutConstants.EW - 3 * ELEMENT_PAD;

    private StructureGraphText() {
        // Static methods only.
    }

    /// @param zoom The canvas zoom.
    /// @return true if names are large enough to read at this zoom; below it, nodes are drawn without them.
    public static boolean isNameVisible(final double zoom) {
        return fits(LayoutConstants.W, NAME_PAD, NAME_SIZE, zoom) >= MIN_NAME_CHARS;
    }

    /// @param layout   The layout.
    /// @param instance An instance of it.
    /// @param zoom     The canvas zoom.
    /// @return the instance's name, cut short with an ellipsis if it does not fit at this zoom.
    public static String name(final LayoutResult layout, final Instance instance, final double zoom) {
        final Dims dims = layout.dims(instance);
        final boolean small = isSmall(instance);
        final boolean summary = isSummary(instance);
        final double size = small
                ? SMALL_NAME_SIZE
                : NAME_SIZE;
        final int badgePad = instance.getShared() != null && instance.getShared() > 1 && zoom > BADGE_HIDDEN_AT_OR_BELOW
                ? fits(BADGE_WIDTH, 0, size, zoom)
                : 0;
        final int pad;
        if (instance.getKind() == InstanceKind.ENDPOINT) {
            pad = 50;
        } else if (isReferenceRow(instance)) {
            // A summary leaves room for its own chevron.
            pad = summary
                    ? 88
                    : 50;
        } else {
            pad = NAME_PAD;
        }
        final int most = small
                ? 22
                : 24;
        final int cap = Math.max(4, Math.min(most, fits(dims.w(), pad, size, zoom) - badgePad));
        return truncate(instance.getName(), cap);
    }

    /// @param layout   The layout.
    /// @param instance An instance of it.
    /// @param zoom     The canvas zoom.
    /// @return the line under the instance's name: its type, or what a summary stands for, cut short if
    /// it does not fit. A summary's count comes first, because it is the part worth reading.
    public static String meta(final LayoutResult layout, final Instance instance, final double zoom) {
        final Dims dims = layout.dims(instance);
        final boolean small = isSmall(instance);
        final boolean expanded = isExpanded(layout, instance);
        final String text;
        if (instance.getKind() == InstanceKind.LANE_SUMMARY) {
            text = instance.getCount() + " docs · collapsed lane";
        } else if (instance.getKind() == InstanceKind.REFERENCE_SUMMARY) {
            text = instance.getCount() + " refs · collapsed";
        } else if (expanded) {
            text = StructureGraphTypes.label("Pipeline");
        } else {
            text = StructureGraphTypes.label(instance.getType());
        }
        final int pad;
        if (expanded) {
            pad = 22;
        } else if (small) {
            pad = 50;
        } else {
            pad = 56;
        }
        final double size = small
                ? SMALL_META_SIZE
                : META_SIZE;
        final int cap = Math.max(3, (int) Math.floor(((dims.w() - pad) * zoom) / (SANS_CHAR_WIDTH * size)));
        return truncate(text, cap);
    }

    /// @param element An element of an expanded pipeline.
    /// @param zoom    The canvas zoom.
    /// @return the element's name (or id, if it has no name), cut short to fit the left of its box at this
    /// zoom.
    public static String elementName(final GraphElement element, final double zoom) {
        final double nameWidth = ELEMENT_TEXT_WIDTH - typeWidth(element, zoom);
        return truncate(element.getDisplayName(), fitsSans(nameWidth, ELEMENT_NAME_SIZE, zoom));
    }

    /// @param element An element of a pipeline.
    /// @return the element in full, e.g. `translationFilter (XSLTFilter)`, and, if the pipeline inherits it,
    /// where from, e.g. `translationFilter (XSLTFilter, inherited from Event Parser Template)`.
    public static String elementDescription(final GraphElement element) {
        final StringBuilder text = new StringBuilder(element.getDisplayName())
                .append(" (")
                .append(element.getType());
        if (element.getInheritedFrom() != null) {
            final String ancestor = element.getInheritedFrom().getName() == null
                    ? element.getInheritedFrom().getUuid()
                    : element.getInheritedFrom().getName();
            text.append(", inherited from ").append(ancestor);
        }
        return text.append(')').toString();
    }

    /// @param element An element of an expanded pipeline.
    /// @param zoom    The canvas zoom.
    /// @return the element's type, e.g. `XSLTFilter`, cut short to fit the right of its box at this zoom.
    public static String elementType(final GraphElement element, final double zoom) {
        return truncate(element.getType(), fitsSans(typeWidth(element, zoom), ELEMENT_TYPE_SIZE, zoom));
    }

    // The width an element's type takes in its box at a zoom: all it needs, up to its most share.
    private static double typeWidth(final GraphElement element, final double zoom) {
        final String type = element.getType() == null
                ? ""
                : element.getType();
        final double needed = type.length() * SANS_CHAR_WIDTH * ELEMENT_TYPE_SIZE * GraphViewport.labelScale(zoom);
        return Math.min(needed, ELEMENT_TEXT_WIDTH * ELEMENT_TYPE_MOST_SHARE);
    }

    /// @param text A label, or null.
    /// @param cap  The most characters to show.
    /// @return the label, or its first `cap - 1` characters and an ellipsis if it is longer than `cap`.
    public static String truncate(final String text, final int cap) {
        if (text == null) {
            return "";
        }
        return text.length() > cap
                ? text.substring(0, Math.max(0, cap - 1)) + ELLIPSIS
                : text;
    }

    /// @param layout   The layout.
    /// @param instance An instance of it.
    /// @return true if the instance is a pipeline drawn expanded, showing its elements.
    public static boolean isExpanded(final LayoutResult layout, final Instance instance) {
        return instance.getKind() == InstanceKind.FLOW && layout.getExpanded().contains(instance.getId());
    }

    /// @param instance An instance.
    /// @return true if it is drawn in the smaller style of the reference and endpoint rows.
    public static boolean isSmall(final Instance instance) {
        return isReferenceRow(instance) || instance.getKind() == InstanceKind.ENDPOINT;
    }

    /// @param instance An instance.
    /// @return true if it is a lane or reference row folded into one node.
    public static boolean isSummary(final Instance instance) {
        return instance.getKind() == InstanceKind.LANE_SUMMARY
               || instance.getKind() == InstanceKind.REFERENCE_SUMMARY;
    }

    private static boolean isReferenceRow(final Instance instance) {
        return instance.getKind() == InstanceKind.REFERENCE
               || instance.getKind() == InstanceKind.REFERENCE_SUMMARY;
    }

    // How many characters of the sans font fit across a width of the graph, at a zoom: labels are drawn
    // in the graph's units, multiplied by the label scale. At least 3, so a label is never cut to nothing.
    private static int fitsSans(final double width, final double size, final double zoom) {
        final double scale = GraphViewport.labelScale(zoom);
        // The tolerance keeps a width worked out as exactly n characters from rounding down to n - 1
        return Math.max(3, (int) Math.floor(width / (SANS_CHAR_WIDTH * size * scale) + FIT_TOLERANCE));
    }

    // How many characters of a monospace font fit across a width, less padding, at a zoom.
    private static int fits(final double width, final double pad, final double size, final double zoom) {
        return (int) Math.floor(((width - pad) * zoom) / (MONO_CHAR_WIDTH * size));
    }
}
