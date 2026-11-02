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

import stroom.docref.DocRef;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.SupervisorLearner;
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

/// The Supervisor's learning pipelines (design 06 A115): one row per pipeline's Shapeshifter AI element
/// that has learned or is learning (the pipeline, its document, where it stands, what serves it, what
/// waits on it, when it last had a session and how much has been said to it), most waiting first, one
/// selectable; with the actions on the pipeline's learner and on the binding the row shows serving (A141),
/// and a filter (A142).
public class LearnerList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final String FILTER_TITLE = "Narrow which learning pipelines are shown";
    private static final String FILTERING_TITLE = "Learning pipelines are being filtered; click to change or clear";

    @UiField
    PagerPanel pager;
    @UiField
    ToolbarButton filterButton;
    @UiField
    ToolbarButton relearnButton;
    @UiField
    ToolbarButton giveUpButton;
    @UiField
    ToolbarButton guidanceButton;
    @UiField
    ToolbarButton improveButton;
    @UiField
    ToolbarButton acceptButton;
    @UiField
    ToolbarButton openButton;
    @UiField
    ToolbarButton retractButton;
    @UiField
    DataGrid<SupervisorLearner> dataGrid;

    private final MultiSelectionModelImpl<SupervisorLearner> selectionModel;
    private final Map<LearnerAction, ToolbarButton> buttons = new EnumMap<>(LearnerAction.class);
    private Consumer<LearnerAction> onAction = action -> {
    };

    /// Makes an empty list, with only the filter available.
    public LearnerList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();
        addButton(LearnerAction.FILTER, filterButton);
        addButton(LearnerAction.RELEARN, relearnButton);
        addButton(LearnerAction.GIVE_UP, giveUpButton);
        addButton(LearnerAction.GUIDANCE, guidanceButton);
        addButton(LearnerAction.IMPROVE, improveButton);
        addButton(LearnerAction.ACCEPT, acceptButton);
        addButton(LearnerAction.OPEN, openButton);
        addButton(LearnerAction.RETRACT, retractButton);
    }

    /// @param dataProvider Gives the list its rows, a page at a time.
    public void setDataProvider(final AbstractDataProvider<SupervisorLearner> dataProvider) {
        dataProvider.addDataDisplay(dataGrid);
    }

    /// @return The task monitor that spins the pager's refresh button.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// @return The selection, one learning pipeline at most.
    public MultiSelectionModel<SupervisorLearner> getSelectionModel() {
        return selectionModel;
    }

    /// Goes back to the first page, e.g. after the filter has changed.
    public void showFirstPage() {
        dataGrid.setVisibleRange(new Range(0, PageRequest.DEFAULT_PAGE_LENGTH));
    }

    /// The filter button says whether anything is being left out, or deleted pipelines let in.
    ///
    /// @param filtering Whether the list is filtered.
    public void setFiltering(final boolean filtering) {
        filterButton.setTitle(filtering
                ? FILTERING_TITLE
                : FILTER_TITLE);
    }

    /// @param onAction Given each action the user takes.
    public void setOnAction(final Consumer<LearnerAction> onAction) {
        this.onAction = onAction;
    }

    /// Sets the actions that apply now, to the row selected; the others are disabled.
    ///
    /// @param actions The actions that apply.
    public void setAvailableActions(final Set<LearnerAction> actions) {
        buttons.forEach((action, button) -> button.setEnabled(actions.contains(action)));
    }

    private void addButton(final LearnerAction action, final ToolbarButton button) {
        buttons.put(action, button);
        button.setOnClick(() -> onAction.accept(action));
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(pipeline(row))).build(),
                DataGridUtil.headingBuilder("Pipeline")
                        .withToolTip("The pipeline whose Shapeshifter AI element this is, and the element's id "
                                     + "where it is not the usual one.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) ->
                        text(NullSafe.get(row.getDoc(), DocRef::getName))).build(),
                DataGridUtil.headingBuilder("Document")
                        .withToolTip("The Shapeshifter AI document the element runs under.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(state(row))).build(),
                DataGridUtil.headingBuilder("State")
                        .withToolTip("Learning, waiting for a person, serving, given up, or learning off for "
                                     + "its document; and whether its pipeline has been deleted, when deleted "
                                     + "pipelines are shown.")
                        .build(),
                // Wide enough for "Waiting for a person" and a deleted pipeline's note
                200);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(serving(row))).build(),
                DataGridUtil.headingBuilder("Serving")
                        .withToolTip("The fragment that serves it, what it scores lately over the learner's "
                                     + "rolling memory and how many records that rests on, what it scored "
                                     + "when it was promoted, and whether it is scoring below its "
                                     + "document's relearn threshold.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) -> text(waiting(row)))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("Waiting")
                        .withToolTip("How many streams wait on it, and whether the longest has waited past "
                                     + "its document's maximum deferral age.")
                        .rightAligned()
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) ->
                        text(NullSafe.get(row.getLastSessionMs(), ClientDateUtil::toISOString))).build(),
                DataGridUtil.headingBuilder("Last session")
                        .withToolTip("When its most recent learning session opened.")
                        .build(),
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorLearner row) ->
                        text(row.getGuidance() == 0
                                ? ""
                                : Integer.toString(row.getGuidance()))).rightAligned().build(),
                DataGridUtil.headingBuilder("Said")
                        .withToolTip("How many things a supervisor has told this learner, so that you can see "
                                     + "whether the last was acted on before saying another.")
                        .rightAligned()
                        .build(),
                60);
    }

    /// A score as the screen shows it: to three places.
    ///
    /// @param score The score, or null for none.
    /// @return The score, or empty where there is none.
    static String score(final Double score) {
        return score == null
                ? ""
                : Double.toString(Math.round(score * 1000.0) / 1000.0);
    }

    private static String pipeline(final SupervisorLearner row) {
        final String name = row.getPipeline() == null || row.getPipeline().getName() == null
                ? NullSafe.get(row.getLearner(), learner -> learner.getPipelineUuid())
                : row.getPipeline().getName();
        final String element = NullSafe.get(row.getLearner(), learner -> learner.getElementId());
        return "shapeshifterAi".equals(element) || element == null
                ? name
                : name + " / " + element;
    }

    private static String state(final SupervisorLearner row) {
        final String state = NullSafe.get(row.getState(), LearnerState::getDisplayValue);
        final String withReason = row.getGivenUpReason() == null
                ? state
                : state + ": " + row.getGivenUpReason();
        return row.isPipelineDeleted()
                ? withReason + " (pipeline deleted)"
                : withReason;
    }

    private static String serving(final SupervisorLearner row) {
        if (row.getBindingUuid() == null) {
            return "";
        }
        final StringBuilder serving = new StringBuilder(NullSafe.string(
                NullSafe.get(row.getFragment(), DocRef::getName)));
        if (row.getRollingScore() != null) {
            // What the score rests on, since 0.6 over three records and 0.6 over ten thousand are different
            // things to a person deciding whether to improve it (A46).
            serving.append(", ").append(score(row.getRollingScore())).append(" lately over ")
                    .append(row.getRecords()).append(row.getRecords() == 1
                            ? " record"
                            : " records");
        }
        if (row.getPromotedScore() != null) {
            serving.append(", ").append(score(row.getPromotedScore())).append(" promoted");
        }
        if (row.isProvisional()) {
            serving.append(", provisional");
        }
        if (row.isBelowThreshold()) {
            // The document's relearn threshold, read here and nowhere else (design 04 §6): a person may
            // improve it.
            serving.append(", below the threshold");
        }
        return serving.toString();
    }

    private static String waiting(final SupervisorLearner row) {
        return row.isRaised()
                ? row.getWaiting() + " (raised)"
                : Integer.toString(row.getWaiting());
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, LearnerList> {

    }


    // --------------------------------------------------------------------------------


    /// An action on the learning pipelines, or on the selected one's learner or binding.
    public enum LearnerAction {
        /// Narrows which learning pipelines are shown (A142); always available.
        FILTER,
        /// Sends the learner back to learn afresh from its next stream (A28, A29).
        RELEARN,
        /// Gives the learner up (design 06 A115).
        GIVE_UP,
        /// Shows what has been said to the learner, and says something else (A46).
        GUIDANCE,
        /// Asks for the binding to be made better (A46).
        IMPROVE,
        /// Accepts a provisional binding now (§6).
        ACCEPT,
        /// Opens the binding's fragment.
        OPEN,
        /// Takes the binding back (A28).
        RETRACT
    }
}
