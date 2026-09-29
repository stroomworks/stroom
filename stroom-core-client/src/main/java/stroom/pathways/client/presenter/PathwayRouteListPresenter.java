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

import stroom.config.global.client.presenter.ListDataProvider;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.RouteUse;
import stroom.pathways.shared.pathway.RouteVisit;
import stroom.pathways.shared.pathway.Routes;
import stroom.pathways.shared.pathway.StepsUse;
import stroom.preferences.client.DateTimeFormatter;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The distinct routes traces have taken through one pathway, busiest first.
 *
 * <p>A route is the whole walk — every node the trace reached, in sequence, and which children each
 * one ran. The model shows what the pathway can do; this shows what it actually does, and how often.
 *
 * <p>Busiest first, and the trace count is the last column: what a reader follows across a row is
 * the route itself, and the count is what they land on. A route carrying one or two traces against
 * another's hundreds is usually a first-run lookup or a connection-pool event rather than something
 * the job does, which matters because locking down everything observed would bless them all for ever.
 */
public class PathwayRouteListPresenter extends MyPresenterWidget<PagerView> {

    private static final int COUNT_COL = 90;
    private static final int ROUTE_COL = 700;
    // A trace id is 16 bytes written as hex, so this holds one rather than being measured.
    private static final int TRACE_ID_COL = 270;
    private static final String FILTER_OFF_TITLE = "Filter by selected node";
    private static final String FILTER_ON_TITLE = "Show every route";

    private final DateTimeFormatter dateTimeFormatter;
    private final InlineSvgToggleButton filterButton;
    private final MyDataGrid<RouteUse> dataGrid;
    private final MultiSelectionModelImpl<RouteUse> selectionModel;
    private final ListDataProvider<RouteUse> dataProvider;

    /**
     * The nodes routes name, by the position they name them at, and the path of each. Rebuilt with
     * the data because a route is positions and nothing else; without the model beside it a row
     * cannot say which node it visited.
     */
    private final Map<Integer, PathNode> nodesByPosition = new HashMap<>();

    /**
     * Where a route's walk starts. Held with the nodes above for the same reason: a route says which
     * steps were taken, and only the model says what taking them reaches.
     */
    private PathNode root;

    // Every route of the pathway on show, busiest first, which is not always what the table is given.
    private List<RouteUse> rows = Collections.emptyList();
    // The node picked out on the drawing, which only matters while the filter is on.
    private String selectedNode;

    @Inject
    public PathwayRouteListPresenter(final EventBus eventBus,
                                     final PagerView view,
                                     final DateTimeFormatter dateTimeFormatter) {
        super(eventBus, view);
        this.dateTimeFormatter = dateTimeFormatter;

        // Narrows the table to the routes that ran the node being looked at. On this toolbar rather
        // than on the drawing because it is this table it changes, and the node it works from is
        // picked out on the drawing either way.
        filterButton = new InlineSvgToggleButton();
        filterButton.setSvg(SvgImage.FILTER);
        filterButton.setTitle(FILTER_OFF_TITLE);
        view.addButton(filterButton);

        dataGrid = new MyDataGrid<>(this);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);

        // Held here rather than fetched a page at a time. The routes arrive on the pathway the view
        // around this one already has, so asking the server again would only risk the two disagreeing.
        dataProvider = new ListDataProvider<>();
        dataProvider.addDataDisplay(dataGrid);

        addColumns();
    }

    @Override
    protected void onBind() {
        super.onBind();
        // Follows the button rather than deciding which clicks count, the same as the drawing's own
        // view button: it turns itself over on any click it accepts.
        registerHandler(filterButton.addClickHandler(e -> {
            filterButton.setTitle(filterButton.getState()
                    ? FILTER_ON_TITLE
                    : FILTER_OFF_TITLE);
            show();
        }));
    }

    /**
     * The node picked out on the drawing, so the filter knows which routes to keep. Given rather than
     * asked for, because the drawing and this table are held by the view around them both.
     */
    public void setSelectedNode(final PathNode node) {
        final String uuid = NullSafe.get(node, PathNode::getUuid);
        if (!Objects.equals(uuid, selectedNode)) {
            selectedNode = uuid;
            // Nothing to redo while every route is on show, and a node is clicked far more often than
            // the filter is turned on.
            if (filterButton.getState()) {
                show();
            }
        }
    }

    /**
     * The route being looked at. Held onto by the view around this one so it can pick the nodes out
     * on the drawing.
     */
    public MultiSelectionModelImpl<RouteUse> getSelectionModel() {
        return selectionModel;
    }

    /**
     * Which nodes the selected route ran, as paths, so they can be picked out on the tree and the
     * graph. Empty where nothing is selected.
     */
    public List<List<String>> getSelectedPaths() {
        final List<List<String>> paths = new ArrayList<>();
        for (final PathNode node : nodesIn(selectionModel.getSelected())) {
            if (node.getPath() != null) {
                paths.add(node.getPath());
            }
        }
        return paths;
    }

    // The nodes a route ran, in the order it ran them. Worked out afresh rather than kept beside the
    // row: a pathway holds a handful of routes and a walk of one is a walk of the model, so holding
    // the answer would only be something else to keep true.
    private List<PathNode> nodesIn(final RouteUse route) {
        if (route == null || root == null) {
            return Collections.emptyList();
        }
        final List<PathNode> nodes = new ArrayList<>();
        walk(root, stepsByNode(route), nodes, new HashSet<>());
        return nodes;
    }

    // Which sets of steps each node took, so a walk knows whether a name in a node's steps is
    // somewhere it carries on into or somewhere it stops.
    private Map<String, List<Integer>> stepsByNode(final RouteUse route) {
        final Map<String, List<Integer>> stepsByNode = new HashMap<>();
        for (final RouteVisit visit : NullSafe.list(route.getVisits())) {
            final PathNode node = nodesByPosition.get(visit.getNode());
            if (node != null) {
                stepsByNode.computeIfAbsent(node.getUuid(), k -> new ArrayList<>()).add(visit.getSteps());
            }
        }
        return stepsByNode;
    }

    // Down into each child before moving on to the next, which is the order the work happened in.
    // Reading the visits in the order the route holds them would not: they are gathered by node, so a
    // node's whole subtree would come after every one of its later siblings.
    //
    // A route only names the nodes that had children to run, because what it holds for each is which
    // of their sets of steps they took, and a node with no children has none. The leaves ran all the
    // same, and the steps are where they are named, so the walk reaches them from there.
    private static void walk(final PathNode node,
                             final Map<String, List<Integer>> stepsByNode,
                             final List<PathNode> nodes,
                             final Set<String> done) {
        if (!done.add(node.getUuid())) {
            return;
        }
        nodes.add(node);
        for (final Integer position : NullSafe.list(stepsByNode.get(node.getUuid()))) {
            // Split reads its argument as a pattern, which the separator is safe to be read as.
            for (final String name : steps(node, position).split(StepsUse.SEPARATOR)) {
                final PathNode child = child(node, name);
                if (child != null) {
                    walk(child, stepsByNode, nodes, done);
                }
            }
        }
    }

    private static PathNode child(final PathNode node, final String name) {
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            if (child.getName().equals(name)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Shows the routes of one pathway. Given rather than fetched, for the reason the data provider
     * gives above.
     */
    public void setData(final Pathway pathway) {
        // Whatever was selected belonged to the pathway being replaced, and the view around this one
        // asks what is selected to decide what to pick out.
        selectionModel.clear();
        nodesByPosition.clear();
        root = NullSafe.get(pathway, Pathway::getRoot);
        // Whatever was picked out belonged to the model being replaced, so the filter starts with
        // nothing to work from rather than with a node this pathway may not have.
        selectedNode = null;

        final Routes routes = NullSafe.get(pathway, Pathway::getRoutes);
        if (routes == null) {
            rows = Collections.emptyList();
            show();
            return;
        }

        indexNodes(NullSafe.get(pathway, Pathway::getRoot), routes.getNodes());

        // Busiest first. Nothing else about a route is worth ordering on: what a reader wants to know
        // is what normally happens, and after that what hardly ever does.
        final List<RouteUse> sorted = new ArrayList<>(NullSafe.list(routes.getRoutes()));
        sorted.sort((a, b) -> Long.compare(b.getTimesUsed(), a.getTimesUsed()));
        rows = sorted;
        show();
    }

    // What the table is given: every route, or only those that ran the node picked out on the
    // drawing. With the filter on and nothing picked out there is nothing to narrow by, so everything
    // is shown rather than nothing.
    private void show() {
        if (!filterButton.getState() || selectedNode == null) {
            dataProvider.setCompleteList(rows);
            return;
        }

        final List<RouteUse> kept = new ArrayList<>();
        for (final RouteUse route : rows) {
            if (runs(route, selectedNode)) {
                kept.add(route);
            }
        }

        // A route that has just been taken off the table should not go on picking nodes out on the
        // drawing, where the reader has no row left to click to stop it.
        final RouteUse selected = selectionModel.getSelected();
        if (selected != null && !kept.contains(selected)) {
            selectionModel.clear();
        }
        dataProvider.setCompleteList(kept);
    }

    private boolean runs(final RouteUse route, final String uuid) {
        for (final PathNode node : nodesIn(route)) {
            if (uuid.equals(node.getUuid())) {
                return true;
            }
        }
        return false;
    }

    // Walks the model once, keeping the nodes the routes name. A node a route references that is not
    // in the model has been removed since, and is left out rather than guessed at.
    private void indexNodes(final PathNode root, final List<String> uuids) {
        if (root == null || NullSafe.isEmptyCollection(uuids)) {
            return;
        }
        final Map<String, PathNode> byUuid = new HashMap<>();
        collect(root, byUuid);
        for (int i = 0; i < uuids.size(); i++) {
            final PathNode node = byUuid.get(uuids.get(i));
            if (node != null) {
                nodesByPosition.put(i, node);
            }
        }
    }

    private static void collect(final PathNode node, final Map<String, PathNode> byUuid) {
        byUuid.put(node.getUuid(), node);
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            collect(child, byUuid);
        }
    }

    private void addColumns() {
        addColumn("Created", route -> NullSafe.get(route.getFirstUsedTime(),
                value -> dateTimeFormatter.format(value.toEpochMillis())), ColumnSizeConstants.DATE_COL);
        addColumn("Last Used", route -> NullSafe.get(route.getLastUsedTime(),
                value -> dateTimeFormatter.format(value.toEpochMillis())), ColumnSizeConstants.DATE_COL);
        addColumn("Route", this::routeText, ROUTE_COL);
        addColumn("Created By", RouteUse::getCreatedByTraceId, TRACE_ID_COL);
        addColumn("Traces", route -> Long.toString(route.getTimesUsed()), COUNT_COL);
    }

    // The walk in the sequence it happened, which is the sequence the drawing picks the nodes out in.
    // Starts below the root rather than at it: the root is the pathway, which is named in the dialog
    // this sits in and drawn beside the table, so every row would open with the same words.
    private String routeText(final RouteUse route) {
        final String steps = root == null
                ? ""
                : stepsText(root, stepsByNode(route), new HashSet<>());
        // A route holds a visit only for a node that ran children, so one with no visits at all is a
        // trace where the operation ran and nothing under it did. The root is then the whole of what
        // ran, so it is what the row says — the same as every other row, which says what ran.
        return steps.isEmpty()
                ? NullSafe.getOrElse(root, PathNode::getName, "")
                : steps;
    }

    // The children a node ran, in the order it ran them, each carrying its own in brackets. Nested
    // rather than listed node by node, because what a reader follows is the work in the order it
    // happened, and a list by node puts everything one step from the root ahead of anything further
    // out. A node that ran the same children fifteen times is named once; a node that ran them two
    // different ways carries both, one set after the other.
    private static String stepsText(final PathNode node,
                                    final Map<String, List<Integer>> stepsByNode,
                                    final Set<String> done) {
        if (!done.add(node.getUuid())) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final Integer position : NullSafe.list(stepsByNode.get(node.getUuid()))) {
            // Split reads its argument as a pattern, which the separator is safe to be read as.
            for (final String name : steps(node, position).split(StepsUse.SEPARATOR)) {
                final PathNode child = child(node, name);
                if (child == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(StepsUse.SEPARATOR);
                }
                sb.append(child.getName());
                final String inner = stepsText(child, stepsByNode, done);
                if (!inner.isEmpty()) {
                    sb.append(" (").append(inner).append(")");
                }
            }
        }
        return sb.toString();
    }

    private static String steps(final PathNode node, final int position) {
        final List<StepsUse> stepsUse = NullSafe.list(node.getStepsUse());
        return position >= 0 && position < stepsUse.size()
                ? stepsUse.get(position).getSteps()
                : "";
    }

    private void addColumn(final String name,
                           final Function<RouteUse, String> value,
                           final int width) {
        final Column<RouteUse, String> column = DataGridUtil
                .textColumnBuilder(value)
                .build();
        dataGrid.addResizableColumn(column, name, width);
    }
}
