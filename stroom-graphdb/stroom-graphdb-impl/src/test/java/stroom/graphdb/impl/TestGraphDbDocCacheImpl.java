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

import stroom.cache.impl.CacheManagerImpl;
import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.shared.PermissionException;
import stroom.util.shared.UserRef;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GraphDbDocCacheImpl} - the real cache-loading/permission-check/eviction logic every other test in this
 * module bypasses by faking the plain {@link GraphDbDocCache} interface (see e.g. {@code TestGraphSearchProvider}).
 * Uses a real {@link CacheManagerImpl} (its no-arg, test-only constructor) so the caching itself - not just the
 * loader function - is genuinely exercised, mirroring {@code stroom.planb.impl.PlanBDocCacheImpl}'s own contract.
 */
class TestGraphDbDocCacheImpl {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();
    private static final DocRef DOC_REF = DOC.asDocRef();

    @Test
    void get_loadsViaDocFinderAndStore_andCachesOnSecondCall() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        when(graphDbDocStore.readDocument(DOC_REF)).thenReturn(DOC);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, mock(GraphStoreManager.class));

        assertThat(cache.get("TestGraph")).isEqualTo(DOC);
        assertThat(cache.get("TestGraph")).isEqualTo(DOC);

        // Loaded once, not once per get() call - the second get() was served from the cache.
        verify(docFinder, times(1)).findByName(GraphDbDoc.TYPE, "TestGraph");
        verify(graphDbDocStore, times(1)).readDocument(DOC_REF);
    }

    @Test
    void get_throwsWhenCallerLacksUsePermission() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        when(graphDbDocStore.readDocument(DOC_REF)).thenReturn(DOC);
        final SecurityContext securityContext = processingUserPassthrough(never_());

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, mock(GraphStoreManager.class));

        assertThatThrownBy(() -> cache.get("TestGraph")).isInstanceOf(PermissionException.class);
    }

    @Test
    void get_throwsWhenNoDocFoundForName() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "Missing")).thenReturn(List.of());
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, mock(GraphStoreManager.class));

        assertThatThrownBy(() -> cache.get("Missing")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void get_throwsWhenMultipleDocsFoundForName() {
        final DocRef otherDocRef = new DocRef(GraphDbDoc.TYPE, "other-uuid", "TestGraph");
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF, otherDocRef));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, mock(GraphStoreManager.class));

        assertThatThrownBy(() -> cache.get("TestGraph")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void remove_evictsSoTheNextGetReloads() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        when(graphDbDocStore.readDocument(DOC_REF)).thenReturn(DOC);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, mock(GraphStoreManager.class));

        cache.get("TestGraph");
        cache.remove("TestGraph");
        cache.get("TestGraph");

        verify(docFinder, times(2)).findByName(GraphDbDoc.TYPE, "TestGraph");
    }

    @Test
    void onChange_forUpdateDeleteOrClearCache_clearsTheWholeCacheSoTheNextGetReloads() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        when(graphDbDocStore.readDocument(DOC_REF)).thenReturn(DOC);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());
        final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, graphStoreManager);

        cache.get("TestGraph");
        cache.onChange(new EntityEvent(DOC_REF, EntityAction.UPDATE));
        cache.get("TestGraph");

        verify(docFinder, times(2)).findByName(GraphDbDoc.TYPE, "TestGraph");
        // Task P5.3: UPDATE clears the cache but must not tear down the physical store - only DELETE does that.
        verify(graphStoreManager, never()).delete(any());
    }

    @Test
    void onChange_forDelete_alsoDeletesThePhysicalStores() {
        // Task P5.3: before this, deleting a GraphDbDoc only evicted the doc cache - the on-disk LMDB store was
        // never removed, orphaning it forever.
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        when(graphDbDocStore.readDocument(DOC_REF)).thenReturn(DOC);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());
        final GraphStoreManager graphStoreManager = mock(GraphStoreManager.class);

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, graphStoreManager);

        cache.get("TestGraph");
        cache.onChange(new EntityEvent(DOC_REF, EntityAction.DELETE));
        cache.get("TestGraph");

        verify(docFinder, times(2)).findByName(GraphDbDoc.TYPE, "TestGraph");
        verify(graphStoreManager, times(1)).delete(DOC_REF.getUuid());
    }

    @Test
    void onChange_forCreate_doesNotClearTheCache() {
        final DocFinder docFinder = mock(DocFinder.class);
        when(docFinder.findByName(GraphDbDoc.TYPE, "TestGraph")).thenReturn(List.of(DOC_REF));
        final GraphDbDocStore graphDbDocStore = mock(GraphDbDocStore.class);
        when(graphDbDocStore.readDocument(DOC_REF)).thenReturn(DOC);
        final SecurityContext securityContext = processingUserPassthrough(alwaysAllowed());

        final GraphDbDocCacheImpl cache = new GraphDbDocCacheImpl(
                new CacheManagerImpl(), graphDbDocStore, securityContext, docFinder, mock(GraphStoreManager.class));

        cache.get("TestGraph");
        cache.onChange(new EntityEvent(DOC_REF, EntityAction.CREATE));
        cache.get("TestGraph");

        verify(docFinder, times(1)).findByName(GraphDbDoc.TYPE, "TestGraph");
    }

    private static SecurityContextAllowance alwaysAllowed() {
        return securityContext -> when(securityContext.hasDocumentPermission(any(), eq(DocumentPermission.USE)))
                .thenReturn(true);
    }

    private static SecurityContextAllowance never_() {
        return securityContext -> {
            when(securityContext.hasDocumentPermission(any(), eq(DocumentPermission.USE))).thenReturn(false);
            when(securityContext.getUserRef()).thenReturn(UserRef.builder().uuid("u1").subjectId("user").build());
        };
    }

    private static SecurityContext processingUserPassthrough(final SecurityContextAllowance allowance) {
        final SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.asProcessingUserResult(any())).thenAnswer(inv ->
                ((Supplier<?>) inv.getArgument(0)).get());
        allowance.apply(securityContext);
        return securityContext;
    }

    @FunctionalInterface
    private interface SecurityContextAllowance {

        void apply(SecurityContext securityContext);
    }
}
