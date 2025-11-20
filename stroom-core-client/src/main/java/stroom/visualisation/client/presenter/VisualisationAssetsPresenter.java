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
import stroom.svg.shared.SvgImage;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;
import stroom.visualisation.client.presenter.assets.VisualisationAssetItem;
import stroom.visualisation.client.presenter.assets.VisualisationAssetTreeModel;
import stroom.visualisation.client.presenter.assets.VisualisationAssetsImageResource;
import stroom.visualisation.client.presenter.tree.UpdatableTreeModel;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.InlineSvgButton;
import stroom.widget.popup.client.event.ShowPopupEvent;

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
        implements HasDirtyHandlers, HasToolbar {

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

    /** Dialog that appears when the Add button is clicked */
    private final VisualisationAssetsAddDialogPresenter addDialog;

    /** Hidden root item in the tree. Not displayed. */
    final static VisualisationAssetItem ROOT_ITEM = new VisualisationAssetItem("root", false);

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetsPresenter(final EventBus eventBus,
                                        final VisualisationAssetsView view,
                                        final VisualisationAssetsAddDialogPresenter addDialog) {
        super(eventBus, view);
        this.addDialog = addDialog;

        treeModel = new VisualisationAssetTreeModel(selectionModel);

        selectionModel.addSelectionChangeHandler(event ->
                VisualisationAssetsPresenter.this.onSelectionChange(selectionModel.getSelectedObject()));

        // TODO Dummy data needs replacing with live data
        //final VisualisationAssetItem root = new VisualisationAssetItem("/", false);
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

        cellTree = new CellTree(treeModel, ROOT_ITEM, new AssetTreeResources());
        cellTree.setAnimation(CellTree.SlideAnimation.create());
        cellTree.setAnimationEnabled(true);
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
        addButton.setSvg(SvgImage.ADD);
        addButton.setTitle("Add file");
        addButton.setVisible(true);
        addButton.addClickHandler(event -> VisualisationAssetsPresenter.this.onAddButtonClick());

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

    private void onSelectionChange(final UpdatableTreeNode item) {
        updateState();
    }

    /**
     * Called when the Add button is clicked.
     * Inserts a new item within the currently selected folder.
     */
    private void onAddButtonClick() {

        UpdatableTreeNode selectedItem = selectionModel.getSelectedObject();
        // If trying to add to a file then find its parent folder
        if (selectedItem != null && selectedItem.isLeaf()) {
            selectedItem = selectedItem.getParent();
        }

        final String path = getItemPath(selectedItem);

        final ShowPopupEvent.Builder builder = ShowPopupEvent.builder(addDialog);
        addDialog.setupDialog(builder,
                path);
        builder.onHideRequest(event -> {
                    if (event.isOk()) {
                        // TODO insert new node
                        event.hide();
                    } else {
                        // Cancel pressed
                        event.hide();
                    }
                })
                .fire();

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
