/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.floormap.impl;

import stroom.docref.DocRef;
import stroom.docref.DocRefInfo;
import stroom.docstore.api.Store;
import stroom.docstore.api.StoreFactory;
import stroom.docstore.api.UniqueNameUtil;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapDoc.Builder;
import stroom.importexport.api.ImportExportDocument;
import stroom.importexport.shared.ImportSettings;
import stroom.importexport.shared.ImportState;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.Message;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
class FloorMapStoreImpl implements FloorMapStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(FloorMapStoreImpl.class);

    private final Store<FloorMapDoc> store;
    private final SecurityContext securityContext;
    private final Provider<FloorMapProcessors> floorMapProcessorsProvider;

    @Inject
    FloorMapStoreImpl(final StoreFactory storeFactory,
                      final FloorMapSerialiser serialiser,
                      final SecurityContext securityContext,
                      final Provider<FloorMapProcessors> floorMapProcessorsProvider) {
        this.store = storeFactory.createStore(
                serialiser,
                FloorMapDoc.TYPE,
                FloorMapDoc::builder,
                FloorMapDoc::copy);
        this.securityContext = securityContext;
        this.floorMapProcessorsProvider = floorMapProcessorsProvider;
    }

    @Override
    public DocRef createDocument(final String name) {
        final DocRef docRef = store.createDocument(name);

        // Read and write as a processing user to ensure we are allowed as documents do not have permissions added to
        // them until after they are created in the store.
        securityContext.asProcessingUser(() -> {
            final FloorMapDoc floorMapDoc = store.readDocument(docRef);
            store.writeDocument(floorMapDoc);
        });
        return docRef;
    }

    @Override
    public DocRef copyDocument(final DocRef docRef,
                               final String name,
                               final boolean makeNameUnique,
                               final Set<String> existingNames) {
        final String newName = UniqueNameUtil.getCopyName(name, makeNameUnique, existingNames);
        final FloorMapDoc document = store.readDocument(docRef);
        return store.createDocument(newName,
                (uuid, docName, version, createTime, updateTime, createUser, updateUser) -> {
                    final Builder builder = document
                            .copy()
                            .uuid(uuid)
                            .name(docName)
                            .version(version)
                            .createTimeMs(createTime)
                            .updateTimeMs(updateTime)
                            .createUser(createUser)
                            .updateUser(updateUser);

                    return builder.build();
                });
    }

    @Override
    public DocRef moveDocument(final DocRef docRef) {
        return store.moveDocument(docRef);
    }

    @Override
    public DocRef renameDocument(final DocRef docRef, final String name) {
        return store.renameDocument(docRef, name);
    }

    @Override
    public void deleteDocument(final DocRef docRef) {
        deleteProcessorFilter(docRef);
        store.deleteDocument(docRef);
    }

    @Override
    public DocRefInfo info(final DocRef docRef) {
        return store.info(docRef);
    }

    @Override
    public Map<DocRef, Set<DocRef>> getDependencies() {
        return store.getDependencies(null);
    }

    @Override
    public Set<DocRef> getDependencies(final DocRef docRef) {
        return store.getDependencies(docRef, null);
    }

    @Override
    public void remapDependencies(final DocRef docRef,
                                  final Map<DocRef, DocRef> remappings) {
        store.remapDependencies(docRef, remappings, null);
    }

    @Override
    public FloorMapDoc readDocument(final DocRef docRef) {
        return store.readDocument(docRef);
    }

    @Override
    public FloorMapDoc writeDocument(final FloorMapDoc document) {
        return store.writeDocument(document);
    }

    @Override
    public Set<DocRef> listDocuments() {
        return store.listDocuments();
    }

    @Override
    public DocRef importDocument(final DocRef docRef,
                                 final ImportExportDocument importExportDocument,
                                 final ImportState importState,
                                 final ImportSettings importSettings) {
        return store.importDocument(docRef, importExportDocument, importState, importSettings);
    }

    @Override
    public ImportExportDocument exportDocument(final DocRef docRef,
                                               final boolean omitAuditFields,
                                               final List<Message> messageList) {
        return store.exportDocument(docRef, omitAuditFields, messageList);
    }

    @Override
    public String getType() {
        return store.getType();
    }

    @Override
    public Set<DocRef> findAssociatedNonExplorerDocRefs(final DocRef docRef) {
        return null;
    }

    @Override
    public List<DocRef> list() {
        return store.list();
    }

    @Override
    public List<DocRef> findByNames(final List<String> name, final boolean allowWildCards) {
        return store.findByNames(name, allowWildCards);
    }

    @Override
    public Map<String, String> getIndexableData(final DocRef docRef) {
        return store.getIndexableData(docRef);
    }

    private void deleteProcessorFilter(final DocRef docRef) {
        try {
            final FloorMapDoc floorMapDoc = readDocument(docRef);
            floorMapProcessorsProvider.get().deleteProcessorFilters(floorMapDoc);
        } catch (final RuntimeException e) {
            LOGGER.debug(e::getMessage, e);
        }
    }
}
