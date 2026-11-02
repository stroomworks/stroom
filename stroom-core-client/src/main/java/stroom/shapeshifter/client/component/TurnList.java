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

import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.shapeshifter.shared.TurnScore;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.ColumnSizeConstants;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.datagrid.client.PagerPanel;
import stroom.widget.task.client.TaskMonitorFactory;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// One session's conversation, turn by turn (A28): its number, the step that asked it, what was asked,
/// what was answered and by whom, how the answer was judged and what it scored, a row each, one
/// selectable; with a button to answer the selected turn instead. The whole conversation is on one page
/// (A116): the turns are set rather than fetched a page at a time, so a second page would be empty, and a
/// Target over many kinds runs past a page of turns.
public class TurnList extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    PagerPanel pager;
    @UiField
    ToolbarButton answerButton;
    @UiField
    DataGrid<SupervisorTurn> dataGrid;

    private final MultiSelectionModelImpl<SupervisorTurn> selectionModel;
    private Runnable onAnswer = () -> {
    };

    /// Makes an empty list.
    public TurnList() {
        initWidget(BINDER.createAndBindUi(this));
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        addColumns();
        dataGrid.setRowData(0, List.of());
        dataGrid.setRowCount(0, true);
        answerButton.setOnClick(() -> onAnswer.run());
    }

    /// Shows the turns. The selection goes first: it holds the row object it was made from, and a rebuilt
    /// list makes new ones, so a turn answered would still be offered as the turn to answer.
    ///
    /// @param turns The turns, in order.
    public void setTurns(final List<SupervisorTurn> turns) {
        selectionModel.clear();
        dataGrid.setRowData(0, turns);
        dataGrid.setRowCount(turns.size(), true);
    }

    /// @return The selection, one turn at most.
    public MultiSelectionModel<SupervisorTurn> getSelectionModel() {
        return selectionModel;
    }

    /// @return The task monitor that spins the pager's refresh button.
    public TaskMonitorFactory getTaskMonitor() {
        return pager;
    }

    /// Scrolls a row into view, as little as it takes.
    ///
    /// @param row The row's index.
    public void scrollToRow(final int row) {
        if (row < dataGrid.getVisibleItemCount()) {
            dataGrid.scrollRowIntoView(row);
        }
    }

    /// @param enabled Whether the selected turn may be answered.
    public void setAnswerEnabled(final boolean enabled) {
        answerButton.setEnabled(enabled);
    }

    /// @param onAnswer Run when the user asks to answer the selected turn.
    public void setOnAnswer(final Runnable onAnswer) {
        this.onAnswer = onAnswer;
    }

    private void addColumns() {
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(Integer.toString(turn.getNumber())))
                        .rightAligned()
                        .build(),
                DataGridUtil.headingBuilder("#")
                        .withToolTip("The turn's place in the conversation.")
                        .rightAligned()
                        .build(),
                40);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(step(turn))).build(),
                DataGridUtil.headingBuilder("Step")
                        .withToolTip("The step of the flow that asked it, and which of the step's candidates "
                                     + "this was.")
                        .build(),
                ColumnSizeConstants.SMALL_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getQuestion())).build(),
                DataGridUtil.headingBuilder("Question")
                        .withToolTip("What was asked: the kind of question and what it was about. Not the "
                                     + "rendered prompt, which carries the stream's own text.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getAnswer())).build(),
                DataGridUtil.headingBuilder("Answer")
                        .withToolTip("What was answered. Empty for the question the attempt stopped at, "
                                     + "which nobody has answered yet.")
                        .build(),
                ColumnSizeConstants.BIG_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) -> text(turn.getAnsweredBy())).build(),
                DataGridUtil.headingBuilder("Answered by")
                        .withToolTip("The model the document names, or the person who answered instead.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) ->
                        text(NullSafe.get(turn.getOutcome(), outcome -> outcome.getDisplayValue()))).build(),
                DataGridUtil.headingBuilder("Outcome")
                        .withToolTip("How the answer was judged when it was run, where it has been run.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
        dataGrid.addResizableColumn(
                DataGridUtil.htmlColumnBuilder((SupervisorTurn turn) ->
                        text(NullSafe.get(turn.getScore(), TurnScore::format))).build(),
                DataGridUtil.headingBuilder("Score")
                        .withToolTip("The score of the check that decided it, against the document's threshold "
                                     + "for that check: on a shortfall the check that fell short, on a pass the "
                                     + "check that passed by the least. Empty where no scorer judged it.")
                        .build(),
                ColumnSizeConstants.MEDIUM_COL);
    }

    private static String step(final SupervisorTurn turn) {
        if (turn.getStepId() == null) {
            return "";
        }
        return turn.getCandidate() > 1
                ? turn.getStepId() + ", candidate " + turn.getCandidate()
                : turn.getStepId();
    }

    private static SafeHtml text(final String value) {
        return SafeHtmlUtils.fromString(NullSafe.string(value));
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, TurnList> {

    }
}
