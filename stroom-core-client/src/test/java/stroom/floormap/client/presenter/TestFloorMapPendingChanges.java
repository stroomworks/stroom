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

package stroom.floormap.client.presenter;

import org.junit.jupiter.api.Test;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapPendingChanges {

    @Test
    void testNaturalKeyMatches_effectiveTimeOutsideCacheRange() {
        final FloorMapPendingChanges pendingChanges = new FloorMapPendingChanges();

        final Long effectiveTime1 = 1000000000000L;
        final Long effectiveTime2 = Long.parseLong("1000000000000");

        // Verify that these are indeed distinct Long object references
        assertThat(effectiveTime1).isNotSameAs(effectiveTime2);

        final TemporalEntry originalEntry = new TemporalEntry("map1", "key1", effectiveTime1, "old-value");
        final TemporalEntry updatedEntry = new TemporalEntry("map1", "key1", effectiveTime2, "new-value");

        pendingChanges.recordUpdate(updatedEntry);

        final List<TemporalEntry> serverEntries = new ArrayList<>();
        serverEntries.add(originalEntry);

        final List<TemporalEntry> result = pendingChanges.applyTo(serverEntries);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getValue()).isEqualTo("new-value");
    }

    @Test
    void testNaturalKeyMatchesId_effectiveTimeOutsideCacheRange() {
        final FloorMapPendingChanges pendingChanges = new FloorMapPendingChanges();

        final Long effectiveTime1 = 1000000000000L;
        final Long effectiveTime2 = Long.parseLong("1000000000000");

        // Verify that these are indeed distinct Long object references
        assertThat(effectiveTime1).isNotSameAs(effectiveTime2);

        final TemporalEntry originalEntry = new TemporalEntry("map1", "key1", effectiveTime1, "old-value");
        final TemporalEntryId deletionId = new TemporalEntryId("map1", "key1", effectiveTime2);

        pendingChanges.recordDeletion(deletionId);

        final List<TemporalEntry> serverEntries = new ArrayList<>();
        serverEntries.add(originalEntry);

        final List<TemporalEntry> result = pendingChanges.applyTo(serverEntries);

        assertThat(result).isEmpty();
    }
}
