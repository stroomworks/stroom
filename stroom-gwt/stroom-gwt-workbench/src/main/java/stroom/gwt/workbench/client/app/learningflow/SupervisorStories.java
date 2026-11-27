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

package stroom.gwt.workbench.client.app.learningflow;

import stroom.document.client.event.OpenDocumentEvent;
import stroom.gwt.workbench.client.app.gin.content.ContentScreenGinjector;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.learningflow.client.component.FeedFilterEditorController;
import stroom.learningflow.client.component.SupervisorController;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Main/LearningFlowSupervisor` stories, showing Stroom's real [SupervisorController] (the Smart
/// Pipelines monitoring tab) with fake REST replies. The screen lists every Smart Pipeline of every Learning
/// Flow, each naming its Learning Flow and status, with a button that opens the pipeline at its Learning
/// Status tab, where its learning is acted on. The screen is opened as `LearningFlowSupervisorPlugin` opens
/// it and refreshed as the content pane does. The refresh stories refresh it again, as the content pane's
/// timer does, after one pipeline's status has changed.
///
/// Its dialog is the filter of which pipelines to show ([FeedFilterEditorController]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `POST /learningFlowSupervisor/v1/learners/find` | the Smart Pipelines, every Learning Flow's |
public final class SupervisorStories {

    private static final String FILTER = "Which pipelines to show";
    private static final String FILTERED = "Pipelines are being filtered; click to change or clear";
    /// The name of the spy that records the UUID of each document the screen asks Stroom to open.
    private static final String OPENED = "openedDocument";

    // A given-up pipeline of a second Learning Flow, so that the list covers more than one Learning Flow.
    private static final String AUDIT_LEARNER = """
            {"learner": {"pipelineUuid": "p-4", "elementId": "learningFlow"},
              "doc": {"type": "LearningFlow", "uuid": "ss-2", "name": "Audit logs"},
              "pipeline": {"type": "Pipeline", "uuid": "p-4", "name": "Audit pipeline"},
              "state": "Given up", "givenUpReason": "No passing configuration for the parser after 5 attempts",
              "waiting": 0, "records": 0, "guidanceCount": 0, "raised": false, "belowThreshold": false,
              "pipelineDeleted": false, "lastSessionMs": 1761500000000}""";
    // A deleted pipeline, listed only when deleted pipelines are requested.
    private static final String DELETED_LEARNER = """
            {"learner": {"pipelineUuid": "p-2", "elementId": "learningFlow"},
              "doc": {"type": "LearningFlow", "uuid": "ss-1", "name": "Firewall logs"},
              "pipeline": {"type": "Pipeline", "uuid": "p-2", "name": "Old proxy pipeline"},
              "state": "Given up", "waiting": 0, "records": 0, "guidanceCount": 0, "raised": false,
              "belowThreshold": false, "pipelineDeleted": true}""";
    private static final String LEARNERS = page(LearningFlowFixtures.FIREWALL_LEARNER,
            LearningFlowFixtures.PROXY_LEARNER, AUDIT_LEARNER);
    private static final String WITH_DELETED = page(LearningFlowFixtures.FIREWALL_LEARNER,
            LearningFlowFixtures.PROXY_LEARNER, AUDIT_LEARNER, DELETED_LEARNER);
    // The list as read again after the firewall pipeline has learned its translation.
    private static final String MOVED_ON = page(LearningFlowFixtures.FIREWALL_TRANSLATING,
            LearningFlowFixtures.PROXY_LEARNER, AUDIT_LEARNER);

    // The screen the story shows. A play can't reach its render's locals, so the render sets this field, and
    // the refresh stories use it to refresh the screen as the content pane's timer does.
    private static SupervisorController supervisor;

    private SupervisorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Main/LearningFlowSupervisor", SupervisorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The pipelines of every Learning Flow, each with its Learning Flow, its status and the reason
                // for it, and a button that opens it. Nothing on this screen acts on a pipeline's learning.
                .story("Default", SupervisorStories::render)
                .withPlay(play -> {
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Proxy pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Audit pipeline")).toBeInTheDocument();
                    play.expect(grid.getByRole("columnheader", TextMatch.startingWith("Learning Flow")))
                            .toBeInTheDocument();
                    play.expect(grid.getAllByText("Firewall logs")).toHaveLength(2);
                    play.expect(grid.getByText("Audit logs")).toBeInTheDocument();
                    play.expect(grid.getByText(TextMatch.containing("No passing configuration for the parser")))
                            .toBeInTheDocument();
                    play.expect(grid.getByRole("button", "Open pipeline Firewall pipeline")).toBeInTheDocument();
                    play.expect(grid.queryByText("Old proxy pipeline")).toBeNull();
                    play.expect(play.queryByRole("button", TextMatch.startingWith("Give this learner up")))
                            .toBeNull();
                    play.expect(play.getByRole("button", "Filter Pipelines")).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // The quick filter shows the pipelines whose own name, or whose Learning Flow's name, contains
                // its text.
                .story("QuickFilterByLearningFlow", SupervisorStories::render)
                .withPlay(play -> {
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    play.type(play.getByRole("searchbox", "Filter pipelines by name"), "audit logs");
                    play.waitFor(() -> play.expect(grid.queryByText("Firewall pipeline")).toBeNull());
                    play.expect(grid.getByText("Audit pipeline")).toBeInTheDocument();
                    play.expect(grid.queryByText("Proxy pipeline")).toBeNull();
                    expectNoProblems(play);
                })
                // When deleted pipelines are requested in the filter dialog, OK lists them too and the button
                // says the list is filtered.
                .story("FilterDialogOk", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    openFilter(play);
                    play.click(screen.getByRole("checkbox", "Show deleted pipelines"));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText(FILTER, StroomDom.DIALOG_TITLE)).toBeNull());
                    play.waitFor(() -> play.expect(grid.getByText("Old proxy pipeline")).toBeInTheDocument());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/learners/find")
                                    .withJsonBodyContaining("{\"showDeletedPipelines\": true}").toSpyMatcher());
                    play.expect(play.getByRole("button", FILTERED)).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // Cancelling the filter dialog requests nothing more and leaves the list unfiltered.
                .story("FilterDialogCancel", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    openFilter(play);
                    play.click(screen.getByRole("checkbox", "Show deleted pipelines"));
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(FILTER, StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/learners/find")
                                    .withJsonBodyContaining("{\"showDeletedPipelines\": true}").toSpyMatcher());
                    play.expect(grid.queryByText("Old proxy pipeline")).toBeNull();
                    play.expect(play.getByRole("button", "Filter Pipelines")).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // A row's button asks Stroom to open its pipeline, where its learning is followed and acted on.
                .story("OpenPipeline", SupervisorStories::render)
                .withPlay(play -> {
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.click(grid.findByRole("button", "Open pipeline Audit pipeline"));
                    play.waitFor(() -> play.expect(play.spy(OPENED)).toHaveBeenCalledWith("p-4"));
                    expectNoProblems(play);
                })
                // A refresh, as the content pane's timer does it, reads each pipeline's status again.
                .story("Refresh", SupervisorStories::renderMovingOn)
                .withPlay(play -> {
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Needs you")).toBeInTheDocument();
                    play.run("Stroom refreshes the tab", () -> supervisor.refresh());
                    play.waitFor(() -> play.expect(grid.queryByText("Needs you")).toBeNull());
                    play.expect(grid.getAllByText("Translating")).toHaveLength(2);
                    expectNoProblems(play);
                })
                // With auto refresh off, a refresh reads nothing. When it is switched on again, the pipelines
                // are read straight away.
                .story("AutoRefreshOff", SupervisorStories::renderMovingOn)
                .withPlay(play -> {
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Needs you")).toBeInTheDocument();
                    play.click(play.getByRole("button", TextMatch.startingWith("Auto Refresh")));
                    play.run("Stroom refreshes the tab", () -> supervisor.refresh());
                    play.sleep(200);
                    play.expect(grid.getByText("Needs you")).toBeInTheDocument();
                    play.click(play.getByRole("button", TextMatch.startingWith("Auto Refresh")));
                    play.waitFor(() -> play.expect(grid.queryByText("Needs you")).toBeNull());
                    expectNoProblems(play);
                });
    }

    private static void openFilter(final Play play) {
        play.click(play.getByRole("button", "Filter Pipelines"));
        play.expect(play.screen().findByText(FILTER, StroomDom.DIALOG_TITLE)).toBeInTheDocument();
    }

    private static void expectNoProblems(final Play play) {
        play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
        play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
    }

    private static Widget render(final StoryContext context) {
        return render(context, RestFixtures.builder()
                .route(RequestMatcher.post("/learningFlowSupervisor/v1/learners/find")
                                .withJsonBodyContaining("{\"showDeletedPipelines\": true}"),
                        RestReply.json(WITH_DELETED))
                .post("/learningFlowSupervisor/v1/learners/find", RestReply.json(LEARNERS)));
    }

    // Renders the screen for the refresh stories, replying first with the pipelines as first read, then with
    // the firewall pipeline's status changed.
    private static Widget renderMovingOn(final StoryContext context) {
        return render(context, RestFixtures.builder()
                .post("/learningFlowSupervisor/v1/learners/find", RestReply.json(LEARNERS),
                        RestReply.json(MOVED_ON)));
    }

    private static Widget render(final StoryContext context, final RestFixtures.Builder learners) {
        final ContentScreenGinjector injector = GWT.create(ContentScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, learners.build())
                .injector(injector)
                .realAlerts()
                .build();
        harness.fn(OPENED);
        harness.getEventBus().addHandler(OpenDocumentEvent.getType(), event ->
                harness.spy(OPENED, event.getDocRef().getUuid()));
        // Once Stroom has started, open the screen as the plugin does and refresh it as the content pane does.
        harness.afterStartUp(() -> {
            supervisor = injector.getSupervisorController();
            harness.show(supervisor).refresh();
        });
        return harness.asWidget();
    }

    private static String page(final String... learners) {
        return "{\"values\": [" + String.join(", ", learners) + "], \"pageResponse\": {\"offset\": 0, \"length\": "
               + learners.length + ", \"total\": " + learners.length + ", \"exact\": true}}";
    }
}
