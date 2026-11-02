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
import stroom.shapeshifter.client.component.ScorerSettingEditorController;
import stroom.shapeshifter.client.component.ShapeshifterAiEditor;
import stroom.shapeshifter.client.component.TransitionEditorController;
import stroom.shapeshifter.client.component.XPathAssertionEditorController;
import stroom.shapeshifter.shared.ShapeshifterAiResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Editors/ShapeshifterAiEditor` stories, showing Stroom's real [ShapeshifterAiEditor] (a
/// Shapeshifter AI document's tab: Score, Learn, Documentation and Permissions, with the process
/// header in the toolbar row) with fake REST replies.
///
/// As `ShapeshifterAiPlugin` does, the story fetches the document and reads it into the editor
/// ([DocEditors#open]). Its dialogs: a scorer's ([ScorerSettingEditorController]), a business rule's
/// assertion within it ([XPathAssertionEditorController]), a step's transition
/// ([TransitionEditorController]) and loading a built-in text ([BuiltInTextController]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET` and `PUT /shapeshifterAi/v1/{uuid}` | the document, fetched and saved |
/// | `GET /shapeshifterAi/v1/templates` | the built-in texts the Learn tab's texts start from |
/// | `POST /shapeshifterAiSupervisor/v1/backlog` and `find` | the process header's figures |
/// | `POST /explorer/v2/decorate` and `getFromDocRef` | the Learn tab's model picker |
/// | the Permissions tab's routes | the Permissions tab |
public final class ShapeshifterAiEditorStories {

    private static final String SAVE_PATH = "/shapeshifterAi/v1/ss-1";
    // A FormGroup gives its control the group's identity as its id
    private static final String WEIGHT = "#scorerWeight";
    private static final String CHART = ".flow-chart";

    private ShapeshifterAiEditorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/ShapeshifterAiEditor", ShapeshifterAiEditorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The Score tab first, with the process header's figures from the Supervisor
                .story("Default", context -> render(context, false))
                .withPlay(play -> {
                    for (final String label : new String[]{"Score", "Learn", "Documentation", "Permissions"}) {
                        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
                    }
                    play.waitFor(() -> play.expect(play.getByText("Business rules")).toBeInTheDocument());
                    play.expect(play.getByText("Compile")).toBeInTheDocument();
                    play.waitFor(() -> play.expect(play.getByText("3 streams waiting")).toBeInTheDocument());
                    play.waitFor(() -> play.expect(play.getByText("1 session parked")).toBeInTheDocument());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByRole("button", "Add scorer")).not().toHaveAttribute("aria-disabled");
                    DocEditors.expectNoProblems(play);
                })
                // Read only: nothing on the Score tab can be changed
                .story("ReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    play.findByRole("button", "Save (read only)");
                    play.waitFor(() -> play.expect(play.getByText("Business rules")).toBeInTheDocument());
                    play.expect(play.getByRole("button", "Add scorer")).toHaveAttribute("aria-disabled", "true");
                    play.click(play.getByText("Business rules"));
                    play.expect(play.getByRole("button", "Edit selected scorer"))
                            .toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The process header opens the stage's tab
                .story("ProcessHeaderOpensLearn", context -> render(context, false))
                .withPlay(play -> {
                    play.click(play.findByRole("button", TextMatch.startingWith("Learn")));
                    play.waitFor(() -> play.expect(play.querySelector(CHART)).not().toBeNull());
                    play.expect(play.getByRole("button", TextMatch.startingWith("Learn")))
                            .toHaveAttribute("aria-current", "step");
                    DocEditors.expectNoProblems(play);
                })
                // A scorer's dialog: a new weight, OK and Save write it
                .story("ScorerDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Business rules");
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
                // A scorer's dialog, cancelled: nothing changes
                .story("ScorerDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Business rules");
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
                .story("ScorerDialogRefusesBadNumber", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Business rules");
                    final Query weight = screen.querySelector(WEIGHT);
                    play.clear(weight);
                    play.type(weight, "heavy");
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.expect(screen.findByText("Must be a number of zero or more")).toBeInTheDocument();
                    play.expect(screen.getByText("Scorer", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.expect(weight).toHaveValue("heavy");
                    DocEditors.expectNoProblems(play);
                })
                // A business rule's assertion, added in its own dialog within the scorer's
                .story("AssertionDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Business rules");
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
                // An assertion's dialog, cancelled: the scorer keeps its one assertion
                .story("AssertionDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openScorer(play, "Business rules");
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
                // The Learn tab: the flow drawn, its first step's form beside it
                .story("Learn", context -> render(context, false))
                .withPlay(play -> {
                    openLearn(play);
                    play.expect(play.getByRole("listbox", "Learning flow")).toBeInTheDocument();
                    play.expect(play.getByRole("option", TextMatch.startingWith("Step 1: Chain"))).toBeInTheDocument();
                    play.expect(play.getByRole("option", TextMatch.startingWith("Step 2: Configure parser")))
                            .toBeInTheDocument();
                    play.expect(play.getByLabelText("Id")).toHaveValue("chain");
                    play.expect(play.getByRole("button", "Add step")).not().toHaveAttribute("aria-disabled");
                    DocEditors.expectNoProblems(play);
                })
                // The Learn tab, read only
                .story("LearnReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    openLearn(play);
                    play.expect(play.getByRole("button", "Add step")).toHaveAttribute("aria-disabled", "true");
                    play.expect(play.getByLabelText("Id")).toHaveAttribute("readonly");
                    DocEditors.expectNoProblems(play);
                })
                // The chart is one tab stop: the arrow keys move along Start, the steps and End, and
                // Enter selects, as a click does
                .story("ChartByKeyboard", context -> render(context, false))
                .withPlay(play -> {
                    openLearn(play);
                    final Query chart = play.getByRole("listbox", "Learning flow");
                    play.expect(chart).toHaveAttribute("tabindex", "0");
                    // A click on the first step focuses the chart there
                    play.click(play.getByRole("option", TextMatch.startingWith("Step 1:")));
                    play.keyboard("{ArrowDown}");
                    // The chart names its active option, which is marked while the chart has the focus
                    play.expect(chart).toHaveAttribute("aria-activedescendant", null);
                    play.expect(play.getByRole("option", TextMatch.startingWith("Step 2:")))
                            .toHaveClass("flow-chart__node--active");
                    play.keyboard("{Enter}");
                    play.waitFor(() -> play.expect(play.getByLabelText("Id")).toHaveValue("parse"));
                    play.expect(play.getByRole("option", TextMatch.startingWith("Step 2:")))
                            .toHaveAttribute("aria-selected", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A step's transition, added in its dialog: OK and Save write it
                .story("TransitionDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStep2(play);
                    play.click(play.getByRole("button", "Add transition"));
                    play.expect(screen.findByText("Transition", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Transition", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    save(play, "{\"flow\": {\"steps\": [{\"id\": \"chain\"}, "
                               + "{\"id\": \"parse\", \"transitions\": [{\"on\": \"COVERAGE_SHORT\"}, {}]}]}}");
                })
                // A transition's dialog, cancelled: the step has none
                .story("TransitionDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openStep2(play);
                    play.click(play.getByRole("button", "Add transition"));
                    play.expect(screen.findByText("Transition", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Transition", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // A built-in text loaded into one of the step's texts
                .story("BuiltInTextDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openLearn(play);
                    play.click(play.getAllByRole("button", "Load built-in").nth(0));
                    play.expect(screen.findByText("Load a built-in text", StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Load a built-in text",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    DocEditors.expectNoProblems(play);
                })
                // Loading a built-in text, cancelled: the document stays clean
                .story("BuiltInTextDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openLearn(play);
                    play.click(play.getAllByRole("button", "Load built-in").nth(0));
                    play.expect(screen.findByText("Load a built-in text", StroomDom.DIALOG_TITLE))
                            .toBeInTheDocument();
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Load a built-in text",
                            StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The time budget is a duration (5 minutes, read as 300000ms), saved in milliseconds
                .story("TimeBudgetIsADuration", context -> render(context, false))
                .withPlay(play -> {
                    openLearn(play);
                    // The document's learning settings are Start's
                    play.click(play.getByRole("option", TextMatch.startingWith("Start")));
                    final Play budget = play.within(play.findByRole("group", "Time budget"));
                    final Query amount = budget.getByRole("spinbutton", "Amount");
                    play.waitFor(() -> play.expect(amount).toHaveValue("5"));
                    play.click(budget.getByTitle("Increase value"));
                    play.waitFor(() -> play.expect(amount).toHaveValue("6"));
                    save(play, "{\"attemptBudgetMs\": 360000, \"maxDeferralAgeMs\": 86400000}");
                });
    }

    // Selects a scorer's row and opens its dialog with Edit
    private static void openScorer(final Play play, final String scorer) {
        final Play screen = play.screen();
        play.click(play.findByText(scorer));
        play.click(play.getByRole("button", "Edit selected scorer"));
        play.expect(screen.findByText("Scorer", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
    }

    // Opens the Learn tab and waits for the flow
    private static void openLearn(final Play play) {
        play.waitFor(() -> play.expect(DocEditors.tab(play, "Learn")).toBeInTheDocument());
        play.click(DocEditors.tab(play, "Learn"));
        play.waitFor(() -> play.expect(play.getByRole("listbox", "Learning flow")).toBeInTheDocument());
    }

    // Opens the Learn tab on its second step (a Chain step has no transitions)
    private static void openStep2(final Play play) {
        openLearn(play);
        play.click(play.getByRole("option", TextMatch.startingWith("Step 2:")));
        play.waitFor(() -> play.expect(play.getByLabelText("Id")).toHaveValue("parse"));
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

    private static Widget render(final StoryContext context, final boolean readOnly) {
        final RestFixtures fixtures = ShapeshifterAiFixtures.editorRoutes(
                        DocEditors.docSelectionRoutes(DocEditors.permissionRoutes(RestFixtures.builder())))
                .build();
        final ShapeshifterAiResource resource = GWT.create(ShapeshifterAiResource.class);
        final String uuid = ShapeshifterAiFixtures.DOC_REF.getUuid();
        return DocEditors.render(context, fixtures, readOnly, (harness, injector) -> DocEditors.open(harness,
                ShapeshifterAiFixtures.DOC_REF,
                injector.getShapeshifterAiEditor(),
                DocResource.of(
                        restFactory -> restFactory.create(resource).method(res -> res.fetch(uuid)),
                        (restFactory, doc) -> restFactory.create(resource)
                                .method(res -> res.update(doc.getUuid(), doc)))));
    }
}
