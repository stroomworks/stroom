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

package stroom.planb.impl.pipeline;

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.planb.impl.PlanBConfig;
import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.data.GetRequest;
import stroom.planb.impl.data.PlanBQueryService;
import stroom.planb.shared.PlanBDoc;
import stroom.query.language.functions.StateProvider;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.security.api.SecurityContext;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Locale;
import java.util.Optional;

@Singleton
public class StateProviderImpl implements StateProvider {

    private static final String CACHE_NAME = "Plan B State Value Cache";

    private final PlanBDocCache stateDocCache;
    private final LoadingStroomCache<GetRequest, Val> cache;
    private final PlanBQueryService planBQueryService;
    private final SecurityContext securityContext;

    @Inject
    public StateProviderImpl(final PlanBDocCache stateDocCache,
                             final PlanBQueryService planBQueryService,
                             final SecurityContext securityContext,
                             final CacheManager cacheManager,
                             final Provider<PlanBConfig> planBConfigProvider) {
        this.stateDocCache = stateDocCache;
        this.planBQueryService = planBQueryService;
        this.securityContext = securityContext;
        // F16: the cache's maximumSize/expireAfterWrite were previously hardcoded (1000 entries, 10 minutes) -
        // now sourced from PlanBConfig#getStateValueCache (same 1000-entry default), so a broadcast-lookup join
        // whose probe side has a high-cardinality key set can be tuned without a code change - see
        // docs/query-graphdb-review-report.md finding F16.
        cache = cacheManager.createLoadingCache(
                CACHE_NAME,
                () -> planBConfigProvider.get().getStateValueCache(),
                planBQueryService::getVal);
    }

    /**
     * Looks up a single enrichment/state value.
     *
     * <p><b>Not-found vs. error</b>: every exception that can reach this method is a real failure - e.g. a
     * {@link stroom.util.shared.PermissionException} from the {@code USE}-permission check in
     * {@link PlanBDocCache#get}, or a {@link NumberFormatException} from a key shape mismatched to the store's
     * type (e.g. a non-numeric key against a {@code RANGED_STATE}/{@code TEMPORAL_RANGED_STATE} store) - and is
     * deliberately left to propagate rather than being caught and converted to a {@code ValErr}. A genuine
     * "no value for this key" is already represented without throwing at all: {@link ValNull#INSTANCE}, either
     * from the {@code orElse} below (no Plan B doc/store known by this name) or from
     * {@link PlanBQueryService#getVal}'s own not-found handling (a store that exists but holds nothing for the
     * key). This split matters because the caller ({@code JoinExecutor.broadcastLookupProbe}) treats a
     * {@code ValErr} as a failed join it must abort, and a {@code ValNull} as an ordinary miss - silently
     * downgrading a real error (in particular a permission deny) to a {@code ValErr} would let it be embedded
     * as a matched row's value instead of failing the search (see
     * {@code docs/query-graphdb-review-report.md}, findings F1/SEC-1).</p>
     *
     * <p><b>Preconditions:</b> {@code mapName} and {@code keyName} must not be null.<br>
     * <b>Postconditions:</b> returns {@link ValNull#INSTANCE} for a confirmed absence; returns a real
     * {@link Val} for a hit; otherwise throws (never returns a {@code ValErr}).</p>
     */
    @Override
    public Val getState(final String mapName, final String keyName, final long effectiveTimeMs) {
        final String docName = mapName.toLowerCase(Locale.ROOT);
        final Optional<PlanBDoc> stateOptional = securityContext.useAsReadResult(() ->
                Optional.ofNullable(stateDocCache.get(docName)));
        return stateOptional
                .map(stateDoc -> {
                    final GetRequest request = new GetRequest(docName, keyName, effectiveTimeMs);
                    return cache.get(request);
                })
                .orElse(ValNull.INSTANCE);
    }
}
