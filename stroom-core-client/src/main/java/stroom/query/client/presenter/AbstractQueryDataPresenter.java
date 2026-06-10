package stroom.query.client.presenter;

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.query.api.Column;
import stroom.query.shared.QueryTablePreferences;
import stroom.util.shared.ErrorMessage;
import stroom.util.shared.TokenError;

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
                getView().setError(errorMsg);
            } else {
                getView().clearError();
            }
        });
        queryModel.addTokenErrorListener(tokenError -> {
            if (tokenError != null) {
                final String detailedMessage = tokenError.getText() +
                        " at line " + tokenError.getFrom().getLineNo() +
                        ", column " + tokenError.getFrom().getColNo();
                getView().setError(detailedMessage);
                selectOffendingToken(tokenError);
            }
        });
    }

    @Override
    protected void onRead(final DocRef docRef, final D doc, final boolean readOnly) {
        tablePresenter.setPreferredColumns(getPreferredColumns(doc));
        getView().setQuery(getDefaultQuery(docRef, doc));
        getView().clearError();
    }

    @Override
    protected D onWrite(final D doc) {
        return doc;
    }

    @Override
    public void onRun() {
        getView().clearError();
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

    private void selectOffendingToken(final TokenError tokenError) {
        final String queryText = getView().getQuery();
        if (queryText == null || queryText.isEmpty()) {
            return;
        }

        final int startOffset = getCharOffset(queryText, tokenError.getFrom().getLineNo(), tokenError.getFrom().getColNo());
        final int endOffset = getCharOffset(queryText, tokenError.getTo().getLineNo(), tokenError.getTo().getColNo());

        if (startOffset >= 0 && endOffset >= startOffset && endOffset <= queryText.length()) {
            getView().selectQueryRange(startOffset, endOffset - startOffset);
        }
    }

    private int getCharOffset(final String text, final int lineNo, final int colNo) {
        if (lineNo < 1 || colNo < 0) {
            return -1;
        }
        int offset = 0;
        int currentLine = 1;
        for (int i = 0; i < text.length(); i++) {
            if (currentLine == lineNo) {
                return offset + colNo;
            }
            final char c = text.charAt(i);
            offset++;
            if (c == '\n') {
                currentLine++;
            }
        }
        return currentLine == lineNo ? offset + colNo : -1;
    }
}
