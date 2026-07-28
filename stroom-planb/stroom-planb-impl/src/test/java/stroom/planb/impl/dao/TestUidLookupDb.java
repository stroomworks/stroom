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

package stroom.planb.impl.dao;

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.lmdb.serde.UnsignedBytes;
import stroom.lmdb.serde.UnsignedBytesInstances;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F15: {@link UidLookupDb#put} must roll back its in-memory {@code maxId} counter when the fixed-width encode
 * ({@code forValue}/{@code put}) of a newly-allocated id throws - see
 *  finding F15. Before the fix, {@code maxId} was advanced
 * ({@code ++maxId}) before the encode ran and was only ever written back on success, so a failed encode left the
 * in-memory counter permanently one past where it should be, skipping an id for every subsequent {@code put} in
 * that namespace until process restart.
 */
class TestUidLookupDb {

    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(new ByteBufferFactoryImpl());
    private static final Long DEFAULT_MAX_STORE_SIZE = 10_737_418_240L;

    @Test
    void put_rollsBackMaxId_whenTheNewUidsEncodeThrows(@TempDir final Path tempDir) {
        try (final PlanBEnv env = new PlanBEnv(
                tempDir, DEFAULT_MAX_STORE_SIZE, 3, false, new HashClashCommitRunnable())) {
            final UidLookupDb db = new UidLookupDb(
                    env, BYTE_BUFFERS, "uids", new OneShotFailingUnsignedBytesFactory());

            final byte[] overflowingKey = "overflow".getBytes(StandardCharsets.UTF_8);
            final byte[] nextKey = "next".getBytes(StandardCharsets.UTF_8);

            // The first put's encode is rigged to throw (simulating a fixed-width overflow) - it must propagate,
            // not be swallowed.
            env.write((Consumer<LmdbWriter>) writer ->
                    assertThatThrownBy(() -> BYTE_BUFFERS.useBytes(
                            overflowingKey,
                            (Function<ByteBuffer, Object>) keyByteBuffer ->
                                    db.put(writer.getWriteTxn(), keyByteBuffer, idByteBuffer -> null)))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("simulated"));

            // The failed key must not have been half-inserted into either lookup direction.
            env.read(readTxn -> {
                BYTE_BUFFERS.useBytes(overflowingKey, (Function<ByteBuffer, Object>) keyByteBuffer ->
                        db.get(readTxn, keyByteBuffer, optionalUid -> {
                            assertThat(optionalUid).isEmpty();
                            return null;
                        }));
                return null;
            });

            // maxId must have been rolled back: this next (successful) put gets uid 1 - the same slot the failed
            // attempt would have taken - not uid 2, which is what an un-rolled-back counter would hand out.
            env.write((Consumer<LmdbWriter>) writer ->
                    BYTE_BUFFERS.useBytes(nextKey, (Function<ByteBuffer, Object>) keyByteBuffer ->
                            db.put(writer.getWriteTxn(), keyByteBuffer, idByteBuffer -> {
                                final UnsignedBytes unsignedBytes =
                                        UnsignedBytesInstances.ofLength(idByteBuffer.remaining());
                                assertThat(unsignedBytes.get(idByteBuffer.duplicate())).isEqualTo(1L);
                                return null;
                            })));
        }
    }

    /**
     * A test-only {@link UidLookupDb.UnsignedBytesFactory} that throws exactly once from {@code forValue}
     * (simulating an encode failure, e.g. a fixed-width namespace whose new value doesn't fit), then delegates
     * normally - so a later {@code put} attempt can be observed succeeding at the rolled-back id.
     */
    private static final class OneShotFailingUnsignedBytesFactory implements UidLookupDb.UnsignedBytesFactory {

        private final UidLookupDb.UnsignedBytesFactory delegate = new UidLookupDb.VariableUnsignedBytesFactory();
        private final AtomicBoolean failNext = new AtomicBoolean(true);

        @Override
        public UnsignedBytes ofLength(final int length) {
            return delegate.ofLength(length);
        }

        @Override
        public UnsignedBytes forValue(final long value) {
            if (failNext.compareAndSet(true, false)) {
                throw new IllegalArgumentException("simulated fixed-width encode overflow for uid " + value);
            }
            return delegate.forValue(value);
        }
    }
}
