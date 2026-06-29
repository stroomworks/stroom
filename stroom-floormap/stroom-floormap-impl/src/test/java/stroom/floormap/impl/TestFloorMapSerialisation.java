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

package stroom.floormap.impl;

import stroom.docref.DocRef;
import stroom.floormap.shared.FloorMapDoc;
import stroom.query.api.TimeRange;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapSerialisation {

    @Test
    void testSerializationRoundTrip() {
        final DocRef storeRef = DocRef.builder()
                .type("SqlTemporalStore")
                .uuid("store-uuid-123")
                .name("StoreName")
                .build();

        final TimeRange timeRange = new TimeRange("LAST_24_HOURS", null, null);

        final FloorMapDoc original = FloorMapDoc.builder()
                .uuid("map-uuid-456")
                .name("MyFloorMap")
                .description("Floor map description")
                .temporalStoreRef(storeRef)
                .eventsQuery("from StoreName select events")
                .eventsQueryTimeRange(timeRange)
                .factsQuery("from StoreName select facts")
                .factsQueryTimeRange(timeRange)
                .build();

        // Serialize
        final String json = JsonUtil.writeValueAsString(original);
        assertThat(json).isNotNull();

        // Deserialize
        final FloorMapDoc deserialized = JsonUtil.readValue(json, FloorMapDoc.class);
        assertThat(deserialized).isNotNull();

        // Assert new fields
        assertThat(deserialized.getTemporalStoreRef()).isEqualTo(storeRef);
        assertThat(deserialized.getEventsQuery()).isEqualTo("from StoreName select events");
        assertThat(deserialized.getEventsQueryTimeRange()).isEqualTo(timeRange);
        assertThat(deserialized.getFactsQuery()).isEqualTo("from StoreName select facts");
        assertThat(deserialized.getFactsQueryTimeRange()).isEqualTo(timeRange);
    }

    @Test
    void testLegacyJsonIgnored() {
        // Old FloorMapDoc JSON that contains the removed 'query' / 'queryTimeRange' fields.
        // Jackson should silently ignore unknown fields; deserialisation must not throw.
        final String oldJson = "{"
                + "\"uuid\":\"map-uuid-456\","
                + "\"name\":\"MyFloorMap\","
                + "\"description\":\"Floor map description\","
                + "\"query\":\"from StoreName select old_query\","
                + "\"queryTimeRange\":{\"name\":\"LAST_24_HOURS\"}"
                + "}";

        final FloorMapDoc deserialized = JsonUtil.readValue(oldJson, FloorMapDoc.class);
        assertThat(deserialized).isNotNull();

        // Legacy 'query' field is no longer migrated eventsQuery should be null.
        assertThat(deserialized.getEventsQuery()).isNull();
        assertThat(deserialized.getTemporalStoreRef()).isNull();
        assertThat(deserialized.getFactsQuery()).isNull();
    }
}
