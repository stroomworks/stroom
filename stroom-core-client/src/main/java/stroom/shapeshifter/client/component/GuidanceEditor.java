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

import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
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

/// The content of the dialog of everything a supervisor has said to one learner (A46, A128), oldest
/// first: what was said, by whom and when, a row each; with buttons to say another thing and to take the
/// selected one back. Not paged. [GuidanceEditorController] shows it.
public class GuidanceEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    PagerPanel pager;
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

    /// Makes an empty list.
    public GuidanceEditor() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();
        addButton.setOnClick(() -> onAdd.run());
        removeButton.setOnClick(() -> onRemove.run());
    }

    /// Shows what has been said. The selection goes first: it holds the row object it was made from, and
    /// a rebuilt list makes new ones, so a row taken back would still be offered for taking back again.
    ///
    /// @param said What has been said, oldest first.
    public void setGuidance(final List<SupervisorGuidance> said) {
        selectionModel.clear();
        dataGrid.setRowData(0, said);
        dataGrid.setRowCount(said.size(), true);
    }

    /// @return The selection, one thing said at most.
    public MultiSelectionModel<SupervisorGuidance> getSelectionModel() {
        return selectionModel;
    }

    /// @return The task monitor that spins the pager's refresh button.
    public PagerPanel getTaskMonitor() {
        return pager;
    }

    /// @param enabled Whether something else may be said.
    public void setAddEnabled(final boolean enabled) {
        addButton.setEnabled(enabled);
    }

    /// @param enabled Whether the selected thing said may be taken back.
    public void setRemoveEnabled(final boolean enabled) {
        removeButton.setEnabled(enabled);
    }

    /// @param onAdd Run when the user asks to say something else.
    public void setOnAdd(final Runnable onAdd) {
        this.onAdd = onAdd;
    }

    /// @param onRemove Run when the user asks to take the selected thing back.
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
