package stroom.visualisation.client.presenter;

import stroom.widget.popup.client.event.ShowPopupEvent.Builder;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

public class VisualisationAssetsAddDialogPresenter
        extends MyPresenterWidget<VisualisationAssetsAddDialogPresenter.VisualisationAssetsAddDialogView> {

    /** Width of dialog */
    private static final int DIALOG_WIDTH = 300;

    /** Height of dialog */
    private static final int DIALOG_HEIGHT = 300;

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @Inject
    public VisualisationAssetsAddDialogPresenter(final EventBus eventBus,
                                                 final VisualisationAssetsAddDialogView view) {
        super(eventBus, view);
    }

    /**
     * Call to prepare the dialog to be shown.
     *
     * @param builder The builder for the dialog.
     * @param path The path that we're adding the item at.
     */
    public void setupDialog(final Builder builder, final String path) {

        builder.popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(DIALOG_WIDTH, DIALOG_HEIGHT))
                .caption("Add File")
                .modal(true);
        this.getView().setPath(path);
    }

    public interface VisualisationAssetsAddDialogView extends View {

        /**
         * Sets the path where this asset will be added.
         */
        void setPath(String path);

    }

}
