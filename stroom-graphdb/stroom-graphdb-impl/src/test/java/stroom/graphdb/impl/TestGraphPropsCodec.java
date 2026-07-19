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

import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValBoolean;
import stroom.query.language.functions.ValDouble;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GraphPropsCodec} in isolation - every other test in this module only exercises it indirectly through
 * {@link GraphNodeDb}/{@link GraphAdjacencyDb}/{@link GraphInEdgeDb}'s own round-trip tests, which never probe
 * an empty map, a multi-typed property map, or the documented null-argument contracts directly.
 */
class TestGraphPropsCodec {

    @Test
    void encodeDecode_roundTripsAnEmptyMap() {
        final byte[] blob = GraphPropsCodec.encode(Map.of());

        final Map<String, Val> decoded = GraphPropsCodec.decode(blob);

        assertThat(decoded).isEmpty();
    }

    @Test
    void encodeDecode_roundTripsMultipleEntriesOfDifferentValTypes() {
        final Map<String, Val> properties = new LinkedHashMap<>();
        properties.put("name", ValString.create("d-42"));
        properties.put("balance", ValLong.create(200L));
        properties.put("ratio", ValDouble.create(3.5));
        properties.put("active", ValBoolean.create(true));

        final byte[] blob = GraphPropsCodec.encode(properties);
        final Map<String, Val> decoded = GraphPropsCodec.decode(blob);

        assertThat(decoded).hasSize(4);
        assertThat(decoded.get("name")).isEqualTo(ValString.create("d-42"));
        assertThat(decoded.get("balance")).isEqualTo(ValLong.create(200L));
        assertThat(decoded.get("ratio")).isEqualTo(ValDouble.create(3.5));
        assertThat(decoded.get("active")).isEqualTo(ValBoolean.create(true));
    }

    @Test
    void encodeDecode_preservesPropertyNamesExactlyIncludingNonAsciiCharacters() {
        final Map<String, Val> properties = Map.of("nom-de-plume-éè", ValString.create("café"));

        final byte[] blob = GraphPropsCodec.encode(properties);
        final Map<String, Val> decoded = GraphPropsCodec.decode(blob);

        assertThat(decoded).containsEntry("nom-de-plume-éè", ValString.create("café"));
    }

    @Test
    void encode_manyEntries_allRoundTrip() {
        final Map<String, Val> properties = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            properties.put("key-" + i, ValLong.create(i));
        }

        final byte[] blob = GraphPropsCodec.encode(properties);
        final Map<String, Val> decoded = GraphPropsCodec.decode(blob);

        assertThat(decoded).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(decoded.get("key-" + i)).isEqualTo(ValLong.create(i));
        }
    }

    @Test
    void encode_rejectsNullProperties() {
        assertThatThrownBy(() -> GraphPropsCodec.encode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void decode_rejectsNullBlob() {
        assertThatThrownBy(() -> GraphPropsCodec.decode(null)).isInstanceOf(NullPointerException.class);
    }
}
