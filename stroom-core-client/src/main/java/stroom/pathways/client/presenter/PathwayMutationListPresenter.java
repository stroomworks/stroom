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

import stroom.cell.expander.client.ExpanderCell;
import stroom.config.global.client.presenter.ListDataProvider;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.entity.client.presenter.TreeRowHandler;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.AbstractRange;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.preferences.client.DateTimeFormatter;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.Expander;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortList;
import com.google.gwt.user.cellview.client.ColumnSortList.ColumnSortInfo;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What each trace changed in the learnt model, newest first.
 *
 * <p>The model shows what a pathway ended up as; this shows how it got there — which trace taught it
 * what, and what each constraint held before it moved.
 */
public class PathwayMutationListPresenter extends MyPresenterWidget<PagerView> {

    // An OpenTelemetry id is a fixed length — 16 bytes for a trace and 8 for a span, written as hex —
    // so these columns are given a width that holds one rather than being measured against the rows.
    // A span id shows only on a change, and every trace starts closed, so measuring would find nothing
    // there to measure. The change names come from a closed set, the longest being "Constraint
    // Optional".
    private static final int TRACE_ID_COL = 270;
    private static final int SPAN_ID_COL = 150;
    private static final int CHANGE_COL = 170;

    private final PagerView pagerView;
    private final DateTimeFormatter dateTimeFormatter;
    private final MyDataGrid<MutationRow> dataGrid;
    private final MultiSelectionModelImpl<MutationRow> selectionModel;

    private final ListDataProvider<MutationRow> dataProvider;
    private final MutationTreeAction treeAction = new MutationTreeAction();
    private Column<MutationRow, String> timeColumn;
    private Column<MutationRow, Expander> expanderColumn;
    // The whole history, which the rows are built from every time the list is drawn. Held apart from
    // the rows because a trace opening or closing changes the rows and not the history.
    private List<PathwayMutation> mutations = new ArrayList<>();
    private final ButtonView expandAllButton;
    private final ButtonView collapseAllButton;

    @Inject
    public PathwayMutationListPresenter(final EventBus eventBus,
                                        final PagerView view,
                                        final DateTimeFormatter dateTimeFormatter) {
        super(eventBus, view);
        this.pagerView = view;
        this.dateTimeFormatter = dateTimeFormatter;

        dataGrid = new MyDataGrid<>(this);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        pagerView.setDataWidget(dataGrid);

        // Held here rather than fetched a page at a time: the view around this one already has the
        // whole history so it can wind the model back without asking the server, and this pages
        // through that same copy so the two cannot disagree.
        dataProvider = new ListDataProvider<>();
        dataProvider.addDataDisplay(dataGrid);

        expandAllButton = pagerView.addButton(SvgPresets.EXPAND_ALL);
        collapseAllButton = pagerView.addButton(SvgPresets.COLLAPSE_ALL);

        addColumns();
        // Sizes the expander column to the depth on show, which is what keeps the indent from
        // taking a fixed slice of the width whether anything is open or not.
        dataProvider.setTreeRowHandler(new TreeRowHandler<>(treeAction, dataGrid, expanderColumn));
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(dataGrid.addColumnSortHandler(event -> order()));

        registerHandler(expandAllButton.addClickHandler(event -> {
            treeAction.expandAll(traceIds());
            order();
        }));
        registerHandler(collapseAllButton.addClickHandler(event -> {
            treeAction.collapseAll();
            order();
        }));

    }

    /**
     * The row being looked at. Held onto by the view around this one so it knows when to redraw.
     */
    public MultiSelectionModelImpl<MutationRow> getSelectionModel() {
        return selectionModel;
    }

    /**
     * Which nodes the selected row changed, as paths. A trace answers with everything it touched,
     * which is the whole point of picking one rather than picking through its changes.
     */
    public List<List<String>> getSelectedPaths() {
        final MutationRow selected = selectionModel.getSelected();
        final List<List<String>> paths = new ArrayList<>();
        if (selected == null) {
            return paths;
        }

        for (final PathwayMutation mutation : mutations) {
            final boolean mine = selected.isTrace()
                    ? Objects.equals(selected.getTraceId(), mutation.getTraceId())
                    : mutation.getSequence() == selected.getSequence();
            if (mine && !paths.contains(mutation.getPath())) {
                paths.add(mutation.getPath());
            }
        }
        return paths;
    }

    /**
     * Moves the selection on to the next trace, so the model can be watched changing a trace at a
     * time. Starts at the first where nothing is selected yet.
     *
     * <p>Traces rather than every row: one trace is one thing that happened to the model, and its
     * changes were all made at once.
     *
     * @return whether there was another trace to move on to.
     */
    public boolean selectNextTrace() {
        final List<MutationRow> rows = dataProvider.getList();
        if (NullSafe.isEmptyCollection(rows)) {
            return false;
        }

        // The next trace forward in time, not the next row down. Which way the list happens to be
        // sorted is how the reader wants to read it; it says nothing about which way a model grew, so
        // stepping follows the history rather than the order the rows are in. On a list newest first
        // that walks up it rather than down.
        final MutationRow selected = selectionModel.getSelected();
        final long after = selected == null
                ? Long.MIN_VALUE
                : selected.getSequence();

        MutationRow next = null;
        for (final MutationRow row : rows) {
            if (row.isTrace()
                && row.getSequence() > after
                && (next == null || row.getSequence() < next.getSequence())) {
                next = row;
            }
        }
        if (next == null) {
            return false;
        }

        // The single-select form. Handed a flag instead, each step would add to the selection rather
        // than move it, and the list would fill up with everything stepped through.
        selectionModel.setSelected(next);
        return true;
    }

    /**
     * When the selected row happened, or null where nothing is selected. What the model is being
     * shown as at.
     */
    public NanoTime getSelectedTime() {
        return NullSafe.get(selectionModel.getSelected(), MutationRow::getTime);
    }

    /**
     * The point in the history being looked at, or null where nothing is selected. A trace answers
     * with the last change it made, so selecting one shows the model as that trace left it.
     */
    public Long getSelectedSequence() {
        final MutationRow selected = selectionModel.getSelected();
        return selected == null
                ? null
                : selected.getSequence();
    }

    /**
     * Shows the changes made to one pathway. Given rather than fetched: the view around this one
     * already holds the whole history so it can wind the model back without asking the server, and one
     * copy means the grid and the model on show cannot disagree.
     */
    public void setData(final List<PathwayMutation> mutations) {
        // Whatever was selected belonged to the list being replaced, and the view around this one asks
        // what is selected to decide which model to show.
        selectionModel.clear();

        // This is reused for every pathway opened, so neither the way the last one was left sorted nor
        // which of its traces were open is carried over to the next.
        treeAction.collapseAll();
        newestFirst();
        this.mutations = new ArrayList<>(NullSafe.list(mutations));
        order();
    }

    // Seeded rather than pushed, because pushing a column sorts it ascending and the list starts
    // newest first. Also what the header reads from, so it shows which way it is ordered before
    // anything has been clicked.
    private void newestFirst() {
        final ColumnSortList sortList = dataGrid.getColumnSortList();
        sortList.clear();
        sortList.push(new ColumnSortInfo(timeColumn, false));
    }

    // Newest first unless the grid has been told otherwise. Nothing but the order the changes were
    // made in means anything here, so that is the only thing the columns offer.
    private void order() {
        final ColumnSortList sortList = dataGrid.getColumnSortList();
        final boolean descending = sortList == null
                                   || sortList.size() == 0
                                   || !sortList.get(0).isAscending();

        dataProvider.setCompleteList(buildRows(descending));
        updateButtons();
    }

    // Nothing to open once everything is open, and nothing to close once everything is closed.
    private void updateButtons() {
        final Set<String> traceIds = traceIds();
        int open = 0;
        for (final String traceId : traceIds) {
            if (treeAction.isTraceExpanded(traceId)) {
                open++;
            }
        }
        expandAllButton.setEnabled(open < traceIds.size());
        collapseAllButton.setEnabled(open > 0);
    }

    // First appearance first, so what the buttons act on is the order the list is built in.
    private Set<String> traceIds() {
        final Set<String> traceIds = new LinkedHashSet<>();
        for (final PathwayMutation mutation : mutations) {
            traceIds.add(mutation.getTraceId());
        }
        return traceIds;
    }

    /**
     * A row per trace, each holding the changes that trace made.
     *
     * <p>Everything one trace taught the model is written in one go, so a trace's changes are always
     * next to each other in the history and grouping them is a walk rather than a sort. They are
     * grouped in the order they were made and the result turned round afterwards, so which way the
     * list is sorted cannot split a trace in two.
     */
    private List<MutationRow> buildRows(final boolean descending) {
        final List<PathwayMutation> oldestFirst = new ArrayList<>(mutations);
        oldestFirst.sort(PathwayMutation.comparator(PathwayMutation.FIELD_TIME, false));

        final List<List<PathwayMutation>> traces = new ArrayList<>();
        List<PathwayMutation> current = null;
        for (final PathwayMutation mutation : oldestFirst) {
            if (current == null || !Objects.equals(current.get(0).getTraceId(), mutation.getTraceId())) {
                current = new ArrayList<>();
                traces.add(current);
            }
            current.add(mutation);
        }

        if (descending) {
            Collections.reverse(traces);
        }

        final List<MutationRow> rows = new ArrayList<>();
        for (final List<PathwayMutation> trace : traces) {
            // The last change the trace made, which is the model as the trace left it however the
            // list is turned round.
            final PathwayMutation last = trace.get(trace.size() - 1);
            final MutationRow traceRow = MutationRow.trace(last.getTraceId(), trace.get(0).getTime(),
                    last.getSequence(), treeAction.isTraceExpanded(last.getTraceId()));
            rows.add(traceRow);

            if (traceRow.getExpander().isExpanded()) {
                final List<PathwayMutation> changes = new ArrayList<>(trace);
                if (descending) {
                    Collections.reverse(changes);
                }
                for (final PathwayMutation mutation : changes) {
                    rows.add(MutationRow.change(mutation));
                }
            }
        }
        return rows;
    }

    private void addColumns() {
        addExpanderColumn();

        // The only column that sorts. Time stands in for the order the changes were made, which is
        // what the sort really runs on — every change a trace made shares one timestamp, so the times
        // alone would shuffle changes that happened in a definite order.
        timeColumn = DataGridUtil
                .textColumnBuilder((MutationRow row) -> NullSafe.get(row.getTime(),
                        value -> dateTimeFormatter.format(value.toEpochMillis())))
                .withSorting(PathwayMutation.FIELD_TIME)
                .build();
        dataGrid.addResizableColumn(timeColumn, PathwayMutation.FIELD_TIME,
                ColumnSizeConstants.DATE_COL);
        newestFirst();

        // What the change belonged to comes before what it was: the list is grouped by trace, so the
        // trace is what a row is found by. Kept on the changes as well as on the trace they sit under,
        // so a row still says which trace made it when the list is copied out of the grid.
        addColumn(PathwayMutation.FIELD_TRACE_ID,
                MutationRow::getTraceId,
                TRACE_ID_COL);
        addColumn(PathwayMutation.FIELD_SPAN_ID,
                row -> ofChange(row, PathwayMutation::getSpanId),
                SPAN_ID_COL);

        addColumn(PathwayMutation.FIELD_PATH,
                row -> ofChange(row, mutation -> String.join(" / ", NullSafe.list(mutation.getPath()))),
                400);
        addColumn(PathwayMutation.FIELD_CONSTRAINT,
                row -> ofChange(row, PathwayMutation::getConstraint),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_TYPE,
                row -> ofChange(row, mutation ->
                        NullSafe.get(mutation.getType(), MutationType::getDisplayValue)),
                CHANGE_COL);
        addColumn(PathwayMutation.FIELD_OLD_VALUE,
                row -> ofChange(row, mutation -> text(mutation, mutation.getOldValue())),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_NEW_VALUE,
                row -> ofChange(row, mutation -> text(mutation, mutation.getNewValue())),
                ColumnSizeConstants.MEDIUM_COL);
    }

    private void addExpanderColumn() {
        expanderColumn = new Column<MutationRow, Expander>(new ExpanderCell()) {
            @Override
            public Expander getValue(final MutationRow row) {
                return row.getExpander();
            }
        };
        expanderColumn.setFieldUpdater((index, row, value) -> {
            treeAction.setTraceExpanded(row.getTraceId(), !value.isExpanded());
            order();
        });
        dataGrid.addColumn(expanderColumn, "");
    }

    // The columns below the trace describe one change, so a trace leaves them empty rather than
    // repeating itself across a row that stands for many.
    private static String ofChange(final MutationRow row,
                                   final Function<PathwayMutation, String> value) {
        return row.isTrace()
                ? ""
                : value.apply(row.getMutation());
    }

    private Column<MutationRow, String> addColumn(final String name,
                                                  final Function<MutationRow, String> value,
                                                  final int width) {
        final Column<MutationRow, String> column = DataGridUtil
                .textColumnBuilder(value)
                .build();
        dataGrid.addResizableColumn(column, name, width);
        return column;
    }

    // A trace carries one value, so it can only push out one end of a range. Showing the whole range
    // reads as though the end that stayed put had moved as well. Where the constraint held a single
    // value before, that value was itself the end that moved, so it is shown as it stands.
    private static String text(final PathwayMutation mutation, final ConstraintValue value) {
        if (value instanceof AbstractRange) {
            final AbstractRange<?> range = (AbstractRange<?>) value;
            final Object end;
            if (MutationType.CONSTRAINT_MIN_EXPANDED.equals(mutation.getType())) {
                end = range.getMin();
            } else if (MutationType.CONSTRAINT_MAX_EXPANDED.equals(mutation.getType())) {
                end = range.getMax();
            } else {
                end = null;
            }
            if (end != null) {
                return end.toString();
            }
        }
        return value == null
                ? ""
                : value.toString();
    }

}
