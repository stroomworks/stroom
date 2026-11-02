/*
 * Copyright 2026 Crown Copyright
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


package stroom.pipelinefactory.client.component;

import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The values a Pipeline Factory writes onto the elements of each pipeline it makes (A113), a row
/// each (element, property and value), with Add, Edit and Remove; a double click edits one. When read
/// only, the buttons are disabled and a double click does nothing.
public class FactoryPropertyList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton addButton;
    @UiField
    ToolbarButton editButton;
    @UiField
    ToolbarButton removeButton;
    @UiField
    DataGrid<PipelineFactoryProperty> dataGrid;

    private final MultiSelectionModelImpl<PipelineFactoryProperty> selectionModel;
    private Runnable onAdd = () -> {
    };
    private Runnable onEdit = () -> {
    };
    private Runnable onRemove = () -> {
    };

    /// Makes an empty list.
    public FactoryPropertyList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();

        addButton.setOnClick(() -> {
            if (!isReadOnly()) {
                onAdd.run();
            }
        });
        editButton.setOnClick(() -> {
            if (!isReadOnly() && selectionModel.getSelected() != null) {
                onEdit.run();
            }
        });
        removeButton.setOnClick(() -> {
            if (!isReadOnly() && selectionModel.getSelected() != null) {
                onRemove.run();
            }
        });
        selectionModel.addSelectionHandler(event -> {
            if (event.getSelectionType().isDoubleSelect()
                && !isReadOnly()
                && selectionModel.getSelected() != null) {
                onEdit.run();
            }
            enableButtons();
        });
        enableButtons();
    }

    /// @param properties The property values, in the order the factory writes them.
    public void setData(final List<PipelineFactoryProperty> properties) {
        dataGrid.setRowData(0, properties);
        dataGrid.setRowCount(properties.size());
        enableButtons();
    }

    /// @return The selection.
    public MultiSelectionModel<PipelineFactoryProperty> getSelectionModel() {
        return selectionModel;
    }

    /// Selects a property value, or none, and brings the buttons up to date.
    ///
    /// @param property The property value, or null to select none.
    public void setSelected(final PipelineFactoryProperty property) {
        if (property == null) {
            selectionModel.clear();
        } else {
            selectionModel.setSelected(property);
        }
        enableButtons();
    }

    /// @param onAdd Run when Add is pressed (not when read only).
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// @param onEdit Run when Edit is pressed, or a row double clicked, with a row selected (not when
    ///               read only).
    public void setOnEdit(final Runnable onEdit) {
        this.onEdit = onEdit;
    }

    /// @param onRemove Run when Remove is pressed with a row selected (not when read only).
    public void setOnRemove(final Runnable onRemove) {
        this.onRemove = onRemove;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = !isReadOnly();
        final boolean selected = selectionModel.getSelected() != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected);
        removeButton.setEnabled(editable && selected);
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryProperty row) ->
                        nonNull(row.getElementId())).build(),
                DataGridUtil.headingBuilder("Element")
                        .withToolTip("The id of the element in the template's chain.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryProperty row) ->
                        nonNull(row.getName())).build(),
                DataGridUtil.headingBuilder("Property")
                        .withToolTip("The property on that element, which must take text.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((PipelineFactoryProperty row) ->
                        nonNull(row.getValue())).build(),
                DataGridUtil.headingBuilder("Value")
                        .withToolTip("The value, with ${feed}, ${type} or ${pipeline} replaced by the "
                                     + "combination's.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }

    private static String nonNull(final String text) {
        return text == null
                ? ""
                : text;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FactoryPropertyList> {

    }
}
