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

package stroom.sqlstore.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.StoreFactory;
import stroom.security.api.SecurityContext;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Set;

@Singleton
public class SqlTemporalStoreDocStoreImpl
        extends AbstractDocumentStore<SqlTemporalStoreDoc>
        implements SqlTemporalStoreDocStore {

    @Inject
    public SqlTemporalStoreDocStoreImpl(
            final StoreFactory storeFactory,
            final SecurityContext securityContext,
            final SqlTemporalStoreSerialiser serialiser) {
        super(storeFactory,
                securityContext,
                serialiser,
                SqlTemporalStoreDoc.TYPE,
                SqlTemporalStoreDoc::builder,
                SqlTemporalStoreDoc::copy);
    }

    @Override
    public DocRef createDocument(final String name) {
        checkNameNotInUse(name);
        return super.createDocument(name);
    }

    @Override
    public DocRef copyDocument(final DocRef docRef,
                               final String name,
                               final boolean makeNameUnique,
                               final Set<String> existingNames) {
        checkNameNotInUse(name);
        return getStore().copyDocument(docRef.getUuid(), name);
    }

    @Override
    public DocRef renameDocument(final DocRef docRef, final String name) {
        checkNameNotInUseByOther(name, docRef.getUuid());
        return super.renameDocument(docRef, name);
    }

    /**
     * Ensures no SqlTemporalStoreDoc already uses the given name.
     * Used by create and copy operations where any existing match is a conflict.
     */
    private void checkNameNotInUse(final String name) {
        final boolean inUse = getStore().list().stream()
                .anyMatch(dr -> dr.getName().equals(name));
        if (inUse) {
            throwNameClash(name);
        }
    }

    /**
     * Ensures no other SqlTemporalStoreDoc already uses the given name.
     * Used by rename operations where the document being renamed is excluded.
     */
    private void checkNameNotInUseByOther(final String name, final String selfUuid) {
        final boolean inUse = getStore().list().stream()
                .anyMatch(dr -> dr.getName().equals(name)
                        && !dr.getUuid().equals(selfUuid));
        if (inUse) {
            throwNameClash(name);
        }
    }

    private void throwNameClash(final String name) {
        throw new EntityServiceException(
                "A SqlTemporalStore with name '" + name + "' already exists. "
                + "Names must be unique because they are used as map identifiers.");
    }
}
