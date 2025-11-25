package stroom.visualisation.client.presenter;

import stroom.util.shared.NullSafe;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.event.shared.HasHandlers;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

public class VisualisationAssetsAddFolderDialogPresenter
        extends MyPresenterWidget<VisualisationAssetsAddFolderDialogPresenter.VisualisationAssetsAddFolderDialogView>
        implements HasHandlers {

    /** Width of dialog */
    private static final int DIALOG_WIDTH = 300;

    /** Height of dialog */
    private static final int DIALOG_HEIGHT = 300;

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @Inject
    public VisualisationAssetsAddFolderDialogPresenter(final EventBus eventBus,
                                                       final VisualisationAssetsAddFolderDialogView view) {
        super(eventBus, view);
    }

    /**
     * Call to prepare and show the dialog.
     *
     * @param path The path that we're adding the item at.
     */
    public void setupPopup(final ShowPopupEvent.Builder builder,
                           final String path) {

        this.getView().setPath(path);

        builder.popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(DIALOG_WIDTH, DIALOG_HEIGHT))
                .caption("Add Folder")
                .modal(true);
    }

    /**
     * @return true if the form is valid, false if not.
     */
    public boolean isValid() {
        return getValidationErrorMessage() == null;
    }

    /**
     * @return The form validation error message, or null if everything is ok.
     */
    public String getValidationErrorMessage() {
        String retval = null;
        if (NullSafe.isBlankString(getView().getFolderName())) {
            retval = "Please set the name of the folder you wish to create";
        }

        return retval;
    }

    public interface VisualisationAssetsAddFolderDialogView extends View {

        /**
         * Sets the path where this asset will be added.
         */
        void setPath(String path);

        /**
         * Gets the file upload widget.
         */
        String getFolderName();

    }

}
