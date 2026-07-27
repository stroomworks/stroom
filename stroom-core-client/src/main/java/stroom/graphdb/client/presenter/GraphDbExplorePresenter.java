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
import stroom.editor.client.presenter.ChangeCurrentPreferencesEvent;
import stroom.editor.client.presenter.CurrentPreferences;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphDbResource;
import stroom.graphdb.shared.GraphElementTable;
import stroom.graphdb.shared.GraphTemporal;
import stroom.query.api.Column;
import stroom.query.client.presenter.AbstractQueryDataPresenter;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryDataView;
import stroom.query.client.presenter.QueryResultTablePresenter;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.ui.config.shared.Theme;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Collections;
import java.util.List;

/**
 * The {@code GraphDbDoc}'s <b>Explore</b> tab: a query editor plus the Cytoscape graph view (with the schema
 * discovery panel and the temporal time-travel controls). This is the interactive graph surface; its sibling
 * <b>Data</b> tab ({@link GraphDbDataPresenter}) owns the tabular result and is where a table-oriented workflow
 * (and, in future, Create Dashboard) lives.
 *
 * <p>This tab is <b>graph-only</b> — {@link QueryDataView#setGraphOnly} shows the graph as the sole surface with
 * no Table/Graph toggle. The table result store still runs underneath (it is what feeds the graph), it is simply
 * never shown here.</p>
 *
 * <p>The default query is a whole-graph preview: {@code MATCH (n) RETURN GRAPH LIMIT 100}. Unanchored scans have
 * no property index to seek, so this rides the engine's dedicated whole-graph dump path
 * ({@code GraphTraversalEngine.dumpWholeGraph}), which walks the store for the first 100 nodes (the {@code LIMIT})
 * and the edges between them, letting a user open a graph and immediately see its shape without knowing a label
 * or id up front. Against an empty graph it returns zero rows.</p>
 */
public class GraphDbExplorePresenter
        extends AbstractQueryDataPresenter<
                GraphDbExplorePresenter.GraphDbExploreView,
                GraphDbDoc> {

    private static final String DEFAULT_QUERY = "MATCH (n) RETURN GRAPH LIMIT 100";

    private static final GraphDbResource GRAPH_DB_RESOURCE = GWT.create(GraphDbResource.class);

    private final GraphResultWidget graphResultWidget;
    private final GraphDiscoveryWidget discoveryWidget;
    private final CurrentPreferences currentPreferences;
    private boolean discoveryVisible;
    private String lastRunQuery;
    // The query the time-travel slider snapshots against - captured on first snapshot, cleared by a manual run.
    private String temporalBaseQuery;
    private boolean runningSnapshot;

    public interface GraphDbExploreView extends QueryDataView {
    }

    @Inject
    public GraphDbExplorePresenter(final EventBus eventBus,
                                   final GraphDbExploreView view,
                                   final QueryResultTablePresenter tablePresenter,
                                   final RestFactory restFactory,
                                   final DateTimeSettingsFactory dateTimeSettingsFactory,
                                   final ResultStoreModel resultStoreModel,
                                   final CurrentPreferences currentPreferences) {
        super(eventBus, view, tablePresenter, restFactory, dateTimeSettingsFactory, resultStoreModel);
        this.currentPreferences = currentPreferences;

        // The graph is this tab's only result surface (the "Data" tab owns the table). The table result store
        // still runs underneath and feeds the graph via the update handler registered in onBind. The graph's own
        // Cytoscape toolbar carries the "Discover" button, which relays back here to onDiscover.
        graphResultWidget = new GraphResultWidget(eventBus, this::expandNode, this::focusNode,
                this::snapshotAt, this::exitTimeTravel, this::compareWindow, this::onDiscover);
        view.setGraphOnly(graphResultWidget);

        // A discovery panel that turns the graph's schema into clickable starter queries, for analysts who don't
        // yet know its labels/ids. Its trigger lives in the Cytoscape toolbar (the "Discover" button), not the
        // query toolbar, so setDiscoveryWidget only installs the panel.
        discoveryWidget = new GraphDiscoveryWidget(this::applyDiscoveredQuery, this::focusNodeFromDiscovery);
        view.setDiscoveryWidget(discoveryWidget);

        // No "Create Dashboard" on the graph surface (it's a table-oriented action) - hide it entirely here; the
        // sibling "Data" tab is where it lives (disabled for now, pending Cypher-carrying dashboards).
        view.setCreateDashboardVisible(false);
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

    /** Focus on an example node picked from the discovery panel: hide the panel and render that node + its
     * neighbours (identity-based - always resolves, never blanks). The graph is always shown on this tab. */
    private void focusNodeFromDiscovery(final String nodeId) {
        discoveryVisible = false;
        getView().showDiscovery(false);
        focusNode(nodeId);
    }

    /** Drop a query into the box and run it - the target of a discovery-panel suggestion. */
    private void runQuery(final String query) {
        getView().setQuery(query);
        onRun();
    }

    /** Expand a node's neighbours (all edge types, both directions) and MERGE them into the current graph - the
     * graph context-menu "Expand neighbours" action. */
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
        // A manual run (not a slider tick) re-bases the time-travel window on the new query.
        if (!runningSnapshot) {
            temporalBaseQuery = null;
        }
        super.onRun();
    }

    /** Render the displayed graph as of {@code instantMillis}: rewrite the base query's temporal clause to
     * {@code AS OF} that instant and run it (see {@link GraphTemporal#withAsOf}). The graph re-renders from the
     * result via the normal table-update path. */
    private void snapshotAt(final Long instantMillis) {
        if (instantMillis == null) {
            return;
        }
        if (temporalBaseQuery == null) {
            temporalBaseQuery = lastRunQuery != null ? lastRunQuery : getView().getQuery();
        }
        final String snapshotQuery = GraphTemporal.withAsOf(temporalBaseQuery, instantMillis);
        runningSnapshot = true;
        try {
            runQuery(snapshotQuery);
        } finally {
            runningSnapshot = false;
        }
    }

    /** Leave time-travel: re-run the base query to restore the live (latest) view. */
    private void exitTimeTravel() {
        if (temporalBaseQuery != null) {
            final String base = temporalBaseQuery;
            temporalBaseQuery = null;
            runQuery(base);
        }
    }

    /** Compare two instants: run the base query as {@code DIFF FROM from TO to} so the result carries the
     * changeKind the graph view styles as added / removed / modified / unchanged. */
    private void compareWindow(final Long fromMillis, final Long toMillis) {
        if (fromMillis == null || toMillis == null) {
            return;
        }
        if (temporalBaseQuery == null) {
            temporalBaseQuery = lastRunQuery != null ? lastRunQuery : getView().getQuery();
        }
        final String diffQuery = GraphTemporal.withDiff(temporalBaseQuery, fromMillis, toMillis);
        runningSnapshot = true;
        try {
            runQuery(diffQuery);
        } finally {
            runningSnapshot = false;
        }
    }

    private void applyTheme(final String theme) {
        graphResultWidget.setClassName(Theme.getClassName(theme));
    }

    @Override
    protected void onBind() {
        super.onBind();
        graphResultWidget.bind();
        // The graph is always visible on this tab, so keep it in step with every table result.
        registerHandler(getTablePresenter().addUpdateHandler(event -> {
            graphResultWidget.setData(getTablePresenter().getCurrentTableResult());
            graphResultWidget.onResize();
        }));
        // Track the app's light/dark theme in the graph sandbox (mirrors VisPresenter).
        applyTheme(currentPreferences.getTheme());
        registerHandler(getEventBus().addHandler(ChangeCurrentPreferencesEvent.getType(),
                event -> applyTheme(event.getTheme())));
    }

    @Override
    protected void onUnbind() {
        super.onUnbind();
        graphResultWidget.unbind();
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
