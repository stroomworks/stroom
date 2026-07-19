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
import stroom.query.api.Column;
import stroom.query.client.presenter.AbstractQueryDataPresenter;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryDataView;
import stroom.query.client.presenter.QueryResultTablePresenter;
import stroom.query.client.presenter.ResultStoreModel;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Collections;
import java.util.List;

/**
 * Task P6.2: {@code GraphDbDoc}'s Data tab. The default query needs no {@code FROM}-equivalent clause - Task
 * P6.1's dispatch seam resolves the target graph from this tab's own doc-ref (via
 * {@code SearchRequestSource.ownerDocRef}), not from the query text.
 *
 * <p>Code-review fix: the original default query ({@code "MATCH (n)-[r]->(m) RETURN labels(n), type(r),
 * labels(m), n, m LIMIT 20"}) could never actually run - {@code GraphTraversalEngine.resolveAnchors} requires an
 * anchor to carry at least one label and one property predicate to seek the property index, an untyped edge
 * pattern has no access path over the per-type-keyed adjacency stores, and {@code labels()}/{@code type()}
 * function calls aren't wired to a graph traversal row. The corrected default below satisfies all three
 * constraints (a labelled, predicated anchor; a typed edge; bare property references in {@code RETURN}) so it
 * genuinely executes - returning zero rows against a still-empty graph rather than throwing.</p>
 */
public class GraphDbDataPresenter
        extends AbstractQueryDataPresenter<
                GraphDbDataPresenter.GraphDbDataView,
                GraphDbDoc> {

    private static final String DEFAULT_QUERY =
            "MATCH (a:Node {id: 'example'})-[:RELATED_TO]->(b:Node) RETURN a.id, b.id LIMIT 20";

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
