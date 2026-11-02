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

import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
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

/// Where one step goes, outcome by outcome (A37, design 01 §10.2): a row per transition, the outcome
/// it fires on and where it goes, with Add, Edit and Remove; a double click edits one. When read only,
/// the buttons are disabled. [TransitionListController] fills it.
public class TransitionList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton addButton;
    @UiField
    ToolbarButton editButton;
    @UiField
    ToolbarButton removeButton;
    @UiField
    DataGrid<Transition> dataGrid;

    private final MultiSelectionModelImpl<Transition> selectionModel;
    private Runnable onAdd = () -> {
    };
    private Runnable onEdit = () -> {
    };
    private Runnable onRemove = () -> {
    };

    /// Makes an empty list, with its columns.
    public TransitionList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();

        addButton.setOnClick(() -> {
            if (!isReadOnly()) {
                onAdd.run();
            }
        });
        editButton.setOnClick(() -> {
            if (!isReadOnly()) {
                onEdit.run();
            }
        });
        removeButton.setOnClick(() -> {
            if (!isReadOnly()) {
                onRemove.run();
            }
        });
        selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (!isReadOnly() && event.getSelectionType().isDoubleSelect()) {
                onEdit.run();
            }
        });
        enableButtons();
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((Transition row) ->
                        NullSafe.getOrElse(row.getOn(), StepOutcome::getDisplayValue, Transition.SPENT)).build(),
                DataGridUtil.headingBuilder("On")
                        .withToolTip("The outcome this fires on, or 'spent' when the step's candidates "
                                     + "are gone.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((Transition row) -> row.getGoTo() == null
                        ? Transition.ABANDON
                        : row.getGoTo()).build(),
                DataGridUtil.headingBuilder("Go to")
                        .withToolTip("The step it goes to, the end of the flow, or an exit: park, give-up, "
                                     + "or abandon for the step's own default.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    /// @param transitions The step's transitions, in the order they are declared.
    public void setData(final List<Transition> transitions) {
        dataGrid.setRowData(0, transitions);
        dataGrid.setRowCount(transitions.size());
        enableButtons();
    }

    /// @return The transition selected, or null for none.
    public Transition getSelected() {
        return selectionModel.getSelected();
    }

    /// @param transition The transition to select, or null to select none.
    public void setSelected(final Transition transition) {
        selectionModel.clear();
        if (transition != null) {
            selectionModel.setSelected(transition);
        }
        enableButtons();
    }

    /// @param onAdd Run when Add is pressed (not when read only).
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// @param onEdit Run when Edit is pressed, or a transition is double clicked (not when read only).
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
        final boolean readOnly = isReadOnly();
        final boolean selected = selectionModel.getSelected() != null;
        addButton.setEnabled(!readOnly);
        editButton.setEnabled(!readOnly && selected);
        removeButton.setEnabled(!readOnly && selected);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, TransitionList> {

    }
}
