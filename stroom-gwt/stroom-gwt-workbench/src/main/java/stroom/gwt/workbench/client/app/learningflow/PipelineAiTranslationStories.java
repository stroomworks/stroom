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

import stroom.gwt.workbench.client.app.editors.DocEditors;
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
import stroom.learningflow.client.component.GrantEditorController;
import stroom.learningflow.client.component.GuidanceEditorController;
import stroom.learningflow.client.component.PipelineAiTabController;
import stroom.learningflow.client.component.SupervisorPromptController;
import stroom.learningflow.client.component.TurnAnswerEditorController;

import com.google.gwt.user.client.ui.Widget;

/// The `App/Editors/PipelineAiTranslation` stories show a pipeline's AI Translation tab
/// ([PipelineAiTabController]) in Stroom's real pipeline editor, with fake REST replies ([AiPipelines]). The
/// tab shows the status of the feed learned by the pipeline's Learning Flow element, its learning run's
/// conversation and waiting streams, and the actions the pipeline's permissions allow. The tab's dialogs are
/// covered too: giving a parked run more time or tokens ([GrantEditorController]), answering a question
/// ([TurnAnswerEditorController]), the prompt that asks why a feed should stop learning
/// ([SupervisorPromptController]), and the notes left for a feed ([GuidanceEditorController]).
public final class PipelineAiTranslationStories {

    private static final String PAUSED = "Paused: ran out of time at \"Write the parser\" (question 4).";
    private static final String TAB = "Learning Status";
    // The firewall's learner, as the Supervisor addresses it
    private static final String FIREWALL = "/learningFlowSupervisor/v1/pipelines/p-1/elements/learningFlow";
    private static final String TRANSLATING_WITH = "Translating with";
    private static final String HISTORY = "Learning history";
    // The firewall pipeline's learner serving its translation, with no streams waiting.
    private static final String TRANSLATING_NOTHING_WAITING = LearningFlowFixtures.FIREWALL_TRANSLATING
            .replace("\"waiting\": 2", "\"waiting\": 0");


    private PipelineAiTranslationStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/PipelineAiTranslation", PipelineAiTranslationStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The tab for the firewall's feed, whose run was paused because it ran out of time. The tab says
                // why it paused and what can be done about it.
                .story("Default", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(DocEditors.tab(play, TAB)).toBeInTheDocument());
                    play.expect(play.findByRole("heading", "Firewall pipeline")).toBeInTheDocument();
                    play.expect(play.findByText(PAUSED)).toBeInTheDocument();
                    // There is only one Learning Flow element, so the element chooser is hidden.
                    play.expect(play.queryByText("Smart Pipeline element")).not().toBeVisible();
                    // The reason it paused is shown as details and suggestions. "Give it more" already has its
                    // own button, so that suggestion is plain text rather than a link.
                    final Play reportDetails = play.within(play.getByRole("list", "Details"));
                    play.expect(reportDetails.getByText(
                            "Each run may take 5 minutes; this one had taken 5 minutes 2 seconds."))
                            .toBeInTheDocument();
                    final Play suggestions = play.within(play.getByRole("list", "Suggestions"));
                    play.expect(suggestions.getByText("Give it more time.")).toBeInTheDocument();
                    play.expect(suggestions.queryByRole("button", "Give it more time.")).toBeNull();
                    play.expect(suggestions.getByRole("button", "Raise \"Time for each run\" on the Setup tab."))
                            .toBeInTheDocument();
                    play.expect(play.getByText("Feed FIREWALL · Raw Events")).toBeInTheDocument();
                    play.expect(play.getByRole("button", StroomDom.button("Give it more…"))).toBeInTheDocument();
                    play.expect(play.getByRole("button", StroomDom.button("Finish with Unknowns")))
                            .toBeInTheDocument();
                    play.expect(play.getByRole("button", StroomDom.button("Answer it myself"))).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("Went back once"))).toBeInTheDocument();
                    // Nothing has been learned yet, so there is no learned translation to open.
                    play.expect(play.queryByText(TRANSLATING_WITH)).not().toBeVisible();
                    // Issues flagged by QA are no longer shown here
                    play.expect(play.queryByRole("list", "Issues flagged by QA")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // A feed being translated shows which learned translation it uses, and clicking its name opens
                // it.
                .story("LearnedTranslation", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.PAUSED,
                                "[" + LearningFlowFixtures.FIREWALL_TRANSLATING + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    play.expect(play.findByText(TRANSLATING_WITH)).toBeVisible();
                    final Query open = play.getByRole("button", "Firewall pipeline learned");
                    play.expect(open).toHaveAttribute("title",
                            "Open the learned translation Firewall pipeline learned");
                    play.click(open);
                    play.waitFor(() -> play.expect(play.spy(AiPipelines.OPENED)).toHaveBeenCalledWith("f-2"));
                    DocEditors.expectNoProblems(play);
                })
                // For a pipeline that is translating with no streams waiting, its status and the note to the AI
                // are visible, its learning history is folded until asked for, and there is no waiting streams
                // tab.
                .story("TranslatingFoldsHistory", context -> AiPipelines.render(context, false,
                        AiPipelines.ONE_ELEMENT,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.FIREWALL_PROMOTED, "[" + TRANSLATING_NOTHING_WAITING + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    play.expect(play.findByText(TRANSLATING_WITH)).toBeVisible();
                    play.expect(play.getByLabelText("Note to the AI")).toBeVisible();
                    final Query history = play.getByRole("button", HISTORY);
                    play.waitFor(() -> play.expect(history).toHaveAttribute("aria-expanded", "false"));
                    play.expect(play.getByText("Progress")).not().toBeVisible();
                    play.click(history);
                    play.waitFor(() -> play.expect(play.getByText("Progress")).toBeVisible());
                    play.expect(history).toHaveAttribute("aria-expanded", "true");
                    // The run finished, so the stepper's flag is reached and no step is current
                    play.expect(play.getByText("Finished")).toBeVisible();
                    final Play steps = play.within(play.getByRole("list", "Learning run steps"));
                    play.expect(steps.getByText(TextMatch.startingWith("Reached:"))).toBeInTheDocument();
                    play.expect(steps.querySelector("[aria-current='step']")).toBeNull();
                    play.expect(play.queryByText("Waiting streams", StroomDom.LINK_TAB_LABEL)).not().toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // A run that needs a person opens the learning history automatically.
                .story("PausedOpensHistory", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByRole("button", HISTORY))
                            .toHaveAttribute("aria-expanded", "true"));
                    play.expect(play.findByText(LearningFlowFixtures.TURN_4)).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // How far the run has got, as a stepper: each step's mark and words say where it stands, the step
                // the run is at is the list's current step, and where that is is said beside the heading
                .story("ProgressStepper", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByRole("button", HISTORY))
                            .toHaveAttribute("aria-expanded", "true"));
                    final Play steps = play.within(play.findByRole("list", "Learning run steps"));
                    play.expect(steps.getAllByRole("listitem")).toHaveLength(3);
                    play.expect(play.getByText("Step 2 of 2 · Write the parser")).toBeVisible();
                    play.expect(steps.querySelector("[aria-current='step']"))
                            .toHaveTextContent(TextMatch.startingWith("Write the parser"));
                    play.expect(steps.getByText("In progress · try 2")).toBeVisible();
                    play.expect(steps.getByText("2 tries")).toBeVisible();
                    // Done and not reached are said to a screen reader, which is shown neither marks nor colours
                    play.expect(steps.getByText(TextMatch.startingWith("Done:"))).toBeInTheDocument();
                    play.expect(steps.getByText(TextMatch.startingWith("Not reached:"))).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The last question is expanded to show what it asked. Expanding another shows its score and
                // answer.
                .story("Conversation", context -> render(context, false))
                .withPlay(play -> {
                    play.expect(play.findByText(LearningFlowFixtures.TURN_4)).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("Not answered yet"))).toBeInTheDocument();
                    final Query second = play.getByRole("button",
                            TextMatch.startingWith("Question 2: Write the parser"));
                    play.expect(second).toHaveAttribute("aria-expanded", "false");
                    play.click(second);
                    play.expect(second).toHaveAttribute("aria-expanded", "true");
                    play.expect(play.getByText("Reading the input scored 42%. It needs 90%.")).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.containing("<var id='field' />"))).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The streams waiting for the feed are listed on the second tab beneath the conversation.
                .story("WaitingStreams", context -> render(context, false))
                .withPlay(play -> {
                    play.findByText(PAUSED);
                    play.click(DocEditors.tab(play, "Waiting streams"));
                    play.expect(play.findByText("Coverage short")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // A paused run is given more time, and OK sends the grant.
                .story("GrantDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGrant(play);
                    final Query minutes = screen.getByLabelText("More minutes for each run");
                    play.clear(minutes);
                    play.type(minutes, "10");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/attempts/7/grant")
                                    .withJsonBodyContaining("{\"ms\": 600000}").toSpyMatcher()));
                    DocEditors.expectNoProblems(play);
                })
                .story("GrantDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGrant(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Give this session more to spend",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/attempts/7/grant").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // The question the run stopped at is answered by hand, and OK sends the answer.
                .story("AnswerDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openAnswer(play);
                    play.click(screen.querySelector(".ace_content"));
                    play.keyboard("x");
                    play.waitFor(() -> play.expect(screen.querySelector(".ace_content"))
                            .toHaveTextContent(TextMatch.containing("x")));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/attempts/7/turns/4").toSpyMatcher()));
                    DocEditors.expectNoProblems(play);
                })
                .story("AnswerDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openAnswer(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(TextMatch.startingWith("Answer question 4"),
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/attempts/7/turns/4").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // Stop learning, chosen from the More menu, prompts for a reason, and OK sends it.
                .story("StopLearningPromptOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStopLearning(play);
                    play.type(screen.getByLabelText("Answer"), "Not a feed we translate.");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post(FIREWALL + "/giveUp")
                                    .withJsonBodyContaining("{\"reason\": \"Not a feed we translate.\"}")
                                    .toSpyMatcher()));
                    DocEditors.expectNoProblems(play);
                })
                .story("StopLearningPromptCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStopLearning(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Stop learning this feed",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post(FIREWALL + "/giveUp").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // A note for the AI is sent from the box beneath the conversation.
                .story("NoteSent", context -> render(context, false))
                .withPlay(play -> {
                    play.findByText(PAUSED);
                    play.type(play.getByLabelText("Note to the AI"), "Ports are numbers.");
                    play.click(play.getByRole("button", StroomDom.button("Send note")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post(FIREWALL + "/guidance")
                                    .withJsonBodyContaining("{\"message\": \"Ports are numbers.\"}")
                                    .toSpyMatcher()));
                    // Once sent, the box is emptied for the next note.
                    play.waitFor(() -> play.expect(play.getByLabelText("Note to the AI")).toHaveValue(""));
                    DocEditors.expectNoProblems(play);
                })
                // The prompt the open question was sent as, which the server rebuilds, is shown in a dialog
                // with notes about it.
                .story("PromptDialog", context -> promptRender(context, """
                        {"prompt": "You write Stroom pipeline configurations.\\n\\nWrite the parser for:\\na,b,c",
                          "notes": ["The questions and answers before it went with it, as the turns above \
                        show them."]}"""))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.click(play.findByRole("button", TextMatch.startingWith("Show the prompt as it was sent")));
                    play.expect(screen.findByText(TextMatch.startingWith("The questions and answers before it")))
                            .toBeInTheDocument();
                    play.waitFor(() -> play.expect(screen.getByLabelText("The prompt as it was sent"))
                            .toHaveValue("You write Stroom pipeline configurations.\n\nWrite the parser for:\na,b,c"));
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    play.waitFor(() -> play.expect(screen.queryByText(TextMatch.startingWith(
                            "The questions and answers before it"))).toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // A user who may not view data is told why and is not shown the prompt.
                .story("PromptDialogRefused", context -> promptRender(context, """
                        {"refusal": "The prompt carries the stream's own text, so it is shown only to \
                        someone who may view data."}"""))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.click(play.findByRole("button", TextMatch.startingWith("Show the prompt as it was sent")));
                    play.expect(screen.findByText(TextMatch.startingWith("The prompt carries the stream's own text")))
                            .toBeInTheDocument();
                    play.expect(screen.getByLabelText("The prompt as it was sent")).not().toBeVisible();
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    DocEditors.expectNoProblems(play);
                })
                // The notes in use are shown in the guidance dialog, which is closed with Close.
                .story("NotesDialog", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.click(play.findByRole("button", "View notes, 1 note in use"));
                    play.expect(screen.findByText("Timestamps are in UTC.")).toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    play.waitFor(() -> play.expect(screen.queryByText("Timestamps are in UTC.")).toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // A run whose model didn't reply in time is shown as failed rather than still learning, with
                // suggestions for what to do.
                .story("FailedRun", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.FAILED,
                                "[" + LearningFlowFixtures.FIREWALL_LEARNER + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    play.expect(play.findByText("Failed: the model \"GPT-4o\" didn't reply in time."))
                            .toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("Learning tries again from the feed's next "
                                                                      + "stream."))).toBeInTheDocument();
                    final Play suggestions = play.within(play.getByRole("list", "Suggestions"));
                    play.expect(suggestions.getByRole("button",
                            "Raise the timeout in the model document's HTTP Client Configuration."))
                            .toBeInTheDocument();
                    play.expect(suggestions.getByRole("button", "Or choose a faster model on the Setup tab."))
                            .toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // A new translation waiting for a person to approve it shows how it was judged, gates first,
                // beside the Approve and Reject buttons.
                .story("AwaitingReviewEvidence", context -> runRender(context, LearningFlowFixtures.AWAITING_REVIEW))
                .withPlay(play -> {
                    play.expect(play.findByText("A new translation is ready for you to check")).toBeInTheDocument();
                    final Play judged = play.within(play.getByRole("list", "How it was judged"));
                    play.expect(judged.getByText("Compiles: 100%, a gate it passed")).toBeInTheDocument();
                    play.expect(judged.getByText(
                            "Event times are plausible: 50%, advice it fell short of, which asks for 100%"))
                            .toBeInTheDocument();
                    play.expect(play.getByRole("button", StroomDom.button("Approve and use it"))).toBeInTheDocument();
                    play.expect(play.queryByText(TextMatch.startingWith("Approved by"))).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // A learner held back by its Learning Flow's spending limit says which limit it is and that it
                // clears by itself.
                .story("SpendLimit", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.APPROVED,
                                "[" + LearningFlowFixtures.FIREWALL_SPEND_PAUSED + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    play.expect(play.findByText("Not learning until a spend ceiling clears")).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("This Learning Flow spent 1500 token(s)")))
                            .toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The tab shows the time zone the pipeline reads timestamps in, and where that setting comes
                // from.
                .story("TimeZone", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.PAUSED,
                                "[" + LearningFlowFixtures.FIREWALL_ZONED + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    play.expect(play.findByText("Timestamps with no offset are read in Europe/London, this "
                                                + "pipeline's time zone, unless a stream's TimeZone header says "
                                                + "otherwise.")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // Once approved, who approved it and when is shown beside how it was judged.
                .story("ApprovedBy", context -> runRender(context, LearningFlowFixtures.APPROVED))
                .withPlay(play -> {
                    play.expect(play.findByText(TextMatch.startingWith("Approved by jo, "))).toBeInTheDocument();
                    play.expect(play.getByRole("list", "How it was judged")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // When the pipeline is read only, the feed's status is shown, but nothing that would change its
                // learning is offered, since the server would refuse it.
                .story("ReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    play.expect(play.findByText(PAUSED)).toBeInTheDocument();
                    play.expect(play.queryByRole("button", StroomDom.button("Give it more…"))).toBeNull();
                    play.expect(play.queryByRole("button", StroomDom.button("Answer it myself"))).toBeNull();
                    play.expect(play.queryByRole("button", StroomDom.button("More…"))).toBeNull();
                    play.expect(play.queryByLabelText("Note to the AI")).not().toBeVisible();
                    final Play suggestions = play.within(play.getByRole("list", "Suggestions"));
                    play.expect(suggestions.getByText("Raise \"Time for each run\" on the Setup tab."))
                            .toBeInTheDocument();
                    play.expect(suggestions.queryByRole("button", "Raise \"Time for each run\" on the Setup tab."))
                            .toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // For a pipeline with two Learning Flow elements, the one to show is chosen at the top, and one
                // that hasn't learned anything says so.
                .story("TwoElements", context -> AiPipelines.render(context, false, AiPipelines.TWO_ELEMENTS,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.PAUSED,
                                "[" + LearningFlowFixtures.FIREWALL_LEARNER + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.expect(play.findByText(PAUSED)).toBeInTheDocument();
                    play.expect(play.getByText("Smart Pipeline element")).toBeVisible();
                    play.click(play.querySelector("#learningFlowPipelineElement " + StroomDom.SELECTION_BOX));
                    play.click(screen.findByText("Learn the audit feed (Firewall logs)"));
                    play.expect(play.findByText(TextMatch.startingWith("This element hasn't learned anything yet.")))
                            .toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // A pipeline without a Learning Flow element has no AI Translation tab.
                .story("NoAiElement", context -> AiPipelines.render(context, false, AiPipelines.NO_ELEMENT,
                        LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.PAUSED,
                                "[]"),
                        null))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(DocEditors.tab(play, "Structure")).toBeInTheDocument());
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/pipeline/v1/fetchPipelineLayers").toSpyMatcher()));
                    play.expect(play.queryByText(TAB, StroomDom.LINK_TAB_LABEL)).not().toBeVisible();
                    DocEditors.expectNoProblems(play);
                });
    }

    private static void openGrant(final Play play) {
        play.click(play.findByRole("button", StroomDom.button("Give it more…")));
        play.expect(play.screen().findByText("Give this session more to spend", StroomDom.DIALOG_TITLE))
                .toBeInTheDocument();
    }

    private static void openAnswer(final Play play) {
        play.click(play.findByRole("button", StroomDom.button("Answer it myself")));
        play.expect(play.screen().findByText(TextMatch.startingWith("Answer question 4:"), StroomDom.DIALOG_TITLE))
                .toBeInTheDocument();
    }

    private static void openStopLearning(final Play play) {
        final Play screen = play.screen();
        play.click(play.findByRole("button", StroomDom.button("More…")));
        play.click(screen.findByText("Stop learning…", StroomDom.MENU_ITEM_TEXT));
        play.expect(screen.findByText("Stop learning this feed", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
    }

    // Renders the pipeline with the firewall's paused run, replying with `rebuilt` when the open question's
    // prompt is requested.
    private static Widget promptRender(final StoryContext context, final String rebuilt) {
        return AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                LearningFlowFixtures.pipelineRoutes(RestFixtures.builder()
                                .get("/learningFlowSupervisor/v1/attempts/7/turns/4/prompt", RestReply.json(rebuilt)),
                        LearningFlowFixtures.DOC, LearningFlowFixtures.PAUSED,
                        "[" + LearningFlowFixtures.FIREWALL_LEARNER + "]"),
                AiPipelines.ELEMENT);
    }

    // Renders the pipeline with the given firewall run.
    private static Widget runRender(final StoryContext context, final String run) {
        return AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC, run,
                        "[" + LearningFlowFixtures.FIREWALL_LEARNER + "]"),
                AiPipelines.ELEMENT);
    }

    // Renders the firewall pipeline at its AI Translation tab, with its run paused because it ran out of time.
    private static Widget render(final StoryContext context, final boolean readOnly) {
        return AiPipelines.render(context, readOnly, AiPipelines.ONE_ELEMENT,
                LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), LearningFlowFixtures.DOC,
                                LearningFlowFixtures.PAUSED,
                        "[" + LearningFlowFixtures.FIREWALL_LEARNER + "]"),
                AiPipelines.ELEMENT);
    }
}
