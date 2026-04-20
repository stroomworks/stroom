package stroom.data.grid.client;

import stroom.dashboard.client.main.DashboardSuperPresenter;
import stroom.dashboard.shared.DashboardResource;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.svg.shared.SvgImage;
import stroom.task.client.TaskMonitorFactory;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.IconParentMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.util.client.FutureImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.shared.HasHandlers;

import java.util.List;
import java.util.stream.Collectors;

public class MyDataGridDomainTypeSupportImpl<T> implements MyDataGridDomainTypeSupport<T> {

    private static final DashboardResource DASHBOARD_RESOURCE = GWT.create(DashboardResource.class);

    private final RestFactory restFactory;
    private final HasHandlers globalEventBus;
    private final TaskMonitorFactory taskMonitorFactory;
    private final MyDataGrid<T> dataGrid;

    public MyDataGridDomainTypeSupportImpl(final RestFactory restFactory,
                                              final HasHandlers globalEventBus,
                                              final TaskMonitorFactory taskMonitorFactory,
                                              final MyDataGrid<T> dataGrid) {
        this.restFactory = restFactory;
        this.globalEventBus = globalEventBus;
        this.taskMonitorFactory = taskMonitorFactory;
        this.dataGrid = dataGrid;
    }

    @Override
    public Item createContextMenu(final int rowIndex, final int colIndex) {
        final String domainType = dataGrid.getDomainType(colIndex);
        if (domainType != null) {
            final String cellValue = dataGrid.getCellText(rowIndex, colIndex);
            final FutureImpl<List<Item>> future = new FutureImpl<>();
            restFactory
                    .create(DASHBOARD_RESOURCE)
                    .method(res -> res.findByType(domainType))
                    .onSuccess(docRefs -> {
                        final List<Item> menuItems = docRefs.stream()
                                .map(docRef -> (Item) new IconMenuItem.Builder()
                                        .icon(SvgImage.DOCUMENT_DASHBOARD)
                                        .text(docRef.getName())
                                        .command(() -> jumpTo(docRef, domainType, cellValue))
                                        .build())
                                .collect(Collectors.toList());
                        future.setResult(menuItems);
                    })
                    .taskMonitorFactory(taskMonitorFactory)
                    .exec();

            return new IconParentMenuItem.Builder()
                    .icon(SvgImage.SEARCH)
                    .text("Jump to ")
                    .children(future)
                    .build();
        }
        return null;
    }

    private void jumpTo(final DocRef docRef, final String domainType, final String cellValue) {
        final String params = domainType + "=" + cellValue;
        OpenDocumentEvent.builder(globalEventBus, docRef)
                .callbackOnOpen(presenter -> {
                    if (presenter instanceof final DashboardSuperPresenter dashboardSuperPresenter) {
                        dashboardSuperPresenter.setParamsFromLink(params);
                    }
                })
                .fire();
    }
}
