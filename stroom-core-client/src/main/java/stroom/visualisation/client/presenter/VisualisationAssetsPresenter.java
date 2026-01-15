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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.DirtyEvent;
import stroom.document.client.event.DirtyEvent.DirtyHandler;
import stroom.document.client.event.HasDirtyHandlers;
import stroom.entity.client.presenter.HasToolbar;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.util.client.Console;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;
import stroom.visualisation.client.presenter.assets.VisualisationAssetTreeItem;
import stroom.visualisation.client.presenter.assets.VisualisationAssetsImageResource;
import stroom.visualisation.shared.VisualisationAsset;
import stroom.visualisation.shared.VisualisationAssetResource;
import stroom.visualisation.shared.VisualisationAssets;
import stroom.visualisation.shared.VisualisationDoc;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.InlineSvgButton;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.ShowMenuEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.popup.client.presenter.PopupPosition.PopupLocation;
import stroom.widget.util.client.Rect;

import com.google.gwt.core.client.GWT;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.user.cellview.client.CellTree.Style;
import com.google.gwt.user.client.ui.Tree;
import com.google.gwt.user.client.ui.TreeItem;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows the Assets - images, css etc - associated with the Visualisation.
 */
public class VisualisationAssetsPresenter
        extends MyPresenterWidget<VisualisationAssetsView>
        implements HasDirtyHandlers, HasToolbar, VisualisationAssetsAddFileCallback {

    /** Rest factory to trigger storing file uploads */
    private final RestFactory restFactory;

    /** Tree */
    Tree tree = new Tree(new AssetTreeResources(), true);

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

    /** List of any resource keys for files that need to be saved */
    private final Map<String, ResourceKey> uploadedFileResourceKeys = new HashMap<>();

    /** Set of the node IDs that are open when the document is saved */
    private final Set<String> nodeIdToOpenState = new HashSet<>();

    /** Items in the context menu */
    private final List<Item> menuItems = new ArrayList<>();

    /** Slash / character */
    private final static String SLASH = "/";

    /** REST interface */
    private final static VisualisationAssetResource VISUALISATION_ASSET_RESOURCE =
            GWT.create(VisualisationAssetResource.class);

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetsPresenter(final EventBus eventBus,
                                        final VisualisationAssetsView view,
                                        final RestFactory restFactory,
                                        final VisualisationAssetsUploadFileDialogPresenter uploadFileDialog,
                                        final VisualisationAssetsAddFolderDialogPresenter addFolderDialog) {
        super(eventBus, view);

        this.restFactory = restFactory;
        this.uploadFileDialog = uploadFileDialog;
        this.addFolderDialog = addFolderDialog;

        tree.setStylePrimaryName("visualisation-asset-tree");
        tree.addSelectionHandler(event -> {
            Console.info("Item selected: " + tree.getSelectedItem());
            VisualisationAssetsPresenter.this.onSelectionChange();
        });
        /*
        treeModel = new VisualisationAssetTreeModel(selectionModel,
                (node, label) ->
                        VisualisationAssetsPresenter.this.getNonClashingLabel(node.getParent(), label),
                this::setDirty,
                this::isReadOnly);
        */
        this.getView().setTree(tree);

    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    protected void onBind() {
        // Add listeners for dirty events.
        super.onBind();

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
     * Called by VisualisationPresenter when the document is loaded.
     * @param docRef Document reference
     * @param document Document
     * @param readOnly Whether this doc is readonly
     */
    public void onRead(final DocRef docRef,
                       final VisualisationDoc document,
                       final boolean readOnly) {

        // Set the readonly flag
        this.readOnly = readOnly;

        // Update UI state
        updateState();

        // Get the assets associated with the document (async)
        this.fetchAssets(document);
    }

    /**
     * Convert the paths in the VisualisationAssets into the tree model.
     * @param assets The list of assets from the server. Might be null.
     */
    private void addPathsToTree(final List<VisualisationAsset> assets) {
        if (assets != null) {
            // Convert list of paths into a tree
            for (final VisualisationAsset asset : assets) {
                String path = asset.getPath();
                // Ignore leading slash
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                final String[] pathItems = path.split(SLASH);
                //VisualisationAssetTreeNode node = ROOT_NODE;
                TreeItem treeItem = null;

                for (int iPath = 0; iPath < pathItems.length; ++iPath) {
                    final String pathItem = pathItems[iPath];
                    final boolean isLast = iPath == pathItems.length - 1;

                    // Search for anything existing that matches this pathItem
                    TreeItem existingTreeItem = null;
                    if (treeItem == null) {
                        for (int iChild = 0; iChild < tree.getItemCount(); ++iChild) {
                            final TreeItem item = tree.getItem(iChild);
                            if (pathItem.equals(item.getText())) {
                                existingTreeItem = item;
                                break;
                            }
                        }
                    } else {
                        for (int iChild = 0; iChild < treeItem.getChildCount(); ++iChild) {
                            final TreeItem item = treeItem.getChild(iChild);
                            if (pathItem.equals(item.getText())) {
                                existingTreeItem = item;
                                break;
                            }
                        }
                    }

                    final TreeItem newChildItem;
                    if (existingTreeItem == null) {
                        if (isLast) {
                            // Last item so set whether it is a folder or not
                            // The last item takes the ID of the asset too.
                            newChildItem = VisualisationAssetTreeItem.createItemFromAsset(asset, pathItem);
                        } else {
                            // Not last item so must be a folder
                            // Its ID isn't important at this stage so it gets a new ID
                            newChildItem = VisualisationAssetTreeItem.createNewFolderItem(pathItem);
                        }
                        if (treeItem == null) {
                            tree.addItem(newChildItem);
                        } else {
                            treeItem.addItem(newChildItem);
                        }
                    } else {
                        newChildItem = existingTreeItem;
                    }

                    treeItem = newChildItem;
                }
            }
            sortTree();
        }
    }

    /**
     * Called by VisualisationPresenter when the document is saved.
     * @param document Document to store stuff in
     * @return The updated document.
     */
    public VisualisationDoc onWrite(final VisualisationDoc document) {

        // Kick off storing the uploads and assets
        storeAssets(document);

        return document;
    }

    /**
     * Called from onRead() to pull down the assets from the server.
     * Uses REST to grab the assets and display them within the tree control.
     * Async.
     * @param document The document received from the server.
     */
    private void fetchAssets(final VisualisationDoc document) {
        final String ownerId = document.getUuid();
        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.fetchAssets(ownerId))
                .onSuccess(assets -> {
                    // Clear any existing content from the tree
                    tree.clear();

                    // Put the new content in
                    addPathsToTree(assets.getAssets());

                    // Make sure UI state is correct
                    updateState();
                })
                .onFailure(error -> {
                    AlertEvent.fireError(this,
                            "Error downloading assets for this visualisation: " + error.getMessage(),
                            null);
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * Called from onWrite() to register all the uploaded documents
     * and move them from their temporary storage into permanent storage.
     */
    private void storeAssets(final VisualisationDoc document) {

        final VisualisationAssets assets = new VisualisationAssets(document.getUuid(), uploadedFileResourceKeys);
        treeToAssets(assets);

        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.updateAssets(document.getUuid(), assets))
                .onSuccess(result -> {
                    if (result) {
                        // Great it worked - clear the list of uploaded files
                        uploadedFileResourceKeys.clear();
                    } else {
                        AlertEvent.fireError(this,
                                "Error storing assets",
                                null);
                    }
                })
                .onFailure(error -> {
                    AlertEvent.fireError(this,
                            "Error storing assets: " + error.getMessage(),
                            null);
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * Convert the tree into a list of assets. Also stores the open state of the tree,
     * so that it can be restored if necessary.
     * @param assets Where to put the assets
     */
    private void treeToAssets(final VisualisationAssets assets) {
        nodeIdToOpenState.clear();
        //recurseTreeToAssets(ROOT_NODE, assets, nodeIdToOpenState);

        for (int i = 0; i < tree.getItemCount(); ++i) {
            recurseTreeToAssets(tree.getItem(i), assets, nodeIdToOpenState);
        }

    }

    /**
     * Recursive function called from treeToAssets().
     */
    private void recurseTreeToAssets(final TreeItem treeItem,
                                     final VisualisationAssets assets,
                                     final Set<String> openIds) {

        if (treeItem instanceof final VisualisationAssetTreeItem assetTreeItem) {

            // Store the open state of this node
            if (treeItem.getState()) {
                openIds.add(assetTreeItem.getId());
            } else {
                Console.info("Closed node found: " + assetTreeItem.getId() + " -> " + assetTreeItem.getText());
            }

            // Store anything without children.
            // So we store folders if they don't have any children, and we store files.
            if (!assetTreeItem.hasChildren()) {
                // No more nodes so store path
                final String path = getItemPath(assetTreeItem);

                final VisualisationAsset asset = new VisualisationAsset(
                        assetTreeItem.getId(),
                        path,
                        !assetTreeItem.isLeaf());
                assets.addAsset(asset);
            } else {
                // More nodes so recurse
                for (int i = 0; i < assetTreeItem.getChildCount(); ++i) {
                    recurseTreeToAssets(assetTreeItem.getChild(i), assets, openIds);
                }
            }
        } else {
            Console.error("Tree node is not a VisualisationAssetTreeNode");
        }
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
        if (!readOnly) {
            final TreeItem folderItem = findFolderForSelectedItem();
            final String path = getItemPath(folderItem);
            final ShowPopupEvent.Builder popupEventBuilder = new ShowPopupEvent.Builder(addFolderDialog);
            addFolderDialog.setupPopup(popupEventBuilder, path);
            popupEventBuilder
                    .onHideRequest(event -> {
                                if (event.isOk()) {
                                    // Ok pressed
                                    if (addFolderDialog.isValid()) {
                                        final String folderName = getNonClashingLabel(
                                                folderItem,
                                                addFolderDialog.getView().getFolderName());
                                        final TreeItem newFolderNode =
                                                VisualisationAssetTreeItem.createNewFolderItem(folderName);
                                        if (folderItem == null) {
                                            tree.addItem(newFolderNode);
                                        } else {
                                            folderItem.addItem(newFolderNode);
                                        }
                                        // TODO Sort the children
                                        setDirty();
                                        event.hide();
                                    } else {
                                        AlertEvent.fireWarn(this, "Folder name not set", event::reset);
                                    }
                                } else {
                                    // Cancel pressed
                                    event.hide();
                                }
                            }
                    )
                    .fire();
        }

    }

    /**
     * Sorts the whole tree, from root to leaf.
     */
    private void sortTree() {
        final List<VisualisationAssetTreeItem> childItems = new ArrayList<>();
        for (int i = 0; i < tree.getItemCount(); ++i) {
            childItems.add((VisualisationAssetTreeItem) tree.getItem(i));
        }
        childItems.sort(new TreeItemComparator());
        tree.removeItems();
        for (final VisualisationAssetTreeItem treeItem : childItems) {
            if (!treeItem.isLeaf()) {
                recurseSortTree(treeItem);
            }
            tree.addItem(treeItem);
            // TODO DELETE
            Console.info("Tree item " + treeItem.getText() + " has HTML '" + treeItem.getHTML() + "'");
        }
    }

    /**
     * Called from sortTree() to recurse down the tree, sorting it.
     * @param assetTreeItem The node to sort and recurse.
     */
    private void recurseSortTree(final VisualisationAssetTreeItem assetTreeItem) {
        final List<VisualisationAssetTreeItem> childItems = new ArrayList<>();
        for (int i  = 0; i < assetTreeItem.getChildCount(); ++i) {
            childItems.add((VisualisationAssetTreeItem) assetTreeItem.getChild(i));
        }
        childItems.sort(new TreeItemComparator());
        assetTreeItem.removeItems();
        for (final VisualisationAssetTreeItem childTreeItem : childItems) {
            if (!childTreeItem.isLeaf()) {
                recurseSortTree(childTreeItem);
            }
            assetTreeItem.addItem(childTreeItem);
        }
    }

    /**
     * Comparator for sorting tree items.
     */
    private static class TreeItemComparator implements Comparator<VisualisationAssetTreeItem> {

        @Override
        public int compare(final VisualisationAssetTreeItem treeItem1, final VisualisationAssetTreeItem treeItem2) {
            if (!treeItem1.isLeaf() && treeItem2.isLeaf()) {
                // 1 is folder, 2 is file so 1 comes first
                return -1;
            } else if (treeItem1.isLeaf() && !treeItem2.isLeaf()) {
                // 1 is file, 2 is folder so 2 comes first
                return 1;
            } else {
                // Sort on label
                return treeItem1.getText().compareTo(treeItem2.getText());
            }
        }
    }

    /**
     * Sorts the children of a node into folders first, then alphabetical order
     * @param node The node whose children you want to sort.
     *//*
    private void sortNodeChildren(final UpdatableTreeNode node) {
        final List<UpdatableTreeNode> children = node.getDataProvider().getList();
        children.sort((node1, node2) -> {
            if (!node1.isLeaf() && node2.isLeaf()) {
                // node1 is folder, node2 is file so node1 comes first
                return -1;
            } else if (node1.isLeaf() && !node2.isLeaf()) {
                // node1 is file, node2 is folder so node2 comes first
                return 1;
            } else {
                // Sort on label
                return node1.getLabel().compareTo(node2.getLabel());
            }
        });
    }*/

    /**
     * Generates a label that doesn't clash with other files/folders in the same directory.
     * Adds an integer to the end, incrementing until an integer is found that doesn't
     * clash with anything else.
     * @param parentItem The tree item that holds the directory.
     * @param label The label that we're trying to put into the directory.
     * @return A label that doesn't clash with anything else.
     */
    @Override
    public String getNonClashingLabel(final TreeItem parentItem, final String label) {
        String nonClashingLabel = label;

        if (parentItem instanceof final VisualisationAssetTreeItem assetParentItem) {
            int i = 1;
            while (assetParentItem.labelExists(nonClashingLabel)) {
                final int iDot = label.lastIndexOf('.');
                String namePart = label;
                String extPart = "";
                if (iDot != -1) {
                    namePart = label.substring(0, iDot);
                    extPart = label.substring(iDot);
                }

                nonClashingLabel = namePart + "-" + i + extPart;
                i++;
            }
        } else {
            Console.error("Unknown tree item type");
        }

        return nonClashingLabel;
    }

    /**
     * Finds the treeItem that we're going to add things to. Either the selected folder,
     * or the parent of the selected file (can't add things to a file).
     * @return The node that we're going to add things to. Null if we're adding to the root item.
     */
    private TreeItem findFolderForSelectedItem() {
        final TreeItem selectedTreeItem = tree.getSelectedItem();
        if (selectedTreeItem != null) {
            if (selectedTreeItem instanceof final VisualisationAssetTreeItem assetTreeItem) {
                if (assetTreeItem.isLeaf()) {
                    // File so we want the parent folder
                    return assetTreeItem.getParentItem();
                } else {
                    // Folder selected so return it
                    return assetTreeItem;
                }
            }
        }

        return selectedTreeItem;
    }

    /**
     * Called when Add Button / Upload File is clicked.
     * Inserts a new item within the currently selected folder.
     */
    private void onUploadFile() {
        if (!readOnly) {
            final TreeItem folderItem = findFolderForSelectedItem();
            final String path = getItemPath(folderItem);
            uploadFileDialog.fireShowPopup(this, folderItem, path);
        }
    }

    /**
     * Callback from the Add dialog that adds a file that has been uploaded.
     * @param parentFolderItem The node that the file has been added to.
     * @param fileName The name of the file that was uploaded.
     * @param resourceKey The resource key of the file, so that the server can find it later.
     */
    @Override
    public void addUploadedFile(final TreeItem parentFolderItem,
                                final String fileName,
                                final ResourceKey resourceKey) {

        final VisualisationAssetTreeItem newFileNode = VisualisationAssetTreeItem.createNewFileItem(fileName);
        if (parentFolderItem == null) {
            tree.addItem(newFileNode);
        } else {
            parentFolderItem.addItem(newFileNode);
        }
        // TODO Sort node
        //sortNodeChildren(parentFolderNode);
        uploadedFileResourceKeys.put(newFileNode.getId(), resourceKey);

        setDirty();
    }

    /**
     * Call to mark the document as dirty and needing saving.
     * Also sorts the tree, as this is called when a tree node is edited.
     */
    private void setDirty() {
        sortTree();
        DirtyEvent.fire(this, true);
    }

    /**
     * Returns the path to the given item.
     * @param item The item to find the path to. Can be null if this is the root path.
     * @return The path as a String, with / separators.
     */
    private String getItemPath(final TreeItem item) {
        final List<String> pathList = new ArrayList<>();
        TreeItem currentItem = item;
        while (currentItem != null) {
            pathList.add(currentItem.getText());
            currentItem = currentItem.getParentItem();
        }
        Collections.reverse(pathList);

        return SLASH + String.join(SLASH, pathList);
    }

    /**
     * Called when the Delete button is clicked.
     * Deletes the currently selected item.
     */
    private void onDeleteButtonClick() {
        if (!readOnly) {
            final TreeItem item = tree.getSelectedItem();
            if (item != null) {
                if (item instanceof final VisualisationAssetTreeItem assetTreeItem) {
                    final String message;
                    if (assetTreeItem.isLeaf()) {
                        message = "Are you sure you want to delete the selected file?";
                    } else {
                        message = "Are you sure you want to delete the selected folder and all its descendants?";
                    }

                    ConfirmEvent.fire(VisualisationAssetsPresenter.this, message,
                            result -> {
                                if (result) {
                                    final TreeItem parentItem = assetTreeItem.getParentItem();
                                    if (parentItem == null) {
                                        tree.removeItem(assetTreeItem);
                                    } else {
                                        parentItem.removeItem(assetTreeItem);
                                    }

                                    recurseRemoveUploadedFiles(assetTreeItem);

                                    setDirty();
                                }
                            });
                } else {
                    Console.error("Unknown tree node type: " + item.getClass());
                }
            }

        }
    }

    /**
     * Recurses down the tree, removing all nodes from the uploadedFileResourceKeys map.
     * Called from onDeleteButtonClick().
     * @param assetTreeItem The root node. Remove uploaded files underneath this node.
     */
    private void recurseRemoveUploadedFiles(final VisualisationAssetTreeItem assetTreeItem) {
        uploadedFileResourceKeys.remove(assetTreeItem.getId());
        for (int i = 0; i < assetTreeItem.getChildCount(); ++i) {
            final TreeItem childTreeItem = assetTreeItem.getChild(i);
            if (childTreeItem instanceof final VisualisationAssetTreeItem childAssetTreeItem) {
                recurseRemoveUploadedFiles(childAssetTreeItem);
            } else {
                Console.error("Unknown tree node type: " + childTreeItem.getClass());
            }
        }
    }

    /**
     * Sets the state of the UI when things have changed.
     */
    private void updateState() {

        if (readOnly) {
            addButton.setEnabled(false);
            deleteButton.setEnabled(false);
        } else {
            final TreeItem item = tree.getSelectedItem();
            if (item == null) {
                // Assume the root item is selected
                addButton.setEnabled(true);
                deleteButton.setEnabled(false);
            } else {
                addButton.setEnabled(true);
                deleteButton.setEnabled(true);
            }
        }
    }

    // --------------------------------------------------------------------------------
    /**
     * Provides the images for the CellTree
     */
    private static class AssetTreeResources implements Tree.Resources {

        /** Height and width of the image in pixels */
        private static final int DIM = 10;
        private static final VisualisationAssetsImageResource CELL_CLOSED =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-right.png");
        private static final VisualisationAssetsImageResource CELL_OPEN =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-down.png");

        /** Tree shows leafs (items without children) with this icon. We need to pretend it is already open. */
        private static final VisualisationAssetsImageResource LEAF =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-down.png");

        @Override
        public ImageResource treeClosed() {
            return CELL_CLOSED;
        }

        @Override
        public ImageResource treeLeaf() {
            return LEAF;
        }

        @Override
        public ImageResource treeOpen() {
            return CELL_OPEN;
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
        void setTree(final Tree cellTree);
    }
}
