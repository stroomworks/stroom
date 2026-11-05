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

import stroom.gwt.workbench.client.app.query.QueryFixtures;
import stroom.gwt.workbench.client.app.rest.RecordedRequest;
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

import java.util.ArrayList;
import java.util.List;

/// The StroomQL Table pane stories of `App/Editors/DashboardEditor` (see `DashboardEditorStories`):
/// a StroomQL Query pane's results shown by StroomQL Table panes (`StroomQLTablePaneController`),
/// each given its own copy of the query's table, with its own settings, and offered to a Text
/// pane as a table whose rows it shows.
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `POST /query/v1/search/{node}` (`QueryFixtures.SEARCH`) | running the query, and each table's refresh |
/// | `POST /query/v1/fetchDataSourceFromQueryString` | each table finding the query's data source |
public final class StroomQLTablePaneStories {

    // The tables' tabs
    private static final String TABLE_A = "Events A";
    private static final String TABLE_B = "Events B";

    // The query's results: two columns and two rows
    private static final String COLUMNS = "[{\"id\": \"f-name\", \"name\": \"Name\", \"visible\": true}, "
            + "{\"id\": \"f-count\", \"name\": \"Count\", \"visible\": true}]";
    private static final String ROWS = "[{\"values\": [\"alpha\", \"10\"], \"depth\": 0}, "
            + "{\"values\": [\"beta\", \"20\"], \"depth\": 0}]";

    private StroomQLTablePaneStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component(DashboardSupport.TITLE, StroomQLTablePaneStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // The query runs on open and asks for a copy of its table for each StroomQL Table, with
                // each table's own preferences (Events B shows 50 rows to a page), and each shows its rows
                .story("StroomQLTable", DashboardSupport.story(DashboardDocs.STROOMQL_TABLE_DASHBOARD,
                        StroomQLTablePaneStories::searchRoutes))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH
                                    .withBody("a copy for each table, Events B's with its page size", body ->
                                            List.of("st1", "st2").equals(componentIds(body))
                                            && isNumber(DashboardPlays.at(body,
                                                    "componentRequests", 1, "queryTablePreferences", "pageSize"), 50))
                                    .toSpyMatcher()));
                    expectRows(play, TABLE_A);
                    expectRows(play, TABLE_B);
                    DashboardPlays.expectNoProblems(play);
                })
                // Read only (the user may only view the dashboard): the tables still show the results
                .story("StroomQLTableReadOnly", DashboardSupport.story(DashboardDocs.STROOMQL_TABLE_DASHBOARD,
                        StroomQLTablePaneStories::searchRoutes,
                        options -> options.setup((harness, injector) ->
                                harness.getSecurityContext().setDocumentPermission(DocumentPermission.VIEW))))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    expectRows(play, TABLE_A);
                    expectRows(play, TABLE_B);
                    DashboardPlays.expectNoProblems(play);
                })
                // With no StroomQL Query on the dashboard, the table says to choose one
                .story("StroomQLTableNoQuery", DashboardSupport.story(
                        DashboardDocs.STROOMQL_TABLE_NO_QUERY_DASHBOARD, routes -> {
                        }))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    play.expect(play.findByText(TextMatch.containing("Choose the StroomQL Query")))
                            .toBeInTheDocument();
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).not().toHaveBeenCalledWith(
                            QueryFixtures.SEARCH.toSpyMatcher());
                    DashboardPlays.expectNoProblems(play);
                })
                // A table refreshed on its own (here by resetting its customisation) asks for its own copy
                // of the results alone, from the search that ran
                .story("StroomQLTableRefresh", DashboardSupport.story(DashboardDocs.STROOMQL_TABLE_DASHBOARD,
                        StroomQLTablePaneStories::searchRoutes))
                .withPlay(play -> {
                    DashboardPlays.opened(play);
                    expectRows(play, TABLE_A);
                    play.click(play.getAllByRole("button", "Reset Table Customisation").nth(0));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH
                                    .withBody("Events A's copy alone, from the search that ran", body ->
                                            List.of("st1").equals(componentIds(body))
                                            && QueryFixtures.QUERY_KEY.equals(
                                                    DashboardPlays.at(body, "queryKey", "uuid")))
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Settings, OK: the Basic tab names the query the table shows and its page size, which
                // Save writes
                .story("StroomQLTableSettings", DashboardSupport.story(DashboardDocs.STROOMQL_TABLE_DASHBOARD,
                        StroomQLTablePaneStories::searchRoutes))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    expectRows(play, TABLE_A);
                    DashboardPlays.openTabMenu(play, TABLE_A);
                    play.click(DashboardPlays.menuItem(screen, "Settings"));
                    final Play dialog = DashboardPlays.dialog(screen, "Settings");
                    play.expect(dialog.getByText("Conditional Formatting", StroomDom.LINK_TAB_LABEL))
                            .toBeInTheDocument();
                    play.expect(dialog.getByText("Selection Filter", StroomDom.LINK_TAB_LABEL))
                            .toBeInTheDocument();
                    play.expect(dialog.getByLabelText("Query")).toHaveValue("Events Query (sq1)");
                    final Query pageSize = dialog.getByLabelText("Table Page Size");
                    play.expect(pageSize).toHaveValue("100");
                    play.clear(pageSize);
                    play.type(pageSize, "25");
                    play.click(dialog.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.getByRole("button", "Save")).toBeEnabled());
                    play.click(play.getByRole("button", "Save"));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.put("/dashboard/v1/dash-st")
                                    .withBody("Events A's query and page size", body -> {
                                        final Object settings = DashboardPlays.at(body,
                                                "dashboardConfig", "components", 2, "settings");
                                        return "sq1".equals(DashboardPlays.at(settings, "queryId"))
                                               && isNumber(DashboardPlays.at(settings,
                                                "queryTablePreferences", "pageSize"), 25);
                                    })
                                    .toSpyMatcher()));
                    DashboardPlays.expectNoProblems(play);
                })
                // Settings, Cancel: nothing changes
                .story("StroomQLTableSettingsCancel", DashboardSupport.story(
                        DashboardDocs.STROOMQL_TABLE_DASHBOARD, StroomQLTablePaneStories::searchRoutes))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    expectRows(play, TABLE_A);
                    DashboardPlays.openTabMenu(play, TABLE_A);
                    play.click(DashboardPlays.menuItem(screen, "Settings"));
                    final Play dialog = DashboardPlays.dialog(screen, "Settings");
                    final Query name = dialog.getByLabelText("Name");
                    play.clear(name);
                    play.type(name, "Renamed Table");
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Settings", StroomDom.DIALOG_TITLE)).toBeNull());
                    play.expect(DashboardPlays.tab(play, TABLE_A)).toBeInTheDocument();
                    play.expect(play.queryByText("Renamed Table", DashboardPlays.COMPONENT_TAB)).toBeNull();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DashboardPlays.expectNoProblems(play);
                })
                // A Text pane offers the StroomQL Tables as tables whose selected rows it shows, and keeps the
                // one it shows when the dashboard links its panes
                .story("StroomQLTableInTextSettings", DashboardSupport.story(
                        DashboardDocs.STROOMQL_TABLE_DASHBOARD, StroomQLTablePaneStories::searchRoutes))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    DashboardPlays.openTabMenu(play, "Event Text");
                    play.click(DashboardPlays.menuItem(screen, "Settings"));
                    final Play dialog = DashboardPlays.dialog(screen, "Settings");
                    play.expect(dialog.getByLabelText("Table")).toHaveValue(TABLE_A + " (st1)");
                    play.click(dialog.getByRole("button", StroomDom.button("Cancel")));
                    DashboardPlays.expectNoProblems(play);
                })
                // Design mode's Add Component offers a StroomQL Table, which shows the dashboard's StroomQL
                // Query's results; as the query has already run, it runs again to make the new table's copy
                .story("StroomQLTableAdd", DashboardSupport.story(DashboardDocs.STROOMQL_TABLE_DASHBOARD,
                        StroomQLTablePaneStories::searchRoutes))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    DashboardPlays.opened(play);
                    expectRows(play, TABLE_A);
                    DashboardPlays.designMode(play, true);
                    play.click(play.findByRole("button", "Add Component"));
                    play.click(DashboardPlays.simpleMenuItem(screen, "StroomQL Table"));
                    // The new component follows the mouse until it is dropped onto a panel
                    play.click(play.querySelectorAll(DashboardPlays.PANEL).nth(0));
                    final Play settings = DashboardPlays.dialog(screen, "Settings");
                    play.expect(settings.getByLabelText("Query")).toHaveValue("Events Query (sq1)");
                    play.click(settings.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            QueryFixtures.SEARCH
                                    .withBody("a copy for each of the three tables", body ->
                                            componentIds(body).size() == 3)
                                    .toSpyMatcher()));
                    expectRows(play, "StroomQL Table");
                    DashboardPlays.expectNoProblems(play);
                });
    }

    // Waits for the table to show the query's two rows. The table is named after its pane (a results
    // table with an expander column is a treegrid, so it is found by its name alone)
    private static void expectRows(final Play play, final String table) {
        final Query grid = play.querySelector(".data-grid__scroller[aria-label=\"" + table + "\"]");
        play.waitFor(() -> play.expect(grid).toBeInTheDocument());
        final Play rows = play.within(grid);
        play.waitFor(() -> play.expect(rows.queryByText("alpha")).toBeInTheDocument());
        play.expect(rows.getByText("beta")).toBeInTheDocument();
    }

    // Whether a JSON value is the number
    private static boolean isNumber(final Object value, final int number) {
        return value instanceof final Number n && n.intValue() == number;
    }

    // The search replies with the query's table for each table the request asks for (or the query's own
    // table, if it asks for none); a table finding the query's data source finds none
    private static void searchRoutes(final RestFixtures.Builder routes) {
        routes.route(QueryFixtures.SEARCH, StroomQLTablePaneStories::search)
                .post("/query/v1/fetchDataSourceFromQueryString", RestReply.json("null"));
    }

    private static RestReply search(final RecordedRequest request) {
        final List<String> ids = componentIds(request.getBody());
        final List<String> results = new ArrayList<>();
        for (final String id : ids.isEmpty()
                ? List.of("table")
                : ids) {
            results.add(QueryFixtures.tableResult(id, COLUMNS, ROWS, 2));
        }
        return RestReply.json(QueryFixtures.response(true, results.toArray(new String[0])));
    }

    // The ids of the tables a StroomQL search request asks for a copy of the results for
    private static List<String> componentIds(final String body) {
        final List<String> ids = new ArrayList<>();
        if (DashboardPlays.at(body, "componentRequests") instanceof final List<?> list) {
            for (final Object componentRequest : list) {
                ids.add(String.valueOf(DashboardPlays.at(componentRequest, "componentId")));
            }
        }
        return ids;
    }
}
