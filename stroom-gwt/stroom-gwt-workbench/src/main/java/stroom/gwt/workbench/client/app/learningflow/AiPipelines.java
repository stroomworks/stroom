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

import stroom.docref.DocRef;
import stroom.document.client.editor.DocumentEditorHost;
import stroom.document.client.event.OpenDocumentEvent;
import stroom.gwt.workbench.client.app.gin.processing.ProcessingScreenGinjector;
import stroom.gwt.workbench.client.app.rest.JsonValues;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.gwt.workbench.client.app.screen.ScreenHarness;
import stroom.gwt.workbench.client.widgets.tree.ExplorerFixture;
import stroom.gwt.workbench.client.widgets.tree.TreeFixtures;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.pipeline.client.component.PipelineEditor;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.PipelineResource;
import stroom.security.shared.DocumentPermission;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

import java.util.Map;

/// Story fixtures for pipelines with Learning Flow elements, opened in Stroom's real [PipelineEditor] at its
/// AI Translation tab. They are used by the stories of that tab and of the reports it shows when a learning
/// run stops. The firewall pipeline (`p-1`) learns under the "Firewall logs" document
/// ([LearningFlowFixtures#DOC_REF]).
///
/// | Stroom endpoint | Used for |
/// |---|---|
/// | `GET` and `PUT /pipeline/v1/p-1`, `POST /pipeline/v1/fetchPipelineLayers` | the pipeline and its layers |
/// | `GET /pipeline/v1/propertyTypes` | its element types, the Learning Flow element's among them |
/// | `POST /meta/v1/find`, `/processorFilter/v1/find` and the like | its Data and Processors tabs |
/// | `LearningFlowFixtures#pipelineRoutes` | what the Supervisor says about its learners |
final class AiPipelines {

    /// The firewall pipeline.
    static final DocRef FIREWALL_PIPELINE = new DocRef(PipelineDoc.TYPE, "p-1", "Firewall pipeline");
    /// The id of the firewall pipeline's Learning Flow element.
    static final String ELEMENT = "learningFlow";
    /// The name of the spy that records the UUID of each document the tab asks Stroom to open.
    static final String OPENED = "openedDocument";

    private static final String DOC_JSON = "{\"type\": \"LearningFlow\", \"uuid\": \"ss-1\", "
                                           + "\"name\": \"Firewall logs\"}";
    private static final String AI_TYPE = """
            {"type": "LearningFlow", "category": "PARSER",
              "roles": ["parser", "hasTargets", "simple", "stepping", "mutator"],
              "icon": "AI", "displayValue": "Smart Pipeline"}""";
    private static final String SOURCE_TYPE = """
            {"type": "Source", "roles": ["source", "hasTargets", "simple"], "icon": "PIPELINE_STREAM",
              "displayValue": "Source"}""";
    private static final String XSLT_TYPE = """
            {"type": "XSLTFilter", "category": "FILTER",
              "roles": ["target", "hasTargets", "simple", "stepping", "mutator", "hasCode"],
              "icon": "PIPELINE_XSLT", "displayValue": "XSLT Filter"}""";
    private static final String PROPERTY_TYPES = """
            [
              {"pipelineElementType": __SOURCE__, "propertyTypes": {}},
              {"pipelineElementType": __AI__, "propertyTypes": {
                "learningFlow": {"elementType": __AI__, "name": "learningFlow", "type": "DocRef",
                  "description": "The Learning Flow that governs this stage.", "defaultValue": "",
                  "pipelineReference": false, "docRefTypes": ["LearningFlow"], "displayPriority": 1,
                  "canEmbed": false}
              }},
              {"pipelineElementType": __XSLT__, "propertyTypes": {}}
            ]"""
            .replace("__SOURCE__", SOURCE_TYPE)
            .replace("__AI__", AI_TYPE)
            .replace("__XSLT__", XSLT_TYPE);

    /// The firewall pipeline's data: one Learning Flow element using the "Firewall logs" document.
    static final String ONE_ELEMENT = """
            {"elements": {"add": [{"id": "learningFlow", "type": "LearningFlow", "name": "Learn the feed"}]},
              "links": {"add": [{"from": "Source", "to": "learningFlow"}]},
              "properties": {"add": [{"element": "learningFlow", "name": "learningFlow",
                "value": {"entity": DOC}}]}}""".replace("DOC", DOC_JSON);

    /// The firewall pipeline's data with a second Learning Flow element beside the first, which has not
    /// learned anything.
    static final String TWO_ELEMENTS = """
            {"elements": {"add": [{"id": "learningFlow", "type": "LearningFlow", "name": "Learn the feed"},
                {"id": "audit", "type": "LearningFlow", "name": "Learn the audit feed"}]},
              "links": {"add": [{"from": "Source", "to": "learningFlow"}, {"from": "Source", "to": "audit"}]},
              "properties": {"add": [{"element": "learningFlow", "name": "learningFlow",
                  "value": {"entity": DOC}},
                {"element": "audit", "name": "learningFlow", "value": {"entity": DOC}}]}}"""
            .replace("DOC", DOC_JSON);

    /// The firewall pipeline's data with no Learning Flow element, only an XSLT filter.
    static final String NO_ELEMENT = """
            {"elements": {"add": [{"id": "xslt", "type": "XSLTFilter"}]},
              "links": {"add": [{"from": "Source", "to": "xslt"}]}}""";

    private static final String NO_ROWS = """
            {"values": [], "pageResponse": {"offset": 0, "length": 0, "total": 0, "exact": true}}""";

    private AiPipelines() {
        // Static utility
    }

    /// Renders the firewall pipeline, opened as Stroom opens a pipeline, and then shown at its AI Translation
    /// tab if `openAt` names a Learning Flow element.
    ///
    /// @param context      The story's context.
    /// @param readOnly     Whether the user may only view the pipeline.
    /// @param pipelineData The pipeline's data, e.g. [#ONE_ELEMENT].
    /// @param learningflow The Supervisor's routes ([LearningFlowFixtures#pipelineRoutes]).
    /// @param openAt       The Learning Flow element to show the tab for, or null to open the pipeline at its
    ///                     default tab.
    /// @return The story's widget.
    static Widget render(final StoryContext context,
                         final boolean readOnly,
                         final String pipelineData,
                         final RestFixtures.Builder learningflow,
                         final String openAt) {
        final ProcessingScreenGinjector injector = GWT.create(ProcessingScreenGinjector.class);
        final ScreenHarness harness = ScreenHarness.builder(context, routes(learningflow, pipelineData))
                .injector(injector)
                .realAlerts()
                .build();
        harness.fn(OPENED);
        harness.getEventBus().addHandler(OpenDocumentEvent.getType(), event ->
                harness.spy(OPENED, event.getDocRef().getUuid()));
        harness.getSecurityContext().setDocumentPermission(readOnly
                ? DocumentPermission.VIEW
                : DocumentPermission.EDIT);
        // Once Stroom has started, open the document as PipelinePlugin does: load it, set whether the user
        // may edit it, read it into the editor and show the tab.
        harness.afterStartUp(() -> {
            final PipelineEditor editor = injector.getPipelineEditor();
            final DocumentEditorHost<PipelineDoc> controller = new DocumentEditorHost<>(
                    harness.getEventBus(), editor, PipelineDoc.TYPE);
            final PipelineResource resource = GWT.create(PipelineResource.class);
            harness.getRestFactory()
                    .create(resource)
                    .method(res -> res.fetch(FIREWALL_PIPELINE.getUuid()))
                    .onSuccess(doc -> {
                        controller.setReadOnly(readOnly);
                        controller.read(FIREWALL_PIPELINE, doc);
                        harness.show(controller);
                        if (openAt != null) {
                            editor.showAiTranslation(openAt);
                        }
                    })
                    .taskMonitorFactory(controller)
                    .exec();
        });
        return harness.asWidget();
    }

    private static String docRefJson(final Map<?, ?> docRef) {
        return "{\"type\": \"" + docRef.get("type")
               + "\", \"uuid\": \"" + docRef.get("uuid")
               + "\", \"name\": \"" + docRef.get("name") + "\"}";
    }

    // The REST routes for the pipeline, its editor's other tabs and the Supervisor.
    private static RestFixtures routes(final RestFixtures.Builder learningflow, final String pipelineData) {
        final String doc = "{\"type\": \"Pipeline\", \"uuid\": \"p-1\", \"name\": \"Firewall pipeline\", "
                           + "\"version\": \"v1\", \"pipelineData\": " + pipelineData + "}";
        final String layers = "[{\"sourcePipeline\": {\"type\": \"Pipeline\", \"uuid\": \"p-1\", "
                              + "\"name\": \"Firewall pipeline\"}, \"pipelineData\": " + pipelineData + "}]";
        return learningflow
                .addAll(TreeFixtures.explorerRoutes(new ExplorerFixture(ExplorerFixture.folder("System")))
                        // The documents Stroom checks (decorates) for their current names
                        .post(TreeFixtures.DECORATE, request -> RestReply.json(docRefJson(
                                (Map<?, ?>) ((Map<?, ?>) JsonValues.parse(request.getBody())).get("docRef"))))
                        .post("/explorer/v2/fetchDocRefs", request -> RestReply.json(request.getBody()))
                        .get("/pipeline/v1/p-1", RestReply.json(doc))
                        .put("/pipeline/v1/p-1", request -> RestReply.json(request.getBody()))
                        .post("/pipeline/v1/fetchPipelineLayers", RestReply.json(layers))
                        .get("/pipeline/v1/propertyTypes", RestReply.json(PROPERTY_TYPES))
                        .post("/meta/v1/find", RestReply.json(NO_ROWS))
                        .get("/meta/v1/getTypes", RestReply.json("[\"Raw Events\"]"))
                        .post("/processorFilter/v1/find", RestReply.json(NO_ROWS))
                        .post("/expression/v1/validate", RestReply.json("{\"ok\": true}"))
                        .route(RequestMatcher.post("/processorTask/v1/*"), RestReply.json(NO_ROWS))
                        .build())
                .build();
    }
}
