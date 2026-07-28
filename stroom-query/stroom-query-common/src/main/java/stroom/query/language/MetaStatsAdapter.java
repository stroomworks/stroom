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

package stroom.query.language;

import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.SelectionSummary;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.planner.port.MetaStats;
import stroom.query.planner.port.RowCountSignal;
import stroom.util.date.DateUtil;

import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * The real {@link MetaStats} adapter, wrapping {@link MetaService#getSelectionSummary(FindMetaCriteria)} - see
 * Task 3.1.
 */
public class MetaStatsAdapter implements MetaStats {

    private final MetaService metaService;

    @Inject
    public MetaStatsAdapter(final MetaService metaService) {
        this.metaService = Objects.requireNonNull(metaService, "metaService");
    }

    @Override
    public Optional<RowCountSignal> estimate(
            final String feedName, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs) {
        Objects.requireNonNull(feedName, "feedName");
        if (!metaService.getFeeds().contains(feedName)) {
            // getSelectionSummary alone can't distinguish "unknown feed" from "known feed, zero rows in range" -
            // both would otherwise report itemCount=0 - so existence is checked explicitly here.
            return Optional.empty();
        }

        final ExpressionOperator.Builder builder = ExpressionOperator.builder()
                .addOperator(MetaExpressionUtil.createFeedExpression(feedName));
        addTimeRangeTerm(builder, fromTimeMs, toTimeMs);

        final FindMetaCriteria criteria = new FindMetaCriteria(builder.build());
        final SelectionSummary summary = metaService.getSelectionSummary(criteria);
        if (summary == null) {
            return Optional.empty();
        }
        return Optional.of(new RowCountSignal(summary.getItemCount()));
    }

    private static void addTimeRangeTerm(
            final ExpressionOperator.Builder builder, final @Nullable Long fromTimeMs,
            final @Nullable Long toTimeMs) {
        if (fromTimeMs != null && toTimeMs != null) {
            builder.addDateTerm(MetaFields.CREATE_TIME, Condition.BETWEEN,
                    DateUtil.createNormalDateTimeString(fromTimeMs) + "," + DateUtil.createNormalDateTimeString(
                            toTimeMs));
        } else if (fromTimeMs != null) {
            builder.addDateTerm(MetaFields.CREATE_TIME, Condition.GREATER_THAN_OR_EQUAL_TO,
                    DateUtil.createNormalDateTimeString(fromTimeMs));
        } else if (toTimeMs != null) {
            builder.addDateTerm(MetaFields.CREATE_TIME, Condition.LESS_THAN,
                    DateUtil.createNormalDateTimeString(toTimeMs));
        }
    }
}
