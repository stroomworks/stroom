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

import stroom.learningflow.shared.SupervisorGuidance;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datepicker.client.ClientDateUtil;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The content of the dialog listing the notes (guidance) people have left for one learner, oldest first.
/// Each row shows what was written, by whom and when. Buttons add a note and withdraw the selected one. The
/// list is not paged. [GuidanceEditorController] shows it.
public class GuidanceEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton addButton;
    @UiField
    ToolbarButton removeButton;
    @UiField
    DataGrid<SupervisorGuidance> dataGrid;

    private final MultiSelectionModelImpl<SupervisorGuidance> selectionModel;
    private Runnable onAdd = () -> {
    };
    private Runnable onRemove = () -> {
    };

    /// Creates an empty list.
    public GuidanceEditor() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();
        addButton.setOnClick(() -> onAdd.run());
        removeButton.setOnClick(() -> onRemove.run());
    }

    /// Shows the notes. The selection is cleared first because it holds the row objects it was made from, and
    /// the new list has new objects; otherwise a note already withdrawn could still be offered for withdrawal.
    ///
    /// @param said The notes, oldest first.
    public void setGuidance(final List<SupervisorGuidance> said) {
        selectionModel.clear();
        dataGrid.setRowData(0, said);
        dataGrid.setRowCount(said.size(), true);
    }

    /// @return The selection. The grid allows several rows to be selected, but Remove acts only on the one
    ///         returned by `getSelected()`.
    public MultiSelectionModel<SupervisorGuidance> getSelectionModel() {
        return selectionModel;
    }

    /// @param enabled Whether a note may be added.
    public void setAddEnabled(final boolean enabled) {
        addButton.setEnabled(enabled);
    }

    /// @param enabled Whether the selected note may be withdrawn.
    public void setRemoveEnabled(final boolean enabled) {
        removeButton.setEnabled(enabled);
    }

    /// @param onAdd Run when the user asks to add a note.
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// @param onRemove Run when the user asks to withdraw the selected note.
    public void setOnRemove(final Runnable onRemove) {
        this.onRemove = onRemove;
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorGuidance said) -> text(said.getMessage())).build(),
                DataGridUtil.headingBuilder("Said")
                        .withToolTip("What was written, as it is carried into every question this "
                                     + "learner asks.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorGuidance said) -> text(said.getAuthor())).build(),
                DataGridUtil.headingBuilder("By")
                        .withToolTip("Whose it is. A person reading a hint a year later needs to know.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorGuidance said) ->
                        text(ClientDateUtil.toISOString(said.getTimeMs()))).build(),
                DataGridUtil.headingBuilder("When")
                        .withToolTip("Oldest first, so that a later hint correcting an earlier one reads "
                                     + "as a correction.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, GuidanceEditor> {

    }
}
