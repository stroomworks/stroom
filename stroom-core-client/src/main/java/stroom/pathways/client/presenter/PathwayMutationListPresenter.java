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

import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.CriteriaUtil;
import stroom.data.client.presenter.RestDataProvider;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayMutationResultPage;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.preferences.client.DateTimeFormatter;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageResponse;
import stroom.util.shared.ResultPage;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.Collections;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What each trace changed in the learnt model, newest first.
 *
 * <p>The model shows what a pathway ended up as; this shows how it got there — which trace taught it
 * what, and what each constraint held before it moved.
 */
public class PathwayMutationListPresenter extends MyPresenterWidget<PagerView> {

    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    private final PagerView pagerView;
    private final RestFactory restFactory;
    private final DateTimeFormatter dateTimeFormatter;
    private final MyDataGrid<PathwayMutation> dataGrid;

    private RestDataProvider<PathwayMutation, ResultPage<PathwayMutation>> dataProvider;
    private DocRef docRef;
    private String pathwayName;

    @Inject
    public PathwayMutationListPresenter(final EventBus eventBus,
                                        final PagerView view,
                                        final RestFactory restFactory,
                                        final DateTimeFormatter dateTimeFormatter) {
        super(eventBus, view);
        this.pagerView = view;
        this.restFactory = restFactory;
        this.dateTimeFormatter = dateTimeFormatter;

        dataGrid = new MyDataGrid<>(this);
        pagerView.setDataWidget(dataGrid);

        addColumns();
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(dataGrid.addColumnSortHandler(event -> refresh()));
    }

    /**
     * Shows the changes made to one pathway, or nothing where none is named.
     */
    public void read(final DocRef docRef, final String pathwayName) {
        this.docRef = docRef;
        this.pathwayName = pathwayName;
        refresh();
    }

    private void addColumns() {
        addColumn(PathwayMutation.FIELD_TIME,
                mutation -> NullSafe.get(mutation.getTime(),
                        time -> dateTimeFormatter.format(time.toEpochMillis())),
                ColumnSizeConstants.DATE_COL);
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
                .withSorting(name)
                .build();
        dataGrid.addResizableColumn(column, name, width);
    }

    private static String text(final ConstraintValue value) {
        return value == null
                ? ""
                : value.toString();
    }

    private void refresh() {
        if (dataProvider == null) {
            dataProvider = new RestDataProvider<PathwayMutation, ResultPage<PathwayMutation>>(getEventBus()) {
                @Override
                protected void exec(final Range range,
                                    final Consumer<ResultPage<PathwayMutation>> dataConsumer,
                                    final RestErrorHandler errorHandler) {
                    if (docRef == null || NullSafe.isBlankString(pathwayName)) {
                        dataConsumer.accept(new PathwayMutationResultPage(
                                Collections.emptyList(), PageResponse.empty()));
                        return;
                    }

                    final FindPathwayMutationCriteria criteria = new FindPathwayMutationCriteria(
                            CriteriaUtil.createPageRequest(range),
                            CriteriaUtil.createSortList(dataGrid.getColumnSortList()),
                            docRef,
                            pathwayName);

                    restFactory
                            .create(PATHWAYS_RESOURCE)
                            .method(res -> res.findMutations(criteria))
                            .onSuccess(result -> dataConsumer.accept(
                                    new ResultPage<>(result.getValues(), result.getPageResponse())))
                            .onFailure(errorHandler)
                            .taskMonitorFactory(pagerView)
                            .exec();
                }
            };
            dataProvider.addDataDisplay(dataGrid);

        } else {
            dataProvider.refresh();
        }
    }
}
