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

package stroom.gwt.workbench.client.app.dependency;

import stroom.dependency.client.component.StructureGraphController;
import stroom.dependency.client.component.StructureGraphView;
import stroom.dependency.client.component.StructureGraphViewStore;
import stroom.docref.DocRef;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.explorer.client.event.LocateDocEvent;
import stroom.gwt.workbench.client.app.gin.AppScreenGinjector;
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

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Folder/Structure` stories, showing Stroom's real [StructureGraphController] (a folder's
/// Structure view: its documents and how they depend on each other, as a graph) with fake REST replies.
///
/// The controller asks `DependencyGraphResource` for the graph (`POST /dependency/v1/graph`), which each
/// story answers with one of [StructureGraphFixtures], most of them the layout's own test scenarios. The
/// controller comes from GIN.
///
/// The view (Graph, Table or Both) the user chooses is kept in the browser's local storage, which the
/// stories share, so each story forgets it when it starts and when it ends, and opens on the graph.
public final class StructureGraphStories {

    private static final String GRAPH_PATH = "/dependency/v1/graph";
    private static final DocRef FOLDER = new DocRef("Folder", "folder-uuid-1", "Event Sources");
    private static final String ON_OPEN = "onOpen";
    private static final String ON_LOCATE = "onLocate";

    private static final String SELECTED = "structure-graph__node--selected";
    private static final String DIMMED = "structure-graph__node--dimmed";
    private static final String FOCUSED = "structure-graph__node--focused";
    private static final String ELEMENT_NAME = ".structure-graph__element-name";

    private StructureGraphStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Folder/Structure", StructureGraphStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // A folder of event sources: four chains of uneven length into one index, fitted so the
                // first lanes are readable
                .story("Default", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.post(GRAPH_PATH)
                                    .withJsonBodyContaining("{\"root\": {\"uuid\": \"folder-uuid-1\"}}")
                                    .toSpyMatcher());
                    // A referenced document is drawn once in each lane that uses it
                    play.expect(play.querySelectorAll("[data-id^='ref:'][data-id$=':sch']")).toHaveLength(4);
                    play.expect(play.querySelectorAll(".structure-graph__ghost")).toHaveLength(0);
                    // The graph is shown alone; the table of its documents is a choice of view away,
                    // which the canvas's description says
                    play.expect(play.getByRole("radio", "Graph")).toBeChecked();
                    play.expect(play.querySelector(".structure-graph__table")).not().toBeVisible();
                    play.expect(play.querySelector(".structure-graph__canvas"))
                            .toHaveAccessibleDescription(TextMatch.containing("choose Table or Both under View"));
                    expectNoProblems(play);
                })
                // An empty folder shows create slots where its documents would go
                .story("EmptyFolder", context -> render(context, RestReply.json(StructureGraphFixtures.EMPTY)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelectorAll(".structure-graph__ghost"))
                            .toHaveLength(7));
                    play.expect(play.querySelector(".structure-graph__drawing"))
                            .toHaveAttribute("aria-label", "Structure of the folder: it has no documents");
                    expectNoProblems(play);
                })
                .story("OneDocument", context -> render(context, RestReply.json(StructureGraphFixtures.SINGLE)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "ref:0:only")).toBeInTheDocument());
                    expectNoProblems(play);
                })
                // Reprocessing: the edge that closes the loop is drawn as a back edge
                .story("Cycle", context -> render(context, RestReply.json(StructureGraphFixtures.CYCLE)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "a")).toBeInTheDocument());
                    play.expect(play.querySelectorAll(".structure-graph__edge--back")).toHaveLength(1);
                    expectNoProblems(play);
                })
                .story("DeepChain", context -> render(context, RestReply.json(StructureGraphFixtures.DEEP)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "idx")).toBeInTheDocument());
                    expectNoProblems(play);
                })
                .story("TwelveLanes", context -> render(context, RestReply.json(StructureGraphFixtures.WIDE)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "r0")).toBeInTheDocument());
                    expectNoProblems(play);
                })
                // An XSLT imports a name two XSLTs have: both edges are drawn as warnings, and the hover
                // card says why
                .story("AmbiguousReference", context ->
                        render(context, RestReply.json(StructureGraphFixtures.AMBIGUOUS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(play.querySelectorAll(".structure-graph__edge--ambiguous"))
                            .toHaveLength(2));
                    play.hover(body(play, "ref:0:xs"));
                    hoverCard(play).findByText("Refers to a name that more than one document has");
                    // Said in the table too, so not only to a mouse user
                    play.expect(table(play).getByText("Refers to a name that more than one document has"))
                            .toBeInTheDocument();
                    expectNoProblems(play);
                })
                .story("NodeLimitReached", context ->
                        render(context, RestReply.json(StructureGraphFixtures.LIMIT_REACHED)))
                .withPlay(play -> {
                    play.findByText("This folder has too many documents to show them all; only the first 2 "
                                    + "are shown.");
                    expectNoProblems(play);
                })
                // While the graph loads the tab says so
                .story("Loading", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS).delayed(500)))
                .withPlay(play -> {
                    play.findByText("Loading the folder's structure…");
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.expect(play.queryByText("Loading the folder's structure…")).toBeNull();
                    expectNoProblems(play);
                })
                // A failure is said in the tab, not as an alert: the tab is opened with every folder
                .story("LoadFails", context ->
                        render(context, RestReply.error(500, "The database is unavailable")))
                .withPlay(play -> {
                    play.findByText("The folder's structure could not be loaded: The database is unavailable");
                    expectNoProblems(play);
                })
                // Selecting a node highlights its chain and dims the rest; Escape clears it
                .story("Select", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.click(body(play, "fw_raw"));
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toHaveClass(SELECTED));
                    // Downstream of the firewall feed, so in its chain
                    play.expect(node(play, "fw_par")).not().toHaveClass(DIMMED);
                    play.expect(node(play, "idx")).not().toHaveClass(DIMMED);
                    // Another source, which only shares the index downstream
                    play.expect(node(play, "dns_raw")).toHaveClass(DIMMED);
                    play.keyboard("{Escape}");
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).not().toHaveClass(SELECTED));
                    play.expect(node(play, "dns_raw")).not().toHaveClass(DIMMED);
                    expectNoProblems(play);
                })
                // A lane's control folds it into one summary, and clicking the summary opens it again
                .story("CollapseLane", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.click(play.querySelector("[data-lane='0'] .structure-graph__lane-toggle-box"));
                    play.waitFor(() -> play.expect(node(play, "lane:0")).toBeInTheDocument());
                    play.click(body(play, "lane:0"));
                    play.waitFor(() -> play.expect(node(play, "lane:0")).not().toBeInTheDocument());
                    expectNoProblems(play);
                })
                // A pipeline's toggle shows its elements, each named (by the name given it, else its id) with
                // its type, in the order data flows through them; the table says the same in words
                .story("ExpandPipeline", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS_WITH_ELEMENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_par")).toBeInTheDocument());
                    play.click(play.querySelector("[data-id='fw_par'] .structure-graph__toggle-hit"));
                    play.waitFor(() -> play.expect(node(play, "fw_par"))
                            .toHaveClass("structure-graph__node--expanded"));
                    play.expect(play.querySelectorAll("[data-id='fw_par'] .structure-graph__chip")).toHaveLength(7);
                    play.expect(play.querySelectorAll("[data-id='fw_par'] .structure-graph__element-name"))
                            .toHaveLength(7);
                    // Each element's full name and type is its tooltip; what is drawn is cut to fit the zoom
                    final Play pipeline = play.within(node(play, "fw_par"));
                    final String titles = "[data-id='fw_par'] .structure-graph__element > title";
                    play.expect(play.querySelectorAll(titles)).toHaveLength(7);
                    play.expect(play.querySelectorAll(titles).nth(0)).toHaveTextContent("dsParser (DSParser)");
                    play.expect(play.querySelectorAll(titles).nth(2))
                            .toHaveTextContent("FW Translation (XSLTFilter)");
                    play.expect(play.querySelectorAll(titles).nth(6))
                            .toHaveTextContent("streamAppender (StreamAppender)");
                    // The element's name, not its id, is drawn, however much of it fits
                    play.expect(pipeline.getByText(TextMatch.startingWith("FW Tr"), ELEMENT_NAME))
                            .toBeInTheDocument();
                    play.expect(table(play).getByText(TextMatch.containing(
                            "7 elements: dsParser (DSParser), readRecordCountFilter (RecordCountFilter), "
                            + "FW Translation (XSLTFilter)"))).toBeInTheDocument();
                    expectNoProblems(play);
                })
                // Finding documents marks them, and says how many match
                .story("Search", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    final Query search = play.getByLabelText("Find documents in the graph");
                    play.type(search, "parser");
                    // The four parsers and their template
                    play.waitFor(() -> play.expect(play.querySelectorAll(".structure-graph__node--match"))
                            .toHaveLength(5));
                    play.expect(play.getByRole("status")).toHaveTextContent("5 documents match");
                    play.clear(search);
                    play.type(search, "no such document");
                    play.waitFor(() -> play.expect(play.getByRole("status"))
                            .toHaveTextContent("No documents match"));
                    play.expect(play.querySelectorAll(".structure-graph__node--match")).toHaveLength(0);
                    expectNoProblems(play);
                })
                // The hover card describes the node under the pointer
                .story("Hover", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.hover(body(play, "fw_raw"));
                    hoverCard(play).findByText("Stream type: RAW_EVENTS");
                    // It stays while the pointer is over it, so it can be read (WCAG 1.4.13)
                    play.hover(play.querySelector(".structure-graph__hover-card"));
                    play.expect(play.querySelector(".structure-graph__hover-card")).toBeVisible();
                    play.unhover(play.querySelector(".structure-graph__hover-card"));
                    expectNoProblems(play);
                })
                // The view: Table shows the table of every document, a row each, in place of the graph;
                // Both shows the graph above it; Graph hides it again
                .story("Table", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.expect(play.querySelector(".structure-graph__canvas")).toBeVisible();
                    chooseView(play, "Table");
                    play.waitFor(() -> play.expect(play.querySelector(".structure-graph__canvas"))
                            .not().toBeVisible());
                    play.expect(play.getByRole("radio", "Table")).toBeChecked();
                    play.expect(table(play).getByText("Event Parser Template")).toBeVisible();
                    play.expect(play.querySelectorAll(".structure-graph__table [data-row]")).toHaveLength(44);
                    chooseView(play, "Both");
                    play.waitFor(() -> play.expect(play.querySelector(".structure-graph__canvas")).toBeVisible());
                    play.expect(table(play).getByText("Event Parser Template")).toBeVisible();
                    play.expect(play.querySelector(".structure-graph__canvas"))
                            .toHaveAccessibleDescription(TextMatch.containing("The table that follows lists"));
                    chooseView(play, "Graph");
                    play.waitFor(() -> play.expect(play.querySelector(".structure-graph__table")).not().toBeVisible());
                    play.expect(play.querySelector(".structure-graph__canvas")).toBeVisible();
                    expectNoProblems(play);
                })
                // The view last chosen is remembered, so the next Structure view opens on it
                .story("OpensOnTheRememberedView", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS), StructureGraphView.TABLE))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(table(play).getByText("Event Parser Template")).toBeVisible());
                    play.expect(play.getByRole("radio", "Table")).toBeChecked();
                    play.expect(play.querySelector(".structure-graph__canvas")).not().toBeVisible();
                    expectNoProblems(play);
                })
                // Shown with the graph, the keyboard moves through the table under the canvas, and the
                // canvas rings the node of
                // the row with the focus; selecting a row selects its node
                .story("KeyboardFollowsTheTable", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "tmpl")).toBeInTheDocument());
                    chooseView(play, "Both");
                    play.click(table(play).getByText("Event Parser Template"));
                    play.waitFor(() -> play.expect(node(play, "tmpl")).toHaveClass(FOCUSED));
                    play.expect(node(play, "tmpl")).toHaveClass(SELECTED);
                    // The next row is the next lane's first document
                    play.keyboard("{ArrowDown}");
                    play.waitFor(() -> play.expect(node(play, "pidx")).toHaveClass(FOCUSED));
                    play.expect(node(play, "tmpl")).not().toHaveClass(FOCUSED);
                    expectNoProblems(play);
                })
                // A row's document opens on a double click, as a node's does
                .story("TableOpensDocument", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "tmpl")).toBeInTheDocument());
                    chooseView(play, "Table");
                    play.dblClick(table(play).getByText("Event Parser Template"));
                    play.waitFor(() -> play.expect(play.spy(ON_OPEN))
                            .toHaveBeenCalledWith("Pipeline Event Parser Template"));
                    expectNoProblems(play);
                })
                // A row's Actions button shows its document's actions; Locate finds it in the explorer
                .story("ActionMenu", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "tmpl")).toBeInTheDocument());
                    chooseView(play, "Table");
                    final Play row = play.within(table(play).getByText("Event Parser Template")
                            .closest(StroomDom.GRID_ROW));
                    // Each row's button is named for its document, as its menu is
                    play.click(row.getByTitle("Actions for Pipeline 'Event Parser Template'"));
                    // Menus show on the page's body, so are found on the whole page
                    final Play screen = play.screen();
                    screen.findByRole("menu", "Actions for Pipeline 'Event Parser Template'");
                    play.expect(screen.getByText("Open")).toBeVisible();
                    play.expect(screen.getByText("Show Dependencies")).toBeVisible();
                    play.expect(screen.getByText("Show Dependants")).toBeVisible();
                    play.click(screen.getByText("Locate in Explorer"));
                    play.waitFor(() -> play.expect(play.spy(ON_LOCATE))
                            .toHaveBeenCalledWith("Pipeline Event Parser Template"));
                    expectNoProblems(play);
                })
                // A row's Actions menu expands a pipeline, so it can be done from the keyboard
                .story("ExpandFromTheTable", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_par")).toBeInTheDocument());
                    chooseView(play, "Both");
                    // Each row's Actions button is named for its document, so it is found by that alone
                    play.click(table(play).getByTitle("Actions for Pipeline 'FW Events Parser'"));
                    play.click(play.screen().findByText("Show Pipeline Elements"));
                    play.waitFor(() -> play.expect(node(play, "fw_par"))
                            .toHaveClass("structure-graph__node--expanded"));
                    // A graph whose pipelines' elements are counted but not named draws them unnamed
                    play.expect(play.querySelectorAll("[data-id='fw_par'] .structure-graph__chip")).toHaveLength(7);
                    play.expect(play.querySelectorAll("[data-id='fw_par'] .structure-graph__element-name"))
                            .toHaveLength(0);
                    expectNoProblems(play);
                })
                // A node's context menu has the same actions
                .story("CanvasContextMenu", context ->
                        render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.rightClick(body(play, "fw_raw"));
                    final Play screen = play.screen();
                    screen.findByRole("menu", "Actions for Feed 'FW-LOGS:RAW_EVENTS'");
                    play.click(screen.getByText("Open"));
                    play.waitFor(() -> play.expect(play.spy(ON_OPEN)).toHaveBeenCalledWith("Feed FW-LOGS:RAW_EVENTS"));
                    expectNoProblems(play);
                })
                // Double-clicking a node opens its document
                .story("OpenDocument", context -> render(context, RestReply.json(StructureGraphFixtures.EVENTS)))
                .withPlay(play -> {
                    play.waitFor(() -> play.expect(node(play, "fw_raw")).toBeInTheDocument());
                    play.dblClick(body(play, "fw_raw"));
                    play.waitFor(() -> play.expect(play.spy(ON_OPEN)).toHaveBeenCalledWith("Feed FW-LOGS:RAW_EVENTS"));
                    expectNoProblems(play);
                });
    }

    private static Query node(final Play play, final String id) {
        return play.querySelector("[data-id='" + id + "']");
    }

    private static Play hoverCard(final Play play) {
        return play.within(play.querySelector(".structure-graph__hover-card"));
    }

    // Chooses a view (Graph, Table or Both) by clicking its option, as the user does
    private static void chooseView(final Play play, final String label) {
        play.click(play.within(play.getByRole("radiogroup", "View")).getByText(label));
    }

    private static Play table(final Play play) {
        return play.within(play.querySelector(".structure-graph__table"));
    }

    private static Query body(final Play play, final String id) {
        return play.querySelector("[data-id='" + id + "'] .structure-graph__body");
    }

    private static void expectNoProblems(final Play play) {
        play.expect(play.spy(ScreenHarness.ALERT_SPY)).not().toHaveBeenCalled();
        play.expect(play.spy(ScreenHarness.UNHANDLED_REQUEST_SPY)).not().toHaveBeenCalled();
    }

    private static Widget render(final StoryContext context, final RestReply reply) {
        return render(context, reply, null);
    }

    // Opens on the view given, as if the user had chosen it in an earlier Structure view, or on the
    // default if it is null
    private static Widget render(final StoryContext context,
                                 final RestReply reply,
                                 final StructureGraphView rememberedView) {
        // The choice is kept in local storage, which the stories share
        final StructureGraphViewStore viewStore = new StructureGraphViewStore();
        viewStore.clear();
        if (rememberedView != null) {
            viewStore.set(rememberedView);
        }
        final RestFixtures fixtures = RestFixtures.builder()
                .post(GRAPH_PATH, reply)
                .build();
        final AppScreenGinjector injector = GWT.create(AppScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, fixtures)
                .injector(injector)
                .build();
        harness.addRegistration(viewStore::clear);
        harness.fn(ON_OPEN);
        harness.fn(ON_LOCATE);
        harness.addRegistration(harness.getEventBus().addHandler(LocateDocEvent.getType(), event ->
                harness.spy(ON_LOCATE, event.getDocRef().getType() + " " + event.getDocRef().getName())));
        harness.addRegistration(harness.getEventBus().addHandler(OpenDocumentEvent.getType(), event ->
                harness.spy(ON_OPEN, event.getDocRef().getType() + " " + event.getDocRef().getName())));
        // As the folder's Structure tab will: show the view, then read the folder into it
        harness.afterStartUp(() -> {
            final StructureGraphController controller = harness.show(injector.getStructureGraphController());
            controller.read(FOLDER);
        });
        return harness.asWidget();
    }
}
