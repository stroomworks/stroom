/*
 * Copyright 2026 Crown Copyright
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
import stroom.docstore.api.Store;
import stroom.docstore.api.StoreFactory;
import stroom.pathways.shared.TracesDoc;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.planb.shared.TraceSettings;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.PermissionException;

import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A trace store is written and deleted through {@link TracesDocStoreImpl}, which wraps {@link Store}
 * directly rather than extending {@code AbstractDocumentStore}, so it carries its own permission
 * checks for those two. Without them the persistence layer would do as it was asked and any
 * authenticated user reaching the REST resource could write or delete a trace store.
 *
 * <p>Reading is not covered, here or in the store: {@code readDocument} goes straight through, so any
 * authenticated user can still fetch a trace store document.
 */
class TestTracesDocStorePermissions {

    private static final String UUID = "6a0f2c11-9a34-4a1e-8f77-2b05c3d4e5f6";

    @TempDir
    Path tempDir;

    @Mock
    private StoreFactory storeFactory;
    @Mock
    private Store<TracesDoc> store;
    @Mock
    private TracesDocSerialiser serialiser;
    @Mock
    private ClusterLockService clusterLockService;
    @Mock
    private SecurityContext securityContext;

    private TracesDocStoreImpl storeImpl;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(store).when(storeFactory).createStore(any(), any(), any(), any(), any());
        final Provider<ClusterLockService> lockServiceProvider = () -> clusterLockService;
        storeImpl = new TracesDocStoreImpl(storeFactory, serialiser, lockServiceProvider, securityContext);
    }

    @Test
    void writeIsRefusedWithoutEdit() {
        when(securityContext.hasDocumentPermission(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> storeImpl.writeDocument(doc()))
                .isInstanceOf(PermissionException.class);
        verify(store, never()).writeDocument(any());
    }

    @Test
    void writeIsRefusedWithViewOnly() {
        when(securityContext.hasDocumentPermission(any(), any())).thenAnswer(invocation ->
                invocation.getArgument(1) == DocumentPermission.VIEW);

        assertThatThrownBy(() -> storeImpl.writeDocument(doc()))
                .isInstanceOf(PermissionException.class);
        verify(store, never()).writeDocument(any());
    }

    @Test
    void writeIsAllowedWithEdit() {
        when(securityContext.hasDocumentPermission(any(), any())).thenReturn(true);
        final TracesDoc doc = doc();
        when(store.readDocument(any())).thenReturn(null);
        when(store.writeDocument(any())).thenReturn(doc);

        storeImpl.writeDocument(doc);

        verify(store).writeDocument(any());
    }

    @Test
    void deleteIsRefusedWithoutDelete() {
        when(securityContext.hasDocumentPermission(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> storeImpl.deleteDocument(docRef()))
                .isInstanceOf(PermissionException.class);
        verify(store, never()).deleteDocument(any());
    }

    private static DocRef docRef() {
        return DocRef.builder()
                .type(TracesDoc.TYPE)
                .uuid(UUID)
                .name("test_name")
                .build();
    }

    private TracesDoc doc() {
        return TracesDoc.tracesBuilder()
                .uuid(UUID)
                .name("test_name")
                .settings(new TraceSettings.Builder()
                        .sharedFileStore(new SharedFileStoreSettings(
                                1, tempDir.resolve("shared").toString()))
                        .build())
                .build();
    }
}
