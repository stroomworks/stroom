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


package stroom.pipelinefactory.client;

import stroom.core.client.ContentManager;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.docstore.shared.DocRefUtil;
import stroom.document.client.DocumentPlugin;
import stroom.document.client.DocumentPluginEventManager;
import stroom.document.client.editor.DocumentEditorHost;
import stroom.pipelinefactory.client.component.PipelineFactoryEditor;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryResource;
import stroom.security.client.api.ClientSecurityContext;
import stroom.widget.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;
import javax.inject.Singleton;

/// Opens, loads and saves Pipeline Factory documents (design 06 §3.5), each in a
/// [PipelineFactoryEditor].
@Singleton
public class PipelineFactoryPlugin extends DocumentPlugin<PipelineFactoryDoc> {

    private static final PipelineFactoryResource RESOURCE = GWT.create(PipelineFactoryResource.class);

    private final EventBus eventBus;
    private final Provider<PipelineFactoryEditor> editorProvider;
    private final RestFactory restFactory;

    /// Made by GIN.
    ///
    /// @param eventBus                 The application's event bus.
    /// @param editorProvider           Makes a factory's editor.
    /// @param restFactory              Loads and saves factories.
    /// @param contentManager           Shows the editors.
    /// @param entityPluginEventManager Handles the document actions.
    /// @param securityContext          Says what the user may do.
    @Inject
    public PipelineFactoryPlugin(final EventBus eventBus,
                                 final Provider<PipelineFactoryEditor> editorProvider,
                                 final RestFactory restFactory,
                                 final ContentManager contentManager,
                                 final DocumentPluginEventManager entityPluginEventManager,
                                 final ClientSecurityContext securityContext) {
        super(eventBus, contentManager, entityPluginEventManager, securityContext);
        this.eventBus = eventBus;
        this.editorProvider = editorProvider;
        this.restFactory = restFactory;
    }

    @Override
    protected DocumentEditorHost<?> createEditor() {
        return new DocumentEditorHost<>(eventBus, editorProvider.get(), PipelineFactoryDoc.TYPE);
    }

    @Override
    public void load(final DocRef docRef,
                     final Consumer<PipelineFactoryDoc> resultConsumer,
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
                     final PipelineFactoryDoc document,
                     final Consumer<PipelineFactoryDoc> resultConsumer,
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
        return PipelineFactoryDoc.TYPE;
    }

    @Override
    protected DocRef getDocRef(final PipelineFactoryDoc document) {
        return DocRefUtil.create(document);
    }
}
