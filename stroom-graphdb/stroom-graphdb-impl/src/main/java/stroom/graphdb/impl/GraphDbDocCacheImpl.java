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
    private final SecurityContext securityContext;
    private final DocFinder docFinder;

    /**
     * <b>Preconditions:</b> no parameter is null (enforced by the Guice binding graph supplying them).
     */
    @Inject
    GraphDbDocCacheImpl(final CacheManager cacheManager,
                       final GraphDbDocStore graphDbDocStore,
                       final SecurityContext securityContext,
                       final DocFinder docFinder) {
        this.graphDbDocStore = graphDbDocStore;
        this.securityContext = securityContext;
        this.docFinder = docFinder;
        cache = cacheManager.createLoadingCache(CACHE_NAME, CacheConfig::new, this::create);
    }

    private GraphDbDoc create(final String name) {
        return securityContext.asProcessingUserResult(() -> {
            final List<DocRef> list = docFinder.findByName(GraphDbDoc.TYPE, name);
            if (list.size() > 1) {
                throw new RuntimeException("Unexpectedly found more than one graph db doc with name: " + name);
            }
            if (list.isEmpty()) {
                throw new NullPointerException("No graph db doc can be found for name: " + name);
            }

            final DocRef docRef = list.getFirst();
            final GraphDbDoc loaded = graphDbDocStore.readDocument(docRef);
            if (loaded == null) {
                throw new NullPointerException("No graph db doc can be found for: " + docRef);
            }

            return loaded;
        });
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
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public void onChange(final EntityEvent event) {
        LOGGER.debug("Received event {}", event);
        final EntityAction eventAction = event.getAction();

        switch (eventAction) {
            case UPDATE, DELETE, CLEAR_CACHE -> {
                LOGGER.debug("Clearing cache");
                clear();
            }
            default -> LOGGER.debug("Unexpected event action {}", eventAction);
        }
    }
}
