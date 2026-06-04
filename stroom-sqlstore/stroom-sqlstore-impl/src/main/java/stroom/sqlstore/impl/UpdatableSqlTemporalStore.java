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
import stroom.query.language.functions.ValString;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.task.api.TaskContextFactory;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Singleton
public class UpdatableSqlTemporalStore implements UpdatableTemporalStore {

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

        final ExpressionCriteria criteria = new ExpressionCriteria(query.getExpression());

        CompletableFuture.runAsync(() -> {
            taskContextFactory.context(searchName, taskContext -> {
                try {
                    dao.search(criteria, entry -> {
                        final Val[] values = new Val[FIELDS.size()];
                        values[0] = ValString.create(entry.getMap());
                        values[1] = ValString.create(entry.getKey());
                        values[2] = ValDate.create(entry.getEffectiveTimeMs());
                        values[3] = ValString.create(entry.getValue());
                        coprocessors.accept(values);
                    });
                } finally {
                    coprocessors.getCompletionState().signalComplete();
                }
            }).run();
        }, executor);

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
