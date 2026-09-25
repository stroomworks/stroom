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

package stroom.pathways.client.presenter;

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.util.shared.NullSafe;
import stroom.widget.htree.client.treelayout.Point;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.safehtml.shared.SafeHtml;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model as a network: the operation at the centre and everything seen beneath it on rings around
 * it, one ring per level.
 *
 * <p>Says two things the tree cannot. How large a node is drawn is how much has gone through it, so a
 * node one trace reaches forty times stands out from one it reaches once. What colour it is drawn is
 * when it last changed, so the parts of the model still being learnt can be told at a glance from the
 * parts that settled long ago.
 *
 * <p>Edges are drawn in SVG behind markers drawn in HTML, the way the tree draws its curves, so the
 * element a click lands on is an HTML one — selecting puts a class on it, and that does not work on an
 * SVG element.
 */
class PathwayGraphRenderer implements PathwayRenderer {

    private static final int RING = 170;
    // Room outside the outermost nodes. Wider to the sides than above and below, because a name is
    // written beside its node rather than over it: sideways a node reaches its own radius plus the
    // gap plus the width its name is clamped to (26 + 6 + 110 in the stylesheet), while upwards it
    // reaches only its own radius. One margin for both would leave a strip of dead space along the
    // top and bottom that nothing can ever occupy.
    private static final int SIDE_MARGIN = 150;
    private static final int END_MARGIN = 40;
    private static final int MIN_RADIUS = 6;
    private static final int MAX_RADIUS = 26;
    private static final int MIN_EDGE = 1;
    private static final int MAX_EDGE = 15;

    private final Map<String, Boolean> leftOfCentre = new HashMap<>();
    private HtmlBuilder gradients = new HtmlBuilder();
    private int gradientCount;
    // Counted up for every drawing, so the ids one leaves behind cannot be picked up by the next.
    private int drawings;

    // How long ago the node last changed, in steps against the clock rather than a scale running from
    // the oldest change in this model to the newest. A scale within the model has no fixed meaning —
    // its brightest node is the latest of that model, whether that was a minute ago or last week.
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final String NEVER_CHANGED = "#5c6470";
    private static final String NEVER_CHANGED_RING = "#434955";
    private static final String NEVER_CHANGED_LIGHT = "#707781";

    // A colour of its own for each step rather than shades of one. Steps are a handful of named
    // things, not a measurement, so nothing is lost by their colours being unrelated — and four
    // shades of a single colour are what a node 12 pixels across cannot be told apart by.
    //
    // Warm for recent, cool for settled, which is the way round people read heat. Red against green
    // is the pair most often indistinguishable, so the middle is amber and teal rather than green.
    //
    // Ordered by how recent, newest first. Read by both the drawing and its key, so the two cannot
    // come to disagree.
    private static final List<Band> BANDS = List.of(
            new Band(5 * MINUTE, "#e8543f", "#a8341f", "#ea624e", "in the last 5 minutes"),
            new Band(HOUR, "#f0b429", "#a8780c", "#f2bd43", "in the last hour"),
            new Band(DAY, "#22a2a2", "#0f6e6e", "#3dadad", "in the last day"),
            new Band(Long.MAX_VALUE, "#4a7fc1", "#2b5488", "#608ec8", "over a day ago"));

    static final String ZOOM_IN_ID = "pathwayZoomIn";
    static final String ZOOM_OUT_ID = "pathwayZoomOut";
    static final String KEY_ID = "pathwayKeyToggle";
    // Found by id rather than by class: a class it is found by is a class nothing else may add, and
    // showing it adds one.
    static final String KEY_PANEL_ID = "pathwayKey";
    static final String KEY_SHOWN_CLASS = "pathway-graph-key--shown";

    @Override
    public boolean isCentred() {
        // The root sits at the middle of the canvas and the rings grow out around it, so a view
        // starting at the top left would be looking at empty space in a corner.
        return true;
    }

    @Override
    public SafeHtml render(final RenderRequest request) {
        final PathNode shown = NullSafe.get(request.getPathway(), Pathway::getRoot);
        // Placed from every node the model has ever held, so a node arriving does not move the ones
        // around it. What is drawn solidly is still only what the model being shown holds.
        final PathNode root = request.getLayout() == null
                ? shown
                : request.getLayout();
        if (root == null) {
            return new HtmlBuilder().toSafeHtml();
        }

        final Set<String> present = new HashSet<>();
        collect(shown, present);

        final Map<String, NodeChange> changes = request.getChanges();
        final Map<String, NodeUsage> usage = request.getUsage();
        leftOfCentre.clear();
        final Map<String, Point> places = new HashMap<>();
        place(root, 0, 0, 2 * Math.PI, places, 0);

        // Only part of the outermost ring is ever occupied — a branch that goes deep reaches it, the
        // rest stop short — so a canvas sized to the ring is mostly empty. Sized to what was actually
        // placed instead, with room for the names that hang off it.
        final Size size = new Size(places);
        places.replaceAll((uuid, at) -> new Point(at.getX() - size.left, at.getY() - size.top));

        gradients = new HtmlBuilder();
        gradientCount = 0;
        drawings++;
        final HtmlBuilder edges = new HtmlBuilder();
        final HtmlBuilder markers = new HtmlBuilder();
        draw(root, places, edges, markers,
                new Scale(request.getChangeCeiling(), request.getUsageCeiling()),
                request.getAsAt(), changes, present, usage);

        // The gradients come before the lines that point at them.
        final HtmlBuilder painted = new HtmlBuilder();
        painted.elem(defs -> defs.append(gradients.toSafeHtml()), SafeHtmlUtil.from("defs"));
        painted.append(edges.toSafeHtml());

        final HtmlBuilder canvas = new HtmlBuilder();
        canvas.div(d -> d.elem(svg -> svg.append(painted.toSafeHtml()),
                        SafeHtmlUtil.from("svg"),
                        new Attribute("width", String.valueOf(size.width)),
                        new Attribute("height", String.valueOf(size.height)),
                        new Attribute("xmlns", "http://www.w3.org/2000/svg")),
                Attribute.className("pathway-curves"));
        canvas.div(d -> d.append(markers.toSafeHtml()), Attribute.className("pathway-nodes"));

        // Drawn at its own size and scaled by the view. The box around it is what carries the scaled
        // size, because scaling does not change what a thing takes up and the scrollbars would
        // otherwise never know it had grown.
        final HtmlBuilder scaled = new HtmlBuilder();
        scaled.div(d -> d.append(canvas.toSafeHtml()),
                Attribute.className("pathway-graph-canvas"),
                Attribute.style(size.style()));

        final HtmlBuilder hb = new HtmlBuilder();
        hb.div(d -> d.div(inner -> inner.append(scaled.toSafeHtml()),
                        Attribute.className("pathway-graph-sizer"),
                        Attribute.style(size.style())),
                Attribute.className("pathway pathway-graph"));
        // Beside the drawing rather than inside it, so it stays put while the drawing is scrolled and
        // is not scaled along with it when the view is zoomed.
        appendKey(hb);
        appendZoom(hb);
        return hb.toSafeHtml();
    }

    // Over the drawing at the bottom right, where a map puts them. Ids rather than a class, because
    // the view around this one finds them the same way it finds the Node Info panel's own buttons.
    private void appendZoom(final HtmlBuilder hb) {
        hb.div(zoom -> {
            zoom.div("+", Attribute.className("pathway-graph-control"),
                    Attribute.id(ZOOM_IN_ID), Attribute.title("Zoom in"));
            zoom.div("\u2212", Attribute.className("pathway-graph-control"),
                    Attribute.id(ZOOM_OUT_ID), Attribute.title("Zoom out"));
        }, Attribute.className("pathway-graph-controls pathway-graph-zoom"));
    }

    // What the colours mean, kept behind a button: it takes a corner of the drawing to say something
    // that only has to be read once. Built from the same numbers the nodes are coloured with, so it
    // cannot come to say something the drawing does not.
    private void appendKey(final HtmlBuilder hb) {
        hb.div(panel -> {
            appendScale(panel);
            panel.div(controls -> controls.div("i",
                            Attribute.className("pathway-graph-control"),
                            Attribute.id(KEY_ID),
                            Attribute.title("What the colours and sizes mean")),
                    Attribute.className("pathway-graph-controls"));
        }, Attribute.className("pathway-graph-key-panel"));
    }

    private void appendScale(final HtmlBuilder hb) {
        hb.div(key -> {
            key.div("Last updated", Attribute.className("pathway-graph-key-title"));
            BANDS.forEach(band -> appendSwatch(key, band.colour, band.ring, band.label));
            appendSwatch(key, NEVER_CHANGED, NEVER_CHANGED_RING, "never changed");
            key.div("Size shows how many times the node has changed",
                    Attribute.className("pathway-graph-key-note"));
            key.div("Line thickness shows how much goes through it, and its colour when that last "
                    + "happened, on the same scale",
                    Attribute.className("pathway-graph-key-note"));
        }, Attribute.className("pathway-graph-key"), Attribute.id(KEY_PANEL_ID));
    }

    private void appendSwatch(final HtmlBuilder hb,
                              final String colour,
                              final String ring,
                              final String label) {
        hb.div(row -> {
            row.div("", Attribute.className("pathway-graph-key-swatch"),
                    Attribute.style("background-color: " + colour + ";"
                                    + " box-shadow: 0 0 0 2px " + ring + ";"));
            row.div(label, Attribute.className("pathway-graph-key-end"));
        }, Attribute.className("pathway-graph-key-scale"));
    }



    // Each node takes the slice of its parent's arc that its share of the leaves below it comes to, so
    // a branch with more under it is given more room and no two nodes are placed on top of each other.
    private void place(final PathNode node,
                       final int depth,
                       final double from,
                       final double to,
                       final Map<String, Point> places,
                       final int centre) {
        final double angle = (from + to) / 2;
        final int radius = depth * RING;
        places.put(node.getUuid(), new Point(
                centre + (radius * Math.cos(angle)),
                centre + (radius * Math.sin(angle))));
        // Which side of the diagram the node sits on, so its name can be written away from the middle
        // rather than across whatever is next to it.
        leftOfCentre.put(node.getUuid(), Math.cos(angle) < 0);

        final List<PathNode> children = NullSafe.list(node.getChildren());
        if (children.isEmpty()) {
            return;
        }

        final int total = leaves(node);
        double start = from;
        for (final PathNode child : children) {
            final double share = (to - from) * ((double) leaves(child) / total);
            place(child, depth + 1, start, start + share, places, centre);
            start += share;
        }
    }

    private void draw(final PathNode node,
                      final Map<String, Point> places,
                      final HtmlBuilder edges,
                      final HtmlBuilder markers,
                      final Scale scale,
                      final long now,
                      final Map<String, NodeChange> changes,
                      final Set<String> present,
                      final Map<String, NodeUsage> usage) {
        final boolean here = present.contains(node.getUuid());
        final Point at = places.get(node.getUuid());
        final NodeChange change = changes.get(node.getUuid());
        final int radius = scale.radius(change);
        final NanoTime updated = NullSafe.get(change, NodeChange::getLastUpdated);

        final String side = (Boolean.TRUE.equals(leftOfCentre.get(node.getUuid()))
                ? " pathway-graph-node--left"
                : "")
                + (here
                        ? ""
                        : " pathway-graph-node--absent");
        markers.div(marker -> {
            marker.div("", Attribute.className("pathway-graph-dot"),
                    // A shadow rather than a border: the width given here is what says how much the
                    // node has changed, and a border would eat into it. Selecting draws an outline
                    // instead of a second shadow, so the two do not fight over one property.
                    Attribute.style("width: " + (radius * 2) + "px;"
                                    + " height: " + (radius * 2) + "px;"
                                    + " background-color: " + colour(updated, now) + ";"
                                    + " box-shadow: 0 0 0 2px " + ring(updated, now) + ";"));
            marker.div(label -> label.append(node.getName()),
                    Attribute.className("pathway-graph-label"));
        }, Attribute.className("pathway-graph-node" + side),
                new Attribute("uuid", node.getUuid()),
                Attribute.style("left: " + ((int) at.getX() - radius) + "px;"
                                + " top: " + ((int) at.getY() - radius) + "px;"),
                // Both as at the moment being shown, not as they stand now: the reading kept when the
                // model last changed says how much the node had been used by then.
                Attribute.title(node.getName()
                                + " — changed " + NullSafe.getOrElse(change, NodeChange::getCount, 0L)
                                + " times, used " + timesUsed(node, usage) + " times"));

        for (final PathNode child : NullSafe.list(node.getChildren())) {
            final Point childAt = places.get(child.getUuid());
            // As thick as the traffic reaching what it points at, and coloured by how recently that
            // traffic last came through, on the same scale as the nodes. So the thick bright lines
            // are the routes being taken now and the thin dim ones are the routes that have stopped.
            //
            // Drawn between the two circles rather than between their middles. A line runs as wide as
            // its traffic says and a node is as large as its changes say, so a line can be wider than
            // what it joins; ending at the middle, it would show either side of the circle meant to
            // be hiding it. Ending at the rim, neither has to be held back for the other.
            final Point from = toward(at, childAt, radius);
            final Point to = toward(childAt, at, scale.radius(changes.get(child.getUuid())));
            // Lighter where it leaves the parent, full strength where it arrives, so a link reads in
            // the direction the work flows without needing an arrow head on it.
            line(edges,
                    from,
                    to,
                    scale.edge(timesUsed(child, usage)),
                    present.contains(child.getUuid()),
                    gradient(from, to,
                            light(lastUsed(child, usage), now),
                            colour(lastUsed(child, usage), now)));
            draw(child, places, edges, markers, scale, now, changes, present, usage);
        }
    }

    // Straight, not the curves the tree draws: those bend towards a left-to-right layout, and on a
    // ring the bend would point the edge away from the node it joins.
    // A gradient of its own for each link, laid along the line it paints. Along the line rather than
    // across the drawing, which is what gradientUnits="userSpaceOnUse" with the line's own ends does —
    // the default would measure against the shape's box and turn with it.
    private String gradient(final Point from, final Point to, final String start, final String end) {
        final String id = "pathwayEdge" + drawings + "-" + gradientCount++;
        gradients.elem(gradient -> {
            gradient.elem(SafeHtmlUtil.from("stop"),
                    new Attribute("offset", "0"),
                    new Attribute("stop-color", start));
            gradient.elem(SafeHtmlUtil.from("stop"),
                    new Attribute("offset", "1"),
                    new Attribute("stop-color", end));
        }, SafeHtmlUtil.from("linearGradient"),
                new Attribute("id", id),
                new Attribute("gradientUnits", "userSpaceOnUse"),
                new Attribute("x1", String.valueOf((int) from.getX())),
                new Attribute("y1", String.valueOf((int) from.getY())),
                new Attribute("x2", String.valueOf((int) to.getX())),
                new Attribute("y2", String.valueOf((int) to.getY())));
        return "url(#" + id + ")";
    }

    // The point on a node's rim facing the other end of the line, which is where the line starts.
    private static Point toward(final Point from, final Point to, final int radius) {
        final double dx = to.getX() - from.getX();
        final double dy = to.getY() - from.getY();
        final double length = Math.sqrt((dx * dx) + (dy * dy));
        if (length <= 0) {
            return from;
        }
        return new Point(from.getX() + ((dx / length) * radius),
                from.getY() + ((dy / length) * radius));
    }

    private static void line(final HtmlBuilder svg,
                             final Point start,
                             final Point end,
                             final int width,
                             final boolean present,
                             final String colour) {
        svg.elem(SafeHtmlUtil.from("line"),
                new Attribute("x1", String.valueOf((int) start.getX())),
                new Attribute("y1", String.valueOf((int) start.getY())),
                new Attribute("x2", String.valueOf((int) end.getX())),
                new Attribute("y2", String.valueOf((int) end.getY())),
                new Attribute("stroke-width", String.valueOf(width)),
                new Attribute("stroke", colour),
                Attribute.className(present
                        ? "pathway-graph-edge"
                        : "pathway-graph-edge pathway-graph-edge--absent"));
    }

    // What the reading kept at the moment being shown says, or what the node says where there is no
    // reading — which is the case when the current model is on show, there being nothing to wind back.
    private static long timesUsed(final PathNode node, final Map<String, NodeUsage> usage) {
        final NodeUsage reading = usage.get(node.getUuid());
        return reading == null
                ? node.getTimesUsed()
                : reading.getTimesUsed();
    }

    private static NanoTime lastUsed(final PathNode node, final Map<String, NodeUsage> usage) {
        final NodeUsage reading = usage.get(node.getUuid());
        return reading == null
                ? node.getLastUsedTime()
                : reading.getLastUsedTime();
    }

    private static void collect(final PathNode node, final Set<String> uuids) {
        if (node != null) {
            uuids.add(node.getUuid());
            NullSafe.list(node.getChildren()).forEach(child -> collect(child, uuids));
        }
    }

    private static String colour(final NanoTime updated, final long now) {
        final Band band = band(updated, now);
        return band == null
                ? NEVER_CHANGED
                : band.colour;
    }

    private static String ring(final NanoTime updated, final long now) {
        final Band band = band(updated, now);
        return band == null
                ? NEVER_CHANGED_RING
                : band.ring;
    }

    private static String light(final NanoTime updated, final long now) {
        final Band band = band(updated, now);
        return band == null
                ? NEVER_CHANGED_LIGHT
                : band.light;
    }

    // The first band the age falls inside, or null where it is not known.
    private static Band band(final NanoTime updated, final long now) {
        if (updated == null) {
            return null;
        }
        final long age = now - updated.toEpochMillis();
        if (age < 0) {
            // After the moment being shown, which the readings taken as the model changed should have
            // ruled out. Not known rather than brand new.
            return null;
        }
        for (final Band band : BANDS) {
            if (age < band.within) {
                return band;
            }
        }
        return BANDS.get(BANDS.size() - 1);
    }

    private static int leaves(final PathNode node) {
        final List<PathNode> children = NullSafe.list(node.getChildren());
        if (children.isEmpty()) {
            return 1;
        }
        int count = 0;
        for (final PathNode child : children) {
            count += leaves(child);
        }
        return count;
    }






    // --------------------------------------------------------------------------------


    // What the drawing came to, and where it starts. Worked out from the nodes once they are placed,
    // because where a ring is occupied depends on the shape of the model rather than on its depth.
    private static class Size {

        private final int width;
        private final int height;
        private final double left;
        private final double top;

        private Size(final Map<String, Point> places) {
            double minX = 0;
            double maxX = 0;
            double minY = 0;
            double maxY = 0;
            for (final Point at : places.values()) {
                minX = Math.min(minX, at.getX());
                maxX = Math.max(maxX, at.getX());
                minY = Math.min(minY, at.getY());
                maxY = Math.max(maxY, at.getY());
            }
            this.left = minX - SIDE_MARGIN;
            this.top = minY - END_MARGIN;
            this.width = (int) Math.round((maxX - minX) + (SIDE_MARGIN * 2));
            this.height = (int) Math.round((maxY - minY) + (END_MARGIN * 2));
        }

        private String style() {
            return "width: " + width + "px; height: " + height + "px;";
        }
    }


    // --------------------------------------------------------------------------------


    // How the numbers turn into sizes. Scaled against the biggest this model has ever reached rather
    // than a fixed number, so a model whose counts are all small is still readable, and square rooted
    // because the counts run over orders of magnitude — on a straight scale everything but the biggest
    // would be drawn at the minimum.
    private static class Scale {

        private final long mostChanged;
        private final long mostUsed;

        private Scale(final long mostChanged, final long mostUsed) {
            this.mostChanged = mostChanged;
            this.mostUsed = mostUsed;
        }

        private int radius(final NodeChange change) {
            return MIN_RADIUS + step(NullSafe.getOrElse(change, NodeChange::getCount, 0L),
                    mostChanged, MAX_RADIUS - MIN_RADIUS);
        }

        private int edge(final long timesUsed) {
            return MIN_EDGE + step(timesUsed, mostUsed, MAX_EDGE - MIN_EDGE);
        }

        private static int step(final long value, final long most, final int range) {
            if (value <= 0 || most <= 0) {
                return 0;
            }
            return (int) Math.round(Math.sqrt((double) value / most) * range);
        }

    }


    // --------------------------------------------------------------------------------


    // One step of the scale: how recent a change has to be to fall in it, and what it is drawn as.
    private static class Band {

        private final long within;
        private final String colour;
        private final String ring;
        // The same colour lightened, for the end of a link where it leaves its parent. Given rather
        // than worked out, for two reasons: on a dark panel, thinning a colour makes it darker rather
        // than lighter; and the same step toward white does not look the same step on every colour.
        // Red turns pink and washes out well before blue or teal do, so it is lightened less — the
        // aim is a fade that looks alike, not one that measures alike.
        private final String light;
        private final String label;

        private Band(final long within,
                     final String colour,
                     final String ring,
                     final String light,
                     final String label) {
            this.within = within;
            this.colour = colour;
            this.ring = ring;
            this.light = light;
            this.label = label;
        }
    }
}
