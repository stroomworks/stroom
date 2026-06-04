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

import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.shared.UnknownStoreException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
public class UpdatableTemporalStoreProvider {

    private final Provider<UpdatableSqlTemporalStore> sqlTemporalStoreProvider;
    private final SqlStoreDocStore sqlStoreDocStore;

    @Inject
    public UpdatableTemporalStoreProvider(final Provider<UpdatableSqlTemporalStore> sqlTemporalStoreProvider,
                                          final SqlStoreDocStore sqlStoreDocStore) {
        this.sqlTemporalStoreProvider = sqlTemporalStoreProvider;
        this.sqlStoreDocStore = sqlStoreDocStore;
    }

    public UpdatableTemporalStore get(final String mapName) {
        // Look for a SqlStoreDoc with this name.
        final boolean exists = sqlStoreDocStore.list().stream()
                .anyMatch(docRef -> docRef.getName().equals(mapName));

        if (exists) {
            return sqlTemporalStoreProvider.get();
        }

        // TODO: Future: Check for PlanBDoc here too.

        throw new UnknownStoreException("Unknown store: " + mapName);
    }
}
