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

import stroom.core.client.event.WindowCloseEvent;
import stroom.dashboard.client.component.StroomQLQueryPane.StroomQLQueryPaneAction;
import stroom.dashboard.client.main.DashboardContext;
import stroom.dashboard.client.main.PaneRegistry.ComponentType;
import stroom.dashboard.client.main.PaneRegistry.ComponentUse;
import stroom.dashboard.client.query.SelectionHandlerExpressionBuilder;
import stroom.dashboard.shared.Automate;
import stroom.dashboard.shared.ComponentConfig;
import stroom.dashboard.shared.StroomQLQueryComponentSettings;
import stroom.dispatch.client.RestFactory;
import stroom.query.api.DestroyReason;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ParamUtil;
import stroom.query.client.DateTimeSettingsFactory;
import stroom.query.client.QueryModel;
import stroom.query.client.QueryResultConsumer;
import stroom.query.client.SearchErrorListener;
import stroom.query.client.SearchErrorsAlert;
import stroom.query.client.SearchStateListener;
import stroom.query.client.component.AnalyticCreatorController;
import stroom.query.client.component.QueryInfo;
import stroom.query.client.component.StroomQLEditorController;
import stroom.query.client.model.ResultStoreModel;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.shared.ErrorMessage;
import stroom.util.shared.ErrorMessages;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Contexts;

import com.google.gwt.core.client.Scheduler;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;

import java.util.List;
import java.util.Objects;

/// A dashboard's StroomQL Query pane ([StroomQLQueryPane]): a StroomQL query written in the
/// dashboard, with its help, run over the dashboard's time range with the dashboard's parameters
/// put into it. It shows no results itself: the StroomQL Table panes that name it as their query
/// show them ([StroomQLResultSource]), each given its own copy of the query's table. It can make an
/// analytic rule or a report from its query.
///
/// Like an Embedded Query, it runs with the dashboard's Run button, when the dashboard opens and
/// on a timer if its settings say to, and again when a selection its Selection Query uses
/// changes.
public class StroomQLQueryPaneController
        extends RefreshableDashboardPaneController
        implements SearchStateListener, SearchErrorListener, StroomQLResultSource {

    /// The type of a StroomQL Query tab.
    public static final String TAB_TYPE = "stroomql-query-component";

    /// The pane's type, offered by the dashboard's Add Component menu.
    public static final ComponentType TYPE = new ComponentType(
            6,
            "stroomql-query",
            "StroomQL Query",
            ComponentUse.PANEL);

    private final StroomQLQueryPane view;
    private final StroomQLEditorController editor;
    private final AnalyticCreatorController analyticCreator;
    private final QueryModel queryModel;

    private QueryInfo queryInfo;
    private boolean queryOnOpen;
    private boolean initialised;
    private ErrorMessages currentErrors;
    private ExpressionOperator currentSelectionQuery;
    // The query as it last ran, with the dashboard's parameters in it
    private String lastQuery;

    /// Made by GIN, with its pane and its parts.
    ///
    /// @param eventBus                   The application's event bus.
    /// @param view                       Its component.
    /// @param settingsControllerProvider Makes the settings dialog.
    /// @param editor                     The query's text: its editor and help.
    /// @param analyticCreator            Makes an analytic rule or a report from the query.
    /// @param restFactory                Runs the query.
    /// @param dateTimeSettingsFactory    Gives the query the user's time settings.
    /// @param resultStoreModel           Keeps the query's results.
    @Inject
    public StroomQLQueryPaneController(final EventBus eventBus,
                                       final StroomQLQueryPane view,
                                       final Provider<StroomQLQuerySettingsController> settingsControllerProvider,
                                       final StroomQLEditorController editor,
                                       final AnalyticCreatorController analyticCreator,
                                       final RestFactory restFactory,
                                       final DateTimeSettingsFactory dateTimeSettingsFactory,
                                       final ResultStoreModel resultStoreModel) {
        super(eventBus, settingsControllerProvider);
        this.view = view;
        initWidget(view);
        this.editor = own(editor).attach(view.getEditor());
        this.analyticCreator = own(analyticCreator);

        view.setOnAction(this::onAction);
        editor.setOnChange(this::onQueryEdited);
        // Read only as its dashboard is
        watch(Contexts.READ_ONLY, editor::setReadOnly);

        // The StroomQL Table panes showing the results give their own table preferences; these are
        // only for a search with none
        queryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                () -> QueryTablePreferences.builder().build());
        // Shows its requests' progress as this controller's owners do (its context's)
        queryModel.setTaskMonitorFactory(taskMonitor());
        queryModel.addSearchStateListener(this);
        queryModel.addSearchErrorListener(this);
        queryModel.addTokenErrorListener(editor::showTokenError);

        //noinspection unused event
        listen(WindowCloseEvent.getType(), event -> {
            // If a user is even attempting to close the browser or browser tab then destroy the query.
            queryModel.reset(DestroyReason.WINDOW_CLOSE);
        });
    }

    private void onAction(final StroomQLQueryPaneAction action) {
        switch (action) {
            case CREATE_RULE -> createRule();
            case CREATE_REPORT -> createReport();
            case SHOW_MESSAGES -> SearchErrorsAlert.show(this, currentErrors);
            case RUN -> onRunPressed();
        }
    }

    // The user has changed the query: it is kept in the settings, so the dashboard saves it
    private void onQueryEdited() {
        final String query = editor.getText();
        if (!Objects.equals(query, getQuerySettings().getQuery())) {
            setSettings(getQuerySettings().copy().query(query).build());
            onChange();
        }
    }

    // The run button: stops the query while it runs, else asks for its purpose (if Stroom is set
    // to) and runs it
    private void onRunPressed() {
        if (queryModel.isSearching()) {
            stop();
        } else {
            queryInfo.prompt(this::start, taskMonitor());
        }
    }

    private void createRule() {
        analyticCreator.createRule(getDashboardContext().getDashboardDocRef(),
                getAnalyticName(),
                getResolvedQuery(),
                getDashboardContext().getResolvedTimeRange());
    }

    private void createReport() {
        analyticCreator.createReport(getDashboardContext().getDashboardDocRef(),
                getAnalyticName(),
                getResolvedQuery(),
                getDashboardContext().getResolvedTimeRange());
    }

    // The name a new rule or report is first given: the dashboard's and the pane's
    private String getAnalyticName() {
        return getDashboardContext().getDashboardDocRef().getName() + " - " + getComponentConfig().getName();
    }

    // The query with the dashboard's parameters put into it, as it runs (a rule or report made
    // from it has no dashboard to give them)
    private String getResolvedQuery() {
        return ParamUtil.replaceParameters(getQuerySettings().getQuery(), getDashboardContext(), true);
    }

    @Override
    public void setDashboardContext(final DashboardContext dashboardContext) {
        // The dashboard gives the same context again each time it runs its queries, which mustn't
        // add another listener
        final boolean newContext = dashboardContext != getDashboardContext();
        super.setDashboardContext(dashboardContext);
        if (newContext) {
            own(dashboardContext.addContextChangeListener(this::onContextChange));
        }
    }

    // Runs the query again when a selection its Selection Query uses changes
    private void onContextChange() {
        if (initialised) {
            final ExpressionOperator selectionQuery = getDashboardContext()
                    .createSelectionHandlerExpression(getQuerySettings().getSelectionQuery())
                    .orElse(null);
            if (!Objects.equals(currentSelectionQuery, selectionQuery)) {
                currentSelectionQuery = selectionQuery;
                queryModel.reset(DestroyReason.NO_LONGER_NEEDED);
                run(true, true, selectionQuery);
            }
        }
    }

    @Override
    public void read(final ComponentConfig componentConfig) {
        super.read(componentConfig);

        if (!(componentConfig.getSettings() instanceof StroomQLQueryComponentSettings)) {
            setSettings(StroomQLQueryComponentSettings.builder().build());
        }
        // Fix legacy selection handlers
        setSettings(getQuerySettings()
                .copy()
                .selectionQuery(SelectionHandlerExpressionBuilder
                        .fixLegacySelectionHandlers(getQuerySettings().getSelectionQuery()))
                .build());

        queryModel.init(getDashboardContext().getDashboardDocRef());
        editor.setText(NullSafe.string(getQuerySettings().getQuery()));

        // Once the dashboard has read all its panes and been told whether to query on open, as the
        // StroomQL Table panes showing the results must be there first
        Scheduler.get().scheduleDeferred(() -> {
            // Not once the pane has gone
            if (!isDisposed()) {
                init();
            }
        });
    }

    private void init() {
        if (!initialised) {
            initialised = true;
            if (queryOnOpen || getAutomate().isOpen()) {
                run(true, false);
            }
        }
    }

    @Override
    public ComponentConfig write() {
        // The query as edited
        setSettings(getQuerySettings().copy().query(editor.getText()).build());
        return super.write();
    }

    @Override
    public void start() {
        if (queryModel.isSearching()) {
            queryModel.stop();
        }
        run(true, true);
    }

    @Override
    public void stop() {
        cancelRefresh();
        queryModel.stop();
    }

    @Override
    public void run(final boolean incremental, final boolean storeHistory) {
        run(incremental, storeHistory, currentSelectionQuery);
    }

    private void run(final boolean incremental,
                     final boolean storeHistory,
                     final ExpressionOperator additionalQueryExpression) {
        final String query = getQuerySettings().getQuery();
        // No point running the search if there is no query
        if (NullSafe.isNonBlankString(query)) {
            currentErrors = null;
            view.setMessages(false, null);
            editor.clearErrors();

            // Destroy any previous query.
            queryModel.reset(DestroyReason.NO_LONGER_NEEDED);

            final DashboardContext dashboardContext = getDashboardContext();
            lastQuery = ParamUtil.replaceParameters(query, dashboardContext, true);
            queryModel.startNewSearch(getComponentConfig().getId(),
                    getComponentConfig().getName(),
                    lastQuery,
                    dashboardContext.getParams(),
                    dashboardContext.getResolvedTimeRange(),
                    incremental,
                    storeHistory,
                    queryInfo.getMessage(),
                    additionalQueryExpression);
        }
    }

    @Override
    public void setQueryInfo(final QueryInfo queryInfo) {
        this.queryInfo = queryInfo;
    }

    @Override
    public void setQueryOnOpen(final boolean queryOnOpen) {
        this.queryOnOpen = queryOnOpen;
    }

    @Override
    public boolean isSearching() {
        return queryModel.isSearching();
    }

    @Override
    public boolean isInitialised() {
        return initialised;
    }

    @Override
    public Automate getAutomate() {
        // Settings may have no automate (e.g. a pane just added)
        final Automate automate = getQuerySettings().getAutomate();
        return automate != null
                ? automate
                : Automate.builder().build();
    }

    @Override
    public void addSearchStateListener(final SearchStateListener listener) {
        queryModel.addSearchStateListener(listener);
    }

    @Override
    public void removeSearchStateListener(final SearchStateListener listener) {
        queryModel.removeSearchStateListener(listener);
    }

    @Override
    public void addSearchErrorListener(final SearchErrorListener listener) {
        queryModel.addSearchErrorListener(listener);
    }

    @Override
    public void removeSearchErrorListener(final SearchErrorListener listener) {
        queryModel.removeSearchErrorListener(listener);
    }

    @Override
    public boolean getSearchState() {
        return queryModel.isSearching();
    }

    @Override
    public void onSearching(final boolean searching) {
        view.setSearching(searching);

        // If this is the end of a query then schedule a refresh.
        if (!searching) {
            scheduleRefresh();
        }
    }

    @Override
    public void onError(final List<ErrorMessage> errors) {
        currentErrors = new ErrorMessages(errors);
        view.setMessages(!currentErrors.isEmpty(), currentErrors.isEmpty()
                ? null
                : currentErrors.getHighestSeverity());
    }

    @Override
    public List<ErrorMessage> getCurrentErrors() {
        // Null until this query has searched
        return currentErrors != null
                ? currentErrors.getErrorMessages()
                : null;
    }

    @Override
    public HandlerRegistration addResultTable(final String tableId, final QueryResultConsumer table) {
        queryModel.addTableComponent(tableId, table);
        // A table's copy of the results is made when a search starts, so one added after the query
        // has run (e.g. a table added to the dashboard, or told to show this query) runs it again.
        // Tables there when the dashboard opens are added before it first runs.
        if (initialised && queryModel.getCurrentSearch() != null) {
            run(true, false);
        }
        return () -> queryModel.removeResultComponent(tableId);
    }

    @Override
    public String getQuery() {
        return lastQuery;
    }

    @Override
    public void link() {
    }

    @Override
    public void onClose() {
        super.onClose();
        queryModel.reset(DestroyReason.TAB_CLOSE);
        initialised = false;
    }

    @Override
    public void onRemove() {
        super.onRemove();
        queryModel.reset(DestroyReason.NO_LONGER_NEEDED);
        initialised = false;
    }

    @Override
    public ComponentType getComponentType() {
        return TYPE;
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }

    private StroomQLQueryComponentSettings getQuerySettings() {
        return (StroomQLQueryComponentSettings) getSettings();
    }
}
