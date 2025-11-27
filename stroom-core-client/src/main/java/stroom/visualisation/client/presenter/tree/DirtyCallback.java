package stroom.visualisation.client.presenter.tree;

/**
 * Class to implement a callback for when the CustomEditTextCell gets changed,
 * so the UI can save stuff as necessary.
 */
public interface DirtyCallback {

    /**
     * Method to call when something is dirty and needs saving.
     */
    void setDirty();

}
