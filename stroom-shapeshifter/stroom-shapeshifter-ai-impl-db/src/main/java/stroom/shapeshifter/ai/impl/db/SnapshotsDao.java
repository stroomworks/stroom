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

package stroom.shapeshifter.ai.impl.db;

import stroom.db.util.JooqUtil;
import stroom.shapeshifter.ai.impl.db.jooq.tables.ShapeshifterSnapshot;
import stroom.shapeshifter.ai.stage.Snapshots;
import stroom.util.json.JsonUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The snapshots of A61 as a table: one row per promotion, keyed by the snapshot id, with the structure,
/// the code and the versions as JSON text.
@Singleton
public class SnapshotsDao implements Snapshots {

    private final ShapeshifterAiDbConnProvider connProvider;

    @Inject
    SnapshotsDao(final ShapeshifterAiDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public void keep(final Snapshot snapshot) {
        JooqUtil.context(connProvider, context -> context
                .insertInto(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT)
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.SNAPSHOT_ID, snapshot.id())
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.DOC_UUID, snapshot.docUuid())
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID, snapshot.bindingUuid())
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.FRAGMENT_UUID, snapshot.fragmentUuid())
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.STRUCTURE, snapshot.structure())
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.CODE,
                        JsonUtil.writeValueAsString(snapshot.code(), false))
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.VERSIONS,
                        JsonUtil.writeValueAsString(snapshot.versions(), false))
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.CREATE_TIME_MS, snapshot.createTimeMs())
                .execute());
    }

    @Override
    public Optional<Snapshot> byId(final String snapshotId) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT)
                .where(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.SNAPSHOT_ID.eq(snapshotId))
                .fetchOptional(SnapshotsDao::snapshot));
    }

    @Override
    public List<Snapshot> forBinding(final String bindingUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                .selectFrom(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT)
                .where(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID.eq(bindingUuid))
                .orderBy(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.ID.asc())
                .fetch(SnapshotsDao::snapshot));
    }

    @Override
    public void discard(final String bindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .deleteFrom(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT)
                .where(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID.eq(bindingUuid))
                .execute());
    }

    @Override
    public void move(final String fromBindingUuid, final String toBindingUuid) {
        JooqUtil.context(connProvider, context -> context
                .update(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT)
                .set(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID, toBindingUuid)
                .where(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID.eq(fromBindingUuid))
                .execute());
    }

    private static Snapshot snapshot(final Record row) {
        return new Snapshot(
                row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.SNAPSHOT_ID),
                row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.DOC_UUID),
                row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.BINDING_UUID),
                row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.FRAGMENT_UUID),
                row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.STRUCTURE),
                strings(row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.CODE)),
                strings(row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.VERSIONS)),
                row.get(ShapeshifterSnapshot.SHAPESHIFTER_SNAPSHOT.CREATE_TIME_MS));
    }

    /// A JSON object of strings, as the row holds the code and the versions.
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
