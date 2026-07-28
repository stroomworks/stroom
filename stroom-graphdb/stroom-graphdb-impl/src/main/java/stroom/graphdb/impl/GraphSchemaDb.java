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

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.planb.impl.dao.HashClashCommitRunnable;
import stroom.planb.impl.dao.PlanBEnv;
import stroom.planb.impl.dao.SchemaInfo;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.HasPrimitiveValue;

import org.lmdbjava.Dbi;
import org.lmdbjava.DbiFlags;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The {@code graph-info} table: a single {@link SchemaInfo} stamp recording the on-disk format of a
 * {@link GraphStores} environment, plus the hash-clash counter for the property index's HASH_LOOKUP tier.
 *
 * <p>This is the mechanism that stops a store written by one version of the code being read by another. Every
 * graph key embeds fixed-width interned UIDs and a fixed-width time encoding, so a change to any of those widths
 * or layouts silently reinterprets existing bytes rather than failing - which is why the stamp exists and is
 * validated on every writable open and on every merge.</p>
 *
 * <p>Deliberately not a {@code stroom.planb.impl.dao.AbstractDb} subclass, which this otherwise mirrors:
 * {@code AbstractDb} opens its own {@code "db"} table and is typed on {@code PlanBDoc}, neither of which fits a
 * multi-table graph environment owned by a {@link GraphDbDoc}. The read-validate-write sequence, the one-byte
 * info-key scheme and the hash-clash wiring are copied from it so the two behave the same way.</p>
 *
 * <p><b>No backward compatibility is offered.</b> A version mismatch is not migrated - the store refuses to open
 * and must be rebuilt (see {@link GraphStores#rebuild}). The graph is a materialised projection of stored
 * streams, so rebuilding is the sanctioned recovery.</p>
 */
final class GraphSchemaDb {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(GraphSchemaDb.class);

    private static final String NAME = "graph-info";

    /**
     * Bump whenever any key layout, UID width, time encoding or value encoding below changes. A store stamped
     * with a different version refuses to open.
     */
    static final int CURRENT_SCHEMA_VERSION = 1;

    private final Dbi<ByteBuffer> dbi;
    private final ByteBuffers byteBuffers;
    private final GraphDbDoc doc;
    private final SchemaInfo schemaInfo;

    /**
     * Opens the {@code graph-info} table, validating any stamp already present against {@code expected}.
     *
     * <p><b>Preconditions:</b> no parameter is null.
     * <b>Postconditions:</b> when {@code env} is writable, {@code expected} has been written and the hash-clash
     * counter is wired to persist on commit. When a stamp was already present it matched {@code expected},
     * otherwise this constructor threw.
     * <b>Null status:</b> no parameter is nullable.
     *
     * @param env                     the environment to open the table in.
     * @param byteBuffers             buffer pool used to encode values.
     * @param doc                     the owning document, used only for error messages.
     * @param expected                the stamp this build of the code writes and requires.
     * @param hashClashCommitRunnable the counter to seed and persist.
     * @throws RuntimeException if a stamp is present and does not match {@code expected}.
     */
    GraphSchemaDb(final PlanBEnv env,
                  final ByteBuffers byteBuffers,
                  final GraphDbDoc doc,
                  final SchemaInfo expected,
                  final HashClashCommitRunnable hashClashCommitRunnable) {
        Objects.requireNonNull(env, "env must not be null");
        Objects.requireNonNull(byteBuffers, "byteBuffers must not be null");
        Objects.requireNonNull(doc, "doc must not be null");
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(hashClashCommitRunnable, "hashClashCommitRunnable must not be null");

        this.byteBuffers = byteBuffers;
        this.doc = doc;
        this.dbi = env.openDbi(NAME, DbiFlags.MDB_CREATE);

        this.schemaInfo = env.read(txn -> read(txn)
                .map(actual -> {
                    validate(expected, actual);
                    return actual;
                })
                .orElse(expected));

        if (!env.isReadOnly()) {
            env.write(writer -> {
                write(writer.getWriteTxn(), expected);
                hashClashCommitRunnable.setHashClashes(readHashClashes(writer.getWriteTxn()));
                writer.commit();
            });
            hashClashCommitRunnable.setRunnable(txn ->
                    writeHashClashes(txn, hashClashCommitRunnable.getHashClashes()));
        }
    }

    /**
     * @return the stamp this store is operating under; never null.
     */
    SchemaInfo getSchemaInfo() {
        return schemaInfo;
    }

    /**
     * Validates a fragment's stamp against this store's before merging it.
     *
     * <p><b>Preconditions:</b> {@code source} is not null.
     * <b>Postconditions:</b> returns normally only when the two stamps match.
     * <b>Null status:</b> {@code source} is not nullable.
     *
     * @param source the incoming fragment's stamp.
     * @throws RuntimeException if the stamps differ, naming the field that differs.
     */
    void validateForMerge(final SchemaInfo source) {
        Objects.requireNonNull(source, "source must not be null");
        validate(schemaInfo, source);
    }

    private void validate(final SchemaInfo expected, final SchemaInfo actual) {
        requireEqual("Schema version", expected.getSchemaVersion(), actual.getSchemaVersion());
        requireEqual("Key schema", expected.getKeySchema(), actual.getKeySchema());
        requireEqual("Value schema", expected.getValueSchema(), actual.getValueSchema());
    }

    private void requireEqual(final String what, final Object expected, final Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new RuntimeException(LogUtil.message(
                    "{} mismatch for graph '{}': expected={}, actual={}. The store was written by a different " +
                    "version of the code and cannot be read; delete and rebuild it.",
                    what,
                    doc.getName(),
                    expected,
                    actual));
        }
    }

    private Optional<SchemaInfo> read(final Txn<ByteBuffer> txn) {
        final OptionalInt version = readInt(txn, InfoKey.SCHEMA_VERSION);
        if (version.isEmpty()) {
            return Optional.empty();
        }
        final SchemaInfo info = new SchemaInfo(
                version.getAsInt(),
                readString(txn, InfoKey.KEY_SCHEMA).orElse(null),
                readString(txn, InfoKey.VALUE_SCHEMA).orElse(null));
        LOGGER.debug(() -> LogUtil.message("graph={}, schemaInfo={}", doc.getName(), info));
        return Optional.of(info);
    }

    private void write(final Txn<ByteBuffer> txn, final SchemaInfo info) {
        writeInt(txn, InfoKey.SCHEMA_VERSION, info.getSchemaVersion());
        writeString(txn, InfoKey.KEY_SCHEMA, info.getKeySchema());
        writeString(txn, InfoKey.VALUE_SCHEMA, info.getValueSchema());
    }

    private OptionalInt readInt(final Txn<ByteBuffer> txn, final InfoKey infoKey) {
        final ByteBuffer valueBuffer = dbi.get(txn, infoKey.getByteBuffer());
        if (valueBuffer == null) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(valueBuffer.getInt());
    }

    private Optional<String> readString(final Txn<ByteBuffer> txn, final InfoKey infoKey) {
        final ByteBuffer valueBuffer = dbi.get(txn, infoKey.getByteBuffer());
        if (valueBuffer == null) {
            return Optional.empty();
        }
        return Optional.of(ByteBufferUtils.toString(valueBuffer));
    }

    private void writeInt(final Txn<ByteBuffer> txn, final InfoKey infoKey, final int value) {
        byteBuffers.useInt(value, byteBuffer -> {
            dbi.put(txn, infoKey.getByteBuffer(), byteBuffer);
        });
    }

    private void writeString(final Txn<ByteBuffer> txn, final InfoKey infoKey, final String value) {
        if (value != null) {
            final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            byteBuffers.useBytes(bytes, byteBuffer -> {
                dbi.put(txn, infoKey.getByteBuffer(), byteBuffer);
            });
        }
    }

    private int readHashClashes(final Txn<ByteBuffer> txn) {
        final ByteBuffer valueBuffer = dbi.get(txn, InfoKey.HASH_CLASHES.getByteBuffer());
        if (valueBuffer == null) {
            return -1;
        }
        return valueBuffer.getInt();
    }

    private void writeHashClashes(final Txn<ByteBuffer> txn, final int hashClashes) {
        writeInt(txn, InfoKey.HASH_CLASHES, hashClashes);
    }

    /**
     * One-byte keys within {@code graph-info}. Values mirror {@code AbstractDb}'s own scheme so the two tables
     * are read the same way; they are an on-disk format and must not be renumbered.
     */
    private enum InfoKey implements HasPrimitiveValue {
        SCHEMA_VERSION(0),
        HASH_CLASHES(1),
        KEY_SCHEMA(2),
        VALUE_SCHEMA(3);

        private final byte primitiveValue;
        private final ByteBuffer byteBuffer;

        InfoKey(final int primitiveValue) {
            this.primitiveValue = (byte) primitiveValue;
            this.byteBuffer = ByteBuffer.allocateDirect(1);
            byteBuffer.put((byte) primitiveValue);
            byteBuffer.flip();
        }

        @Override
        public byte getPrimitiveValue() {
            return primitiveValue;
        }

        ByteBuffer getByteBuffer() {
            return byteBuffer.asReadOnlyBuffer();
        }
    }
}
