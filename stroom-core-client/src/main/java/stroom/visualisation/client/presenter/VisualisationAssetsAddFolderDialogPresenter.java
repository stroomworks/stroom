package stroom.visualisation.client.presenter;

import stroom.alert.client.event.AlertEvent;
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
     * Checks if the data in the dialog is valid. If not shows a warning dialog and returns false.
     * @return true if everything is ok. False if not ok.
     */
    public boolean checkValid() {
        final String folderName = getView().getFolderName();
        if (NullSafe.isBlankString(folderName)) {
            AlertEvent.fireWarn(this, "Folder name not set", null);
            return false;
        }

        // TODO Check name is unique

        return true;
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
