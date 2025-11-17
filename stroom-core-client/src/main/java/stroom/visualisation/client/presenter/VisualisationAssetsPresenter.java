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
import stroom.util.client.Console;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;

import com.google.gwt.cell.client.AbstractCell;
import com.google.gwt.cell.client.Cell;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.user.cellview.client.CellTree;
import com.google.gwt.user.cellview.client.CellTree.Style;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.ListDataProvider;
import com.google.gwt.view.client.SelectionChangeEvent;
import com.google.gwt.view.client.SelectionChangeEvent.Handler;
import com.google.gwt.view.client.SelectionModel;
import com.google.gwt.view.client.SingleSelectionModel;
import com.google.gwt.view.client.TreeViewModel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the Assets - images, css etc - associated with the Visualisation.
 */
public class VisualisationAssetsPresenter
        extends MyPresenterWidget<VisualisationAssetsView>
        implements HasDirtyHandlers, HasToolbar {

    /** Main tree we're displaying */
    private final CellTree cellTree;

    /** Selection model */
    private final SingleSelectionModel<VisualisationAssetItem> selectionModel = new SingleSelectionModel<>();

    /** True if the UI is readonly, false if read-write */
    private boolean readOnly = false;

    /**
     * Injected constructor.
     */
    @Inject
    public VisualisationAssetsPresenter(final EventBus eventBus,
                                        final VisualisationAssetsView view) {
        super(eventBus, view);

        final TreeViewModel model = new AssetTreeModel(selectionModel);
        final VisualisationAssetItem rootItem = new VisualisationAssetItem("root", false);

        selectionModel.addSelectionChangeHandler(new Handler() {
            @Override
            public void onSelectionChange(final SelectionChangeEvent event) {
                VisualisationAssetsPresenter.this.onSelectionChange(selectionModel.getSelectedObject());
            }
        });

        // TODO Dummy data needs replacing with live data
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
        subdir1.addSubItem(file1);
        subdir1.addSubItem(file2);
        subdir1.addSubItem(file3);
        subdir1.addSubItem(file4);
        subdir1.addSubItem(file5);
        subdir1.addSubItem(file6);
        subdir1.addSubItem(file7);
        subdir1.addSubItem(file8);
        subdir1.addSubItem(file9);
        subdir1.addSubItem(file10);
        subdir1.addSubItem(file11);
        subdir1.addSubItem(file12);
        subdir1.addSubItem(file13);
        dir1.addSubItem(subdir1);
        rootItem.addSubItem(dir1);

        cellTree = new CellTree(model, rootItem, new AssetTreeResources());
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
        return List.of();
    }

    /**
     * Called during UI setup to set whether this UI is in readonly state.
     */
    public void setReadOnly(final boolean readOnly) {
        this.readOnly = readOnly;

        // TODO updateState()
    }

    void onSelectionChange(final VisualisationAssetItem item) {
        // TODO
    }

    // --------------------------------------------------------------------------------
    /**
     * Models the assets within the tree.
     */
    private static class AssetTreeModel implements TreeViewModel {

        private final SelectionModel<VisualisationAssetItem> selectionModel;

        /** Map of extension to image */
        private static final Map<String, SvgImage> FILE_ICONS = new HashMap<>();

        /*
         * Initialise the map of extension to file icon.
         */
        static {
            FILE_ICONS.put("png",  SvgImage.FILE_IMAGE);
            FILE_ICONS.put("jpg",  SvgImage.FILE_IMAGE);
            FILE_ICONS.put("jpeg", SvgImage.FILE_IMAGE);
            FILE_ICONS.put("gif",  SvgImage.FILE_IMAGE);
            FILE_ICONS.put("webp", SvgImage.FILE_IMAGE);
            FILE_ICONS.put("svg",  SvgImage.FILE_IMAGE);
            FILE_ICONS.put("css",  SvgImage.FILE_RAW);
            FILE_ICONS.put("htm",  SvgImage.FILE_FORMATTED);
            FILE_ICONS.put("html", SvgImage.FILE_FORMATTED);
        }

        /**
         * Constructor.
         */
        public AssetTreeModel(final SelectionModel<VisualisationAssetItem> selectionModel) {
            this.selectionModel = selectionModel;
        }

        @Override
        public <T> NodeInfo<?> getNodeInfo(final T parent) {
            Console.info("getNodeInfo: " + parent + ": " + parent.getClass());
            final ListDataProvider<VisualisationAssetItem> dataProvider = new ListDataProvider<>();
            final Cell<VisualisationAssetItem> cell;

            if (parent instanceof final VisualisationAssetItem parentItem) {
                Console.info("-> isLeaf: " + parentItem.isLeaf());
                if (!parentItem.isLeaf()) {
                    // Must be a folder, so find its children and display a folder icon
                    final List<VisualisationAssetItem> subItems = parentItem.getSubItems();
                    for (final VisualisationAssetItem assetItem : subItems) {
                        dataProvider.getList().add(assetItem);
                    }
                }
                final Cell<VisualisationAssetItem> textCell = new AbstractCell<>() {
                    @Override
                    public void render(final Context context,
                                       final VisualisationAssetItem value,
                                       final SafeHtmlBuilder sb) {
                        if (value != null) {
                            sb.appendEscaped(value.getName());
                        }
                    }
                };
                cell = new VisualisationAssetsIconCellDecorator(
                        SvgImage.FOLDER,
                        FILE_ICONS,
                        SvgImage.FILE,
                        textCell) {
                };
            } else {
                // Shouldn't happen but keeps final happy
                cell = new AbstractCell<>() {
                    @Override
                    public void render(final Context context,
                                       final VisualisationAssetItem value,
                                       final SafeHtmlBuilder sb) {
                        // Do nothing
                    }
                };
            }

            return new DefaultNodeInfo<>(dataProvider, cell, selectionModel, null);
        }

        @Override
        public boolean isLeaf(final Object objectItem) {
            Console.info("isLeaf: " + objectItem);
            if (objectItem instanceof final VisualisationAssetItem assetItem) {
                Console.info("-> " + assetItem.isLeaf());
                return assetItem.isLeaf();
            } else {
                // Shouldn't happen
                return false;
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
