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

import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/// One attempt's conversation, turn by turn (A28), in a [TurnList]: what was asked, what was answered
/// and by whom, and what the answer scored. A turn with no answer is the question the attempt stopped at,
/// which is what a person may answer instead. Read beside the attempt's path over its flow (design 06
/// A116): a turn chosen here lights its step there, and a step or a visit chosen there brings its turn
/// into view here.
public class TurnListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestFactory restFactory;
    private final Provider<TurnAnswerEditorController> answerEditorProvider;
    private final List<SupervisorTurn> turns = new ArrayList<>();
    private TurnList view;
    /// Which attempt the grid is showing: a person clicking down a list asks faster than the server
    /// answers, and the answer to a row they have left must not land in the grid under the row they are
    /// on now.
    private int asked;
    /// Which attempt's conversation is on show, so that an answer is written against the right one.
    private SupervisorAttempt attempt;
    /// What to do when an answer has been written: the attempt has changed and the list above it with it.
    private Consumer<SupervisorAttempt> onAmended = amended -> {
    };
    /// Who is told the attempt's detail once it is fetched, and null when there is no attempt.
    private Consumer<SupervisorAttempt> onDetail = detail -> {
    };
    /// Who is told which turn was chosen, so that the chart beside it can light its step (A116).
    private Consumer<SupervisorTurn> onTurnSelected = turn -> {
    };

    /// Made by GIN; [#attach(TurnList)] gives it its list.
    ///
    /// @param eventBus             The application's event bus.
    /// @param restFactory          Makes REST requests.
    /// @param answerEditorProvider Makes the dialog a turn is answered in.
    @Inject
    public TurnListController(final EventBus eventBus,
                              final RestFactory restFactory,
                              final Provider<TurnAnswerEditorController> answerEditorProvider) {
        super(eventBus);
        this.restFactory = restFactory;
        this.answerEditorProvider = answerEditorProvider;
    }

    /// Attaches this to a list, e.g. one a template declares. The list is then this controller's widget.
    ///
    /// @param view The list.
    /// @return This, for its owner to keep.
    public TurnListController attach(final TurnList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        // A28's two acts on a turn, which are one act: *answer instead* for the question an attempt
        // stopped at, and *edit and re-run from here* for one already answered. The same button, because
        // the difference is whether there is an answer there, and a person editing turn 7 of a finished
        // attempt is doing exactly what a person answering turn 7 of a parked one is.
        view.setOnAnswer(this::answer);
        own(view.getSelectionModel().addSelectionHandler(event -> {
            // Double-clicking a turn opens it, as it does everywhere else a row has a dialog behind it.
            // A cell of the grid holds one line of what may be a whole configuration, so the row is
            // unreadable until it is opened.
            if (event.getSelectionType().isDoubleSelect()) {
                answer();
            }
            onTurnSelected.accept(view.getSelectionModel().getSelected());
            updateButtons();
        }));
        updateButtons();
        return this;
    }

    /// @param onDetail Who is told the attempt's detail once it is fetched, and null when there is no
    ///                 attempt: the Supervisor draws the path over the flow from the same turns this
    ///                 lists (A88).
    public void setOnDetail(final Consumer<SupervisorAttempt> onDetail) {
        this.onDetail = onDetail;
    }

    /// @param onAmended Told the attempt when an answer has been written and the attempt run again from
    ///                  there.
    public void setOnAmended(final Consumer<SupervisorAttempt> onAmended) {
        this.onAmended = onAmended;
    }

    /// @param onTurnSelected Who is told which turn was chosen, or null when none is (A116).
    public void setOnTurnSelected(final Consumer<SupervisorTurn> onTurnSelected) {
        this.onTurnSelected = onTurnSelected;
    }

    /// @return The turn chosen, or null for none.
    public SupervisorTurn getSelected() {
        return view.getSelectionModel().getSelected();
    }

    /// Chooses the first turn asked at a step, and brings it into view (A116): a step chosen on the chart.
    ///
    /// @param stepId The step's id, as the flow gives it.
    public void showStep(final String stepId) {
        reveal(turn -> stepId != null && stepId.equalsIgnoreCase(turn.getStepId()));
    }

    /// Chooses a turn by its number, and brings it into view (A116): a visit chosen on the chart.
    ///
    /// @param number The turn's number.
    public void showTurn(final int number) {
        reveal(turn -> turn.getNumber() == number);
    }

    /// Reads the conversation of one attempt. The list gives the row without its turns (a page of
    /// transcripts is a page nobody reads), so the detail is fetched when a row is chosen.
    ///
    /// @param attempt The attempt, or null for none.
    public void read(final SupervisorAttempt attempt) {
        final int request = ++asked;
        this.attempt = attempt;
        turns.clear();
        show();
        updateButtons();
        if (attempt == null) {
            onDetail.accept(null);
            return;
        }
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.fetch(attempt.getId()))
                .onSuccess(detail -> {
                    if (request == asked) {
                        turns.addAll(NullSafe.list(detail.getTurns()));
                        show();
                        updateButtons();
                        onDetail.accept(detail);
                    }
                })
                .onFailure(error -> {
                    // The grid says nothing rather than going on showing the turns of whatever was
                    // selected before.
                    if (request == asked) {
                        show();
                    }
                })
                .owner(this)
                .taskMonitorFactory(view.getTaskMonitor())
                .exec();
    }

    /// Reads the conversation on show again, from the server: a session still being learned has asked
    /// more since it was chosen. The turn chosen stays chosen, by its number, without telling anyone (its
    /// owner keeps its step lit and its turn marked) and without scrolling to it, so that a person reading
    /// further down is left where they are. A reply that fails leaves the turns as they were.
    public void refresh() {
        if (attempt == null) {
            return;
        }
        final int request = ++asked;
        final long attemptId = attempt.getId();
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.fetch(attemptId))
                .onSuccess(detail -> {
                    if (request == asked) {
                        final Integer chosen = NullSafe.get(getSelected(), SupervisorTurn::getNumber);
                        attempt = detail;
                        turns.clear();
                        turns.addAll(NullSafe.list(detail.getTurns()));
                        show();
                        turns.stream()
                                .filter(turn -> chosen != null && turn.getNumber() == chosen)
                                .findFirst()
                                .ifPresent(turn -> view.getSelectionModel().setSelected(turn, new SelectionType(),
                                        false));
                        updateButtons();
                        onDetail.accept(detail);
                    }
                })
                .onFailure(error -> {
                    // Kept as it was: the next refresh may answer, and blanking a conversation a person is
                    // reading because one read of it failed would lose their place.
                })
                .owner(this)
                .taskMonitorFactory(view.getTaskMonitor())
                .exec();
    }

    /// The first turn that matches, selected without telling anyone (whoever asked has already shown
    /// what it stands for) and scrolled to. Where none matches, a step the session never reached, no turn
    /// is selected: the one selected before belongs to another step, and Answer would act on it.
    private void reveal(final Predicate<SupervisorTurn> matching) {
        for (int row = 0; row < turns.size(); row++) {
            final SupervisorTurn turn = turns.get(row);
            if (matching.test(turn)) {
                view.getSelectionModel().setSelected(turn, new SelectionType(), false);
                updateButtons();
                view.scrollToRow(row);
                return;
            }
        }
        view.getSelectionModel().clear(false);
        updateButtons();
    }

    /// Answers a turn in place of whoever answered it (A28).
    ///
    /// Nothing is run here. The answer is written and the attempt is left waiting for the worker to carry
    /// on from what it has now been told, because a person's request must not wait on a model. The turns
    /// after the one answered are discarded: what they were is a consequence of an answer that has
    /// changed, and re-walking derives them again (A45).
    private void answer() {
        final SupervisorTurn turn = view.getSelectionModel().getSelected();
        if (turn == null || attempt == null) {
            return;
        }
        // Which attempt this answer is for, taken now. The dialog does not stop a person clicking another
        // attempt in the list behind it, and reading the field when OK is pressed would write a
        // hand-written answer into whatever they had moved on to, reopening that attempt and taking its
        // claim back.
        final long attemptId = attempt.getId();
        // A deleted pipeline's attempt is read and not answered (design 06 A137): the turn opens as it
        // was answered, and closes without asking anything.
        final boolean readOnly = attempt.isPipelineDeleted();
        final String caption = (readOnly
                ? "Turn "
                : NullSafe.isBlankString(turn.getAnswer())
                        ? "Answer turn "
                        : "Edit turn ") + turn.getNumber() + ": " + NullSafe.string(turn.getQuestion());
        answerEditorProvider.get().edit(this, caption, NullSafe.string(turn.getAnswer()), readOnly,
                answer -> amend(attemptId, turn, answer));
    }

    private void amend(final long attemptId, final SupervisorTurn turn, final String answer) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.amend(attemptId, turn.getNumber(),
                        new AmendTurnRequest(answer)))
                .onSuccess(amended -> {
                    // The attempt is open again and has taken its claim back, so the list above is showing
                    // a status that is no longer true. Told first, because refreshing the list clears the
                    // selection and reading the transcript under a cleared selection would blank the
                    // answer just written.
                    onAmended.accept(amended);
                    read(amended);
                })
                .owner(this)
                .taskMonitorFactory(view.getTaskMonitor())
                .exec();
    }

    /// A deleted pipeline's attempt is kept to be read and not answered (design 06 A137): its turns still
    /// open on a double click, to read.
    private void updateButtons() {
        view.setAnswerEnabled(attempt != null
                              && !attempt.isPipelineDeleted()
                              && view.getSelectionModel().getSelected() != null);
    }

    private void show() {
        view.setTurns(new ArrayList<>(turns));
    }
}
