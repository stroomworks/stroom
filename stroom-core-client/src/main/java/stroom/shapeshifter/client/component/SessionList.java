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

import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.datepicker.client.ClientDateUtil;
import stroom.widget.task.client.TaskMonitorFactory;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;
import com.google.gwt.view.client.AbstractDataProvider;
import com.google.gwt.view.client.Range;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/// The selected learning pipeline's sessions, newest first (A28, design 06 A115, A143): one row per
/// attempt (when, on what feed and type, where it stands, what it came to, what it scored, what it cost,
/// what it has been granted and where it ran), one selectable; with the actions A28 puts beside the
/// attempt they act on.
public class SessionList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    PagerPanel pager;
    @UiField
    ToolbarButton approveButton;
    @UiField
    ToolbarButton rejectButton;
    @UiField
    ToolbarButton grantButton;
    @UiField
    ToolbarButton exhaustButton;
    @UiField
    DataGrid<SupervisorAttempt> dataGrid;

    private final MultiSelectionModelImpl<SupervisorAttempt> selectionModel;
    private final Map<SessionAction, ToolbarButton> buttons = new EnumMap<>(SessionAction.class);
    private Consumer<SessionAction> onAction = action -> {
    };

    /// Makes an empty list, with every action unavailable.
    public SessionList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();
        addButton(SessionAction.APPROVE, approveButton);
        addButton(SessionAction.REJECT, rejectButton);
        addButton(SessionAction.GRANT, grantButton);
        addButton(SessionAction.EXHAUST, exhaustButton);
    }

    /// @param dataProvider Gives the list its rows, a page at a time.
    public void setDataProvider(final AbstractDataProvider<SupervisorAttempt> dataProvider) {
        dataProvider.addDataDisplay(dataGrid);
    }

    /// @return The task monitor that spins the pager's refresh button.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// @return The selection, one session at most.
    public MultiSelectionModel<SupervisorAttempt> getSelectionModel() {
        return selectionModel;
    }

    /// Goes back to the first page, e.g. for another pipeline's sessions.
    public void showFirstPage() {
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
    }

    /// @param onAction Given each action the user takes.
    public void setOnAction(final Consumer<SessionAction> onAction) {
        this.onAction = onAction;
    }

    /// Sets the actions that apply now, to the session selected; the others are disabled.
    ///
    /// @param actions The actions that apply.
    public void setAvailableActions(final Set<SessionAction> actions) {
        buttons.forEach((action, button) -> button.setEnabled(actions.contains(action)));
    }

    private void addButton(final SessionAction action, final ToolbarButton button) {
        buttons.put(action, button);
        button.setOnClick(() -> onAction.accept(action));
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                        text(ClientDateUtil.toISOString(row.getCreateTimeMs()))).build(),
                DataGridUtil.headingBuilder("Started")
                        .withToolTip("When the attempt was opened.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getFeed())).build(),
                DataGridUtil.headingBuilder("Feed")
                        .withToolTip("The feed of the stream the attempt was raised on.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getType())).build(),
                DataGridUtil.headingBuilder("Type")
                        .withToolTip("The type of the stream the attempt was raised on.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(status(row))).build(),
                DataGridUtil.headingBuilder("Status")
                        .withToolTip("Where the attempt stands: learning, waiting for the model, waiting for "
                                     + "a person, or what it came to; whether a person has sent it to the "
                                     + "exhaustion step; and whether its pipeline has been deleted, when "
                                     + "deleted pipelines are shown.")
                        .build(),
                // Wide enough for "Abandoned (pipeline deleted)"
                200);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getDecision())).build(),
                DataGridUtil.headingBuilder("Decision")
                        .withToolTip("What the attempt came to, in its own words.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                                text(NullSafe.get(row.getScore(), score -> score.toString())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Score")
                        .withToolTip("What it scored, where it bound anything.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) ->
                                text(Long.toString(row.getTokensSpent())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Tokens")
                        .withToolTip("What the model charged for this attempt, where it says.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(GrantText.granted(row))).build(),
                DataGridUtil.headingBuilder("Granted")
                        .withToolTip("What a person has granted it beyond its document's budgets: tokens over "
                                     + "its whole life, and minutes for each run of it.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorAttempt row) -> text(row.getNode())).build(),
                DataGridUtil.headingBuilder("Node")
                        .withToolTip("Which node last carried the attempt.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    /// Where the attempt stands; that a person has sent it to the exhaustion step, until the worker takes
    /// it there (A65, ISSUES item 6); and that its pipeline is deleted where it is (design 06 A137): kept to
    /// be read, with nothing offered that would change it.
    private static String status(final SupervisorAttempt row) {
        final StringBuilder status = new StringBuilder(NullSafe.string(
                NullSafe.get(row.getStatus(), AttemptStatus::getDisplayValue)));
        if (row.isExhaustNext()) {
            status.append(", sent to the exhaustion step");
        }
        if (row.isPipelineDeleted()) {
            status.append(" (pipeline deleted)");
        }
        return status.toString();
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, SessionList> {

    }


    // --------------------------------------------------------------------------------


    /// An action on the selected session (A28).
    public enum SessionAction {
        /// Approves what it drafted (A25).
        APPROVE,
        /// Rejects what it drafted (A25).
        REJECT,
        /// Gives a parked session more to spend (A65, A77).
        GRANT,
        /// Sends a parked session to the exhaustion step (A65, A76).
        EXHAUST
    }
}
