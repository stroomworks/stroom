package stroom.visualisation.client.presenter.tree;

/**
 * Interface used by CustomEditTextCell to check if the cell should be readonly.
 */
public interface ReadOnlyCheck {

    /**
     * Implement this to tell the cell if it is readonly.
     * @return true if readonly, false if read-write.
     */
    boolean isReadOnly();
}
