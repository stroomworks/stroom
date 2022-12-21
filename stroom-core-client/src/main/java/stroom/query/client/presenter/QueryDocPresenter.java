/*
 * Copyright 2022 Crown Copyright
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
 *
 */

package stroom.query.client.presenter;

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.editor.client.presenter.EditorPresenter;
import stroom.entity.client.presenter.DocumentEditPresenter;
import stroom.instance.client.ClientApplicationInstance;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.TimeRange;
import stroom.query.client.presenter.QueryDocPresenter.QueryDocView;
import stroom.query.client.view.QueryButtons;
import stroom.query.shared.QueryDoc;
import stroom.security.client.api.ClientSecurityContext;
import stroom.view.client.presenter.IndexLoader;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.function.Function;
import javax.inject.Provider;

public class QueryDocPresenter
        extends DocumentEditPresenter<QueryDocView, QueryDoc>
        implements QueryDocUiHandlers, QueryUiHandlers {

    private EditorPresenter codePresenter;
    private final QueryResultTablePresenter tablePresenter;
    private boolean readOnly = true;
    private final QueryModel queryModel;

    @Inject
    public QueryDocPresenter(final EventBus eventBus,
                             final QueryDocView view,
                             final ClientSecurityContext securityContext,
                             final Provider<EditorPresenter> editorPresenterProvider,
                             final QueryResultTablePresenter tablePresenter,
                             final RestFactory restFactory,
                             final ClientApplicationInstance applicationInstance,
                             final IndexLoader indexLoader,
                             final DateTimeSettingsFactory dateTimeSettingsFactory) {
        super(eventBus, view, securityContext);
        this.tablePresenter = tablePresenter;

        queryModel = new QueryModel(restFactory, applicationInstance, indexLoader, dateTimeSettingsFactory);
        tablePresenter.setCurrentSearchModel(queryModel);
        queryModel.addComponent("table", tablePresenter);

        codePresenter = editorPresenterProvider.get();
        codePresenter.setMode(AceEditorMode.STROOM_QUERY);

        view.setQueryEditor(codePresenter.getView());
        view.setTable(tablePresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(codePresenter.addValueChangeHandler(event -> setDirty(true)));
        registerHandler(codePresenter.addFormatHandler(event -> setDirty(true)));
    }

    @Override
    public void onTimeRange(final TimeRange timeRange) {
        setTimeRange(timeRange);
    }

    private void setTimeRange(final TimeRange timeRange) {
//        getEntity().dashboardContext.setTimeRange(timeRange);
        getView().setTimeRange(timeRange);
    }

    @Override
    public void start() {
        run(true, true);
    }

//    @Override
//    public void stop() {
//        searchModel.stop();
//    }

    private void run(final boolean incremental,
                     final boolean storeHistory) {
        run(incremental, storeHistory, Function.identity());
    }

    private void run(final boolean incremental,
                     final boolean storeHistory,
                     final Function<ExpressionOperator, ExpressionOperator> expressionDecorator) {
//        final DocRef dataSourceRef = getQuerySettings().getDataSource();
//
//        if (dataSourceRef == null) {
//            warnNoDataSource();
//        } else {
//            currentWarnings = null;
//            expressionPresenter.clearSelection();
//
//            warningsButton.setVisible(false);
//
//            // Write expression.
//            final ExpressionOperator root = expressionPresenter.write();
//            final ExpressionOperator decorated = expressionDecorator.apply(root);

        // Start search.
        queryModel.reset();
        queryModel.startNewSearch(
                codePresenter.getText(),
                null,//getDashboardContext().getCombinedParams(),
                getView().getTimeRange(),
                incremental,
                storeHistory,
                null);
//        }
    }

    @Override
    public void onRead(final DocRef docRef, final QueryDoc entity) {
        queryModel.init(docRef.getUuid(), "query");
        if (entity.getQuery() != null) {
            codePresenter.setText(entity.getQuery());
        }
        getView().getQueryButtons().setEnabled(true);
    }

    @Override
    protected void onWrite(final QueryDoc entity) {
        entity.setQuery(codePresenter.getText());
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        super.onReadOnly(readOnly);
        this.readOnly = readOnly;

        codePresenter.setReadOnly(readOnly);
        codePresenter.getFormatAction().setAvailable(!readOnly);
    }

    @Override
    public String getType() {
        return QueryDoc.DOCUMENT_TYPE;
    }

    public interface QueryDocView extends View, HasUiHandlers<QueryDocUiHandlers> {

        QueryButtons getQueryButtons();

        TimeRange getTimeRange();

        void setTimeRange(TimeRange timeRange);

        void setQueryEditor(View view);

        void setTable(View view);
    }
}