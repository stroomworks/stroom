/*
 * Copyright 2023 Crown Copyright
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

package stroom.query.client.component;

import stroom.docref.DocRef;
import stroom.document.client.editor.HasToolbar;
import stroom.query.shared.QueryDoc;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.Toolbar;
import stroom.widget.component.client.Controller;

import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;

/// A query document's Query tab: the query ([QueryEditorController]), with buttons that make an
/// analytic rule or a report from it.
public class QueryTabController
        extends Controller
        implements HasToolbar {

    private final QueryEditorController queryEditController;
    private final AnalyticCreatorController analyticCreator;
    private final Toolbar toolbar;
    private DocRef docRef;

    /// Made by GIN, with its parts.
    ///
    /// @param eventBus            The application's event bus.
    /// @param queryEditController The query.
    /// @param analyticCreator     Makes the rule or report.
    @Inject
    public QueryTabController(final EventBus eventBus,
                              final QueryEditorController queryEditController,
                              final AnalyticCreatorController analyticCreator) {
        super(eventBus);
        initWidget(queryEditController.asWidget());
        own(queryEditController);
        this.queryEditController = queryEditController;
        this.analyticCreator = own(analyticCreator);

        toolbar = new Toolbar("Query actions");
        toolbar.button(SvgImage.DOCUMENT_ANALYTIC_RULE, "Create Analytic Rule", this::createRule);
        toolbar.button(SvgImage.DOCUMENT_REPORT, "Create Report", this::createReport);

        queryEditController.setOnChange(this::markChanged);
    }

    @Override
    public List<Widget> getDocumentButtons() {
        final List<Widget> list = new ArrayList<>();
        list.add(toolbar);
        list.addAll(queryEditController.getDocumentButtons());
        return list;
    }

    @Override
    public List<Widget> getToolbars() {
        return queryEditController.getToolbars();
    }

    private void createRule() {
        analyticCreator.createRule(docRef,
                docRef.getName(),
                queryEditController.getQuery(),
                queryEditController.getTimeRange());
    }

    private void createReport() {
        analyticCreator.createReport(docRef,
                docRef.getName(),
                queryEditController.getQuery(),
                queryEditController.getTimeRange());
    }

    /// Shows a query document's query, time range and table preferences.
    ///
    /// @param docRef The document.
    /// @param entity The document.
    public void read(final DocRef docRef, final QueryDoc entity) {
        this.docRef = docRef;
        queryEditController.setTimeRange(entity.getTimeRange());
        queryEditController.setQuery(docRef, entity.getQuery());
        queryEditController.read(entity.getQueryTablePreferences());
    }

    /// @param visible Whether the query's tab is showing.
    public void onContentTabVisible(final boolean visible) {
        queryEditController.onContentTabVisible(visible);
    }

    /// @param entity The document.
    /// @return A copy of it with the query, time range and table preferences shown.
    public QueryDoc write(final QueryDoc entity) {
        return entity
                .copy()
                .timeRange(queryEditController.getTimeRange())
                .query(queryEditController.getQuery())
                .queryTablePreferences(queryEditController.write())
                .build();
    }

    /// Runs the query again.
    public void start() {
        queryEditController.start();
    }

    /// Stops the query.
    public void stop() {
        queryEditController.stop();
    }
}
