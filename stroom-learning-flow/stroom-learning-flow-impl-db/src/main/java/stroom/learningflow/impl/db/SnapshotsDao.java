/*
 * Copyright 2026 Crown Copyright
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

package stroom.learningflow.impl.db;

import stroom.db.util.JooqUtil;
import stroom.learningflow.impl.db.jooq.tables.LearningFlowSnapshot;
import stroom.learningflow.stage.Snapshots;
import stroom.util.json.JsonUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The database implementation of [Snapshots], which keeps the immutable configuration a fragment had at
/// each promotion, so that a stream can later be reprocessed exactly as it was first processed.
///
/// There is one row per promotion, keyed by the snapshot id. The code and the versions are stored as JSON
/// text.
@Singleton
public class SnapshotsDao implements Snapshots {

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    SnapshotsDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void keep(final Snapshot snapshot) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT)
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.SNAPSHOT_ID, snapshot.id())
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.DOC_UUID, snapshot.docUuid())
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID, snapshot.bindingUuid())
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.FRAGMENT_UUID, snapshot.fragmentUuid())
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.STRUCTURE, snapshot.structure())
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.CODE,
                        JsonUtil.writeValueAsString(snapshot.code(), false))
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.VERSIONS,
                        JsonUtil.writeValueAsString(snapshot.versions(), false))
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.CREATE_TIME_MS, snapshot.createTimeMs())
                .execute());
    }

    @Override
    public Optional<Snapshot> byId(final String snapshotId) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT)
                .where(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.SNAPSHOT_ID.eq(snapshotId))
                .fetchOptional(SnapshotsDao::snapshot));
    }

    @Override
    public List<Snapshot> forBinding(final String bindingUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT)
                .where(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID.eq(bindingUuid))
                .orderBy(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.ID.asc())
                .fetch(SnapshotsDao::snapshot));
    }

    @Override
    public void discard(final String bindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT)
                .where(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID.eq(bindingUuid))
                .execute());
    }

    @Override
    public void move(final String fromBindingUuid, final String toBindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .update(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT)
                .set(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID, toBindingUuid)
                .where(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID.eq(fromBindingUuid))
                .execute());
    }

    private static Snapshot snapshot(final Record row) {
        return new Snapshot(
                row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.SNAPSHOT_ID),
                row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.DOC_UUID),
                row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.BINDING_UUID),
                row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.FRAGMENT_UUID),
                row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.STRUCTURE),
                strings(row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.CODE)),
                strings(row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.VERSIONS)),
                row.get(LearningFlowSnapshot.LEARNING_FLOW_SNAPSHOT.CREATE_TIME_MS));
    }

    /// Reads a JSON object of strings, which is how the row holds the code and the versions.
    private static Map<String, String> strings(final String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        final Map<String, String> strings = new LinkedHashMap<>();
        JsonUtil.readValue(json, Map.class)
                .forEach((key, value) -> strings.put(String.valueOf(key), String.valueOf(value)));
        return strings;
    }
}
