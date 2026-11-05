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

import stroom.egress.shared.EgressAttemptDto;
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
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// What the policy has refused, or would have refused in report-only mode, a row for each destination, sink, pipeline
/// and feed, with how many times and when. Destinations are masked. Add Rule starts a rule from the selected row,
/// Reveal shows its destination in full (and is audited), and Clear empties the list (also audited).
public class EgressAttemptList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    ToolbarButton addRuleButton;
    @UiField
    ToolbarButton revealButton;
    @UiField
    ToolbarButton clearButton;
    @UiField
    ToolbarButton refreshButton;
    @UiField
    DataGrid<EgressAttemptDto> dataGrid;

    private final MultiSelectionModelImpl<EgressAttemptDto> selectionModel;
    private Runnable onAddRule = () -> {
    };
    private Runnable onReveal = () -> {
    };
    private Runnable onClear = () -> {
    };
    private Runnable onRefresh = () -> {
    };

    /// Makes an empty list.
    public EgressAttemptList() {
        initWidget(BINDER.createAndBindUi(this));
        dataGrid.setKeyProvider(EgressAttemptDto::getId);
        selectionModel = dataGrid.addDefaultSelectionModel(false);
        addColumns();
        addRuleButton.setOnClick(() -> onAddRule.run());
        revealButton.setOnClick(() -> onReveal.run());
        clearButton.setOnClick(() -> onClear.run());
        refreshButton.setOnClick(() -> onRefresh.run());
        selectionModel.addSelectionHandler(event -> enableButtons());
        enableButtons();
    }

    /// Shows the attempts, none selected.
    ///
    /// @param attempts The attempts, in the order to show them.
    public void setData(final List<EgressAttemptDto> attempts) {
        selectionModel.clear();
        dataGrid.setRowData(0, attempts);
        dataGrid.setRowCount(attempts.size());
        enableButtons();
    }

    /// @return The selection.
    public MultiSelectionModel<EgressAttemptDto> getSelectionModel() {
        return selectionModel;
    }

    /// @param onAddRule Run when Add Rule is pressed.
    public void setOnAddRule(final Runnable onAddRule) {
        this.onAddRule = onAddRule;
    }

    /// @param onReveal Run when Reveal is pressed.
    public void setOnReveal(final Runnable onReveal) {
        this.onReveal = onReveal;
    }

    /// @param onClear Run when Clear is pressed.
    public void setOnClear(final Runnable onClear) {
        this.onClear = onClear;
    }

    /// @param onRefresh Run when Refresh is pressed.
    public void setOnRefresh(final Runnable onRefresh) {
        this.onRefresh = onRefresh;
    }

    private void enableButtons() {
        final boolean selected = selectionModel.getSelected() != null;
        addRuleButton.setEnabled(selected);
        revealButton.setEnabled(selected);
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> row.getKind().getDisplayValue()).build(),
                DataGridUtil.headingBuilder("Kind").build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> NullSafe.string(row.getDestination()))
                        .build(),
                DataGridUtil.headingBuilder("Destination")
                        .withToolTip("Where it tried to send data. The last part is hidden, as it can hold a secret. "
                                     + "Reveal shows it.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> NullSafe.string(row.getOutcome())).build(),
                DataGridUtil.headingBuilder("Outcome")
                        .withToolTip("Denied: it was refused. Would deny: it was only reported, and went ahead.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> Long.toString(row.getCount()))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Count").rightAligned().build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) ->
                        NullSafe.string(ClientDateUtil.toISOString(row.getLastSeenMs()))).build(),
                DataGridUtil.headingBuilder("Last seen").build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> NullSafe.string(row.getSink())).build(),
                DataGridUtil.headingBuilder("Sink")
                        .withToolTip("The part of Stroom that tried to send it.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> NullSafe.string(row.getFeed())).build(),
                DataGridUtil.headingBuilder("Feed").build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((EgressAttemptDto row) -> NullSafe.string(row.getPipeline())).build(),
                DataGridUtil.headingBuilder("Pipeline").build(),
                ColumnSizeConstants.BIG_COL);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, EgressAttemptList> {

    }
}
