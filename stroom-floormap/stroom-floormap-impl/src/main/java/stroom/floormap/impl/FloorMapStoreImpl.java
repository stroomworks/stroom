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
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.docstore.api.UniqueNameUtil;
import stroom.floormap.shared.FloorMapDoc;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Set;

/**
 * Singleton implementation of {@link FloorMapStore} built on {@link AbstractDocumentStore},
 * which handles the standard document CRUD, import/export and dependency delegation.
 * <p>
 * This class adds floor-map specific behaviour: it materialises newly created documents as a
 * processing user, performs a deep copy when duplicating, cleans up associated processor filters
 * when a floor map document is deleted, and remaps the facts/events store references it depends on.
 */
@Singleton
class FloorMapStoreImpl extends AbstractDocumentStore<FloorMapDoc> implements FloorMapStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(FloorMapStoreImpl.class);

    private final SecurityContext securityContext;
    private final Provider<FloorMapProcessors> floorMapProcessorsProvider;

    @Inject
    FloorMapStoreImpl(final StoreFactory storeFactory,
                      final FloorMapSerialiser serialiser,
                      final SecurityContext securityContext,
                      final Provider<FloorMapProcessors> floorMapProcessorsProvider) {
        super(storeFactory,
                securityContext,
                serialiser,
                FloorMapDoc.TYPE,
                FloorMapDoc::builder,
                FloorMapDoc::copy);
        this.securityContext = securityContext;
        this.floorMapProcessorsProvider = floorMapProcessorsProvider;
    }

    @Override
    public DocRef createDocument(final String name) {
        final DocRef docRef = getStore().createDocument(name);

        // Read and write as a processing user to ensure we are allowed as documents do not have permissions added to
        // them until after they are created in the store.
        securityContext.asProcessingUser(() -> {
            final FloorMapDoc floorMapDoc = getStore().readDocument(docRef);
            getStore().writeDocument(floorMapDoc);
        });
        return docRef;
    }

    @Override
    public DocRef copyDocument(final DocRef docRef,
                               final String name,
                               final boolean makeNameUnique,
                               final Set<String> existingNames) {
        final String newName = UniqueNameUtil.getCopyName(name, makeNameUnique, existingNames);
        final FloorMapDoc document = getStore().readDocument(docRef);
        return getStore().createDocument(newName,
                (uuid, docName, version, createTime, updateTime, createUser, updateUser) ->
                        document.copy()
                                .uuid(uuid)
                                .name(docName)
                                .version(version)
                                .createTimeMs(createTime)
                                .updateTimeMs(updateTime)
                                .createUser(createUser)
                                .updateUser(updateUser)
                                .build());
    }

    @Override
    public void deleteDocument(final DocRef docRef) {
        deleteProcessorFilter(docRef);
        super.deleteDocument(docRef);
    }

    @Override
    protected DependencyRemapFunction<FloorMapDoc> getDependencyRemapFunction() {
        return (doc, dependencyRemapper) -> {
            final FloorMapDoc.Builder builder = doc.copy();
            if (doc.getFactsStoreRef() != null) {
                builder.factsStoreRef(dependencyRemapper.remap(doc.getFactsStoreRef()));
            }
            if (doc.getEventsStoreRef() != null) {
                builder.eventsStoreRef(dependencyRemapper.remap(doc.getEventsStoreRef()));
            }
            return builder.build();
        };
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
