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

package stroom.sqlstore.impl.db;

import stroom.db.util.ExpressionMapper;
import stroom.db.util.ExpressionMapperFactory;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class TestUpdatableTemporalStoreDaoImpl {

    @Mock
    private SqlStoreDbConnProvider sqlStoreDbConnProvider;

    @Mock
    private ExpressionMapperFactory expressionMapperFactory;

    @Mock
    private ExpressionMapper expressionMapper;

    private UpdatableTemporalStoreDaoImpl dao;

    @BeforeEach
    void setUp() {
        Mockito.when(expressionMapperFactory.create()).thenReturn(expressionMapper);
        dao = new UpdatableTemporalStoreDaoImpl(sqlStoreDbConnProvider, expressionMapperFactory);
    }

    @Test
    void testCreateWithNullEntry() {
        assertThatThrownBy(() -> dao.create(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Entry cannot be null.");
    }

    @Test
    void testCreateWithNullMap() {
        final TemporalEntry entry = new TemporalEntry(null, "key1", 1000L, "val");
        assertThatThrownBy(() -> dao.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map name must be defined and not empty.");
    }

    @Test
    void testCreateWithEmptyMap() {
        final TemporalEntry entry = new TemporalEntry("  ", "key1", 1000L, "val");
        assertThatThrownBy(() -> dao.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map name must be defined and not empty.");
    }

    @Test
    void testCreateWithNullKey() {
        final TemporalEntry entry = new TemporalEntry("map1", null, 1000L, "val");
        assertThatThrownBy(() -> dao.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Key must be defined and not empty.");
    }

    @Test
    void testCreateWithEmptyKey() {
        final TemporalEntry entry = new TemporalEntry("map1", "", 1000L, "val");
        assertThatThrownBy(() -> dao.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Key must be defined and not empty.");
    }

    @Test
    void testCreateWithNullEffectiveTime() {
        final TemporalEntry entry = new TemporalEntry("map1", "key1", null, "val");
        assertThatThrownBy(() -> dao.create(entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Effective time must be defined.");
    }

    @Test
    void testFetchWithNullId() {
        assertThatThrownBy(() -> dao.fetch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Id cannot be null.");
    }

    @Test
    void testFetchWithNullMap() {
        final TemporalEntryId id = new TemporalEntryId(null, "key1", 1000L);
        assertThatThrownBy(() -> dao.fetch(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map name must be defined and not empty.");
    }

    @Test
    void testFetchWithNullKey() {
        final TemporalEntryId id = new TemporalEntryId("map1", null, 1000L);
        assertThatThrownBy(() -> dao.fetch(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Key must be defined and not empty.");
    }

    @Test
    void testFetchWithNullEffectiveTime() {
        final TemporalEntryId id = new TemporalEntryId("map1", "key1", null);
        assertThatThrownBy(() -> dao.fetch(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Effective time must be defined.");
    }

    @Test
    void testDeleteWithNullId() {
        assertThatThrownBy(() -> dao.delete(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Id cannot be null.");
    }

    @Test
    void testDeleteWithNullMap() {
        final TemporalEntryId id = new TemporalEntryId(null, "key1", 1000L);
        assertThatThrownBy(() -> dao.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Map name must be defined and not empty.");
    }

    @Test
    void testDeleteWithNullKey() {
        final TemporalEntryId id = new TemporalEntryId("map1", null, 1000L);
        assertThatThrownBy(() -> dao.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Key must be defined and not empty.");
    }

    @Test
    void testDeleteWithNullEffectiveTime() {
        final TemporalEntryId id = new TemporalEntryId("map1", "key1", null);
        assertThatThrownBy(() -> dao.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Effective time must be defined.");
    }
}
