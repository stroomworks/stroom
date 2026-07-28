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
import stroom.planb.impl.dao.HashClashCommitRunnable;
import stroom.planb.impl.dao.PlanBEnv;

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;

import java.nio.ByteBuffer;
import java.nio.file.Path;

/**
 * Test-only helper that doctors a graph store's on-disk format stamp, so tests can prove the store refuses to
 * open when the recorded format is not the one this build expects.
 *
 * <p>Writes the raw {@code graph-info} bytes directly rather than going through {@link GraphSchemaDb}, because
 * the production path deliberately offers no way to write a stamp other than the current one.</p>
 */
final class GraphSchemaDbTestSupport {

    /** Mirrors {@code GraphSchemaDb.InfoKey.SCHEMA_VERSION}, which is private by design. */
    private static final byte SCHEMA_VERSION_KEY = 0;

    private static final String INFO_DBI_NAME = "graph-info";

    private GraphSchemaDbTestSupport() {
        // Static utility.
    }

    /**
     * Overwrites the recorded schema version of the store under {@code directory}.
     *
     * <p><b>Preconditions:</b> {@code directory} holds a provisioned graph store and is not open elsewhere.
     * <b>Postconditions:</b> the store's recorded schema version is {@code schemaVersion}.
     * <b>Null status:</b> no parameter is nullable.
     *
     * @param directory     the store directory.
     * @param doc           the owning document.
     * @param schemaVersion the version to record.
     */
    static void overwriteSchemaVersion(final Path directory,
                                       final GraphDbDoc doc,
                                       final int schemaVersion) {
        final PlanBEnv env = new PlanBEnv(directory, null, 32, false, new HashClashCommitRunnable());
        try {
            final Dbi<ByteBuffer> dbi = env.openDbi(INFO_DBI_NAME, DbiFlags.MDB_CREATE);
            env.write(writer -> {
                final ByteBuffer key = ByteBuffer.allocateDirect(1);
                key.put(SCHEMA_VERSION_KEY);
                key.flip();
                final ByteBuffer value = ByteBuffer.allocateDirect(Integer.BYTES);
                value.putInt(schemaVersion);
                value.flip();
                dbi.put(writer.getWriteTxn(), key, value);
                writer.commit();
            });
        } finally {
            env.close();
        }
    }
}
