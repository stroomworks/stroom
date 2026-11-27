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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.learningflow.client.component.ConversationList.Row;
import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.shared.AmendTurnRequest;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.ReasonRequest;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.preferences.client.DateTimeFormatter;
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

/// Wires the view of one Smart Pipeline's learning ([FeedDetail]) on the pipeline's Learning Status tab.
///
/// It fetches the pipeline's learning runs and the chosen run with its turns, shows the feed's situation
/// ([FeedSituation]) and how far the run has got ([FeedProgress]), and carries out the actions a person takes
/// on the learner, its translation and its learning run, through `SupervisorResource` calls and dialogs. This
/// is the only place a pipeline's learning is acted on; the Smart Pipelines screen in Monitoring only lists the
/// pipelines and opens them here.
///
/// The Learning history section is expanded automatically when it becomes needed
/// ([FeedSituation#needsHistory]); otherwise it is left as the user set it.
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
    private final Provider<TurnPromptController> turnPromptProvider;
    private FeedDetail view;
    private LearningFlowDoc doc;
    private SupervisorLearner learner;
    /// Whether the user can't edit the feed's pipeline, in which case nothing that would change its learning is
    /// offered.
    private boolean readOnly;
    private List<SupervisorAttempt> runs = new ArrayList<>();
    /// The id of the run a person chose to look at, or null for the newest.
    private Long chosen;
    /// The run shown, with its turns, or null for none.
    private SupervisorAttempt run;
    /// Whether the feed's Learning history needed to be expanded when it was last shown. It is expanded
    /// automatically only when this changes from false to true, so that otherwise it is left as the user set it.
    private boolean historyWanted;
    /// A counter identifying the latest request, so that a reply to an earlier, slower request is ignored.
    private int asked;
    private Runnable onActed = () -> {
    };
    private Runnable onOpenSettings = () -> {
    };
    private Consumer<SuggestionAction> onOpenSetup = action -> {
    };

    /// Created by GIN; [#attach(FeedDetail)] then gives it its view.
    ///
    /// @param eventBus           The application's event bus.
    /// @param restFactory        Creates REST requests.
    /// @param dateTimeFormatter  Formats the time each learning run started in the user's preferred format.
    /// @param waitingList        The controller for the list of streams waiting for the feed.
    /// @param grantProvider      Creates the dialog that gives a paused run more tokens or time.
    /// @param promptProvider     Creates the dialog that asks why (on reject, stop or remove) or what to improve.
    /// @param guidanceProvider   Creates the dialog listing the notes left for a feed.
    /// @param answerProvider     Creates the dialog in which a question is answered.
    /// @param turnPromptProvider Creates the dialog that shows the prompt exactly as it was sent to the AI.
    @Inject
    public FeedDetailController(final EventBus eventBus,
                                final RestFactory restFactory,
                                final DateTimeFormatter dateTimeFormatter,
                                final WaitingStreamListController waitingList,
                                final Provider<GrantEditorController> grantProvider,
                                final Provider<SupervisorPromptController> promptProvider,
                                final Provider<GuidanceEditorController> guidanceProvider,
                                final Provider<TurnAnswerEditorController> answerProvider,
                                final Provider<TurnPromptController> turnPromptProvider) {
        super(eventBus);
        this.restFactory = restFactory;
        this.dateTimeFormatter = dateTimeFormatter;
        this.waitingList = waitingList;
        this.grantProvider = grantProvider;
        this.promptProvider = promptProvider;
        this.guidanceProvider = guidanceProvider;
        this.answerProvider = answerProvider;
        this.turnPromptProvider = turnPromptProvider;
    }

    /// Attaches this controller to its view, which is declared in the owning screen's template.
    ///
    /// @param view The view.
    /// @return This controller, for the owner to keep.
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
        view.setOnShowPrompt(number -> {
            if (run != null) {
                turnPromptProvider.get().show(this, run.getId(), number);
            }
        });
        view.setOnSendNote(this::sendNote);
        view.setOnShowNotes(this::showNotes);
        view.setOnOpenTranslation(() -> {
            if (learner != null && learner.getFragment() != null) {
                OpenDocumentEvent.fire(this, learner.getFragment(), true);
            }
        });
        view.setOnWaitingShown(waitingList::refresh);
        // Read only when the user can't edit the pipeline, so that nothing is offered that the server would
        // refuse
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = Boolean.TRUE.equals(readOnly);
            if (learner != null) {
                show(false);
            }
        });
        return this;
    }

    /// @param doc The Learning Flow document the feeds learn under, which supplies the flow's steps, the
    ///            score below which a translation is flagged, and the budgets.
    public void setDocument(final LearningFlowDoc doc) {
        this.doc = doc;
    }

    /// @param onActed Run after the user has acted on the feed, so that the list is fetched again, which in
    ///                turn refreshes this feed's row and run.
    public void setOnActed(final Runnable onActed) {
        this.onActed = onActed;
    }

    /// @param onOpenSettings Run when the user asks to go to the Setup tab, to switch learning on.
    public void setOnOpenSettings(final Runnable onOpenSettings) {
        this.onOpenSettings = onOpenSettings;
    }

    /// @param onOpenSetup Called with a suggestion the user follows that is acted on in the Setup tab: to
    ///                    change the limits, the learning steps or the model.
    public void setOnOpenSetup(final Consumer<SuggestionAction> onOpenSetup) {
        this.onOpenSetup = onOpenSetup;
    }

    /// Shows a feed, or none. If it is the feed already shown, it is refreshed, keeping the chosen run and the
    /// expanded questions.
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

    /// Fetches the feed's runs and the run shown again, keeping the chosen run and what is expanded.
    public void refresh() {
        if (learner != null) {
            readRuns(false);
            if (view.isWaitingShown()) {
                waitingList.refresh();
            }
        }
    }

    private void readRuns(final boolean fresh) {
        final int request = ++asked;
        final SupervisorLearner whose = learner;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.findAttempts(AttemptCriteria.forLearner(new PageRequest(0, RUNS),
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

    // Fetches the chosen run, or the newest if none is chosen, with its turns
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
                .method(resource -> resource.fetchAttempt(row.getId()))
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
        view.setTimeZone(FeedSituation.timeZone(learner));
        view.setEvidence(run == null
                        ? List.of()
                        : Evidence.lines(run.getEvidence()),
                Evidence.reviewed(run, dateTimeFormatter::format));
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
        view.setNotes(!readOnly && !learner.isPipelineDeleted(), learner.getGuidanceCount());
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
                return FlowWords.stepName(steps, i);
            }
        }
        return FlowWords.stepName(turn.getKind(), null);
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

    /// Follows a suggestion: by taking the matching feed action if there is one, or by focusing the note box,
    /// opening the AI model's document, or going to the Setup tab.
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
            case IMPROVE -> improve();
            case RELEARN -> relearn();
            case GIVE_UP -> giveUp();
            case RETRACT -> retract();
            case OPEN_SETTINGS -> onOpenSettings.run();
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
    }

    /// Approves a draft translation, after confirmation. The translation is put to use, and every stream that
    /// waited for it is processed again.
    private void approve() {
        final SupervisorAttempt draft = run;
        ConfirmEvent.fire(this,
                "Approve this translation? It is put to use for this feed, and every stream that waited for it is "
                + "tried again.",
                ok -> {
                    if (ok) {
                        callThenReread(resource -> resource.approve(draft.getId()));
                    }
                });
    }

    /// Rejects a draft translation, asking for the reason. The draft is discarded, and the feed stops learning
    /// with that reason.
    private void reject() {
        final SupervisorAttempt draft = run;
        promptProvider.get().ask(this,
                "Reject this translation",
                "Why is it being rejected? The feed stops learning with this reason, and isn't learned again until "
                + "someone says Learn again.",
                reason -> {
                    if (!NullSafe.isBlankString(reason)) {
                        callThenReread(resource -> resource.reject(draft.getId(), new ReasonRequest(reason)));
                    }
                });
    }

    /// Gives a paused run more tokens or time, using the grant dialog. A run pauses for a person when it runs
    /// out of budget.
    private void grant() {
        final SupervisorAttempt paused = run;
        grantProvider.get().grant(this, paused, doc, granted ->
                callThenReread(resource -> resource.grant(paused.getId(), granted)));
    }

    /// Sends a paused run to the fallback step, after confirmation. Instead of another translation, the AI is
    /// asked to write the records that still can't be translated as marked Unknown events, and the stream is
    /// processed with them.
    private void exhaust() {
        final SupervisorAttempt paused = run;
        ConfirmEvent.fire(this,
                "Finish with Unknowns? The AI isn't asked for another translation: it is asked to write the "
                + "records the stream still can't translate as marked Unknown events, and the stream is served "
                + "with them.",
                ok -> {
                    if (ok) {
                        callThenReread(resource -> resource.exhaust(paused.getId()));
                    }
                });
    }

    /// Answers a question in place of the AI, or changes an answer. Nothing is run here: the learning job resumes
    /// the run from that answer, and the questions that came after it are asked again.
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
                answer -> callThenReread(resource ->
                        resource.amendTurn(shown.getId(), number, new AmendTurnRequest(answer))));
    }

    /// Asks for the translation in use to be improved. Any text typed is kept as a note for the feed and
    /// included in every later question; with no text, the AI is simply asked again, starting from the current
    /// translation.
    private void improve() {
        final SupervisorLearner serving = learner;
        promptProvider.get().ask(this,
                "Improve this translation",
                "What should be better about it? Leave it empty to ask again from what it already does. What you "
                + "write is kept as a note for this feed, so every question asked about it from now on carries it.",
                message -> restFactory
                        .create(SUPERVISOR_RESOURCE)
                        .method(resource -> resource.improve(serving.getBindingUuid(), new ImproveRequest(message)))
                        .onSuccess(outcome -> AlertEvent.fireInfo(this, outcome.getSaid(), this::acted))
                        // This request only queues the improvement: the background worker asks the AI, and the
                        // result shows on this tab. So a failure here means nothing was queued or run.
                        .onFailure(error -> AlertEvent.fireError(this,
                                "The improvement couldn't be asked for: " + error.getMessage(),
                                "Nothing has been asked of the AI, so nothing has been spent. Try again.",
                                this::acted))
                        .owner(this)
                        .taskMonitorFactory(taskMonitor())
                        .exec());
    }

    /// Starts learning the feed again from scratch with its next stream, after confirmation. Any translation in
    /// use carries on translating until then.
    private void relearn() {
        final SupervisorLearner whose = learner;
        ConfirmEvent.fire(this,
                "Learn this feed again? It is learned afresh from its next stream, and its translation, if it has "
                + "one, goes on translating until then. Streams that errored while it was stopped are ordinary "
                + "error streams: reprocess them from the pipeline's filter to put them through.",
                ok -> {
                    if (ok) {
                        callThenReread(resource -> resource.relearn(whose.getLearner().getPipelineUuid(),
                                whose.getLearner().getElementId(), new ReasonRequest(null)));
                    }
                });
    }

    /// Stops learning the feed, asking for the reason. Streams that nothing translates then fail with that
    /// reason; any translation in use carries on translating.
    private void giveUp() {
        final SupervisorLearner whose = learner;
        promptProvider.get().ask(this,
                "Stop learning this feed",
                "Why is it being stopped? It learns no more, and its streams that nothing translates error with "
                + "this reason. Its translation, if it has one, goes on translating; Learn again starts it again.",
                reason -> {
                    if (!NullSafe.isBlankString(reason)) {
                        callThenReread(resource -> resource.giveUp(whose.getLearner().getPipelineUuid(),
                                whose.getLearner().getElementId(), new ReasonRequest(reason)));
                    }
                });
    }

    /// Removes the translation in use, asking for the reason. The feed is learned again from its next stream.
    /// The output the translation already produced is left as it is; the person is told how many streams it
    /// produced, so that they can reprocess them if they want to.
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
                            .method(resource -> resource.retract(serving.getBindingUuid(), new ReasonRequest(reason)))
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

    /// Leaves the AI a note about this feed, which is included in every later question.
    private void sendNote(final String message) {
        if (learner == null || NullSafe.isBlankString(message)) {
            return;
        }
        final SupervisorLearner whose = learner;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.addGuidance(whose.getLearner().getPipelineUuid(),
                        whose.getLearner().getElementId(), new GuidanceRequest(message.trim())))
                .onSuccess(notes -> {
                    view.clearNote();
                    view.setNotes(true, NullSafe.list(notes).size());
                    acted();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// Shows the notes left for this feed in the guidance dialog, where they can be read and withdrawn.
    private void showNotes() {
        if (learner != null) {
            guidanceProvider.get().show(this, learner.getLearner(),
                    learner.isPipelineDeleted(), this::acted);
        }
    }

    // Makes a call on the run or learner shown, then fetches everything again
    private <R> void callThenReread(final Function<SupervisorResource, R> call) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(call::apply)
                .onSuccess(result -> acted())
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // Asks for the list to be fetched again, which gives this controller a fresh row and so fetches the run
    // again
    private void acted() {
        onActed.run();
    }
}
