package stroom.planb.client.view;

import stroom.planb.client.presenter.PlanBDataPresenter.PlanBDataView;
import stroom.planb.client.presenter.PlanBDataUiHandlers;
import stroom.svg.shared.SvgImage;
import stroom.util.client.Console;
import stroom.widget.button.client.InlineSvgButton;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

public class PlanBDataViewImpl
        extends ViewImpl
        implements PlanBDataView {

    private final Widget widget;

    @UiField
    TextBox query;
    @UiField
    InlineSvgButton run;
    @UiField
    InlineSvgButton stop;
    @UiField
    SimplePanel tableContainer;

    private PlanBDataUiHandlers uiHandlers;

    @Inject
    public PlanBDataViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        run.setSvg(SvgImage.PLAY);
        stop.setSvg(SvgImage.STOP);

        run.addClickHandler(event -> {
            if (uiHandlers != null) {
                uiHandlers.onRun();
            } else {
                Console.warn("PlanBDataViewImpl: WARNING: uiHandlers is NULL!");
            }
        });

        stop.addClickHandler(event -> {
            if (uiHandlers != null) {
                uiHandlers.onStop();
            } else {
                Console.warn("PlanBDataViewImpl: WARNING: uiHandlers is NULL!");
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
    public void setUiHandlers(final PlanBDataUiHandlers uiHandlers) {
        this.uiHandlers = uiHandlers;
    }

    public interface Binder extends UiBinder<Widget, PlanBDataViewImpl> {

    }
}
