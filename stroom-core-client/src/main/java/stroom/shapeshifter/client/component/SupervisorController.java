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

import stroom.alert.client.event.ConfirmEvent;
import stroom.content.client.component.ContentTabController;
import stroom.dispatch.client.RestFactory;
import stroom.shapeshifter.client.component.SessionList.SessionAction;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiResource;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.svg.client.IconColour;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.ControllerLayer;
import stroom.widget.tab.client.TabData;
import stroom.widget.tab.client.TabDataImpl;
import stroom.widget.util.client.HasRefresh;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/// The Supervisor (A28, design 06 A115), a content tab opened from the Monitoring menu ([Supervisor]):
/// three levels, master to detail. The learning pipelines above, with the actions on a pipeline's learner
/// and its binding ([LearnerListController]); beneath them the selected pipeline's sessions, with the
/// actions on a session, and beside them on a second tab the streams waiting on it; and beneath those the
/// selected session's conversation, turn by turn, with the whole of it to read on a second tab
/// ([TurnTranscript]), beside its path drawn over the flow (A88).
public class SupervisorController extends ContentTabController implements HasRefresh {

    public static final String TAB_TYPE = "ShapeshifterAiSupervisor";

    private static final TabData SESSIONS_TAB = new TabDataImpl("Sessions");
    private static final TabData WAITING_TAB = new TabDataImpl("Waiting streams");
    private static final TabData TURNS_TAB = new TabDataImpl("Turns");
    private static final TabData CONVERSATION_TAB = new TabDataImpl("Conversation");

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final ShapeshifterAiResource DOCUMENT_RESOURCE = GWT.create(ShapeshifterAiResource.class);

    private final Supervisor view;
    private final LearnerListController learnerList;
    private final SessionListController sessionList;
    private final WaitingStreamListController waitingList;
    private final TurnListController turnList;
    private final ControllerLayer sessionsLayer;
    private final ControllerLayer waitingLayer;
    private final ControllerLayer turnsLayer;
    private final ControllerLayer conversationLayer;
    private final RestFactory restFactory;
    private final Provider<GrantEditorController> grantProvider;
    private final Provider<SupervisorPromptController> promptProvider;
    /// The documents read to draw sessions' paths and to state their budgets in the grant dialog. Dropped
    /// when the Supervisor is refreshed or another pipeline is chosen, so that a flow or a budget edited
    /// since is not shown as it was.
    private final Map<String, ShapeshifterAiDoc> documents = new HashMap<>();
    /// The steps drawn now, so that a step chosen on the chart can be found among the turns.
    private List<FlowStep> steps = List.of();

    /// Made by GIN, with its screen and its parts.
    ///
    /// @param eventBus       The application's event bus.
    /// @param view           Its component.
    /// @param learnerList    The learning pipelines.
    /// @param sessionList    The selected pipeline's sessions.
    /// @param waitingList    The selected pipeline's waiting streams.
    /// @param turnList       The selected session's turns.
    /// @param grantProvider  Makes the grant dialog.
    /// @param promptProvider Makes the prompt that asks why a draft is rejected.
    /// @param restFactory    Makes REST requests.
    @Inject
    public SupervisorController(final EventBus eventBus,
                                final Supervisor view,
                                final LearnerListController learnerList,
                                final SessionListController sessionList,
                                final WaitingStreamListController waitingList,
                                final TurnListController turnList,
                                final Provider<GrantEditorController> grantProvider,
                                final Provider<SupervisorPromptController> promptProvider,
                                final RestFactory restFactory) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        this.learnerList = own(learnerList).attach(view.getLearnerList());
        this.sessionList = own(sessionList);
        this.waitingList = own(waitingList);
        this.turnList = own(turnList).attach(view.getTurnList());
        this.sessionsLayer = new ControllerLayer(sessionList);
        this.waitingLayer = new ControllerLayer(waitingList);
        this.turnsLayer = new ControllerLayer(turnList);
        this.conversationLayer = new ControllerLayer(view.getTranscript());
        this.grantProvider = grantProvider;
        this.promptProvider = promptProvider;
        this.restFactory = restFactory;

        // Giving a learner up or sending it back changes its sessions and waiting streams as well as its row.
        learnerList.setOnActed(this::refresh);
        own(learnerList.getSelectionModel().addSelectionHandler(event -> {
            // A pipeline chosen: its sessions and waiting streams beneath it, and no session yet.
            final SupervisorLearner chosen = learnerList.getSelectionModel().getSelected();
            documents.clear();
            sessionList.setLearner(NullSafe.get(chosen, SupervisorLearner::getLearner));
            waitingList.setLearner(chosen);
            turnList.read(null);
            updateButtons();
        }));

        view.getTabBar().addTab(SESSIONS_TAB);
        view.getTabBar().addTab(WAITING_TAB);
        own(view.getTabBar().addSelectionHandler(event -> selectTab(event.getSelectedItem())));
        selectTab(SESSIONS_TAB);

        // A session's turns, a line each to choose and answer from, and beside them its whole conversation
        // to read (A116).
        view.getConversationTabBar().addTab(TURNS_TAB);
        view.getConversationTabBar().addTab(CONVERSATION_TAB);
        own(view.getConversationTabBar().addSelectionHandler(event ->
                selectConversationTab(event.getSelectedItem())));
        selectConversationTab(TURNS_TAB);

        // The decisions A28 puts beside the attempt they are about. Approve and Reject are review mode's
        // (A25), offered here because this is where a person watching the feature meets the draft. What
        // acts on the learner rather than the session (learn again, give up, guidance) is on the pipeline's
        // row above (A115, A141).
        sessionList.setOnAction(this::onSessionAction);
        own(sessionList.getSelectionModel().addSelectionHandler(event -> {
            turnList.read(sessionList.getSelectionModel().getSelected());
            updateButtons();
        }));

        // A turn answered reopens the attempt and takes its claim back (A28), so the row it was answered
        // from is showing a status that is no longer true.
        turnList.setOnAmended(amended -> {
            sessionList.getSelectionModel().clear();
            updateButtons();
            refresh();
        });
        final TurnTranscript transcript = view.getTranscript();
        turnList.setOnDetail(detail -> {
            drawPath(detail);
            transcript.setTurns(NullSafe.getOrElse(detail, SupervisorAttempt::getTurns, List.of()));
        });
        // The transcript and the chart choose each other (A116): a turn lights its step and rings its visit;
        // a step brings its first turn into view, and a visit its own. The whole conversation follows
        // whichever chose.
        final FlowChart flowChart = view.getFlowChart();
        turnList.setOnTurnSelected(turn -> {
            final Integer number = NullSafe.get(turn, SupervisorTurn::getNumber);
            flowChart.setSelectedTurn(number);
            transcript.setSelected(number);
        });
        flowChart.setOnSelect(step -> {
            flowChart.setSelected(step);
            if (step >= 0 && step < steps.size()) {
                turnList.showStep(steps.get(step).effectiveId());
                transcript.setSelected(NullSafe.get(turnList.getSelected(), SupervisorTurn::getNumber));
            }
        });
        flowChart.setOnTurnSelect(number -> {
            flowChart.setSelectedTurn(number);
            turnList.showTurn(number);
            transcript.setSelected(number);
        });
        updateButtons();
    }

    @Override
    public void refresh() {
        documents.clear();
        learnerList.refresh();
        sessionList.refresh();
        waitingList.refresh();
    }

    @Override
    public SvgImage getIcon() {
        return SvgImage.AI;
    }

    @Override
    public IconColour getIconColour() {
        return IconColour.GREY;
    }

    @Override
    public String getLabel() {
        return "Shapeshifter AI Supervisor";
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }

    private void onSessionAction(final SessionAction action) {
        switch (action) {
            case APPROVE -> approve();
            case REJECT -> reject();
            case GRANT -> grant();
            case EXHAUST -> exhaust();
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
    }

    /// Approves what an attempt drafted (A25): the promotion it was waiting for, and the release of every
    /// stream that waited with it.
    private void approve() {
        final SupervisorAttempt attempt = awaitingReview();
        if (attempt != null) {
            ConfirmEvent.fire(this,
                    "Approve what this attempt drafted? It binds the fragment for its learner, and every "
                    + "stream that waited for it is tried again.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.approve(attempt.getId()));
                        }
                    });
        }
    }

    /// Rejects what an attempt drafted (A25): the binding goes and the learner gives up with the reason.
    /// The reason is asked for rather than assumed: it is what the learner carries afterwards.
    private void reject() {
        final SupervisorAttempt attempt = awaitingReview();
        if (attempt != null) {
            promptProvider.get().ask(this,
                    "Reject this draft",
                    "Why is this being rejected? Its learner gives up with the reason, and is not learned "
                    + "again until somebody says otherwise.",
                    reason -> {
                        if (!NullSafe.isBlankString(reason)) {
                            act(resource -> resource.reject(attempt.getId(), new RejectRequest(reason)));
                        }
                    });
        }
    }

    /// Gives a parked session more to spend (A65, A77): tokens over its life, minutes for each run (design
    /// 06 A145), asked for in the grant dialog and recorded against the attempt alone and not the document;
    /// the worker carries the session on with them.
    private void grant() {
        final SupervisorAttempt attempt = parked();
        if (attempt != null) {
            // Its document's budgets, where its path has been drawn and the document read for it.
            final ShapeshifterAiDoc doc = NullSafe.get(attempt.getDoc(), docRef -> documents.get(docRef.getUuid()));
            grantProvider.get().grant(this, attempt, doc, granted ->
                    act(resource -> resource.grant(attempt.getId(), granted)));
        }
    }

    /// Sends a parked session to the exhaustion step (A65, A76): the worker carries it on, puts no further
    /// transform question to the model, and asks for the admitted form for what the stream still wants.
    private void exhaust() {
        final SupervisorAttempt attempt = parked();
        if (attempt != null) {
            ConfirmEvent.fire(this,
                    "Send this attempt to the exhaustion step? The model is not asked for another "
                    + "translation: it is asked to admit, as marked Unknown events, the records the stream "
                    + "still cannot translate, and the stream is served with them.",
                    ok -> {
                        if (ok) {
                            act(resource -> resource.exhaust(attempt.getId()));
                        }
                    });
        }
    }

    /// The attempt's path over its document's flow (A88): the flow is fetched once per document and the
    /// turns are the transcript's; nothing is drawn where there is no attempt, or no document to draw.
    private void drawPath(final SupervisorAttempt detail) {
        if (detail == null || detail.getDoc() == null) {
            clearPath();
            return;
        }
        final String uuid = detail.getDoc().getUuid();
        final ShapeshifterAiDoc known = documents.get(uuid);
        if (known != null) {
            showPath(known, detail);
            return;
        }
        restFactory
                .create(DOCUMENT_RESOURCE)
                .method(resource -> resource.fetch(uuid))
                .onSuccess(doc -> {
                    documents.put(uuid, doc);
                    showPath(doc, detail);
                })
                .onFailure(error -> clearPath())
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    private void showPath(final ShapeshifterAiDoc doc, final SupervisorAttempt detail) {
        steps = NullSafe.list(doc.getFlow().getSteps());
        view.getFlowChart().show(steps, null, detail.getTurns(), null);
    }

    private void clearPath() {
        steps = List.of();
        view.getFlowChart().clear();
    }

    /// One action, one call, and the lists read again afterwards: an attempt decided here changes the row
    /// it was decided from, and a person watching should see it change.
    ///
    /// The selection goes first, and that is not tidiness. A selection holds the row *object* it was made
    /// from, and refreshing the list builds new ones without touching it, so an attempt that has just been
    /// approved would still answer `AWAITING_REVIEW` to the guard that decides whether Reject is offered,
    /// and rejecting it would take back the binding that was just promoted and give its learner up. What is
    /// shown afterwards is the decided attempt, which is what the person was looking at; what is *selected*
    /// is nothing, until they choose again from the list as it now is.
    private void act(final Function<SupervisorResource, SupervisorAttempt> call) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(call::apply)
                .onSuccess(decided -> {
                    sessionList.getSelectionModel().clear();
                    updateButtons();
                    turnList.read(decided);
                    // What serves with it, and where its pipeline now stands: approving a draft puts a binding
                    // in front of its learner, and rejecting one gives it up.
                    refresh();
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    /// The selected attempt where its pipeline has not been deleted, and null otherwise: a deleted
    /// pipeline's attempts are kept to be read, and nothing is offered that would change them (design 06
    /// A137).
    private SupervisorAttempt live() {
        final SupervisorAttempt attempt = sessionList.getSelectionModel().getSelected();
        return attempt != null && !attempt.isPipelineDeleted()
                ? attempt
                : null;
    }

    /// The selected attempt where it is one a person may approve or reject, and null otherwise: an attempt
    /// that drafted nothing has nothing to decide about.
    private SupervisorAttempt awaitingReview() {
        final SupervisorAttempt attempt = live();
        return attempt != null && AttemptStatus.AWAITING_REVIEW == attempt.getStatus()
                ? attempt
                : null;
    }

    /// The selected attempt where it is parked for a person, and null otherwise.
    private SupervisorAttempt parked() {
        final SupervisorAttempt attempt = live();
        return attempt != null && AttemptStatus.PARKED == attempt.getStatus()
                ? attempt
                : null;
    }

    private void updateButtons() {
        final Set<SessionAction> available = EnumSet.noneOf(SessionAction.class);
        if (awaitingReview() != null) {
            available.add(SessionAction.APPROVE);
            available.add(SessionAction.REJECT);
        }
        final SupervisorAttempt parked = parked();
        if (parked != null) {
            available.add(SessionAction.GRANT);
            // Sent once is sent (A65): the mark stands until the attempt closes, and the list says so.
            if (!parked.isExhaustNext()) {
                available.add(SessionAction.EXHAUST);
            }
        }
        sessionList.setAvailableActions(available);
    }

    /// Shows a session's turns, or its whole conversation.
    private void selectConversationTab(final TabData tab) {
        view.getConversationLayerContainer().show(CONVERSATION_TAB.equals(tab)
                ? conversationLayer
                : turnsLayer);
        view.getConversationTabBar().selectTab(tab);
    }

    /// Shows a tab's list beneath the pipelines: the sessions, or the waiting streams.
    private void selectTab(final TabData tab) {
        view.getLayerContainer().show(WAITING_TAB.equals(tab)
                ? waitingLayer
                : sessionsLayer);
        view.getTabBar().selectTab(tab);
    }
}
