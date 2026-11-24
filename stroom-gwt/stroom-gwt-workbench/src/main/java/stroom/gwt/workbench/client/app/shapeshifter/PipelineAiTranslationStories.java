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
import stroom.shapeshifter.client.component.GrantEditorController;
import stroom.shapeshifter.client.component.GuidanceEditorController;
import stroom.shapeshifter.client.component.PipelineAiTabController;
import stroom.shapeshifter.client.component.SupervisorPromptController;
import stroom.shapeshifter.client.component.TurnAnswerEditorController;

import com.google.gwt.user.client.ui.Widget;

/// The `App/Editors/PipelineAiTranslation` stories: a pipeline's AI Translation tab
/// ([PipelineAiTabController]) in Stroom's real pipeline editor, with fake REST replies ([AiPipelines]): where
/// the pipeline's AI element's feed stands, its learning run's conversation and waiting streams, and what can be
/// done about it, by the pipeline's permissions. Its dialogs: giving a paused run more
/// ([GrantEditorController]), answering a question ([TurnAnswerEditorController]), the prompt that asks why a
/// feed stops learning ([SupervisorPromptController]) and the notes left for a feed
/// ([GuidanceEditorController]).
public final class PipelineAiTranslationStories {

    private static final String PAUSED = "Paused: ran out of time at \"Write the parser\" (question 4).";
    private static final String TAB = "Learning Status";
    // The firewall's learner, as the Supervisor addresses it
    private static final String FIREWALL = "/smartPipelinesSupervisor/v1/pipelines/p-1/elements/shapeshifterAi";
    private static final String TRANSLATING_WITH = "Translating with";
    private static final String HISTORY = "Learning history";
    // The firewall's pipeline just translating, with nothing waiting
    private static final String TRANSLATING_NOTHING_WAITING = ShapeshifterAiFixtures.FIREWALL_TRANSLATING
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
                // The tab, opened from the document's Feeds tab: the firewall's feed paused out of time, why,
                // and what to do about it
                .story("Default", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(DocEditors.tab(play, TAB)).toBeInTheDocument());
                    play.expect(play.findByRole("heading", "Firewall pipeline")).toBeInTheDocument();
                    play.expect(play.findByText(PAUSED)).toBeInTheDocument();
                    // One AI element, so there is no choosing between them
                    play.expect(play.queryByText("AI Translator element")).not().toBeVisible();
                    // Why it paused, in parts; "Give it more" is a button already, so it is said, not linked
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
                    // Nothing learned yet, so no learned translation to open
                    play.expect(play.queryByText(TRANSLATING_WITH)).not().toBeVisible();
                    // Issues flagged by QA are no longer shown here
                    play.expect(play.queryByRole("list", "Issues flagged by QA")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // A feed translating says which learned translation it runs; its name opens it
                .story("LearnedTranslation", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        ShapeshifterAiFixtures.pipelineRoutes(RestFixtures.builder(), ShapeshifterAiFixtures.DOC,
                                ShapeshifterAiFixtures.PAUSED,
                                "[" + ShapeshifterAiFixtures.FIREWALL_TRANSLATING + "]"),
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
                // A pipeline just translating, with nothing waiting: where it stands and the note to the AI are in
                // sight, and its learning history is folded until asked for, with no waiting streams' tab
                .story("TranslatingFoldsHistory", context -> AiPipelines.render(context, false,
                        AiPipelines.ONE_ELEMENT,
                        ShapeshifterAiFixtures.pipelineRoutes(RestFixtures.builder(), ShapeshifterAiFixtures.DOC,
                                ShapeshifterAiFixtures.FIREWALL_PROMOTED, "[" + TRANSLATING_NOTHING_WAITING + "]"),
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
                // A run that needs someone opens the learning history of its own accord
                .story("PausedOpensHistory", context -> render(context, false))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.getByRole("button", HISTORY))
                            .toHaveAttribute("aria-expanded", "true"));
                    play.expect(play.findByText(ShapeshifterAiFixtures.TURN_4)).toBeVisible();
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
                // The last question is open, with what it asked; another opens to its score and answer
                .story("Conversation", context -> render(context, false))
                .withPlay(play -> {
                    play.expect(play.findByText(ShapeshifterAiFixtures.TURN_4)).toBeInTheDocument();
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
                // The streams waiting for the feed, on the second tab beneath it
                .story("WaitingStreams", context -> render(context, false))
                .withPlay(play -> {
                    play.findByText(PAUSED);
                    play.click(DocEditors.tab(play, "Waiting streams"));
                    play.expect(play.findByText("Coverage short")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // A paused run given more time: OK sends it
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
                            RequestMatcher.post("/smartPipelinesSupervisor/v1/attempts/7/grant")
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
                            RequestMatcher.post("/smartPipelinesSupervisor/v1/attempts/7/grant").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // The question the run stopped at, answered by hand: OK sends it
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
                            RequestMatcher.post("/smartPipelinesSupervisor/v1/attempts/7/turns/4").toSpyMatcher()));
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
                            RequestMatcher.post("/smartPipelinesSupervisor/v1/attempts/7/turns/4").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // Stop learning, from More: the prompt asks why, and OK sends it
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
                // A note left for the AI, sent from beneath the conversation
                .story("NoteSent", context -> render(context, false))
                .withPlay(play -> {
                    play.findByText(PAUSED);
                    play.type(play.getByLabelText("Note to the AI"), "Ports are numbers.");
                    play.click(play.getByRole("button", StroomDom.button("Send note")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post(FIREWALL + "/guidance")
                                    .withJsonBodyContaining("{\"message\": \"Ports are numbers.\"}")
                                    .toSpyMatcher()));
                    // Sent, the box is emptied for the next
                    play.waitFor(() -> play.expect(play.getByLabelText("Note to the AI")).toHaveValue(""));
                    DocEditors.expectNoProblems(play);
                })
                // The notes in use, shown in the guidance dialog, closed with Close
                .story("NotesDialog", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.click(play.findByRole("button", "View notes, 1 note in use"));
                    play.expect(screen.findByText("Timestamps are in UTC.")).toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    play.waitFor(() -> play.expect(screen.queryByText("Timestamps are in UTC.")).toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // A run whose model didn't reply in time: failed, not still learning, with what to do about it
                .story("FailedRun", context -> AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                        ShapeshifterAiFixtures.pipelineRoutes(RestFixtures.builder(), ShapeshifterAiFixtures.DOC,
                                ShapeshifterAiFixtures.FAILED,
                                "[" + ShapeshifterAiFixtures.FIREWALL_LEARNER + "]"),
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
                // Read only, the pipeline viewed and not edited: where the feed stands, and nothing offered that
                // changes its learning, which the server would refuse
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
                // A pipeline with two AI elements: which to show is chosen at the top, and one that hasn't
                // learned anything says so
                .story("TwoElements", context -> AiPipelines.render(context, false, AiPipelines.TWO_ELEMENTS,
                        ShapeshifterAiFixtures.pipelineRoutes(RestFixtures.builder(), ShapeshifterAiFixtures.DOC,
                                ShapeshifterAiFixtures.PAUSED,
                                "[" + ShapeshifterAiFixtures.FIREWALL_LEARNER + "]"),
                        AiPipelines.ELEMENT))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.expect(play.findByText(PAUSED)).toBeInTheDocument();
                    play.expect(play.getByText("AI Translator element")).toBeVisible();
                    play.click(play.querySelector("#shapeshifterAiPipelineElement " + StroomDom.SELECTION_BOX));
                    play.click(screen.findByText("Learn the audit feed (Firewall logs)"));
                    play.expect(play.findByText(TextMatch.startingWith("This element hasn't learned anything yet.")))
                            .toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // A pipeline without an AI element has no AI Translation tab
                .story("NoAiElement", context -> AiPipelines.render(context, false, AiPipelines.NO_ELEMENT,
                        ShapeshifterAiFixtures.pipelineRoutes(RestFixtures.builder(), ShapeshifterAiFixtures.DOC,
                                ShapeshifterAiFixtures.PAUSED,
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

    // The firewall's pipeline at its AI Translation tab, its feed paused out of time
    private static Widget render(final StoryContext context, final boolean readOnly) {
        return AiPipelines.render(context, readOnly, AiPipelines.ONE_ELEMENT,
                ShapeshifterAiFixtures.pipelineRoutes(RestFixtures.builder(), ShapeshifterAiFixtures.DOC,
                                ShapeshifterAiFixtures.PAUSED,
                        "[" + ShapeshifterAiFixtures.FIREWALL_LEARNER + "]"),
                AiPipelines.ELEMENT);
    }
}
