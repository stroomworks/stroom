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

package stroom.gwt.workbench.client.app.dashboard;

import stroom.document.client.event.ShowCreateDocumentDialogEvent;
import stroom.gwt.workbench.client.app.query.QueryFixtures;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.play.TextMatch;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.security.shared.DocumentPermission;


/// The StroomQL Query pane stories of `App/Editors/DashboardEditor` (see `DashboardEditorStories`):
/// a StroomQL query written in a dashboard (`StroomQLQueryPaneController`), with its help, run over
/// the dashboard's time range with the dashboard's parameters put into it, its settings, and the
/// analytic rule and report made from it.
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `POST /query/v1/search/{node}` (`QueryFixtures.SEARCH`) | running the query |
/// | `POST /query/v1/fetchDataSourceFromQueryString` | the Selection Query's fields |
/// | `POST /dataSource/v1/findFields` | the Selection Query's fields |
/// | `POST /query/v1/validateQuery` | Create Analytic Rule, Create Report |
/// | `POST /explorer/v2/getFromDocRef` | where the new rule or report goes |
public final class StroomQLQueryPaneStories {

    // The pane's tab
    private static final String PANE = "Events Query";

    // The pane's own Execute Query button (the dashboard's toolbar has one too)
    private static final String RUN_BUTTON = ".stroomql-query-pane .run-query-button__button";

    // The text of the pane's editor
    private static final String EDITOR_TEXT = ".stroomql-query-pane .ace_content";

    // The spy of the app's request to create a document (the new rule or report)
    private static final String CREATE_DOCUMENT = "createDocument";

    // A search reply with an error
    private static final String ERROR_RESPONSE = "{\"complete\": true, \"node\": \"" + QueryFixtures.NODE
            + "\", \"queryKey\": {\"uuid\": \"" + QueryFixtures.QUERY_KEY + "\"}, \"highlights\": [], "
            + "\"errorMessages\": [{\"severity\": \"ERROR\", \"message\": \"Unknown field 'Nmae'\"}], "
            + "\"results\": []}";

    private StroomQLQueryPaneStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component(DashboardSupport.TITLE, StroomQLQueryPaneStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The pane shows its query in its editor, beside the help, below its buttons
                .story("StroomQLQuery", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD, routes -> {
                }))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.expect(DashboardPlays.tab(play, PANE)).toBeInTheDocument();
                    play.waitFor(() -> play.expect(play.querySelector(EDITOR_TEXT).textContent())
                            .toMatch(TextMatch.containing("where Name = ${component.in1.param.name}")));
                    final Play pane = pane(play);
                    play.expect(pane.getByRole("button", "Create Analytic Rule")).toBeInTheDocument();
                    play.expect(pane.getByRole("button", "Create Report")).toBeInTheDocument();
                    play.expect(pane.getByRole("button", "Execute Query")).toBeInTheDocument();
                    // The help's quick filter
                    play.expect(pane.querySelector(".query-help")).toBeInTheDocument();
                    // With no messages, Show Errors holds its place but is hidden
                    play.expect(pane.queryByRole("button", "Show Errors")).toBeNull();
                    // Not run until asked
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            QueryFixtures.SEARCH.toSpyMatcher());
                    DashboardPlays.expectNoProblems(play);
                })
                // Execute Query runs the query with the Text Input's value put into it, and becomes
                // Stop Query while it runs, which stops it
                .story("StroomQLQueryRun", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD,
                        routes -> routes.route(QueryFixtures.SEARCH,
                                RestReply.json(QueryFixtures.response(false)),
                                // Later polls hang, so the search keeps running until it is stopped
                                RestReply.json(QueryFixtures.response(true)).delayed(600_000))))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.click(pane(play).getByRole("button", "Execute Query"));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH
                                    .withJsonBodyContaining("{\"searchRequestSource\": {\"componentId\": \"sq1\", "
                                                            + "\"componentName\": \"" + PANE + "\", "
                                                            + "\"ownerDocRef\": {\"uuid\": \"dash-sq\"}}}")
                                    .withBody("the parameter's value", body -> body.contains("Name = alpha")
                                                                               && !body.contains("${"))
                                    .toSpyMatcher()));
                    final Query stop = pane(play).findByRole("button", "Stop Query");
                    play.click(stop);
                    play.waitFor(() -> play.expect(pane(play).getByRole("button", "Execute Query"))
                            .toBeInTheDocument());
                    DashboardPlays.expectNoProblems(play);
                })
                // The dashboard's own Execute Query runs the pane's query too
                .story("StroomQLQueryRunAll", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD,
                        routes -> {
                        }))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    DashboardPlays.runAll(play);
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH
                                    .withJsonBodyContaining("{\"searchRequestSource\": {\"componentId\": \"sq1\"}}")
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Query On Open runs the query once the dashboard has opened, with no click
                .story("StroomQLQueryOnOpen", DashboardSupport.story(
                        DashboardDocs.stroomQLQueryDashboard(", \"automate\": {\"open\": true}"), routes -> {
                        }))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH
                                    .withJsonBodyContaining("{\"searchRequestSource\": {\"componentId\": \"sq1\"}}")
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // A search's messages are shown by the pane's Show Errors button, in an alert
                .story("StroomQLQueryErrors", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD,
                        routes -> routes.route(QueryFixtures.SEARCH, RestReply.json(ERROR_RESPONSE))))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    play.click(play.querySelector(RUN_BUTTON));
                    final Query showErrors = pane(play).findByRole("button", "Show Errors");
                    play.click(showErrors);
                    final Play alert = DashboardPlays.dialog(screen, "Alert");
                    play.expect(alert.findByText(TextMatch.containing("Unknown field 'Nmae'")))
                            .toBeInTheDocument();
                    play.click(alert.getByRole("button", StroomDom.button("Close")));
                    play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
                })
                // Changing the query changes the dashboard, whose Save writes the new query
                .story("StroomQLQueryEdit", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD,
                        routes -> {
                        }))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.waitFor(() -> play.expect(play.querySelector(EDITOR_TEXT).textContent())
                            .toMatch(TextMatch.containing("select Name")));
                    replaceQuery(play, "from index select Count");
                    play.waitFor(() -> play.expect(play.getByRole("button", "Save")).toBeEnabled());
                    play.click(play.getByRole("button", "Save"));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.put("/dashboard/v1/dash-sq")
                                    .withJsonBodyContaining("{\"dashboardConfig\": {\"components\": [{}, "
                                                            + "{\"id\": \"sq1\", \"settings\": "
                                                            + "{\"query\": \"from index select Count\"}}]}}")
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Read only (the user may only view the dashboard): the query can't be changed, but
                // can still be run
                .story("StroomQLQueryReadOnly", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD,
                        routes -> {
                        }, options -> options.setup((harness, injector) ->
                                harness.getSecurityContext().setDocumentPermission(DocumentPermission.VIEW))))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.waitFor(() -> play.expect(play.querySelector(EDITOR_TEXT).textContent())
                            .toMatch(TextMatch.containing("select Name")));
                    play.click(play.querySelector(EDITOR_TEXT));
                    play.keyboard("xyz");
                    play.sleep(300);
                    play.expect(play.querySelector(EDITOR_TEXT).textContent())
                            .not().toMatch(TextMatch.containing("xyz"));
                    play.click(play.querySelector(RUN_BUTTON));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH.toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Settings, OK: the Basic tab renames the pane and makes it run on open, which Save
                // writes
                .story("StroomQLQuerySettings", DashboardSupport.story(DashboardDocs.STROOMQL_QUERY_DASHBOARD,
                        routes -> {
                        }))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    DashboardPlays.openTabMenu(play, PANE);
                    play.click(DashboardPlays.menuItem(screen, "Settings"));
                    final Play dialog = DashboardPlays.dialog(screen, "Settings");
                    play.expect(dialog.getByText("Selection Query", StroomDom.LINK_TAB_LABEL)).toBeInTheDocument();
                    final Query name = dialog.getByLabelText("Name");
                    play.expect(name).toHaveValue(PANE);
                    play.clear(name);
                    play.type(name, "Renamed Query");
                    play.click(dialog.getByText("Query On Open"));
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(DashboardPlays.tab(play, "Renamed Query")).toBeInTheDocument());
                    play.waitFor(() -> play.expect(play.getByRole("button", "Save")).toBeEnabled());
                    play.click(play.getByRole("button", "Save"));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.put("/dashboard/v1/dash-sq")
                                    .withJsonBodyContaining("{\"dashboardConfig\": {\"components\": [{}, "
                                                            + "{\"id\": \"sq1\", \"name\": \"Renamed Query\", "
                                                            + "\"settings\": {\"automate\": {\"open\": true}, "
                                                            + "\"query\": \"" + DashboardDocs.STROOMQL_QUERY
                                                            + "\"}}]}}")
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Settings, Cancel: nothing changes
                .story("StroomQLQuerySettingsCancel", DashboardSupport.story(
                        DashboardDocs.STROOMQL_QUERY_DASHBOARD, routes -> {
                        }))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    DashboardPlays.openTabMenu(play, PANE);
                    play.click(DashboardPlays.menuItem(screen, "Settings"));
                    final Play dialog = DashboardPlays.dialog(screen, "Settings");
                    final Query name = dialog.getByLabelText("Name");
                    play.clear(name);
                    play.type(name, "Renamed Query");
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Settings", StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(DashboardPlays.tab(play, PANE)).toBeInTheDocument();
                    play.expect(play.queryByText("Renamed Query", DashboardPlays.COMPONENT_TAB)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DashboardPlays.expectNoProblems(play);
                })
                // The Selection Query's fields are those of the data source the query reads, found
                // from the query's text
                .story("StroomQLQuerySelectionQueryFields", DashboardSupport.story(
                        DashboardDocs.STROOMQL_QUERY_DASHBOARD, routes ->
                                routes.route(RequestMatcher.post("/dataSource/v1/findFields"), RestReply.json(
                                        "{\"values\": [], \"pageResponse\": {\"offset\": 0, \"length\": 0, "
                                        + "\"total\": 0, \"exact\": true}}"))))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    DashboardPlays.openTabMenu(play, PANE);
                    play.click(DashboardPlays.menuItem(screen, "Settings"));
                    final Play settings = DashboardPlays.dialog(screen, "Settings");
                    play.click(settings.getByText("Selection Query", StroomDom.LINK_TAB_LABEL));
                    play.click(settings.findAllByTitle("New Selection Handler").nth(0));
                    final Play handler = DashboardPlays.dialog(screen, "Add New Selection Handler");
                    play.click(handler.getByTitle("Add Term"));
                    final Query fieldPicker = handler.querySelector(
                            ".term-editor__item--field " + StroomDom.SELECTION_BOX);
                    play.waitFor(() -> play.expect(fieldPicker).toBeInTheDocument());
                    play.click(fieldPicker);
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/query/v1/fetchDataSourceFromQueryString")
                                    .withBodyContaining("where Name = ${component.in1.param.name}")
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Create Analytic Rule and Create Report check the query as it runs (with the Text
                // Input's value put into it) and ask for the new document beside the dashboard,
                // named after the dashboard and the pane
                .story("StroomQLQueryCreateRuleAndReport", DashboardSupport.story(
                        DashboardDocs.STROOMQL_QUERY_DASHBOARD, StroomQLQueryPaneStories::createRoutes,
                        StroomQLQueryPaneStories::createProbe))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.click(pane(play).getByRole("button", "Create Analytic Rule"));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/query/v1/validateQuery")
                                    .withBody("the query as it runs", body -> body.contains("Name = alpha")
                                                                              && !body.contains("${"))
                                    .toSpyMatcher()));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post("/explorer/v2/getFromDocRef")
                                    .withJsonBodyContaining("{\"uuid\": \"dash-sq\"}")
                                    .toSpyMatcher()));
                    play.waitFor(() -> play.expect(play.spy(CREATE_DOCUMENT)).toHaveBeenCalledWith(
                            "AnalyticRule: StroomQL - " + PANE));
                    play.click(pane(play).getByRole("button", "Create Report"));
                    play.waitFor(() -> play.expect(play.spy(CREATE_DOCUMENT)).toHaveBeenCalledWith(
                            "Report: StroomQL - " + PANE));
                    DashboardPlays.expectNoProblems(play);
                })
                // Design mode's Add Component offers a StroomQL Query, which starts with no query
                .story("StroomQLQueryAdd", DashboardSupport.story(DashboardDocs.QUERY_TABLE_DASHBOARD, routes -> {
                }))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    DashboardPlays.designMode(play, true);
                    play.click(play.findByRole("button", "Add Component"));
                    play.click(DashboardPlays.simpleMenuItem(screen, "StroomQL Query"));
                    // The new component follows the mouse until it is dropped onto a panel
                    play.click(play.querySelectorAll(DashboardPlays.PANEL).nth(0));
                    final Play settings = DashboardPlays.dialog(screen, "Settings");
                    play.click(settings.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(DashboardPlays.tab(play, "StroomQL Query")).toBeInTheDocument());
                    play.expect(pane(play).getByRole("button", "Execute Query")).toBeInTheDocument();
                    DashboardPlays.expectNoProblems(play);
                });
    }

    // The pane
    private static Play pane(final Play play) {
        return play.within(play.querySelector(".stroomql-query-pane"));
    }

    // Replaces the pane's query by typing, as a user would: select all of it, then type over it
    private static void replaceQuery(final Play play, final String query) {
        play.click(play.querySelector(EDITOR_TEXT));
        play.waitFor(() -> play.expect(play.querySelector(".stroomql-query-pane .ace_text-input")).toHaveFocus());
        play.keyboard("{Control>}a{/Control}");
        play.keyboard(query);
        play.waitFor(() -> play.expect(play.querySelector(EDITOR_TEXT).textContent()).toBe(query));
    }

    // Create Analytic Rule / Create Report: the query's check and the dashboard's explorer node
    private static void createRoutes(final RestFixtures.Builder builder) {
        builder.post("/query/v1/validateQuery", RestReply.json("{\"ok\": true, \"groupBy\": false}"))
                .post("/explorer/v2/getFromDocRef", RestReply.json(
                        "{\"type\": \"Dashboard\", \"uuid\": \"dash-sq\", \"name\": \"StroomQL\"}"));
    }

    // Records the app's request to create the new document (Stroom's explorer would show its dialog)
    // as its type and the name it is first given ('AnalyticRule: name'), and gives the analytic rule
    // and report defaults
    private static void createProbe(final DashboardSupport.Options options) {
        options.harness(builder -> builder.uiConfig(QueryFixtures.uiConfigWith(
                        "\"analyticUiDefaultConfig\": {}, \"reportUiDefaultConfig\": {}")))
                .setup((harness, injector) -> {
                    harness.fn(CREATE_DOCUMENT);
                    harness.addRegistration(harness.getEventBus().addHandler(
                            ShowCreateDocumentDialogEvent.getType(),
                            event -> harness.spy(CREATE_DOCUMENT,
                                    event.getDocType() + ": " + event.getInitialDocName())));
                });
    }
}
