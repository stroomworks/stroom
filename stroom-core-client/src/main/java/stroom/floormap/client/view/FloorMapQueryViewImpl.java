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
    }

    @Override
    public void setQueryEditView(final View view) {
        queryEditContainer.setWidget(view.asWidget());
    }

    @Override
    public void setColumnMappingsVisible(final boolean visible) {
        columnMappingsContainer.setVisible(visible);
    }

    @Override
    public void setAvailableColumns(final List<String> columnNames) {
        entityIdColumn.clear();
        locationIdColumn.clear();

        entityIdColumn.addItem("");
        locationIdColumn.addItem("");

        if (columnNames != null) {
            for (final String col : columnNames) {
                entityIdColumn.addItem(col);
                locationIdColumn.addItem(col);
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
