package stroom.data.grid.client;

import stroom.dashboard.client.main.DashboardContext;
import stroom.dashboard.client.main.DashboardSuperPresenter;
import stroom.dashboard.shared.DashboardResource;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.query.api.TimeRange;
import stroom.svg.shared.SvgImage;
import stroom.task.client.TaskMonitorFactory;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.IconParentMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.util.client.FutureImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.shared.HasHandlers;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class MyDataGridDomainTypeSupportImpl<T> implements MyDataGridDomainTypeSupport<T> {

    private static final DashboardResource DASHBOARD_RESOURCE = GWT.create(DashboardResource.class);

    private final RestFactory restFactory;
    private final HasHandlers globalEventBus;
    private final TaskMonitorFactory taskMonitorFactory;
    private final MyDataGrid<T> dataGrid;
    private final Supplier<DashboardContext> dashboardContextSupplier;

    public MyDataGridDomainTypeSupportImpl(final RestFactory restFactory,
                                              final HasHandlers globalEventBus,
                                              final TaskMonitorFactory taskMonitorFactory,
                                              final MyDataGrid<T> dataGrid,
                                              final Supplier<DashboardContext> dashboardContextSupplier) {
        this.restFactory = restFactory;
        this.globalEventBus = globalEventBus;
        this.taskMonitorFactory = taskMonitorFactory;
        this.dataGrid = dataGrid;
        this.dashboardContextSupplier = dashboardContextSupplier;
    }

    @Override
    public Item createContextMenu(final int rowIndex, final int colIndex) {
        final String domainType = dataGrid.getDomainType(colIndex);
        if (domainType != null) {
            final FutureImpl<List<Item>> future = new FutureImpl<>();
            restFactory
                    .create(DASHBOARD_RESOURCE)
                    .method(res -> res.findByType(domainType))
                    .onSuccess(docRefs -> {
                        final List<Item> menuItems = docRefs.stream()
                                .map(docRef -> (Item) new IconMenuItem.Builder()
                                        .icon(SvgImage.DOCUMENT_DASHBOARD)
                                        .text(docRef.getName())
                                        .command(() -> jumpTo(docRef, rowIndex))
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

    /**
     * Convert any character that isn't a letter or digit to _.
     * Can't use streams or codepoints as this is running in GWT.
     * @param domainType The string to sanitise.
     * @return A sanitised version of the string.
     */
    private String convertToParamName(final String domainType) {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < domainType.length(); i++) {
            final char c = domainType.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.') {
                buf.append(c);
            } else {
                buf.append("_");
            }
        }

        return buf.toString();
    }

    /**
     * Escape the value of a parameter so that it can be correctly decoded at the
     * destination dashboard.
     * <p>
     *     Spaces and equals signs mean that the whole string gets quoted.
     *     If quoted then each internal quote must be doubled.
     * </p>
     * @param value The value to escape
     * @return The escaped value
     */
    private String escape(final String value) {
        if (value == null) {
            return "";
        }

        boolean quote = false;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == ' ' || c == '=') {
                quote = true;
            } else if (c == '"') {
                quote = true;
                sb.append('"');
            }
            sb.append(c);
        }

        if (quote) {
            sb.insert(0, '"');
            sb.append('"');
        }

        return sb.toString();
    }

    /**
     * Jumps to the dashboard given by the docRef.
     * @param docRef The dashboard to jump to.
     * @param rowIndex The index of the row that is under the mouse.
     */
    private void jumpTo(final DocRef docRef, final int rowIndex) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dataGrid.getColumnCount(); i++) {
            final String domainType = dataGrid.getDomainType(i);
            if (domainType != null) {
                final String paramName = convertToParamName(domainType);
                final String cellValue = dataGrid.getCellText(rowIndex, i);
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(paramName);
                sb.append("=");
                sb.append(escape(cellValue));
            }
        }

        final DashboardContext dashboardContext = dashboardContextSupplier.get();
        if (dashboardContext != null) {
            final TimeRange timeRange = dashboardContext.getRawTimeRange();
            if (timeRange != null) {
                if (timeRange.getFrom() != null) {
                    if (!sb.isEmpty()) {
                        sb.append(" ");
                    }
                    sb.append("timeRange.from=");
                    sb.append(escape(timeRange.getFrom()));
                }
                if (timeRange.getTo() != null) {
                    if (!sb.isEmpty()) {
                        sb.append(" ");
                    }
                    sb.append("timeRange.to=");
                    sb.append(escape(timeRange.getTo()));
                }
            }
        }

        final String params = sb.toString();
        OpenDocumentEvent.builder(globalEventBus, docRef)
                .callbackOnOpen(presenter -> {
                    if (presenter instanceof final DashboardSuperPresenter dashboardSuperPresenter) {
                        dashboardSuperPresenter.setParamsFromLink(params);
                    }
                })
                .fire();
    }
}
