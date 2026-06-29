/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.sqlstore.impl;

import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.event.logging.api.StroomEventLoggingService;
import stroom.event.logging.api.StroomEventLoggingUtil;
import stroom.index.shared.IndexFieldImpl;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionUtil;
import stroom.query.api.Query;
import stroom.query.api.SearchRequest;
import stroom.query.api.SearchTaskProgress;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.IndexField;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.CoprocessorSettings;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.CoprocessorsImpl;
import stroom.query.common.v2.DataStoreSettings;
import stroom.query.common.v2.FieldInfoResultPageFactory;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProcess;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValDate;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.shared.ApplyChangesRequest;
import stroom.sqlstore.shared.ApplyChangesResult;
import stroom.sqlstore.shared.ChangeOperation;
import stroom.sqlstore.shared.FetchAtTimeRequest;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.sqlstore.shared.TemporalStoreTimeRange;
import stroom.task.api.TaskContextFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Singleton
public class UpdatableSqlTemporalStore implements UpdatableTemporalStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(UpdatableSqlTemporalStore.class);

    private final UpdatableTemporalStoreDao dao;
    private final SqlTemporalStoreDocStore sqlStoreDocStore;
    private final FieldInfoResultPageFactory fieldInfoResultPageFactory;
    private final CoprocessorsFactory coprocessorsFactory;
    private final ResultStoreFactory resultStoreFactory;
    private final Executor executor;
    private final TaskContextFactory taskContextFactory;
    private final SecurityContext securityContext;
    private final Provider<StroomEventLoggingService> eventLoggingServiceProvider;

    @Inject
    public UpdatableSqlTemporalStore(final UpdatableTemporalStoreDao dao,
                                     final SqlTemporalStoreDocStore sqlStoreDocStore,
                                     final FieldInfoResultPageFactory fieldInfoResultPageFactory,
                                     final CoprocessorsFactory coprocessorsFactory,
                                     final ResultStoreFactory resultStoreFactory,
                                     final Executor executor,
                                     final TaskContextFactory taskContextFactory,
                                     final SecurityContext securityContext,
                                     final Provider<StroomEventLoggingService> eventLoggingServiceProvider) {
        this.dao = dao;
        this.sqlStoreDocStore = sqlStoreDocStore;
        this.fieldInfoResultPageFactory = fieldInfoResultPageFactory;
        this.coprocessorsFactory = coprocessorsFactory;
        this.resultStoreFactory = resultStoreFactory;
        this.executor = executor;
        this.taskContextFactory = taskContextFactory;
        this.securityContext = securityContext;
        this.eventLoggingServiceProvider = eventLoggingServiceProvider;
    }

    @Override
    public TemporalEntry create(final TemporalEntry entry) {
        checkPermission(entry.getMap(), DocumentPermission.EDIT);
        return eventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "create"))
                .withDescription("Create temporal entry")
                .withDefaultEventAction(event.logging.CreateEventAction.builder().build())
                .withSimpleLoggedResult(() -> {
                    validateKey(entry.getKey());
                    return dao.create(entry);
                })
                .getResultAndLog();
    }

    @Override
    public TemporalEntry update(final TemporalEntry entry) {
        return create(entry);
    }

    @Override
    public Optional<TemporalEntry> fetch(final TemporalEntryId id) {
        checkPermission(id.getMap(), DocumentPermission.VIEW);
        return dao.fetch(id);
    }

    @Override
    public boolean delete(final TemporalEntryId id) {
        checkPermission(id.getMap(), DocumentPermission.DELETE);
        return eventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "delete"))
                .withDescription("Delete temporal entry")
                .withDefaultEventAction(event.logging.DeleteEventAction.builder().build())
                .withSimpleLoggedResult(() -> dao.delete(id))
                .getResultAndLog();
    }

    @Override
    public ResultPage<TemporalEntry> find(final ExpressionCriteria criteria) {
        final ResultPage<TemporalEntry> page = dao.find(criteria);

        // Filter list by permissions
        final List<TemporalEntry> filteredList = page.getValues().stream()
                .filter(entry -> hasPermission(entry.getMap(), DocumentPermission.VIEW))
                .toList();

        return ResultPage.createPageLimitedList(filteredList, criteria.getPageRequest());
    }

    @Override
    public void clear(final String mapName) {
        checkPermission(mapName, DocumentPermission.EDIT);
        eventLoggingServiceProvider.get().loggedWorkBuilder()
                .withTypeId(StroomEventLoggingUtil.buildTypeId(this, "clear"))
                .withDescription("Clear temporal store: " + mapName)
                .withDefaultEventAction(event.logging.DeleteEventAction.builder().build())
                .withSimpleLoggedResult(() -> {
                    dao.clear(mapName);
                    return null;
                })
                .getResultAndLog();
    }

    /**
     * Returns the latest-at-time version of every key in the specified map.
     *
     * <p>Delegates temporal deduplication to the existing
     * {@link UpdatableTemporalStoreDao#find} implementation: by supplying an
     * {@code EffectiveTime <= timeTo} term the DAO activates its "QueryTime Path"
     * which uses a correlated subquery to select only the most-recent version of
     * each key at or before {@code timeTo}, without fetching all historical
     * versions.</p>
     *
     * <p>Requires {@code VIEW} permission on the map.</p>
     *
     * @param request specifies the map name and required upper time bound
     * @return deduplicated list of entries, one per key, sorted by key ascending
     */
    @Override
    public List<TemporalEntry> fetchAtTime(final FetchAtTimeRequest request) {
        if (request == null || request.getMapName() == null || request.getMapName().isBlank()) {
            throw new IllegalArgumentException("mapName must be specified in the request.");
        }
        checkPermission(request.getMapName(), DocumentPermission.VIEW);

        // Build criteria that trigger the DAO's temporal-deduplication subquery path.
        // The Map = mapName term scopes the query to the correct store.
        // The EffectiveTime <= timeTo term is detected by getQueryTime() in the DAO,
        // which switches from a plain SELECT to the correlated MAX(effective_time) subquery,
        // returning one row per key rather than all historical versions.
        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTerm(ExpressionTerm.builder()
                        .field(UpdatableTemporalStore.MAP_FIELD.getFldName())
                        .condition(ExpressionTerm.Condition.EQUALS)
                        .value(request.getMapName())
                        .build())
                .addTerm(ExpressionTerm.builder()
                        .field(UpdatableTemporalStore.TIME_FIELD.getFldName())
                        .condition(ExpressionTerm.Condition.LESS_THAN_OR_EQUAL_TO)
                        .value(String.valueOf(request.getTimeTo()))
                        .build())
                .build();

        // No page limit — we want all keys for the map.
        final ExpressionCriteria criteria = new ExpressionCriteria(expression);
        final ResultPage<TemporalEntry> page = dao.find(criteria);

        // Apply permission filtering (consistent with find()).
        // Sort by key for a predictable, user-friendly order.
        return page.getValues().stream()
                .filter(e -> hasPermission(e.getMap(), DocumentPermission.VIEW))
                .sorted(Comparator.comparing(TemporalEntry::getKey,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /**
     * Returns the absolute latest version of every key in the specified map,
     * with no upper-time constraint.
     *
     * <p>The server uses {@code currentTimeMillis() + ONE_DAY_MS} as an
     * internal upper bound so that entries with effective times a short way
     * in the future are not silently missed. The client does not need to
     * supply a time value.</p>
     *
     * <p>Delegates deduplication to
     * {@link UpdatableTemporalStoreDao#fetchAll(String)}, which fetches all
     * historical versions and keeps only the latest per key in-process.</p>
     *
     * <p>Requires {@code VIEW} permission on the map.</p>
     *
     * @param mapName name of the map to query; must not be {@code null} or blank
     * @return deduplicated list — one entry per key — sorted by key ascending
     */
    @Override
    public List<TemporalEntry> fetchAll(final String mapName) {
        if (mapName == null || mapName.isBlank()) {
            throw new IllegalArgumentException("mapName must be specified.");
        }
        checkPermission(mapName, DocumentPermission.VIEW);
        return dao.fetchAll(mapName);
    }

    /**
     * Returns the minimum and maximum {@code effective_time} values in the
     * store for the given map, for use in initialising the timeline slider.
     *
     * <p>Requires {@code VIEW} permission on the map.</p>
     *
     * @param mapName name of the map to query; must not be {@code null} or blank
     * @return the time range; both fields {@code null} if the store is empty
     */
    @Override
    public TemporalStoreTimeRange getTimeRange(final String mapName) {
        if (mapName == null || mapName.isBlank()) {
            throw new IllegalArgumentException("mapName must be specified.");
        }
        checkPermission(mapName, DocumentPermission.VIEW);
        return dao.getTimeRange(mapName);
    }

    /**
     * Applies a list of staged changes atomically.
     *
     * <p>Validates the request, asserts that all operations target the same map
     * (cross-map batches are rejected outright), checks {@code EDIT} permission
     * on that map, then delegates to {@link UpdatableTemporalStoreDao#applyChanges}
     * which wraps all operations in a single JOOQ transaction.</p>
     *
     * <p>Any exception thrown by the DAO is caught and converted into an
     * {@link ApplyChangesResult} with {@code success = false} so that the REST
     * layer always receives a well-formed response rather than an HTTP 500.</p>
     *
     * @param request the ordered list of operations; must not be {@code null}
     * @return result indicating success or failure; never {@code null}
     */
    @Override
    public ApplyChangesResult applyChanges(final ApplyChangesRequest request) {
        if (request == null || request.getOperations() == null) {
            return new ApplyChangesResult(false, "Request must not be null.");
        }

        // Extract the map name from every operation and verify they all agree.
        // A batch that spans multiple maps is rejected before any DB work is done.
        String mapName = null;
        for (int i = 0; i < request.getOperations().size(); i++) {
            final ChangeOperation op = request.getOperations().get(i);
            if (op == null) {
                continue;
            }
            final String opMap = op.getType() == ChangeOperation.Type.UPSERT && op.getEntry() != null
                    ? op.getEntry().getMap()
                    : op.getId() != null
                    ? op.getId().getMap()
                    : null;
            if (opMap == null || opMap.isBlank()) {
                return new ApplyChangesResult(false,
                        "Operation at index " + i + " has a null or blank map name.");
            }
            if (mapName == null) {
                mapName = opMap;
            } else if (!mapName.equals(opMap)) {
                return new ApplyChangesResult(false,
                        "All operations in a batch must target the same map. "
                        + "Expected '" + mapName + "' but operation at index " + i
                        + " targets '" + opMap + "'.");
            }
        }

        if (mapName == null) {
            return new ApplyChangesResult(false,
                    "Could not determine map name: the operations list is empty or contains only nulls.");
        }
        try {
            checkPermission(mapName, DocumentPermission.EDIT);
            dao.applyChanges(request.getOperations());
            return new ApplyChangesResult(true, null);
        } catch (final Exception e) {
            LOGGER.error("applyChanges failed for map '{}': {}", mapName, e.getMessage(), e);
            return new ApplyChangesResult(false, e.getMessage());
        }
    }

    private void checkPermission(final String mapName, final DocumentPermission permission) {
        if (!hasPermission(mapName, permission)) {
            throw new PermissionException(securityContext.getUserRef(),
                    "User does not have " + permission + " permission on store " + mapName);
        }
    }

    private boolean hasPermission(final String mapName, final DocumentPermission permission) {
        final Optional<DocRef> docRef = sqlStoreDocStore.list().stream()
                .filter(dr -> dr.getName().equals(mapName))
                .findFirst();

        return docRef.map(dr -> securityContext.hasDocumentPermission(dr, permission)).orElse(false);
    }

    @Override
    public String getDataSourceType() {
        return SqlTemporalStoreDoc.TYPE;
    }

    @Override
    public List<DocRef> getDataSourceDocRefs() {
        return sqlStoreDocStore.list();
    }

    @Override
    public Optional<QueryField> getTimeField(final DocRef docRef) {
        return Optional.of(TIME_FIELD);
    }

    @Override
    public ResultPage<QueryField> getFieldInfo(final FindFieldCriteria criteria) {
        return fieldInfoResultPageFactory.create(criteria, FIELDS);
    }

    @Override
    public int getFieldCount(final DocRef docRef) {
        return FIELDS.size();
    }

    @SuppressWarnings("unused")
    public IndexField getIndexField(final DocRef docRef, final String fieldName) {

        return FIELDS.stream()
                .filter(f -> f.getFldName().equals(fieldName))
                .findFirst()
                .map(f -> IndexFieldImpl.builder().fldName(f.getFldName()).fldType(f.getFldType()).build())
                .orElse(null);
    }

    @Override
    public ResultStore createResultStore(final SearchRequest searchRequest) {
        // Replace expression parameters.
        final SearchRequest modifiedSearchRequest = ExpressionUtil.replaceExpressionParameters(searchRequest);

        // Get the search.
        final Query query = modifiedSearchRequest.getQuery();

        // Load the doc.
        final DocRef docRef = query.getDataSource();

        // Check we have permission to read the doc.
        checkPermission(docRef.getName(), DocumentPermission.VIEW);

        // Create a coprocessor settings list.
        final List<CoprocessorSettings> coprocessorSettingsList = coprocessorsFactory
                .createSettings(modifiedSearchRequest);

        // Create a handler for search results.
        final DataStoreSettings dataStoreSettings = DataStoreSettings
                .createBasicSearchResultStoreSettings();
        final CoprocessorsImpl coprocessors = coprocessorsFactory.create(
                modifiedSearchRequest.getSearchRequestSource(),
                modifiedSearchRequest.getDateTimeSettings(),
                modifiedSearchRequest.getKey(),
                coprocessorSettingsList,
                query.getParams(),
                dataStoreSettings);

        final ResultStore resultStore = resultStoreFactory.create(
                modifiedSearchRequest.getSearchRequestSource(),
                coprocessors);

        final String searchName = "Search '" + modifiedSearchRequest.getKey().toString() + "'";

        final SearchProcess searchProcess = new SearchProcess() {
            @Override
            public SearchTaskProgress getSearchTaskProgress() {
                return new SearchTaskProgress(
                        searchName,
                        "Searching...",
                        securityContext.getUserRef(),
                        Thread.currentThread().getName(),
                        "node", // Generic node name for now
                        System.currentTimeMillis(),
                        System.currentTimeMillis());
            }

            @Override
            public void onTerminate() {
            }
        };

        resultStore.setSearchProcess(searchProcess);

        final ExpressionOperator.Builder builder = ExpressionOperator.builder();
        if (query.getExpression() != null) {
            builder.addOperator(query.getExpression());
        }
        builder.addTerm(
                UpdatableTemporalStore.MAP_FIELD.getFldName(),
                ExpressionTerm.Condition.EQUALS,
                docRef.getName());

        final ExpressionCriteria criteria = new ExpressionCriteria(builder.build());

        //noinspection unused taskContext
        final Runnable runnable = taskContextFactory.context(searchName, taskContext -> {
            try {
                dao.search(criteria, entry -> {
                    final Map<String, Object> attributeMap = new HashMap<>();
                    attributeMap.put(UpdatableTemporalStore.MAP_FIELD.getFldName(), entry.getMap());
                    attributeMap.put(UpdatableTemporalStore.KEY_FIELD.getFldName(), entry.getKey());
                    attributeMap.put(UpdatableTemporalStore.TIME_FIELD.getFldName(), entry.getEffectiveTimeMs());
                    attributeMap.put(UpdatableTemporalStore.VALUE_FIELD.getFldName(), entry.getValue());

                    final String[] fields = coprocessors.getFieldIndex().getFields();
                    final Val[] arr = new Val[fields.length];
                    for (int i = 0; i < fields.length; i++) {
                        final String fieldName = fields[i];
                        Val val = ValNull.INSTANCE;
                        if (fieldName != null) {
                            final Object o = attributeMap.get(fieldName);
                            if (o != null) {
                                if (UpdatableTemporalStore.TIME_FIELD.getFldName().equals(fieldName)) {
                                    val = ValDate.create((Long) o);
                                } else {
                                    val = ValString.create(String.valueOf(o));
                                }
                            }
                        }
                        arr[i] = val;
                    }
                    coprocessors.accept(arr);
                });
            } catch (final RuntimeException e) {
                LOGGER.error(() -> "Error running SQL temporal store search: " + e.getMessage(), e);
                resultStore.addError(e);
            } finally {
                resultStore.signalComplete();
            }
        });

        CompletableFuture.runAsync(runnable, executor);

        return resultStore;
    }

    @Override
    public long count(final DocRef docRef) {
        checkPermission(docRef.getName(), DocumentPermission.VIEW);
        return dao.count(docRef.getName());
    }

    private void validateKey(final String key) {
        if (key != null && key.length() > 255) {
            throw new IllegalArgumentException("Key length exceeds 255 characters: " + key);
        }
    }
}
