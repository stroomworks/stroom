/*
 * Copyright 2020 Crown Copyright
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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.CriteriaUtil;
import stroom.data.client.presenter.RestDataProvider;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.pathways.shared.AddPathway;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.FetchPathwayRequest;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.UpdatePathway;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Pathway;
import stroom.preferences.client.DateTimeFormatter;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.ModelStringUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.ResultPage;
import stroom.widget.button.client.ButtonView;
import stroom.widget.dropdowntree.client.view.QuickFilterPageView;
import stroom.widget.dropdowntree.client.view.QuickFilterUiHandlers;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class PathwayListPresenter
        extends DocPresenter<QuickFilterPageView, PathwaysDoc>
        implements QuickFilterUiHandlers {

    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    private final DateTimeFormatter dateTimeFormatter;
    private final PagerView pagerView;
    private final RestFactory restFactory;
    private final MyDataGrid<PathwaySummary> dataGrid;
    private final MultiSelectionModelImpl<PathwaySummary> selectionModel;
    private final PathwayEditPresenter pathwayEditPresenter;
    private final ButtonView newButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private RestDataProvider<PathwaySummary, ResultPage<PathwaySummary>> dataProvider;
    private String selectedName;
    private Pathway selectedPathway;
    private boolean fetching;
    private final List<Consumer<Pathway>> waiting = new ArrayList<>();

    private String filter;
    private DocRef docRef;
    private PathwaysDoc pathwaysDoc;
    private boolean readOnly = true;

    @Inject
    public PathwayListPresenter(final EventBus eventBus,
                                final QuickFilterPageView view,
                                final PagerView pagerView,
                                final RestFactory restFactory,
                                final DateTimeFormatter dateTimeFormatter,
                                final PathwayEditPresenter pathwayEditPresenter) {
        super(eventBus, view);
        this.pagerView = pagerView;
        this.restFactory = restFactory;
        this.dateTimeFormatter = dateTimeFormatter;
        view.setDataView(pagerView);
        view.setUiHandlers(this);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Pathways");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        pagerView.setDataWidget(dataGrid);

        this.pathwayEditPresenter = pathwayEditPresenter;

        newButton = pagerView.addButton(SvgPresets.NEW_ITEM);
        editButton = pagerView.addButton(SvgPresets.EDIT);
        removeButton = pagerView.addButton(SvgPresets.DELETE);

        addColumns();
        enableButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();

        registerHandler(newButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    onAdd();
                }
            }
        }));
        registerHandler(editButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    onEdit();
                }
            }
        }));
        registerHandler(removeButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    onRemove();
                }
            }
        }));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            // A double click raises this twice, once for each click, so the model is fetched only when
            // the row being looked at has really changed.
            withSelectedPathway(pathway -> {
                if (!readOnly) {
                    enableButtons();
                    if (event.getSelectionType().isDoubleSelect()) {
                        edit(pathway);
                    }
                }
            });
        }));
        registerHandler(dataGrid.addColumnSortHandler(event -> refresh()));
    }

    public MultiSelectionModelImpl<PathwaySummary> getSelectionModel() {
        return selectionModel;
    }

    @Override
    public void onFilterChange(final String text) {
        filter = text;
        refresh();
    }

    private void enableButtons() {
        newButton.setEnabled(!readOnly);
        if (!readOnly) {
            final PathwaySummary selectedElement = selectionModel.getSelected();
            final boolean enabled = selectedElement != null;
            editButton.setEnabled(enabled);
            removeButton.setEnabled(enabled);
        } else {
            editButton.setEnabled(false);
            removeButton.setEnabled(false);
        }

        if (readOnly) {
            newButton.setTitle("New pathway disabled as read only");
            editButton.setTitle("Edit pathway disabled as read only");
            removeButton.setTitle("Remove pathway disabled as read only");
        } else {
            newButton.setTitle("New Pathway");
            editButton.setTitle("Edit Pathway");
            removeButton.setTitle("Remove Pathway");
        }
    }

    private void addColumns() {
        addNameColumn();
        addCreateTimeColumn();
        addUpdateTimeColumn();
        addLastUsedColumn();
        addSizeColumn();
    }

    // A pathway keeps every path it has seen, so they differ by orders of magnitude and the large ones
    // are slow to open. Free to show: it is the stored length of the value the row was read from.
    private void addSizeColumn() {
        final Column<PathwaySummary, String> column = DataGridUtil
                .textColumnBuilder((PathwaySummary summary) ->
                        ModelStringUtil.formatIECByteSizeString(summary.getSizeBytes()))
                .withSorting(PathwaySummary.FIELD_SIZE)
                .build();
        dataGrid.addResizableColumn(column, PathwaySummary.FIELD_SIZE, ColumnSizeConstants.SMALL_COL);
    }

    private void addNameColumn() {
        final Column<PathwaySummary, String> column = DataGridUtil.textColumnBuilder(PathwaySummary::getName)
                .withSorting(PathwaySummary.FIELD_NAME)
                .build();
        dataGrid.addResizableColumn(column,
                PathwaySummary.FIELD_NAME,
                500);
//        dataGrid.sort(column);
    }

    private void addCreateTimeColumn() {
        addTimeColumn(PathwaySummary.FIELD_CREATE_TIME, PathwaySummary::getCreateTime);
    }

    private void addUpdateTimeColumn() {
        addTimeColumn(PathwaySummary.FIELD_UPDATE_TIME, PathwaySummary::getUpdateTime);
    }

    private void addLastUsedColumn() {
        addTimeColumn(PathwaySummary.FIELD_LAST_USED_TIME, PathwaySummary::getLastUsedTime);
    }

    private void addTimeColumn(final String name, final Function<PathwaySummary, NanoTime> function) {
        final Function<PathwaySummary, String> valueExtractor = summary -> {
            final NanoTime nanoTime = function.apply(summary);
            return nanoTime == null
                    ? ""
                    : dateTimeFormatter.format(nanoTime.toEpochMillis());
        };
        final Column<PathwaySummary, String> column = DataGridUtil
                .textColumnBuilder(valueExtractor)
                .withSorting(name)
                .build();
        dataGrid.addResizableColumn(column,
                name,
                ColumnSizeConstants.DATE_COL);
//        dataGrid.sort(column);
    }

    private void onAdd() {
        final NanoTime now = NanoTime.ofMillis(System.currentTimeMillis());
        pathwayEditPresenter.read(pathwaysDoc, Pathway.builder().name("").createTime(now).build(), readOnly);
        pathwayEditPresenter.show("New Pathway", e -> {
            if (e.isOk()) {
                final Pathway pathway = pathwayEditPresenter.write();
                restFactory
                        .create(PATHWAYS_RESOURCE)
                        .method(res -> res.addPathway(new AddPathway(docRef, pathway)))
                        .onSuccess(response -> {
                            refresh();
                            e.hide();
                        })
                        .onFailure(new DefaultErrorHandler(this, e::reset))
                        .taskMonitorFactory(pagerView)
                        .exec();
            } else {
                e.hide();
            }
        });
    }

    private void onEdit() {
        withSelectedPathway(this::edit);
    }

    private void edit(final Pathway pathway) {
        final PathwaySummary selected = selectionModel.getSelected();
        if (selected != null && pathway != null) {
            editFetched(selected, pathway);
        }
    }

    /**
     * The model for the row being looked at, fetched once and held. The row carries only its size —
     * see {@link PathwaySummary} — and the model is the expensive half, so it is not fetched again
     * while the same row is selected.
     */
    public void withSelectedPathway(final Consumer<Pathway> consumer) {
        final String name = NullSafe.get(selectionModel.getSelected(), PathwaySummary::getName);

        if (name == null) {
            forget();
            consumer.accept(null);
            return;
        }

        if (name.equals(selectedName)) {
            if (fetching) {
                // One is already on its way for this row. Everyone asking is told when it lands, so a
                // row is fetched once however many parts of the view want it.
                waiting.add(consumer);
            } else {
                consumer.accept(selectedPathway);
            }
            return;
        }

        forget();
        selectedName = name;
        fetching = true;
        waiting.add(consumer);

        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.fetchPathway(new FetchPathwayRequest(docRef, name)))
                .onSuccess(pathway -> {
                    // A row selected since this was asked for has its own fetch, so this one is stale.
                    if (name.equals(selectedName)) {
                        selectedPathway = pathway;
                        fetching = false;
                        tell(pathway);
                    }
                })
                .onFailure(new DefaultErrorHandler(this, () -> {
                    if (name.equals(selectedName)) {
                        forget();
                    }
                }))
                .taskMonitorFactory(pagerView)
                .exec();
    }

    private void tell(final Pathway pathway) {
        final List<Consumer<Pathway>> toTell = new ArrayList<>(waiting);
        waiting.clear();
        toTell.forEach(consumer -> consumer.accept(pathway));
    }

    private void forget() {
        selectedName = null;
        selectedPathway = null;
        fetching = false;
        waiting.clear();
    }

    private void editFetched(final PathwaySummary selected, final Pathway existingPathway) {
        if (existingPathway != null) {
            pathwayEditPresenter.read(pathwaysDoc, existingPathway, readOnly);
            pathwayEditPresenter.show("Edit Pathway", e -> {
                if (e.isOk()) {
                    try {
                        final Pathway pathway = pathwayEditPresenter.write();
                        restFactory
                                .create(PATHWAYS_RESOURCE)
                                .method(res -> res.updatePathway(new UpdatePathway(
                                        docRef,
                                        selected.getName(),
                                        pathway)))
                                .onSuccess(response -> {
                                    refresh();
                                    e.hide();
                                })
                                .onFailure(new DefaultErrorHandler(this, e::reset))
                                .taskMonitorFactory(pagerView)
                                .exec();
                    } catch (final RuntimeException ex) {
                        AlertEvent.fireError(PathwayListPresenter.this, ex.getMessage(), e::reset);
                    }
                } else {
                    e.hide();
                }
            });
        }
    }

    private void onRemove() {
        final List<PathwaySummary> list = selectionModel.getSelectedItems();
        if (list != null && !list.isEmpty()) {
            String message = "Are you sure you want to delete the selected pathway?";
            if (list.size() > 1) {
                message = "Are you sure you want to delete the selected pathways?";
            }

            ConfirmEvent.fire(this, message, result -> {
                if (result) {
                    for (final PathwaySummary pathway : list) {
                        restFactory
                                .create(PATHWAYS_RESOURCE)
                                .method(res -> res.deletePathway(new DeletePathway(docRef, pathway.getName())))
                                .onSuccess(response -> {
                                    selectionModel.clear();
                                    refresh();
                                })
                                .taskMonitorFactory(pagerView)
                                .exec();
                    }
                }
            });
        }
    }

    @Override
    protected void onRead(final DocRef docRef, final PathwaysDoc document, final boolean readOnly) {
        this.docRef = docRef;
        this.pathwaysDoc = document;
        this.readOnly = readOnly;
        enableButtons();
        refresh();
    }

    @Override
    protected PathwaysDoc onWrite(final PathwaysDoc document) {
        return document;
    }

    private void refresh() {
        if (dataProvider == null) {
            dataProvider = new RestDataProvider<PathwaySummary, ResultPage<PathwaySummary>>(getEventBus()) {
                @Override
                protected void exec(final Range range,
                                    final Consumer<ResultPage<PathwaySummary>> dataConsumer,
                                    final RestErrorHandler errorHandler) {
                    final FindPathwayCriteria criteria = new FindPathwayCriteria(
                            CriteriaUtil.createPageRequest(range),
                            CriteriaUtil.createSortList(dataGrid.getColumnSortList()),
                            docRef,
                            filter,
                            null);

                    restFactory
                            .create(PATHWAYS_RESOURCE)
                            .method(res -> res.findPathways(criteria))
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
