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
import stroom.visualisation.client.presenter.assets.VisualisationAssetTreeNode;
import stroom.visualisation.client.presenter.assets.VisualisationAssetTreeModel;
import stroom.visualisation.client.presenter.assets.VisualisationAssetsImageResource;
import stroom.visualisation.client.presenter.tree.UpdatableTreeModel;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shows the Assets - images, css etc - associated with the Visualisation.
 */
public class VisualisationAssetsPresenter
        extends MyPresenterWidget<VisualisationAssetsView>
        implements HasDirtyHandlers, HasToolbar, VisualisationAssetsAddFileCallback {

    /** Rest factory to trigger storing file uploads */
    private final RestFactory restFactory;

    /** Model behind the tree */
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

    /** List of any resource keys for files that need to be saved */
    private final Map<String, ResourceKey> uploadedFileResourceKeys = new HashMap<>();

    /** Items in the context menu */
    private final List<Item> menuItems = new ArrayList<>();

    /** Hidden root item in the tree. Not displayed. */
    final static VisualisationAssetTreeNode ROOT_NODE = VisualisationAssetTreeNode.createFolderNode("root");

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

        treeModel = new VisualisationAssetTreeModel(selectionModel,
                (node, label) ->
                        VisualisationAssetsPresenter.this.getNonClashingLabel(node.getParent(), label),
                this::setDirty,
                this::isReadOnly);

        selectionModel.addSelectionChangeHandler(event ->
                VisualisationAssetsPresenter.this.onSelectionChange());

        // Hack here - need to add a dummy node in otherwise the tree is broken :-(
        // This node will be removed later in onRead()
        final VisualisationAssetTreeNode dummyNode = VisualisationAssetTreeNode.createDummyNode();
        treeModel.add(ROOT_NODE, dummyNode);

        // Main tree we're displaying
        final CellTree cellTree = new CellTree(treeModel, ROOT_NODE, new AssetTreeResources());
        cellTree.setAnimation(CellTree.SlideAnimation.create());
        cellTree.setAnimationEnabled(true);
        this.getView().setCellTree(cellTree);
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
     * @param assets The list of assets from the VisualisationDoc. Might be null.
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
                UpdatableTreeNode node = ROOT_NODE;
                for (int iPath = 0; iPath < pathItems.length; ++iPath) {
                    final String pathItem = pathItems[iPath];
                    final boolean isLast = iPath == pathItems.length - 1;

                    final Optional<UpdatableTreeNode> existingChildNode =
                            node.getDataProvider().getList().stream()
                                    .filter(n -> n.getLabel().equals(pathItem)).findFirst();
                    final UpdatableTreeNode childNode;
                    if (existingChildNode.isEmpty()) {
                        if (isLast) {
                            // Last item so set whether it is a folder or not
                            // The last item takes the ID of the asset too.
                            childNode = VisualisationAssetTreeNode.createNodeFromAsset(asset, pathItem);
                        } else {
                            // Not last item so must be a folder
                            // Its ID isn't important at this stage so it gets a new ID
                            childNode = VisualisationAssetTreeNode.createFolderNode(pathItem);
                        }
                        treeModel.add(node, childNode);
                    } else {
                        childNode = existingChildNode.get();
                    }
                    node = childNode;
                }
            }
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
        Console.info("Fetching assets for " + ownerId);
        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.fetchAssets(ownerId))
                .onSuccess(assets -> {
                    // Clear any existing content from the tree
                    treeModel.clear(ROOT_NODE);

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
        treeToAssets(document, assets);

        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.updateAssets(document.getUuid(), assets))
                .onSuccess(result -> {
                    if (result) {
                        // Great it worked - clear the list of uploaded files
                        Console.info("Visualisation Assets: uploaded files stored");
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
     * Convert the tree into a list of assets.
     * @param document The document which owns the assets
     * @param assets Where to put the assets
     */
    private void treeToAssets(final VisualisationDoc document, final VisualisationAssets assets) {
        recurseTreeToAssets(ROOT_NODE, document, assets);
    }

    /**
     * Recursive function called from treeToAssets().
     */
    private void recurseTreeToAssets(final UpdatableTreeNode currentNode,
                                     final VisualisationDoc document,
                                     final VisualisationAssets assets) {
        if (currentNode instanceof final VisualisationAssetTreeNode assetTreeNode) {

            // Don't store the ROOT_NODE, but otherwise store anything without children
            // So we store folders if they don't have any children, and we store files.
            if (assetTreeNode.getParent() != null && !assetTreeNode.hasChildren()) {
                // No more nodes so store path
                final String path = getItemPath(assetTreeNode);

                final VisualisationAsset asset = new VisualisationAsset(
                        document.asDocRef(),
                        assetTreeNode.getId(),
                        path,
                        !assetTreeNode.isLeaf());
                assets.addAsset(asset);
            } else {
                // More nodes so recurse
                for (final UpdatableTreeNode child : assetTreeNode.getDataProvider().getList()) {
                    recurseTreeToAssets(child, document, assets);
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
            final UpdatableTreeNode folderNode = findFolderForSelectedNode();
            final String path = getItemPath(folderNode);
            final ShowPopupEvent.Builder popupEventBuilder = new ShowPopupEvent.Builder(addFolderDialog);
            addFolderDialog.setupPopup(popupEventBuilder, path);
            popupEventBuilder
                    .onHideRequest(event -> {
                                if (event.isOk()) {
                                    // Ok pressed
                                    if (addFolderDialog.isValid()) {
                                        final String folderName = getNonClashingLabel(
                                                folderNode,
                                                addFolderDialog.getView().getFolderName());
                                        final UpdatableTreeNode newFolderNode =
                                                VisualisationAssetTreeNode.createFolderNode(folderName);
                                        treeModel.add(folderNode, newFolderNode);
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
     * Generates a label that doesn't clash with other files/folders in the same directory.
     * Adds an integer to the end, incrementing until an integer is found that doesn't
     * clash with anything else.
     * @param parentNode The node that holds the directory.
     * @param label The label that we're trying to put into the directory.
     * @return A label that doesn't clash with anything else.
     */
    @Override
    public String getNonClashingLabel(final UpdatableTreeNode parentNode, final String label) {
        int i = 1;
        String nonClashingLabel = label;
        while (parentNode.labelExists(nonClashingLabel)) {
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

        return nonClashingLabel;
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
            selectedFolder = ROOT_NODE;
        }

        return selectedFolder;
    }

    /**
     * Called when Add Button / Upload File is clicked.
     * Inserts a new item within the currently selected folder.
     */
    private void onUploadFile() {
        if (!readOnly) {
            final UpdatableTreeNode folderNode = findFolderForSelectedNode();
            final String path = getItemPath(folderNode);
            uploadFileDialog.fireShowPopup(this, folderNode, path);
        }
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

        final VisualisationAssetTreeNode newFileNode = VisualisationAssetTreeNode.createNewFileNode(fileName);
        treeModel.add(parentFolderNode, newFileNode);
        uploadedFileResourceKeys.put(newFileNode.getId(), resourceKey);

        setDirty();
    }

    /**
     * Call to mark the document as dirty and needing saving.
     */
    private void setDirty() {
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
        while (node != null && !node.equals(ROOT_NODE)) {
            pathList.add(node.getLabel());
            node = node.getParent();
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
            final UpdatableTreeNode node = selectionModel.getSelectedObject();
            if (node != null) {
                final String message;
                if (node.isLeaf()) {
                    message = "Are you sure you want to delete the selected file?";
                } else {
                    message = "Are you sure you want to delete the selected folder and all its descendants?";
                }

                ConfirmEvent.fire(VisualisationAssetsPresenter.this, message,
                        result -> {
                            if (result) {
                                treeModel.remove(node);
                                if (node instanceof final VisualisationAssetTreeNode assetTreeNode) {
                                    uploadedFileResourceKeys.remove(assetTreeNode.getId());
                                } else {
                                    Console.error("Unknown tree node type: " + node.getClass());
                                }

                                setDirty();
                            }
                        });
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
    }

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
