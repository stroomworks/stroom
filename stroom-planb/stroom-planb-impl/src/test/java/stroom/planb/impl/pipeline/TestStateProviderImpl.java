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

package stroom.planb.impl.pipeline;

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.planb.impl.PlanBConfig;
import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.data.GetRequest;
import stroom.planb.impl.data.PlanBQueryService;
import stroom.planb.shared.PlanBDoc;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.security.api.SecurityContext;
import stroom.util.cache.CacheConfig;
import stroom.util.shared.PermissionException;

import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F1/SEC-1 regression (docs/query-graphdb-review-report.md): {@link StateProviderImpl#getState} must not catch
 * every exception and downgrade it to a {@code ValErr} - {@code JoinExecutor.broadcastLookupProbe} would then
 * treat that {@code ValErr} as a matched row and embed the error text as the joined value, silently turning a
 * permission deny (or any other real lookup failure) into junk output. Only a confirmed absence may become
 * {@link ValNull#INSTANCE}; every real exception (in particular {@link PermissionException} from the
 * {@code USE}-permission check, and a {@link NumberFormatException} from a key shape mismatched to the store's
 * type) must propagate so the search fails cleanly instead.
 *
 * <p>Also covers F16's config wiring: the lookup cache's size/expiry come from {@link PlanBConfig#
 * getStateValueCache()} (via an injected {@link CacheManager}/{@code Provider<PlanBConfig>}) rather than a
 * hardcoded Caffeine {@code maximumSize(1000)}.</p>
 */
@ExtendWith(MockitoExtension.class)
class TestStateProviderImpl {

    @Mock
    private PlanBDocCache stateDocCache;
    @Mock
    private PlanBQueryService planBQueryService;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Provider<PlanBConfig> planBConfigProvider;

    private StateProviderImpl stateProvider;

    @BeforeEach
    void setUp() {
        // The behavioural tests below don't care about real caching/eviction, only that a lookup reaches
        // planBQueryService - so the faked cache just delegates every get() straight through, matching what the
        // real cache would do on a miss (every key here is looked up exactly once per test).
        final LoadingStroomCache<GetRequest, Val> cache = mock(LoadingStroomCache.class);
        lenient().when(cache.get(any()))
                .thenAnswer(invocation -> planBQueryService.getVal(invocation.getArgument(0)));
        when(cacheManager.<GetRequest, Val>createLoadingCache(anyString(), any(), any())).thenReturn(cache);

        stateProvider = new StateProviderImpl(
                stateDocCache, planBQueryService, securityContext, cacheManager, planBConfigProvider);
        // useAsReadResult just runs the supplied block - it elevates scope, not identity (see
        // GraphDbDocCacheImpl/PlanBDocCacheImpl's real behaviour), so the fake simply invokes it. Not every
        // test below calls getState (the F16 config-wiring/default-value tests don't touch securityContext at
        // all), so this is lenient.
        lenient().when(securityContext.useAsReadResult(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
    }

    @Test
    void construction_sourcesTheLookupCachesConfigFromPlanBConfig() {
        // F16: the CacheManager's config supplier must resolve to PlanBConfig#getStateValueCache (via the
        // injected Provider), not a hardcoded size - so re-configuring PlanBConfig changes the cache without a
        // code change.
        final CacheManager freshCacheManager = mock(CacheManager.class);
        final Provider<PlanBConfig> freshConfigProvider = mock(Provider.class);
        final CacheConfig configuredCache = CacheConfig.builder().maximumSize(42L).build();
        when(freshConfigProvider.get()).thenReturn(PlanBConfig.builder().stateValueCache(configuredCache).build());
        final ArgumentCaptor<Supplier<CacheConfig>> supplierCaptor = ArgumentCaptor.forClass(Supplier.class);
        when(freshCacheManager.<GetRequest, Val>createLoadingCache(anyString(), supplierCaptor.capture(), any()))
                .thenReturn(mock(LoadingStroomCache.class));

        new StateProviderImpl(
                stateDocCache, planBQueryService, securityContext, freshCacheManager, freshConfigProvider);

        assertThat(supplierCaptor.getValue().get().getMaximumSize()).isEqualTo(42L);
    }

    @Test
    void planBConfig_defaultStateValueCacheMaximumSize_matchesThePreviousHardcodedSize() {
        // F16: the new config property's default preserves the cache's previous hardcoded size, so an
        // un-configured deployment sees no behaviour change.
        assertThat(new PlanBConfig().getStateValueCache().getMaximumSize()).isEqualTo(1000L);
    }

    @Test
    void permissionDenied_propagates_ratherThanBecomingAValErr() {
        when(stateDocCache.get("users"))
                .thenThrow(new PermissionException(null, "You are not authorised to read Users"));

        assertThatThrownBy(() -> stateProvider.getState("users", "1", 0L))
                .isInstanceOf(PermissionException.class)
                .hasMessageContaining("You are not authorised to read Users");
    }

    @Test
    void mismatchedKeyShapeAgainstTheUnderlyingStore_propagates_ratherThanBecomingAValErr() {
        // e.g. a non-numeric key probed against a RANGED_STATE/TEMPORAL_RANGED_STATE store, which parses the
        // key as a long - see PlanBQueryService.getLocalValue.
        final PlanBDoc doc = mock(PlanBDoc.class);
        when(stateDocCache.get("ranges")).thenReturn(doc);
        when(planBQueryService.getVal(any())).thenThrow(new NumberFormatException("For input string: \"abc\""));

        assertThatThrownBy(() -> stateProvider.getState("ranges", "abc", 0L))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void genuineHit_returnsTheLookedUpValue_unaffectedByTheErrorHandlingChange() {
        final PlanBDoc doc = mock(PlanBDoc.class);
        when(stateDocCache.get("users")).thenReturn(doc);
        when(planBQueryService.getVal(any())).thenReturn(ValLong.create(42L));

        final Val result = stateProvider.getState("users", "1", 0L);

        assertThat(result).isEqualTo(ValLong.create(42L));
    }

    @Test
    void noSuchStoreByName_isATrueAbsence_returnsValNull_notAnException() {
        // Defensive not-found path: if PlanBDocCache#get were ever to legitimately return null for an unknown
        // name (rather than throwing), that must still surface as an ordinary miss, not an error.
        when(stateDocCache.get("missing")).thenReturn(null);

        final Val result = stateProvider.getState("missing", "1", 0L);

        assertThat(result).isEqualTo(ValNull.INSTANCE);
    }

    @Test
    void keyNotPresentInAnExistingStore_returnsValNull_unaffectedByTheErrorHandlingChange() {
        final PlanBDoc doc = mock(PlanBDoc.class);
        when(stateDocCache.get("users")).thenReturn(doc);
        when(planBQueryService.getVal(any())).thenReturn(ValNull.INSTANCE);

        final Val result = stateProvider.getState("users", "unknown-key", 0L);

        assertThat(result).isEqualTo(ValNull.INSTANCE);
    }
}
