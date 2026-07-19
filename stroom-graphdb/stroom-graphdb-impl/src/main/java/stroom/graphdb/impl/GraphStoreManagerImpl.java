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

import stroom.graphdb.shared.GraphDbDoc;
import stroom.util.io.PathCreator;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default {@link GraphStoreManager}. Resolves each doc's on-disk directory to {@code <app path>/graphdb/<uuid>}
 * via {@link PathCreator} - the same mechanism {@code stroom.planb.impl.dao.StatePaths} uses for its own root,
 * without yet introducing a dedicated config surface (P5 hardening; see {@link GraphDbDocCacheImpl}'s Javadoc for
 * the same deferral on the cache side).
 */
@Singleton
public class GraphStoreManagerImpl implements GraphStoreManager {

    private static final String ROOT_DIR_NAME = "graphdb";

    private final PathCreator pathCreator;
    private final ConcurrentMap<String, GraphStores> openStores = new ConcurrentHashMap<>();

    @Inject
    public GraphStoreManagerImpl(final PathCreator pathCreator) {
        this.pathCreator = Objects.requireNonNull(pathCreator, "pathCreator");
    }

    @Override
    public GraphStores getOrOpen(final GraphDbDoc doc) {
        Objects.requireNonNull(doc, "doc");
        return openStores.computeIfAbsent(doc.getUuid(), uuid ->
                GraphStores.open(directoryFor(uuid), doc, false));
    }

    @Override
    public void delete(final String uuid) {
        Objects.requireNonNull(uuid, "uuid");
        final GraphStores stores = openStores.remove(uuid);
        if (stores != null) {
            stores.close();
        }
        GraphStores.delete(directoryFor(uuid));
    }

    private Path directoryFor(final String uuid) {
        return pathCreator.toAppPath(ROOT_DIR_NAME).resolve(uuid);
    }
}
