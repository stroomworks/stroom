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

package stroom.dependency.client.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Checks a layout for problems no hand-placed drawing could be checked for.
///
/// A layout passes when no instances overlap, none is off the canvas and none is unplaced. Edges that
/// pass through an instance are reported but tolerated, because an edge between lanes can cross another
/// lane. Ported from `audit()` in the reference harness.
public final class LayoutAudit {

    // How many points along each edge are tested against each instance.
    private static final int SAMPLES = 26;
    // How far inside an instance a point must be to count as passing through it.
    private static final double INSET = 5;

    private final List<String> overlap;
    private final List<String> offCanvas;
    private final List<String> through;
    private final List<String> missing;

    private LayoutAudit(final Set<String> overlap,
                        final Set<String> offCanvas,
                        final Set<String> through,
                        final Set<String> missing) {
        this.overlap = Collections.unmodifiableList(new ArrayList<>(overlap));
        this.offCanvas = Collections.unmodifiableList(new ArrayList<>(offCanvas));
        this.through = Collections.unmodifiableList(new ArrayList<>(through));
        this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
    }

    /// @param layout The layout to check.
    /// @return what is wrong with it.
    public static LayoutAudit audit(final LayoutResult layout) {
        final Set<String> overlap = new LinkedHashSet<>();
        final Set<String> offCanvas = new LinkedHashSet<>();
        final Set<String> through = new LinkedHashSet<>();
        final Set<String> missing = new LinkedHashSet<>();

        final List<Instance> instances = layout.getInstances();
        for (int i = 0; i < instances.size(); i++) {
            final Instance a = instances.get(i);
            if (a.getX() == null || a.getY() == null) {
                missing.add(a.getId());
                continue;
            }
            final Dims da = layout.dims(a);
            if (a.getX() < 0 || a.getY() < 0) {
                offCanvas.add(a.getId());
            }
            for (int j = i + 1; j < instances.size(); j++) {
                final Instance b = instances.get(j);
                if (b.getX() == null || b.getY() == null) {
                    continue;
                }
                final Dims db = layout.dims(b);
                final boolean apart = a.getX() + da.w() <= b.getX()
                                      || b.getX() + db.w() <= a.getX()
                                      || a.getY() + da.h() <= b.getY()
                                      || b.getY() + db.h() <= a.getY();
                if (!apart) {
                    overlap.add(a.getId() + " / " + b.getId());
                }
            }
        }

        for (final LayoutEdge edge : layout.getEdges()) {
            final String hit = firstInstanceCrossed(layout, edge);
            if (hit != null) {
                through.add(edge.kind() + " " + edge.a().getId() + "→" + edge.b().getId() + " / " + hit);
            }
        }
        return new LayoutAudit(overlap, offCanvas, through, missing);
    }

    // The id of the first instance the edge passes through, testing points along it in order, or null.
    private static String firstInstanceCrossed(final LayoutResult layout, final LayoutEdge edge) {
        final double[] points = EdgePaths.points(layout, edge);
        if (points == null) {
            return null;
        }
        for (int i = 1; i < SAMPLES; i++) {
            final double[] point = EdgePaths.pointAt(points, (double) i / SAMPLES);
            final double x = point[0];
            final double y = point[1];
            for (final Instance other : layout.getInstances()) {
                if (other.getId().equals(edge.a().getId())
                    || other.getId().equals(edge.b().getId())
                    || other.getX() == null) {
                    continue;
                }
                final Dims dims = layout.dims(other);
                if (x > other.getX() + INSET
                    && x < other.getX() + dims.w() - INSET
                    && y > other.getY() + INSET
                    && y < other.getY() + dims.h() - INSET) {
                    return other.getId();
                }
            }
        }
        return null;
    }

    /// @return pairs of instances that overlap, each as `a / b`.
    public List<String> getOverlap() {
        return overlap;
    }

    /// @return instances placed at a negative position.
    public List<String> getOffCanvas() {
        return offCanvas;
    }

    /// @return edges that pass through an instance, each as `KIND a→b / instance`. Tolerated.
    public List<String> getThrough() {
        return through;
    }

    /// @return instances with no position.
    public List<String> getMissing() {
        return missing;
    }

    /// @return true if nothing overlaps, nothing is off the canvas and everything is placed.
    public boolean isClean() {
        return overlap.isEmpty() && offCanvas.isEmpty() && missing.isEmpty();
    }

    @Override
    public String toString() {
        return "LayoutAudit{"
               + "overlap=" + overlap
               + ", offCanvas=" + offCanvas
               + ", missing=" + missing
               + ", through=" + through.size()
               + '}';
    }
}
