package stroom.sqlstore.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.query.api.Column;
import stroom.query.client.presenter.DateTimeSettingsFactory;
import stroom.query.client.presenter.QueryModel;
import stroom.query.client.presenter.QueryResultTablePresenter;
import stroom.query.client.presenter.ResultStoreModel;
import stroom.query.shared.QueryTablePreferences;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.util.shared.ErrorMessage;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

public class SqlTemporalStoreDataPresenter
        extends DocPresenter<SqlTemporalStoreDataPresenter.SqlTemporalStoreDataView, SqlTemporalStoreDoc>
        implements SqlTemporalStoreDataUiHandlers {

    private final QueryModel queryModel;
    private QueryTablePreferences queryTablePreferences = QueryTablePreferences.builder().build();

    @Inject
    public SqlTemporalStoreDataPresenter(final EventBus eventBus,
                                         final SqlTemporalStoreDataView view,
                                         final QueryResultTablePresenter tablePresenter,
                                         final RestFactory restFactory,
                                         final DateTimeSettingsFactory dateTimeSettingsFactory,
                                         final ResultStoreModel resultStoreModel) {
        super(eventBus, view);

        view.setUiHandlers(this);
        view.setTable(tablePresenter.getView());

        tablePresenter.setEmptyText("No data");

        final Column timeCol = Column.builder()
                .id("EffectiveTime")
                .name("Effective Time")
                .expression("EffectiveTime")
                .build();
        final Column keyCol = Column.builder()
                .id("Key")
                .name("Key")
                .expression("Key")
                .build();
        final Column valueCol = Column.builder()
                .id("Value")
                .name("Value")
                .expression("substring(Value, 1, 100)")
                .build();

        tablePresenter.setQueryTablePreferencesSupplier(() -> queryTablePreferences);
        tablePresenter.setQueryTablePreferencesConsumer(qtp -> {
            queryTablePreferences = qtp;
        });

        tablePresenter.setPreferredColumns(Arrays.asList(timeCol, keyCol, valueCol));
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
                AlertEvent.fireError(SqlTemporalStoreDataPresenter.this, errorMsg, null);
            }
        });

    }

    @Override
    protected void onRead(final DocRef docRef, final SqlTemporalStoreDoc doc, final boolean readOnly) {
        getView().setQuery("from \"" + docRef.getName() + "\" select EffectiveTime, Key, Value");
    }

    @Override
    protected SqlTemporalStoreDoc onWrite(final SqlTemporalStoreDoc doc) {
        return doc;
    }

    @Override
    public void onRun() {
        consoleLog("SqlTemporalStoreDataPresenter: onRun() starting search. Query text: " + getView().getQuery());
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
        consoleLog("SqlTemporalStoreDataPresenter: onStop() requested.");
        queryModel.stop();
    }

    private native void consoleLog(String msg) /*-{
        console.log(msg);
    }-*/;

    public interface SqlTemporalStoreDataView extends View, HasUiHandlers<SqlTemporalStoreDataUiHandlers> {
        void setQuery(String query);

        String getQuery();

        void setTable(View view);
    }
}
