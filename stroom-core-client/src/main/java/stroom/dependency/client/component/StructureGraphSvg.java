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
import stroom.dependency.client.layout.EdgePaths;
import stroom.dependency.client.layout.Ghost;
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LaneBox;
import stroom.dependency.client.layout.LayoutConstants;
import stroom.dependency.client.layout.LayoutEdge;
import stroom.dependency.client.layout.LayoutResult;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Builds the Structure view's SVG for one layout.
///
/// The SVG is built once per layout, when the data, a collapse, a pin or the detail level changes. Every
/// node group carries `data-id` (its instance id) and every edge `data-a` and `data-b`, so that everything
/// else (hover, selection, search, zoom and pan) is done by setting classes and attributes on the elements
/// already drawn, not by building them again. Colours and font sizes are in `structure-graph.css`, so the
/// markup carries geometry and classes only.
///
/// The SVG is hidden from assistive technology: it is a picture of the graph, positioned for the eye,
/// and the grid beside it is what a screen reader reads.
public final class StructureGraphSvg {

    /// The attribute holding an instance's id on its node group, and on any control inside it.
    public static final String DATA_ID = "data-id";
    /// The attribute holding the action a control inside a node or lane performs when clicked.
    public static final String DATA_ACTION = "data-action";
    /// The attribute holding a lane number on a lane's controls.
    public static final String DATA_LANE = "data-lane";
    /// The attribute holding the id of the instance an edge leaves.
    public static final String DATA_A = "data-a";
    /// The attribute holding the id of the instance an edge arrives at.
    public static final String DATA_B = "data-b";

    /// [#DATA_ACTION] of a pipeline's expand toggle.
    public static final String ACTION_EXPAND = "expand";
    /// [#DATA_ACTION] of a lane's collapse control.
    public static final String ACTION_COLLAPSE_LANE = "collapse-lane";
    /// [#DATA_ACTION] of a summary, which opens what it folds.
    public static final String ACTION_OPEN_SUMMARY = "open-summary";

    private static final SafeHtml SVG = SafeHtmlUtils.fromSafeConstant("svg");
    private static final SafeHtml DEFS = SafeHtmlUtils.fromSafeConstant("defs");
    private static final SafeHtml MARKER = SafeHtmlUtils.fromSafeConstant("marker");
    private static final SafeHtml G = SafeHtmlUtils.fromSafeConstant("g");
    private static final SafeHtml RECT = SafeHtmlUtils.fromSafeConstant("rect");
    private static final SafeHtml PATH = SafeHtmlUtils.fromSafeConstant("path");
    private static final SafeHtml POLYGON = SafeHtmlUtils.fromSafeConstant("polygon");
    private static final SafeHtml TEXT = SafeHtmlUtils.fromSafeConstant("text");

    private static final String BLOCK = "structure-graph";
    // How far a lane's background extends beyond its outermost instances.
    private static final int LANE_MARGIN = 30;
    private static final int LANE_RADIUS = 16;
    private static final int NODE_RADIUS = 8;
    private static final int EXPANDED_RADIUS = 10;
    private static final int CHIP_RADIUS = 6;
    // An endpoint's hexagon's corner inset.
    private static final double HEX_INSET = 13;

    private StructureGraphSvg() {
        // Static methods only.
    }

    /// @param layout   The layout to draw.
    /// @param markerId A document-unique id for the arrowhead marker, e.g. from `AriaUtil.uniqueId`.
    /// @param zoom     The zoom the graph is first drawn at, which decides how much of each name fits.
    ///                 Names are cut short again with [StructureGraphText] when the zoom changes.
    /// @return the SVG.
    public static SafeHtml build(final LayoutResult layout, final String markerId, final double zoom) {
        final HtmlBuilder builder = new HtmlBuilder();
        builder.elem(svg -> appendContent(svg, layout, markerId, zoom),
                SVG,
                elementClass("svg"),
                new Attribute("width", "100%"),
                new Attribute("height", "100%"),
                new Attribute("xmlns", "http://www.w3.org/2000/svg"),
                new Attribute("aria-hidden", "true"),
                new Attribute("focusable", "false"));
        return builder.toSafeHtml();
    }

    private static void appendContent(final HtmlBuilder svg,
                                      final LayoutResult layout,
                                      final String markerId,
                                      final double zoom) {
        svg.elem(defs -> arrowMarker(defs, markerId), DEFS);
        svg.elem(world -> {
            world.elem(lanes -> appendLanes(lanes, layout), G, elementClass("lanes"));
            world.elem(edges -> appendEdges(edges, layout, markerId), G, elementClass("edges"));
            world.elem(nodes -> appendNodes(nodes, layout, zoom), G, elementClass("nodes"));
        }, G, elementClass("world"));
    }

    private static void appendNodes(final HtmlBuilder nodes, final LayoutResult layout, final double zoom) {
        appendGhosts(nodes, layout);
        for (final Instance instance : layout.getInstances()) {
            if (instance.getX() != null) {
                appendNode(nodes, layout, instance, zoom);
            }
        }
    }

    /// @param kind An instance kind.
    /// @return the BEM modifier a node of that kind carries, e.g. `reference-summary`.
    public static String kindModifier(final InstanceKind kind) {
        return kind.name().toLowerCase().replace('_', '-');
    }

    private static void arrowMarker(final HtmlBuilder defs, final String markerId) {
        defs.elem(marker -> marker.elem(PATH,
                        new Attribute("d", "M0,0 L10,5 L0,10 z"),
                        elementClass("arrow-head")),
                MARKER,
                new Attribute("id", markerId),
                elementClass("arrow"),
                new Attribute("viewBox", "0 0 10 10"),
                new Attribute("refX", "9"),
                new Attribute("refY", "5"),
                new Attribute("markerWidth", "7"),
                new Attribute("markerHeight", "7"),
                new Attribute("orient", "auto-start-reverse"));
    }

    private static void appendLanes(final HtmlBuilder lanes, final LayoutResult layout) {
        // Each lane's placed instances, found in one pass
        final Map<Integer, List<Instance>> byLane = new HashMap<>();
        for (final Instance instance : layout.getInstances()) {
            if (instance.getX() != null) {
                byLane.computeIfAbsent(instance.getLane(), k -> new ArrayList<>()).add(instance);
            }
        }
        int index = 0;
        for (final Integer lane : layout.getLanes()) {
            final LaneBox box = layout.getLaneBoxes().get(lane);
            final List<Instance> mine = byLane.getOrDefault(lane, List.of());
            if (box == null || mine.isEmpty()) {
                continue;
            }
            int x0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            boolean collapsed = false;
            for (final Instance instance : mine) {
                x0 = Math.min(x0, instance.getX());
                x1 = Math.max(x1, instance.getX() + layout.dims(instance).w());
                collapsed |= instance.getKind() == InstanceKind.LANE_SUMMARY;
            }
            final int left = x0 - LANE_MARGIN;
            final int right = x1 + LANE_MARGIN;
            final String laneClass = index % 2 == 0
                    ? element("lane")
                    : element("lane") + " " + element("lane") + "--alternate";
            lanes.elem(RECT,
                    new Attribute("class", laneClass),
                    number("x", left),
                    number("y", box.y0()),
                    number("width", right - left),
                    number("height", box.y1() - box.y0()),
                    number("rx", LANE_RADIUS));
            // A collapsed lane is opened by its summary, so has no control of its own.
            if (!collapsed) {
                lanes.elem(control -> appendLaneToggle(control, left, box.y0()),
                        G,
                        elementClass("lane-toggle"),
                        new Attribute(DATA_ACTION, ACTION_COLLAPSE_LANE),
                        new Attribute(DATA_LANE, String.valueOf(lane)));
            }
            index++;
        }
    }

    private static void appendLaneToggle(final HtmlBuilder control, final int left, final int top) {
        control.elem(RECT,
                elementClass("lane-toggle-box"),
                number("x", left + 5),
                number("y", top + 3),
                number("width", 24),
                number("height", 20),
                number("rx", 5));
        control.elem("▴",
                TEXT,
                elementClass("lane-toggle-glyph"),
                number("x", left + 17),
                number("y", top + 17.5),
                new Attribute("text-anchor", "middle"));
    }

    private static void appendEdges(final HtmlBuilder edges, final LayoutResult layout, final String markerId) {
        for (final LayoutEdge edge : layout.getEdges()) {
            final String path = EdgePaths.path(layout, edge);
            if (path == null) {
                continue;
            }
            final StringBuilder classes = new StringBuilder(element("edge"));
            classes.append(' ').append(element("edge")).append("--").append(edgeModifier(edge));
            if (Boolean.TRUE.equals(edge.back())) {
                classes.append(' ').append(element("edge")).append("--back");
            }
            if (edge.ambiguous()) {
                classes.append(' ').append(element("edge")).append("--ambiguous");
            }
            edges.elem(PATH,
                    new Attribute("class", classes.toString()),
                    new Attribute("d", path),
                    new Attribute("marker-end", "url(#" + markerId + ")"),
                    new Attribute(DATA_A, edge.a().getId()),
                    new Attribute(DATA_B, edge.b().getId()));
        }
    }

    private static void appendGhosts(final HtmlBuilder nodes, final LayoutResult layout) {
        final List<Ghost> flowGhosts = new ArrayList<>();
        for (final Ghost ghost : layout.getGhosts()) {
            if (!ghost.referenceRow()) {
                flowGhosts.add(ghost);
            }
            nodes.elem(g -> {
                g.elem(RECT,
                        elementClass("ghost-body"),
                        number("x", ghost.x()),
                        number("y", ghost.y()),
                        number("width", ghost.w()),
                        number("height", ghost.h()),
                        number("rx", NODE_RADIUS));
                g.elem(ghost.label(),
                        TEXT,
                        elementClass("ghost-label"),
                        number("x", ghost.x() + ghost.w() / 2.0),
                        number("y", ghost.y() + ghost.h() / 2.0 + 5),
                        new Attribute("text-anchor", "middle"));
            }, G, elementClass("ghost"));
        }
        // A dashed line along the create slots, where flow would go.
        if (flowGhosts.size() > 1) {
            final StringBuilder d = new StringBuilder();
            for (int i = 0; i < flowGhosts.size() - 1; i++) {
                final Ghost from = flowGhosts.get(i);
                final Ghost to = flowGhosts.get(i + 1);
                if (i > 0) {
                    d.append(' ');
                }
                d.append('M').append(EdgePaths.format(from.x() + from.w()))
                        .append(',').append(EdgePaths.format(from.y() + from.h() / 2.0))
                        .append(" H").append(EdgePaths.format(to.x() - 12));
            }
            nodes.elem(PATH, elementClass("ghost-line"), new Attribute("d", d.toString()));
        }
    }

    private static void appendNode(final HtmlBuilder nodes,
                                   final LayoutResult layout,
                                   final Instance instance,
                                   final double zoom) {
        final Dims dims = layout.dims(instance);
        final boolean small = StructureGraphText.isSmall(instance);
        final boolean summary = StructureGraphText.isSummary(instance);
        final boolean expanded = StructureGraphText.isExpanded(layout, instance);
        final boolean endpoint = instance.getKind() == InstanceKind.ENDPOINT;

        final StringBuilder classes = new StringBuilder(element("node"));
        classes.append(' ').append(element("node")).append("--").append(kindModifier(instance.getKind()));
        if (expanded) {
            classes.append(' ').append(element("node")).append("--expanded");
        }
        if (instance.getNode() != null
            && instance.getNode().getUnresolvedRefs() != null
            && instance.getNode().getUnresolvedRefs() > 0) {
            // Refers to a document that cannot be found: deleted, or a name nothing has
            classes.append(' ').append(element("node")).append("--broken");
        }
        if (instance.getNode() != null && !instance.getNode().isInScope()) {
            // Context from outside the folder.
            classes.append(' ').append(element("node")).append("--context");
        }

        final List<Attribute> attributes = new ArrayList<>();
        attributes.add(new Attribute("class", classes.toString()));
        attributes.add(new Attribute(DATA_ID, instance.getId()));
        attributes.add(new Attribute("transform",
                "translate(" + instance.getX() + "," + instance.getY() + ")"));
        if (summary) {
            attributes.add(new Attribute(DATA_ACTION, ACTION_OPEN_SUMMARY));
        }

        nodes.elem(g -> {
            if (endpoint) {
                g.elem(POLYGON,
                        elementClass("body"),
                        new Attribute("points", hexagon(dims.w(), dims.h())));
            } else {
                g.elem(RECT,
                        elementClass("body"),
                        number("x", 0),
                        number("y", 0),
                        number("width", dims.w()),
                        number("height", dims.h()),
                        number("rx", expanded
                                ? EXPANDED_RADIUS
                                : NODE_RADIUS));
            }

            final double textX;
            if (expanded) {
                textX = 16;
            } else if (small) {
                textX = 42;
            } else {
                textX = 50;
            }
            final double nameY;
            if (small) {
                nameY = 27;
            } else if (expanded) {
                nameY = 26;
            } else {
                nameY = 32;
            }
            g.elem(StructureGraphText.name(layout, instance, zoom),
                    TEXT,
                    elementClass("name"),
                    number("x", textX),
                    number("y", nameY));

            if (!expanded) {
                final String glyphClass = element("glyph") + " " + element("glyph") + "--"
                                          + StructureGraphTypes.modifier(instance.getType());
                g.elem(RECT,
                        new Attribute("class", glyphClass),
                        number("x", small
                                ? 12
                                : 15),
                        number("y", small
                                ? 12
                                : 14),
                        number("width", small
                                ? 21
                                : 25),
                        number("height", small
                                ? 21
                                : 25),
                        number("rx", CHIP_RADIUS));
                g.elem(StructureGraphTypes.glyph(instance.getType()),
                        TEXT,
                        elementClass("glyph-text"),
                        number("x", small
                                ? 22.5
                                : 27.5),
                        number("y", small
                                ? 27
                                : 31.5),
                        new Attribute("text-anchor", "middle"));
            }

            final double metaY;
            if (small) {
                metaY = 46;
            } else if (expanded) {
                metaY = dims.h() - 11;
            } else {
                metaY = 56;
            }
            g.elem(StructureGraphText.meta(layout, instance, zoom),
                    TEXT,
                    elementClass("meta"),
                    number("x", textX),
                    number("y", metaY));

            if (instance.getShared() != null && instance.getShared() > 1) {
                g.elem("×" + instance.getShared(),
                        TEXT,
                        elementClass("badge"),
                        number("x", dims.w() - 13),
                        number("y", 26),
                        new Attribute("text-anchor", "end"));
            }

            if (instance.isExpandable()) {
                g.elem(expanded
                                ? "▴"
                                : "▾",
                        TEXT,
                        elementClass("toggle"),
                        number("x", dims.w() - 26),
                        number("y", expanded
                                ? 26
                                : 27),
                        new Attribute("text-anchor", "middle"));
                g.elem(RECT,
                        elementClass("toggle-hit"),
                        new Attribute(DATA_ACTION, ACTION_EXPAND),
                        new Attribute(DATA_ID, instance.getId()),
                        number("x", dims.w() - 46),
                        number("y", expanded
                                ? 9
                                : 6),
                        number("width", 40),
                        number("height", 30),
                        number("rx", 4));
            }

            if (expanded) {
                final int elementCount = instance.getNode().getElementCount();
                for (int i = 0; i < elementCount; i++) {
                    g.elem(RECT,
                            elementClass("chip"),
                            number("x", 13),
                            number("y", LayoutConstants.EXPANDED_HEADER
                                        + i * (LayoutConstants.EH + LayoutConstants.EXPANDED_CHIP_GAP)),
                            number("width", LayoutConstants.EW),
                            number("height", LayoutConstants.EH),
                            number("rx", CHIP_RADIUS));
                }
            }

            if (summary) {
                g.elem("▾",
                        TEXT,
                        elementClass("toggle"),
                        number("x", dims.w() - 26),
                        number("y", 27),
                        new Attribute("text-anchor", "middle"));
            }
        }, G, attributes.toArray(new Attribute[0]));
    }

    private static String edgeModifier(final LayoutEdge edge) {
        return edge.kind().name().toLowerCase();
    }

    private static String hexagon(final double w, final double h) {
        return EdgePaths.format(HEX_INSET) + ",0 "
               + EdgePaths.format(w - HEX_INSET) + ",0 "
               + EdgePaths.format(w) + "," + EdgePaths.format(h / 2) + " "
               + EdgePaths.format(w - HEX_INSET) + "," + EdgePaths.format(h) + " "
               + EdgePaths.format(HEX_INSET) + "," + EdgePaths.format(h) + " "
               + "0," + EdgePaths.format(h / 2);
    }

    private static Attribute elementClass(final String elementName) {
        return Attribute.className(element(elementName));
    }

    private static String element(final String elementName) {
        return BLOCK + "__" + elementName;
    }

    private static Attribute number(final String name, final double value) {
        return new Attribute(name, EdgePaths.format(value));
    }
}
