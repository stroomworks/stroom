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

package stroom.gwt.workbench.client.app.pipelinefactory;

import stroom.docref.DocRef;
import stroom.gwt.workbench.client.app.editors.DocEditors;
import stroom.gwt.workbench.client.app.editors.DocEditors.DocResource;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.app.screen.StroomDom;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.play.Query;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.pipelinefactory.client.component.FactoryPropertyEditorController;
import stroom.pipelinefactory.client.component.PipelineFactoryEditor;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

/// The `App/Editors/PipelineFactoryEditor` stories, showing Stroom's real [PipelineFactoryEditor] (a
/// Pipeline Factory's tab: Settings, Filter, Made, Documentation and Permissions) with fake REST
/// replies. As `PipelineFactoryPlugin` does, the story fetches the document and reads it into the
/// editor ([DocEditors#open]). Its dialog is a property value's ([FactoryPropertyEditorController]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET` and `PUT /pipelineFactory/v1/{uuid}` | the document, fetched and saved |
/// | `GET /pipelineFactory/v1/{uuid}/combinations` | the Made tab |
/// | `POST /explorer/v2/decorate` and `getFromDocRef` | the template's picker |
/// | the Permissions tab's routes | the Permissions tab |
public final class PipelineFactoryEditorStories {

    private static final DocRef DOC_REF = new DocRef(PipelineFactoryDoc.TYPE, "pf-1", "Firewall factory");
    private static final String SAVE_PATH = "/pipelineFactory/v1/pf-1";

    // PipelineFactoryResource.fetch(): a pipeline per feed from a template, with one property value
    private static final String DOC = """
            {"type": "PipelineFactory", "uuid": "pf-1", "name": "Firewall factory",
              "description": "# Firewall factory", "enabled": true,
              "template": {"type": "Pipeline", "uuid": "t-1", "name": "Template pipeline"},
              "key": ["Feed"], "folderPattern": "Made/${feed}", "namePattern": "${feed} pipeline",
              "properties": [{"elementId": "parser", "name": "textConverter", "value": "${feed}"}],
              "streamFilter": {"type": "operator", "op": "AND", "children": []},
              "filterSettings": {"enabled": true, "priority": 10, "autoPriority": false,
                "maxProcessingTasks": 0, "export": false}}""";

    // PipelineFactoryResource.combinations(): one feed met, and its pipeline made
    private static final String COMBINATIONS = """
            [{"filterUuid": "pf-1", "values": {"Feed": "FIREWALL"},
              "pipeline": {"type": "Pipeline", "uuid": "m-1", "name": "FIREWALL pipeline"},
              "pipelineExists": true, "state": "MADE", "tries": 1,
              "createTimeMs": 1761700000000, "updateTimeMs": 1761700000000}]""";

    private PipelineFactoryEditorStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/PipelineFactoryEditor", PipelineFactoryEditorStories.class)
                .layout(StoryLayout.FULLSCREEN)
                .story("Default", context -> render(context, false))
                .withPlay(play -> {
                    for (final String label : new String[]{
                            "Settings", "Filter", "Made", "Documentation", "Permissions"}) {
                        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
                    }
                    play.waitFor(() -> play.expect(play.getByLabelText("Folder")).toHaveValue("Made/${feed}"));
                    play.expect(play.getByText("textConverter")).toBeInTheDocument();
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                .story("ReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    play.findByRole("button", "Save (read only)");
                    play.waitFor(() -> play.expect(play.getByLabelText("Folder")).toHaveAttribute("readonly"));
                    play.expect(play.getByRole("button", "Add a property value"))
                            .toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                })
                // The filter that makes the pipelines' processor filters
                .story("Filter", context -> render(context, false))
                .withPlay(play -> {
                    openTab(play, "Filter");
                    play.waitFor(() -> play.expect(play.getByLabelText("Include in Export")).toBeInTheDocument());
                    DocEditors.expectNoProblems(play);
                })
                .story("FilterReadOnly", context -> render(context, true))
                .withPlay(play -> {
                    openTab(play, "Filter");
                    play.waitFor(() -> play.expect(play.getByLabelText("Include in Export")).toBeInTheDocument());
                    play.expect(play.getByText("Read only", ".link-tab-panel__read-only-note")).toBeVisible();
                    DocEditors.expectNoProblems(play);
                })
                // What the factory has met, and the pipeline it made for each
                .story("Made", context -> render(context, false))
                .withPlay(play -> {
                    openTab(play, "Made");
                    play.expect(play.findByText("FIREWALL pipeline")).toBeInTheDocument();
                    DocEditors.expectNoProblems(play);
                })
                // A property value added in its dialog: OK and Save write it
                .story("PropertyDialogOk", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openPropertyDialog(play);
                    play.type(screen.getByLabelText("Element"), "writer");
                    play.type(screen.getByLabelText("Property"), "outputPaths");
                    play.type(screen.getByLabelText("Value"), "/data/${{feed}");
                    play.tab();
                    play.click(screen.getByRole("button", StroomDom.button("OK")));
                    play.waitFor(() -> play.expect(screen.queryByText("Property value", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.waitFor(() -> play.expect(play.getByText("outputPaths")).toBeInTheDocument());
                    final Query save = play.getByRole("button", "Save");
                    play.waitFor(() -> play.expect(save).not().toHaveAttribute("aria-disabled"));
                    play.click(save);
                    play.waitFor(() -> play.expect(play.spy(ScreenHarness.REQUEST_SPY)).toHaveBeenCalledWith(
                            RequestMatcher.put(SAVE_PATH).withJsonBodyContaining(
                                    "{\"properties\": [{\"name\": \"textConverter\"}, "
                                    + "{\"elementId\": \"writer\", \"name\": \"outputPaths\", "
                                    + "\"value\": \"/data/${feed}\"}]}").toSpyMatcher()));
                    DocEditors.expectNoProblems(play);
                })
                .story("PropertyDialogCancel", context -> render(context, false))
                .withPlay(play -> {
                    final Play screen = play.screen();
                    openPropertyDialog(play);
                    play.type(screen.getByLabelText("Element"), "writer");
                    play.click(screen.getByRole("button", StroomDom.button("Cancel")));
                    play.waitFor(() -> play.expect(screen.queryByText("Property value", StroomDom.DIALOG_TITLE))
                            .toBeNull());
                    play.expect(play.getByRole("button", "Save")).toHaveAttribute("aria-disabled", "true");
                    DocEditors.expectNoProblems(play);
                });
    }

    private static void openTab(final Play play, final String label) {
        play.waitFor(() -> play.expect(DocEditors.tab(play, label)).toBeInTheDocument());
        play.click(DocEditors.tab(play, label));
    }

    private static void openPropertyDialog(final Play play) {
        play.click(play.findByRole("button", "Add a property value"));
        play.expect(play.screen().findByText("Property value", StroomDom.DIALOG_TITLE)).toBeInTheDocument();
    }

    private static Widget render(final StoryContext context, final boolean readOnly) {
        final RestFixtures fixtures = DocEditors.docSelectionRoutes(DocEditors.permissionRoutes(RestFixtures.builder()))
                .get("/pipelineFactory/v1/pf-1/combinations", RestReply.json(COMBINATIONS))
                .get("/pipelineFactory/v1/pf-1", RestReply.json(DOC))
                .put("/pipelineFactory/v1/pf-1", request -> RestReply.json(request.getBody()))
                .build();
        final PipelineFactoryResource resource = GWT.create(PipelineFactoryResource.class);
        return DocEditors.render(context, fixtures, readOnly, (harness, injector) -> DocEditors.open(harness,
                DOC_REF,
                injector.getPipelineFactoryEditor(),
                DocResource.of(
                        restFactory -> restFactory.create(resource).method(res -> res.fetch(DOC_REF.getUuid())),
                        (restFactory, doc) -> restFactory.create(resource)
                                .method(res -> res.update(doc.getUuid(), doc)))));
    }
}
