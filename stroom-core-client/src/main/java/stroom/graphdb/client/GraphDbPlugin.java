/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.graphdb.client;

import stroom.core.client.ContentManager;
import stroom.dispatch.client.RestErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.docstore.shared.DocRefUtil;
import stroom.document.client.DocumentPlugin;
import stroom.document.client.DocumentPluginEventManager;
import stroom.entity.client.presenter.DocPresenter;
import stroom.graphdb.client.presenter.GraphDbPresenter;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphDbResource;
import stroom.security.client.api.ClientSecurityContext;
import stroom.task.client.TaskMonitorFactory;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;
import javax.inject.Singleton;

/**
 * Task P6.2: registers {@link GraphDbDoc} with the explorer/document-plugin framework, mirroring
 * {@code stroom.sqlstore.client.SqlTemporalStorePlugin} exactly - {@link GraphDbResource} (built in Task P5.2)
 * already provides the fetch/update REST access this plugin needs.
 */
@Singleton
public class GraphDbPlugin extends DocumentPlugin<GraphDbDoc> {

    private static final GraphDbResource GRAPH_DB_RESOURCE = GWT.create(GraphDbResource.class);

    private final Provider<GraphDbPresenter> editorProvider;
    private final RestFactory restFactory;

    @Inject
    public GraphDbPlugin(
            final EventBus eventBus,
            final Provider<GraphDbPresenter> editorProvider,
            final RestFactory restFactory,
            final ContentManager contentManager,
            final DocumentPluginEventManager entityPluginEventManager,
            final ClientSecurityContext securityContext) {
        super(eventBus, contentManager, entityPluginEventManager, securityContext);

        this.editorProvider = editorProvider;
        this.restFactory = restFactory;
    }

    @Override
    protected DocPresenter<?, ?> createEditor() {
        return editorProvider.get();
    }

    @Override
    public void load(final DocRef docRef,
                     final Consumer<GraphDbDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(GRAPH_DB_RESOURCE)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public void save(final DocRef docRef,
                     final GraphDbDoc document,
                     final Consumer<GraphDbDoc> resultConsumer,
                     final RestErrorHandler errorHandler,
                     final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(GRAPH_DB_RESOURCE)
                .method(res -> res.update(document.getUuid(), document))
                .onSuccess(resultConsumer)
                .onFailure(errorHandler)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    @Override
    public String getType() {
        return GraphDbDoc.TYPE;
    }

    @Override
    protected DocRef getDocRef(final GraphDbDoc document) {
        return DocRefUtil.create(document);
    }
}
