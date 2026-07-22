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

import stroom.planb.impl.PlanBDocCache;
import stroom.planb.impl.data.PlanBQueryService;
import stroom.planb.shared.PlanBDoc;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.security.api.SecurityContext;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 */
@ExtendWith(MockitoExtension.class)
class TestStateProviderImpl {

    @Mock
    private PlanBDocCache stateDocCache;
    @Mock
    private PlanBQueryService planBQueryService;
    @Mock
    private SecurityContext securityContext;

    private StateProviderImpl stateProvider;

    @BeforeEach
    void setUp() {
        stateProvider = new StateProviderImpl(stateDocCache, planBQueryService, securityContext);
        // useAsReadResult just runs the supplied block - it elevates scope, not identity (see
        // GraphDbDocCacheImpl/PlanBDocCacheImpl's real behaviour), so the fake simply invokes it.
        when(securityContext.useAsReadResult(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
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
