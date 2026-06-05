package stroom.sqlstore.client.view;

import stroom.sqlstore.client.presenter.SqlTemporalStoreDataPresenter.SqlTemporalStoreDataView;
import stroom.sqlstore.client.presenter.SqlTemporalStoreDataUiHandlers;
import stroom.util.client.Console;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class SqlTemporalStoreDataViewImpl
        extends ViewImpl
        implements SqlTemporalStoreDataView {

    private final Widget widget;

    @UiField
    TextBox query;
    @UiField
    Button run;
    @UiField
    Button stop;
    @UiField
    SimplePanel tableContainer;

    private SqlTemporalStoreDataUiHandlers uiHandlers;

    @Inject
    public SqlTemporalStoreDataViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        run.addClickHandler(event -> {
            if (uiHandlers != null) {
                uiHandlers.onRun();
            } else {
                Console.warn("SqlTemporalStoreDataViewImpl: WARNING: uiHandlers is NULL!");
            }
        });

        stop.addClickHandler(event -> {
            if (uiHandlers != null) {
                uiHandlers.onStop();
            } else {
                Console.warn("SqlTemporalStoreDataViewImpl: WARNING: uiHandlers is NULL!");
            }
        });
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setQuery(final String queryStr) {
        query.setText(queryStr);
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
    public void setUiHandlers(final SqlTemporalStoreDataUiHandlers uiHandlers) {
        this.uiHandlers = uiHandlers;
    }

    public interface Binder extends UiBinder<Widget, SqlTemporalStoreDataViewImpl> {

    }
}
