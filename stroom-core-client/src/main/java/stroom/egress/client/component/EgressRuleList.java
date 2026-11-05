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

package stroom.egress.client.component;

import stroom.cell.tickbox.client.TickBoxState;
import stroom.egress.shared.EgressRuleDto;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
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
import java.util.function.BiConsumer;

/// The permitted destinations, a row a rule: its kind, destination, how it matches, why it exists and whether it is
/// enabled. New, Edit (or a double click) and Delete act on the selected rule.
public class EgressRuleList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton newButton;
    @UiField
    ToolbarButton editButton;
    @UiField
    ToolbarButton deleteButton;
    @UiField
    ToolbarButton refreshButton;
    @UiField
    DataGrid<EgressRuleDto> dataGrid;

    private final MultiSelectionModelImpl<EgressRuleDto> selectionModel;
    private Runnable onNew = () -> {
    };
    private Runnable onEdit = () -> {
    };
    private Runnable onDelete = () -> {
    };
    private Runnable onRefresh = () -> {
    };
    private BiConsumer<EgressRuleDto, Boolean> onEnabledChange = (rule, enabled) -> {
    };

    /// Makes an empty list.
    public EgressRuleList() {
        initWidget(BINDER.createAndBindUi(this));
        dataGrid.setKeyProvider(EgressRuleDto::getId);
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        addColumns();
        newButton.setOnClick(() -> onNew.run());
        editButton.setOnClick(() -> onEdit.run());
        deleteButton.setOnClick(() -> onDelete.run());
        refreshButton.setOnClick(() -> onRefresh.run());
        selectionModel.addSelectionHandler(event -> {
            enableButtons();
            if (event.getSelectionType().isDoubleSelect()) {
                onEdit.run();
            }
        });
        enableButtons();
    }

    /// Shows the rules, none selected.
    ///
    /// @param rules The rules, in the order to show them.
    public void setData(final List<EgressRuleDto> rules) {
        selectionModel.clear();
        dataGrid.setRowData(0, rules);
        dataGrid.setRowCount(rules.size());
        enableButtons();
    }

    /// @return The selection.
    public MultiSelectionModel<EgressRuleDto> getSelectionModel() {
        return selectionModel;
    }

    /// @param onNew Run when New is pressed.
    public void setOnNew(final Runnable onNew) {
        this.onNew = onNew;
    }

    /// @param onEdit Run when Edit is pressed, or a row is double clicked.
    public void setOnEdit(final Runnable onEdit) {
        this.onEdit = onEdit;
    }

    /// @param onDelete Run when Delete is pressed.
    public void setOnDelete(final Runnable onDelete) {
        this.onDelete = onDelete;
    }

    /// @param onRefresh Run when Refresh is pressed.
    public void setOnRefresh(final Runnable onRefresh) {
        this.onRefresh = onRefresh;
    }

    /// @param onEnabledChange Given a rule and whether it is now enabled, when its tick box is pressed.
    public void setOnEnabledChange(final BiConsumer<EgressRuleDto, Boolean> onEnabledChange) {
        this.onEnabledChange = onEnabledChange;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        enableButtons();
    }

    private void enableButtons() {
        final boolean selected = selectionModel.getSelected() != null;
        newButton.setEnabled(!isReadOnly());
        editButton.setEnabled(selected && !isReadOnly());
        deleteButton.setEnabled(selected && !isReadOnly());
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressRuleDto rule) -> rule.getKind().getDisplayValue()).build(),
                DataGridUtil.headingBuilder("Kind")
                        .withToolTip("The kind of destination the rule permits.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressRuleDto rule) -> rule.getDestination()).build(),
                DataGridUtil.headingBuilder("Destination")
                        .withToolTip("The destination, in the form it is compared in.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressRuleDto rule) -> rule.getMatch().getDisplayValue()).build(),
                DataGridUtil.headingBuilder("Match")
                        .withToolTip("Exact: the destination must be the same. Prefix: the destination must be "
                                     + "inside it, a whole part at a time.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((EgressRuleDto rule) -> NullSafe.string(rule.getComment())).build(),
                DataGridUtil.headingBuilder("Reason")
                        .withToolTip("Why the destination is permitted.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addColumn(
                DataGridUtil.updatableTickBoxColumnBuilder(TickBoxState.createTickBoxFunc(
                                (EgressRuleDto rule) -> NullSafe.get(rule, EgressRuleDto::isEnabled)))
                        .withFieldUpdater((index, row, value) -> {
                            if (row != null && !isReadOnly()) {
                                onEnabledChange.accept(row, value.toBoolean());
                            }
                        })
                        .centerAligned()
                        .build(),
                DataGridUtil.headingBuilder("Enabled")
                        .withToolTip("A rule that is not enabled permits nothing.")
                        .centerAligned()
                        .build(),
                ColumnSizeConstants.ENABLED_COL);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, EgressRuleList> {

    }
}
