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

package stroom.domaintype.impl;

import stroom.docref.DocRef;
import stroom.docref.DocRefInfo;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.Store;
import stroom.docstore.api.StoreFactory;
import stroom.domaintype.shared.DomainTypeDoc;
import stroom.importexport.api.ImportExportDocument;
import stroom.importexport.shared.ImportSettings;
import stroom.importexport.shared.ImportState;
import stroom.util.shared.Message;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Singleton
public class DomainTypeStoreImpl implements DomainTypeStore {

    private final Store<DomainTypeDoc> store;

    @Inject
    public DomainTypeStoreImpl(final StoreFactory storeFactory,
                               final DomainTypeSerialiser serialiser) {
        this.store = storeFactory.createStore(
                serialiser,
                DomainTypeDoc.TYPE,
                DomainTypeDoc::builder,
                DomainTypeDoc::copy);
    }

    @Override
    public DocRef createDocument(final String name) {
        return store.createDocument(name);
    }

    @Override
    public DocRef createDocument(final String name, final DocumentCreator<DomainTypeDoc> documentCreator) {
        return store.createDocument(name, documentCreator);
    }

    @Override
    public DomainTypeDoc readDocument(final DocRef docRef) {
        return store.readDocument(docRef);
    }

    @Override
    public DomainTypeDoc writeDocument(final DomainTypeDoc document) {
        return store.writeDocument(document);
    }

    @Override
    public void deleteDocument(final DocRef docRef) {
        store.deleteDocument(docRef);
    }

    @Override
    public DocRef copyDocument(final DocRef docRef,
                               final String name,
                               final boolean makeUnique,
                               final Set<String> existingNames) {
        final String newUuid = store.copyDocument(docRef.getUuid(), name).getUuid();
        return DocRef.builder(DomainTypeDoc.TYPE)
                .uuid(newUuid)
                .name(name)
                .build();
    }

    @Override
    public DocRef copyDocument(final String originalUuid, final String newName) {
        return store.copyDocument(originalUuid, newName);
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
    public ImportExportDocument exportDocument(final DocRef docRef,
                                               final boolean omitAuditFields,
                                               final List<Message> messageList,
                                               final Function<DomainTypeDoc, DomainTypeDoc> function) {
        return store.exportDocument(docRef, omitAuditFields, messageList, function);
    }

    @Override
    public boolean exists(final DocRef docRef) {
        return store.exists(docRef);
    }

    @Override
    public List<DocRef> list() {
        return store.list();
    }

    @Override
    public DocRefInfo info(final DocRef docRef) {
        return store.info(docRef);
    }

    @Override
    public String getType() {
        return DomainTypeDoc.TYPE;
    }

    @Override
    public List<DocRef> findDocRefsEmbeddedIn(final DocRef parent) {
        return store.findDocRefsEmbeddedIn(parent);
    }

    @Override
    public Map<DocRef, Set<DocRef>> getDependencies() {
        return store.getDependencies(null);
    }

    @Override
    public Map<DocRef, Set<DocRef>> getDependencies(final DependencyRemapFunction<DomainTypeDoc> mapper) {
        return store.getDependencies(mapper);
    }

    @Override
    public Set<DocRef> getDependencies(final DocRef docRef) {
        return store.getDependencies(docRef, null);
    }

    @Override
    public Set<DocRef> getDependencies(final DocRef docRef, final DependencyRemapFunction<DomainTypeDoc> mapper) {
        return store.getDependencies(docRef, mapper);
    }

    @Override
    public void remapDependencies(final DocRef docRef, final Map<DocRef, DocRef> remappings) {
        store.remapDependencies(docRef, remappings, null);
    }

    @Override
    public void remapDependencies(final DocRef docRef,
                                  final Map<DocRef, DocRef> remappings,
                                  final DependencyRemapFunction<DomainTypeDoc> mapper) {
        store.remapDependencies(docRef, remappings, mapper);
    }

    @Override
    public List<DocRef> findByName(final String name) {
        return store.findByName(name);
    }

    @Override
    public List<DocRef> findByNames(final List<String> names, final boolean ignoreCase) {
        return store.findByNames(names, ignoreCase);
    }

    @Override
    public Set<DocRef> listDocuments() {
        return new HashSet<>(store.list());
    }

    @Override
    public Set<DocRef> findAssociatedNonExplorerDocRefs(final DocRef docRef) {
        return Collections.emptySet();
    }

    @Override
    public Map<String, String> getIndexableData(final DocRef docRef) {
        return store.getIndexableData(docRef);
    }
}
