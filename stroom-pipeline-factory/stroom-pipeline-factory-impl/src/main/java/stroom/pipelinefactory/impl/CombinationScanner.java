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

package stroom.pipelinefactory.impl;

import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.Status;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.security.api.SecurityContext;
import stroom.util.shared.PageRequest;

import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Reads the streams a factory has not yet looked at, a batch at a time in id order, and picks out the
/// combinations of its key's values among them (design 06 §3.2).
///
/// It reads as the factory's run-as user, so a factory meets only the feeds that user may use: a feed
/// it could not read is one whose filter could not process it either. Streams still being written are
/// read as well as finished ones, since the key values are known from the moment a stream exists and a
/// batch read past a stream is never read again. Deleted streams are not.
public class CombinationScanner {

    private final MetaService metaService;
    private final SecurityContext securityContext;

    @Inject
    CombinationScanner(final MetaService metaService, final SecurityContext securityContext) {
        this.metaService = metaService;
        this.securityContext = securityContext;
    }

    /// Read one batch.
    ///
    /// @param factory   The factory, with a run-as user.
    /// @param afterId   The highest stream id it has already read.
    /// @param batchSize How many streams to read.
    /// @return The combinations among them, and how far they reached.
    public Scan scan(final PipelineFactoryDoc factory, final long afterId, final int batchSize) {
        final FindMetaCriteria criteria = new FindMetaCriteria(expressionOf(factory, afterId));
        criteria.setPageRequest(new PageRequest(0, batchSize));
        criteria.setSort(MetaFields.ID.getFldName());
        final List<Meta> streams = securityContext.asUserResult(factory.getRunAsUser(), () ->
                metaService.find(criteria).getValues());
        final Set<Combination> found = new LinkedHashSet<>();
        long lastId = afterId;
        for (final Meta stream : streams) {
            lastId = Math.max(lastId, stream.getId());
            final Combination combination = combinationOf(factory.getKey(), stream);
            if (combination != null) {
                found.add(combination);
            }
        }
        return new Scan(found, lastId, streams.size() >= batchSize);
    }

    static ExpressionOperator expressionOf(final PipelineFactoryDoc factory, final long afterId) {
        final ExpressionOperator.Builder builder = ExpressionOperator.builder()
                .addIdTerm(MetaFields.ID, Condition.GREATER_THAN, afterId)
                .addOperator(ExpressionOperator.builder()
                        .op(Op.OR)
                        .addTextTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                        .addTextTerm(MetaFields.STATUS, Condition.EQUALS, Status.LOCKED.getDisplayValue())
                        .build());
        if (factory.getStreamFilter() != null) {
            builder.addOperator(factory.getStreamFilter());
        }
        return builder.build();
    }

    /// A stream's combination, or null if it has no value for a key field: a raw stream has no pipeline,
    /// so a factory keyed on the pipeline that wrote its streams does not meet one.
    static Combination combinationOf(final List<String> key, final Meta stream) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String field : key) {
            final String value = valueOf(field, stream);
            if (value == null || value.isBlank()) {
                return null;
            }
            values.put(field, value);
        }
        return new Combination(values);
    }

    private static String valueOf(final String field, final Meta stream) {
        if (MetaFields.FEED.getFldName().equals(field)) {
            return stream.getFeedName();
        } else if (MetaFields.TYPE.getFldName().equals(field)) {
            return stream.getTypeName();
        } else if (MetaFields.PIPELINE.getFldName().equals(field)) {
            return stream.getPipelineUuid();
        }
        throw new IllegalArgumentException("'" + field + "' cannot be a key field");
    }


    // --------------------------------------------------------------------------------


    /// One batch read.
    ///
    /// @param found  The combinations among the streams read, in the order first met.
    /// @param lastId The highest stream id read, or where the read began if it read none.
    /// @param full   Whether the batch was full, and so whether there may be more to read now.
    public record Scan(Set<Combination> found, long lastId, boolean full) {

    }
}
