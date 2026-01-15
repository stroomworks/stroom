package stroom.visualisation.client.presenter.assets;

import stroom.util.client.Console;
import stroom.visualisation.shared.VisualisationAsset;

import com.google.gwt.user.client.ui.TreeItem;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an item in the asset tree.
 */
public class VisualisationAssetTreeItem extends TreeItem {

    /**
     * The unique ID for this node.
     */
    private final String id;

    /**
     * Whether this is a leaf (file) or not (folder / directory)
     */
    private final boolean isLeaf;

    /** Additional style to use when nothing else matches */
    private static final String DEFAULT_TYPE_STYLE = "asset-type-default";

    /** Additional style to use when this is a folder */
    private static final String FOLDER_TYPE_STYLE = "asset-type-folder";

    /** Map of extension to image */
    private static final Map<String, String> TYPE_STYLES = new HashMap<>();

    /*
     * Initialise the map of extension to file icon.
     */
    static {
        TYPE_STYLES.put("png",  "asset-type-image");
        TYPE_STYLES.put("jpg",  "asset-type-image");
        TYPE_STYLES.put("jpeg", "asset-type-image");
        TYPE_STYLES.put("gif",  "asset-type-image");
        TYPE_STYLES.put("webp", "asset-type-image");
        TYPE_STYLES.put("svg",  "asset-type-image");
        TYPE_STYLES.put("css",  "asset-type-css");
        TYPE_STYLES.put("htm",  "asset-type-html");
        TYPE_STYLES.put("html", "asset-type-html");
        TYPE_STYLES.put("js",   "asset-type-javascript");
    }

    /**
     * Returns a tree node that is for use as a Folder.
     * This node will have a new UUID.
     * @param text The name of the folder.
     */
    public static VisualisationAssetTreeItem createNewFolderItem(final String text) {
        Console.info("Creating new folder node '" + text + "'");
        return new VisualisationAssetTreeItem(UUID.randomUUID().toString(), text, false);
    }

    /**
     * Returns a tree node that is a new file node, so it has a new ID.
     * @param text The name of the file.
     */
    public static VisualisationAssetTreeItem createNewFileItem(final String text) {
        Console.info("Creating new file node '" + text + "'");
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
        Console.info("Creating node from asset " + asset);
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
    }

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
        Console.info("Label: " + getText() + "; Setting style name " + styleNameToAdd);
        addStyleName(styleNameToAdd);
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
