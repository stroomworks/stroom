package stroom.visualisation.client.view;

import stroom.visualisation.client.presenter.VisualisationAssetsAddDialogPresenter.VisualisationAssetsAddDialogView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;

public class VisualisationAssetsAddDialogViewImpl implements VisualisationAssetsAddDialogView {

    /** GWT widget */
    private final Widget widget;

    @UiField
    Label lblPath;

    /**
     * Injected constructor.
     */
    @Inject
    @SuppressWarnings("unused")
    public VisualisationAssetsAddDialogViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public void addToSlot(final Object slot, final Widget content) {
        // TODO
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void removeFromSlot(final Object slot, final Widget content) {
        // TODO
    }

    @Override
    public void setInSlot(final Object slot, final Widget content) {
        // TODO
    }

    @Override
    public void setPath(final String path) {
        lblPath.setText(path);
    }

    /**
     * Interface to keep GWT UiBinder happy.
     */
    public interface Binder extends UiBinder<Widget, VisualisationAssetsAddDialogViewImpl> {
        // No code
    }
}
