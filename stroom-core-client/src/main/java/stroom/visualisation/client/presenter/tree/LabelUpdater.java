package stroom.visualisation.client.presenter.tree;

/**
 * Interface used within the CustomEditTextCell. Used to tweak node label
 * so no two nodes within a directory have the same name.
 */
public interface LabelUpdater {

    /**
     * Called before committing the label to adjust the label if necessary.
     * @param node The node containing the label.
     * @param label The label for the node as given by the user.
     * @return The new value for the label.
     */
    String update(UpdatableTreeNode node, String label);

}
