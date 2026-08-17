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

package stroom.floormap.client.view;

import stroom.floormap.client.FloorMapAria;
import stroom.floormap.client.presenter.FloorMapQueryPresenter.FloorMapQueryView;
import stroom.item.client.SelectionBox;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;
import com.gwtplatform.mvp.client.View;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.List;
import javax.inject.Inject;

/**
 * View implementation for the floor map query configuration panel.
 *
 * <p>Embeds the standard query editor and exposes column-mapping dropdowns that
 * let the user select which result columns should be used as the entity ID and
 * location ID when plotting facts on the floor map.</p>
 */
public class FloorMapQueryViewImpl extends ViewImpl implements FloorMapQueryView {

    private final Widget widget;

    @UiField
    SimplePanel queryEditContainer;
    @UiField
    FlowPanel columnMappingsContainer;
    @UiField
    SelectionBox<String> entityIdColumn;
    @UiField
    SelectionBox<String> locationIdColumn;

    @Inject
    public FloorMapQueryViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);

        // The FormGroups around these two carry identity="entityColumn" /
        // "locationColumn" and a visible label, but SelectionBox is a composite whose
        // root is a wrapper div — so setIdentity() puts the id on the wrapper and the
        // <label for> resolves to a non-labelable element, naming nothing. Both boxes
        // announced as unnamed. Name the inner input directly instead; the label text
        // is duplicated here deliberately, since the FormGroup's copy cannot reach it.
        // See §10.1 of docs/floormap-accessibility.md.
        FloorMapAria.labelInnerControl(entityIdColumn, "Entity ID Column");
        FloorMapAria.labelInnerControl(locationIdColumn, "Location ID Column");
    }

    @Override
    public void setQueryEditView(final View view) {
        queryEditContainer.setWidget(view.asWidget());
    }

    @Override
    public void setColumnMappingsVisible(final boolean visible) {
        columnMappingsContainer.setVisible(visible);
    }

    /**
     * Replaces the available items in both column-mapping dropdowns with the given
     * column names, preceded by an empty "none selected" entry.
     */
    @Override
    public void setAvailableColumns(final List<String> columnNames) {
        populateSelectionBox(entityIdColumn, columnNames);
        populateSelectionBox(locationIdColumn, columnNames);
    }

    private static void populateSelectionBox(final SelectionBox<String> box,
                                             final List<String> items) {
        box.clear();
        box.addItem("");
        if (items != null) {
            for (final String item : items) {
                box.addItem(item);
            }
        }
    }

    @Override
    public void setEntityIdColumn(final String entityId) {
        entityIdColumn.setValue(entityId);
    }

    @Override
    public void setLocationIdColumn(final String locationId) {
        locationIdColumn.setValue(locationId);
    }

    @Override
    public String getEntityIdColumn() {
        return entityIdColumn.getValue();
    }

    @Override
    public String getLocationIdColumn() {
        return locationIdColumn.getValue();
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    public interface Binder extends UiBinder<Widget, FloorMapQueryViewImpl> {}
}
