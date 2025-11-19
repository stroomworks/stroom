package stroom.visualisation.client.presenter.assets;

import stroom.util.client.Console;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;

import com.google.gwt.view.client.ListDataProvider;

/**
 * Represents an item in the asset tree.
 */
public class VisualisationAssetItem implements UpdatableTreeNode {

    /**
     * The name to display for the item
     */
    private String label;

    /**
     * Whether this is a leaf (file) or not (folder / directory)
     */
    private final boolean isLeaf;

    /**
     * Parent item.
     */
    private UpdatableTreeNode parent = null;

    /**
     * Children.
     */
    final ListDataProvider<UpdatableTreeNode> dataProvider = new ListDataProvider<>();

    /**
     * Constructor
     */
    public VisualisationAssetItem(final String label, final boolean isLeaf) {
        this.label = label;
        this.isLeaf = isLeaf;
    }

    /**
     * Returns the name of the item to display
     */
    @Override
    public String getLabel() {
        return label;
    }

    /**
     * Sets the name of the item to display.
     */
    @Override
    public void setLabel(final String label) {
        this.label = label;
    }

    /**
     * Returns whether this item has children.
     */
    @Override
    public boolean hasChildren() {
        return !isLeaf && !dataProvider.getList().isEmpty();
    }

    /**
     * Returns the number of children of this item.
     */
    @Override
    public int getChildCount() {
        return isLeaf ? 0 : dataProvider.getList().size();
    }

    /**
     * Adds a child item to this item. !this.isLeaf() otherwise does nothing.
     */
    @Override
    public void addChild(final UpdatableTreeNode item) {
        if (!isLeaf) {
            Console.info("Adding item to " + label + ": " + item.getLabel());
            dataProvider.getList().add(item);
            item.setParent(this);
        } else {
            Console.info("Not adding item to " + label + " as isLeaf");
        }
    }

    @Override
    public void removeChild(final UpdatableTreeNode child) {
        dataProvider.getList().removeIf(internal -> internal.getLabel().equals(child.getLabel()));
    }

    /**
     * Sets the parent of this item. Called from addSubItem().
     * @param parent The parent to add.
     */
    @Override
    public void setParent(final UpdatableTreeNode parent) {
        this.parent = parent;
    }

    /**
     * @return The parent of this item. Returns null if the item is the root.
     */
    @Override
    public UpdatableTreeNode getParent() {
        return parent;
    }

    /**
     * Checks if the name exists within this item, assuming that this item is a folder.
     * @param name The name to check.
     * @return true
     */
    public boolean nameExists(final String name) {
        return dataProvider.getList().stream().anyMatch(
                item -> item.getLabel().equals(name));
    }

    /**
     * Returns whether this is a leaf (file) or not (folder/directory)
     */
    public boolean isLeaf() {
        return isLeaf;
    }

    /**
     * For debugging
     */
    @Override
    public String toString() {
        return label;
    }

    /**
     * Provides access to the children of this item.
     */
    @Override
    public ListDataProvider<UpdatableTreeNode> getDataProvider() {
        return dataProvider;
    }
}
