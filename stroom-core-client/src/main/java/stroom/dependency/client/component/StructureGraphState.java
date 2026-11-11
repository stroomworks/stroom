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

import stroom.dependency.client.layout.ChainScope;
import stroom.dependency.client.layout.DetailLevel;
import stroom.dependency.client.layout.GraphLayout;
import stroom.dependency.client.layout.GraphViewport;
import stroom.dependency.client.layout.GraphViewport.FitMode;
import stroom.dependency.client.layout.Instance;
import stroom.dependency.client.layout.InstanceKind;
import stroom.dependency.client.layout.LayoutResult;
import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.GraphNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// What the Structure view shows and how the user has changed it: the graph, its layout, the pan and
/// zoom, which pipelines are expanded, which lanes are pinned open or shut, the selection and the search.
///
/// Holds no GWT or DOM types, so [StructureGraphController]'s decisions are tested on the JVM; the
/// controller turns pointer and key events into calls on this, and draws what it says.
public final class StructureGraphState {

    private static final String REFERENCE_SUMMARY_PREFIX = "refsum:";
    private static final DependencyGraph EMPTY = new DependencyGraph(null, null, null, null, null, null, null);

    private DependencyGraph graph = EMPTY;
    private final Set<String> expanded = new LinkedHashSet<>();
    private final Map<String, Boolean> pins = new LinkedHashMap<>();
    private GraphViewport viewport = new GraphViewport();
    private LayoutResult layout;
    // The current layout's instances by id, and what its notes say, kept with it
    private final Map<String, Instance> instances = new HashMap<>();
    private StructureGraphNotes notes;
    private String selectedId;
    private Set<String> matches = Collections.emptySet();
    // The table lists every document whatever the canvas has collapsed, so it is built from a layout at
    // full detail with no pins: a new graph's first.
    private List<StructureGraphRow> rows;
    private final Map<String, StructureGraphRow> rowsById = new HashMap<>();

    /// Makes a state showing an empty graph.
    public StructureGraphState() {
        setGraph(null);
    }

    /// Shows a new graph, forgetting what the user changed about the last one.
    ///
    /// @param graph The graph; null for an empty one.
    public void setGraph(final DependencyGraph graph) {
        this.graph = graph == null
                ? EMPTY
                : graph;
        expanded.clear();
        pins.clear();
        selectedId = null;
        matches = Collections.emptySet();
        viewport = new GraphViewport();
        setLayout(layoutAt(viewport.getDetailLevel()));
        // A new graph starts at full detail with nothing pinned or expanded, so its first layout is the
        // table's
        rows = StructureGraphRows.of(layout);
        rowsById.clear();
        for (final StructureGraphRow row : rows) {
            rowsById.put(row.id(), row);
        }
    }

    /// @return the table's rows: every document as the graph draws it at full detail, in reading order.
    public List<StructureGraphRow> getRows() {
        return rows;
    }

    /// @param rowId A row's id.
    /// @return the row, or null if there is none with that id.
    public StructureGraphRow row(final String rowId) {
        return rowId == null
                ? null
                : rowsById.get(rowId);
    }

    /// @param rowId A row's id.
    /// @return the id of what the canvas draws for the row: its node, or the summary its node is folded
    /// into; null if neither is drawn.
    public String canvasIdFor(final String rowId) {
        if (instance(rowId) != null) {
            return rowId;
        }
        final StructureGraphRow row = row(rowId);
        if (row == null) {
            return null;
        }
        final String laneSummary = GraphLayout.lanePinKey(row.lane());
        if (instance(laneSummary) != null) {
            return laneSummary;
        }
        final String referenceSummary = REFERENCE_SUMMARY_PREFIX + row.lane();
        if (instance(referenceSummary) != null && row.kind() != InstanceKind.FLOW) {
            return referenceSummary;
        }
        return null;
    }

    /// @param canvasId The id of a node on the canvas.
    /// @return the id of its row, or null for a summary, which has none.
    public String rowIdFor(final String canvasId) {
        return row(canvasId) == null
                ? null
                : canvasId;
    }

    /// @return the graph shown.
    public DependencyGraph getGraph() {
        return graph;
    }

    /// @return the current layout.
    public LayoutResult getLayout() {
        return layout;
    }

    /// @return the current pan and zoom.
    public GraphViewport getViewport() {
        return viewport;
    }

    /// Fits the graph to the canvas, laying it out again if the zoom that fits calls for another detail
    /// level.
    ///
    /// @param viewWidth  The canvas width, in pixels.
    /// @param viewHeight The canvas height, in pixels.
    /// @param mode       How to fit.
    /// @return true if it was laid out again, so must be drawn again.
    public boolean fit(final double viewWidth, final double viewHeight, final FitMode mode) {
        final LayoutResult settled = viewport.fitAndSettle(layout, this::layoutAt, viewWidth, viewHeight, mode);
        final boolean changed = settled != layout;
        if (changed) {
            setLayout(settled);
            dropLostSelection();
        }
        return changed;
    }

    /// Zooms as a mouse wheel does, about the pointer.
    ///
    /// @param x      The pointer's horizontal position on the canvas.
    /// @param y      The pointer's vertical position on the canvas.
    /// @param deltaY The wheel's movement in pixels. Positive zooms out.
    /// @return true if the zoom crossed a detail threshold, so the graph was laid out again and must be drawn
    /// again; false if only the view moved.
    public boolean zoomByWheel(final double x, final double y, final double deltaY) {
        final DetailLevel before = viewport.getDetailLevel();
        viewport.zoomByWheel(x, y, deltaY);
        return relayoutIfDetailChanged(before);
    }

    /// Zooms by a factor about a point, as the keyboard does about the canvas's centre.
    ///
    /// @param x      The horizontal position to zoom about.
    /// @param y      The vertical position to zoom about.
    /// @param factor How much to zoom by: above 1 zooms in.
    /// @return true if the graph was laid out again and must be drawn again.
    public boolean zoomBy(final double x, final double y, final double factor) {
        final DetailLevel before = viewport.getDetailLevel();
        viewport.zoomAbout(x, y, viewport.getZoom() * factor);
        return relayoutIfDetailChanged(before);
    }

    /// Expands a pipeline to show its elements, or collapses it again.
    ///
    /// @param id The pipeline node's id.
    public void toggleExpanded(final String id) {
        if (!expanded.remove(id)) {
            expanded.add(id);
        }
        relayout();
    }

    /// Folds a lane into one summary, whatever the zoom, until it is opened again.
    ///
    /// @param lane The lane.
    public void collapseLane(final int lane) {
        pins.put(GraphLayout.lanePinKey(lane), true);
        selectedId = null;
        relayout();
    }

    /// Opens what a summary folds, whatever the zoom, until it is collapsed again.
    ///
    /// @param id The summary's id.
    /// @return true if `id` was a summary and it was opened.
    public boolean openSummary(final String id) {
        final Instance summary = instance(id);
        if (summary == null) {
            return false;
        }
        if (summary.getKind() == InstanceKind.LANE_SUMMARY) {
            pins.put(GraphLayout.lanePinKey(summary.getLane()), false);
        } else if (summary.getKind() == InstanceKind.REFERENCE_SUMMARY) {
            pins.put(GraphLayout.refsPinKey(summary.getLane()), false);
        } else {
            return false;
        }
        selectedId = null;
        relayout();
        return true;
    }

    /// Selects a node. Selecting the selected node again keeps it selected, so the two clicks of a
    /// double click, which opens a node, leave it selected.
    ///
    /// @param id The node's id, or null to clear the selection. An id the layout has no node for clears it.
    public void setSelectedId(final String id) {
        selectedId = instance(id) == null
                ? null
                : id;
    }

    /// @return the selected node's id, or null.
    public String getSelectedId() {
        return selectedId;
    }

    /// @return the ids of the selected node's chain, to highlight while the rest dims; null if nothing is
    /// selected.
    public Set<String> getScope() {
        return selectedId == null
                ? null
                : ChainScope.of(layout, selectedId);
    }

    /// Finds the nodes whose names contain some text, ignoring case.
    ///
    /// @param text The text; null or blank to clear the search.
    /// @return the ids of the nodes that match, in drawing order.
    public Set<String> search(final String text) {
        final String wanted = text == null
                ? ""
                : text.trim().toLowerCase();
        final Set<String> found = new LinkedHashSet<>();
        if (!wanted.isEmpty()) {
            for (final Instance instance : layout.getInstances()) {
                if (instance.getName() != null && instance.getName().toLowerCase().contains(wanted)) {
                    found.add(instance.getId());
                }
            }
        }
        matches = found;
        return matches;
    }

    /// @return the ids of the nodes the current search matches.
    public Set<String> getMatches() {
        return matches;
    }

    /// @param id A node's id.
    /// @return the node, or null if the layout has none with that id.
    public Instance instance(final String id) {
        return id == null
                ? null
                : instances.get(id);
    }

    /// @param instance A node.
    /// @return what the hover card says about it, a line each, under its name.
    public List<String> describe(final Instance instance) {
        final List<String> lines = new ArrayList<>();
        if (instance.getKind() == InstanceKind.LANE_SUMMARY) {
            lines.add("A collapsed lane of " + StructureGraphNotes.plural(instance.getCount(), "document"));
            lines.add("Click to open it");
            return lines;
        }
        if (instance.getKind() == InstanceKind.REFERENCE_SUMMARY) {
            lines.add(StructureGraphNotes.plural(instance.getCount(), "referenced document") + ", collapsed");
            lines.add("Click to open them");
            return lines;
        }
        lines.add(StructureGraphTypes.label(instance.getType()));
        lines.addAll(notes.of(instance));
        return lines;
    }

    /// @return what the graph shows, in words, to name the drawing for a screen reader.
    public String summary() {
        // A pipeline is drawn once per source and destination, so its nodes are not all documents
        final Set<String> uuids = new HashSet<>();
        for (final GraphNode node : graph.getNodes()) {
            if (node.getDocRef() != null) {
                uuids.add(node.getDocRef().getUuid());
            }
        }
        final int documents = uuids.size();
        if (documents == 0) {
            return "Structure of the folder: it has no documents";
        }
        return "Structure of the folder: " + StructureGraphNotes.plural(documents, "document") + " in "
               + StructureGraphNotes.plural(layout.getLanes().size(), "lane");
    }

    /// @return a note to show over the graph, e.g. that it was cut short; null for none.
    public String note() {
        return graph.isNodeLimitReached()
                ? "This folder has too many documents to show them all; only the first "
                  + graph.getNodes().size() + " are shown."
                : null;
    }

    private LayoutResult layoutAt(final DetailLevel detail) {
        return GraphLayout.layout(graph, expanded, detail, pins);
    }

    private void setLayout(final LayoutResult layout) {
        this.layout = layout;
        instances.clear();
        for (final Instance instance : layout.getInstances()) {
            instances.put(instance.getId(), instance);
        }
        notes = new StructureGraphNotes(layout);
    }

    // Lays out again at the detail level drawn now. That is not always the zoom's: a fit can settle on a
    // zoom whose level differs from the one it laid out at (it lays out again at most once), and an
    // expand or a collapse must not change the detail level with it.
    private void relayout() {
        relayoutAt(layout.getDetail());
    }

    private void relayoutAt(final DetailLevel detail) {
        setLayout(layoutAt(detail));
        dropLostSelection();
    }

    // A zoom that crosses a threshold lays out at the new level.
    private boolean relayoutIfDetailChanged(final DetailLevel before) {
        final DetailLevel after = viewport.getDetailLevel();
        if (after == before || after == layout.getDetail()) {
            return false;
        }
        relayoutAt(after);
        return true;
    }

    // A collapse can fold the selected node into a summary, so it is no longer there to select.
    private void dropLostSelection() {
        if (selectedId != null && instance(selectedId) == null) {
            selectedId = null;
        }
    }

}
