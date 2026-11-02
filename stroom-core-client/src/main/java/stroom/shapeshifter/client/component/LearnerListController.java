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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestDataProvider;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.shapeshifter.client.component.LearnerList.LearnerAction;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.LearnerRequest;
import stroom.shapeshifter.shared.LearnerState;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;
import stroom.widget.component.client.Controller;
import stroom.widget.util.client.MultiSelectionModel;
import stroom.widget.util.client.SelectionType;

import com.google.gwt.core.client.GWT;
import com.google.gwt.view.client.Range;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/// The Supervisor's learning pipelines ([LearnerList], design 06 A115), fetched a page at a time. The
/// pipeline-level actions sit here (send back, give up, guidance, improve) and so do those on the binding
/// the row shows serving: accept, open its fragment, retract (A141). Narrowed by document, state and
/// deletion (A142).
public class LearnerListController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);

    private final RestFactory restFactory;
    private final Provider<GuidanceEditorController> guidanceProvider;
    private final Provider<LearnerFilterEditorController> filterProvider;
    private final Provider<SupervisorPromptController> promptProvider;
    private final RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>> dataProvider;
    private LearnerList view;
    /// What is being asked for: every learning pipeline of every readable document until somebody narrows
    /// it. Kept here because the data provider is asked for a page at a time and must ask the same question
    /// each time.
    private LearnerCriteria criteria = new LearnerCriteria();
    /// What is read again after a row is acted on: this list alone, until the Supervisor says otherwise.
    private Runnable onActed = this::refresh;

    /// Made by GIN; [#attach(LearnerList)] gives it its list.
    ///
    /// @param eventBus         The application's event bus.
    /// @param restFactory      Makes REST requests.
    /// @param guidanceProvider Makes the dialog of what has been said to a learner.
    /// @param filterProvider   Makes the filter's dialog.
    /// @param promptProvider   Makes the prompt that asks for a reason or a message.
    @Inject
    public LearnerListController(final EventBus eventBus,
                                 final RestFactory restFactory,
                                 final Provider<GuidanceEditorController> guidanceProvider,
                                 final Provider<LearnerFilterEditorController> filterProvider,
                                 final Provider<SupervisorPromptController> promptProvider) {
        super(eventBus);
        this.restFactory = restFactory;
        this.guidanceProvider = guidanceProvider;
        this.filterProvider = filterProvider;
        this.promptProvider = promptProvider;
        dataProvider = new RestDataProvider<SupervisorLearner, ResultPage<SupervisorLearner>>(eventBus) {
            @Override
            protected void exec(final Range range,
                                final Consumer<ResultPage<SupervisorLearner>> dataConsumer,
                                final RestErrorHandler errorHandler) {
                final LearnerCriteria asked = new LearnerCriteria(
                        new PageRequest(range.getStart(), range.getLength()),
                        criteria.getSortList(),
                        criteria.getDocUuid(),
                        criteria.getStates(),
                        criteria.isShowDeletedPipelines());
                restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.learners(asked))
                        .onSuccess(page -> {
                            dataConsumer.accept(page);
                            keepSelected(page);
                        })
                        .onFailure(errorHandler)
                        .owner(LearnerListController.this)
                        .taskMonitorFactory(view.getTaskMonitor())
                        .exec();
            }
        };
    }

    /// Attaches this to a list, e.g. one a template declares, which it fills from the server. The list is
    /// then this controller's widget.
    ///
    /// @param view The list.
    /// @return This, for its owner to keep.
    public LearnerListController attach(final LearnerList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnAction(this::onAction);
        own(view.getSelectionModel().addSelectionHandler(event -> updateButtons()));
        view.setDataProvider(dataProvider);
        updateFilterButton();
        updateButtons();
        return this;
    }

    /// @return The selection, whose learner the levels beneath follow.
    public MultiSelectionModel<SupervisorLearner> getSelectionModel() {
        return view.getSelectionModel();
    }

    /// Reads the page again.
    public void refresh() {
        dataProvider.refresh();
    }

    /// @param onActed What to read again after a row is acted on.
    public void setOnActed(final Runnable onActed) {
        this.onActed = onActed;
    }

    private void onAction(final LearnerAction action) {
        switch (action) {
            case FILTER -> filter();
            case RELEARN -> relearn();
            case GIVE_UP -> giveUp();
            case GUIDANCE -> guidance();
            case IMPROVE -> improve();
            case ACCEPT -> accept();
            case OPEN -> open();
            case RETRACT -> retract();
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
    }

    /// Narrows what is shown, or widens it again (A142). Always from the first page: the offset a person is
    /// on is an offset into a list that has just changed length.
    private void filter() {
        // What is being asked for now, taken now: the dialog does not stop a person opening it again behind
        // itself, and reading the field when OK is pressed would write one dialog's answer over the other's.
        final LearnerCriteria opened = criteria;
        filterProvider.get().edit(this, opened, written -> {
            criteria = written;
            updateFilterButton();
            // The selection goes with it: a row the filter has just excluded would still answer the guards
            // that decide what is offered.
            view.getSelectionModel().clear();
            updateButtons();
            view.showFirstPage();
            refresh();
        });
    }

    private void updateFilterButton() {
        view.setFiltering(LearnerFilterEditorController.isFiltering(criteria));
    }

    /// Sends the learner back to learn afresh from its next stream (A28, A29): for one that gave up, or
    /// that bound something a person is not content with.
    private void relearn() {
        final SupervisorLearner learner = selected();
        if (learner == null) {
            return;
        }
        ConfirmEvent.fire(this, "Learn again? The learner learns afresh from its next stream, and what is "
                                + "bound now keeps serving until then. Streams that errored while it had "
                                + "given up are ordinary error streams: reprocess them on the pipeline to "
                                + "put them through.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.relearnLearner(learner.getDoc().getUuid(),
                                        new LearnerRequest(learner.getLearner(), null)))
                                .onSuccess(done -> acted())
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    /// Gives the learner up (design 06 A115): it learns no more, and its streams nothing serves error with
    /// the reason, which is asked for rather than assumed. What serves it goes on serving.
    private void giveUp() {
        final SupervisorLearner learner = selected();
        if (learner == null) {
            return;
        }
        promptProvider.get().ask(this,
                "Give this learner up",
                "Why is this learner being given up? It learns no more, and its streams that nothing "
                + "serves error with the reason. What serves it goes on serving, and learning again "
                + "sends it back.",
                reason -> {
                    // Empty is no reason, and this one is not offered without one.
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.giveUp(learner.getDoc().getUuid(),
                                    new LearnerRequest(learner.getLearner(), reason)))
                            .onSuccess(done -> acted())
                            .owner(this)
                            .taskMonitorFactory(taskMonitor())
                            .exec();
                });
    }

    /// Asks for a binding that is already serving to be made better (A46).
    ///
    /// The message is optional and the prompt says so: asking again from the incumbent, with the records
    /// it was accepted on and the score it made on them, is itself worth something. What is typed is kept
    /// as guidance for the learner rather than for the attempt, so it is carried into every question it
    /// asks from then on, including the relearning of A29 months later.
    private void improve() {
        final SupervisorLearner binding = selected();
        if (binding == null) {
            return;
        }
        // Cancel is no answer and OK with nothing typed is "ask again from what it already does", and the
        // two must not be confused: an improvement spends a model's tokens and may rebind the binding, so a
        // person who changed their mind must not have started one. The prompt calls back on OK alone.
        promptProvider.get().ask(this,
                "Make this binding better",
                "What should be better about this binding? Leave it empty to ask again from what it already "
                + "does. What you write is kept against the learner, so everything it asks from now "
                + "on carries it.",
                message -> restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.improve(binding.getDoc().getUuid(),
                                binding.getBindingUuid(), new ImproveRequest(message)))
                        // The incumbent served every stream throughout and a candidate takes over only
                        // through the ordinary gate, so "nothing changed" is an outcome and not a failure.
                        // The person is told which of the four.
                        .onSuccess(outcome -> AlertEvent.fireInfo(this, outcome.getSaid(), this::acted))
                        // The attempt runs while the request is open, so a request that times out has not
                        // necessarily failed: it may have promoted or drafted since. Said rather than left
                        // as a bare error, because the answer to "did it work?" is in the session list and
                        // a second click would spend another model run.
                        .onFailure(error -> AlertEvent.fireError(this,
                                "The improvement did not come back: " + error.getMessage(),
                                "It may still be running, or may have finished after the request "
                                + "gave up. Look for its session under the pipeline before asking again — "
                                + "asking twice spends a second model run.",
                                this::acted))
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec());
    }

    /// Tells this binding's learner something, without asking for anything to be run (A46): the feed's own
    /// explanation of a field, a correction, a fact no sample shows.
    ///
    /// Separate from improving, because a person who has just learned something about a feed should be
    /// able to write it down at once and have it carried into whatever is asked next, which may be months
    /// away, and may be a relearning nobody is watching.
    private void guidance() {
        final SupervisorLearner binding = selected();
        if (binding != null) {
            // What is standing first, then the adding: a person about to say something should see what has
            // already been said, or the same hint is given three times and a wrong one is never taken back.
            // Only the count on the row changes, so the row a person is reading stays selected while it is
            // read again.
            guidanceProvider.get().show(this, binding.getDoc().getUuid(), binding.getLearner(),
                    binding.isPipelineDeleted(), this::refresh);
        }
    }

    /// Accepts a provisional binding now (§6): the binding stops being marked provisional and serves on the
    /// score it was bound at.
    ///
    /// Confirmed, and the confirmation says what is being skipped. A provisional binding cleared the
    /// promotion floor (that is what made it bindable), and what it has not had is enough records for a
    /// held-out judgement. Accepting says the wait is not worth it, which is a person's call to make and not
    /// a gate's.
    private void accept() {
        final SupervisorLearner binding = selected();
        if (binding == null || !binding.isProvisional()) {
            return;
        }
        ConfirmEvent.fire(this,
                "Accept this binding? It scored " + LearnerList.score(binding.getPromotedScore())
                + " on the stream it was learned from, which cleared the floor but was too few records to "
                + "judge it on. Accepting serves it on that score without waiting for more.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.accept(binding.getDoc().getUuid(),
                                        binding.getBindingUuid()))
                                .onSuccess(done -> acted())
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    /// Opens the selected binding's fragment, which is how a person gets from "this is scoring 0.71" to the
    /// stylesheet that is doing it.
    private void open() {
        final SupervisorLearner binding = selected();
        if (binding != null && binding.getFragment() != null) {
            OpenDocumentEvent.fire(this, binding.getFragment(), true);
        }
    }

    /// Takes a binding back (A28): what it produced was produced by a binding that is being withdrawn. It is
    /// counted, not put through again (A129): reprocessing it as it would be now is a filter the person
    /// makes on the pipeline.
    ///
    /// Confirmed and reasoned, because it is the most consequential thing on this screen: a feed stops being
    /// served until its learner learns again. The reason is asked for rather than assumed; it is recorded
    /// against the attempt that wrote the binding.
    private void retract() {
        final SupervisorLearner binding = selected();
        if (binding == null) {
            return;
        }
        promptProvider.get().ask(this,
                "Take this binding back",
                "Why is this binding being taken back? It stops serving and its learner learns again "
                + "from the next stream. What it produced stands as it made it: you are told how many "
                + "streams that is, to reprocess on the pipeline if you want them as they would be now.",
                reason -> {
                    // Empty is no reason, and this one is not offered without one.
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.retract(binding.getDoc().getUuid(),
                                    binding.getBindingUuid(), new RejectRequest(reason)))
                            // What a person cannot see for themselves: how much stands as the withdrawn
                            // binding made it.
                            .onSuccess(produced -> AlertEvent.fireInfo(this, produced == null || produced == 0
                                    ? "The binding has been taken back. It had produced nothing."
                                    : "The binding has been taken back. " + produced + " stream"
                                      + (produced == 1
                                              ? " it produced stands"
                                              : "s it produced stand")
                                      + " as it made them; reprocess them on the pipeline to put "
                                      + "them through what serves now.", this::acted))
                            .owner(this)
                            .taskMonitorFactory(taskMonitor())
                            .exec();
                });
    }

    /// Reads everything again after something was done to a row: the row, and the sessions and waiting
    /// streams beneath it, which giving up or learning again change too. The row stays selected, and
    /// [#keepSelected] swaps in its fresh copy when the page comes back.
    private void acted() {
        onActed.run();
    }

    /// The selected row swapped for its copy in the page just read, without telling anyone the selection
    /// changed, since the learner chosen has not.
    ///
    /// A selection holds the row *object* it was made from, and reading the list again makes new ones
    /// without touching it, so a binding just accepted would go on answering "provisional" to the guard
    /// that offers Accept, a learner a session's rejection has just given up would go on offering Give up,
    /// and a binding a promotion has just replaced would go on offering Retract for itself. A row no longer
    /// on the page lets the selection go, and the levels beneath with it.
    private void keepSelected(final ResultPage<SupervisorLearner> page) {
        final SupervisorLearner selected = selected();
        if (selected == null) {
            return;
        }
        final SupervisorLearner fresh = NullSafe.stream(page.getValues())
                .filter(row -> selected.getLearner().equals(row.getLearner()))
                .findFirst()
                .orElse(null);
        if (fresh == null) {
            view.getSelectionModel().clear();
        } else {
            view.getSelectionModel().setSelected(fresh, new SelectionType(), false);
        }
        updateButtons();
    }

    private SupervisorLearner selected() {
        return view.getSelectionModel().getSelected();
    }

    /// What is offered depends on the row: nothing that would change a deleted pipeline's learning (A137),
    /// the binding's actions only where a binding serves, accept only where it is provisional, and give up
    /// only where the learner has not given up already.
    private void updateButtons() {
        final SupervisorLearner selected = selected();
        final boolean one = selected != null;
        final boolean live = one && !selected.isPipelineDeleted();
        final boolean serving = live && selected.getBindingUuid() != null;
        final Set<LearnerAction> available = EnumSet.of(LearnerAction.FILTER);
        if (live) {
            available.add(LearnerAction.RELEARN);
        }
        if (live && selected.getState() != LearnerState.GIVEN_UP) {
            available.add(LearnerAction.GIVE_UP);
        }
        if (one) {
            available.add(LearnerAction.GUIDANCE);
        }
        if (serving) {
            available.add(LearnerAction.IMPROVE);
            available.add(LearnerAction.RETRACT);
        }
        if (serving && selected.isProvisional()) {
            available.add(LearnerAction.ACCEPT);
        }
        if (one && selected.getFragment() != null) {
            available.add(LearnerAction.OPEN);
        }
        view.setAvailableActions(available);
    }
}
