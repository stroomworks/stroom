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
import stroom.dashboard.client.main.PaneRegistry.ComponentType;
import stroom.dashboard.client.main.PaneRegistry.ComponentUse;
import stroom.dashboard.client.query.SelectionHandlerExpressionBuilder;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLTableComponentSettings;
import stroom.query.api.Column;
import stroom.query.api.ColumnRef;
import stroom.query.api.ConditionalFormattingRule;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.GroupSelection;
import stroom.query.api.OffsetRange;
import stroom.query.api.Result;
import stroom.query.api.TableResult;
import stroom.query.client.QueryModel;
import stroom.query.client.QueryResultConsumer;
import stroom.query.client.component.QueryResultTableController;
import stroom.query.client.result.table.ColumnValuesDataSupplier;
import stroom.query.client.result.table.ComponentSelection;
import stroom.query.client.result.table.FilterCellManager;
import stroom.query.client.result.table.FilterableTable;
import stroom.query.client.result.table.HasComponentSelection;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.shared.NullSafe;

import com.google.gwt.dom.client.Element;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/// A dashboard's StroomQL Table pane ([StroomQLTablePane]): the results of a StroomQL Query pane's
/// query, as a table with its own sorts, filters, formats and conditional formatting. Several can
/// show one query's results, each its own way, as the query gives each its own copy of its table.
///
/// Like a Table, its selected rows are a selection that Text panes show and other panes' Selection
/// Query and Selection Filter use, and a Table Filter pane can filter it.
public class StroomQLTablePaneController extends DashboardPaneController
        implements HasComponentSelection, FilterableTable {

    /// The type of a StroomQL Table tab.
    public static final String TAB_TYPE = "stroomql-table-component";

    /// The pane's type, offered by the dashboard's Add Component menu.
    public static final ComponentType TYPE = new ComponentType(
            7,
            "stroomql-table",
            "StroomQL Table",
            ComponentUse.PANEL);

    private final StroomQLTablePane view;
    private final QueryResultTableController table;
    private final QueryResultConsumer resultConsumer = new TableResultConsumer();

    // The pane whose query's results the table shows, if any
    private StroomQLResultSource currentSource;
    private HandlerRegistration sourceRegistration;
    // The query the table last found the data source of
    private String currentQuery;

    /// Made by GIN, with its pane and its table.
    ///
    /// @param eventBus                   The application's event bus.
    /// @param view                       Its component.
    /// @param settingsControllerProvider Makes the settings dialog.
    /// @param table                      Shows the results.
    @Inject
    public StroomQLTablePaneController(final EventBus eventBus,
                                       final StroomQLTablePane view,
                                       final Provider<StroomQLTableSettingsController> settingsControllerProvider,
                                       final QueryResultTableController table) {
        super(eventBus, settingsControllerProvider);
        this.view = view;
        initWidget(view);
        this.table = own(table).attach(view.getTable());

        table.setQueryTablePreferencesSupplier(this::getTablePreferences);
        table.setQueryTablePreferencesConsumer(this::setTablePreferences);
        table.setOnChange(this::onChange);
        // A row chosen is a selection other panes use
        own(table.getSelectionModel().addSelectionHandler(event ->
                getDashboardContext().fireComponentChangeEvent(this)));
        // The query gives a table it no longer has no more results
        onDispose(this::removeFromSource);
    }

    @Override
    public void setDashboardContext(final DashboardContext dashboardContext) {
        // Not one listener per time the same context is given
        final boolean newContext = dashboardContext != getDashboardContext();
        super.setDashboardContext(dashboardContext);
        // So a hyperlink's parameters can be filled from the dashboard
        table.setHyperlinkContext(dashboardContext);
        if (newContext) {
            own(dashboardContext.addContextChangeListener(() -> updateSelectionFilter(true)));
        }
    }

    @Override
    public void read(final ComponentConfig componentConfig) {
        super.read(componentConfig);

        if (!(componentConfig.getSettings() instanceof StroomQLTableComponentSettings)) {
            setSettings(StroomQLTableComponentSettings.builder().build());
        }
        // Fix legacy selection filters
        setSettings(getTableSettings()
                .copy()
                .selectionFilter(SelectionHandlerExpressionBuilder
                        .fixLegacySelectionHandlers(getTableSettings().getSelectionFilter()))
                .build());

        // The table's copy of the query's results is kept under the pane's ID
        table.setResultComponentId(componentConfig.getId());
        // Named for copying, exporting or asking AI about it
        view.getTable().setTableName(componentConfig.getName());
        updateSelectionFilter(false);
        table.updateQueryTablePreferences();
    }

    @Override
    public void link() {
        // The pane named, or else the dashboard's last StroomQL Query
        final String queryId = getDashboardContext()
                .getComponents()
                .validateOrGetLastComponentId(getTableSettings().getQueryId(),
                        StroomQLQueryPaneController.TYPE.getId());
        setSettings(getTableSettings().copy().queryId(queryId).build());
        linkSource(queryId);
    }

    // Shows the results of the named pane's query, in place of those of the pane it showed, if it
    // differs
    private void linkSource(final String queryId) {
        final DashboardPane component = queryId != null
                ? getDashboardContext().getComponents().get(queryId)
                : null;
        final StroomQLResultSource source = component instanceof final StroomQLResultSource resultSource
                ? resultSource
                : null;
        if (source != currentSource) {
            unlinkSource();
            if (source != null) {
                currentSource = source;
                view.setHasQuery(true);
                sourceRegistration = source.addResultTable(getComponentConfig().getId(), resultConsumer);
            }
        }
    }

    // Shows no results, and stops the pane it showed giving it any
    private void unlinkSource() {
        removeFromSource();
        if (currentSource != null) {
            currentSource = null;
            currentQuery = null;
            table.setQueryModel(null);
            table.setData(null);
            view.setHasQuery(false);
        }
    }

    private void removeFromSource() {
        if (sourceRegistration != null) {
            sourceRegistration.removeHandler();
            sourceRegistration = null;
        }
    }

    // Filters the rows by the selections the Selection Filter names, refreshing the table if the
    // filter has changed and it should
    private void updateSelectionFilter(final boolean refresh) {
        final DashboardContext dashboardContext = getDashboardContext();
        final ExpressionOperator selectionFilter = dashboardContext == null
                ? null
                : dashboardContext
                        .createSelectionHandlerExpression(getTableSettings().getSelectionFilter())
                        .orElse(null);
        if (!Objects.equals(table.getCurrentSelectionFilter(), selectionFilter)) {
            table.setCurrentSelectionFilter(selectionFilter);
            if (refresh) {
                table.onColumnFilterChange();
            }
        }
    }

    // The query has started a search: the table finds the query's data source if the query has
    // changed (for annotation data sources)
    private void onSearchStart() {
        final String query = NullSafe.get(currentSource, StroomQLResultSource::getQuery);
        if (NullSafe.isNonBlankString(query) && !Objects.equals(query, currentQuery)) {
            currentQuery = query;
            table.setQuery(query);
        }
    }

    // Keeps the columns the results have in the table's preferences, as the settings' Conditional
    // Formatting and Selection Filter offer them
    private void keepColumns(final Result result) {
        if (result instanceof final TableResult tableResult && NullSafe.hasItems(tableResult.getColumns())) {
            final QueryTablePreferences preferences = getTablePreferences();
            if (!Objects.equals(preferences.getColumns(), tableResult.getColumns())) {
                setTablePreferences(QueryTablePreferences
                        .copy(preferences)
                        .columns(tableResult.getColumns())
                        .build());
            }
        }
    }

    @Override
    protected void changeSettings() {
        super.changeSettings();
        view.getTable().setTableName(getComponentConfig().getName());
        linkSource(getTableSettings().getQueryId());
        updateSelectionFilter(false);
        // Its page size and conditional formatting
        table.changeSettings();
        table.refresh();
    }

    @Override
    public void onRemove() {
        super.onRemove();
        unlinkSource();
    }

    @Override
    public void onContentTabVisible(final boolean visible) {
        table.onContentTabVisible(visible);
    }

    // The table's preferences, or the defaults if it has none (e.g. a pane just added), as the
    // results table expects some
    private QueryTablePreferences getTablePreferences() {
        final QueryTablePreferences preferences = getTableSettings().getQueryTablePreferences();
        return preferences != null
                ? preferences
                : QueryTablePreferences.builder().build();
    }

    private void setTablePreferences(final QueryTablePreferences preferences) {
        setSettings(getTableSettings().copy().queryTablePreferences(preferences).build());
    }

    private StroomQLTableComponentSettings getTableSettings() {
        return (StroomQLTableComponentSettings) getSettings();
    }

    /// @return The table, e.g. for a test or a story to look at.
    QueryResultTableController getTable() {
        return table;
    }

    @Override
    public ComponentType getComponentType() {
        return TYPE;
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }

    @Override
    public List<ColumnRef> getColumnRefs() {
        return table.getColumnRefs();
    }

    @Override
    public List<ComponentSelection> getSelection() {
        return table.getSelection();
    }

    @Override
    public Set<String> getHighlights() {
        return table.getHighlights();
    }

    @Override
    public List<Column> getColumns() {
        return table.getColumns();
    }

    @Override
    public Element getFilterButton(final Column column) {
        return table.getFilterButton(column);
    }

    @Override
    public ColumnValuesDataSupplier getDataSupplier(final Column column,
                                                    final List<ConditionalFormattingRule> conditionalFormattingRules) {
        return table.getDataSupplier(column, conditionalFormattingRules);
    }

    @Override
    public FilterCellManager getFilterCellManager() {
        return table.getFilterCellManager();
    }

    @Override
    public HandlerRegistration addUpdateListener(final Runnable listener) {
        return table.addUpdateListener(listener);
    }


    // --------------------------------------------------------------------------------


    // Given the table's copy of the query's results; asks for them as the table wants them, its
    // preferences with the rows its Selection Filter allows
    private class TableResultConsumer implements QueryResultConsumer {

        @Override
        public OffsetRange getRequestedRange() {
            return table.getRequestedRange();
        }

        @Override
        public GroupSelection getGroupSelection() {
            return table.getGroupSelection();
        }

        @Override
        public QueryTablePreferences getQueryTablePreferences() {
            return getTablePreferences()
                    .copy()
                    .selectionFilter(table.getCurrentSelectionFilter())
                    .build();
        }

        @Override
        public void reset() {
            table.reset();
        }

        @Override
        public void startSearch() {
            onSearchStart();
            table.startSearch();
        }

        @Override
        public void endSearch() {
            table.endSearch();
        }

        @Override
        public void setData(final Result componentResult) {
            keepColumns(componentResult);
            table.setData(componentResult);
        }

        @Override
        public void setQueryModel(final QueryModel queryModel) {
            table.setQueryModel(queryModel);
        }
    }
}
