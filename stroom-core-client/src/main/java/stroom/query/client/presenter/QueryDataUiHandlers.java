package stroom.query.client.presenter;

import com.gwtplatform.mvp.client.UiHandlers;

public interface QueryDataUiHandlers extends UiHandlers {
    void onRun();

    void onStop();

    void onReset();

    void onCreateDashboard();

    /**
     * The optional "Discover" affordance (only surfaced when a datasource offers schema discovery, e.g. the Graph
     * DB Data tab). A no-op for datasources that do not.
     */
    void onDiscover();
}
