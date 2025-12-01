package stroom.visualisation.client.presenter.assets;

import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;
import stroom.visualisation.shared.VisualisationAsset;

import com.google.gwt.view.client.ListDataProvider;

import java.util.UUID;

/**
 * Represents an item in the asset tree.
 */
public class VisualisationAssetTreeNode implements UpdatableTreeNode {

    /**
     * The unique ID for this node.
     */
    private final String id;

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
     * Used to create a dummy tree node when first creating the CellTree.
     * This node will be removed when the real content gets added.
     * If this node doesn't get added to the model after construction
     * then the tree doesn't work :-)
     */
    public static VisualisationAssetTreeNode createDummyNode() {
        return new VisualisationAssetTreeNode(null, "", false);
    }

    /**
     * Returns a tree node that is for use as a Folder.
     * This node will have a new UUID.
     * @param label The name of the folder.
     */
    public static VisualisationAssetTreeNode createFolderNode(final String label) {
        return new VisualisationAssetTreeNode(UUID.randomUUID().toString(), label, false);
    }

    /**
     * Returns a tree node that is a new file node, so it has a new ID.
     * @param label The name of the file.
     */
    public static VisualisationAssetTreeNode createNewFileNode(final String label) {
        return new VisualisationAssetTreeNode(UUID.randomUUID().toString(),
                label,
                true);
    }

    /**
     * Returns a tree node that was created from the VisualisationAsset sent from
     * the server. Can create a folder or a file. Either way, the ID will be that of the asset.
     * @param asset The asset that this represents.
     * @param label The label associated with the asset.
     */
    public static VisualisationAssetTreeNode createNodeFromAsset(final VisualisationAsset asset,
                                                                 final String label) {
        return new VisualisationAssetTreeNode(asset.getId(), label, !asset.isFolder());
    }

    /**
     * Constructor
     */
    private VisualisationAssetTreeNode(final String id,
                                      final String label,
                                      final boolean isLeaf) {
        this.id = id;
        this.label = label;
        this.isLeaf = isLeaf;
    }

    /**
     * Returns the ID associated with this tree node.
     */
    public String getId() {
        return id;
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
            dataProvider.getList().add(item);
            item.setParent(this);
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
     *
     * @param label The name to check.
     * @return true if the label exists anywhere. Note that this includes if the
     *         label matches itself.
     */
    @Override
    public boolean labelExists(final String label) {
        return dataProvider.getList().stream().anyMatch(
                item -> item.getLabel().equals(label));
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
