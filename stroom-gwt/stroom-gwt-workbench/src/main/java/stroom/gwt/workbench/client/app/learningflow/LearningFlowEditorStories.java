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
import stroom.learningflow.client.component.BuiltInTextController;
import stroom.learningflow.client.component.CheckEditorController;
import stroom.learningflow.client.component.FeedFilterEditorController;
import stroom.learningflow.client.component.LearningFlowEditor;
import stroom.learningflow.client.component.XPathAssertionEditorController;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.Map;

/// The `App/Editors/LearningFlowEditor` stories, showing Stroom's real [LearningFlowEditor] (a
/// Learning Flow document's tabs: Pipelines, Setup, Documentation and Permissions) with fake REST replies.
///
/// As `LearningFlowPlugin` does, the story fetches the document and reads it into the editor
/// ([DocEditors#open]). The Pipelines tab lists the pipelines of the document's feeds, each with a button that
/// opens the pipeline, whose AI Translation tab has its own stories ([PipelineAiTranslationStories]). The tab
/// also has a quick filter and a dialog for choosing which feeds to show ([FeedFilterEditorController]). The
/// Setup tab's dialogs are covered too: a check's dialog ([CheckEditorController]), the dialog for a business
/// rule's assertion within it ([XPathAssertionEditorController]), and the dialog for loading a built-in text
/// ([BuiltInTextController]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET` and `PUT /learningFlow/v1/{uuid}` | the document, fetched and saved |
/// | `GET /learningFlow/v1/templates` | the built-in texts the Setup tab's question texts start from |
/// | `POST /learningFlowSupervisor/v1/learners/find` | the document's feeds |
/// | `POST /explorer/v2/decorate` and `getFromDocRef` | the Setup tab's model and target schema pickers |
/// | `GET /learningFlow/v1/targetSchema/{uuid}` | what the target schema means for the fallback and records |
/// | the Permissions tab's routes | the Permissions tab |
public final class LearningFlowEditorStories {

    private static final String SAVE_PATH = "/learningFlow/v1/ss-1";
    // A FormGroup gives its control the group's identity as its id.
    private static final String THRESHOLD = "#checkThreshold";
    private static final String RULES = "Meets the business rules";
    private static final String TIMES = "Event times are plausible";
    private static final String TIME_FIELD = "#timesTimeField";
    // All the fixture's checks, as a save sends them: the Standard set, with business rules as advice.
    private static final List<CheckSetting> DOC_CHECKS = CheckSettings.complete(List.of(
            CheckSetting.of(Check.COMPILE, CheckState.GATE),
            new CheckSetting(Check.RULES, CheckState.ADVICE, 0.9, null)));
    private static final String MAP = "Setup route map";
    // The target schema's picker, found by the id its FormGroup gives it.
    private static final String TARGET_SCHEMA = "#learningFlowTargetSchema";
    private static final String FALLBACK_ON = "Serve what it can, with the rest as marked Unknown events";
    private static final String NO_UNKNOWN = "has none, so a transform that can't be finished";
    private static final String TARGET_SCHEMA_BUTTON = TARGET_SCHEMA + ".drop-down, " + TARGET_SCHEMA + " .drop-down";
    private static final String NO_TARGET_SCHEMA =
            "No target schema is chosen, so the AI has no schema to write to.";
    // A new document with its own words for the system text, one of the texts every step shares
    private static final String OWN_SYSTEM_TEXT = LearningFlowFixtures.NEW_DOC.replace("\"flow\": {\"steps\": [",
            "\"flow\": {\"templates\": {\"SYSTEM\": \"Our own system text.\"}, \"steps\": [");
    private static final String FEED_FILTER = "Which pipelines to show";
    private static final String FEEDS_FILTERED = "Pipelines are being filtered; click to change or clear";

    private LearningFlowEditorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/LearningFlowEditor", LearningFlowEditorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The Pipelines tab is shown first. The document is ready to learn, so there is no banner.
                // The feeds' pipelines are listed in a table, each with a button that opens the pipeline,
                // where its learning can be followed.
                .story("Default", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    for (final String label : new String[]{"Pipelines", "Setup", "Documentation",
                            "Permissions"}) {
                        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
                    }
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Proxy pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Needs you")).toBeInTheDocument();
                    play.expect(grid.getByText("Translating")).toBeInTheDocument();
                    play.expect(grid.getByRole("button", "Open pipeline Firewall pipeline")).toBeInTheDocument();
                    play.expect(grid.getByRole("button", "Open pipeline Proxy pipeline")).toBeInTheDocument();
                    // A feed's detailed status is shown on its pipeline, not here.
                    play.expect(play.queryByRole("heading", "Firewall pipeline")).toBeNull();
                    play.expect(play.queryByText("This document won't learn anything yet")).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The quick filter above the table shows only the feeds whose pipeline name contains its text.
                .story("FeedsQuickFilter", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    play.type(play.getByRole("searchbox", "Filter pipelines by name"), "proxy");
                    play.waitFor(() -> play.expect(grid.queryByText("Firewall pipeline")).toBeNull());
                    play.expect(grid.getByText("Proxy pipeline")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The filter button opens a dialog listing each learner state with its count, and an option to
                // show deleted pipelines. OK lists only the chosen feeds, requests deleted pipelines too, and
                // the button then says the list is filtered.
                .story("FeedsFilterDialogOk", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    openFeedFilter(play);
                    play.click(screen.querySelector("#learningFlowFeedFilter " + StroomDom.SELECTION_BOX));
                    play.click(screen.findByText("Translating (1)"));
                    play.click(screen.getByRole("checkbox", "Show deleted pipelines"));
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText(FEED_FILTER, StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.waitFor(() -> play.expect(grid.queryByText("Firewall pipeline")).toBeNull());
                    play.expect(grid.getByText("Proxy pipeline")).toBeInTheDocument();
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/learningFlowSupervisor/v1/learners/find")
                                    .withJsonBodyContaining("{\"showDeletedPipelines\": true}")
                                    .toSpyMatcher());
                    play.expect(play.getByRole("button", FEEDS_FILTERED)).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // Cancelling the filter dialog leaves every feed listed, unfiltered.
                .story("FeedsFilterDialogCancel", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    openFeedFilter(play);
                    play.click(screen.querySelector("#learningFlowFeedFilter " + StroomDom.SELECTION_BOX));
                    play.click(screen.findByText("Translating (1)"));
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(FEED_FILTER, StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.expect(grid.getByText("Firewall pipeline")).toBeInTheDocument();
                    play.expect(grid.getByText("Proxy pipeline")).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Filter Pipelines")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // A new document won't learn, and the banner says why. Each fix opens its tab and makes the
                // change there, and the banner then shows the change as not yet saved.
                .story("NotReady", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    play.expect(play.findByText("This document won't learn anything yet")).toBeInTheDocument();
                    // A new Learning Flow has the Standard checks, so the checks are not what stops it.
                    play.expect(play.getByText("7 quality gates")).toBeInTheDocument();
                    play.expect(play.getByText(NO_TARGET_SCHEMA)).toBeInTheDocument();
                    play.click(play.getByRole("button", StroomDom.button("Switch on")));
                    play.expect(play.findByRole("radio", "On: learn new feeds automatically")).toBeChecked();
                    openTab(play, "Pipelines");
                    play.expect(play.findByText("Learning is on")).toBeInTheDocument();
                    play.expect(play.getByText(TextMatch.startingWith("This is the document as you have it now")))
                            .toBeVisible();
                    save(play, "{\"learningMode\": \"AUTOMATIC\"}");
                })
                // When compile is the only check that is a gate, the banner offers the Standard set, and
                // accepting it puts the set in place.
                .story("NoGates", context -> render(context, false, LearningFlowFixtures.NO_GATES_DOC))
                .withPlay(play -> {
                    play.expect(play.findByText("No quality check is a gate, so any answer that runs would pass."))
                            .toBeInTheDocument();
                    play.click(play.getByRole("button", StroomDom.button("Use the Standard checks")));
                    play.expect(play.findByRole("radio", "Standard")).toBeChecked();
                    openTab(play, "Pipelines");
                    // The Standard set has six gates besides compile, so nothing stops the document learning
                    // and the banner is hidden. The tab hides the banner rather than emptying it, so its text
                    // stays in the DOM, and the check is on visibility.
                    final Play grid = play.within(play.findByRole("grid", "Pipelines"));
                    play.expect(grid.findByText("Firewall pipeline")).toBeInTheDocument();
                    play.waitFor(() -> play.expect(play.getByText("This document won't learn anything yet"))
                            .not().toBeVisible());
                    save(play, checks(CheckPreset.STANDARD.settings(), Map.of()));
                })
                // When read only, the banner offers nothing that changes the document.
                .story("NotReadyReadOnly", context -> render(context, true, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    play.expect(play.findByText("This document won't learn anything yet")).toBeInTheDocument();
                    play.expect(play.queryByRole("button", StroomDom.button("Switch on"))).toBeNull();
                    play.expect(play.getByRole("button", StroomDom.button("Choose a model"))).toBeInTheDocument();
                    play.expect(play.getByRole("button", StroomDom.button("Choose a target schema")))
                            .toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // The Setup tab shows whether learning is on, the model, what a run may spend, and whether a
                // person approves new translations, and changes to them are saved in the document.
                .story("Setup", context -> render(context, false, LearningFlowFixtures.DOC))
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
                .story("SetupReadOnly", context -> render(context, true, LearningFlowFixtures.DOC))
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
                // A document with no target schema won't learn (D1), which the banner says; its fix shows the
                // field, and the route map says the section needs attention, and why.
                .story("SetupTargetSchemaMissing",
                        context -> render(context, false, LearningFlowFixtures.DOC_UNTARGETED))
                .withPlay(play -> {
                    play.expect(play.findByText(NO_TARGET_SCHEMA)).toBeInTheDocument();
                    play.click(play.getByRole("button", StroomDom.button("Choose a target schema")));
                    play.expect(play.findByRole("navigation", MAP)).toBeInTheDocument();
                    final Play map = play.within(play.getByRole("navigation", MAP));
                    // The problem is wrapped over lines on the map, and said whole in the station's name
                    play.expect(map.getByRole("button", TextMatch.containing(
                            "AI model and instructions: needs attention. " + NO_TARGET_SCHEMA))).toBeInTheDocument();
                    play.waitFor(() -> play.expect(play.querySelector(TARGET_SCHEMA)).toBeVisible());
                    DocEditors.expectNoProblems(play);
                })
                // The target schema the document names is shown beside the model, and is kept unchanged when
                // something else on the tab is saved.
                .story("SetupTargetSchema", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    play.waitFor(() -> play.expect(play.querySelector(TARGET_SCHEMA))
                            .toHaveTextContent(TextMatch.containing("event-logging v4.1.0")));
                    play.expect(play.querySelector(TARGET_SCHEMA_BUTTON)).not().toHaveAttribute("aria-disabled");
                    play.expect(play.queryByText(TextMatch.startingWith("Every child of"))).toBeNull();
                    final Query instructions = play.getByLabelText("Instructions to the AI");
                    play.clear(instructions);
                    play.type(instructions, "Prefer Network events.");
                    play.tab();
                    save(play, "{\"instructions\": \"Prefer Network events.\", "
                               + "\"targetSchema\": {\"type\": \"XMLSchema\", \"uuid\": \"schema-1\"}}");
                })
                // When read only, the target schema is shown but can't be changed.
                .story("SetupTargetSchemaReadOnly", context -> render(context, true, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    play.waitFor(() -> play.expect(play.querySelector(TARGET_SCHEMA))
                            .toHaveTextContent(TextMatch.containing("event-logging v4.1.0")));
                    play.expect(play.querySelector(TARGET_SCHEMA_BUTTON)).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // When the target schema's root may hold a header beside its records, the tab warns, because
                // every child of the root is read as a record. Event-logging's root holds only events, so no
                // warning is shown for it.
                .story("SetupTargetSchemaRootHoldsSeveralKinds",
                        context -> render(context, false, LearningFlowFixtures.HEADED_SCHEMA_DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.startingWith(
                            "Every child of <Batch> is read as one record, and it may hold Header, Record.")))
                            .toBeVisible());
                    DocEditors.expectNoProblems(play);
                })
                // With event-logging as the target schema, the fallback applies and no note is shown.
                .story("SetupFallbackForEventLogging", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    // The fixture's advanced settings differ from a new document's, so Advanced options is
                    // already open.
                    play.waitFor(() -> play.expect(play.getByRole("button", "Advanced options"))
                            .toHaveAttribute("aria-expanded", "true"));
                    play.expect(play.findByRole("radio", FALLBACK_ON)).toBeInTheDocument();
                    play.expect(play.queryByText(TextMatch.containing(NO_UNKNOWN))).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // Another schema has no marked Unknown, so the fallback doesn't apply, and a note beside it
                // says so.
                .story("SetupFallbackNotForOtherSchemas",
                        context -> render(context, false, LearningFlowFixtures.OTHER_SCHEMA_DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    // The fixture's advanced settings differ from a new document's, so Advanced options is
                    // already open.
                    play.waitFor(() -> play.expect(play.getByRole("button", "Advanced options"))
                            .toHaveAttribute("aria-expanded", "true"));
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.containing(
                            "and sightings v2.4 has none"))).toBeVisible());
                    DocEditors.expectNoProblems(play);
                })
                // A document with no target schema, such as one made before the setting existed, shows none
                // and is saved with none. It continues to be judged against the EVENTS schema group, but its
                // Pipelines tab says it won't learn.
                .story("SetupNoTargetSchema", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    play.waitFor(() -> play.expect(play.querySelector(TARGET_SCHEMA)).toBeVisible());
                    play.expect(play.querySelector(TARGET_SCHEMA))
                            .not().toHaveTextContent(TextMatch.containing("event-logging"));
                    DocEditors.expectNoProblems(play);
                })
                // The route map summarises each section's settings. Choosing a stop marks it as current and
                // shows only that part of the tab.
                .story("SetupRouteMap", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openTab(play, "Setup");
                    final Play map = play.within(play.findByRole("navigation", MAP));
                    play.expect(map.findByRole("button",
                            "AI model and instructions: On · GPT-4o · event-logging v4.1.0"))
                            .toHaveAttribute("aria-current", "location");
                    play.expect(play.getByRole("region", "AI model and instructions")).toBeVisible();
                    play.expect(map.getByRole("button", "Learning steps: Its own approach · 2 steps"))
                            .toBeInTheDocument();
                    play.expect(map.getByRole("button", "Step 1: Choose the elements")).toBeInTheDocument();
                    play.expect(map.getByRole("button", "Step 2: Write the parser")).toBeInTheDocument();
                    play.expect(map.getByRole("button", "Quality checks: Own checks · 9 gates, 3 advice checks"))
                            .toBeInTheDocument();
                    play.expect(map.getByRole("button",
                            "Approving new translations: Used once it passes")).toBeInTheDocument();
                    final Query inService = map.getByRole("button",
                            "Monitoring translations in use: Flagged below 60%");
                    play.click(inService);
                    play.waitFor(() -> play.expect(inService).toHaveAttribute("aria-current", "location"));
                    play.expect(play.getByRole("region", "Monitoring translations in use")).toBeVisible();
                    play.expect(play.queryByRole("region", "AI model and instructions")).toBeNull();
                    play.expect(map.getByRole("button", TextMatch.startingWith("AI model and instructions:")))
                            .not().toHaveAttribute("aria-current");
                    // Choosing a step shows only its card, under its own heading.
                    play.click(map.getByRole("button", "Step 2: Write the parser"));
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.queryByRole("heading", "Step 1: Choose the elements")).toBeNull();
                    play.expect(play.queryByRole("region", "Monitoring translations in use")).toBeNull();
                    // A change is summarised on the route map straight away.
                    openStop(play, "Approving new translations:");
                    play.click(play.findByRole("radio", "Wait for someone to approve them"));
                    play.expect(map.findByRole("button",
                            "Approving new translations: Approved first")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // Using the route map from the keyboard. Only the current stop is in the tab order, the arrow
                // keys move between stops, and Enter chooses one while keeping the focus on it.
                .story("SetupRouteMapKeyboard", context -> render(context, false, LearningFlowFixtures.DOC))
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
                // The quality checks section lists the document's own checks in plain words.
                .story("Quality", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Quality checks:");
                    play.expect(play.findByText(RULES)).toBeInTheDocument();
                    play.expect(play.getByText("Compiles")).toBeInTheDocument();
                    play.expect(play.getByText("Records are whole")).toBeInTheDocument();
                    play.expect(play.getByText("These are this Learning Flow's own checks.")).toBeInTheDocument();
                    // Every check always has a row, so checks are edited, never added.
                    play.expect(play.queryByRole("button", "Add a check")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                .story("QualityReadOnly", context -> render(context, true, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Quality checks:");
                    play.click(play.findByText(RULES));
                    play.expect(play.getByRole("button", "Edit the selected check"))
                            .toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("radio", "Standard")).toBeDisabled();
                    DocEditors.expectNoProblems(play);
                })
                // Choosing a preset set of checks replaces the document's own checks once confirmed.
                .story("QualityPresetConfirmOk", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Quality checks:");
                    play.click(play.findByRole("radio", "Standard"));
                    play.expect(screen.findByText(TextMatch.startingWith("Replace this Learning Flow's own checks")))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.expect(play.findByRole("radio", "Standard")).toBeChecked();
                    save(play, checks(CheckPreset.STANDARD.settings(), Map.of()));
                })
                .story("QualityPresetConfirmCancel", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Quality checks:");
                    play.click(play.findByRole("radio", "Standard"));
                    play.expect(screen.findByText(TextMatch.startingWith("Replace this Learning Flow's own checks")))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(play.getByRole("radio", "Standard")).not().toBeChecked());
                    play.expect(play.getByText(RULES)).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A new threshold entered in a check's dialog is written by OK and then Save.
                .story("CheckDialogOk", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, RULES);
                    final Query threshold = screen.querySelector(THRESHOLD);
                    play.clear(threshold);
                    play.type(threshold, "0.75");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText(RULES, StroomDom.DIALOG_TITLE)).toBeNull());
                    play.waitFor(() -> play.expect(play.getByText("75%")).toBeInTheDocument());
                    save(play, checks(DOC_CHECKS, Map.of(Check.RULES,
                            "{\"check\": \"RULES\", \"state\": \"ADVICE\", \"threshold\": 0.75}")));
                })
                // Cancelling a check's dialog changes nothing.
                .story("CheckDialogCancel", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, RULES);
                    final Query threshold = screen.querySelector(THRESHOLD);
                    play.clear(threshold);
                    play.type(threshold, "0.4");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(RULES, StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.queryByText("40%")).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The time plausibility check's dialog sets its time window and the field holding the time
                // it judges, which must be named for a target schema other than event-logging. OK and Save
                // write all three.
                .story("TimesDialogOk", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, TIMES);
                    final Query field = screen.querySelector(TIME_FIELD);
                    play.type(field, "When");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText(TIMES, StroomDom.DIALOG_TITLE)).toBeNull());
                    save(play, checks(DOC_CHECKS, Map.of(Check.TIMES,
                            "{\"check\": \"TIMES\", \"parameters\": {\"type\": \"times\", "
                            + "\"beforeDays\": 365, \"afterDays\": 1, \"timeField\": \"When\"}}")));
                })
                .story("TimesDialogCancel", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, TIMES);
                    play.type(screen.querySelector(TIME_FIELD), "When");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText(TIMES, StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A threshold that is not a valid number is rejected rather than changed. OK says why and the
                // dialog stays open.
                .story("CheckDialogRefusesBadNumber", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, RULES);
                    final Query threshold = screen.querySelector(THRESHOLD);
                    play.clear(threshold);
                    play.type(threshold, "most");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.expect(screen.findByText("Must be a number from 0 to 1")).toBeInTheDocument();
                    play.expect(screen.getByText(RULES, StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.expect(threshold).toHaveValue("most");
                    DocEditors.expectNoProblems(play);
                })
                // A business rule's assertion is added in its own dialog, opened from the check's dialog.
                .story("AssertionDialogOk", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, RULES);
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
                    play.waitFor(() -> play.expect(screen.queryByText(RULES, StroomDom.DIALOG_TITLE)).toBeNull());
                    save(play, checks(DOC_CHECKS, Map.of(Check.RULES, "{\"check\": \"RULES\", "
                               + "\"parameters\": {\"assertions\": [{\"name\": \"users are named\"}, "
                               + "{\"name\": \"events have a time\", \"xpath\": \"EventTime/TimeCreated\"}]}}")));
                })
                // Cancelling an assertion's dialog leaves the check with its one existing assertion.
                .story("AssertionDialogCancel", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openCheckDialog(play, RULES);
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
                // The learning steps section shows the starting points and limits. A step chosen on the route
                // map shows its card, which reads as sentences, with its question text beneath.
                .story("Approach", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Learning steps:");
                    play.expect(play.findByText(TextMatch.startingWith("This is the document's own approach")))
                            .toBeVisible();
                    play.expect(play.getByRole("group", "Time for each run")).toBeVisible();
                    openStop(play, "Step 1:");
                    play.expect(play.findByRole("heading", "Step 1: Choose the elements")).toBeVisible();
                    // The Add step button appears on the map under the chosen step.
                    play.expect(play.getByRole("button", "Add a step after step 1")).toBeVisible();
                    play.expect(play.getByText("Question text of step 1: Choose the elements")).toBeVisible();
                    // A step shows only its own text; the text every step shares is under Learning steps
                    play.expect(play.queryByRole("button", "Show the text every step shares")).toBeNull();
                    play.expect(play.getByLabelText("Chain question")).toBeVisible();
                    play.expect(play.getByLabelText("System text")).not().toBeVisible();
                    openStop(play, "Step 2:");
                    play.expect(play.findByText("If it runs out of tries, it will stop learning.")).toBeVisible();
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.getByRole("button", "Add a step after step 2")).toBeVisible();
                    play.expect(play.queryByRole("button", "Add a step after step 1")).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                .story("ApproachReadOnly", context -> render(context, true, LearningFlowFixtures.DOC))
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
                // A default setup chosen from the Load default setup menu replaces the steps once confirmed,
                // and is then shown as in use.
                .story("ApproachStartingPoint", context -> render(context, false, LearningFlowFixtures.DOC))
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
                // A step added from the Add step menu appears on the route map under the chosen step, and
                // becomes the chosen step.
                .story("ApproachAddStep", context -> render(context, false, LearningFlowFixtures.DOC))
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
                // A rule's destination is chosen from its menu.
                .story("ApproachRuleDestination", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStop(play, "Step 2:");
                    play.click(play.findByRole("combobox", "Rule 1: then"));
                    play.click(screen.findByRole("option", "pause for a person"));
                    save(play, "{\"flow\": {\"steps\": [{\"kind\": \"CHAIN\"}, {\"kind\": \"CONFIGURE\", "
                               + "\"transitions\": [{\"on\": \"COVERAGE_SHORT\", \"goTo\": \"park\"}]}]}}");
                })
                // The fallback is chosen under Approving new translations. Choosing to pause for a person
                // removes the fallback step from the flow, together with the rule that led to it.
                .story("Fallback", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    openAdvanced(play);
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
                // The fallback's question text is opened from Approving new translations, which chooses the
                // fallback's stop on the route map.
                .story("FallbackText", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    openAdvanced(play);
                    play.click(play.findByRole("button", "Show the fallback's question text"));
                    final Play map = play.within(play.getByRole("navigation", MAP));
                    play.waitFor(() -> play.expect(map.getByRole("button", "Fallback: marked Unknowns"))
                            .toHaveAttribute("aria-current", "location"));
                    play.expect(play.getByRole("heading", "Fallback: marked Unknowns")).toBeVisible();
                    play.expect(play.getByText("Question text of the fallback")).toBeVisible();
                    play.expect(play.getByRole("region", "Learning step")).toHaveFocus();
                    DocEditors.expectNoProblems(play);
                })
                // On a new document's Setup tab, settings an administrator rarely changes are folded under
                // each section's Advanced options, and unfold on request.
                .story("SetupAdvancedFolded", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    play.expect(play.findByRole("radio", "Wait for someone to approve them")).toBeVisible();
                    final Query advanced = play.getByRole("button", "Advanced options");
                    play.expect(advanced).toHaveAttribute("aria-expanded", "false");
                    play.expect(play.getByLabelText("Records before a translation is trusted")).not().toBeVisible();
                    play.click(advanced);
                    play.waitFor(() -> play.expect(advanced).toHaveAttribute("aria-expanded", "true"));
                    play.expect(play.getByLabelText("Records before a translation is trusted")).toBeVisible();
                    openStop(play, "Quality checks:");
                    play.expect(play.getByRole("button", "Advanced options")).toHaveAttribute("aria-expanded", "false");
                    openStop(play, "Monitoring translations in use:");
                    play.expect(play.getByLabelText("Flag a translation in use below (%)")).toBeVisible();
                    play.expect(play.getByLabelText("Records kept to check against later")).not().toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // When a document's rarely changed settings differ from a new document's, their Advanced
                // options are open, so that no changed setting is hidden.
                .story("SetupAdvancedOpenWhereChanged",
                        context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Approving new translations:");
                    play.expect(play.findByRole("button", "Advanced options"))
                            .toHaveAttribute("aria-expanded", "true");
                    play.expect(play.getByLabelText("Records before a translation is trusted")).toBeVisible();
                    openStop(play, "Quality checks:");
                    play.expect(play.findByRole("grid", "Quality checks")).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // The question text every step shares, under Learning steps' Advanced options rather than with any
                // one step: the built-in's words until the document has its own, edited in place and saved
                .story("SetupSharedTexts", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Learning steps:");
                    play.expect(play.findByLabelText("System text")).not().toBeVisible();
                    openAdvanced(play);
                    play.expect(play.getByText("Question text every step shares")).toBeVisible();
                    final Query system = play.getByLabelText("System text");
                    play.waitFor(() -> play.expect(system).toHaveValue("You write Stroom pipelines."));
                    play.expect(play.getByLabelText("Split rules")).toBeVisible();
                    play.expect(play.getByLabelText("Extraction rules")).toBeVisible();
                    play.expect(play.getByLabelText("Transformation rules")).toBeVisible();
                    play.clear(system);
                    play.type(system, "Write only XSLT 2.0.");
                    play.tab();
                    play.waitFor(() -> play.expect(play.getByText(TextMatch.startingWith("This document's own text")))
                            .toBeVisible());
                    save(play, "{\"flow\": {\"templates\": {\"SYSTEM\": \"Write only XSLT 2.0.\"}}}");
                })
                // A document with its own words for a text every step shares: Learning steps' Advanced options are
                // open of their own accord, so those words are never out of sight
                .story("SetupSharedTextsOpenWhereChanged", context -> render(context, false, OWN_SYSTEM_TEXT))
                .withPlay(play -> {
                    openStop(play, "Learning steps:");
                    play.expect(play.findByRole("button", "Advanced options"))
                            .toHaveAttribute("aria-expanded", "true");
                    final Query system = play.getByLabelText("System text");
                    play.expect(system).toBeVisible();
                    play.waitFor(() -> play.expect(system).toHaveValue("Our own system text."));
                    DocEditors.expectNoProblems(play);
                })
                // Read only, the texts every step shares are shown and can't be changed or loaded
                .story("SetupSharedTextsReadOnly", context -> render(context, true, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Learning steps:");
                    openAdvanced(play);
                    final Query system = play.getByLabelText("System text");
                    play.waitFor(() -> play.expect(system).toHaveValue("You write Stroom pipelines."));
                    play.expect(system).toHaveAttribute("readonly");
                    play.expect(play.getAllByRole("button", "Load built-in").nth(0)).toBeDisabled();
                    DocEditors.expectNoProblems(play);
                })
                // A new document's steps are only a starting point, so they are not on the route map until the
                // person asks to customise them. They then appear on it, with Load default setup, and can be
                // hidden again.
                .story("SetupStepsCustomise", context -> render(context, false, LearningFlowFixtures.NEW_DOC))
                .withPlay(play -> {
                    openStop(play, "Learning steps:");
                    final Play map = play.within(play.getByRole("navigation", MAP));
                    play.expect(map.queryByRole("button", TextMatch.startingWith("Step 1:"))).toBeNull();
                    play.expect(play.queryByRole("button", StroomDom.button("Load default setup"))).toBeNull();
                    play.click(play.getByRole("button", TextMatch.startingWith("Customise the learning steps")));
                    play.waitFor(() -> play.expect(map.getByRole("button", TextMatch.startingWith("Step 1:")))
                            .toBeInTheDocument());
                    play.expect(play.getByRole("button", StroomDom.button("Load default setup"))).toBeVisible();
                    // A chosen step's card replaces the learning steps section's own part.
                    openStop(play, "Step 1:");
                    play.expect(play.findByRole("heading", "Step 1: Choose the elements")).toBeVisible();
                    openStop(play, "Learning steps:");
                    play.click(play.getByRole("button", TextMatch.startingWith("Hide the learning steps")));
                    play.waitFor(() -> play.expect(map.queryByRole("button", TextMatch.startingWith("Step 1:")))
                            .toBeNull());
                    play.expect(map.getByRole("button", TextMatch.startingWith("Learning steps:")))
                            .toHaveAttribute("aria-current", "location");
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A rule that fires when a check falls short, on a Learning Flow where that check is off. The
                // step's card says the rule never fires, but the document can still be saved, since the user
                // may be about to switch the check on.
                .story("ApproachRuleOnCheckThatIsOff",
                        context -> render(context, false, LearningFlowFixtures.RULE_ON_OFF_CHECK_DOC))
                .withPlay(play -> {
                    openStop(play, "Step 2:");
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.findByText(TextMatch.containing("never fires"))).toBeVisible();
                    play.expect(play.getByText(TextMatch.containing("the coverage check is off"))).toBeVisible();
                    // The warning doesn't mark the document as changed, because it is about the document as
                    // saved. TestLearningFlow checks that a warning never stops a save, since the store only
                    // refuses a save because of problems().
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The same rule where the check is a gate shows no warning.
                .story("ApproachRuleOnCheckThatIsOn", context -> render(context, false, LearningFlowFixtures.DOC))
                .withPlay(play -> {
                    openStop(play, "Step 2:");
                    play.expect(play.findByRole("heading", "Step 2: Write the parser")).toBeVisible();
                    play.expect(play.queryByText(TextMatch.containing("never fires"))).toBeNull();
                    DocEditors.expectNoProblems(play);
                })
                // A step's number of tries is hidden under Advanced options until unfolded, and a changed
                // value is tagged on the step's heading.
                .story("ApproachFineTune", context -> render(context, false, LearningFlowFixtures.DOC))
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
                    // After the save the document is read back, and the step stays unfolded.
                    play.waitFor(() -> play.expect(fineTune).toHaveAttribute("aria-expanded", "true"));
                })
                // The parsers and filters the AI may choose from are under the first step's "Advanced
                // options". Every one is ticked by default, and unticking one saves the list on the step and
                // tags its heading.
                .story("ApproachElementTypes", context -> render(context, false, LearningFlowFixtures.DOC))
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
                // The last ticked parser or filter cannot be unticked, and the group says why.
                .story("ApproachElementTypesLastOneStays",
                        context -> render(context, false, LearningFlowFixtures.DOC))
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
                // A step naming an element type the AI can't learn shows a note, and the type can be removed.
                .story("ApproachUnlearnableElements",
                        context -> render(context, false, LearningFlowFixtures.DOC_UNLEARNABLE))
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
                // A built-in text is loaded into one of the selected step's texts.
                .story("BuiltInTextDialogOk", context -> render(context, false, LearningFlowFixtures.DOC))
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
                // Cancelling the load of a built-in text leaves the document unchanged.
                .story("BuiltInTextDialogCancel", context -> render(context, false, LearningFlowFixtures.DOC))
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

    // Opens the dialog for choosing which pipelines the Pipelines tab lists, using the filter button on the
    // table's toolbar.
    private static void openFeedFilter(final Play play) {
        play.click(play.getByRole("button", "Filter Pipelines"));
        play.expect(play.screen().findByText(FEED_FILTER, StroomDom.DIALOG_TITLE)).toBeInTheDocument();
    }

    // Opens one of the document's tabs once it is shown.
    private static void openTab(final Play play, final String label) {
        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
        play.click(DocEditors.tab(play, label));
    }

    // Opens the Setup tab and chooses a stop on its route map by the start of the stop's button label, which
    // is a section, e.g. "Quality checks:", or a step, e.g. "Step 2:".
    private static void openStop(final Play play, final String stop) {
        openTab(play, "Setup");
        final Play map = play.within(play.findByRole("navigation", MAP));
        final Query button = map.findByRole("button", TextMatch.startingWith(stop));
        play.click(button);
        play.waitFor(() -> play.expect(button).toHaveAttribute("aria-current", "location"));
    }

    // Unfolds the Advanced options of the Setup section that is shown.
    private static void openAdvanced(final Play play) {
        final Query advanced = play.findByRole("button", "Advanced options");
        play.click(advanced);
        play.waitFor(() -> play.expect(advanced).toHaveAttribute("aria-expanded", "true"));
    }

    // Selects a check's row on the Setup tab and opens its dialog with Edit.
    private static void openCheckDialog(final Play play, final String check) {
        final Play screen = play.screen();
        openStop(play, "Quality checks:");
        play.click(play.findByText(check));
        play.click(play.getByRole("button", "Edit the selected check"));
        play.expect(screen.findByText(check, StroomDom.DIALOG_TITLE)).toBeInTheDocument();
    }

    // Builds the JSON a save is expected to contain for the checks: every check in order with its state, or
    // the JSON given in `instead` for that check.
    private static String checks(final List<CheckSetting> settings, final Map<Check, String> instead) {
        final StringBuilder json = new StringBuilder("{\"checks\": [");
        for (int i = 0; i < settings.size(); i++) {
            final CheckSetting setting = settings.get(i);
            if (i > 0) {
                json.append(", ");
            }
            json.append(instead.getOrDefault(setting.getCheck(), "{\"check\": \"" + setting.getCheck().name()
                                                                 + "\", \"state\": \"" + setting.getState().name()
                                                                 + "\"}"));
        }
        return json.append("]}").toString();
    }

    // Saves the document and checks that what was sent contains the expected JSON.
    private static void save(final Play play, final String expected) {
        final Query save = play.getByRole("button", "Save");
        play.waitFor(() -> play.expect(save).not().toHaveAttribute("aria-disabled"));
        play.click(save);
        play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                RequestMatcher.put(SAVE_PATH).withJsonBodyContaining(expected).toSpyMatcher()));
        DocEditors.expectNoProblems(play);
    }

    private static Widget render(final StoryContext context, final boolean readOnly, final String doc) {
        final RestFixtures fixtures = LearningFlowFixtures.editorRoutes(
                        DocEditors.docSelectionRoutes(DocEditors.permissionRoutes(RestFixtures.builder())), doc)
                .build();
        final LearningFlowResource resource = GWT.create(LearningFlowResource.class);
        final String uuid = LearningFlowFixtures.DOC_REF.getUuid();
        return DocEditors.render(context, fixtures, readOnly, (harness, injector) -> DocEditors.open(harness,
                LearningFlowFixtures.DOC_REF,
                injector.getLearningFlowEditor(),
                DocResource.of(
                        restFactory -> restFactory.create(resource).method(res -> res.fetch(uuid)),
                        (restFactory, doc2) -> restFactory.create(resource)
                                .method(res -> res.update(doc2.getUuid(), doc2)))));
    }
}
