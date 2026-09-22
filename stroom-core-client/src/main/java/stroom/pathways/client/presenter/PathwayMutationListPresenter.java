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
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.preferences.client.DateTimeFormatter;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.cellview.client.ColumnSortList;
import com.google.gwt.user.cellview.client.ColumnSortList.ColumnSortInfo;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What each trace changed in the learnt model, newest first.
 *
 * <p>The model shows what a pathway ended up as; this shows how it got there — which trace taught it
 * what, and what each constraint held before it moved.
 */
public class PathwayMutationListPresenter extends MyPresenterWidget<PagerView> {

    private final PagerView pagerView;
    private final DateTimeFormatter dateTimeFormatter;
    private final MyDataGrid<PathwayMutation> dataGrid;
    private final MultiSelectionModelImpl<PathwayMutation> selectionModel;

    private final ListDataProvider<PathwayMutation> dataProvider;
    private Column<PathwayMutation, String> timeColumn;

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

        addColumns();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(dataGrid.addColumnSortHandler(event -> order()));
    }

    /**
     * The change being looked at, so the view around this one can show the model as it stood then.
     */
    public MultiSelectionModelImpl<PathwayMutation> getSelectionModel() {
        return selectionModel;
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

        // This is reused for every pathway opened, so the way the last one was left sorted is not
        // carried over to the next.
        newestFirst();
        dataProvider.setCompleteList(new ArrayList<>(NullSafe.list(mutations)));
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

        dataProvider.getList().sort(PathwayMutation.comparator(PathwayMutation.FIELD_TIME, descending));
        dataProvider.refresh(true);
    }

    private void addColumns() {
        // The only column that sorts. Time stands in for the order the changes were made, which is
        // what the sort really runs on — every change a trace made shares one timestamp, so the times
        // alone would shuffle changes that happened in a definite order.
        timeColumn = DataGridUtil
                .textColumnBuilder((PathwayMutation mutation) -> NullSafe.get(mutation.getTime(),
                        value -> dateTimeFormatter.format(value.toEpochMillis())))
                .withSorting(PathwayMutation.FIELD_TIME)
                .build();
        dataGrid.addResizableColumn(timeColumn, PathwayMutation.FIELD_TIME,
                ColumnSizeConstants.DATE_COL);
        newestFirst();
        addColumn(PathwayMutation.FIELD_TYPE,
                mutation -> NullSafe.get(mutation.getType(), MutationType::getDisplayValue),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_PATH,
                mutation -> String.join(" / ", NullSafe.list(mutation.getPath())),
                400);
        addColumn(PathwayMutation.FIELD_CONSTRAINT,
                PathwayMutation::getConstraint,
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_OLD_VALUE,
                mutation -> text(mutation.getOldValue()),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_NEW_VALUE,
                mutation -> text(mutation.getNewValue()),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_TRACE_ID,
                PathwayMutation::getTraceId,
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_SPAN_ID,
                PathwayMutation::getSpanId,
                ColumnSizeConstants.SMALL_COL);
    }

    private void addColumn(final String name,
                           final Function<PathwayMutation, String> value,
                           final int width) {
        final Column<PathwayMutation, String> column = DataGridUtil
                .textColumnBuilder(value)
                .build();
        dataGrid.addResizableColumn(column, name, width);
    }

    private static String text(final ConstraintValue value) {
        return value == null
                ? ""
                : value.toString();
    }

}
