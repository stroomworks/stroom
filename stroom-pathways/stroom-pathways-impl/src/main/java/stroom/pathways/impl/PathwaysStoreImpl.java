/*
 * Copyright 2017 Crown Copyright
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

package stroom.pathways.impl;

import stroom.cluster.lock.api.ClusterLockService;
import stroom.docref.DocRef;
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.StoreFactory;
import stroom.pathways.shared.PathwaysDoc;
import stroom.security.api.SecurityContext;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
public class PathwaysStoreImpl
        extends AbstractDocumentStore<PathwaysDoc>
        implements PathwaysStore {

    private final Provider<ClusterLockService> clusterLockServiceProvider;

    @Inject
    public PathwaysStoreImpl(final StoreFactory storeFactory,
                             final SecurityContext securityContext,
                             final PathwaysSerialiser serialiser,
                             final Provider<ClusterLockService> clusterLockServiceProvider) {
        super(storeFactory,
                securityContext,
                serialiser,
                PathwaysDoc.TYPE,
                PathwaysDoc::builder,
                PathwaysDoc::copy);
        this.clusterLockServiceProvider = clusterLockServiceProvider;
    }

    /**
     * Registers this document's reference to the feed it writes findings to, so it appears on the
     * Dependencies screen, is reported when the feed is deleted, and is rewritten when a copy or an
     * import lands the feed under a different uuid.
     *
     * <p>This does nothing for a rename. {@link stroom.docref.DocRef} equality is on uuid alone, so a
     * renamed target is the same reference and nothing here is rewritten; the Dependencies screen gets
     * the new name from {@code DocDependencyService.propagateName}, and the name stored inside this
     * document stays as it was.
     */
    @Override
    protected DependencyRemapFunction<PathwaysDoc> getDependencyRemapFunction() {
        return (doc, remapper) -> doc.getInfoFeed() == null
                ? doc
                : doc.copy()
                        .infoFeed(remapper.remap(doc.getInfoFeed()))
                        .build();
    }

    @Override
    public void deleteDocument(final DocRef docRef) {
        super.deleteDocument(docRef);

        // Clean up the per-shard cluster locks this document's processing takes. Lock rows accumulate
        // in cluster_lock as shards are worked and are never removed automatically, so they have to go
        // with the document or they stay for good.
        if (docRef != null && docRef.getUuid() != null) {
            try {
                final ClusterLockService clusterLockService = clusterLockServiceProvider.get();
                clusterLockService.deleteLocks(PathwaysProcessor.lockPrefix(docRef.getUuid()));
            } catch (final Exception e) {
                // Ignore lock deletion failures to avoid failing the document delete itself.
            }
        }
    }
}
