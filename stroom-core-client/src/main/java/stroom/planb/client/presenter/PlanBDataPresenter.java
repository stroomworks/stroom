package stroom.planb.client.presenter;

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
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.StateType;
import stroom.util.shared.ErrorMessage;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class PlanBDataPresenter
        extends DocPresenter<PlanBDataPresenter.PlanBDataView, PlanBDoc>
        implements PlanBDataUiHandlers {

    private final QueryResultTablePresenter tablePresenter;
    private final QueryModel queryModel;
    private QueryTablePreferences queryTablePreferences = QueryTablePreferences.builder().build();

    // Column definitions
    private final Column timeCol = Column.builder().id("EffectiveTime").name("Effective Time").expression("EffectiveTime").build();
    private final Column keyCol = Column.builder().id("Key").name("Key").expression("Key").build();
    private final Column valueCol = Column.builder().id("Value").name("Value").expression("substring(Value, 1, 100)").build();
    private final Column keyStartCol = Column.builder().id("KeyStart").name("Key Start").expression("KeyStart").build();
    private final Column keyEndCol = Column.builder().id("KeyEnd").name("Key End").expression("KeyEnd").build();
    private final Column startCol = Column.builder().id("Start").name("Start").expression("Start").build();
    private final Column endCol = Column.builder().id("End").name("End").expression("End").build();
    private final Column histTimeCol = Column.builder().id("Time").name("Time").expression("Time").build();
    private final Column resolutionCol = Column.builder().id("Resolution").name("Resolution").expression("Resolution").build();
    private final Column minCol = Column.builder().id("Min").name("Min").expression("Min").build();
    private final Column maxCol = Column.builder().id("Max").name("Max").expression("Max").build();
    private final Column countCol = Column.builder().id("Count").name("Count").expression("Count").build();
    private final Column sumCol = Column.builder().id("Sum").name("Sum").expression("Sum").build();
    private final Column avgCol = Column.builder().id("Average").name("Average").expression("Average").build();
    private final Column traceIdCol = Column.builder().id("TraceId").name("Trace Id").expression("TraceId").build();
    private final Column parentSpanIdCol = Column.builder().id("ParentSpanId").name("Parent Span Id").expression("ParentSpanId").build();
    private final Column spanIdCol = Column.builder().id("SpanId").name("Span Id").expression("SpanId").build();
    private final Column traceStartTimeCol = Column.builder().id("StartTime").name("Start Time").expression("StartTime").build();
    private final Column traceEndTimeCol = Column.builder().id("EndTime").name("End Time").expression("EndTime").build();

    @Inject
    public PlanBDataPresenter(final EventBus eventBus,
                              final PlanBDataView view,
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
                AlertEvent.fireError(PlanBDataPresenter.this, errorMsg, null);
            }
        });
    }

    @Override
    protected void onRead(final DocRef docRef, final PlanBDoc doc, final boolean readOnly) {
        final StateType stateType = doc.getStateType() != null ? doc.getStateType() : StateType.TEMPORAL_STATE;
        
        // Define columns and query dynamically based on StateType
        final List<Column> preferredCols;
        final String defaultQuery = switch (stateType) {
            case STATE -> {
                preferredCols = Arrays.asList(keyCol, valueCol);
                yield "from \"" + docRef.getName() + "\" select Key, Value";
            }
            case TEMPORAL_STATE -> {
                preferredCols = Arrays.asList(timeCol, keyCol, valueCol);
                yield "from \"" + docRef.getName() + "\" select EffectiveTime, Key, Value";
            }
            case RANGED_STATE -> {
                preferredCols = Arrays.asList(keyStartCol, keyEndCol, valueCol);
                yield "from \"" + docRef.getName() + "\" select KeyStart, KeyEnd, Value";
            }
            case TEMPORAL_RANGED_STATE -> {
                preferredCols = Arrays.asList(timeCol, keyStartCol, keyEndCol, valueCol);
                yield "from \"" + docRef.getName() + "\" select EffectiveTime, KeyStart, KeyEnd, Value";
            }
            case SESSION -> {
                preferredCols = Arrays.asList(startCol, endCol, keyCol);
                yield "from \"" + docRef.getName() + "\" select Start, End, Key";
            }
            case HISTOGRAM -> {
                preferredCols = Arrays.asList(histTimeCol, keyCol, resolutionCol, valueCol);
                yield "from \"" + docRef.getName() + "\" select Time, Key, Resolution, Value";
            }
            case METRIC -> {
                preferredCols = Arrays.asList(histTimeCol,
                        keyCol,
                        resolutionCol,
                        valueCol,
                        minCol,
                        maxCol,
                        countCol,
                        sumCol,
                        avgCol);
                yield "from \"" + docRef.getName() + "\" select Time, Key, Resolution, Value, Min, Max, Count, Sum, Average";
            }
            case TRACE -> {
                preferredCols = Arrays.asList(traceStartTimeCol,
                        traceEndTimeCol,
                        traceIdCol,
                        parentSpanIdCol,
                        spanIdCol);
                yield "from \"" + docRef.getName() + "\" select StartTime, EndTime, TraceId, ParentSpanId, SpanId";
            }
            default -> {
                preferredCols = Arrays.asList(timeCol, keyCol, valueCol);
                yield "from \"" + docRef.getName() + "\" select EffectiveTime, Key, Value";
            }
        };

        tablePresenter.setPreferredColumns(preferredCols);
        getView().setQuery(defaultQuery);
    }

    @Override
    protected PlanBDoc onWrite(final PlanBDoc doc) {
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

    public interface PlanBDataView extends View, HasUiHandlers<PlanBDataUiHandlers> {
        void setQuery(String query);

        String getQuery();

        void setTable(View view);
    }
}
