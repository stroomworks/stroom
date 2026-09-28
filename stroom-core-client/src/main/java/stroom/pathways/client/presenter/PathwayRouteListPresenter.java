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
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private final DateTimeFormatter dateTimeFormatter;
    private final MyDataGrid<RouteUse> dataGrid;
    private final MultiSelectionModelImpl<RouteUse> selectionModel;
    private final ListDataProvider<RouteUse> dataProvider;

    /**
     * The nodes routes name, by the position they name them at, and the path of each. Rebuilt with
     * the data because a route is positions and nothing else; without the model beside it a row
     * cannot say which node it visited.
     */
    private final Map<Integer, PathNode> nodesByPosition = new HashMap<>();

    @Inject
    public PathwayRouteListPresenter(final EventBus eventBus,
                                     final PagerView view,
                                     final DateTimeFormatter dateTimeFormatter) {
        super(eventBus, view);
        this.dateTimeFormatter = dateTimeFormatter;

        dataGrid = new MyDataGrid<>(this);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        view.setDataWidget(dataGrid);

        // Held here rather than fetched a page at a time. The routes arrive on the pathway the view
        // around this one already has, so asking the server again would only risk the two disagreeing.
        dataProvider = new ListDataProvider<>();
        dataProvider.addDataDisplay(dataGrid);

        addColumns();
    }

    /**
     * The route being looked at. Held onto by the view around this one so it can pick the nodes out
     * on the drawing.
     */
    public MultiSelectionModelImpl<RouteUse> getSelectionModel() {
        return selectionModel;
    }

    /**
     * Which nodes the selected route visited, as paths, so they can be picked out on the tree and the
     * graph. Empty where nothing is selected.
     */
    public List<List<String>> getSelectedPaths() {
        final RouteUse selected = selectionModel.getSelected();
        if (selected == null) {
            return Collections.emptyList();
        }

        final List<List<String>> paths = new ArrayList<>();
        for (final RouteVisit visit : selected.getVisits()) {
            final PathNode node = nodesByPosition.get(visit.getNode());
            final List<String> path = NullSafe.get(node, PathNode::getPath);
            if (path != null && !paths.contains(path)) {
                paths.add(path);
            }
        }
        return paths;
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

        final Routes routes = NullSafe.get(pathway, Pathway::getRoutes);
        if (routes == null) {
            dataProvider.setCompleteList(Collections.emptyList());
            return;
        }

        indexNodes(NullSafe.get(pathway, Pathway::getRoot), routes.getNodes());

        // Busiest first. Nothing else about a route is worth ordering on: what a reader wants to know
        // is what normally happens, and after that what hardly ever does.
        final List<RouteUse> rows = new ArrayList<>(NullSafe.list(routes.getRoutes()));
        rows.sort((a, b) -> Long.compare(b.getTimesUsed(), a.getTimesUsed()));
        dataProvider.setCompleteList(rows);
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

    // The walk in the sequence it happened, each node and set of steps named once. A node that ran
    // the same children fifteen times is one entry; a node that ran them two different ways is two.
    private String routeText(final RouteUse route) {
        final StringBuilder sb = new StringBuilder();
        for (final RouteVisit visit : NullSafe.list(route.getVisits())) {
            final PathNode node = nodesByPosition.get(visit.getNode());
            if (node == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("  |  ");
            }
            sb.append(node.getName()).append(": ").append(steps(node, visit.getSteps()));
        }
        return sb.length() == 0
                ? "Nothing below the root"
                : sb.toString();
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
