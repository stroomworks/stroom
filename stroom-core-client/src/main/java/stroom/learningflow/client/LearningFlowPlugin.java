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

package stroom.learningflow.client;

import stroom.core.client.ContentManager;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.docstore.shared.DocRefUtil;
import stroom.document.client.DocumentPlugin;
import stroom.document.client.DocumentPluginEventManager;
import stroom.document.client.editor.DocumentEditorHost;
import stroom.learningflow.client.component.LearningFlowEditor;
import stroom.learningflow.client.component.StageDecisionPaneController;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningFlowElements;
import stroom.learningflow.shared.LearningFlowResource;
import stroom.pipeline.stepping.client.component.ElementStepDetailsPaneRegistry;
import stroom.security.client.api.ClientSecurityContext;
import stroom.widget.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;
import javax.inject.Singleton;

/// The client plugin for the Learning Flow document type. It opens, loads and saves Learning Flow documents
/// ([LearningFlowEditor]), and registers with the pipeline stepper the pane that a Smart Pipeline element
/// shows in place of a code pane.
@Singleton
public class LearningFlowPlugin extends DocumentPlugin<LearningFlowDoc> {

    private static final LearningFlowResource RESOURCE = GWT.create(LearningFlowResource.class);

    private final EventBus eventBus;
    private final Provider<LearningFlowEditor> editorProvider;
    private final RestFactory restFactory;

    /// Made by GIN, once.
    ///
    /// @param eventBus                 The application's event bus.
    /// @param editorProvider           Makes a document's editor.
    /// @param restFactory              Makes the REST requests that load and save documents.
    /// @param contentManager           Shows the editors as tabs.
    /// @param entityPluginEventManager Handles the documents' events.
    /// @param securityContext          The user's permissions.
    /// @param stepDetailsRegistry      The registry the stepper uses to find the pane for each element type.
    /// @param stagePaneProvider        Makes the stepper pane for a Smart Pipeline element.
    @Inject
    public LearningFlowPlugin(final EventBus eventBus,
                                final Provider<LearningFlowEditor> editorProvider,
                                final RestFactory restFactory,
                                final ContentManager contentManager,
                                final DocumentPluginEventManager entityPluginEventManager,
                                final ClientSecurityContext securityContext,
                                final ElementStepDetailsPaneRegistry stepDetailsRegistry,
                                final Provider<StageDecisionPaneController> stagePaneProvider) {
        super(eventBus, contentManager, entityPluginEventManager, securityContext);
        this.eventBus = eventBus;
        this.editorProvider = editorProvider;
        this.restFactory = restFactory;
        // The stepper shows a Smart Pipeline element's decision where other elements show a code pane. The
        // stepper knows nothing about Learning Flow, so the feature registers its own pane for its element
        // type. This plugin does it because it is the feature's entry point in the client.
        stepDetailsRegistry.register(LearningFlowElements.PARSER, stagePaneProvider::get);
    }

    @Override
    protected DocumentEditorHost<?> createEditor() {
        return new DocumentEditorHost<>(eventBus, editorProvider.get(), LearningFlowDoc.TYPE);
    }

    @Override
    public void load(final DocRef docRef,
                     final Consumer<LearningFlowDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public void save(final DocRef docRef,
                     final LearningFlowDoc document,
                     final Consumer<LearningFlowDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(RESOURCE)
                .method(res -> res.update(document.getUuid(), document))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public String getType() {
        return LearningFlowDoc.TYPE;
    }

    @Override
    protected DocRef getDocRef(final LearningFlowDoc document) {
        return DocRefUtil.create(document);
    }
}
