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
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.RouteStep;
import stroom.pathways.shared.pathway.RouteUse;
import stroom.pathways.shared.pathway.Routes;
import stroom.preferences.client.DateTimeFormatter;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortList;
import com.google.gwt.user.cellview.client.ColumnSortList.ColumnSortInfo;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    // What separates one step from the next on show. A reading choice rather than anything the stored
    // route depends on, which holds its steps as a tree.
    private static final String STEP_SEPARATOR = " > ";
    // What separates one run from the next where several happened at the same time. Different from the
    // step separator because what it joins did not follow on from what came before it.
    private static final String RUN_SEPARATOR = " | ";
    // What separates turns of one node from each other. Different from the step separator because
    // they did not follow on from one another: turns of a node are read in an order settled by what
    // they are rather than by when they happened, whether they were taken by one thread or shared
    // between several.
    private static final String TURN_SEPARATOR = ", ";

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
    // What each node is called in the Route column. The schema a database span carries, and the class
    // a code span carries, are the same down most of a route, so dropping them fits far more of the
    // route in the column. A name is only shortened where nothing else in the pathway shortens to the
    // same thing, so two nodes can never come to read alike.
    private final Map<Integer, String> shortNames = new HashMap<>();

    /**
     * Where a route's walk starts. Held with the nodes above for the same reason: a route says which
     * steps were taken, and only the model says what taking them reaches.
     */
    private PathNode root;

    /**
     * Every shape the routes are built from, by the position they name it at. Held with the nodes
     * above and for the same reason: a route is positions, and only the pathway beside it says what
     * they reach.
     */
    private List<RouteStep> shapes = Collections.emptyList();

    // Every route of the pathway on show, busiest first, which is not always what the table is given.
    private List<RouteUse> rows = Collections.emptyList();
    // The node picked out on the drawing, which only matters while the filter is on.
    private String selectedNode;
    // How to order the rows for each column that offers it, and the one ordered on where nothing has
    // been chosen.
    private final Map<Column<?, ?>, Comparator<RouteUse>> orders = new HashMap<>();
    private Column<RouteUse, String> tracesColumn;

    /**
     * What each route comes to when it is walked, worked out the first time it is wanted and kept.
     * A walk is a walk of the whole model, and the same answers are asked for over and over — the
     * grid asks for a route's text on every draw of every visible row, and ordering on it asks twice
     * for each pair it puts in order. Emptied with the data, which is the only thing that changes it.
     */
    private final Map<RouteUse, String> texts = new HashMap<>();
    private final Map<RouteUse, List<List<PathNode>>> walks = new HashMap<>();
    // Which run of work happening at the same time each moment belongs to, numbered as the runs are
    // reached throughout the route, and zero for a moment that was not part of one. Held beside the
    // walk rather than in it because only the drawing wants it.
    private final Map<RouteUse, List<Integer>> walkRuns = new HashMap<>();
    // Where each run of work that happened at the same time begins: the moment it starts, against the
    // run it is. Held per route, and only for routes whose walk has been worked out.
    private final Map<RouteUse, List<int[]>> walkRunStarts = new HashMap<>();
    private final Map<RouteUse, Integer> stepCounts = new HashMap<>();

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
        registerHandler(dataGrid.addColumnSortHandler(e -> order()));
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
    public List<List<List<String>>> getSelectedPaths() {
        return pathsIn(selectionModel.getSelected());
    }

    /**
     * Which run of work happening at the same time each moment of the selected route belongs to,
     * numbered as the runs are reached throughout the route, and zero where the moment was not part of
     * one. One entry per moment, so it reads alongside {@link #getSelectedPaths()}.
     *
     * <p>Runs are walked one after another rather than together, so a reader can follow one before the
     * next begins. This is what says they were not a sequence.
     */
    public List<Integer> getSelectedRuns() {
        final RouteUse route = selectionModel.getSelected();
        if (route == null || root == null) {
            return Collections.emptyList();
        }
        momentsIn(route);
        return NullSafe.list(walkRuns.get(route));
    }

    /**
     * Where each run of work that happened at the same time begins, as the node the walk arrives at
     * first in that run, against which run it is and the moment it starts. By node uuid, because the
     * line into a node is how the drawing finds the place to say it.
     *
     * <p>A node can open more than one run — two runs that began by doing the same thing — so a uuid
     * can carry several, each at its own moment.
     */
    public Map<String, List<int[]>> getSelectedRunStarts() {
        final RouteUse route = selectionModel.getSelected();
        if (route == null || root == null) {
            return Collections.emptyMap();
        }
        final List<List<PathNode>> moments = momentsIn(route);
        final Map<String, List<int[]>> starts = new LinkedHashMap<>();
        for (final int[] start : NullSafe.list(walkRunStarts.get(route))) {
            final int moment = start[0];
            final int run = start[1];
            if (moment < 0 || moment >= moments.size() || moments.get(moment).isEmpty()) {
                continue;
            }
            starts.computeIfAbsent(moments.get(moment).get(0).getUuid(), k -> new ArrayList<>())
                    .add(new int[]{run, moment});
        }
        return starts;
    }

    // The paths the drawing lights, gathered by the moment they light at. Everything in one entry
    // lights at once, so work that happened at the same time is shown happening at the same time.
    private List<List<List<String>>> pathsIn(final RouteUse route) {
        final List<List<List<String>>> moments = new ArrayList<>();
        for (final List<PathNode> nodes : momentsIn(route)) {
            final List<List<String>> paths = new ArrayList<>(nodes.size());
            for (final PathNode node : nodes) {
                if (node.getPath() != null) {
                    paths.add(node.getPath());
                }
            }
            moments.add(paths);
        }
        return moments;
    }

    // How many moments the drawing steps through, which is what the Steps column says. Not the same as
    // how many nodes ran: work repeated is held as one step however many times it went round, and the
    // runs of a moment are stepped through one after another, so each of them adds its own.
    private int steps(final RouteUse route) {
        Integer count = stepCounts.get(route);
        if (count == null) {
            count = momentsIn(route).size();
            stepCounts.put(route, count);
        }
        return count;
    }

    // Whether this route ran the given node at all.
    private boolean runs(final RouteUse route, final String uuid) {
        for (final List<PathNode> moment : momentsIn(route)) {
            for (final PathNode node : moment) {
                if (uuid.equals(node.getUuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    // What the route ran, by the moment it ran it.
    private List<List<PathNode>> momentsIn(final RouteUse route) {
        if (route == null || root == null) {
            return Collections.emptyList();
        }
        List<List<PathNode>> moments = walks.get(route);
        if (moments == null) {
            moments = new ArrayList<>();
            final List<Integer> runs = new ArrayList<>();
            final List<int[]> starts = new ArrayList<>();
            walk(route.getRoot(), moments, runs, starts, 0, 0);
            walks.put(route, moments);
            walkRuns.put(route, runs);
            walkRunStarts.put(route, starts);
        }
        return moments;
    }

    // Down into each child before moving on to the next, which is the order the work happened in, and
    // says how many moments the shape took so the one after it knows where to start.
    //
    // Runs that happened at the same time are walked one after another rather than together, because
    // several lines lighting at once is hard to follow. Each moment is marked with the run it belongs
    // to so the drawing can say which one is being watched; that mark is the only thing left saying
    // they did not follow on from one another. Runs are numbered as they are reached, throughout the
    // route, so the numbers only ever count up.
    private int walk(final int shape,
                     final List<List<PathNode>> moments,
                     final List<Integer> runs,
                     final List<int[]> starts,
                     final int at,
                     final int run) {
        final RouteStep step = shapeAt(shape);
        if (step == null) {
            return 0;
        }

        int used = 0;
        // Only a node is somewhere the trace reached; a shape holding runs lights nothing of its own.
        if (step.getNode() >= 0) {
            final PathNode node = nodesByPosition.get(step.getNode());
            if (node != null) {
                lightAt(moments, runs, at, node, run);
                used = 1;
            }
        }

        if (step.isConcurrent()) {
            int after = 0;
            for (final Integer child : NullSafe.list(step.getSteps())) {
                // Where a run came down to turns of one node, each turn is what one of the threads
                // did, and the run holding them is all that is left of the work being shared out.
                // So the turns are numbered rather than the run they were gathered into, which
                // would otherwise leave a lone 1 over work several threads took a hand in. Turns
                // the same as one another are held as one, so this counts the turns, not the
                // threads.
                final RouteStep held = shapeAt(child);
                final List<Integer> strands = held != null && turnsOfOneNode(held)
                        ? NullSafe.list(held.getSteps())
                        : Collections.singletonList(child);
                for (final Integer strand : strands) {
                    // Counted across the whole route rather than within the moment it belongs to.
                    // Work happening at the same time can hold work that does too, and numbering
                    // each lot from one would put a 1 after a 2 and leave a reader with no way of
                    // telling which lot either belonged to. Counting up throughout says only what
                    // the badge has to say, which is that this is a strand apart from the one
                    // before it.
                    final int which = starts.size() + 1;
                    starts.add(new int[]{at + used + after, which});
                    after += walk(strand, moments, runs, starts, at + used + after, which);
                }
            }
            return used + after;
        }

        for (final Integer child : NullSafe.list(step.getSteps())) {
            used += walk(child, moments, runs, starts, at + used, run);
        }
        return used;
    }

    private static void lightAt(final List<List<PathNode>> moments,
                                final List<Integer> runs,
                                final int at,
                                final PathNode node,
                                final int run) {
        while (moments.size() <= at) {
            moments.add(new ArrayList<>());
            runs.add(0);
        }
        // A moment is reached by one run only, because the runs are walked one after another, so the
        // first to say which it is says it for the moment.
        if (runs.get(at) == 0) {
            runs.set(at, run);
        }
        moments.get(at).add(node);
    }

    // A shape is written after the shapes it is made of, so its steps are always earlier in the list
    // than it is and following them cannot come back round. A step naming a shape that is not there
    // belongs to a pathway written by another build, and is left rather than guessed at.
    private RouteStep shapeAt(final int shape) {
        return shape >= 0 && shape < shapes.size()
                ? shapes.get(shape)
                : null;
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
        shortNames.clear();
        // What was worked out about the routes of the pathway being replaced says nothing about this
        // one's, and the walk they came from starts at a root that is about to change.
        texts.clear();
        walks.clear();
        walkRuns.clear();
        walkRunStarts.clear();
        stepCounts.clear();
        root = NullSafe.get(pathway, Pathway::getRoot);
        shapes = Collections.emptyList();
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
        shapes = NullSafe.list(routes.getSteps());

        rows = new ArrayList<>(NullSafe.list(routes.getRoutes()));
        order();
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
        nameNodes();
    }

    // Shortens every node name that stays its own after shortening, and leaves the rest in full. One
    // name reaching the model twice is still one name, so what disqualifies a shortening is two
    // different names coming to the same thing, not the same name arriving again.
    private void nameNodes() {
        shortNames.clear();
        final Map<String, String> firstSeen = new HashMap<>();
        final Set<String> ambiguous = new HashSet<>();
        for (final PathNode node : nodesByPosition.values()) {
            final String shortened = shorten(node.getName());
            final String seen = firstSeen.putIfAbsent(shortened, node.getName());
            if (seen != null && !seen.equals(node.getName())) {
                ambiguous.add(shortened);
            }
        }
        for (final Map.Entry<Integer, PathNode> entry : nodesByPosition.entrySet()) {
            final String shortened = shorten(entry.getValue().getName());
            shortNames.put(entry.getKey(), ambiguous.contains(shortened)
                    ? entry.getValue().getName()
                    : shortened);
        }
    }

    // A database span is named for the operation and the table, a code span for the class and the
    // method. Either way what comes before the last dot is shared with the names around it and what
    // comes after it is the part that tells them apart. A name holding a quote is left alone: the dot
    // in it belongs to a value rather than to a name, and cutting there would mangle it.
    static String shorten(final String name) {
        final int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1 || name.indexOf('\'') >= 0 || name.indexOf('"') >= 0) {
            return name;
        }
        final int space = name.lastIndexOf(' ', dot);
        return space < 0
                ? name.substring(dot + 1)
                : name.substring(0, space + 1) + name.substring(dot + 1);
    }

    private static void collect(final PathNode node, final Map<String, PathNode> byUuid) {
        byUuid.put(node.getUuid(), node);
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            collect(child, byUuid);
        }
    }

    private void addColumns() {
        addColumn("Created", route -> NullSafe.get(route.getFirstUsedTime(),
                        value -> dateTimeFormatter.format(value.toEpochMillis())),
                ColumnSizeConstants.DATE_COL,
                (a, b) -> compare(a.getFirstUsedTime(), b.getFirstUsedTime()));
        addColumn("Last Used", route -> NullSafe.get(route.getLastUsedTime(),
                        value -> dateTimeFormatter.format(value.toEpochMillis())),
                ColumnSizeConstants.DATE_COL,
                (a, b) -> compare(a.getLastUsedTime(), b.getLastUsedTime()));
        addColumn("Route", this::text, ROUTE_COL,
                (a, b) -> compare(text(a), text(b)));
        addColumn("Created By", RouteUse::getCreatedByTraceId, TRACE_ID_COL,
                (a, b) -> compare(a.getCreatedByTraceId(), b.getCreatedByTraceId()));
        addColumn("Steps", route -> Integer.toString(steps(route)), COUNT_COL,
                (a, b) -> Integer.compare(steps(a), steps(b)));
        tracesColumn = addColumn("Traces", route -> Long.toString(route.getTimesUsed()), COUNT_COL,
                (a, b) -> Long.compare(a.getTimesUsed(), b.getTimesUsed()));
        busiestFirst();
    }

    // Busiest first unless the grid has been told otherwise. What a reader wants to know is what
    // normally happens, and after that what hardly ever does.
    private void busiestFirst() {
        final ColumnSortList sortList = dataGrid.getColumnSortList();
        sortList.clear();
        sortList.push(new ColumnSortInfo(tracesColumn, false));
    }

    // Whatever the grid has been told to order on, or the busiest first where it has been told
    // nothing it knows about.
    private void order() {
        Comparator<RouteUse> order = orders.get(tracesColumn);
        boolean ascending = false;

        final ColumnSortList sortList = dataGrid.getColumnSortList();
        if (sortList != null && sortList.size() > 0) {
            final ColumnSortInfo info = sortList.get(0);
            final Comparator<RouteUse> chosen = orders.get(info.getColumn());
            if (chosen != null) {
                order = chosen;
                ascending = info.isAscending();
            }
        }

        final Comparator<RouteUse> chosen = order;
        final boolean up = ascending;
        final List<RouteUse> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> up
                ? chosen.compare(a, b)
                : chosen.compare(b, a));
        rows = sorted;
        show();
    }

    private static int compare(final NanoTime a, final NanoTime b) {
        if (a == null || b == null) {
            return a == b
                    ? 0
                    : (a == null
                            ? -1
                            : 1);
        }
        return a.compareTo(b);
    }

    private static int compare(final String a, final String b) {
        if (a == null || b == null) {
            return a == b
                    ? 0
                    : (a == null
                            ? -1
                            : 1);
        }
        return a.compareTo(b);
    }

    private String text(final RouteUse route) {
        String text = texts.get(route);
        if (text == null) {
            text = routeText(route);
            texts.put(route, text);
        }
        return text;
    }

    // The walk in the sequence it happened, which is the sequence the drawing picks the nodes out in.
    // Starts below the root rather than at it: the root is the pathway, which is named in the dialog
    // this sits in and drawn beside the table, so every row would open with the same words.
    private String routeText(final RouteUse route) {
        final RouteStep step = shapeAt(route.getRoot());
        final String steps = step == null
                ? ""
                : ran(step);
        // A shape with no steps is a node that ran nothing, so a root with none is a trace where the
        // operation ran and nothing under it did. The root is then the whole of what ran, so it is
        // what the row says — the same as every other row, which says what ran.
        return steps.isEmpty()
                ? NullSafe.getOrElse(root, PathNode::getName, "")
                : steps;
    }

    // One shape as it reads: a name on its own where the node ran nothing, and the name with what it
    // ran in brackets where it ran something. Nested rather than listed node by node, because what a
    // reader follows is the work in the order it happened, and a list by node puts everything one step
    // from the root ahead of anything further out.
    private String stepText(final int shape) {
        final RouteStep step = shapeAt(shape);
        if (step == null) {
            return "";
        }
        if (step.isConcurrent()) {
            // Runs that happened at the same time, held in braces and parted by the run separator.
            // Read in an order settled by what they are rather than by which thread got there first,
            // so a reader must not follow them as a sequence.
            final StringBuilder sb = new StringBuilder();
            for (final Integer run : NullSafe.list(step.getSteps())) {
                final String text = stepText(run);
                if (text.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(RUN_SEPARATOR);
                }
                sb.append(text);
            }
            return "{" + sb + "}";
        }
        final String inner = ran(step);
        if (step.isRun()) {
            return inner;
        }
        if (step.isUnfinished()) {
            // Work that ran over and over and stopped part way through the last time, held in square
            // brackets. Work that repeated and did finish is simply said once with nothing to mark
            // it, because how much there was to do is the workload rather than the path through the
            // code. Each of the three brackets means one thing and nothing else: round for what a
            // node ran, braces for work at the same time, square for a run that stopped part way.
            return "[" + inner + "]";
        }
        final String name = shortNames.get(step.getNode());
        if (name == null) {
            return "";
        }
        return inner.isEmpty()
                ? name
                : name + " (" + inner + ")";
    }

    // What one shape ran, in the order it ran it — or, where everything it ran is a turn of one
    // node, in no order at all.
    private String ran(final RouteStep step) {
        final String separator = turnsOfOneNode(step)
                ? TURN_SEPARATOR
                : STEP_SEPARATOR;
        final StringBuilder sb = new StringBuilder();
        for (final Integer child : NullSafe.list(step.getSteps())) {
            final String text = stepText(child);
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(text);
        }
        return sb.toString();
    }

    // Whether everything this shape ran is a turn of one and the same node. The model reads a run of
    // turns of one node in an order settled by what they are, so what is shown is not the order they
    // happened in and must not be read as one.
    private boolean turnsOfOneNode(final RouteStep step) {
        final List<Integer> children = NullSafe.list(step.getSteps());
        if (children.size() < 2) {
            return false;
        }
        int node = -1;
        for (final Integer child : children) {
            final RouteStep shape = shapeAt(child);
            if (shape == null || shape.getNode() < 0) {
                return false;
            }
            if (node < 0) {
                node = shape.getNode();
            } else if (node != shape.getNode()) {
                return false;
            }
        }
        return true;
    }

    private Column<RouteUse, String> addColumn(final String name,
                                               final Function<RouteUse, String> value,
                                               final int width,
                                               final Comparator<RouteUse> order) {
        final Column<RouteUse, String> column = DataGridUtil
                .textColumnBuilder(value)
                .withSorting(name)
                .build();
        dataGrid.addResizableColumn(column, name, width);
        // Held against the column rather than the name, because what the grid hands back when a header
        // is clicked is the column.
        orders.put(column, order);
        return column;
    }
}
