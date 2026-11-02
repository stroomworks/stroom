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


package stroom.shapeshifter.client.component;

import stroom.shapeshifter.shared.XPathAssertion;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The assertions of a business rules scorer as a list: a row each, its name and XPath, with Add, Edit
/// and Remove. A double click edits an assertion. When read only, the buttons are disabled and a
/// double click does nothing. [XPathAssertionListController] does the work.
public class XPathAssertionList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final int XPATH_COL = 500;

    @UiField
    ToolbarButton addButton;
    @UiField
    ToolbarButton editButton;
    @UiField
    ToolbarButton removeButton;
    @UiField
    DataGrid<XPathAssertion> dataGrid;

    private final MultiSelectionModelImpl<XPathAssertion> selectionModel;
    private Runnable onAdd = () -> {
    };
    private Runnable onEdit = () -> {
    };
    private Runnable onRemove = () -> {
    };

    /// Makes an empty list.
    public XPathAssertionList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();

        addButton.setOnClick(() -> {
            if (!isReadOnly()) {
                onAdd.run();
            }
        });
        editButton.setOnClick(() -> {
            if (!isReadOnly() && getSelected() != null) {
                onEdit.run();
            }
        });
        removeButton.setOnClick(() -> {
            if (!isReadOnly() && getSelected() != null) {
                onRemove.run();
            }
        });
        selectionModel.addSelectionHandler(event -> {
            if (getSelected() != null && event.getSelectionType().isDoubleSelect() && !isReadOnly()) {
                onEdit.run();
            }
            enableButtons();
        });
        enableButtons();
    }

    /// @param assertions The assertions, in their order.
    public void setData(final List<XPathAssertion> assertions) {
        dataGrid.setRowData(0, assertions);
        dataGrid.setRowCount(assertions.size());
        enableButtons();
    }

    /// @return The assertion selected, or null if none is.
    public XPathAssertion getSelected() {
        return selectionModel.getSelected();
    }

    /// Selects nothing.
    public void clearSelection() {
        selectionModel.clear();
        enableButtons();
    }

    /// @param onAdd Run when Add is pressed (not when read only).
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// @param onEdit Run when Edit is pressed, or an assertion is double clicked (not when read only).
    public void setOnEdit(final Runnable onEdit) {
        this.onEdit = onEdit;
    }

    /// @param onRemove Run when Remove is pressed (not when read only).
    public void setOnRemove(final Runnable onRemove) {
        this.onRemove = onRemove;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = !isReadOnly();
        final boolean selected = getSelected() != null;
        addButton.setEnabled(editable);
        editButton.setEnabled(editable && selected);
        removeButton.setEnabled(editable && selected);
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XPathAssertion::getName).build(),
                DataGridUtil.headingBuilder("Name").build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder(XPathAssertion::getXpath).build(),
                DataGridUtil.headingBuilder("XPath")
                        .withToolTip("Must evaluate true for every record.")
                        .build(),
                XPATH_COL);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, XPathAssertionList> {

    }
}
