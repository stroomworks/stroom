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

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.serde.val.ValSerdeUtil;
import stroom.query.language.functions.Val;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes a node/edge's named property map ({@code Map<String, Val>}) to/from the opaque {@code byte[]} blob
 * {@link GraphNodeDb}/{@link GraphAdjacencyDb} store as a value - reusing {@code ValSerdeUtil} (the same typed
 * value codec Plan B's own value serdes use) rather than inventing a new one, so a graph's property values carry
 * the same type fidelity (string/number/boolean/date/...) as any other Stroom-stored value.
 *
 * <p>Format: a 2-byte entry count, then per entry: a 2-byte name length + UTF-8 name bytes, a 4-byte
 * type-tagged-value length + the {@code ValSerdeUtil}-encoded value bytes. Package-private: an internal encoding
 * detail of the two DAOs above, not part of this package's public API.</p>
 */
final class GraphPropsCodec {

    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(new ByteBufferFactoryImpl());

    private GraphPropsCodec() {
        // Static utility - not instantiable.
    }

    /**
     * <b>Preconditions:</b> {@code properties} is not null (may be empty).
     * <b>Postconditions:</b> {@link #decode} applied to the result returns a map equal to {@code properties}.
     */
    static byte[] encode(final Map<String, Val> properties) {
        Objects.requireNonNull(properties, "properties");

        final byte[][] nameBytes = new byte[properties.size()][];
        final byte[][] valueBytes = new byte[properties.size()][];
        int total = 2;
        int i = 0;
        for (final Map.Entry<String, Val> entry : properties.entrySet()) {
            nameBytes[i] = entry.getKey().getBytes(StandardCharsets.UTF_8);
            valueBytes[i] = ValSerdeUtil.write(entry.getValue(), BYTE_BUFFERS, buffer -> {
                final byte[] copy = new byte[buffer.remaining()];
                buffer.get(copy);
                return copy;
            });
            total += 2 + nameBytes[i].length + 4 + valueBytes[i].length;
            i++;
        }

        final ByteBuffer out = ByteBuffer.allocate(total);
        out.putShort((short) properties.size());
        for (int j = 0; j < nameBytes.length; j++) {
            out.putShort((short) nameBytes[j].length);
            out.put(nameBytes[j]);
            out.putInt(valueBytes[j].length);
            out.put(valueBytes[j]);
        }
        return out.array();
    }

    /**
     * <b>Postconditions:</b> never null; empty if {@code blob} encoded an empty property map.
     */
    static Map<String, Val> decode(final byte[] blob) {
        Objects.requireNonNull(blob, "blob");

        final ByteBuffer in = ByteBuffer.wrap(blob);
        final int count = in.getShort() & 0xFFFF;
        final Map<String, Val> properties = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            final int nameLength = in.getShort() & 0xFFFF;
            final byte[] nameBytes = new byte[nameLength];
            in.get(nameBytes);
            final int valueLength = in.getInt();
            final byte[] valueBytes = new byte[valueLength];
            in.get(valueBytes);
            final String name = new String(nameBytes, StandardCharsets.UTF_8);
            properties.put(name, ValSerdeUtil.read(ByteBuffer.wrap(valueBytes)));
        }
        return properties;
    }
}
