/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.floormap.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.data.grid.client.MyDataGrid;
import stroom.docref.DocRef;
import stroom.document.client.event.DirtyUiHandlers;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.feed.shared.FeedDoc;
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapBackground;
import stroom.floormap.shared.FloorMapDoc;
import stroom.security.shared.DocumentPermission;
import stroom.svg.client.SvgPresets;
import stroom.ui.config.client.UiConfigCache;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.SvgButton;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.cell.client.TextCell;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc>
        implements DirtyUiHandlers {

    final DocSelectionBoxPresenter sourceFeedPresenter;
    private final UiConfigCache uiConfigCache;

    private List<FloorMapBackground> localBackgroundList;

    private final MyDataGrid<FloorMapBackground> grid;
    private final MultiSelectionModelImpl<FloorMapBackground> selectionModel;
    private final ButtonView editButton;
    private final ButtonView deleteButton;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final DocSelectionBoxPresenter sourceFeedPresenter,
                                     final UiConfigCache uiConfigcache) {
        super(eventBus, view);
        this.sourceFeedPresenter = sourceFeedPresenter;
        this.uiConfigCache = uiConfigcache;

        view.setUiHandlers(this);

        sourceFeedPresenter.setIncludedTypes(FeedDoc.TYPE);
        sourceFeedPresenter.setRequiredPermissions(DocumentPermission.VIEW);
        view.setSourceFeed(sourceFeedPresenter.getView());

        // Setup the DataGrid
        grid = new MyDataGrid<>(this);
        selectionModel = grid.addDefaultSelectionModel(false);
        
        grid.addColumn(new Column<>(new TextCell()) {
            @Override
            public String getValue(final FloorMapBackground row) {
                return new Date(row.getValidFromTime()).toString();
            }
        }, "Valid From");

        view.setGridView(grid);

        // Setup the Toolbar
        final ButtonPanel toolbar = new ButtonPanel();
        editButton = SvgButton.create(SvgPresets.EDIT);
        deleteButton = SvgButton.create(SvgPresets.DELETE);
        toolbar.addButton(editButton);
        toolbar.addButton(deleteButton);
        view.setToolbar(toolbar);
    }

    @Override
    protected void onBind() {
        super.onBind();

        registerHandler(sourceFeedPresenter.addDataSelectionHandler(e -> onChange()));
        registerHandler(getView().addBackgroundImageChangeHandler(e -> onChange()));
        registerHandler(getView().addAddBackgroundHandler(e -> onAdd()));

        registerHandler(editButton.addClickHandler(e -> onEdit()));
        registerHandler(deleteButton.addClickHandler(e -> onDelete()));

        registerHandler(selectionModel.addSelectionHandler(e -> {
            final boolean hasSelection = selectionModel.getSelected() != null;
            editButton.setEnabled(hasSelection);
            deleteButton.setEnabled(hasSelection);
        }));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {
        if (floorMapDoc.getBackgroundImages() != null) {
            this.localBackgroundList = new ArrayList<>(floorMapDoc.getBackgroundImages());
        } else {
            this.localBackgroundList = new ArrayList<>();
        }

        uiConfigCache.get(extendedUiConfig -> {
            if (extendedUiConfig != null) {
                final DocRef selectedDocRef = floorMapDoc.getFeed();
                if (selectedDocRef != null) {
                    sourceFeedPresenter.setSelectedEntityReference(selectedDocRef, true);
                }

                getView().setBackgroundImage("");
                getView().setStartTime(System.currentTimeMillis());
                refreshGrid();
            }
        }, this);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        return doc.copy()
                .feed(sourceFeedPresenter.getSelectedEntityReference())
                .backgroundImages(localBackgroundList)
                .build();
    }

    @Override
    public void onDirty() {
        onChange();
    }

    private void onAdd() {
        final String currentImage = getView().getBackgroundImage();

        if (currentImage != null && !currentImage.isEmpty()) {
            final long time = getView().getStartTime();
            final FloorMapBackground newBg = new FloorMapBackground(time, currentImage);

            localBackgroundList.add(newBg);
            refreshGrid();
            setDirty(true);
            getView().setBackgroundImage("");
        }
    }

    private void onDelete() {
        final FloorMapBackground selected = selectionModel.getSelected();
        if (selected != null) {
            ConfirmEvent.fire(this, "Are you sure you want to delete this background image?", result -> {
                if (result) {
                    localBackgroundList.remove(selected);
                    refreshGrid();
                    setDirty(true);
                }
            });
        }
    }

    private void onEdit() {
        final FloorMapBackground selected = selectionModel.getSelected();
        if (selected != null) {
            getView().setStartTime(selected.getValidFromTime());
            getView().setBackgroundImage(selected.getImage());
            localBackgroundList.remove(selected);
            refreshGrid();
            setDirty(true);
        }
    }

    private void refreshGrid() {
        grid.setRowData(0, localBackgroundList);
        grid.setRowCount(localBackgroundList.size());
    }

    public interface FloorMapSettingsView extends View, HasUiHandlers<DirtyUiHandlers>, ReadOnlyChangeHandler {

        void setSourceFeed(View view);
        void setBackgroundImage(String backgroundImage);
        String getBackgroundImage();
        long getStartTime();
        HandlerRegistration addBackgroundImageChangeHandler(ValueChangeHandler<String> handler);
        HandlerRegistration addAddBackgroundHandler(ClickHandler handler);
        void setToolbar(Widget widget);
        void setGridView(Widget widget);
        void setStartTime(long startTime);
    }
}
