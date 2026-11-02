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

import stroom.cell.tickbox.client.TickBoxState;
import stroom.shapeshifter.shared.ScorerSetting;
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

/// The scorer set of design §8.4 as a grid: one row per scorer the document applies, with its weight
/// in the total, the score below which an attempt fails, whether it is a gate, and its own
/// parameters; with Add, Edit and Remove. A double click edits a scorer. When read only, the buttons
/// are disabled and a double click does nothing. [ScorerListController] does the work.
public class ScorerList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton addButton;
    @UiField
    ToolbarButton editButton;
    @UiField
    ToolbarButton removeButton;
    @UiField
    DataGrid<ScorerSetting> dataGrid;

    private final MultiSelectionModelImpl<ScorerSetting> selectionModel;
    private Runnable onAdd = () -> {
    };
    private Runnable onEdit = () -> {
    };
    private Runnable onRemove = () -> {
    };

    /// Makes an empty list.
    public ScorerList() {
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

    /// @param scorers The scorers, in the order to show them.
    public void setData(final List<ScorerSetting> scorers) {
        dataGrid.setRowData(0, scorers);
        dataGrid.setRowCount(scorers.size());
        enableButtons();
    }

    /// @return The scorer selected, or null if none is.
    public ScorerSetting getSelected() {
        return selectionModel.getSelected();
    }

    /// @param scorer The scorer to select.
    public void setSelected(final ScorerSetting scorer) {
        selectionModel.setSelected(scorer);
        enableButtons();
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

    /// @param onEdit Run when Edit is pressed, or a scorer is double clicked (not when read only).
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
                DataGridUtil.textColumnBuilder((ScorerSetting row) -> row.getType().getDisplayValue())
                        .build(),
                DataGridUtil.headingBuilder("Scorer")
                        .withToolTip("What is measured on every attempt.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((ScorerSetting row) -> Double.toString(row.getWeight()))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Weight")
                        .withToolTip("How much this score contributes to the weighted total.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((ScorerSetting row) -> Double.toString(row.getThreshold()))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Threshold")
                        .withToolTip("The score, from 0 to 1, below which the attempt fails this scorer.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addColumn(
                DataGridUtil.readOnlyTickBoxColumnBuilder((ScorerSetting row) ->
                        TickBoxState.fromBoolean(row.isGate())).build(),
                DataGridUtil.headingBuilder("Gate")
                        .withToolTip("A gate must reach its threshold on its own, whatever the weighted total.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((ScorerSetting row) -> row.getParameters() == null
                                ? ""
                                : row.getParameters().toString())
                        .build(),
                DataGridUtil.headingBuilder("Parameters")
                        .withToolTip("The scorer's own settings; edit the row to change them.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ScorerList> {

    }
}
