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

package stroom.query.common.v2;

import stroom.lmdb2.LmdbEnvDir;
import stroom.lmdb2.LmdbEnvDirFactory;
import stroom.query.planner.join.BuildSideLookup;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.UUID;

/**
 * Creates the {@link BuildSideLookup} a join's build (right) side is realised into (see
 * items C1/C2). Injected into {@code JoinSearchProvider}
 * so that {@code stroom-searchable-impl} does not need its own LMDB dependency - this factory owns the LMDB
 * env-dir/config wiring, exactly as {@code LmdbDataStoreFactory} does for ordinary result stores, and hands back
 * a lookup that transparently spills to disk past a threshold.
 *
 * <p>The spill store is created lazily (only if the threshold is crossed) under the same configured result-store
 * LMDB directory ordinary searches spill to, in a per-store unique subdirectory that is deleted when the lookup is
 * closed.</p>
 */
@Singleton
public class JoinBuildSideLookupFactory {

    private final LmdbEnvDirFactory lmdbEnvDirFactory;
    private final Provider<SearchResultStoreConfig> resultStoreConfigProvider;

    /**
     * @param lmdbEnvDirFactory         resolves/creates the temporary LMDB directory; must not be null.
     * @param resultStoreConfigProvider supplies the current result-store config (its LMDB sizing/dir is reused for
     *                                  join spill); must not be null - a live {@link Provider} so a runtime config
     *                                  change is honoured by the next join, matching {@code LmdbDataStoreFactory}.
     */
    @Inject
    public JoinBuildSideLookupFactory(final LmdbEnvDirFactory lmdbEnvDirFactory,
                                      final Provider<SearchResultStoreConfig> resultStoreConfigProvider) {
        this.lmdbEnvDirFactory = Objects.requireNonNull(lmdbEnvDirFactory, "lmdbEnvDirFactory");
        this.resultStoreConfigProvider =
                Objects.requireNonNull(resultStoreConfigProvider, "resultStoreConfigProvider");
    }

    /**
     * Creates a lookup that stays on the heap until it exceeds {@code maxHeapRows} rows <i>or</i>
     * {@code maxHeapBytes} of estimated heap, then spills to disk.
     *
     * <p><b>Preconditions:</b> {@code maxHeapRows} and {@code maxHeapBytes} must each be {@code >= 0} (see
     * {@code JoinConfig.getMaxHeapBuildRows}/{@code getMaxHeapBuildBytes}).<br>
     * <b>Postconditions:</b> never null; the caller owns closing the returned lookup (which releases any spill
     * store's temporary directory).</p>
     */
    public BuildSideLookup create(final long maxHeapRows, final long maxHeapBytes) {
        return new SpillingBuildSideLookup(maxHeapRows, maxHeapBytes, this::createSpillStore);
    }

    private LmdbJoinBuildStore createSpillStore() {
        final SearchResultStoreConfig resultStoreConfig = resultStoreConfigProvider.get();
        final LmdbEnvDir envDir = lmdbEnvDirFactory
                .builder()
                .config(resultStoreConfig.getLmdbConfig())
                .subDir("join_" + UUID.randomUUID())
                .build();
        return new LmdbJoinBuildStore(envDir, resultStoreConfig.getLmdbConfig());
    }
}
