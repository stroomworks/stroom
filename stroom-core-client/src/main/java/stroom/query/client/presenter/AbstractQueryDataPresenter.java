package stroom.query.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.query.api.Column;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.shared.ErrorMessage;

import com.google.web.bindery.event.shared.EventBus;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public abstract class AbstractQueryDataPresenter<V extends QueryDataView, D>
        extends DocPresenter<V, D>
        implements QueryDataUiHandlers {

    private final QueryModel queryModel;
    private final QueryResultTablePresenter tablePresenter;
    private QueryTablePreferences queryTablePreferences = QueryTablePreferences.builder().build();

    public AbstractQueryDataPresenter(final EventBus eventBus,
                                      final V view,
                                      final QueryResultTablePresenter tablePresenter,
                                      final RestFactory restFactory,
                                      final DateTimeSettingsFactory dateTimeSettingsFactory,
                                      final ResultStoreModel resultStoreModel) {
        super(eventBus, view);
        this.tablePresenter = tablePresenter;

        view.setUiHandlers(this);
        view.setTable(tablePresenter.getView());

        tablePresenter.setEmptyText("No data");
        tablePresenter.setQueryTablePreferencesSupplier(() -> queryTablePreferences);
        tablePresenter.setQueryTablePreferencesConsumer(qtp -> queryTablePreferences = qtp);
        tablePresenter.setData(null);

        queryModel = new QueryModel(
                eventBus,
                restFactory,
                dateTimeSettingsFactory,
                resultStoreModel,
                () -> queryTablePreferences);
        queryModel.addResultComponent(QueryModel.TABLE_COMPONENT_ID, tablePresenter);
        queryModel.addSearchErrorListener(errors -> {
            if (errors != null && !errors.isEmpty()) {
                final String errorMsg = errors.stream()
                        .map(ErrorMessage::getMessage)
                        .collect(Collectors.joining("\n"));
                AlertEvent.fireError(AbstractQueryDataPresenter.this, errorMsg, null);
            }
        });
    }

    @Override
    protected void onRead(final DocRef docRef, final D doc, final boolean readOnly) {
        tablePresenter.setPreferredColumns(getPreferredColumns(doc));
        getView().setQuery(getDefaultQuery(docRef, doc));
    }

    @Override
    protected D onWrite(final D doc) {
        return doc;
    }

    @Override
    public void onRun() {
        queryModel.startNewSearch(
                QueryModel.TABLE_COMPONENT_ID,
                "Table",
                getView().getQuery(),
                Collections.emptyList(),
                null,
                false,
                false,
                null,
                null);
    }

    @Override
    public void onStop() {
        queryModel.stop();
    }

    protected abstract String getDefaultQuery(DocRef docRef, D doc);

    protected abstract List<Column> getPreferredColumns(D doc);
}
