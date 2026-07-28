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

package stroom.graphdb.impl;

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.cache.CacheConfig;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Default {@link GraphDbDocCache}, mirroring {@code stroom.planb.impl.PlanBDocCacheImpl}. The cache uses a fixed
 * default {@link CacheConfig} rather than a dedicated config surface — wiring a configurable size/expiry into the
 * global config tree is P5 hardening work, not part of this scaffold.
 */
@Singleton
@EntityEventHandler(
        type = GraphDbDoc.TYPE,
        action = {EntityAction.DELETE, EntityAction.UPDATE, EntityAction.CLEAR_CACHE})
public class GraphDbDocCacheImpl implements GraphDbDocCache, Clearable, EntityEvent.Handler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphDbDocCacheImpl.class);

    private static final String CACHE_NAME = "Graph Db Doc Cache";

    private final GraphDbDocStore graphDbDocStore;
    private final LoadingStroomCache<String, GraphDbDoc> cache;
    private final LoadingStroomCache<String, GraphDbDoc> uuidCache;
    private final SecurityContext securityContext;
    private final DocFinder docFinder;
    private final GraphStoreManager graphStoreManager;

    /**
     * <b>Preconditions:</b> no parameter is null (enforced by the Guice binding graph supplying them).
     */
    @Inject
    GraphDbDocCacheImpl(final CacheManager cacheManager,
                       final GraphDbDocStore graphDbDocStore,
                       final SecurityContext securityContext,
                       final DocFinder docFinder,
                       final GraphStoreManager graphStoreManager) {
        this.graphDbDocStore = graphDbDocStore;
        this.securityContext = securityContext;
        this.docFinder = docFinder;
        this.graphStoreManager = graphStoreManager;
        cache = cacheManager.createLoadingCache(CACHE_NAME, CacheConfig::new, this::create);
        uuidCache = cacheManager.createLoadingCache(
                CACHE_NAME + " (by UUID)", CacheConfig::new, this::createByUuid);
    }

    /**
     * Code-review fix: previously threw a plain {@link NullPointerException} for both "not found" cases below,
     * which a caller could only distinguish from a genuine NPE bug elsewhere in this same call chain by string-
     * matching the message - and {@link GraphStoreStatsAdapter} did exactly that, catching
     * {@code NullPointerException} broadly enough to also silently swallow an unrelated real defect. Now throws
     * {@link NoSuchElementException} when no doc exists for {@code name} at all (no {@link DocRef} to attach to
     * a more specific exception), or {@link DocumentNotFoundException} (an existing, precedented type also used
     * by {@code stroom.planb.impl.data.ShardManager}) when a matching {@code DocRef} was found but the store no
     * longer has a document for it.
     */
    private GraphDbDoc create(final String name) {
        return securityContext.asProcessingUserResult(() -> {
            final List<DocRef> list = docFinder.findByName(GraphDbDoc.TYPE, name);
            if (list.size() > 1) {
                throw new RuntimeException("Unexpectedly found more than one graph db doc with name: " + name);
            }
            if (list.isEmpty()) {
                throw new NoSuchElementException("No graph db doc can be found for name: " + name);
            }

            final DocRef docRef = list.getFirst();
            final GraphDbDoc loaded = graphDbDocStore.readDocument(docRef);
            if (loaded == null) {
                throw new DocumentNotFoundException(docRef);
            }

            return loaded;
        });
    }

    /**
     * Loads by UUID, which needs no name search at all - so unlike {@link #create} it cannot fail on an ambiguous
     * name, and is unaffected by a rename.
     */
    private GraphDbDoc createByUuid(final String uuid) {
        return securityContext.asProcessingUserResult(() -> {
            final DocRef docRef = DocRef.builder().type(GraphDbDoc.TYPE).uuid(uuid).build();
            final GraphDbDoc loaded = graphDbDocStore.readDocument(docRef);
            if (loaded == null) {
                throw new DocumentNotFoundException(docRef);
            }
            return loaded;
        });
    }

    @Override
    public GraphDbDoc getByUuid(final String uuid) {
        Objects.requireNonNull(uuid, "Null key supplied");
        final GraphDbDoc doc = uuidCache.get(uuid);

        final DocRef docRef = doc.asDocRef();
        if (!securityContext.hasDocumentPermission(docRef, DocumentPermission.USE)) {
            throw new PermissionException(
                    securityContext.getUserRef(),
                    "You are not authorised to read " + docRef);
        }
        return doc;
    }

    @Override
    public GraphDbDoc get(final String name) {
        Objects.requireNonNull(name, "Null key supplied");
        final GraphDbDoc doc = cache.get(name);

        final DocRef docRef = doc.asDocRef();
        if (!securityContext.hasDocumentPermission(docRef, DocumentPermission.USE)) {
            throw new PermissionException(
                    securityContext.getUserRef(),
                    "You are not authorised to read " + docRef);
        }

        return doc;
    }

    @Override
    public void remove(final String name) {
        Objects.requireNonNull(name, "Null key supplied");
        cache.invalidate(name);
        // The UUID cache is keyed by UUID, so a name gives no entry to invalidate precisely. Clearing it is the
        // safe choice: a rename leaves the UUID mapping valid but its cached document stale.
        uuidCache.clear();
    }

    @Override
    public void clear() {
        cache.clear();
        uuidCache.clear();
    }

    @Override
    public void onChange(final EntityEvent event) {
        LOGGER.debug("Received event {}", event);
        final EntityAction eventAction = event.getAction();

        switch (eventAction) {
            case DELETE -> {
                LOGGER.debug("Clearing cache and deleting physical stores");
                clear();
                graphStoreManager.delete(event.getDocRef().getUuid());
            }
            case UPDATE, CLEAR_CACHE -> {
                LOGGER.debug("Clearing cache");
                clear();
            }
            default -> LOGGER.debug("Unexpected event action {}", eventAction);
        }
    }
}
