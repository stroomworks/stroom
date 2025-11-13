/*
 * Copyright 2024 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.visualisation.client.presenter;

import stroom.document.client.event.DirtyEvent;
import stroom.document.client.event.DirtyEvent.DirtyHandler;
import stroom.document.client.event.HasDirtyHandlers;
import stroom.entity.client.presenter.HasToolbar;
import stroom.util.client.Console;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;

import com.google.gwt.cell.client.AbstractCell;
import com.google.gwt.cell.client.Cell;
import com.google.gwt.cell.client.IconCellDecorator;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.safehtml.shared.SafeUri;
import com.google.gwt.user.cellview.client.CellTree;
import com.google.gwt.user.cellview.client.CellTree.Style;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.TreeViewModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shows the Assets - images, css etc - associated with the Visualisation.
 */
public class VisualisationAssetsPresenter
        extends MyPresenterWidget<VisualisationAssetsView>
        implements HasDirtyHandlers, HasToolbar {

    /** Main tree we're displaying */
    private final CellTree cellTree;

    /** True if the UI is readonly, false if read-write */
    private boolean readOnly = false;

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetsPresenter(final EventBus eventBus,
                                        final VisualisationAssetsView view) {
        super(eventBus, view);

        final TreeViewModel model = new AssetTreeModel();
        final AssetItem rootItem = new AssetItem("root", false);

        // TODO Dummy data needs replacing with live data
        final AssetItem dir1 = new AssetItem("dir1", false);
        final AssetItem subdir1 = new AssetItem("dir2", false);
        final AssetItem file1 = new AssetItem("file1", true);
        final AssetItem file2 = new AssetItem("file2", true);
        subdir1.addSubItem(file1);
        subdir1.addSubItem(file2);
        dir1.addSubItem(subdir1);
        rootItem.addSubItem(dir1);

        cellTree = new CellTree(model, rootItem, new AssetTreeResources());
        this.getView().setCellTree(cellTree);
    }

    @Override
    protected void onBind() {
        // Add listeners for dirty events.
    }

    /**
     * Dirty mechanism across the tabs.
     */
    @Override
    public HandlerRegistration addDirtyHandler(final DirtyHandler handler) {
        return addHandlerToSource(DirtyEvent.getType(), handler);
    }

    /**
     * Sets up the toolbar for the tab.
     */
    @Override
    public List<Widget> getToolbars() {
        return List.of();
    }

    /**
     * Called during UI setup to set whether this UI is in readonly state.
     */
    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;

        // TODO updateState()
    }

    // --------------------------------------------------------------------------------
    /**
     * Represents an item in the asset tree.
     */
    private static class AssetItem {
        /** The name to display for the item */
        private final String name;

        /** Whether this is a leaf (file) or not (folder / directory) */
        private final boolean isLeaf;

        /** Child items */
        private final List<AssetItem> directory = new ArrayList<>();

        /** Constructor */
        public AssetItem(final String name, final boolean isLeaf) {
            this.name = name;
            this.isLeaf = isLeaf;
        }

        /** Returns the name of the item to display */
        public String getName() {
            return name;
        }

        /** Adds a child item to this item. !this.isLeaf() otherwise does nothing. */
        public void addSubItem(final AssetItem item) {
            if (!isLeaf) {
                Console.info("Adding item to " + name + ": " + item.getName());
                directory.add(item);
            } else {
                Console.info("Not adding item to " + name + " as isLeaf");
            }
        }

        /** Returns any children of this item */
        public List<AssetItem> getSubItems() {
            return Collections.unmodifiableList(directory);
        }

        /** Returns whether this is a leaf (file) or not (folder/directory) */
        public boolean isLeaf() {
            return isLeaf;
        }

        /** For debugging */
        public String toString() {
            return name;
        }
    }

    // --------------------------------------------------------------------------------
    /**
     * Models the assets within the tree.
     */
    private static class AssetTreeModel implements TreeViewModel {

        /** Height and width of the (square) icons */
        private static final int ICON_DIM = 16;

        /** Folder icon URL */
        private static final String FOLDER_URL = "/ui/images/background/folder.png";

        /** Folder icon */
        private static final ImageResource FOLDER_ICON =
                new AssetImageResource(ICON_DIM, ICON_DIM, FOLDER_URL);

        @Override
        public <T> NodeInfo<?> getNodeInfo(final T parent) {
            Console.info("getNodeInfo: " + parent + ": " + parent.getClass());
            final ListDataProvider<AssetItem> dataProvider = new ListDataProvider<>();
            final Cell<AssetItem> cell;

            if (parent instanceof final AssetItem parentItem) {
                Console.info("-> isLeaf: " + parentItem.isLeaf());
                if (!parentItem.isLeaf()) {
                    // Must be a folder, so find its children and display a folder icon
                    final List<AssetItem> subItems = parentItem.getSubItems();
                    for (final AssetItem assetItem : subItems) {
                        dataProvider.getList().add(assetItem);
                    }
                }
                final Cell<AssetItem> textCell = new AbstractCell<AssetItem>() {
                    @Override
                    public void render(final Context context, final AssetItem value, final SafeHtmlBuilder sb) {
                        if (value != null) {
                            sb.appendEscaped(value.getName());
                        }
                    }
                };
                cell = new IconCellDecorator<>(FOLDER_ICON, textCell) {
                    @Override
                    protected boolean isIconUsed(final AssetItem assetItem) {
                        return !assetItem.isLeaf();
                    }
                };
            } else {
                // Shouldn't happen but keeps final happy
                cell = new AbstractCell<AssetItem>() {
                    @Override
                    public void render(final Context context, final AssetItem value, final SafeHtmlBuilder sb) {
                        // Do nothing
                    }
                };
            }

            return new DefaultNodeInfo<>(dataProvider, cell);
        }

        @Override
        public boolean isLeaf(final Object objectItem) {
            Console.info("isLeaf: " + objectItem);
            if (objectItem instanceof final AssetItem assetItem) {
                Console.info("-> " + assetItem.isLeaf);
                return assetItem.isLeaf();
            } else {
                // Shouldn't happen
                return false;
            }
        }

    }

    // --------------------------------------------------------------------------------
    /**
     * Implementation of the SafeUri class for the tree.
     * Doesn't do any filtering or checking.
     */
    private static class AssetSafeUri implements SafeUri {

        private final String uri;

        public AssetSafeUri(final String uri) {
            this.uri = uri;
        }

        @Override
        public String asString() {
            return uri;
        }

    }

    // --------------------------------------------------------------------------------
    /**
     * Implements the ImageResource for the tree.
     */
    private static class AssetImageResource implements ImageResource {

        /** Height of the image in the img tag */
        private final int height;

        /** Width of the image in the img tag */
        private final int width;

        /** The URL of the image to display */
        private final String url;

        /** Constructor */
        public AssetImageResource(final int height,
                                  final int width,
                                  final String url) {
            this.height = height;
            this.width = width;
            this.url = url;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getLeft() {
            return 0;
        }

        @Override
        public SafeUri getSafeUri() {
            return new AssetSafeUri(url);
        }

        @Override
        public int getTop() {
            return 0;
        }

        @Override
        public String getURL() {
            return url;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public boolean isAnimated() {
            return false;
        }

        @Override
        public String getName() {
            return "";
        }
    }
    // --------------------------------------------------------------------------------
    /**
     * Provides the images for the CellTree
     */
    private static class AssetTreeResources implements CellTree.Resources {

        private static final int DIM = 10;
        private static final AssetImageResource CELL_CLOSED =
                new AssetImageResource(DIM, DIM, "/ui/images/background/arrow-right.png");
        private static final AssetImageResource CELL_OPEN =
                new AssetImageResource(DIM, DIM, "/ui/images/background/arrow-down.png");
        private static final Style STYLE = new AssetTreeStyle();

        @Override
        public ImageResource cellTreeClosedItem() {
            return CELL_CLOSED;
        }

        @Override
        public ImageResource cellTreeLoading() {
            return CELL_CLOSED; // TODO
        }

        @Override
        public ImageResource cellTreeOpenItem() {
            return CELL_OPEN;
        }

        @Override
        public ImageResource cellTreeSelectedBackground() {
            return CELL_CLOSED; // TODO
        }

        @Override
        public Style cellTreeStyle() {
            return STYLE;
        }
    }

    // --------------------------------------------------------------------------------
    /**
     * Customises style in the tree by providing class names.
     */
    private static class AssetTreeStyle implements Style {

        @Override
        public String cellTreeEmptyMessage() {
            return "visualisation-asset-tree-empty-message";
        }

        @Override
        public String cellTreeItem() {
            return "visualisation-asset-tree-item";
        }

        @Override
        public String cellTreeItemImage() {
            return "visualisation-asset-tree-item-image";
        }

        @Override
        public String cellTreeItemImageValue() {
            return "visualisation-asset-tree-item-image-value";
        }

        @Override
        public String cellTreeItemValue() {
            return "visualisation-asset-tree-item-value";
        }

        @Override
        public String cellTreeKeyboardSelectedItem() {
            return "visualisation-asset-tree-keyboard-selected-item";
        }

        @Override
        public String cellTreeOpenItem() {
            return "visualisation-asset-tree-open-item";
        }

        @Override
        public String cellTreeSelectedItem() {
            return "visualisation-asset-tree-selected-item";
        }

        @Override
        public String cellTreeShowMoreButton() {
            return "visualisation-asset-tree-show-more-button";
        }

        @Override
        public String cellTreeTopItem() {
            return "visualisation-asset-tree-top-item";
        }

        @Override
        public String cellTreeTopItemImage() {
            return "visualisation-asset-tree-top-item-image";
        }

        @Override
        public String cellTreeTopItemImageValue() {
            return "visualisation-asset-tree-top-item-image-value";
        }

        @Override
        public String cellTreeWidget() {
            return "visualisation-asset-tree-widget";
        }

        @Override
        public boolean ensureInjected() {
            return false;
        }

        @Override
        public String getText() {
            return "visualisation-asset-tree-text";
        }

        @Override
        public String getName() {
            return "visualisation-asset-tree-name";
        }

    }

    // --------------------------------------------------------------------------------
    /**
     * Interface for View.
     */
    public interface VisualisationAssetsView extends View {

        /**
         * Sets the cell tree within the view.
         */
        void setCellTree(final CellTree cellTree);
    }
}
