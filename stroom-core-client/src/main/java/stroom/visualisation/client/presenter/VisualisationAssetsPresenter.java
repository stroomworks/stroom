/*
 * Copyright 2025 Crown Copyright
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
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.util.client.Console;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;
import stroom.visualisation.client.presenter.assets.VisualisationAssetItem;
import stroom.visualisation.client.presenter.assets.VisualisationAssetTreeModel;
import stroom.visualisation.client.presenter.assets.VisualisationAssetsImageResource;
import stroom.visualisation.client.presenter.tree.UpdatableTreeModel;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.InlineSvgButton;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.ShowMenuEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupPosition.PopupLocation;
import stroom.widget.util.client.Rect;

import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.user.cellview.client.CellTree;
import com.google.gwt.user.cellview.client.CellTree.Style;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.SingleSelectionModel;
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
        implements HasDirtyHandlers, HasToolbar, VisualisationAssetsAddFileCallback {

    /** Main tree we're displaying */
    private final CellTree cellTree;

    private final UpdatableTreeModel treeModel;

    /** Selection model */
    private final SingleSelectionModel<UpdatableTreeNode> selectionModel = new SingleSelectionModel<>();

    /** True if the UI is readonly, false if read-write */
    private boolean readOnly = false;

    /** Button to add stuff to the tree */
    private final InlineSvgButton addButton = new InlineSvgButton();

    /** Button to delete stuff from the tree */
    private final InlineSvgButton deleteButton = new InlineSvgButton();

    /** Dialog that appears when user wants to upload a file */
    private final VisualisationAssetsUploadFileDialogPresenter uploadFileDialog;

    /** Dialog that appears when user wants to add a folder */
    private final VisualisationAssetsAddFolderDialogPresenter addFolderDialog;

    /** List of any resource keys for files that need to be saved TODO allow items to be overridden */
    private final List<ResourceKey> uploadedFileResourceKeys = new ArrayList<>();

    final List<Item> menuItems = new ArrayList<>();

    /** Hidden root item in the tree. Not displayed. */
    final static VisualisationAssetItem ROOT_ITEM = new VisualisationAssetItem("root", false);

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetsPresenter(final EventBus eventBus,
                                        final VisualisationAssetsView view,
                                        final VisualisationAssetsUploadFileDialogPresenter uploadFileDialog,
                                        final VisualisationAssetsAddFolderDialogPresenter addFolderDialog) {
        super(eventBus, view);
        this.uploadFileDialog = uploadFileDialog;
        this.addFolderDialog = addFolderDialog;

        treeModel = new VisualisationAssetTreeModel(selectionModel);
        createDummyData();

        selectionModel.addSelectionChangeHandler(event ->
                VisualisationAssetsPresenter.this.onSelectionChange());

        cellTree = new CellTree(treeModel, ROOT_ITEM, new AssetTreeResources());
        cellTree.setAnimation(CellTree.SlideAnimation.create());
        cellTree.setAnimationEnabled(true);
        this.getView().setCellTree(cellTree);
    }

    /**
     * TODO Dummy data needs replacing with live data
     */
    private void createDummyData() {
        Console.info("Creating dummy data");
        final VisualisationAssetItem dir1 = new VisualisationAssetItem("dir1", false);
        final VisualisationAssetItem subdir1 = new VisualisationAssetItem("dir2", false);
        final VisualisationAssetItem file1 = new VisualisationAssetItem("file1.svg", true);
        final VisualisationAssetItem file2 = new VisualisationAssetItem("file2.png", true);
        final VisualisationAssetItem file3 = new VisualisationAssetItem("file3.gif", true);
        final VisualisationAssetItem file4 = new VisualisationAssetItem("file4.jpg", true);
        final VisualisationAssetItem file5 = new VisualisationAssetItem("file5.jpeg", true);
        final VisualisationAssetItem file6 = new VisualisationAssetItem("file6.webp", true);
        final VisualisationAssetItem file7 = new VisualisationAssetItem("file7.css", true);
        final VisualisationAssetItem file8 = new VisualisationAssetItem("file8.htm", true);
        final VisualisationAssetItem file9 = new VisualisationAssetItem("file9.html", true);
        final VisualisationAssetItem file10 = new VisualisationAssetItem("file10.unknown", true);
        final VisualisationAssetItem file11 = new VisualisationAssetItem("file11.", true);
        final VisualisationAssetItem file12 = new VisualisationAssetItem("file12", true);
        final VisualisationAssetItem file13 = new VisualisationAssetItem(".file13", true);
        final VisualisationAssetItem file14 = new VisualisationAssetItem("file14.js", true);
        treeModel.add(subdir1, file1);
        treeModel.add(subdir1, file2);
        treeModel.add(subdir1, file3);
        treeModel.add(subdir1, file4);
        treeModel.add(subdir1, file5);
        treeModel.add(subdir1, file6);
        treeModel.add(subdir1, file7);
        treeModel.add(subdir1, file8);
        treeModel.add(subdir1, file9);
        treeModel.add(subdir1, file10);
        treeModel.add(subdir1, file11);
        treeModel.add(subdir1, file12);
        treeModel.add(subdir1, file13);
        treeModel.add(subdir1, file14);
        treeModel.add(dir1, subdir1);
        treeModel.add(ROOT_ITEM, dir1);

    }

    @Override
    protected void onBind() {
        // Add listeners for dirty events.
        super.onBind();

        //createDummyData();

        // Create the Add menu
        menuItems.add(new IconMenuItem.Builder()
                .priority(0)
                .icon(SvgImage.FOLDER)
                .iconColour(IconColour.BLUE)
                .text("Add Folder")
                .command(this::onAddFolder)
                .enabled(true)
                .build());
        menuItems.add(new IconMenuItem.Builder()
                .priority(1)
                .icon(SvgImage.FILE)
                .iconColour(IconColour.BLUE)
                .text("Upload File")
                .command(this::onUploadFile)
                .enabled(true)
                .build());

        addButton.addClickHandler(event -> {
            final Rect relativeRect = new Rect(addButton.getElement()).grow(3);
            final PopupPosition position = new PopupPosition(relativeRect, PopupLocation.RIGHT);
            ShowMenuEvent.builder()
                    .items(menuItems)
                    .popupPosition(position)
                    .allowCloseOnMoveLeft()
                    .fire(this);
        });

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
        addButton.setSvg(SvgImage.ADD);
        addButton.setTitle("Add file");
        addButton.setVisible(true);

        deleteButton.setSvg(SvgImage.DELETE);
        deleteButton.setTitle("Delete");
        deleteButton.setVisible(true);
        deleteButton.addClickHandler(event -> VisualisationAssetsPresenter.this.onDeleteButtonClick());

        final ButtonPanel toolbar = new ButtonPanel();
        toolbar.addButton(addButton);
        toolbar.addButton(deleteButton);

        // Ensure state is set correctly
        updateState();

        return List.of(toolbar);
    }

    /**
     * Called during UI setup to set whether this UI is in readonly state.
     */
    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;

        // TODO updateState()
    }

    /**
     * Called when the selection changes.
     */
    private void onSelectionChange() {
        updateState();
    }

    /**
     * Called when Add button / Add Folder is clicked.
     * Inserts a new folder with the currently selected folder.
     */
    private void onAddFolder() {
        final UpdatableTreeNode folderNode = findFolderForSelectedNode();
        final String path = getItemPath(folderNode);
        final ShowPopupEvent.Builder popupEventBuilder = new ShowPopupEvent.Builder(addFolderDialog);
        addFolderDialog.setupPopup(popupEventBuilder, path);
        popupEventBuilder
                .onHideRequest(event -> {
                            if (event.isOk()) {
                                // Ok pressed
                                // TODO check valid

                                final UpdatableTreeNode newFolderNode =
                                        new VisualisationAssetItem(addFolderDialog.getView().getFolderName(),
                                                false);
                                treeModel.add(folderNode, newFolderNode);
                            }
                            event.hide();
                        }
                )
                .fire();

    }

    /**
     * @return The node that we're going to add things to.
     */
    private UpdatableTreeNode findFolderForSelectedNode() {
        UpdatableTreeNode selectedFolder = selectionModel.getSelectedObject();

        if (selectedFolder != null) {
            // If trying to add to a file then find its parent folder
            if (selectedFolder.isLeaf()) {
                selectedFolder = selectedFolder.getParent();
            }
        } else {
            // Nothing selected so add item at root
            selectedFolder = ROOT_ITEM;
        }

        return selectedFolder;
    }

    /**
     * Called when Add Button / Upload File is clicked.
     * Inserts a new item within the currently selected folder.
     */
    private void onUploadFile() {
        final UpdatableTreeNode folderNode = findFolderForSelectedNode();
        final String path = getItemPath(folderNode);
        uploadFileDialog.fireShowPopup(this, folderNode, path);
    }

    /**
     * Callback from the Add dialog that adds a file that has been uploaded.
     * @param parentFolderNode The node that the file has been added to.
     * @param fileName The name of the file that was uploaded.
     * @param resourceKey The resource key of the file, so that the server can find it later.
     */
    @Override
    public void addUploadedFile(final UpdatableTreeNode parentFolderNode,
                                final String fileName,
                                final ResourceKey resourceKey) {

        Console.info("Adding " + fileName + " to node " + parentFolderNode);

        final UpdatableTreeNode newFileNode = new VisualisationAssetItem(fileName, true);
        if (parentFolderNode == null) {
            Console.info("parent node is null - trying to add now...");
        }
        treeModel.add(parentFolderNode, newFileNode);

        // TODO Use the path as a kind of key to the resourceKey so we can handle overwrites?
        // TODO What about renames?
        uploadedFileResourceKeys.add(resourceKey);

        // Mark the document as dirty
        DirtyEvent.fire(this, true);
    }

    /**
     * Returns the path to the given item.
     * @param item The item to find the path to. Can be null if this is the root path.
     * @return The path as a String, with / separators.
     */
    private String getItemPath(final UpdatableTreeNode item) {
        final List<String> pathList = new ArrayList<>();
        UpdatableTreeNode node = item;
        while (node != null && !node.equals(ROOT_ITEM)) {
            pathList.add(node.getLabel());
            node = node.getParent();
        }
        pathList.sort(Collections.reverseOrder());
        return "/" + String.join("/", pathList);
    }

    /**
     * Called when the Delete button is clicked.
     * Deletes the currently selected item.
     */
    private void onDeleteButtonClick() {
        final UpdatableTreeNode item = selectionModel.getSelectedObject();
        if (item != null) {
            treeModel.remove(item);
        }
    }

    /**
     * Sets the state of the UI when things have changed.
     */
    private void updateState() {
        final UpdatableTreeNode item = selectionModel.getSelectedObject();
        if (item == null) {
            // Assume the root item is selected
            addButton.setEnabled(true);
            deleteButton.setEnabled(false);
        } else {
            addButton.setEnabled(true);
            deleteButton.setEnabled(true);
        }
    }

    // --------------------------------------------------------------------------------

    // --------------------------------------------------------------------------------
    /**
     * Provides the images for the CellTree
     */
    private static class AssetTreeResources implements CellTree.Resources {

        private static final int DIM = 10;
        private static final VisualisationAssetsImageResource CELL_CLOSED =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-right.png");
        private static final VisualisationAssetsImageResource CELL_OPEN =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-down.png");
        private static final VisualisationAssetsImageResource LOADING =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/ellipses-horizontal.png");
        private static final Style STYLE = new AssetTreeStyle();

        @Override
        public ImageResource cellTreeClosedItem() {
            return CELL_CLOSED;
        }

        @Override
        public ImageResource cellTreeLoading() {
            return LOADING;
        }

        @Override
        public ImageResource cellTreeOpenItem() {
            return CELL_OPEN;
        }

        @Override
        public ImageResource cellTreeSelectedBackground() {
            return null;
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
