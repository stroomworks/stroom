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

package stroom.dashboard.client.component;

import stroom.dashboard.client.main.DashboardContext;
import stroom.dashboard.client.main.DashboardPane;
import stroom.dashboard.client.main.Panes;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLTableComponentSettings;
import stroom.query.api.Column;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.TableResult;
import stroom.query.client.QueryResultConsumer;
import stroom.query.client.component.QueryResultTableController;
import stroom.query.client.result.component.ResultTable;
import stroom.query.client.result.table.TableRow;
import stroom.query.shared.QueryTablePreferences;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.junit.GWTMockUtilities;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.google.web.bindery.event.shared.SimpleEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class TestStroomQLTablePaneController {

    private static final String TABLE_ID = "stroomql-table-1";
    private static final String QUERY_1 = "stroomql-query-1";
    private static final String QUERY_2 = "stroomql-query-2";
    private static final ExpressionOperator SELECTION_FILTER = ExpressionOperator.builder()
            .addTerm(ExpressionTerm.builder().field("System").condition(Condition.EQUALS).value("Firewall").build())
            .build();
    private static final List<Column> COLUMNS = List.of(
            Column.builder().id("col-1").name("UserId").build(),
            Column.builder().id("col-2").name("System").build());

    private StroomQLTablePane view;
    private QueryResultTableController table;
    private DashboardContext dashboardContext;
    private Panes panes;
    private StroomQLQueryPaneController query1;
    private StroomQLQueryPaneController query2;
    private HandlerRegistration registration1;
    private HandlerRegistration registration2;
    private StroomQLTablePaneController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        GWTMockUtilities.disarm();
        view = Mockito.mock(StroomQLTablePane.class);
        Mockito.when(view.getTable()).thenReturn(Mockito.mock(ResultTable.class));
        table = Mockito.mock(QueryResultTableController.class);
        Mockito.when(table.attach(any())).thenReturn(table);
        final MultiSelectionModelImpl<TableRow> selectionModel = Mockito.mock(MultiSelectionModelImpl.class);
        Mockito.when(table.getSelectionModel()).thenReturn(selectionModel);
        Mockito.when(selectionModel.addSelectionHandler(any()))
                .thenReturn(Mockito.mock(com.google.gwt.event.shared.HandlerRegistration.class));

        panes = Mockito.mock(Panes.class);
        // The pane named if there is one, else none, as no other StroomQL Query is on the dashboard
        Mockito.when(panes.validateOrGetLastComponentId(any(), eq(StroomQLQueryPaneController.TYPE.getId())))
                .thenAnswer(invocation -> invocation.getArgument(0));
        dashboardContext = Mockito.mock(DashboardContext.class);
        Mockito.when(dashboardContext.getComponents()).thenReturn(panes);
        Mockito.when(dashboardContext.createSelectionHandlerExpression(any())).thenReturn(Optional.empty());
        Mockito.when(dashboardContext.addContextChangeListener(any()))
                .thenReturn(Mockito.mock(HandlerRegistration.class));

        registration1 = Mockito.mock(HandlerRegistration.class);
        registration2 = Mockito.mock(HandlerRegistration.class);
        query1 = query(QUERY_1, registration1);
        query2 = query(QUERY_2, registration2);

        controller = new StroomQLTablePaneController(new SimpleEventBus(), view, () -> null, table);
        controller.setDashboardContext(dashboardContext);
    }

    @AfterEach
    void tearDown() {
        GWTMockUtilities.restore();
    }

    @Test
    void read_keepsResultsUnderThePanesId() {
        controller.read(config(QUERY_1, null));

        Mockito.verify(table).setResultComponentId(TABLE_ID);
    }

    @Test
    void link_addsTheTableToItsQuery() {
        controller.read(config(QUERY_1, null));

        controller.link();

        Mockito.verify(query1).addResultTable(eq(TABLE_ID), any());
        Mockito.verify(view).setHasQuery(true);
    }

    @Test
    void link_again_addsItOnce() {
        // The dashboard links every pane each time one is added or removed, which mustn't add the table to
        // its query again (that would run the query again)
        controller.read(config(QUERY_1, null));

        controller.link();
        controller.link();

        Mockito.verify(query1, Mockito.times(1)).addResultTable(anyString(), any());
    }

    @Test
    void link_noQuery() {
        controller.read(config(null, null));

        controller.link();

        Mockito.verify(query1, Mockito.never()).addResultTable(anyString(), any());
        Mockito.verify(view, Mockito.never()).setHasQuery(true);
    }

    @Test
    void link_queryNotAStroomQLQuery() {
        // A pane of another type isn't a source of results
        final DashboardPane otherPane = Mockito.mock(DashboardPane.class);
        Mockito.when(panes.get("table-1")).thenReturn(otherPane);
        controller.read(config("table-1", null));

        controller.link();

        Mockito.verify(view, Mockito.never()).setHasQuery(true);
    }

    @Test
    void changeSettings_anotherQuery() {
        controller.read(config(QUERY_1, null));
        controller.link();

        controller.setSettings(settings(QUERY_2, null));
        controller.changeSettings();

        Mockito.verify(registration1).removeHandler();
        Mockito.verify(query2).addResultTable(eq(TABLE_ID), any());
    }

    @Test
    void changeSettings_noQuery() {
        controller.read(config(QUERY_1, null));
        controller.link();

        controller.setSettings(settings(null, null));
        controller.changeSettings();

        Mockito.verify(registration1).removeHandler();
        // Its last results go, so it doesn't show a query it no longer has
        Mockito.verify(table).setQueryModel(null);
        Mockito.verify(table).setData(null);
        Mockito.verify(view).setHasQuery(false);
    }

    @Test
    void onRemove_removesTheTableFromItsQuery() {
        controller.read(config(QUERY_1, null));
        controller.link();

        controller.onRemove();

        Mockito.verify(registration1).removeHandler();
        Mockito.verify(table).setQueryModel(null);
    }

    @Test
    void resultConsumer_asksWithItsPreferencesAndSelectionFilter() {
        // Each table asks for its copy of the results as it shows them, with the rows its Selection Filter
        // allows; the filter isn't kept in its saved preferences
        controller.read(config(QUERY_1, QueryTablePreferences.builder().pageSize(25).build()));
        controller.link();
        Mockito.when(table.getCurrentSelectionFilter()).thenReturn(SELECTION_FILTER);

        final QueryTablePreferences preferences = resultConsumer(query1).getQueryTablePreferences();

        assertThat(preferences.getPageSize())
                .isEqualTo(25);
        assertThat(preferences.getSelectionFilter())
                .isEqualTo(SELECTION_FILTER);
        assertThat(tableSettings().getQueryTablePreferences().getSelectionFilter())
                .isNull();
    }

    @Test
    void resultConsumer_keepsTheResultsColumns() {
        // The settings' Conditional Formatting and Selection Filter offer the columns the results last had
        controller.read(config(QUERY_1, null));
        controller.link();
        final TableResult result = TableResult.builder().componentId(TABLE_ID).columns(COLUMNS).build();

        resultConsumer(query1).setData(result);

        assertThat(tableSettings().getQueryTablePreferences().getColumns())
                .isEqualTo(COLUMNS);
        Mockito.verify(table).setData(result);
    }

    @Test
    void resultConsumer_findsTheDataSourceOncePerQuery() {
        controller.read(config(QUERY_1, null));
        controller.link();
        Mockito.when(query1.getQuery()).thenReturn("from \"Security Events\" select UserId");
        final QueryResultConsumer resultConsumer = resultConsumer(query1);

        resultConsumer.startSearch();
        resultConsumer.startSearch();

        Mockito.verify(table, Mockito.times(1)).setQuery("from \"Security Events\" select UserId");
        Mockito.verify(table, Mockito.times(2)).startSearch();
    }

    @Test
    void contextChange_filtersByTheSelection() {
        controller.read(config(QUERY_1, null));
        final ArgumentCaptor<Runnable> listener = ArgumentCaptor.forClass(Runnable.class);
        Mockito.verify(dashboardContext).addContextChangeListener(listener.capture());
        Mockito.when(dashboardContext.createSelectionHandlerExpression(any()))
                .thenReturn(Optional.of(SELECTION_FILTER));

        listener.getValue().run();

        Mockito.verify(table).setCurrentSelectionFilter(SELECTION_FILTER);
        Mockito.verify(table).onColumnFilterChange();
    }

    @Test
    void setDashboardContext_sameContext_listensOnce() {
        controller.setDashboardContext(dashboardContext);

        Mockito.verify(dashboardContext, Mockito.times(1)).addContextChangeListener(any());
    }

    private StroomQLQueryPaneController query(final String id, final HandlerRegistration registration) {
        final StroomQLQueryPaneController query = Mockito.mock(StroomQLQueryPaneController.class);
        Mockito.when(query.addResultTable(anyString(), any())).thenReturn(registration);
        Mockito.when(panes.get(id)).thenReturn(query);
        return query;
    }

    // The consumer the table gave its query
    private static QueryResultConsumer resultConsumer(final StroomQLQueryPaneController query) {
        final ArgumentCaptor<QueryResultConsumer> consumer = ArgumentCaptor.forClass(QueryResultConsumer.class);
        Mockito.verify(query).addResultTable(eq(TABLE_ID), consumer.capture());
        return consumer.getValue();
    }

    private StroomQLTableComponentSettings tableSettings() {
        return (StroomQLTableComponentSettings) controller.getSettings();
    }

    private static ComponentConfig config(final String queryId, final QueryTablePreferences preferences) {
        return ComponentConfig.builder()
                .id(TABLE_ID)
                .name("Events")
                .type(StroomQLTablePaneController.TYPE.getId())
                .settings(settings(queryId, preferences))
                .build();
    }

    private static StroomQLTableComponentSettings settings(final String queryId,
                                                           final QueryTablePreferences preferences) {
        return StroomQLTableComponentSettings.builder()
                .queryId(queryId)
                .queryTablePreferences(preferences)
                .build();
    }
}
