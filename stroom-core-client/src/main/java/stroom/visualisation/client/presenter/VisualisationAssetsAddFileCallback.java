package stroom.visualisation.client.presenter;

import stroom.util.shared.ResourceKey;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;

/**
 * Callback interface, so that the Add File dialog can record what it has done back
 * into the VisualisationAssetsAddDialogPresenter.
 */
public interface VisualisationAssetsAddFileCallback {

    /**
     * Method for the Add File dialog to call when it has uploaded a file.
     * @param parentFolderNode The node that the file has been added to.
     * @param fileName The name of the file that was uploaded.
     * @param resourceKey The resource key of the file, so that the server can find it later.
     */
    void addUploadedFile(UpdatableTreeNode parentFolderNode,
                         String fileName,
                         ResourceKey resourceKey);

}
