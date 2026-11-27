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


package stroom.learningflow.client.component;

import stroom.learningflow.shared.CheckSetting;
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

/// A grid of a Learning Flow's quality checks, with an Edit button. It has one row for every check, showing
/// whether the check is Off, Advice or a Gate, its threshold if it has one, and its own settings. A double
/// click also edits a check. When read only, Edit is disabled and a double click does nothing.
/// [CheckListController] wires it.
public class CheckList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton editButton;
    @UiField
    DataGrid<CheckSetting> dataGrid;

    private final MultiSelectionModelImpl<CheckSetting> selectionModel;
    private Runnable onEdit = () -> {
    };

    /// Creates an empty list.
    public CheckList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();

        editButton.setOnClick(() -> {
            if (!isReadOnly() && getSelected() != null) {
                onEdit.run();
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

    /// @param checks The checks' settings, in the order to show them.
    public void setData(final List<CheckSetting> checks) {
        dataGrid.setRowData(0, checks);
        dataGrid.setRowCount(checks.size());
        enableButtons();
    }

    /// @return The check selected, or null if none is.
    public CheckSetting getSelected() {
        return selectionModel.getSelected();
    }

    /// @param setting The check to select.
    public void setSelected(final CheckSetting setting) {
        selectionModel.setSelected(setting);
        enableButtons();
    }

    /// Selects nothing.
    public void clearSelection() {
        selectionModel.clear();
        enableButtons();
    }

    /// @param onEdit Run when Edit is pressed, or a check is double clicked (not when read only).
    public void setOnEdit(final Runnable onEdit) {
        this.onEdit = onEdit;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        enableButtons();
    }

    private void enableButtons() {
        final boolean editable = !isReadOnly();
        final boolean selected = getSelected() != null;
        editButton.setEnabled(editable && selected);
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((CheckSetting row) -> PlainWords.checkName(row.getCheck()))
                        .build(),
                DataGridUtil.headingBuilder("Check")
                        .withToolTip("What is asked of every answer and every record translated.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((CheckSetting row) -> row.getState().getDisplayValue())
                        .build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Off: not run. Advice: shown and told to the AI, never refusing a "
                                     + "translation. Gate: a translation that falls short is refused.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((CheckSetting row) -> row.getThreshold() == null
                                ? ""
                                : PlainWords.percent(row.getThreshold()))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Threshold")
                        .withToolTip("The score below which an answer falls short of this check; none for a "
                                     + "check that only passes or fails.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((CheckSetting row) -> row.getParameters() == null
                                ? ""
                                : row.getParameters().toString())
                        .build(),
                DataGridUtil.headingBuilder("Settings")
                        .withToolTip("The check's own settings; edit the row to change them.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, CheckList> {

    }
}
