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
import stroom.graphdb.shared.GraphElementTable;
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
    private String lastRunQuery;

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
        graphResultWidget = new GraphResultWidget(eventBus, this::expandNode, this::focusNode);
        view.setGraphView(graphResultWidget, this::onViewModeChange);

        // ...and a discovery panel that turns the graph's schema into clickable starter queries, for analysts who
        // don't yet know its labels/ids. Only the Graph DB tab installs it, so the Discover control stays hidden
        // elsewhere.
        discoveryWidget = new GraphDiscoveryWidget(this::applyDiscoveredQuery, this::focusNodeFromDiscovery);
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
        runQuery(query);
    }

    /** Focus on an example node picked from the discovery panel: hide the panel, switch to the graph view, and
     * render that node + its neighbours (identity-based - always resolves, never blanks). */
    private void focusNodeFromDiscovery(final String nodeId) {
        discoveryVisible = false;
        getView().showDiscovery(false);
        getView().selectGraphView();
        focusNode(nodeId);
    }

    /** Drop a query into the box and run it - the target of a discovery-panel suggestion. */
    private void runQuery(final String query) {
        getView().setQuery(query);
        onRun();
    }

    /** Expand a node's neighbours (all edge types, both directions) and MERGE them into the current graph - the
     * graph context-menu "Expand neighbours" action. Graph-view only; the table is left unchanged. */
    private void expandNode(final String nodeId) {
        fetchNeighbours(nodeId, graphResultWidget::addElements);
    }

    /** Focus on a node: fetch it and its neighbours and REPLACE the view with them - the graph context-menu
     * "Focus on this node" action. Identity-based (never blanks the graph), unlike a property-anchored query. */
    private void focusNode(final String nodeId) {
        fetchNeighbours(nodeId, graphResultWidget::focusElements);
    }

    private void fetchNeighbours(final String nodeId,
                                 final java.util.function.Consumer<GraphElementTable> onResult) {
        final DocRef docRef = getCurrentDocRef();
        if (docRef == null || nodeId == null) {
            return;
        }
        // Use the query that produced the current graph so the expand matches its temporal instant/window (the
        // box may have been edited without re-running).
        final String query = lastRunQuery != null ? lastRunQuery : getView().getQuery();
        getRestFactory()
                .create(GRAPH_DB_RESOURCE)
                .method(res -> res.expandNode(docRef.getUuid(), nodeId, query))
                .onSuccess(onResult::accept)
                .taskMonitorFactory(this)
                .exec();
    }

    @Override
    public void onRun() {
        // Remember the query that produced the current graph, so an "Expand neighbours" runs at the same
        // temporal instant/window (the box may be edited afterwards without re-running).
        lastRunQuery = getView().getQuery();
        super.onRun();
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
