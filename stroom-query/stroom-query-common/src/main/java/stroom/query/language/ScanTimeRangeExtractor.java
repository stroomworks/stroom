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

import stroom.query.api.DateTimeSettings;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.DateExpressionParser;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.FieldInfoSource;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a time bound (and the remaining selectivity-relevant terms) for a {@code Scan} from the {@link Filter}
 * directly above it - shared by {@link LogicalPlanExplainer} (advisory cost estimate) and
 * {@link OptimisingQueryCompiler}'s {@code create()} enhancement (real shard/time-partition pruning) - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 5.1.
 *
 * <p><b>Scope note</b>: only the common shape - a {@link Filter} directly above a {@code Scan} - is analysed.
 * A deeper/more complex shape isn't extracted from; callers just get no time bound in that case, not a wrong one.</p>
 */
final class ScanTimeRangeExtractor {

    private ScanTimeRangeExtractor() {
    }

    static ScanTimeBounds extract(
            final Scan scan,
            final Filter filter,
            final FieldInfoSource fieldInfoSource,
            final ExpressionContext expressionContext) {
        final List<ExpressionTerm> allTerms = new ArrayList<>();
        if (filter.wherePredicate() != null) {
            allTerms.addAll(topLevelTerms(filter.wherePredicate()));
        }
        if (filter.filterPredicate() != null) {
            allTerms.addAll(topLevelTerms(filter.filterPredicate()));
        }

        final String timeFieldName = fieldInfoSource.getTimeField(scan.dataSourceName())
                .map(QueryField::getFldName)
                .orElse(null);

        Long fromTimeMs = null;
        Long toTimeMs = null;
        final List<ExpressionTerm> selectivityTerms = new ArrayList<>();
        for (final ExpressionTerm term : allTerms) {
            final Long[] bounds = timeFieldName == null
                    ? null
                    : tryExtractTimeBounds(term, timeFieldName, expressionContext);
            if (bounds != null) {
                if (bounds[0] != null) {
                    fromTimeMs = fromTimeMs == null ? bounds[0] : Math.max(fromTimeMs, bounds[0]);
                }
                if (bounds[1] != null) {
                    toTimeMs = toTimeMs == null ? bounds[1] : Math.min(toTimeMs, bounds[1]);
                }
            } else {
                selectivityTerms.add(term);
            }
        }
        return new ScanTimeBounds(fromTimeMs, toTimeMs, selectivityTerms);
    }

    /** @return {@code [from, to]} (either may be null) if {@code term} contributed a time bound, or null if it
     *          isn't a recognisable time-range condition on {@code timeFieldName} - in which case the caller
     *          treats it as an ordinary selectivity term instead. Never throws: an unparseable value is treated
     *          the same as "not a time-range term" rather than failing the caller. */
    private static @Nullable Long[] tryExtractTimeBounds(
            final ExpressionTerm term, final String timeFieldName, final ExpressionContext expressionContext) {
        final String fieldName = bareFieldName(term.getField());
        if (!timeFieldName.equals(fieldName)) {
            return null;
        }
        try {
            return switch (term.getCondition()) {
                case GREATER_THAN, GREATER_THAN_OR_EQUAL_TO ->
                        new Long[]{DateExpressionParser.getMs(
                                fieldName, term.getValue(), expressionContext.getDateTimeSettings()), null};
                case LESS_THAN, LESS_THAN_OR_EQUAL_TO ->
                        new Long[]{null, DateExpressionParser.getMs(
                                fieldName, term.getValue(), expressionContext.getDateTimeSettings())};
                case BETWEEN -> {
                    final String[] parts = term.getValue().split(",", 2);
                    if (parts.length != 2) {
                        yield null;
                    }
                    final DateTimeSettings dateTimeSettings = expressionContext.getDateTimeSettings();
                    yield new Long[]{
                            DateExpressionParser.getMs(fieldName, parts[0].trim(), dateTimeSettings),
                            DateExpressionParser.getMs(fieldName, parts[1].trim(), dateTimeSettings)};
                }
                default -> null;
            };
        } catch (final RuntimeException e) {
            return null;
        }
    }

    private static String bareFieldName(final String rawField) {
        final int dot = rawField.indexOf('.');
        return dot < 0 ? rawField : rawField.substring(dot + 1);
    }

    /** The direct children of {@code predicate}'s top-level AND (or the single term/predicate itself if it
     *  isn't an AND) - the same "top-level conjuncts" notion {@code AutoWhereFilterSplitRule} uses, a small
     *  local copy since that class is package-private in a different module. */
    private static List<ExpressionTerm> topLevelTerms(final ExpressionOperator predicate) {
        final ExpressionOperator.Op op = predicate.getOp() == null ? ExpressionOperator.Op.AND : predicate.getOp();
        if (op != ExpressionOperator.Op.AND) {
            return List.of();
        }
        final List<ExpressionItem> children = predicate.getChildren() == null ? List.of() : predicate.getChildren();
        final List<ExpressionTerm> terms = new ArrayList<>();
        for (final ExpressionItem child : children) {
            if (child instanceof final ExpressionTerm term) {
                terms.add(term);
            }
        }
        return terms;
    }
}
