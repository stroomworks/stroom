package stroom.query.client.presenter;

import com.google.gwt.user.client.ui.Widget;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.function.Consumer;

public interface QueryDataView extends View, HasUiHandlers<QueryDataUiHandlers> {
    void setQuery(String query);

    String getQuery();

    void setTable(View view);

    /**
     * Install an optional graph view alongside the table and reveal a Table/Graph toggle. Only datasource
     * types that can produce a graph (the Graph DB Data tab) call this; every other query data tab never does,
     * so the toggle stays hidden and the tab behaves exactly as before.
     *
     * @param graphWidget       the graph view's widget.
     * @param onViewModeChange  invoked with {@code true} when the user switches to the graph and {@code false}
     *                          when they switch back to the table.
     */
    void setGraphView(Widget graphWidget, Consumer<Boolean> onViewModeChange);

    /**
     * Install an optional discovery panel (schema + starter queries) and reveal the "Discover" control. Only the
     * Graph DB Data tab calls this; other tabs never do, so the control and panel stay hidden.
     */
    void setDiscoveryWidget(Widget discoveryWidget);

    /** Show or hide the discovery panel installed by {@link #setDiscoveryWidget}. */
    void showDiscovery(boolean visible);

    /** Switch to the graph view (if one was installed via {@link #setGraphView}); a no-op otherwise. */
    void selectGraphView();

    void setError(String error);

    void clearError();

    void selectQueryRange(int pos, int length);
}
