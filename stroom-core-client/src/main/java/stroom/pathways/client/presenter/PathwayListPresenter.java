/*
 * Copyright 2016-2025 Crown Copyright
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
import stroom.data.grid.client.EndColumn;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.DirtyEvent;
import stroom.entity.client.presenter.DocPresenter;
import stroom.pathways.shared.AddPathway;
import stroom.pathways.shared.DeleteAndReprocessPathway;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.FindPathwayEventCriteria;
import stroom.pathways.shared.PathwayEventRow;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.UpdatePathway;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Pathway;
import stroom.preferences.client.DateTimeFormatter;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
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
    private final MyDataGrid<Pathway> dataGrid;
    private final MultiSelectionModelImpl<Pathway> selectionModel;
    private final PathwayEditPresenter pathwayEditPresenter;
    private final ButtonView newButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;
    private final ButtonView reprocessButton;
    private final ButtonView eventsButton;
    private RestDataProvider<Pathway, ResultPage<Pathway>> dataProvider;

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
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        pagerView.setDataWidget(dataGrid);

        this.pathwayEditPresenter = pathwayEditPresenter;

        newButton = pagerView.addButton(SvgPresets.NEW_ITEM);
        editButton = pagerView.addButton(SvgPresets.EDIT);
        removeButton = pagerView.addButton(SvgPresets.DELETE);
        reprocessButton = pagerView.addButton(SvgPresets.RERUN);
        eventsButton = pagerView.addButton(SvgPresets.HISTORY);

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
        registerHandler(reprocessButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    onDeleteAndReprocess();
                }
            }
        }));
        // Viewing events is allowed even when read only.
        registerHandler(eventsButton.addClickHandler(event -> {
            if (MouseUtil.isPrimary(event)) {
                onShowEvents();
            }
        }));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (!readOnly && event.getSelectionType().isDoubleSelect()) {
                onEdit();
            }
        }));
        registerHandler(dataGrid.addColumnSortHandler(event -> refresh()));
    }

    public MultiSelectionModelImpl<Pathway> getSelectionModel() {
        return selectionModel;
    }

    @Override
    public void onFilterChange(final String text) {
        filter = text;
        refresh();
    }

    private void enableButtons() {
        newButton.setEnabled(!readOnly);
        // Reprocess resets all traces for the doc regardless of selection, so it only depends on !readOnly.
        reprocessButton.setEnabled(!readOnly);
        // Viewing events is a read-only action, so it only depends on a selection being present.
        final boolean pathwaySelected = selectionModel.getSelected() != null;
        eventsButton.setEnabled(pathwaySelected);
        eventsButton.setTitle(pathwaySelected
                ? "View Pathway Events"
                : "Select a pathway to view its events");
        if (!readOnly) {
            final Pathway selectedElement = selectionModel.getSelected();
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
            reprocessButton.setTitle("Delete & reprocess disabled as read only");
        } else {
            newButton.setTitle("New Pathway");
            editButton.setTitle("Edit Pathway");
            removeButton.setTitle("Remove Pathway");
            reprocessButton.setTitle("Delete Pathway & Reprocess All Traces");
        }
    }

    private void addColumns() {
        addNameColumn();
        addCreateTimeColumn();
        addUpdateTimeColumn();
        addLastUsedColumn();
        dataGrid.addEndColumn(new EndColumn<>());
    }

    private void addNameColumn() {
        final Column<Pathway, String> column = DataGridUtil.textColumnBuilder(Pathway::getName)
                .withSorting("Root")
                .build();
        dataGrid.addResizableColumn(column,
                "Root",
                500);
//        dataGrid.sort(column);
    }

    private void addCreateTimeColumn() {
        addTimeColumn("Create Time", Pathway::getCreateTime);
    }

    private void addUpdateTimeColumn() {
        addTimeColumn("Update Time", Pathway::getUpdateTime);
    }

    private void addLastUsedColumn() {
        addTimeColumn("Last Used", Pathway::getLastUsedTime);
    }

    private void addTimeColumn(final String name, final Function<Pathway, NanoTime> function) {
        final Function<Pathway, String> valueExtractor = pathway -> {
            final NanoTime nanoTime = function.apply(pathway);
            return nanoTime == null
                    ? ""
                    : dateTimeFormatter.format(nanoTime.toEpochMillis());
        };
        final Column<Pathway, String> column = DataGridUtil
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
                            selectionModel.setSelected(pathway);
                            refresh();
                            e.hide();
                            DirtyEvent.fire(PathwayListPresenter.this, true);
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
        final Pathway existingPathway = selectionModel.getSelected();
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
                                        existingPathway.getName(),
                                        pathway)))
                                .onSuccess(response -> {
                                    selectionModel.setSelected(pathway);
                                    refresh();
                                    e.hide();
                                    DirtyEvent.fire(PathwayListPresenter.this, true);
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
        final List<Pathway> list = selectionModel.getSelectedItems();
        if (list != null && !list.isEmpty()) {
            String message = "Are you sure you want to delete the selected pathway?";
            if (list.size() > 1) {
                message = "Are you sure you want to delete the selected pathways?";
            }

            ConfirmEvent.fire(this, message, result -> {
                if (result) {
                    for (final Pathway pathway : list) {
                        restFactory
                                .create(PATHWAYS_RESOURCE)
                                .method(res -> res.deletePathway(new DeletePathway(docRef, pathway.getName())))
                                .onSuccess(response -> {
                                    selectionModel.clear();
                                    refresh();
                                    DirtyEvent.fire(PathwayListPresenter.this, true);
                                })
                                .taskMonitorFactory(pagerView)
                                .exec();
                    }
                }
            });
        }
    }

    private void onDeleteAndReprocess() {
        final List<Pathway> list = selectionModel.getSelectedItems();
        if (list == null || list.isEmpty()) {
            // Nothing selected: reset all traces for the doc without deleting a specific pathway.
            ConfirmEvent.fire(this,
                    "Clear the processing status for all traces so they are reprocessed on the next run?",
                    result -> {
                        if (result) {
                            restFactory
                                    .create(PATHWAYS_RESOURCE)
                                    .method(res -> res.deleteAndReprocessPathway(
                                            new DeleteAndReprocessPathway(docRef, null)))
                                    .onSuccess(response -> {
                                        refresh();
                                        DirtyEvent.fire(PathwayListPresenter.this, true);
                                    })
                                    .taskMonitorFactory(pagerView)
                                    .exec();
                        }
                    });
        } else {
            String message = "Delete the selected pathway AND clear the processing status for all traces "
                             + "so they are reprocessed on the next run?";
            if (list.size() > 1) {
                message = "Delete the selected pathways AND clear the processing status for all traces "
                          + "so they are reprocessed on the next run?";
            }

            ConfirmEvent.fire(this, message, result -> {
                if (result) {
                    for (final Pathway pathway : list) {
                        restFactory
                                .create(PATHWAYS_RESOURCE)
                                .method(res -> res.deleteAndReprocessPathway(
                                        new DeleteAndReprocessPathway(docRef, pathway.getName())))
                                .onSuccess(response -> {
                                    selectionModel.clear();
                                    refresh();
                                    DirtyEvent.fire(PathwayListPresenter.this, true);
                                })
                                .taskMonitorFactory(pagerView)
                                .exec();
                    }
                }
            });
        }
    }

    private void onShowEvents() {
        final Pathway selected = selectionModel.getSelected();
        if (selected == null) {
            return;
        }
        final FindPathwayEventCriteria criteria = new FindPathwayEventCriteria(
                CriteriaUtil.createPageRequest(new Range(0, 1000)),
                null,
                docRef,
                selected.getName(),
                null,
                null,
                null);
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.findPathwayEvents(criteria))
                .onSuccess(result -> {
                    final StringBuilder sb = new StringBuilder();
                    for (final PathwayEventRow row : result.getValues()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        if (row.getTimeMs() != null) {
                            sb.append(dateTimeFormatter.format(row.getTimeMs())).append("  ");
                        }
                        if (row.getEventType() != null) {
                            sb.append("[").append(row.getEventType()).append("]  ");
                        }
                        sb.append(row.getDescription() != null
                                ? row.getDescription()
                                : row.getCategory());
                    }
                    final long total = result.getPageResponse() != null && result.getPageResponse().getTotal() != null
                            ? result.getPageResponse().getTotal()
                            : result.getValues().size();
                    if (sb.length() == 0) {
                        sb.append("No events recorded for this pathway yet.");
                    }
                    AlertEvent.fireInfo(
                            PathwayListPresenter.this,
                            "Events for pathway '" + selected.getName() + "' (" + total + ")",
                            sb.toString(),
                            null);
                })
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(pagerView)
                .exec();
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
            dataProvider = new RestDataProvider<Pathway, ResultPage<Pathway>>(getEventBus()) {
                @Override
                protected void exec(final Range range,
                                    final Consumer<ResultPage<Pathway>> dataConsumer,
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
                            .onSuccess(result ->
                                    dataConsumer.accept(new ResultPage<>(result.getValues(), result.getPageResponse())))
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
