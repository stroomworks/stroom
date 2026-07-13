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

package stroom.sqlstore.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.Store;
import stroom.docstore.api.StoreFactory;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.util.shared.EntityServiceException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the duplicate-name guard in {@link SqlTemporalStoreDocStoreImpl}.
 *
 * <p>{@code SqlTemporalStore} names must be unique because a store's name is
 * used as the map identifier for temporal-store queries. The store impl
 * enforces this on create, copy and rename by consulting {@link Store#list()};
 * all other operations delegate straight through. These tests mock the backing
 * {@link Store} so no docstore or database is required.</p>
 */
@ExtendWith(MockitoExtension.class)
class TestSqlTemporalStoreDocStoreImpl {

    @Mock
    private SqlTemporalStoreSerialiser mockSerialiser;
    @Mock
    private Store<SqlTemporalStoreDoc> mockStore;
    @Mock
    private StoreFactory mockStoreFactory;

    // -----------------------------------------------------------------------
    // createDocument
    // -----------------------------------------------------------------------

    @Test
    void testCreateDocument_uniqueName_delegatesToStore() {
        when(mockStore.list()).thenReturn(List.of(docRef("uuid-a", "existingMap")));
        final DocRef created = docRef("uuid-new", "newMap");
        when(mockStore.createDocument("newMap")).thenReturn(created);

        assertThat(getStore().createDocument("newMap")).isEqualTo(created);
    }

    @Test
    void testCreateDocument_duplicateName_throwsAndDoesNotCreate() {
        when(mockStore.list()).thenReturn(List.of(docRef("uuid-a", "dupMap")));

        assertThatThrownBy(() -> getStore().createDocument("dupMap"))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("already exists");

        verify(mockStore, never()).createDocument(any());
    }

    // -----------------------------------------------------------------------
    // copyDocument
    // -----------------------------------------------------------------------

    @Test
    void testCopyDocument_duplicateName_throws() {
        when(mockStore.list()).thenReturn(List.of(docRef("uuid-a", "taken")));

        assertThatThrownBy(() -> getStore().copyDocument(
                docRef("uuid-src", "source"), "taken", false, null))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void testCopyDocument_uniqueName_delegatesToStore() {
        when(mockStore.list()).thenReturn(List.of(docRef("uuid-a", "existingMap")));
        final DocRef copy = docRef("uuid-copy", "copyMap");
        when(mockStore.copyDocument("uuid-src", "copyMap")).thenReturn(copy);

        assertThat(getStore().copyDocument(docRef("uuid-src", "source"), "copyMap", false, null))
                .isEqualTo(copy);
    }

    // -----------------------------------------------------------------------
    // renameDocument
    // -----------------------------------------------------------------------

    @Test
    void testRenameDocument_toNameUsedByAnother_throws() {
        when(mockStore.list()).thenReturn(List.of(docRef("uuid-other", "taken")));

        assertThatThrownBy(() -> getStore().renameDocument(
                docRef("uuid-self", "oldName"), "taken"))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("already exists");
    }

    /**
     * Renaming a document to a name only it holds must be allowed — the guard
     * excludes the document being renamed from the clash check.
     */
    @Test
    void testRenameDocument_keepingOwnName_isAllowed() {
        final DocRef self = docRef("uuid-self", "myMap");
        when(mockStore.list()).thenReturn(List.of(self));
        when(mockStore.renameDocument(self, "myMap")).thenReturn(self);

        assertThat(getStore().renameDocument(self, "myMap")).isEqualTo(self);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private SqlTemporalStoreDocStoreImpl getStore() {
        when(mockStoreFactory.<SqlTemporalStoreDoc, SqlTemporalStoreDoc.Builder>createStore(
                any(), eq(SqlTemporalStoreDoc.TYPE), any(), any()))
                .thenReturn(mockStore);
        return new SqlTemporalStoreDocStoreImpl(mockStoreFactory, mockSerialiser);
    }

    private static DocRef docRef(final String uuid, final String name) {
        return DocRef.builder()
                .type(SqlTemporalStoreDoc.TYPE)
                .uuid(uuid)
                .name(name)
                .build();
    }
}
