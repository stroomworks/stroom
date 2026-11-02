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

package stroom.pipelinefactory.impl.db;

import stroom.db.util.JooqUtil;
import stroom.docref.DocRef;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.impl.Combination;
import stroom.pipelinefactory.impl.Combinations;
import stroom.pipelinefactory.impl.Made;
import stroom.pipelinefactory.impl.MetCombination;
import stroom.pipelinefactory.impl.MetCombination.State;
import stroom.pipelinefactory.impl.db.jooq.tables.records.PipelineFactoryCombinationRecord;
import stroom.util.json.JsonUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static stroom.pipelinefactory.impl.db.jooq.tables.PipelineFactoryCombination.PIPELINE_FACTORY_COMBINATION;
import static stroom.pipelinefactory.impl.db.jooq.tables.PipelineFactoryScan.PIPELINE_FACTORY_SCAN;

/// What each Pipeline Factory has met, and how far it has looked, as rows (design 06 §3.4).
///
/// A combination is stored as its fields and values in JSON with the fields in name order, so the same
/// combination is the same text whatever order the factory's key lists its fields in, and it is found by
/// the SHA-256 of that text.
@Singleton
public class CombinationsDao implements Combinations {

    private final PipelineFactoryDbConnProvider connProvider;

    @Inject
    CombinationsDao(final PipelineFactoryDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public long lastMetaId(final String factoryUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(PIPELINE_FACTORY_SCAN.LAST_META_ID)
                        .from(PIPELINE_FACTORY_SCAN)
                        .where(PIPELINE_FACTORY_SCAN.FACTORY_UUID.eq(factoryUuid))
                        .fetchOptional(PIPELINE_FACTORY_SCAN.LAST_META_ID))
                .orElse(0L);
    }

    @Override
    public void lastMetaId(final String factoryUuid, final long lastMetaId) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connProvider, context -> context
                .insertInto(PIPELINE_FACTORY_SCAN)
                .set(PIPELINE_FACTORY_SCAN.VERSION, 1)
                .set(PIPELINE_FACTORY_SCAN.UPDATE_TIME_MS, now)
                .set(PIPELINE_FACTORY_SCAN.FACTORY_UUID, factoryUuid)
                .set(PIPELINE_FACTORY_SCAN.LAST_META_ID, lastMetaId)
                .onDuplicateKeyUpdate()
                .set(PIPELINE_FACTORY_SCAN.VERSION, PIPELINE_FACTORY_SCAN.VERSION.plus(1))
                .set(PIPELINE_FACTORY_SCAN.UPDATE_TIME_MS, now)
                .set(PIPELINE_FACTORY_SCAN.LAST_META_ID, lastMetaId)
                .execute());
    }

    @Override
    public boolean met(final String factoryUuid, final Combination combination) {
        final String text = textOf(combination);
        final long now = System.currentTimeMillis();
        // The unique key decides: a combination already met inserts nothing, and says so.
        return JooqUtil.contextResult(connProvider, context -> context
                .insertInto(PIPELINE_FACTORY_COMBINATION)
                .set(PIPELINE_FACTORY_COMBINATION.VERSION, 1)
                .set(PIPELINE_FACTORY_COMBINATION.CREATE_TIME_MS, now)
                .set(PIPELINE_FACTORY_COMBINATION.UPDATE_TIME_MS, now)
                .set(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID, factoryUuid)
                .set(PIPELINE_FACTORY_COMBINATION.COMBINATION_HASH, hash(text))
                .set(PIPELINE_FACTORY_COMBINATION.COMBINATION, text)
                .set(PIPELINE_FACTORY_COMBINATION.STATE, State.PENDING.name())
                .onDuplicateKeyIgnore()
                .execute()) > 0;
    }

    @Override
    public void made(final String factoryUuid, final Combination combination, final Made made) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connProvider, context -> context
                .update(PIPELINE_FACTORY_COMBINATION)
                .set(PIPELINE_FACTORY_COMBINATION.STATE, State.MADE.name())
                .set(PIPELINE_FACTORY_COMBINATION.TRIES, PIPELINE_FACTORY_COMBINATION.TRIES.plus(1))
                .set(PIPELINE_FACTORY_COMBINATION.PIPELINE_UUID, made.pipeline().getUuid())
                .set(PIPELINE_FACTORY_COMBINATION.PIPELINE_NAME, made.pipeline().getName())
                .set(PIPELINE_FACTORY_COMBINATION.FILTER_UUID, made.filter().getUuid())
                .setNull(PIPELINE_FACTORY_COMBINATION.MESSAGE)
                .set(PIPELINE_FACTORY_COMBINATION.VERSION, PIPELINE_FACTORY_COMBINATION.VERSION.plus(1))
                .set(PIPELINE_FACTORY_COMBINATION.UPDATE_TIME_MS, now)
                .where(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID.eq(factoryUuid))
                .and(PIPELINE_FACTORY_COMBINATION.COMBINATION_HASH.eq(hash(textOf(combination))))
                .execute());
    }

    @Override
    public void failed(final String factoryUuid, final Combination combination, final String message) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connProvider, context -> context
                .update(PIPELINE_FACTORY_COMBINATION)
                .set(PIPELINE_FACTORY_COMBINATION.STATE, State.FAILED.name())
                .set(PIPELINE_FACTORY_COMBINATION.TRIES, PIPELINE_FACTORY_COMBINATION.TRIES.plus(1))
                .set(PIPELINE_FACTORY_COMBINATION.MESSAGE, message)
                .set(PIPELINE_FACTORY_COMBINATION.VERSION, PIPELINE_FACTORY_COMBINATION.VERSION.plus(1))
                .set(PIPELINE_FACTORY_COMBINATION.UPDATE_TIME_MS, now)
                .where(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID.eq(factoryUuid))
                .and(PIPELINE_FACTORY_COMBINATION.COMBINATION_HASH.eq(hash(textOf(combination))))
                .execute());
    }

    @Override
    public List<Combination> unmade(final String factoryUuid, final long beforeMs) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .select(PIPELINE_FACTORY_COMBINATION.COMBINATION)
                        .from(PIPELINE_FACTORY_COMBINATION)
                        .where(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID.eq(factoryUuid))
                        .and(PIPELINE_FACTORY_COMBINATION.STATE.ne(State.MADE.name()))
                        .and(PIPELINE_FACTORY_COMBINATION.UPDATE_TIME_MS.lt(beforeMs))
                        .orderBy(PIPELINE_FACTORY_COMBINATION.ID)
                        .fetch(PIPELINE_FACTORY_COMBINATION.COMBINATION))
                .stream()
                .map(CombinationsDao::combinationOf)
                .toList();
    }

    @Override
    public List<MetCombination> list(final String factoryUuid) {
        return JooqUtil.contextResult(connProvider, context -> context
                        .selectFrom(PIPELINE_FACTORY_COMBINATION)
                        .where(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID.eq(factoryUuid))
                        .orderBy(PIPELINE_FACTORY_COMBINATION.ID)
                        .fetch())
                .stream()
                .map(CombinationsDao::metOf)
                .toList();
    }

    @Override
    public boolean forget(final String factoryUuid, final Combination combination) {
        return JooqUtil.contextResult(connProvider, context -> context
                .deleteFrom(PIPELINE_FACTORY_COMBINATION)
                .where(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID.eq(factoryUuid))
                .and(PIPELINE_FACTORY_COMBINATION.COMBINATION_HASH.eq(hash(textOf(combination))))
                .execute()) > 0;
    }

    @Override
    public void forgetFactory(final String factoryUuid) {
        JooqUtil.transaction(connProvider, context -> {
            context.deleteFrom(PIPELINE_FACTORY_COMBINATION)
                    .where(PIPELINE_FACTORY_COMBINATION.FACTORY_UUID.eq(factoryUuid))
                    .execute();
            context.deleteFrom(PIPELINE_FACTORY_SCAN)
                    .where(PIPELINE_FACTORY_SCAN.FACTORY_UUID.eq(factoryUuid))
                    .execute();
        });
    }

    private static MetCombination metOf(final PipelineFactoryCombinationRecord row) {
        final DocRef pipeline = Optional.ofNullable(row.getPipelineUuid())
                .map(uuid -> PipelineDoc.buildDocRef().uuid(uuid).name(row.getPipelineName()).build())
                .orElse(null);
        return new MetCombination(combinationOf(row.getCombination()),
                State.valueOf(row.getState()),
                row.getTries(),
                pipeline,
                row.getFilterUuid(),
                row.getMessage(),
                row.getCreateTimeMs(),
                row.getUpdateTimeMs());
    }

    static String textOf(final Combination combination) {
        return JsonUtil.writeValueAsString(new TreeMap<>(combination.values()), false);
    }

    static Combination combinationOf(final String text) {
        final Map<?, ?> read = JsonUtil.readValue(text, Map.class);
        final Map<String, String> values = new TreeMap<>();
        read.forEach((field, value) -> values.put(String.valueOf(field), String.valueOf(value)));
        return new Combination(values);
    }

    static String hash(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
