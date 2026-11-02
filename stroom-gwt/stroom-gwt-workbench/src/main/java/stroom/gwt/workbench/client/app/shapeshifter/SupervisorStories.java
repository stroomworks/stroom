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

package stroom.gwt.workbench.client.app.shapeshifter;

import stroom.gwt.workbench.client.app.gin.content.ContentScreenGinjector;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.client.component.GrantEditorController;
import stroom.shapeshifter.client.component.GuidanceEditorController;
import stroom.shapeshifter.client.component.LearnerFilterEditorController;
import stroom.shapeshifter.client.component.SupervisorController;
import stroom.shapeshifter.client.component.SupervisorPromptController;
import stroom.shapeshifter.client.component.TurnAnswerEditorController;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Main/ShapeshifterAiSupervisor` stories, showing Stroom's real [SupervisorController] (the
/// Shapeshifter AI Supervisor, a monitoring tab) with fake REST replies: the learning pipelines, the
/// selected one's sessions and waiting streams, and the selected session's turns beside its path over
/// the flow. Opened as `ShapeshifterAiSupervisorPlugin` opens it, and refreshed as the content pane
/// does.
///
/// Its dialogs: the learners' filter ([LearnerFilterEditorController]), what has been said to a learner
/// ([GuidanceEditorController]) and the prompt that says something more or asks why a draft is
/// rejected ([SupervisorPromptController]), a parked session's grant ([GrantEditorController]) and a
/// turn's answer ([TurnAnswerEditorController]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `POST /shapeshifterAiSupervisor/v1/learners` | the learning pipelines |
/// | `POST /shapeshifterAiSupervisor/v1/find` | a learner's sessions |
/// | `GET /shapeshifterAiSupervisor/v1/{id}` | a session's turns |
/// | `POST /shapeshifterAiSupervisor/v1/learners/{docUuid}/waiting` | a learner's waiting streams |
/// | `GET` and `POST /shapeshifterAiSupervisor/v1/guidance/{docUuid}` | what has been said, and saying more |
/// | `POST /shapeshifterAiSupervisor/v1/{id}/grant`, `reject`, `turns/{number}` | acting on a session |
/// | `GET /shapeshifterAi/v1/{uuid}` | the document, for the session's path over its flow |
public final class SupervisorStories {

    private static final String DOC_JSON = "{\"type\": \"ShapeshifterAi\", \"uuid\": \"ss-1\", "
                                           + "\"name\": \"Firewall logs\"}";

    // SupervisorResource.learners(): one learner awaiting a person, and one whose pipeline is deleted.
    // A learner's state and an attempt's status are sent as their display values
    private static final String LEARNERS = """
            {"values": [
              {"learner": {"pipelineUuid": "p-1", "elementId": "shapeshifterAi"}, "doc": DOC,
                "pipeline": {"type": "Pipeline", "uuid": "p-1", "name": "Firewall pipeline"},
                "state": "Waiting for a person", "waiting": 2, "records": 0, "guidance": 1, "raised": false,
                "belowThreshold": false, "provisional": false, "pipelineDeleted": false,
                "lastSessionMs": 1761800600000, "oldestWaitMs": 1761700000000},
              {"learner": {"pipelineUuid": "p-2", "elementId": "shapeshifterAi"}, "doc": DOC,
                "pipeline": {"type": "Pipeline", "uuid": "p-2", "name": "Old proxy pipeline"},
                "state": "Given up", "waiting": 0, "records": 0, "guidance": 0, "raised": false,
                "belowThreshold": false, "provisional": false, "pipelineDeleted": true}],
              "pageResponse": {"offset": 0, "length": 2, "total": 2, "exact": true}}""".replace("DOC", DOC_JSON);

    // A session: parked (or, on the deleted pipeline, abandoned) out of time, as the flow walks it: the
    // chain, a parser that fell short of the input and went back to the chain, the chain again, and the
    // parser asked again, which is where it stopped. The questions are the lines a turn records.
    private static final String TURN_2 = "Configure: dsParser TextConverter";
    // Turn 2's answer, a configuration of several lines, as JSON carries it
    private static final String PARSER = String.join("\\n",
            "<dataSplitter xmlns='data-splitter:3' version='3.0'>",
            "  <split delimiter='\\\\n'>",
            "    <group>",
            "      <split delimiter=','>",
            "        <var id='field' />",
            "      </split>",
            "    </group>",
            "  </split>",
            "</dataSplitter>");
    private static final String TURNS = """
            [{"number": 1, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
               "question": "Chain: choose from [dsParser, xmlFragmentParser, xslt]",
               "answer": "dsParser\\nxslt", "outcome": "PASSED", "answeredBy": "model"},
             {"number": 2, "stepId": "parse", "kind": "CONFIGURE", "candidate": 1,
               "question": "TURN_2",
               "answer": "PARSER",
               "outcome": "COVERAGE_SHORT", "answeredBy": "model",
               "score": {"scorer": "INPUT_COVERAGE", "score": 0.42, "threshold": 0.9}},
             {"number": 3, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
               "question": "Chain: choose from [dsParser, xmlFragmentParser, xslt], after 1 shortfall(s)",
               "answer": "dsParser\\nxslt", "outcome": "PASSED", "answeredBy": "model"},
             {"number": 4, "stepId": "parse", "kind": "CONFIGURE", "candidate": 1,
               "question": "Configure: dsParser TextConverter, after 1 shortfall(s)"}]"""
            .replace("TURN_2", TURN_2)
            .replace("PARSER", PARSER);
    private static final String ATTEMPT = """
            {"id": ID, "learner": {"pipelineUuid": "PIPE", "elementId": "shapeshifterAi"}, "doc": DOC,
              "status": "STATUS", "feed": "FIREWALL", "type": "Raw Events", "node": "node1a",
              "createTimeMs": 1761800000000, "updateTimeMs": 1761800600000, "score": 0.42,
              "tokensSpent": 12000, "grantedTokens": 0, "grantedMs": 0, "exhaustNext": false,
              "pipelineDeleted": DELETED, "promotionMode": "AUTOMATIC", "inputId": 101,
              "decision": "Out of time", "turns": TURNS}""".replace("DOC", DOC_JSON).replace("TURNS", TURNS);
    private static final String PARKED = ATTEMPT.replace("ID", "7").replace("PIPE", "p-1")
            .replace("STATUS", "Parked").replace("DELETED", "false");
    private static final String ABANDONED = ATTEMPT.replace("ID", "8").replace("PIPE", "p-2")
            .replace("STATUS", "Abandoned").replace("DELETED", "true");

    private static final String GUIDANCE = """
            [{"id": 1, "message": "Timestamps are in UTC.", "author": "admin", "timeMs": 1761700000000}]""";
    private static final String MORE_GUIDANCE = """
            [{"id": 1, "message": "Timestamps are in UTC.", "author": "admin", "timeMs": 1761700000000},
             {"id": 2, "message": "Ports are numbers.", "author": "admin", "timeMs": 1761800000000}]""";

    private static final String WAITING = """
            {"values": [{"inputId": 101, "doc": DOC, "feed": "FIREWALL", "type": "Raw Events",
                "firstDeferredMs": 1761700000000, "tries": 1, "failingRecords": 12, "raised": false,
                "reason": "Coverage short"}],
              "pageResponse": {"offset": 0, "length": 1, "total": 1, "exact": true}}""".replace("DOC", DOC_JSON);

    private SupervisorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Main/ShapeshifterAiSupervisor", SupervisorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The three levels: a pipeline chosen shows its sessions, and a session its turns and path
                .story("Default", SupervisorStories::render)
                .withPlay(play -> {
                    openSession(play);
                    play.waitFor(() -> play.expect(play.getByText(TURN_2)).toBeInTheDocument());
                    play.expect(play.getByRole("option", TextMatch.startingWith("Step 2: Configure parser")))
                            .toBeInTheDocument();
                    play.expect(play.getByRole("button", TextMatch.startingWith("Give this parked attempt")))
                            .not().toHaveAttribute("aria-disabled");
                    expectNoProblems(play);
                })
                // A deleted pipeline is read, not acted on: no actions, and a turn opens to be read only
                .story("DeletedPipelineReadOnly", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.click(play.findByText(TextMatch.startingWith("Old proxy pipeline")));
                    play.expect(play.getByRole("button", TextMatch.startingWith("Give this learner up")))
                            .toHaveAttribute("aria-disabled", "true");
                    play.click(play.findByText("Abandoned (pipeline deleted)"));
                    play.dblClick(play.findByText(TURN_2));
                    play.expect(screen.findByText(TextMatch.startingWith("Turn 2:"), StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    play.waitFor(() -> play.expect(screen.queryByText(TextMatch.startingWith("Turn 2:"),
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    expectNoProblems(play);
                })
                // The whole conversation, on the second tab beside the chart: every turn in full, the answer
                // as it was written, and the question the session stopped at
                .story("Conversation", SupervisorStories::render)
                .withPlay(play -> {
                    final Play conversation = openConversation(play);
                    play.expect(conversation.getByText("Turn 2 · parse · Configure")).toBeInTheDocument();
                    play.expect(conversation.getByText(TextMatch.containing("<var id='field' />")))
                            .toBeInTheDocument();
                    play.expect(conversation.getByText("Input coverage 0.42 < 0.9")).toBeInTheDocument();
                    play.expect(conversation.getByText(TextMatch.startingWith("Not yet:"))).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // A step chosen on the chart marks its first turn in the conversation, and a turn chosen in the
                // grid marks its own there
                .story("ConversationFollowsChoosing", SupervisorStories::render)
                .withPlay(play -> {
                    final Play conversation = openConversation(play);
                    play.click(play.getByRole("option", TextMatch.startingWith("Step 2: Configure parser")));
                    play.expect(conversation.getByText("Turn 2 · parse · Configure")
                            .closest(".turn-transcript__turn")).toHaveClass("turn-transcript__turn--selected");
                    play.click(play.getByText("Turns", StroomDom.LINK_TAB_LABEL));
                    // The conversation, though hidden, is still on the page, so the grid is looked in alone
                    play.click(play.within(play.getByRole("grid", "Turns")).findByText(TextMatch.startingWith(
                            "Chain: choose from [dsParser, xmlFragmentParser, xslt], after")));
                    play.click(play.getByText("Conversation", StroomDom.LINK_TAB_LABEL));
                    play.waitFor(() -> play.expect(conversation.getByText("Turn 3 · chain · Chain")
                            .closest(".turn-transcript__turn")).toHaveClass("turn-transcript__turn--selected"));
                    play.expect(conversation.getByText("Turn 2 · parse · Configure")
                            .closest(".turn-transcript__turn")).not().toHaveClass("turn-transcript__turn--selected");
                    expectNoProblems(play);
                })
                // The waiting streams, on the second tab beneath the pipelines
                .story("WaitingStreams", SupervisorStories::render)
                .withPlay(play -> {
                    play.click(play.findByText("Firewall pipeline"));
                    play.click(play.getByText("Waiting streams", StroomDom.LINK_TAB_LABEL));
                    play.expect(play.findByText("Coverage short")).toBeInTheDocument();
                    expectNoProblems(play);
                })
                .story("LearnerFilterDialogOk", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openFilter(play);
                    play.click(screen.getByLabelText("Show deleted pipelines"));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/learners")
                                    .withJsonBodyContaining("{\"showDeletedPipelines\": true}").toSpyMatcher()));
                    expectNoProblems(play);
                })
                .story("LearnerFilterDialogCancel", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openFilter(play);
                    play.click(screen.getByLabelText("Show deleted pipelines"));
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Which learning pipelines to show",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/learners")
                                    .withJsonBodyContaining("{\"showDeletedPipelines\": true}").toSpyMatcher());
                    expectNoProblems(play);
                })
                // What has been said to a learner, and saying more through the prompt
                .story("GuidancePromptOk", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGuidance(play);
                    play.click(screen.getByRole("button", "Tell this learner something else"));
                    play.expect(screen.findByText("Tell this learner something", StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    play.type(screen.getByLabelText("Answer"), "Ports are numbers.");
                    final Play prompt = play.within(screen.getByText("Tell this learner something",
                            StroomDom.DIALOG_TITLE).closest(StroomDom.DIALOG));
                    play.click(prompt.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.getByText("Ports are numbers.")).toBeInTheDocument());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/guidance/ss-1")
                                    .withJsonBodyContaining("{\"message\": \"Ports are numbers.\"}")
                                    .toSpyMatcher());
                    closeGuidance(play);
                    expectNoProblems(play);
                })
                // The prompt, cancelled: nothing is said, and the guidance dialog closes with Close
                .story("GuidancePromptCancel", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGuidance(play);
                    play.click(screen.getByRole("button", "Tell this learner something else"));
                    play.expect(screen.findByText("Tell this learner something", StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    final Play prompt = play.within(screen.getByText("Tell this learner something",
                            StroomDom.DIALOG_TITLE).closest(StroomDom.DIALOG));
                    play.click(prompt.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Tell this learner something",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/guidance/ss-1").toSpyMatcher());
                    closeGuidance(play);
                    expectNoProblems(play);
                })
                // More to spend for a parked session
                .story("GrantDialogOk", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGrant(play);
                    final Query minutes = screen.getByLabelText("More minutes for each run");
                    play.clear(minutes);
                    play.type(minutes, "10");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/grant")
                                    .withJsonBodyContaining("{\"ms\": 600000}").toSpyMatcher()));
                    expectNoProblems(play);
                })
                .story("GrantDialogCancel", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGrant(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Give this session more to spend",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/grant").toSpyMatcher());
                    expectNoProblems(play);
                })
                // A turn answered by hand: OK sends it, and the session runs again from there
                .story("TurnAnswerDialogOk", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openAnswer(play);
                    play.click(screen.querySelector(".ace_content"));
                    play.keyboard("x");
                    play.waitFor(() -> play.expect(screen.querySelector(".ace_content"))
                            .toHaveTextContent(TextMatch.containing("x")));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/turns/2").toSpyMatcher()));
                    expectNoProblems(play);
                })
                .story("TurnAnswerDialogCancel", SupervisorStories::render)
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openAnswer(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(TextMatch.startingWith("Edit turn 2:"),
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/turns/2").toSpyMatcher());
                    expectNoProblems(play);
                });
    }

    // Chooses the first pipeline and its parked session
    private static void openSession(final Play play) {
        play.click(play.findByText("Firewall pipeline"));
        play.click(play.findByText("Parked"));
    }

    // Chooses the parked session and the tab of its whole conversation, and gives the conversation to look in
    private static Play openConversation(final Play play) {
        openSession(play);
        play.waitFor(() -> play.expect(play.getByText(TURN_2)).toBeInTheDocument());
        play.click(play.getByText("Conversation", StroomDom.LINK_TAB_LABEL));
        return play.within(play.findByRole("region", "Conversation"));
    }

    private static void openFilter(final Play play) {
        play.click(play.findByRole("button", "Narrow which learning pipelines are shown"));
        play.expect(play.screen().findByText("Which learning pipelines to show", StroomDom.DIALOG_TITLE))
                .toBeInTheDocument();
    }

    private static void openGuidance(final Play play) {
        play.click(play.findByText("Firewall pipeline"));
        play.click(play.getByRole("button", TextMatch.startingWith("What has been said to this learner")));
        play.expect(play.screen().findByText("Timestamps are in UTC.")).toBeInTheDocument();
    }

    private static void closeGuidance(final Play play) {
        final Play screen = play.screen();
        play.click(screen.getByRole("button", StroomDom.button("Close")));
        play.waitFor(() -> play.expect(screen.queryByText("What has been said to this learner",
                StroomDom.DIALOG_TITLE)).toBeNull());
    }

    private static void openGrant(final Play play) {
        openSession(play);
        play.click(play.getByRole("button", TextMatch.startingWith("Give this parked attempt")));
        play.expect(play.screen().findByText("Give this session more to spend", StroomDom.DIALOG_TITLE))
                .toBeInTheDocument();
    }

    private static void openAnswer(final Play play) {
        openSession(play);
        play.click(play.findByText(TURN_2));
        play.click(play.getByRole("button", TextMatch.startingWith("Answer this turn instead")));
        play.expect(play.screen().findByText(TextMatch.startingWith("Edit turn 2:"), StroomDom.DIALOG_TITLE))
                .toBeInTheDocument();
    }

    private static void expectNoProblems(final Play play) {
        play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
        play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
    }

    private static Widget render(final StoryContext context) {
        final RestFixtures fixtures = RestFixtures.builder()
                .post("/shapeshifterAiSupervisor/v1/learners", RestReply.json(LEARNERS))
                .route(RequestMatcher.post("/shapeshifterAiSupervisor/v1/find")
                                .withJsonBodyContaining("{\"learner\": {\"pipelineUuid\": \"p-2\"}}"),
                        RestReply.json(page(ABANDONED)))
                .post("/shapeshifterAiSupervisor/v1/find", RestReply.json(page(PARKED)))
                .get("/shapeshifterAiSupervisor/v1/7", RestReply.json(PARKED))
                .get("/shapeshifterAiSupervisor/v1/8", RestReply.json(ABANDONED))
                .post("/shapeshifterAiSupervisor/v1/7/grant", RestReply.json(PARKED))
                .post("/shapeshifterAiSupervisor/v1/7/turns/2", RestReply.json(PARKED))
                .post("/shapeshifterAiSupervisor/v1/learners/ss-1/waiting", RestReply.json(WAITING))
                .get("/shapeshifterAiSupervisor/v1/guidance/ss-1", RestReply.json(GUIDANCE))
                .post("/shapeshifterAiSupervisor/v1/guidance/ss-1", RestReply.json(MORE_GUIDANCE))
                .get("/shapeshifterAi/v1/ss-1", RestReply.json(ShapeshifterAiFixtures.DOC))
                .post("/explorer/v2/decorate", request -> RestReply.json(request.getBody()))
                .build();
        final ContentScreenGinjector injector = GWT.create(ContentScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, fixtures)
                .injector(injector)
                .realAlerts()
                .build();
        // Opened once Stroom has started, as the plugin opens it, and refreshed as the content pane does
        harness.afterStartUp(() -> harness.show(injector.getSupervisorController()).refresh());
        return harness.asWidget();
    }

    private static String page(final String attempt) {
        return "{\"values\": [" + attempt + "], "
               + "\"pageResponse\": {\"offset\": 0, \"length\": 1, \"total\": 1, \"exact\": true}}";
    }
}
