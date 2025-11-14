package stroom.visualisation.client.presenter;

import stroom.util.client.Console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents an item in the asset tree.
 */
public class VisualisationAssetItem {

    /**
     * The name to display for the item
     */
    private final String name;

    /**
     * Whether this is a leaf (file) or not (folder / directory)
     */
    private final boolean isLeaf;

    /**
     * Child items
     */
    private final List<VisualisationAssetItem> directory = new ArrayList<>();

    /**
     * Constructor
     */
    public VisualisationAssetItem(final String name, final boolean isLeaf) {
        this.name = name;
        this.isLeaf = isLeaf;
    }

    /**
     * Returns the name of the item to display
     */
    public String getName() {
        return name;
    }

    /**
     * Adds a child item to this item. !this.isLeaf() otherwise does nothing.
     */
    public void addSubItem(final VisualisationAssetItem item) {
        if (!isLeaf) {
            Console.info("Adding item to " + name + ": " + item.getName());
            directory.add(item);
        } else {
            Console.info("Not adding item to " + name + " as isLeaf");
        }
    }

    /**
     * Returns any children of this item
     */
    public List<VisualisationAssetItem> getSubItems() {
        return Collections.unmodifiableList(directory);
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
    public String toString() {
        return name;
    }
}
