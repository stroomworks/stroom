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
import stroom.learningflow.impl.db.jooq.tables.LearningFlowOutput;
import stroom.learningflow.quality.Volumes;
import stroom.learningflow.shared.VolumeDay;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/// The database implementation of [Volumes], which reports how much a learner's translations produced per
/// day (UTC), for the volume chart on a Smart Pipeline's Quality Assurance tab.
///
/// It reads the output rows that each binding recorded for each stream it translated: each row counts as a
/// stream, with its record and skip counts. A row written before record counts were kept counts as a stream
/// with no records. A stream is counted on the day it was first translated, however often it was
/// reprocessed later.
@Singleton
public class VolumesDao implements Volumes {

    private static final LearningFlowOutput OUTPUT = LearningFlowOutput.LEARNING_FLOW_OUTPUT;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final LearningFlowDbConnProvider connProvider;

    @Inject
    VolumesDao(final LearningFlowDbConnProvider connProvider) {
        this.connProvider = connProvider;
    }

    @Override
    public List<VolumeDay> perDay(final Collection<String> bindingUuids, final long sinceMs) {
        if (bindingUuids == null || bindingUuids.isEmpty()) {
            return List.of();
        }
        final Field<Long> day = DSL.field("FLOOR({0} / {1}) * {1}", SQLDataType.BIGINT,
                OUTPUT.CREATE_TIME_MS, DSL.inline(DAY_MS));
        final Field<Integer> streams = DSL.count();
        final Field<BigDecimal> records = DSL.sum(DSL.coalesce(OUTPUT.RECORD_COUNT, 0));
        final Field<BigDecimal> skips = DSL.sum(DSL.coalesce(OUTPUT.SKIP_COUNT, 0));
        return JooqUtil.contextResult(connProvider, context -> context
                .select(day, streams, records, skips)
                .from(OUTPUT)
                .where(OUTPUT.BINDING_UUID.in(bindingUuids))
                .and(OUTPUT.CREATE_TIME_MS.ge(sinceMs))
                .groupBy(day)
                .orderBy(day)
                .fetch(row -> new VolumeDay(
                        row.get(day),
                        Long.valueOf(row.get(streams)),
                        Objects.requireNonNullElse(row.get(records), BigDecimal.ZERO).longValue(),
                        Objects.requireNonNullElse(row.get(skips), BigDecimal.ZERO).longValue())));
    }
}
