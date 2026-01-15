package stroom.visualisation.client.presenter.assets;

import stroom.util.client.Console;
import stroom.visualisation.shared.VisualisationAsset;

import com.google.gwt.user.client.ui.TreeItem;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents an item in the asset tree.
 */
public class VisualisationAssetTreeItem extends TreeItem {

    /**
     * The unique ID for this node.
     */
    private final String id;

    /** Whether this is a leaf (file) or not (folder / directory) */
    private final boolean isLeaf;

    /** Additional style to use when nothing else matches */
    private static final String DEFAULT_TYPE_STYLE = "visualisation-asset-type-default";

    /** Additional style to use when this is a folder */
    private static final String FOLDER_TYPE_STYLE = "visualisation-asset-type-folder";

    /** Additional style to use when this is an open folder */
    private static final String OPEN_STYLE = "visualisation-asset-folder-open";

    /** Additional style to use when this is a closed folder */
    private static final String CLOSED_STYLE = "visualisation-asset-folder-closed";

    /** Map of extension to CSS class name */
    private static final Map<String, String> TYPE_STYLES = new HashMap<>();

    /*
     * Initialise the map of extension to CSS class name. The class name determines the icon.
     */
    static {
        TYPE_STYLES.put("png",  "visualisation-asset-type-image");
        TYPE_STYLES.put("jpg",  "visualisation-asset-type-image");
        TYPE_STYLES.put("jpeg", "visualisation-asset-type-image");
        TYPE_STYLES.put("gif",  "visualisation-asset-type-image");
        TYPE_STYLES.put("webp", "visualisation-asset-type-image");
        TYPE_STYLES.put("svg",  "visualisation-asset-type-image");
        TYPE_STYLES.put("css",  "visualisation-asset-type-css");
        TYPE_STYLES.put("htm",  "visualisation-asset-type-html");
        TYPE_STYLES.put("html", "visualisation-asset-type-html");
        TYPE_STYLES.put("js",   "visualisation-asset-type-javascript");
    }

    /**
     * Returns a tree node that is for use as a Folder.
     * This node will have a new UUID.
     * @param text The name of the folder.
     */
    public static VisualisationAssetTreeItem createNewFolderItem(final String text) {
        return new VisualisationAssetTreeItem(UUID.randomUUID().toString(), text, false);
    }

    /**
     * Returns a tree node that is a new file node, so it has a new ID.
     * @param text The name of the file.
     */
    public static VisualisationAssetTreeItem createNewFileItem(final String text) {
        return new VisualisationAssetTreeItem(UUID.randomUUID().toString(),
                text,
                true);
    }

    /**
     * Returns a tree node that was created from the VisualisationAsset sent from
     * the server. Can create a folder or a file. Either way, the ID will be that of the asset.
     * @param asset The asset that this represents.
     * @param text The label associated with the asset.
     */
    public static VisualisationAssetTreeItem createItemFromAsset(final VisualisationAsset asset,
                                                                 final String text) {
        Console.info("Creating tree item from asset: " + asset.getId() + ": " + text);
        return new VisualisationAssetTreeItem(asset.getId(), text, !asset.isFolder());
    }

    /**
     * Constructor
     */
    private VisualisationAssetTreeItem(final String id,
                                       final String text,
                                       final boolean isLeaf) {
        super.setText(text);
        this.id = id;
        this.isLeaf = isLeaf;
        setStyle();
        setState(false);
    }

    /**
     * Called from constructor to set the style (icon) applied to this item.
     */
    private void setStyle() {
        final String label = this.getText();
        final String styleNameToAdd;

        if (isLeaf) {
            final int dotIndex = label.lastIndexOf('.');
            if (dotIndex != -1) {
                // Got an extension - look it up
                final String extension = label.substring(dotIndex + 1);
                final String style = TYPE_STYLES.get(extension);
                if (style != null) {
                    styleNameToAdd = style;
                } else {
                    // Default - extension not recognised
                    styleNameToAdd = DEFAULT_TYPE_STYLE;
                }
            } else {
                // No extension - use default
                styleNameToAdd = DEFAULT_TYPE_STYLE;
            }
        } else {
            // Not a leaf so is a folder
            styleNameToAdd = FOLDER_TYPE_STYLE;
        }

        // Set the style in the HTML
        addStyleName(styleNameToAdd);
    }

    /**
     * Intercepts setState() so we can set the style dynamically. Does not fire events.
     * @param open whether the item is open
     */
    @Override
    public void setState(final boolean open) {
        this.setState(open, false);
    }

    /**
     * Intercepts setState() so we can set the style dynamically.
     * Stroom does not show open/closed icons when a folder is empty.
     * @param open whether the item is open
     * @param fireEvents Whether to fire events to tell the system that things have changed.
     */
    @Override
    public void setState(final boolean open, final boolean fireEvents) {
        super.setState(open, fireEvents);
        updateStateStyle();
    }

    /**
     * Updates the styles relating to the open/closed state of the item.
     */
    private void updateStateStyle() {
        if (!isLeaf) {
            if (this.hasChildren()) {
                if (getState()) {
                    addStyleName(OPEN_STYLE);
                    removeStyleName(CLOSED_STYLE);
                } else {
                    addStyleName(CLOSED_STYLE);
                    removeStyleName(OPEN_STYLE);
                }
            } else {
                removeStyleName(OPEN_STYLE);
                removeStyleName(CLOSED_STYLE);
            }
        }
    }

    /**
     * Stores the state (open/closed folders) of the tree in the parameter openIds.
     * @param openIds Must not be null. Somewhere to store the state.
     *                If the item is open then its ID is stored in the set.
     */
    public void storeState(final Set<String> openIds) {
        Console.info("Storing state for " + getText() + ": " + getState() + " -> " + id);
        if (getState()) {
            openIds.add(id);
        }
        for (int i = 0; i < getChildCount(); ++i) {
            final VisualisationAssetTreeItem child = (VisualisationAssetTreeItem) getChild(i);
            child.storeState(openIds);
        }
    }

    /**
     * Restores the state (open/closed folders) of the tree from the parameter openIds.
     * @param openIds Must not be null. If the ID of the item is in the set then the
     *                folder is open.
     */
    public void restoreState(final Set<String> openIds) {
        Console.info("Restoring state for " + getText() + ": " + openIds.contains(id) + " -> " + id);
        setState(openIds.contains(id));
        for (int i = 0; i < getChildCount(); ++i) {
            final VisualisationAssetTreeItem child = (VisualisationAssetTreeItem) getChild(i);
            child.restoreState(openIds);
        }
    }

    /**
     * Returns the ID associated with this tree node.
     */
    public String getId() {
        return id;
    }

    /**
     * Returns whether this item has children.
     */
    public boolean hasChildren() {
        return getChildCount() > 0;
    }

    /**
     * Returns the number of children of this item.
     */
    @Override
    public int getChildCount() {
        return isLeaf ? 0 : super.getChildCount();
    }

    /**
     * Adds a child item to this item. !this.isLeaf() otherwise does nothing.
     */
    @Override
    public void addItem(final TreeItem item) {
        if (!isLeaf) {
            super.addItem(item);
            updateStateStyle();
        }
    }

    /**
     * Checks if the name exists within this item, assuming that this item is a folder.
     *
     * @param text The name to check.
     * @return true if the label exists anywhere. Note that this includes if the
     *         label matches itself.
     */
    public boolean labelExists(final String text) {
        for (int i = 0; i < super.getChildCount(); ++i) {
            final TreeItem item = super.getChild(i);
            if (text.equals(item.getText())) {
                return true;
            }
        }
        return false;
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
        return super.getText();
    }

}
