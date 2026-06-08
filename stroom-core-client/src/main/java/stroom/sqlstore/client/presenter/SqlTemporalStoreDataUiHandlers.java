package stroom.sqlstore.client.presenter;

import com.gwtplatform.mvp.client.UiHandlers;

public interface SqlTemporalStoreDataUiHandlers extends UiHandlers {
    void onRun();

    void onStop();
}
