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
import stroom.editor.client.presenter.EditorPresenter;
import stroom.entity.client.presenter.HasToolbar;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.util.client.Console;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.client.presenter.VisualisationAssetsAddItemDialogPresenter.DialogType;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;
import stroom.visualisation.client.presenter.assets.VisualisationAssetTreeItem;
import stroom.visualisation.client.presenter.assets.VisualisationAssetsImageResource;
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
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.Tree;
import com.google.gwt.user.client.ui.TreeItem;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Provider;

/**
 * Shows the Assets - images, css etc - associated with the Visualisation.
 */
public class VisualisationAssetsPresenter
        extends MyPresenterWidget<VisualisationAssetsView>
        implements HasDirtyHandlers, HasToolbar, VisualisationAssetsAddFileCallback {

    /** Illegal asset name characters - not allowed in any file or folder name */
    private final static String ILLEGAL_ASSET_NAME_CHARACTERS = "/:";

    /** Servlet path - start of the URL for the asset as retrieved via the Servlet */
    private final static String ASSET_SERVLET_PATH_PREFIX = "/assets/";

    /** REST interface */
    private final static VisualisationAssetResource VISUALISATION_ASSET_RESOURCE =
            GWT.create(VisualisationAssetResource.class);

    /** Rest factory to trigger storing file uploads */
    private final RestFactory restFactory;

    /** Tree representing file assets and folders */
    private final Tree tree = new Tree(new AssetTreeResources(), true);

    /** Button to revert any changes and go back to live version */
    private final InlineSvgButton revertButton = new InlineSvgButton();

    /** Button to add stuff to the tree */
    private final InlineSvgButton addButton = new InlineSvgButton();

    /** Button to delete stuff from the tree */
    private final InlineSvgButton deleteButton = new InlineSvgButton();

    /** Button to edit stuff from the tree */
    private final InlineSvgButton editButton = new InlineSvgButton();

    /** Button to view stuff */
    private final InlineSvgButton viewButton = new InlineSvgButton();

    /** Dialog that appears when user wants to upload a file */
    private final VisualisationAssetsUploadFileDialogPresenter uploadFileDialog;

    /** Dialog that appears when user wants to add a folder */
    private final VisualisationAssetsAddItemDialogPresenter addItemDialog;

    /** Dialog that appears when user wants to edit the tree item */
    private final VisualisationAssetsEditAssetDialogPresenter editAssetDialog;

    /** Editor widget */
    private final EditorPresenter editorPresenter;

    /** List of any resource keys for files that need to be saved */
    private final Map<String, ResourceKey> uploadedFileResourceKeys = new HashMap<>();

    /** Set of the node paths that are open when the document is saved */
    private final Set<String> treeItemPathToOpenState = new HashSet<>();

    /** Items in the context menu */
    private final List<Item> menuItems = new ArrayList<>();

    /** Current document - may be null */
    private VisualisationDoc document;

    /** True if the UI is readonly, false if read-write */
    private boolean readOnly = false;

    /** Whether this document is dirty - readonly changed by event handler */
    private boolean dirty = false;

    @Inject
    public VisualisationAssetsPresenter(final EventBus eventBus,
                                        final VisualisationAssetsView view,
                                        final RestFactory restFactory,
                                        final VisualisationAssetsUploadFileDialogPresenter uploadFileDialog,
                                        final VisualisationAssetsAddItemDialogPresenter addItemDialog,
                                        final VisualisationAssetsEditAssetDialogPresenter editAssetDialog,
                                        final Provider<EditorPresenter> editorPresenterProvider) {
        super(eventBus, view);

        this.restFactory = restFactory;
        this.uploadFileDialog = uploadFileDialog;
        this.addItemDialog = addItemDialog;
        this.editAssetDialog = editAssetDialog;
        this.editorPresenter = editorPresenterProvider.get();

        tree.setStylePrimaryName("visualisation-asset-tree");
        tree.addSelectionHandler(event -> {
            VisualisationAssetsPresenter.this.onSelectionChange();
        });
        this.getView().setTreeAndEditor(tree, editorPresenter);

        // Ensure that this class knows whether the data is dirty
        this.addDirtyHandler(this::onDirty);

        // TODO register handler for ValueChangeHandler -> dirty
        // TODO register handler for FormatHandler -> dirty

    }

    /**
     * Dirty mechanism across the tabs.
     */
    @Override
    public HandlerRegistration addDirtyHandler(final DirtyHandler handler) {
        return addHandlerToSource(DirtyEvent.getType(), handler);
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

        VisualisationAssetsPresenterUtils.recurseSortTree(tree, (VisualisationAssetTreeItem) parentFolderItem);
        uploadedFileResourceKeys.put(newFileNode.getId(), resourceKey);

        setDirty(true);
    }

    /**
     * Generates a label that doesn't clash with other files/folders in the same directory.
     * Adds an integer to the end, incrementing until an integer is found that doesn't
     * clash with anything else.
     * @param assetParentItem The tree item that holds the directory.
     * @param label The label that we're trying to put into the directory.
     * @param itemId The ID of the item with this label. Can be null if this is a new item with no ID yet.
     * @return A label that doesn't clash with anything else.
     */
    @Override
    public String getNonClashingLabel(final VisualisationAssetTreeItem assetParentItem,
                                      final String label,
                                      final String itemId) {
        String nonClashingLabel = label;

        if (assetParentItem != null) {
            int i = 1;
            while (assetParentItem.labelExists(nonClashingLabel, itemId)) {
                nonClashingLabel = VisualisationAssetsPresenterUtils.generateNonClashingLabel(label, i);
                i++;
            }
        } else {
            // Parent is the Tree itself
            int i = 1;
            while (VisualisationAssetsPresenterUtils.labelClashesInTreeRoot(tree, nonClashingLabel, itemId)) {
                nonClashingLabel = VisualisationAssetsPresenterUtils.generateNonClashingLabel(label, i);
                i++;
            }
        }

        return nonClashingLabel;
    }

    /**
     * Sets up the toolbar for the tab.
     */
    @Override
    public List<Widget> getToolbars() {
        revertButton.setSvg(SvgImage.REFRESH);
        revertButton.setTitle("Revert changes");
        revertButton.setVisible(true);
        revertButton.addClickHandler(event -> VisualisationAssetsPresenter.this.onRevertButtonClick());

        addButton.setSvg(SvgImage.ADD);
        addButton.setTitle("Add file");
        addButton.setVisible(true);

        deleteButton.setSvg(SvgImage.DELETE);
        deleteButton.setTitle("Delete");
        deleteButton.setVisible(true);
        deleteButton.addClickHandler(event -> VisualisationAssetsPresenter.this.onDeleteButtonClick());

        editButton.setSvg(SvgImage.EDIT);
        editButton.setTitle("Rename");
        editButton.setVisible(true);
        editButton.addClickHandler(event -> VisualisationAssetsPresenter.this.onEditFilename());

        viewButton.setSvg(SvgImage.EYE);
        viewButton.setTitle("View in browser");
        viewButton.setVisible(true);
        viewButton.addClickHandler(event -> VisualisationAssetsPresenter.this.onViewAsset());

        final ButtonPanel toolbar = new ButtonPanel();
        toolbar.addButton(revertButton);
        toolbar.addButton(addButton);
        toolbar.addButton(deleteButton);
        toolbar.addButton(editButton);
        toolbar.addButton(viewButton);

        // Ensure state is set correctly
        updateState();

        return List.of(toolbar);
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
                .text("Add New Folder")
                .command(() -> this.onCreateNewItem(false))
                .enabled(true)
                .build());
        menuItems.add(new IconMenuItem.Builder()
                .priority(1)
                .icon(SvgImage.FILE)
                .text("Add New File")
                .command(() -> this.onCreateNewItem(true))
                .enabled(true)
                .build());
        menuItems.add(new IconMenuItem.Builder()
                .priority(2)
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
     * Called when Add button / Add Folder is clicked.
     * Inserts a new folder with the currently selected folder.
     */
    private void onCreateNewItem(final boolean addFile) {
        if (!readOnly) {
            final VisualisationAssetTreeItem parentItem =
                    VisualisationAssetsPresenterUtils.findFolderForSelectedItem(
                            (VisualisationAssetTreeItem) tree.getSelectedItem());

            final String path = VisualisationAssetsPresenterUtils.getItemPath(parentItem);
            final ShowPopupEvent.Builder popupEventBuilder = new ShowPopupEvent.Builder(addItemDialog);
            final DialogType dialogType = addFile ? DialogType.FILE_DIALOG : DialogType.FOLDER_DIALOG;
            addItemDialog.setupPopup(popupEventBuilder, path, ILLEGAL_ASSET_NAME_CHARACTERS, dialogType);
            popupEventBuilder
                    .onHideRequest(event -> {
                                if (event.isOk()) {
                                    // Ok pressed
                                    if (addItemDialog.isValid()) {
                                        final String itemName = getNonClashingLabel(parentItem,
                                                addItemDialog.getView().getName(),
                                                null);
                                        final VisualisationAssetTreeItem newNode;
                                        if (addFile) {
                                            newNode = VisualisationAssetTreeItem.createNewFileItem(itemName);
                                        } else {
                                            newNode = VisualisationAssetTreeItem.createNewFolderItem(itemName);
                                        }
                                        if (parentItem == null) {
                                            tree.addItem(newNode);
                                        } else {
                                            parentItem.addItem(newNode);
                                        }
                                        VisualisationAssetsPresenterUtils.recurseSortTree(tree, parentItem);
                                        setDirty(true);
                                        event.hide();
                                    } else {
                                        AlertEvent.fireWarn(this, "Item name not set", event::reset);
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

                                    VisualisationAssetsPresenterUtils.recurseRemoveUploadedFiles(assetTreeItem,
                                            uploadedFileResourceKeys);

                                    setDirty(true);
                                }
                            });
                } else {
                    Console.error("Unknown tree node type: " + item.getClass());
                }
            }

        }
    }

    /**
     * Gets dirty events to update the class member variable.
     */
    private void onDirty(final DirtyEvent dirtyEvent) {
        Console.info("onDirty(" + dirtyEvent.isDirty() + ")");
        dirty = dirtyEvent.isDirty();
        updateState();
    }

    /**
     * Called when the EDIT button is pressed. Allows users to change the text of
     * a TreeItem.
     */
    private void onEditFilename() {
        if (!readOnly) {
            final VisualisationAssetTreeItem selectedItem = (VisualisationAssetTreeItem) tree.getSelectedItem();
            if (selectedItem != null) {
                final ShowPopupEvent.Builder popupEventBuilder = new ShowPopupEvent.Builder(editAssetDialog);
                editAssetDialog.setupPopup(popupEventBuilder, selectedItem, ILLEGAL_ASSET_NAME_CHARACTERS);
                popupEventBuilder
                        .onHideRequest(event -> {
                                    if (event.isOk()) {
                                        if (editAssetDialog.isValid()) {
                                            final VisualisationAssetTreeItem parentItem =
                                                    (VisualisationAssetTreeItem) selectedItem.getParentItem();
                                            final String text = getNonClashingLabel(parentItem,
                                                    editAssetDialog.getView().getText(),
                                                    editAssetDialog.getView().getId());
                                            selectedItem.setText(text);
                                            VisualisationAssetsPresenterUtils.recurseSortTree(tree, parentItem);
                                            setDirty(true);
                                            event.hide();
                                        } else {
                                            AlertEvent.fireWarn(this,
                                                    editAssetDialog.getValidationErrorMessage(),
                                                    event::reset);
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
        Console.info("onRead()");

        // Is this the first time this tab has been loaded?
        final boolean firstLoad = this.document == null || !this.document.getUuid().equals(document.getUuid());
        Console.info("onRead(): first load = " + firstLoad);

        this.document = document;

        // Set the readonly flag
        this.readOnly = readOnly;

        // Update UI state
        updateState();

        // Get the assets associated with the document (async)
        // but only if this is the first time this has been loaded
        // Otherwise we get into trouble with async operations clashing
        if (firstLoad) {
            this.fetchDraftAssets(document);
        }

        Console.info("onRead() complete");
    }

    /**
     * Called when the Revert button is clicked.
     * Gets rid of draft changes and goes back to the live version of the assets.
     */
    private void onRevertButtonClick() {
        VisualisationAssetsPresenterUtils.storeOpenClosedState(tree, treeItemPathToOpenState);

        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.revertDraftFromLive(document.getUuid()))
                .onSuccess(result -> {
                    if (result) {
                        // It worked - data reverted
                        fetchDraftAssets(document);
                    } else {
                        AlertEvent.fireError(this,
                                "Error reverting to live version",
                                null);
                    }
                })
                .onFailure(error -> {
                    AlertEvent.fireError(this,
                            "Error reverting to live version: " + error.getMessage(),
                            null);
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * Called when the selection changes.
     */
    private void onSelectionChange() {
        final VisualisationAssetTreeItem selectedItem = (VisualisationAssetTreeItem) tree.getSelectedItem();
        final String label = selectedItem.getText();
        if (label.endsWith(".html")) {
            editorPresenter.setText("<html><body></body></html>");
            editorPresenter.setMode(AceEditorMode.HTML);
            editorPresenter.setReadOnly(readOnly);
        } else {
            editorPresenter.setText("");
            editorPresenter.setReadOnly(true);
        }
        updateState();
    }

    /**
     * Called when Add Button / Upload File is clicked.
     * Shows the uploadFileDialog. The dialog calls back into this object via the
     * VisualisationAssetsAddFileCallback interface.
     */
    private void onUploadFile() {
        if (!readOnly) {
            final VisualisationAssetTreeItem folderItem =
                    VisualisationAssetsPresenterUtils.findFolderForSelectedItem(
                            (VisualisationAssetTreeItem) tree.getSelectedItem());
            final String path = VisualisationAssetsPresenterUtils.getItemPath(folderItem);
            uploadFileDialog.fireShowPopup(this, folderItem, path, ILLEGAL_ASSET_NAME_CHARACTERS);
        }
    }

    /**
     * Called when the user wants to view an asset.
     * Opens a new Browser window (tab) pointing to the asset via the Servlet.
     */
    public void onViewAsset() {
        final TreeItem selectedItem = tree.getSelectedItem();
        if (selectedItem != null)  {
            // Find the document ID
            if (document != null) {
                final String docId = document.getUuid();
                final String relativePath = ASSET_SERVLET_PATH_PREFIX
                                            + docId
                                            + VisualisationAssetsPresenterUtils.getItemPath(selectedItem);
                Window.open(relativePath, "_blank", null);
            }
        }
    }

    /**
     * Called by VisualisationPresenter so this tab gets a chance to write any changes
     * to the document before it is saved.
     * Requests the server to copy data from the draft area to the live area of the database.
     * @param document Document to store stuff in
     * @return The updated document.
     */
    public VisualisationDoc onWrite(final VisualisationDoc document) {
        Console.info("onWrite(); isDirty()==" + isDirty());

        // Run this section after the main document has been saved to the server
        Scheduler.get().scheduleFinally(() -> {

            VisualisationAssetsPresenterUtils.storeOpenClosedState(tree, treeItemPathToOpenState);

            // Transfer draft saves to live
            restFactory.create(VISUALISATION_ASSET_RESOURCE)
                    .method(r -> r.saveDraftToLive(document.getUuid()))
                    .onSuccess(result -> {
                        if (result) {
                            Console.info("onWrite(); onSuccess(); isDirty()==" + isDirty());
                            Scheduler.get().scheduleFinally(() -> {
                                // Reload doc via chain, once this chain is complete
                                fetchDraftAssets(document);
                            });
                        } else {
                            AlertEvent.fireError(this,
                                    "Error saving assets",
                                    null);
                        }
                    })
                    .onFailure(error -> {
                        AlertEvent.fireError(this,
                                "Error saving assets: " + error.getMessage(),
                                null);
                    })
                    .taskMonitorFactory(this)
                    .exec();
        });

        return document;
    }

    /**
     * Called from onRead() to pull down the assets from the server.
     * Uses REST to grab the assets and display them within the tree control.
     * Async.
     * @param document The document received from the server.
     */
    private void fetchDraftAssets(final VisualisationDoc document) {
        Console.info("fetchDraftAssets() start");
        final String ownerId = document.getUuid();
        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.fetchDraftAssets(ownerId))
                .onSuccess(assets -> {
                    Console.info("fetchDraftAssets() - onSuccess");
                    // Clear any existing content from the tree
                    tree.clear();

                    // Put the new content in
                    Console.info("fetchDraftAssets: got " + assets.getAssets().size() + " assets to add to the tree");
                    VisualisationAssetsPresenterUtils.addPathsToTree(tree, assets.getAssets());

                    // Set dirty state
                    Console.info("fetchDraftAssets: dirty=" + assets.isDirty());
                    setDirty(assets.isDirty());

                    // Restore the open/closed state of the tree
                    VisualisationAssetsPresenterUtils.restoreOpenClosedState(tree, treeItemPathToOpenState);

                    // Make sure UI state is correct
                    updateState();
                    Console.info("fetchDraftAssets() - onSuccess end");
                })
                .onFailure(error -> {
                    AlertEvent.fireError(this,
                            "Error downloading assets for this visualisation: " + error.getMessage(),
                            null);
                })
                .taskMonitorFactory(this)
                .exec();
        Console.info("fetchDraftAssets() end");
    }

    /**
     * Called when anything changes to register all the changes & uploaded documents
     * and move them from the ResourceStore into Draft storage in the database.
     */
    private void storeDraftAssets(final VisualisationDoc document) {

        final VisualisationAssets assets = new VisualisationAssets(document.getUuid(), uploadedFileResourceKeys);
        VisualisationAssetsPresenterUtils.treeToAssets(tree, assets);

        restFactory.create(VISUALISATION_ASSET_RESOURCE)
                .method(r -> r.updateDraftAssets(document.getUuid(), assets))
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
     * Sets the state of the UI when things have changed.
     */
    private void updateState() {

        if (readOnly) {
            addButton.setEnabled(false);
            deleteButton.setEnabled(false);
            editButton.setEnabled(false);
            viewButton.setEnabled(false);
        } else {
            final VisualisationAssetTreeItem item = (VisualisationAssetTreeItem) tree.getSelectedItem();
            if (item == null) {
                // Assume the root item is selected so enable 'add'
                addButton.setEnabled(true);
                deleteButton.setEnabled(false);
                editButton.setEnabled(false);
                viewButton.setEnabled(false);
            } else {
                addButton.setEnabled(true);
                deleteButton.setEnabled(true);
                editButton.setEnabled(true);
                // Only enable this button if tree isn't dirty (otherwise the item might not be on the server)
                // and the thing isn't a folder
                viewButton.setEnabled(!isDirty() && item.isLeaf());
            }
        }
    }

    /**
     * Call to mark the document as dirty and needing saving.
     * Saves everything to Draft storage so everything is on the server.
     */
    private void setDirty(final boolean dirty) {
        Console.info("Setting dirty to: " + dirty);
        if (dirty) {
            storeDraftAssets(document); // Async
        }
        // Try firing later to avoid issues
        Scheduler.get().scheduleFinally(() -> {
            DirtyEvent.fire(this, dirty);
        });
    }

    /**
     * Method to find out whether this document is dirty.
     */
    boolean isDirty() {
        return dirty;
    }

    // --------------------------------------------------------------------------------
    /**
     * Provides the images for the CellTree
     */
    private static class AssetTreeResources implements Tree.Resources {

        /** Height and width of the image in pixels */
        private static final int DIM = 16;
        private static final VisualisationAssetsImageResource CLOSED =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-right.png");
        private static final VisualisationAssetsImageResource OPEN =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/arrow-down.png");
        private static final VisualisationAssetsImageResource TRANSPARENT =
                new VisualisationAssetsImageResource(DIM, DIM, "/ui/background-images/transparent-16x16.png");

        @Override
        public ImageResource treeClosed() {
            return CLOSED;
        }

        @Override
        public ImageResource treeLeaf() {
            return TRANSPARENT;
        }

        @Override
        public ImageResource treeOpen() {
            return OPEN;
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
        void setTreeAndEditor(final Tree cellTree, final EditorPresenter editor);
    }
}
