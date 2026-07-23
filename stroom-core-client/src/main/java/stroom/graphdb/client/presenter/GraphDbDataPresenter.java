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

package stroom.graphdb.client.presenter;

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphDbResource;
import stroom.query.api.Column;
import stroom.query.client.presenter.AbstractQueryDataPresenter;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryDataView;
import stroom.query.client.presenter.QueryResultTablePresenter;
import stroom.query.client.presenter.ResultStoreModel;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Collections;
import java.util.List;

/**
 * Task P6.2: {@code GraphDbDoc}'s Data tab. The default query needs no {@code FROM}-equivalent clause - Task
 * P6.1's dispatch seam resolves the target graph from this tab's own doc-ref (via
 * {@code SearchRequestSource.ownerDocRef}), not from the query text.
 *
 * <p>The default query is a whole-graph preview: {@code MATCH (n) RETURN GRAPH LIMIT 100}. Unanchored scans have
 * no property index to seek, so this rides the engine's dedicated whole-graph dump path
 * ({@code GraphTraversalEngine.dumpWholeGraph}), which walks the store for the first 100 nodes (the {@code LIMIT})
 * and the edges between them. It lets a user open a graph and immediately see that it holds data and what shape it
 * takes - returning the element table (kind/id/labels/source/target/properties), which the Graph view renders with
 * Cytoscape - without having to know a specific label or id up front. Against an empty graph it simply returns zero
 * rows.</p>
 */
public class GraphDbDataPresenter
        extends AbstractQueryDataPresenter<
                GraphDbDataPresenter.GraphDbDataView,
                GraphDbDoc> {

    private static final String DEFAULT_QUERY = "MATCH (n) RETURN GRAPH LIMIT 100";

    private static final GraphDbResource GRAPH_DB_RESOURCE = GWT.create(GraphDbResource.class);

    private final GraphResultWidget graphResultWidget;
    private final GraphDiscoveryWidget discoveryWidget;
    private boolean graphVisible;
    private boolean discoveryVisible;

    public interface GraphDbDataView extends QueryDataView {
    }

    @Inject
    public GraphDbDataPresenter(final EventBus eventBus,
                                final GraphDbDataView view,
                                final QueryResultTablePresenter tablePresenter,
                                final RestFactory restFactory,
                                final DateTimeSettingsFactory dateTimeSettingsFactory,
                                final ResultStoreModel resultStoreModel) {
        super(eventBus, view, tablePresenter, restFactory, dateTimeSettingsFactory, resultStoreModel);

        // The Graph DB Data tab adds a Graph view alongside the table, driven by the same result store
        // (Cytoscape implementation plan P3). The toggle stays hidden for every other query data tab.
        graphResultWidget = new GraphResultWidget(eventBus);
        view.setGraphView(graphResultWidget, this::onViewModeChange);

        // ...and a discovery panel that turns the graph's schema into clickable starter queries, for analysts who
        // don't yet know its labels/ids. Only the Graph DB tab installs it, so the Discover control stays hidden
        // elsewhere.
        discoveryWidget = new GraphDiscoveryWidget(this::applyDiscoveredQuery);
        view.setDiscoveryWidget(discoveryWidget);
    }

    @Override
    public void onDiscover() {
        // Toggle: a second click hides the panel.
        if (discoveryVisible) {
            discoveryVisible = false;
            getView().showDiscovery(false);
            return;
        }
        final DocRef docRef = getCurrentDocRef();
        if (docRef == null) {
            return;
        }
        getRestFactory()
                .create(GRAPH_DB_RESOURCE)
                .method(res -> res.fetchSchema(docRef.getUuid()))
                .onSuccess(schema -> {
                    discoveryWidget.setSchema(schema);
                    discoveryVisible = true;
                    getView().showDiscovery(true);
                })
                .taskMonitorFactory(this)
                .exec();
    }

    private void applyDiscoveredQuery(final String query) {
        discoveryVisible = false;
        getView().showDiscovery(false);
        getView().setQuery(query);
        onRun();
    }

    @Override
    protected void onBind() {
        super.onBind();
        graphResultWidget.bind();
        // Keep the graph in step with the table while the graph view is showing.
        registerHandler(getTablePresenter().addUpdateHandler(event -> {
            if (graphVisible) {
                graphResultWidget.setData(getTablePresenter().getCurrentTableResult());
            }
        }));
    }

    @Override
    protected void onUnbind() {
        super.onUnbind();
        graphResultWidget.unbind();
    }

    private void onViewModeChange(final boolean graph) {
        graphVisible = graph;
        if (graph) {
            graphResultWidget.setData(getTablePresenter().getCurrentTableResult());
            graphResultWidget.onResize();
        }
    }

    @Override
    protected String getDefaultQuery(final DocRef docRef, final GraphDbDoc doc) {
        return DEFAULT_QUERY;
    }

    @Override
    protected List<Column> getPreferredColumns(final GraphDbDoc doc) {
        return Collections.emptyList();
    }
}
