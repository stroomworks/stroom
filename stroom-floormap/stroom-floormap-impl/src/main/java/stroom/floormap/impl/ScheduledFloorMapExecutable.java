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

import stroom.analytics.impl.ScheduledExecutable;
import stroom.analytics.impl.ScheduledExecutorService.ExecutionResult;
import stroom.analytics.shared.ExecutionSchedule;
import stroom.analytics.shared.ExecutionTracker;
import stroom.docref.DocRef;
import stroom.floormap.shared.FloorMapDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.scheduler.Trigger;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class ScheduledFloorMapExecutable implements ScheduledExecutable<FloorMapDoc> {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ScheduledFloorMapExecutable.class);

    private final FloorMapStore floorMapStore;

    @Inject
    ScheduledFloorMapExecutable(final FloorMapStore floorMapStore) {
        this.floorMapStore = floorMapStore;
    }

    @Override
    public ExecutionResult run(final FloorMapDoc doc,
                               final Trigger trigger,
                               final Instant executionTime,
                               final Instant effectiveExecutionTime,
                               final ExecutionSchedule executionSchedule,
                               final ExecutionTracker currentTracker,
                               final ExecutionResult executionResult) {
        // No-op: Background data generation into feeds is disabled under the new column-mapping architecture.
        return executionResult;
    }

    @Override
    public DocRef getDocRef(final FloorMapDoc doc) {
        return doc.asDocRef();
    }

    @Override
    public FloorMapDoc load(final DocRef docRef) {
        return floorMapStore.readDocument(docRef);
    }

    @Override
    public FloorMapDoc reload(final FloorMapDoc doc) {
        return floorMapStore.readDocument(doc.asDocRef());
    }

    @Override
    public List<FloorMapDoc> getDocs() {
        final List<FloorMapDoc> currentFloorMaps = new ArrayList<>();
        final List<DocRef> docRefs = floorMapStore.list();
        for (final DocRef docRef : docRefs) {
            try {
                final FloorMapDoc floorMapDoc = floorMapStore.readDocument(docRef);
                if (floorMapDoc != null) {
                    currentFloorMaps.add(floorMapDoc);
                }
            } catch (final RuntimeException e) {
                LOGGER.error(e::getMessage, e);
            }
        }
        return currentFloorMaps;
    }

    @Override
    public String getProcessType() {
        return "floor map";
    }

    @Override
    public String getIdentity(final FloorMapDoc doc) {
        return NullSafe.get(doc, d -> d.getName() + " (" + d.getUuid() + ")");
    }
}
