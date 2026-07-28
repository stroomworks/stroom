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
import stroom.planb.impl.dao.SchemaInfo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the {@code graph-info} store-version stamp: that it is written on provision, survives a reopen, and
 * rejects a store whose recorded format differs from the one this build expects.
 *
 * <p>The rejection case is the point of the whole mechanism. Every graph key embeds fixed-width UIDs and a
 * fixed-width time encoding, so without this stamp a store written by different code is silently
 * reinterpreted rather than refused.</p>
 */
class TestGraphSchemaDb {

    private static final GraphDbDoc DOC = GraphDbDoc.builder().uuid("graph-uuid").name("TestGraph").build();

    @Test
    void provision_writesAStamp_andReopeningTheSameStoreAccceptsIt(@TempDir final Path root) {
        final Path dir = root.resolve("graph");

        final SchemaInfo stamped;
        try (GraphStores stores = GraphStores.provision(dir, DOC)) {
            stamped = stores.getSchemaInfo();
            assertThat(stamped.getSchemaVersion()).isEqualTo(GraphSchemaDb.CURRENT_SCHEMA_VERSION);
            assertThat(stamped.getKeySchema()).contains("nodeUidWidth");
            assertThat(stamped.getValueSchema()).contains("propsCodec");
        }

        // Reopening a store this build wrote must succeed and report the same stamp.
        try (GraphStores reopened = GraphStores.open(dir, DOC, false)) {
            assertThat(reopened.getSchemaInfo()).isEqualTo(stamped);
        }
    }

    @Test
    void reopeningAStoreStampedWithADifferentVersion_refusesToOpen(@TempDir final Path root) {
        final Path dir = root.resolve("graph");
        try (GraphStores stores = GraphStores.provision(dir, DOC)) {
            assertThat(stores.getSchemaInfo()).isNotNull();
        }

        // Rewrite the stamp to a version this build does not recognise, simulating a store written by other code.
        GraphSchemaDbTestSupport.overwriteSchemaVersion(dir, DOC, GraphSchemaDb.CURRENT_SCHEMA_VERSION + 1);

        assertThatThrownBy(() -> GraphStores.open(dir, DOC, false).close())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Schema version mismatch")
                .hasMessageContaining("TestGraph")
                .hasMessageContaining("rebuild");
    }

    @Test
    void validateForMerge_rejectsAFragmentStampedDifferently(@TempDir final Path root) {
        try (GraphStores stores = GraphStores.provision(root.resolve("graph"), DOC)) {
            final SchemaInfo mine = stores.getSchemaInfo();
            final SchemaInfo other = new SchemaInfo(
                    mine.getSchemaVersion(),
                    "{\"nodeUidWidth\":8}",
                    mine.getValueSchema());

            assertThatThrownBy(() -> stores.validateForMerge(other))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Key schema mismatch");

            // A fragment written by the same build merges without complaint.
            stores.validateForMerge(mine);
        }
    }
}
