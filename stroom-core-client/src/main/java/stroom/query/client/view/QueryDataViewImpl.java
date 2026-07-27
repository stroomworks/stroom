package stroom.query.client.view;

import stroom.query.client.presenter.QueryDataUiHandlers;
import stroom.query.client.presenter.QueryDataView;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.InlineSvgButton;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ScrollPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.ToggleButton;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.function.Consumer;

public class QueryDataViewImpl extends ViewWithUiHandlers<QueryDataUiHandlers> implements QueryDataView {

    public interface Binder extends UiBinder<Widget, QueryDataViewImpl> {
    }

    private final Widget widget;

    @UiField
    TextBox query;
    @UiField
    ScrollPanel errorContainer;
    @UiField
    Label errorLabel;
    @UiField
    InlineSvgButton undo;
    @UiField
    InlineSvgButton run;
    @UiField
    InlineSvgButton stop;
    @UiField
    InlineSvgButton createDashboard;
    @UiField
    InlineSvgButton discover;
    @UiField
    SimplePanel discoveryContainer;
    @UiField
    FlowPanel viewToggleBar;
    @UiField
    ToggleButton tableToggle;
    @UiField
    ToggleButton graphToggle;
    @UiField
    SimplePanel tableContainer;
    @UiField
    SimplePanel graphContainer;

    private Consumer<Boolean> onViewModeChange;

    @Inject
    public QueryDataViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        undo.setSvg(SvgImage.UNDO);
        run.setSvg(SvgImage.PLAY);
        stop.setSvg(SvgImage.STOP);
        createDashboard.setSvg(SvgImage.DOCUMENT_DASHBOARD);
        discover.setSvg(SvgImage.EXPLORER);

        tableToggle.setText("Table");
        graphToggle.setText("Graph");
        tableToggle.addClickHandler(event -> showTable());
        graphToggle.addClickHandler(event -> showGraph());
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setQuery(final String queryText) {
        query.setText(queryText);
    }

    @Override
    public String getQuery() {
        return query.getText();
    }

    @Override
    public void setTable(final View view) {
        view.asWidget().addStyleName("TablePresenter");
        tableContainer.setWidget(view.asWidget());
    }

    @Override
    public void setGraphView(final Widget graphWidget, final Consumer<Boolean> onViewModeChange) {
        this.onViewModeChange = onViewModeChange;
        graphWidget.addStyleName("GraphPresenter");
        graphContainer.setWidget(graphWidget);
        viewToggleBar.setVisible(true);
        // Start on the table (the default, unchanged view).
        showTable();
    }

    private void showTable() {
        tableToggle.setDown(true);
        graphToggle.setDown(false);
        tableContainer.setVisible(true);
        graphContainer.setVisible(false);
        if (onViewModeChange != null) {
            onViewModeChange.accept(false);
        }
    }

    private void showGraph() {
        tableToggle.setDown(false);
        graphToggle.setDown(true);
        tableContainer.setVisible(false);
        graphContainer.setVisible(true);
        if (onViewModeChange != null) {
            onViewModeChange.accept(true);
        }
    }

    @Override
    public void setGraphOnly(final Widget graphWidget) {
        graphWidget.addStyleName("GraphPresenter");
        graphContainer.setWidget(graphWidget);
        // Graph is the only surface: no toggle, table container hidden (it still runs to feed the graph).
        viewToggleBar.setVisible(false);
        tableContainer.setVisible(false);
        graphContainer.setVisible(true);
    }

    @Override
    public void setDiscoveryWidget(final Widget discoveryWidget) {
        // Install the panel only. The GraphDb Explore tab triggers discovery from its Cytoscape toolbar's
        // "Discover" button (relayed via the sandbox), so the query-toolbar 'discover' button stays hidden.
        discoveryContainer.setWidget(discoveryWidget);
    }

    @Override
    public void showDiscovery(final boolean visible) {
        discoveryContainer.setVisible(visible);
    }

    @Override
    public void selectGraphView() {
        // Only meaningful once a graph view has been installed (GraphDb tab); a no-op elsewhere.
        if (graphContainer.getWidget() != null) {
            showGraph();
        }
    }

    @Override
    public void setCreateDashboardEnabled(final boolean enabled) {
        createDashboard.setEnabled(enabled);
        createDashboard.setTitle(enabled
                ? "Create Dashboard"
                : "Create Dashboard (not yet supported for graph queries)");
    }

    @Override
    public void setCreateDashboardVisible(final boolean visible) {
        createDashboard.setVisible(visible);
    }

    @Override
    public void setError(final String error) {
        if (error != null && !error.trim().isEmpty()) {
            errorLabel.setText(error);
            errorContainer.setVisible(true);
            query.addStyleName("invalid");
        } else {
            clearError();
        }
    }

    @Override
    public void clearError() {
        errorLabel.setText("");
        errorContainer.setVisible(false);
        query.removeStyleName("invalid");
    }

    @Override
    public void selectQueryRange(final int pos, final int length) {
        query.setFocus(true);
        query.setSelectionRange(pos, length);
    }

    @UiHandler("undo")
    @SuppressWarnings("unused")
    public void onUndo(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onReset();
        }
    }

    @UiHandler("run")
    @SuppressWarnings("unused")
    public void onRun(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onRun();
        }
    }

    @UiHandler("stop")
    @SuppressWarnings("unused")
    public void onStop(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onStop();
        }
    }

    @UiHandler("createDashboard")
    @SuppressWarnings("unused")
    public void onCreateDashboard(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onCreateDashboard();
        }
    }

    @UiHandler("discover")
    @SuppressWarnings("unused")
    public void onDiscover(final ClickEvent event) {
        if (getUiHandlers() != null) {
            getUiHandlers().onDiscover();
        }
    }

    @UiHandler("query")
    @SuppressWarnings("unused")
    public void onQueryKeyDown(final KeyDownEvent event) {
        if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
            if (getUiHandlers() != null) {
                getUiHandlers().onRun();
            }
        }
    }
}
