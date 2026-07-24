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

package stroom.graphdb.client.presenter;

import stroom.dashboard.client.table.ComponentSelection;
import stroom.dashboard.client.vis.SelectionUiHandlers;
import stroom.graphdb.shared.GraphElementTable;
import stroom.query.api.Column;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.util.shared.NullSafe;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNull;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.web.bindery.event.shared.EventBus;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The Graph DB Data tab's graph view: a {@link GraphFrame} (the Cytoscape sandbox) plus a message overlay.
 * It renders directly from the {@link TableResult} the tab has <em>already</em> fetched - no second search -
 * so the graph and the table share one query (design study §5). Only a {@code RETURN GRAPH} element-row result
 * is renderable; any other shape shows guidance instead of an empty canvas.
 *
 * <p><b>Scope note (P0&ndash;P3):</b> the tab pages its results, so this renders the <em>current page</em> of
 * element rows (a natural, small bound). Fetching the full element set and enforcing an explicit node/edge cap
 * with a warning/error is deferred to P4 (the size guardrail).</p>
 */
public class GraphResultWidget extends Composite implements SelectionUiHandlers {

    /** Param keys a context-menu action uses to ask the parent to act (see {@code ui/graph.js}). Both resolve the
     * node identity-based via the /expand endpoint: expand merges the result in, focus replaces the view with it. */
    private static final String EXPAND_NODE_PARAM = "__stroomExpand";
    private static final String FOCUS_NODE_PARAM = "__stroomFocus";

    private final GraphFrame frame;
    private final SimplePanel graphHolder;
    private final Label messageLabel;
    private final Consumer<String> onExpandNode;
    private final Consumer<String> onFocusNode;

    public GraphResultWidget(final EventBus eventBus,
                             final Consumer<String> onExpandNode,
                             final Consumer<String> onFocusNode) {
        this.onExpandNode = onExpandNode;
        this.onFocusNode = onFocusNode;
        frame = new GraphFrame(eventBus);
        frame.setUiHandlers(this);

        graphHolder = new SimplePanel(frame);
        graphHolder.addStyleName("max GraphResultWidget-graph");

        messageLabel = new Label();
        messageLabel.addStyleName("GraphResultWidget-message");
        messageLabel.setVisible(false);

        final FlowPanel panel = new FlowPanel();
        panel.addStyleName("max GraphResultWidget");
        panel.add(graphHolder);
        panel.add(messageLabel);
        initWidget(panel);
    }

    /**
     * Register this frame's postMessage listener. Call from the owning presenter's {@code onBind}.
     */
    public void bind() {
        frame.bind();
    }

    /**
     * Deregister this frame's postMessage listener. Call from the owning presenter's {@code onUnbind}.
     */
    public void unbind() {
        frame.unbind();
    }

    public void onResize() {
        frame.onResize();
    }

    /**
     * Render a result, or show guidance when it isn't a graph shape.
     */
    public void setData(final TableResult result) {
        if (result == null) {
            frame.clear();
            showMessage("Run a query to see a graph.");
            return;
        }

        final List<Column> columns = NullSafe.list(result.getColumns());
        if (!isElementTable(columns)) {
            frame.clear();
            showMessage("This result is not a graph shape. Switch to the Table view, "
                    + "or use RETURN GRAPH to produce a graph.");
            return;
        }

        showGraph();
        frame.setElements(buildPayload(columns, NullSafe.list(result.getRows())));
        frame.onResize();
    }

    /**
     * A {@code RETURN GRAPH} element table always carries these columns
     * (see {@code CypherToLogicalPlan.ELEMENT_ROW_COLUMNS}).
     */
    private static boolean isElementTable(final List<Column> columns) {
        final Set<String> names = new HashSet<>();
        for (final Column column : columns) {
            if (column.getName() != null) {
                names.add(column.getName().toLowerCase());
            }
        }
        return names.contains("kind")
                && names.contains("id")
                && names.contains("source")
                && names.contains("target");
    }

    private static JSONObject buildPayload(final List<Column> columns, final List<Row> rows) {
        final JSONArray columnArray = new JSONArray();
        for (int i = 0; i < columns.size(); i++) {
            columnArray.set(i, new JSONString(NullSafe.getOrElse(columns.get(i), Column::getName, "")));
        }

        final JSONArray rowArray = new JSONArray();
        for (int r = 0; r < rows.size(); r++) {
            final List<String> values = NullSafe.list(rows.get(r).getValues());
            final JSONArray valueArray = new JSONArray();
            for (int k = 0; k < values.size(); k++) {
                final String value = values.get(k);
                valueArray.set(k, value == null
                        ? JSONNull.getInstance()
                        : new JSONString(value));
            }
            rowArray.set(r, valueArray);
        }

        final JSONObject payload = new JSONObject();
        payload.put("columns", columnArray);
        payload.put("rows", rowArray);
        return payload;
    }

    private void showGraph() {
        messageLabel.setVisible(false);
        graphHolder.setVisible(true);
    }

    private void showMessage(final String message) {
        messageLabel.setText(message);
        messageLabel.setVisible(true);
        graphHolder.setVisible(false);
    }

    @Override
    public void onSelection(final List<ComponentSelection> values) {
        if (values != null && !values.isEmpty()) {
            final ComponentSelection selection = values.get(0);
            // Context-menu actions carry a command param; a plain tap carries neither.
            final String expandNodeId = selection.getParamValue(EXPAND_NODE_PARAM);
            if (expandNodeId != null && onExpandNode != null) {
                onExpandNode.accept(expandNodeId);
                return;
            }
            final String focusNodeId = selection.getParamValue(FOCUS_NODE_PARAM);
            if (focusNodeId != null && onFocusNode != null) {
                onFocusNode.accept(focusNodeId);
                return;
            }
        }
        // TODO(P5): open the tapped element's properties and drive dashboard-style selection linking.
        GWT.log("GraphResultWidget: element selected (" + NullSafe.size(values) + ")");
    }

    /**
     * Merge an "Expand neighbours" result into the already-rendered graph (additive - does not replace the view).
     */
    public void addElements(final GraphElementTable table) {
        final JSONObject payload = toPayload(table);
        if (payload != null) {
            frame.addElements(payload);
        }
    }

    /**
     * Replace the view with a "Focus on this node" result (the node + its neighbours). The /expand endpoint is
     * identity-based, so a focused node always resolves and the result is never empty - it never blanks the graph.
     */
    public void focusElements(final GraphElementTable table) {
        final JSONObject payload = toPayload(table);
        if (payload != null) {
            showGraph();
            frame.setElements(payload);
            frame.onResize();
        }
    }

    private static JSONObject toPayload(final GraphElementTable table) {
        if (table == null || table.rows().isEmpty()) {
            return null;
        }
        final JSONArray columnArray = new JSONArray();
        final List<String> columns = NullSafe.list(table.columns());
        for (int i = 0; i < columns.size(); i++) {
            columnArray.set(i, new JSONString(columns.get(i)));
        }

        final JSONArray rowArray = new JSONArray();
        final List<List<String>> rows = NullSafe.list(table.rows());
        for (int r = 0; r < rows.size(); r++) {
            final List<String> values = NullSafe.list(rows.get(r));
            final JSONArray valueArray = new JSONArray();
            for (int k = 0; k < values.size(); k++) {
                final String value = values.get(k);
                valueArray.set(k, value == null
                        ? JSONNull.getInstance()
                        : new JSONString(value));
            }
            rowArray.set(r, valueArray);
        }

        final JSONObject payload = new JSONObject();
        payload.put("columns", columnArray);
        payload.put("rows", rowArray);
        return payload;
    }
}
