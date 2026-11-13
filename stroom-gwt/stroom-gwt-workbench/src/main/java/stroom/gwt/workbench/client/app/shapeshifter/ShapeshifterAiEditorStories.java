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
import stroom.gwt.workbench.client.app.editors.DocEditors.DocResource;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.client.component.BuiltInTextController;
import stroom.shapeshifter.client.component.GrantEditorController;
import stroom.shapeshifter.client.component.GuidanceEditorController;
import stroom.shapeshifter.client.component.ScorerSettingEditorController;
import stroom.shapeshifter.client.component.ShapeshifterAiEditor;
import stroom.shapeshifter.client.component.SupervisorPromptController;
import stroom.shapeshifter.client.component.TurnAnswerEditorController;
import stroom.shapeshifter.client.component.XPathAssertionEditorController;
import stroom.shapeshifter.shared.ShapeshifterAiResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Editors/ShapeshifterAiEditor` stories, showing Stroom's real [ShapeshifterAiEditor] (a
/// Shapeshifter AI document's tab: Feeds, Setup, Documentation and Permissions) with fake REST replies.
///
/// As `ShapeshifterAiPlugin` does, the story fetches the document and reads it into the editor
/// ([DocEditors#open]). Its dialogs: on the Feeds tab, giving a paused run more ([GrantEditorController]),
/// answering a question ([TurnAnswerEditorController]), the prompt that asks why a feed stops learning
/// ([SupervisorPromptController]) and the notes left for a feed ([GuidanceEditorController]); on the Setup
/// tab, a check's ([ScorerSettingEditorController]) and a business rule's assertion within it
/// ([XPathAssertionEditorController]), and loading a built-in text ([BuiltInTextController]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET` and `PUT /shapeshifterAi/v1/{uuid}` | the document, fetched and saved |
/// | `GET /shapeshifterAi/v1/templates` | the built-in texts the Setup tab's question texts start from |
/// | `POST /shapeshifterAiSupervisor/v1/learners` | the document's feeds |
/// | `POST /shapeshifterAiSupervisor/v1/find` | a feed's learning runs |
/// | `GET /shapeshifterAiSupervisor/v1/{id}` | a learning run, with its turns |
/// | `POST /shapeshifterAiSupervisor/v1/{id}/grant`, `turns/{number}` | acting on a run |
/// | `POST /shapeshifterAiSupervisor/v1/learners/{docUuid}/giveUp`, `waiting` | stopping a feed; its waiting streams |
/// | `GET` and `POST /shapeshifterAiSupervisor/v1/guidance/{docUuid}` | the notes left for a feed, and leaving one |
/// | `POST /explorer/v2/decorate` and `getFromDocRef` | the Setup tab's model picker |
/// | the Permissions tab's routes | the Permissions tab |
public final class ShapeshifterAiEditorStories {

    private static final String SAVE_PATH = "/shapeshifterAi/v1/ss-1";
    // A FormGroup gives its control the group's identity as its id
    private static final String WEIGHT = "#scorerWeight";
    private static final String PAUSED = "Paused: ran out of time at \"Write the parser\" (question 4).";
    private static final String MAP = "Setup route map";

    private ShapeshifterAiEditorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/ShapeshifterAiEditor", ShapeshifterAiEditorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The Feeds tab first: ready to learn, so with no banner, its feeds, and the first chosen,
                // paused out of time
                .story("Default", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    for (final String label : new String[]{"Feeds", "Setup", "Documentation",
                            "Permissions"}) {
                        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
                    }
                    final Play grid = play.within(play.findByRole("grid", "Feeds"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Proxy pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Needs you")).toBeInTheDocument();
                    play.expect(play.findByRole("heading", "Firewall pipeline")).toBeInTheDocument();
                    play.expect(play.findByText(PAUSED)).toBeInTheDocument();
                    play.expect(play.queryByText("This document won't learn anything yet")).toBeNull();
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
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The last question is open, with what it asked; another opens to its score and answer
                .story("Conversation", context -> render(context, false, ShapeshifterAiFixtures.DOC))
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
                // Another feed chosen: translating, with what it scores and Improve; its pipeline and its
                // translation are opened from More
                .story("ATranslatingFeed", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    play.click(play.within(play.findByRole("grid", "Feeds")).findByText("Proxy pipeline"));
                    play.expect(play.findByRole("heading", "Proxy pipeline")).toBeInTheDocument();
                    play.expect(play.findByText("It scores 98% lately, over 1,204 records. It scored 95% when it "
                                                + "was put to use.")).toBeInTheDocument();
                    play.expect(play.getByRole("button", StroomDom.button("Improve…"))).toBeInTheDocument();
                    play.expect(play.queryByRole("button", StroomDom.button("Open the translation"))).toBeNull();
                    play.expect(play.queryByRole("button", StroomDom.button("Open the pipeline"))).toBeNull();
                    final Play screen = play.screen();
                    play.click(play.getByRole("button", StroomDom.button("More…")));
                    play.expect(screen.findByText("Open the pipeline", StroomDom.MENU_ITEM_TEXT))
                            .toBeInTheDocument();
                    play.expect(screen.getByText("Open the translation", StroomDom.MENU_ITEM_TEXT))
                            .toBeInTheDocument();
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(screen.queryByText("Open the pipeline", StroomDom.MENU_ITEM_TEXT))
                            .toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // The streams waiting for a feed, on the second tab beneath it
                .story("WaitingStreams", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    play.findByText(PAUSED);
                    play.click(DocEditors.tab(play, "Waiting streams"));
                    play.expect(play.findByText("Coverage short")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // A paused run given more time: OK sends it
                .story("GrantDialogOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
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
                    DocEditors.expectNoProblems(play);
                })
                .story("GrantDialogCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openGrant(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Give this session more to spend",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/grant").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // The question the run stopped at, answered by hand: OK sends it
                .story("AnswerDialogOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openAnswer(play);
                    play.click(screen.querySelector(".ace_content"));
                    play.keyboard("x");
                    play.waitFor(() -> play.expect(screen.querySelector(".ace_content"))
                            .toHaveTextContent(TextMatch.containing("x")));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/turns/4").toSpyMatcher()));
                    DocEditors.expectNoProblems(play);
                })
                .story("AnswerDialogCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openAnswer(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(TextMatch.startingWith("Answer question 4"),
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/7/turns/4").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // Stop learning, from More: the prompt asks why, and OK sends it
                .story("StopLearningPromptOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStopLearning(play);
                    play.type(screen.getByLabelText("Answer"), "Not a feed we translate.");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/learners/ss-1/giveUp")
                                    .withJsonBodyContaining("{\"reason\": \"Not a feed we translate.\"}")
                                    .toSpyMatcher()));
                    DocEditors.expectNoProblems(play);
                })
                .story("StopLearningPromptCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStopLearning(play);
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Stop learning this feed",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/learners/ss-1/giveUp").toSpyMatcher());
                    DocEditors.expectNoProblems(play);
                })
                // A note left for the AI, sent from beneath the conversation
                .story("NoteSent", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    play.findByText(PAUSED);
                    play.type(play.getByLabelText("Note to the AI"), "Ports are numbers.");
                    play.click(play.getByRole("button", StroomDom.button("Send note")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/shapeshifterAiSupervisor/v1/guidance/ss-1")
                                    .withJsonBodyContaining("{\"message\": \"Ports are numbers.\"}")
                                    .toSpyMatcher()));
                    // Sent, the box is emptied for the next
                    play.waitFor(() -> play.expect(play.getByLabelText("Note to the AI")).toHaveValue(""));
                    DocEditors.expectNoProblems(play);
                })
                // The notes in use, shown in the guidance dialog, closed with Close
                .story("NotesDialog", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    play.click(play.findByRole("button", "View notes, 1 note in use"));
                    play.expect(screen.findByText("Timestamps are in UTC.")).toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Close")));
                    play.waitFor(() -> play.expect(screen.queryByText("Timestamps are in UTC.")).toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // A new document won't learn: the banner says why, and each fix opens its tab and makes the
                // change there, which the banner then counts, as not yet saved
                .story("NotReady", context -> render(context, false, ShapeshifterAiFixtures.NEW_DOC))
                .withPlay(play -> {
                    play.expect(play.findByText("This document won't learn anything yet")).toBeInTheDocument();
                    play.expect(play.getByText("There are no quality checks, so every answer would pass."))
                            .toBeInTheDocument();
                    play.click(play.getByRole("button", StroomDom.button("Switch on")));
                    play.expect(play.findByRole("radio", "On: learn new feeds automatically")).toBeChecked();
                    openTab(play, "Feeds");
                    play.click(play.findByRole("button", StroomDom.button("Use the Standard checks")));
                    play.expect(play.findByText("Events carry real content")).toBeInTheDocument();
                    openTab(play, "Feeds");
                    play.expect(play.findByText("Learning is on")).toBeInTheDocument();
                    play.expect(play.getByText("4 quality checks")).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("This is the document as you have it now")))
                            .toBeVisible();
                    save(play, "{\"learningMode\": \"AUTOMATIC\", \"scorers\": [{\"type\": \"COMPILE\"}, "
                               + "{\"type\": \"YIELD\"}, {\"type\": \"SCHEMA_CONFORMANCE\"}, "
                               + "{\"type\": \"EXTRACTION_QUALITY\"}]}");
                })
                // A suggestion followed: the Setup tab, at the limits on a learning run
                .story("SuggestionOpensTheLimits", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play suggestions = play.within(play.findByRole("list", "Suggestions"));
                    play.click(suggestions.getByRole("button", "Raise \"Time for each run\" on the Setup tab."));
                    final Play sections = play.within(play.findByRole("navigation", MAP));
                    play.waitFor(() -> play.expect(sections.getByRole("button",
                            TextMatch.startingWith("Learning steps:"))).toHaveAttribute("aria-current", "location"));
                    play.expect(play.getByRole("group", "Time for each run")).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // A run whose model didn't reply in time: failed, not still learning, with what to do about it
                .story("FailedRun", context -> render(context, false, ShapeshifterAiFixtures.DOC,
                        ShapeshifterAiFixtures.FAILED))
                .withPlay(play -> {
                    play.expect(play.findByText("Failed: the model \"GPT-4o\" didn't reply in time."))
                            .toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("Learning tries again from the feed's next "
                                                                      + "stream."))).toBeInTheDocument();
                    final Play suggestions = play.within(play.getByRole("list", "Suggestions"));
                    play.expect(suggestions.getByRole("button",
                            "Raise the timeout in the model document's HTTP Client Configuration."))
                            .toBeInTheDocument();
                    play.click(suggestions.getByRole("button", "Or choose a faster model on the Setup tab."));
                    final Play sections = play.within(play.findByRole("navigation", MAP));
                    play.waitFor(() -> play.expect(sections.getByRole("button",
                                    TextMatch.startingWith("AI model and instructions:")))
                            .toHaveAttribute("aria-current", "location"));
                    DocEditors.expectNoProblems(play);
                })
                // Read only: the banner offers nothing that changes the document
                .story("NotReadyReadOnly", context -> render(context, true, ShapeshifterAiFixtures.NEW_DOC))
                .withPlay(play -> {
                    play.expect(play.findByText("This document won't learn anything yet")).toBeInTheDocument();
                    play.expect(play.queryByRole("button", StroomDom.button("Switch on"))).toBeNull();
                    play.expect(play.getByRole("button", StroomDom.button("Choose a model"))).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The Setup tab: learning, the model, what a run may spend and whether a person approves, saved
                // as the document has them
                .story("Setup", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    play.expect(play.findByRole("radio", "On: learn new feeds automatically")).toBeChecked();
                    openStop(play, "Learning steps:");
                    final Play budget = play.within(play.findByRole("group", "Time for each run"));
                    final Query amount = budget.getByRole("spinbutton", "Amount");
                    play.waitFor(() -> play.expect(amount).toHaveValue("5"));
                    play.click(budget.getByTitle("Increase value"));
                    play.waitFor(() -> play.expect(amount).toHaveValue("6"));
                    openStop(play, "Approving new translations:");
                    play.click(play.findByRole("radio", "Wait for someone to approve them"));
                    save(play, "{\"attemptBudgetMs\": 360000, \"promotionMode\": \"REVIEW\"}");
                })
                .story("SetupReadOnly", context -> render(context, true, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    play.expect(play.findByRole("radio", "On: learn new feeds automatically")).toBeDisabled();
                    play.expect(play.getByLabelText("Instructions to the AI")).toHaveAttribute("readonly");
                    openStop(play, "Approving new translations:");
                    play.expect(play.findByRole("radio", "Pause for a person")).toBeDisabled();
                    openStop(play, "Step 1:");
                    play.expect(play.findByRole("button", "Advanced options")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The route map says what each section is set to; choosing a stop marks it and shows its part,
                // alone
                .story("SetupRouteMap", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    final Play map = play.within(play.findByRole("navigation", MAP));
                    play.expect(map.findByRole("button", "AI model and instructions: On · GPT-4o"))
                            .toHaveAttribute("aria-current", "location");
                    play.expect(play.getByRole("region", "AI model and instructions")).toBeVisible();
                    play.expect(map.getByRole("button", "Learning steps: Its own approach · 2 steps"))
                            .toBeInTheDocument();
                    play.expect(map.getByRole("button", "Step 1: Choose the elements")).toBeInTheDocument();
                    play.expect(map.getByRole("button", "Step 2: Write the parser")).toBeInTheDocument();
                    play.expect(map.getByRole("button", "Quality checks: Own checks · 2 checks"))
                            .toBeInTheDocument();
                    play.expect(map.getByRole("button",
                            "Approving new translations: Pass mark 80% · used once it passes")).toBeInTheDocument();
                    final Query inService = map.getByRole("button",
                            "Monitoring translations in use: Flagged below 60%");
                    play.click(inService);
                    play.waitFor(() -> play.expect(inService).toHaveAttribute("aria-current", "location"));
                    play.expect(play.getByRole("region", "Monitoring translations in use")).toBeVisible();
                    play.expect(play.queryByRole("region", "AI model and instructions")).toBeNull();
                    play.expect(map.getByRole("button", TextMatch.startingWith("AI model and instructions:")))
                            .not().toHaveAttribute("aria-current");
                    // A step shows its card alone, under its own heading
                    play.click(map.getByRole("button", "Step 2: Write the parser"));
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.queryByRole("heading", "Step 1: Choose the elements")).toBeNull();
                    play.expect(play.queryByRole("region", "Monitoring translations in use")).toBeNull();
                    // A change is summed up at once
                    openStop(play, "Approving new translations:");
                    play.click(play.findByRole("radio", "Wait for someone to approve them"));
                    play.expect(map.findByRole("button",
                            "Approving new translations: Pass mark 80% · approved first")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The route map from the keyboard: only the stop chosen is in the tab order, the arrow keys move
                // between stops and Enter chooses one, keeping the focus on it
                .story("SetupRouteMapKeyboard", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    final Play map = play.within(play.findByRole("navigation", MAP));
                    final Query learning = map.findByRole("button", TextMatch.startingWith("AI model and"));
                    play.expect(learning).toHaveAttribute("tabindex", "0");
                    play.expect(map.getByRole("button", TextMatch.startingWith("Learning steps:")))
                            .toHaveAttribute("tabindex", "-1");
                    play.click(learning);
                    play.keyboard("{ArrowDown}");
                    final Query approach = map.getByRole("button", TextMatch.startingWith("Learning steps:"));
                    play.expect(approach).toHaveFocus();
                    play.keyboard("{ArrowDown}{Enter}");
                    final Query first = map.getByRole("button", "Step 1: Choose the elements");
                    play.waitFor(() -> play.expect(first).toHaveAttribute("aria-current", "location"));
                    play.expect(first).toHaveFocus();
                    play.expect(play.getByRole("heading", "Step 1: Choose the elements")).toBeVisible();
                    play.keyboard("{End}");
                    play.expect(map.getByRole("button", TextMatch.startingWith("Monitoring translations in use:")))
                            .toHaveFocus();
                    play.keyboard("{Home}");
                    play.expect(map.getByRole("button", TextMatch.startingWith("AI model and"))).toHaveFocus();
                    DocEditors.expectNoProblems(play);
                })
                // The quality checks: the document's own, in plain words
                .story("Quality", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Quality checks:");
                    play.expect(play.findByText("Meets the business rules")).toBeInTheDocument();
                    play.expect(play.getByText("Compiles")).toBeInTheDocument();
                    play.expect(play.getByText("These are this document's own checks.")).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Add a check")).not().toHaveAttribute("aria-disabled");
                    DocEditors.expectNoProblems(play);
                })
                .story("QualityReadOnly", context -> render(context, true, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Quality checks:");
                    play.click(play.findByText("Meets the business rules"));
                    play.expect(play.getByRole("button", "Add a check")).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", "Edit the selected check"))
                            .toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("radio", "Standard")).toBeDisabled();
                    DocEditors.expectNoProblems(play);
                })
                // A starting set replaces the document's own checks once it says yes
                .story("QualityPresetConfirmOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Quality checks:");
                    play.click(play.findByRole("radio", "Standard"));
                    play.expect(screen.findByText(TextMatch.startingWith("Replace this document's 2 checks")))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.expect(play.findByText("Events carry real content")).toBeInTheDocument();
                    play.expect(play.queryByText("Meets the business rules")).toBeNull();
                    save(play, "{\"scorers\": [{\"type\": \"COMPILE\"}, {\"type\": \"YIELD\"}, "
                               + "{\"type\": \"SCHEMA_CONFORMANCE\"}, {\"type\": \"EXTRACTION_QUALITY\"}]}");
                })
                .story("QualityPresetConfirmCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Quality checks:");
                    play.click(play.findByRole("radio", "Standard"));
                    play.expect(screen.findByText(TextMatch.startingWith("Replace this document's 2 checks")))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(play.getByRole("radio", "Standard")).not().toBeChecked());
                    play.expect(play.getByText("Meets the business rules")).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A check's dialog: a new weight, OK and Save write it
                .story("ScorerDialogOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Meets the business rules");
                    final Query weight = screen.querySelector(WEIGHT);
                    play.clear(weight);
                    play.type(weight, "2.5");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Scorer", StroomDom.DIALOG_TITLE)).toBeNull());
                    play.waitFor(() -> play.expect(play.getByText("2.5")).toBeInTheDocument());
                    save(play, "{\"scorers\": [{\"type\": \"COMPILE\"}, "
                               + "{\"type\": \"BUSINESS_RULES\", \"weight\": 2.5}]}");
                })
                // A check's dialog, cancelled: nothing changes
                .story("ScorerDialogCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Meets the business rules");
                    final Query weight = screen.querySelector(WEIGHT);
                    play.clear(weight);
                    play.type(weight, "7");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Scorer", StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("7.0")).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A weight that isn't a number is refused, not changed: OK says why and stays open
                .story("ScorerDialogRefusesBadNumber", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Meets the business rules");
                    final Query weight = screen.querySelector(WEIGHT);
                    play.clear(weight);
                    play.type(weight, "heavy");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.expect(screen.findByText("Must be a number of zero or more")).toBeInTheDocument();
                    play.expect(screen.getByText("Scorer", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.expect(weight).toHaveValue("heavy");
                    DocEditors.expectNoProblems(play);
                })
                // A business rule's assertion, added in its own dialog within the check's
                .story("AssertionDialogOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Meets the business rules");
                    play.click(screen.getByRole("button", "Add assertion"));
                    play.expect(screen.findByText("Assertion", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.type(screen.getByLabelText("Name"), "events have a time");
                    play.type(screen.getByLabelText("XPath"), "EventTime/TimeCreated");
                    play.tab();
                    final Play assertion = play.within(screen.getByText("Assertion", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(assertion.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.getByText("events have a time")).toBeInTheDocument());
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Scorer", StroomDom.DIALOG_TITLE)).toBeNull());
                    save(play, "{\"scorers\": [{\"type\": \"COMPILE\"}, {\"type\": \"BUSINESS_RULES\", "
                               + "\"parameters\": {\"assertions\": [{\"name\": \"users are named\"}, "
                               + "{\"name\": \"events have a time\", \"xpath\": \"EventTime/TimeCreated\"}]}}]}");
                })
                // An assertion's dialog, cancelled: the check keeps its one assertion
                .story("AssertionDialogCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Meets the business rules");
                    play.click(screen.getByRole("button", "Add assertion"));
                    play.expect(screen.findByText("Assertion", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.type(screen.getByLabelText("Name"), "never kept");
                    final Play assertion = play.within(screen.getByText("Assertion", StroomDom.DIALOG_TITLE)
                            .closest(StroomDom.DIALOG));
                    play.click(assertion.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Assertion", StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(screen.queryByText("never kept")).toBeNull();
                    play.expect(screen.getByText("users are named")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // Learning steps: the starting points and limits; a step chosen on the route map, its card read as
                // sentences, with its question text beneath
                .story("Approach", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Learning steps:");
                    play.expect(play.findByText(TextMatch.startingWith("This is the document's own approach")))
                            .toBeVisible();
                    play.expect(play.getByRole("group", "Time for each run")).toBeVisible();
                    openStop(play, "Step 1:");
                    play.expect(play.findByRole("heading", "Step 1: Choose the elements")).toBeVisible();
                    // Add step, on the map under the step chosen
                    play.expect(play.getByRole("button", "Add a step after step 1")).toBeVisible();
                    play.expect(play.getByText("Question text of step 1: Choose the elements")).toBeVisible();
                    openStop(play, "Step 2:");
                    play.expect(play.findByText("If it runs out of tries, it will stop learning.")).toBeVisible();
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.getByRole("button", "Add a step after step 2")).toBeVisible();
                    play.expect(play.queryByRole("button", "Add a step after step 1")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                .story("ApproachReadOnly", context -> render(context, true, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Step 2:");
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.queryByRole("button", "Add a step after step 2")).toBeNull();
                    play.expect(play.queryByRole("button", "Delete this step")).toBeNull();
                    openStop(play, "Learning steps:");
                    play.expect(play.findByRole("button", StroomDom.button("Load default setup")))
                            .toBeDisabled();
                    DocEditors.expectNoProblems(play);
                })
                // A default setup, from the menu of Load default setup, replaces the steps once confirmed, and
                // is said to be in use
                .story("ApproachStartingPoint", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Learning steps:");
                    play.click(play.findByRole("button", StroomDom.button("Load default setup")));
                    play.click(screen.findByText("Quick, then careful", StroomDom.MENU_ITEM_TEXT));
                    play.expect(screen.findByText(TextMatch.startingWith(
                                    "Load the default setup 'Quick, then careful'?")))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.expect(play.findByRole("button", "Step 7: Write the transform again")).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("In use: Quick, then careful")))
                            .toBeInTheDocument();
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\"}, {\"kind\": \"SPLIT\"}, "
                               + "{\"kind\": \"CONFIGURE\"}, {\"id\": \"first\"}, {\"kind\": \"TARGET\"}, "
                               + "{\"id\": \"again\"}, {\"kind\": \"CONFIGURE\"}, {\"kind\": \"ADMIT\"}]}}");
                })
                // A step added from the menu of Add step, on the route map under the step chosen, which is
                // then the step chosen
                .story("ApproachAddStep", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Step 1:");
                    play.click(play.findByRole("button", "Add a step after step 1"));
                    play.click(screen.findByText("Find what one record is", StroomDom.MENU_ITEM_TEXT));
                    final Play map = play.within(play.getByRole("navigation", MAP));
                    final Query added = map.findByRole("button", "Step 2: Find what one record is");
                    play.waitFor(() -> play.expect(added).toHaveAttribute("aria-current", "location"));
                    play.expect(map.getByRole("button", "Step 3: Write the parser")).toBeInTheDocument();
                    play.expect(play.getByRole("heading", "Step 2: Find what one record is")).toBeVisible();
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\"}, {\"kind\": \"SPLIT\"}, "
                               + "{\"kind\": \"CONFIGURE\"}]}}");
                })
                // A rule's destination chosen from its menu
                .story("ApproachRuleDestination", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Step 2:");
                    play.click(play.findByRole("combobox", "Rule 1: then"));
                    play.click(screen.findByRole("option", "pause for a person"));
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\"}, {\"kind\": \"CONFIGURE\", "
                               + "\"transitions\": [{\"on\": \"COVERAGE_SHORT\", \"goTo\": \"park\"}]}]}}");
                })
                // The fallback, chosen under Approving new translations: pausing for a person takes its step out
                // of the flow, with the rule that went to it
                .story("Fallback", context -> render(context, false, ShapeshifterAiFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    play.expect(play.findByRole("radio", TextMatch.startingWith("Serve what it can"))).toBeChecked();
                    play.expect(play.getByRole("button", "Show the fallback's question text")).toBeVisible();
                    final Query pause = play.getByRole("radio", "Pause for a person");
                    play.click(pause);
                    play.waitFor(() -> play.expect(pause).toBeChecked());
                    play.expect(play.queryByRole("button", "Show the fallback's question text")).toBeNull();
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\"}, {\"kind\": \"CONFIGURE\"}, "
                               + "{\"kind\": \"CONFIGURE\"}]}}");
                    play.expect(play.getByRole("navigation", MAP)).not().toHaveTextContent("Fallback");
                })
                // The fallback's question text, from Approving new translations: its stop on the route map
                .story("FallbackText", context -> render(context, false, ShapeshifterAiFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    play.click(play.findByRole("button", "Show the fallback's question text"));
                    final Play map = play.within(play.getByRole("navigation", MAP));
                    play.waitFor(() -> play.expect(map.getByRole("button", "Fallback: marked Unknowns"))
                            .toHaveAttribute("aria-current", "location"));
                    play.expect(play.getByRole("heading", "Fallback: marked Unknowns")).toBeVisible();
                    play.expect(play.getByText("Question text of the fallback")).toBeVisible();
                    play.expect(play.getByRole("region", "Learning step")).toHaveFocus();
                    DocEditors.expectNoProblems(play);
                })
                // A step fine-tuned: its tries, out of sight until unfolded, and a tag on its heading for them
                .story("ApproachFineTune", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Step 2:");
                    final Query fineTune = play.findByRole("button", "Advanced options");
                    play.expect(fineTune).toHaveAttribute("aria-expanded", "false");
                    play.click(fineTune);
                    play.expect(fineTune).toHaveAttribute("aria-expanded", "true");
                    final Query tries = play.getByLabelText("Tries");
                    play.type(tries, "5");
                    play.tab();
                    play.expect(play.findByText("5 tries")).toBeInTheDocument();
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\"}, {\"kind\": \"CONFIGURE\", "
                               + "\"candidates\": 5}]}}");
                    // Read back after the save, the step stays unfolded
                    play.waitFor(() -> play.expect(fineTune).toHaveAttribute("aria-expanded", "true"));
                })
                // The parsers and filters the AI may choose from, under the first step's "Advanced options":
                // every one is ticked, and one unticked is saved on the step and tagged on its heading
                .story("ApproachElementTypes", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Step 1:");
                    play.click(play.findByRole("button", "Advanced options"));
                    final Play choices = play.within(play.findByRole("group",
                            "Parsers and filters the AI may choose from"));
                    final Query xslt = choices.findByRole("checkbox", "XSLTFilter");
                    play.expect(xslt).toBeChecked();
                    play.expect(choices.getByRole("checkbox", "XMLFragmentParser")).toBeChecked();
                    play.click(xslt);
                    play.waitFor(() -> play.expect(xslt).not().toBeChecked());
                    play.expect(play.findByText("chooses from DSParser, JSONParser and XMLFragmentParser"))
                            .toBeInTheDocument();
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\", \"elements\": [\"DSParser\", "
                               + "\"JSONParser\", \"XMLFragmentParser\"]}, {\"kind\": \"CONFIGURE\"}]}}");
                })
                // The last parser or filter ticked stays ticked, and the group says why
                .story("ApproachElementTypesLastOneStays",
                        context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Step 1:");
                    play.click(play.findByRole("button", "Advanced options"));
                    final Play choices = play.within(play.findByRole("group",
                            "Parsers and filters the AI may choose from"));
                    play.click(choices.findByRole("checkbox", "DSParser"));
                    play.click(choices.getByRole("checkbox", "JSONParser"));
                    play.click(choices.getByRole("checkbox", "XMLFragmentParser"));
                    final Query xslt = choices.getByRole("checkbox", "XSLTFilter");
                    play.click(xslt);
                    play.expect(xslt).toBeChecked();
                    play.expect(play.findByText("Tick at least one: the AI must have something to choose from."))
                            .toBeInTheDocument();
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\", \"elements\": "
                               + "[\"XSLTFilter\"]}, {\"kind\": \"CONFIGURE\"}]}}");
                })
                // A step naming an element type the AI can't learn says so, and it can be removed
                .story("ApproachUnlearnableElements",
                        context -> render(context, false, ShapeshifterAiFixtures.DOC_UNLEARNABLE))
                .withPlay(play -> {
                    openStop(play, "Step 1:");
                    play.click(play.findByRole("button", "Advanced options"));
                    play.expect(play.findByText("The AI can't learn XMLParser, so a run passes it over."))
                            .toBeInTheDocument();
                    play.expect(play.getByRole("checkbox", "XSLTFilter")).toBeChecked();
                    play.click(play.getByRole("button", "Remove XMLParser"));
                    play.waitFor(() -> play.expect(play.queryByText(
                            "The AI can't learn XMLParser, so a run passes it over.")).toBeNull());
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\", \"elements\": "
                               + "[\"XSLTFilter\"]}, {\"kind\": \"CONFIGURE\"}]}}");
                })
                // A built-in text loaded into one of the selected step's texts
                .story("BuiltInTextDialogOk", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Step 1:");
                    play.click(play.findAllByRole("button", "Load built-in").nth(0));
                    play.expect(screen.findByText("Load a built-in text", StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Load a built-in text",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // Loading a built-in text, cancelled: the document stays clean
                .story("BuiltInTextDialogCancel", context -> render(context, false, ShapeshifterAiFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Step 1:");
                    play.click(play.findAllByRole("button", "Load built-in").nth(0));
                    play.expect(screen.findByText("Load a built-in text", StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Load a built-in text",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                });
    }

    // Opens one of the document's tabs once it is shown
    private static void openTab(final Play play, final String label) {
        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
        play.click(DocEditors.tab(play, label));
    }

    // Opens the Setup tab, and chooses a stop on its route map by the start of what its button is called: a
    // section, e.g. "Quality checks:", or a step, e.g. "Step 2:"
    private static void openStop(final Play play, final String stop) {
        openTab(play, "Setup");
        final Play map = play.within(play.findByRole("navigation", MAP));
        final Query button = map.findByRole("button", TextMatch.startingWith(stop));
        play.click(button);
        play.waitFor(() -> play.expect(button).toHaveAttribute("aria-current", "location"));
    }

    // Selects a check's row on the Setup tab and opens its dialog with Edit
    private static void openScorer(final Play play, final String check) {
        final Play screen = play.screen();
        openStop(play, "Quality checks:");
        play.click(play.findByText(check));
        play.click(play.getByRole("button", "Edit the selected check"));
        play.expect(screen.findByText("Scorer", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
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

    // Saves, and checks the document sent holds what is given
    private static void save(final Play play, final String expected) {
        final Query save = play.getByRole("button", "Save");
        play.waitFor(() -> play.expect(save).not().toHaveAttribute("aria-disabled"));
        play.click(save);
        play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                RequestMatcher.put(SAVE_PATH).withJsonBodyContaining(expected).toSpyMatcher()));
        DocEditors.expectNoProblems(play);
    }

    private static Widget render(final StoryContext context, final boolean readOnly, final String doc) {
        return render(context, readOnly, doc, ShapeshifterAiFixtures.PAUSED);
    }

    private static Widget render(final StoryContext context,
                                 final boolean readOnly,
                                 final String doc,
                                 final String firewall) {
        final RestFixtures fixtures = ShapeshifterAiFixtures.editorRoutes(
                        DocEditors.docSelectionRoutes(DocEditors.permissionRoutes(RestFixtures.builder())), doc,
                        firewall)
                .build();
        final ShapeshifterAiResource resource = GWT.create(ShapeshifterAiResource.class);
        final String uuid = ShapeshifterAiFixtures.DOC_REF.getUuid();
        return DocEditors.render(context, fixtures, readOnly, (harness, injector) -> DocEditors.open(harness,
                ShapeshifterAiFixtures.DOC_REF,
                injector.getShapeshifterAiEditor(),
                DocResource.of(
                        restFactory -> restFactory.create(resource).method(res -> res.fetch(uuid)),
                        (restFactory, doc2) -> restFactory.create(resource)
                                .method(res -> res.update(doc2.getUuid(), doc2)))));
    }
}
