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
import stroom.floormap.client.presenter.FloorMapSettingsPresenter.FloorMapSettingsView;
import stroom.floormap.shared.FloorMapBackground;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapTransformationMatrix;
import stroom.security.shared.DocumentPermission;
import stroom.svg.client.SvgPresets;
import stroom.ui.config.client.UiConfigCache;
import stroom.widget.button.client.ButtonPanel;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.SvgButton;
import stroom.widget.util.client.HtmlBuilder;
import stroom.widget.util.client.HtmlBuilder.Attribute;
import stroom.widget.util.client.MultiSelectionModelImpl;
import stroom.widget.util.client.SafeHtmlUtil;

import com.google.gwt.cell.client.SafeHtmlCell;
import com.google.gwt.cell.client.TextCell;
import com.google.gwt.dom.client.Style.Unit;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class FloorMapSettingsPresenter
        extends DocPresenter<FloorMapSettingsView, FloorMapDoc>
        implements DirtyUiHandlers {

    private final UiConfigCache uiConfigCache;

    private List<FloorMapBackground> localBackgroundList;

    private final MyDataGrid<FloorMapBackground> grid;
    private final MultiSelectionModelImpl<FloorMapBackground> selectionModel;
    private final ButtonView editButton;
    private final ButtonView deleteButton;
    private final DocSelectionBoxPresenter temporalStoreRefPresenter;
    private FloorMapTransformationMatrix currentMatrix;

    @Inject
    public FloorMapSettingsPresenter(final EventBus eventBus,
                                     final FloorMapSettingsView view,
                                     final UiConfigCache uiConfigcache,
                                     final Provider<DocSelectionBoxPresenter> docSelectionBoxPresenterProvider) {
        super(eventBus, view);
        this.uiConfigCache = uiConfigcache;

        view.setUiHandlers(this);

        this.temporalStoreRefPresenter = docSelectionBoxPresenterProvider.get();
        this.temporalStoreRefPresenter.setIncludedTypes("SqlTemporalStore");
        this.temporalStoreRefPresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setTemporalStoreRefView(this.temporalStoreRefPresenter.getView());

        // Set up the DataGrid
        grid = new MyDataGrid<>(this);
        selectionModel = grid.addDefaultSelectionModel(false);
        
        final Column<FloorMapBackground, String> validFromColumn = new Column<>(new TextCell()) {
            @Override
            public String getValue(final FloorMapBackground row) {
                return new Date(row.getValidFromTime()).toString();
            }
        };
        grid.addColumn(validFromColumn, "Valid From");
        grid.setColumnWidth(validFromColumn, 250, Unit.PX);

        final Column<FloorMapBackground, SafeHtml> previewColumn = new Column<>(
                new SafeHtmlCell()) {
            @Override
            public SafeHtml getValue(final FloorMapBackground row) {
                final HtmlBuilder hb = new HtmlBuilder();
                hb.elem(SafeHtmlUtil.from("img"),
                        new Attribute("src", row.getImage()),
                        new Attribute("style", "width: 100px; height: 100px; object-fit: contain;"));
                return hb.toSafeHtml();

            }
        };
        grid.addColumn(previewColumn, "Preview");
        grid.setColumnWidth(previewColumn, 120, Unit.PX);

        view.setGridView(grid);

        // Set up the Toolbar
        final ButtonPanel toolbar = new ButtonPanel();
        editButton = SvgButton.create(SvgPresets.EDIT);
        deleteButton = SvgButton.create(SvgPresets.DELETE);
        toolbar.addButton(editButton);
        toolbar.addButton(deleteButton);
        view.setToolbar(toolbar);

        this.currentMatrix = FloorMapTransformationMatrix.identity();
    }

    @Override
    protected void onBind() {
        super.onBind();

        registerHandler(getView().addBackgroundImageChangeHandler(e -> onChange()));
        registerHandler(getView().addAddBackgroundHandler(e -> onAdd()));

        registerHandler(editButton.addClickHandler(e -> onEdit()));
        registerHandler(deleteButton.addClickHandler(e -> onDelete()));

        registerHandler(selectionModel.addSelectionHandler(e -> {
            final boolean hasSelection = selectionModel.getSelected() != null;
            editButton.setEnabled(hasSelection);
            deleteButton.setEnabled(hasSelection);
        }));

        registerHandler(getView().addRotationChangeHandler(e -> onRotation()));
        registerHandler(temporalStoreRefPresenter.addDataSelectionHandler(e -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc floorMapDoc, final boolean readOnly) {
        if (floorMapDoc.getBackgroundImages() != null) {
            this.localBackgroundList = new ArrayList<>(floorMapDoc.getBackgroundImages());
        } else {
            this.localBackgroundList = new ArrayList<>();
        }

        temporalStoreRefPresenter.setSelectedEntityReference(floorMapDoc.getTemporalStoreRef(), true);

        uiConfigCache.get(extendedUiConfig -> {
            if (extendedUiConfig != null) {
                getView().setBackgroundImage("");
                getView().setStartTime(System.currentTimeMillis());
                refreshGrid();
            }
        }, this);
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc doc) {
        return doc.copy()
                .backgroundImages(localBackgroundList)
                .temporalStoreRef(temporalStoreRefPresenter.getSelectedEntityReference())
                .build();
    }

    public DocRef getTemporalStoreRef() {
        return temporalStoreRefPresenter.getSelectedEntityReference();
    }

    @Override
    public void onDirty() {
        onChange();
    }

    private void onAdd() {
        final String currentImage = getView().getBackgroundImage();

        if (currentImage != null && !currentImage.isEmpty()) {
            final long time = getView().getStartTime();
            final FloorMapBackground newBg = new FloorMapBackground(
                    time,
                    currentImage,
                    currentMatrix
            );

            localBackgroundList.add(newBg);
            refreshGrid();
            setDirty(true);
            getView().setBackgroundImage("");

            this.currentMatrix = FloorMapTransformationMatrix.identity();
            getView().setRotation(0);
        }
    }

    private void onRotation() {
        final double degrees = getView().getRotation();
        this.currentMatrix = FloorMapTransformationMatrix.rotate(degrees);
        setDirty(true);
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

            // Load the existing matrix into the 'current' state.
            this.currentMatrix = selected.getMatrix();

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

        void setBackgroundImage(String backgroundImage);
        String getBackgroundImage();
        long getStartTime();
        double getRotation();

        void setToolbar(Widget widget);
        void setGridView(Widget widget);
        void setStartTime(long startTime);
        void setRotation(double degrees);
        void setTemporalStoreRefView(View view);

        HandlerRegistration addBackgroundImageChangeHandler(ValueChangeHandler<String> handler);
        HandlerRegistration addAddBackgroundHandler(ClickHandler handler);
        HandlerRegistration addRotationChangeHandler(final ValueChangeHandler<Long> handler);
    }
}
