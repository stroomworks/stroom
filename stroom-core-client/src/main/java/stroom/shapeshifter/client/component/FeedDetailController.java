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
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.preferences.client.DateTimeFormatter;
import stroom.shapeshifter.client.component.ConversationList.Row;
import stroom.shapeshifter.client.component.FeedSituation.Tone;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerRequest;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.SuggestionAction;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.menu.client.Item;
import stroom.widget.menu.client.ShowMenuEvent;
import stroom.widget.menu.client.SimpleMenuItem;
import stroom.widget.popup.client.PopupPosition;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Element;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/// One Smart Pipeline's learning on its Learning Status tab ([FeedDetail]): reads its learning runs and the one
/// shown, with its turns, says where the feed stands ([FeedSituation]) and how far the run has got
/// ([FeedProgress]), and does what a person asks: every action the Supervisor offers on a learner, its
/// translation and its learning run, together, through the `SupervisorResource` calls and dialogs (A28, A115,
/// A141). It is the one place a pipeline's learning is acted on; the Smart Pipelines screen in Monitoring only
/// lists the pipelines and opens them here. Its Learning history opens of its own accord when it comes to be
/// needed ([FeedSituation#needsHistory]), and is otherwise left as the user put it.
public class FeedDetailController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final int RUNS = 50;

    private final RestFactory restFactory;
    private final DateTimeFormatter dateTimeFormatter;
    private final WaitingStreamListController waitingList;
    private final Provider<GrantEditorController> grantProvider;
    private final Provider<SupervisorPromptController> promptProvider;
    private final Provider<GuidanceEditorController> guidanceProvider;
    private final Provider<TurnAnswerEditorController> answerProvider;
    private FeedDetail view;
    private ShapeshifterAiDoc doc;
    private SupervisorLearner learner;
    /// Whether the feed's pipeline can't be edited here: then nothing is offered that would change its learning.
    private boolean readOnly;
    private List<SupervisorAttempt> runs = new ArrayList<>();
    /// The run a person chose to look at, or null for the newest.
    private Long chosen;
    /// The run shown, with its turns, or null for none.
    private SupervisorAttempt run;
    /// Whether the feed's Learning history was wanted open as last shown, so that it opens of its own accord
    /// only when it comes to be wanted, and is otherwise left as the user put it.
    private boolean historyWanted;
    /// Which read is current, so that a slower earlier reply is ignored.
    private int asked;
    private Runnable onActed = () -> {
    };
    private Runnable onOpenSettings = () -> {
    };
    private Consumer<SuggestionAction> onOpenSetup = action -> {
    };

    /// Made by GIN; [#attach(FeedDetail)] gives it its view.
    ///
    /// @param eventBus          The application's event bus.
    /// @param restFactory       Makes REST requests.
    /// @param dateTimeFormatter Says when each learning run started, as the user likes dates.
    /// @param waitingList       The streams waiting for the feed.
    /// @param grantProvider     Makes the dialog that gives a paused run more.
    /// @param promptProvider    Makes the prompt that asks why, or what to improve.
    /// @param guidanceProvider  Makes the dialog of the notes left for a feed.
    /// @param answerProvider    Makes the dialog a question is answered in.
    @Inject
    public FeedDetailController(final EventBus eventBus,
                                final RestFactory restFactory,
                                final DateTimeFormatter dateTimeFormatter,
                                final WaitingStreamListController waitingList,
                                final Provider<GrantEditorController> grantProvider,
                                final Provider<SupervisorPromptController> promptProvider,
                                final Provider<GuidanceEditorController> guidanceProvider,
                                final Provider<TurnAnswerEditorController> answerProvider) {
        super(eventBus);
        this.restFactory = restFactory;
        this.dateTimeFormatter = dateTimeFormatter;
        this.waitingList = waitingList;
        this.grantProvider = grantProvider;
        this.promptProvider = promptProvider;
        this.guidanceProvider = guidanceProvider;
        this.answerProvider = answerProvider;
    }

    /// Attaches this to its view, declared in its owner's template.
    ///
    /// @param view The view.
    /// @return This, for its owner to keep.
    public FeedDetailController attach(final FeedDetail view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        own(waitingList);
        view.setWaitingList(waitingList);
        view.setOnAction(this::onAction);
        view.setOnSuggestion(this::onSuggestion);
        view.setOnMore(this::showMore);
        view.setOnRun(picked -> {
            chosen = picked == null
                    ? null
                    : picked.getId();
            readRun(true);
        });
        view.setOnAnswer(this::answer);
        view.setOnSendNote(this::sendNote);
        view.setOnShowNotes(this::showNotes);
        view.setOnOpenTranslation(() -> {
            if (learner != null && learner.getFragment() != null) {
                OpenDocumentEvent.fire(this, learner.getFragment(), true);
            }
        });
        view.setOnWaitingShown(waitingList::refresh);
        // Read only where the pipeline can't be edited: nothing is offered that the server would refuse
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = Boolean.TRUE.equals(readOnly);
            if (learner != null) {
                show(false);
            }
        });
        return this;
    }

    /// @param doc The document the feeds run under: its flow, pass mark and budgets.
    public void setDocument(final ShapeshifterAiDoc doc) {
        this.doc = doc;
    }

    /// @param onActed Run after the user has done something to the feed, for the list to be read again, and
    ///                with it this feed's row and run.
    public void setOnActed(final Runnable onActed) {
        this.onActed = onActed;
    }

    /// @param onOpenSettings Run when the user asks for the Setup tab, to switch learning on.
    public void setOnOpenSettings(final Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    /// @param onOpenSetup Told a suggestion that is followed on the Setup tab: to change the limits, the
    ///                    learning steps or the model.
    public void setOnOpenSetup(final Consumer<SuggestionAction> onOpenSetup) {
        this.onOpenSetup = onOpenSetup;
    }

    /// Shows a feed, or none. The same feed read again keeps the run chosen and the questions open.
    ///
    /// @param chosenLearner The feed's learner row, or null for none.
    public void setFeed(final SupervisorLearner chosenLearner) {
        final boolean same = learner != null && chosenLearner != null
                             && learner.getLearner().equals(chosenLearner.getLearner());
        learner = chosenLearner;
        if (learner == null) {
            asked++;
            run = null;
            runs = new ArrayList<>();
            view.showNothing();
            return;
        }
        if (same) {
            refresh();
            return;
        }
        chosen = null;
        run = null;
        runs = new ArrayList<>();
        historyWanted = false;
        view.setHistoryOpen(false);
        waitingList.setLearner(learner);
        show(true);
        readRuns(true);
    }

    /// Reads the feed's runs and the run shown again, keeping what is chosen and open.
    public void refresh() {
        if (learner != null) {
            readRuns(false);
            if (view.isWaitingShown()) {
                waitingList.refresh();
            }
        }
    }

    // ---- Reading ----

    private void readRuns(final boolean fresh) {
        final int request = ++asked;
        final SupervisorLearner whose = learner;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.find(AttemptCriteria.forLearner(new PageRequest(0, RUNS),
                        whose.getLearner())))
                .onSuccess(page -> {
                    if (request == asked) {
                        runs = new ArrayList<>(NullSafe.list(page.getValues()));
                        readRun(fresh);
                    }
                })
                .onFailure(error -> {
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // The run chosen, or the newest, with its turns
    private void readRun(final boolean fresh) {
        final SupervisorAttempt row = runs.stream()
                .filter(candidate -> chosen != null && candidate.getId() == chosen)
                .findFirst()
                .orElse(runs.isEmpty()
                        ? null
                        : runs.get(0));
        if (row == null) {
            run = null;
            show(fresh);
            return;
        }
        final int request = ++asked;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.fetch(row.getId()))
                .onSuccess(detail -> {
                    if (request == asked) {
                        final boolean another = run == null || run.getId() != detail.getId();
                        run = detail;
                        show(fresh || another);
                    }
                })
                .onFailure(error -> {
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void show(final boolean fresh) {
        final String feed = run == null
                ? ""
                : "Feed " + run.getFeed() + " · " + run.getType();
        view.showFeed(FeedList.name(learner), feed);
        final FeedSituation situation = FeedSituation.of(learner, run, doc, System.currentTimeMillis());
        view.setSituation(readOnly
                ? situation.readOnly()
                : situation);
        view.setTranslation(learner.getFragment() == null
                ? null
                : NullSafe.getOrElse(learner.getFragment(), DocRef::getName, "Learned translation"));
        view.setRuns(runs, run == null
                ? null
                : run.getId(), this::runLabel);
        final List<FlowStep> steps = doc == null
                ? List.of()
                : doc.getFlow().getSteps();
        view.setProgress(FeedProgress.of(steps, run));
        view.setConversation(rows(steps), fresh, !readOnly && run != null && !run.isPipelineDeleted());
        view.setNotes(!readOnly && !learner.isPipelineDeleted(), learner.getGuidance());
        final boolean waiting = learner.getWaiting() > 0;
        view.setHistory(!runs.isEmpty() || waiting, waiting);
        final boolean wanted = FeedSituation.needsHistory(learner, run);
        if (wanted && !historyWanted) {
            view.setHistoryOpen(true);
        }
        historyWanted = wanted;
    }

    private String runLabel(final SupervisorAttempt attempt) {
        return dateTimeFormatter.format(attempt.getCreateTimeMs()) + " · "
               + NullSafe.get(attempt.getStatus(), status -> status.getDisplayValue());
    }

    private List<Row> rows(final List<FlowStep> steps) {
        final List<Row> rows = new ArrayList<>();
        if (run == null) {
            return rows;
        }
        for (final SupervisorTurn turn : NullSafe.list(run.getTurns())) {
            final String step = stepName(steps, turn);
            final String who = turn.getAnswer() == null
                    ? "Nobody yet"
                    : "model".equalsIgnoreCase(turn.getAnsweredBy())
                            ? "AI"
                            : NullSafe.string(turn.getAnsweredBy());
            final String result;
            final Tone tone;
            if (turn.getAnswer() == null) {
                result = "Waiting for an answer";
                tone = Tone.IDLE;
            } else if (turn.getOutcome() == null) {
                result = "Not judged";
                tone = Tone.IDLE;
            } else {
                result = PlainWords.result(turn.getOutcome());
                tone = turn.getOutcome() == StepOutcome.PASSED
                        ? Tone.GOOD
                        : Tone.WARNING;
            }
            rows.add(new Row(turn.getNumber(), step, who, result, tone,
                    NullSafe.get(turn.getScore(), PlainWords::score), turn.getQuestion(), turn.getAnswer()));
        }
        return rows;
    }

    private static String stepName(final List<FlowStep> steps, final SupervisorTurn turn) {
        for (int i = 0; i < steps.size(); i++) {
            if (turn.getStepId() != null && steps.get(i).effectiveId().equalsIgnoreCase(turn.getStepId())) {
                return PlainWords.stepName(steps, i);
            }
        }
        return PlainWords.stepName(turn.getKind(), null);
    }

    // ---- Acting ----

    private void showMore(final Element opener) {
        if (learner == null) {
            return;
        }
        final FeedSituation situation = FeedSituation.of(learner, run, doc, System.currentTimeMillis());
        final List<Item> items = new ArrayList<>();
        for (final FeedAction action : situation.getMore()) {
            items.add(new SimpleMenuItem.Builder()
                    .text(action.getLabel())
                    .command(() -> onAction(action))
                    .build());
        }
        ShowMenuEvent
                .builder()
                .name("More actions")
                .items(items)
                .popupPosition(new PopupPosition(opener.getAbsoluteLeft(),
                        opener.getAbsoluteTop() + opener.getClientHeight() + 1))
                .opener(opener)
                .fire(this);
    }

    /// A suggestion followed: the feed's own action where it is one, the note box, the model's document, or
    /// the Setup tab.
    private void onSuggestion(final SuggestionAction action) {
        final FeedAction feedAction = FeedSituation.feedAction(action);
        if (feedAction != null) {
            onAction(feedAction);
            return;
        }
        switch (action) {
            case LEAVE_NOTE -> view.focusNote();
            case OPEN_MODEL -> {
                if (doc != null && doc.getModel() != null) {
                    OpenDocumentEvent.fire(this, doc.getModel(), true);
                }
            }
            default -> onOpenSetup.accept(action);
        }
    }

    private void onAction(final FeedAction action) {
        if (learner == null) {
            return;
        }
        switch (action) {
            case APPROVE -> approve();
            case REJECT -> reject();
            case GRANT -> grant();
            case EXHAUST -> exhaust();
            case ANSWER -> {
                final SupervisorTurn open = FeedSituation.unanswered(run);
                if (open != null) {
                    answer(open.getNumber());
                }
            }
            case ACCEPT -> accept();
            case IMPROVE -> improve();
            case RELEARN -> relearn();
            case GIVE_UP -> giveUp();
            case RETRACT -> retract();
            case OPEN_SETTINGS -> onOpenSettings.run();
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
    }

    /// Puts a draft to use (A25): the promotion it waited for, and every stream that waited with it tried
    /// again.
    private void approve() {
        final SupervisorAttempt draft = run;
        ConfirmEvent.fire(this,
                "Approve this translation? It is put to use for this feed, and every stream that waited for it is "
                + "tried again.",
                ok -> {
                    if (ok) {
                        actOnRun(resource -> resource.approve(draft.getId()));
                    }
                });
    }

    /// Rejects a draft (A25): it goes, and the feed stops learning with the reason, which is asked for.
    private void reject() {
        final SupervisorAttempt draft = run;
        promptProvider.get().ask(this,
                "Reject this translation",
                "Why is it being rejected? The feed stops learning with this reason, and isn't learned again until "
                + "someone says Learn again.",
                reason -> {
                    if (!NullSafe.isBlankString(reason)) {
                        actOnRun(resource -> resource.reject(draft.getId(), new RejectRequest(reason)));
                    }
                });
    }

    /// Gives a paused run more to spend (A65, A77, A145), in the grant dialog.
    private void grant() {
        final SupervisorAttempt paused = run;
        grantProvider.get().grant(this, paused, doc, granted ->
                actOnRun(resource -> resource.grant(paused.getId(), granted)));
    }

    /// Sends a paused run to the fallback (A65, A76): the AI is asked to admit what can't be translated as
    /// marked Unknown events, and the stream is served with them.
    private void exhaust() {
        final SupervisorAttempt paused = run;
        ConfirmEvent.fire(this,
                "Finish with Unknowns? The AI isn't asked for another translation: it is asked to write the "
                + "records the stream still can't translate as marked Unknown events, and the stream is served "
                + "with them.",
                ok -> {
                    if (ok) {
                        actOnRun(resource -> resource.exhaust(paused.getId()));
                    }
                });
    }

    /// Answers a question in place of the AI, or changes an answer (A28): nothing is run here; the run is
    /// carried on from the answer by the learning job, and what came after it is asked again.
    private void answer(final int number) {
        final SupervisorAttempt shown = run;
        if (shown == null) {
            return;
        }
        final SupervisorTurn turn = NullSafe.stream(shown.getTurns())
                .filter(candidate -> candidate.getNumber() == number)
                .findFirst()
                .orElse(null);
        if (turn == null) {
            return;
        }
        final boolean readOnly = shown.isPipelineDeleted();
        final String caption = (turn.getAnswer() == null
                ? "Answer question "
                : "Change the answer to question ") + number + ": " + NullSafe.string(turn.getQuestion());
        answerProvider.get().edit(this, caption, NullSafe.string(turn.getAnswer()), readOnly,
                answer -> restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.amend(shown.getId(), number, new AmendTurnRequest(answer)))
                        .onSuccess(amended -> acted())
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec());
    }

    /// Accepts a translation on trial now (§6): it cleared the pass mark; what is skipped is the wait.
    private void accept() {
        final SupervisorLearner trial = learner;
        ConfirmEvent.fire(this,
                "Accept this translation now? It scored " + NullSafe.getOrElse(trial.getPromotedScore(),
                        PlainWords::percent, "enough") + " when it was learned, which cleared the pass mark, but from "
                + "fewer records than its Learning Flow asks for. Accepting it skips the wait for more.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.accept(trial.getDoc().getUuid(), trial.getBindingUuid()))
                                .onSuccess(done -> acted())
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    /// Asks for the translation in use to be made better (A46). What is typed is kept as a note for the
    /// feed, carried into every question after it; nothing typed asks again from what it already does.
    private void improve() {
        final SupervisorLearner serving = learner;
        promptProvider.get().ask(this,
                "Improve this translation",
                "What should be better about it? Leave it empty to ask again from what it already does. What you "
                + "write is kept as a note for this feed, so every question asked about it from now on carries it.",
                message -> restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.improve(serving.getDoc().getUuid(), serving.getBindingUuid(),
                                new ImproveRequest(message)))
                        .onSuccess(outcome -> AlertEvent.fireInfo(this, outcome.getSaid(), this::acted))
                        // The run goes on while the request is open, so one that times out may yet finish
                        .onFailure(error -> AlertEvent.fireError(this,
                                "The improvement didn't come back: " + error.getMessage(),
                                "It may still be running, or may have finished after the request gave up. Look "
                                + "at the feed's learning runs before asking again: asking twice spends a second "
                                + "run.",
                                this::acted))
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec());
    }

    /// Learns afresh from the next stream (A28, A29); what is in use goes on translating until then.
    private void relearn() {
        final SupervisorLearner whose = learner;
        ConfirmEvent.fire(this,
                "Learn this feed again? It is learned afresh from its next stream, and its translation, if it has "
                + "one, goes on translating until then. Streams that errored while it was stopped are ordinary "
                + "error streams: reprocess them from the pipeline's filter to put them through.",
                ok -> {
                    if (ok) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.relearnLearner(whose.getDoc().getUuid(),
                                        new LearnerRequest(whose.getLearner(), null)))
                                .onSuccess(done -> acted())
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    /// Stops learning (design 06 A115): streams that nothing translates error with the reason, which is
    /// asked for; what is in use goes on translating.
    private void giveUp() {
        final SupervisorLearner whose = learner;
        promptProvider.get().ask(this,
                "Stop learning this feed",
                "Why is it being stopped? It learns no more, and its streams that nothing translates error with "
                + "this reason. Its translation, if it has one, goes on translating; Learn again starts it again.",
                reason -> {
                    if (!NullSafe.isBlankString(reason)) {
                        restFactory
                                .create(SUPERVISOR_RESOURCE)
                                .method(resource -> resource.giveUp(whose.getDoc().getUuid(),
                                        new LearnerRequest(whose.getLearner(), reason)))
                                .onSuccess(done -> acted())
                                .owner(this)
                                .taskMonitorFactory(taskMonitor())
                                .exec();
                    }
                });
    }

    /// Takes the translation back (A28): the feed is learned afresh from its next stream. What it made stands
    /// as it made it, and the person is told how much, to reprocess if they want (A129).
    private void retract() {
        final SupervisorLearner serving = learner;
        promptProvider.get().ask(this,
                "Remove this translation",
                "Why is it being removed? It stops translating, and the feed is learned afresh from its next "
                + "stream. What it has already made stands as it made it.",
                reason -> {
                    if (NullSafe.isBlankString(reason)) {
                        return;
                    }
                    restFactory
                            .create(SUPERVISOR_RESOURCE)
                            .method(resource -> resource.retract(serving.getDoc().getUuid(),
                                    serving.getBindingUuid(), new RejectRequest(reason)))
                            .onSuccess(produced -> AlertEvent.fireInfo(this, produced == null || produced == 0
                                    ? "The translation has been removed. It had made nothing."
                                    : "The translation has been removed. " + PlainWords.count(produced, "stream")
                                      + (produced == 1
                                              ? " it made stands"
                                              : " it made stand")
                                      + " as it made them; reprocess them from the pipeline's filter to put them "
                                      + "through what translates now.", this::acted))
                            .owner(this)
                            .taskMonitorFactory(taskMonitor())
                            .exec();
                });
    }

    /// Leaves the AI a note about this feed (A46), carried into every question after it.
    private void sendNote(final String message) {
        if (learner == null || NullSafe.isBlankString(message)) {
            return;
        }
        final SupervisorLearner whose = learner;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.hint(whose.getDoc().getUuid(),
                        new GuidanceRequest(whose.getLearner(), message.trim())))
                .onSuccess(notes -> {
                    view.clearNote();
                    view.setNotes(true, NullSafe.list(notes).size());
                    acted();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// The notes left for this feed, to read and take back, in the guidance dialog.
    private void showNotes() {
        if (learner != null) {
            guidanceProvider.get().show(this, learner.getDoc().getUuid(), learner.getLearner(),
                    learner.isPipelineDeleted(), this::acted);
        }
    }

    // A call on the run shown, then everything read again
    private void actOnRun(final Function<SupervisorResource, SupervisorAttempt> call) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(call::apply)
                .onSuccess(decided -> acted())
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // The list is read again, which gives this its fresh row and reads the run again
    private void acted() {
        onActed.run();
    }
}
