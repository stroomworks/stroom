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
 * {@link OptimisingQueryCompiler}'s {@code create} enhancement (real shard/time-partition pruning) - see
 * Task 5.1.
 *
 * <p><b>Scope note</b>: only the common shape - a {@link Filter} directly above a {@code Scan} - is analysed.
 * A deeper/more complex shape isn't extracted from; callers just get no time bound in that case, not a wrong one.</p>
 *
 * <p><b>The derived bound may widen, never narrow</b> (Task 8.3): the extracted range is a pruning hint, and
 * {@code TimeRange.to} is applied at search time as a strict {@code <} on the partition time field (see
 * {@code ResultStoreManager}), so {@link ScanTimeBounds#toTimeMs()} is emitted as an <i>exclusive</i> bound. An
 * inclusive user bound ({@code <=}, or the upper part of {@code between}) is therefore widened to
 * {@code bound + 1ms}; a user {@code <} maps exactly. Time values here are whole milliseconds
 * ({@link DateExpressionParser#getMs(String, String)}), so {@code +1ms} is the exact exclusive equivalent of an
 * inclusive millisecond bound - not an approximation, and not to be "tidied" back to the raw value. The lower
 * bound is deliberately asymmetric: it is injected at search time as {@code >=}, so mapping both {@code >} and
 * {@code >=} to the same millisecond only ever <i>widens</i> the range (the retained {@code WHERE} still excludes
 * the boundary row for {@code >}) - do not "fix" that to match the upper bound.</p>
 *
 * <p><b>Disabled items are opaque</b> (Task 8.4): a term or predicate with {@code enabled == false} is ignored at
 * evaluation, so inspecting it here could only <i>narrow</i> the derived range - the one direction that is never
 * safe. Disabled items are skipped entirely (never a bound, never a selectivity term), matching
 * {@code JoinPredicateSplitter}'s handling of the same flag.</p>
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

    /** @return {@code [from, to)} (either end may be null = unbounded) if {@code term} contributed a time bound,
     *          or null if it isn't a recognisable time-range condition on {@code timeFieldName} - in which case
     *          the caller treats it as an ordinary selectivity term instead. {@code to} is <i>exclusive</i>: an
     *          inclusive user bound is widened to {@code bound + 1ms} (see the class Javadoc). Never throws: an
     *          unparseable value is treated the same as "not a time-range term" rather than failing the caller. */
    private static @Nullable Long[] tryExtractTimeBounds(
            final ExpressionTerm term, final String timeFieldName, final ExpressionContext expressionContext) {
        final String fieldName = bareFieldName(term.getField());
        if (!timeFieldName.equals(fieldName)) {
            return null;
        }
        try {
            return switch (term.getCondition()) {
                // Both map to the same 'from': the range's lower bound is applied as >= at search time, so this
                // is exact for >= and 1ms *wider* than a user's > - safe, because the retained WHERE still
                // excludes the boundary row. Deliberately NOT symmetrical with the upper bound below.
                case GREATER_THAN, GREATER_THAN_OR_EQUAL_TO ->
                        new Long[]{DateExpressionParser.getMs(
                                fieldName, term.getValue(), expressionContext.getDateTimeSettings()), null};
                // The range's upper bound is applied as a strict < at search time, so a user's < maps exactly...
                case LESS_THAN ->
                        new Long[]{null, DateExpressionParser.getMs(
                                fieldName, term.getValue(), expressionContext.getDateTimeSettings())};
                // ...but a user's <= must be widened to the exclusive equivalent, or the row at exactly the
                // bound would be silently dropped.
                case LESS_THAN_OR_EQUAL_TO ->
                        new Long[]{null, exclusiveUpperBound(DateExpressionParser.getMs(
                                fieldName, term.getValue(), expressionContext.getDateTimeSettings()))};
                case BETWEEN -> {
                    final String[] parts = term.getValue().split(",", 2);
                    if (parts.length != 2) {
                        yield null;
                    }
                    final DateTimeSettings dateTimeSettings = expressionContext.getDateTimeSettings();
                    // BETWEEN is inclusive at both ends: the lower maps exactly (>= at search time), the upper
                    // is widened like <= above.
                    yield new Long[]{
                            DateExpressionParser.getMs(fieldName, parts[0].trim(), dateTimeSettings),
                            exclusiveUpperBound(
                                    DateExpressionParser.getMs(fieldName, parts[1].trim(), dateTimeSettings))};
                }
                default -> null;
            };
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * The exclusive equivalent of an inclusive millisecond upper bound: {@code inclusiveMs + 1}. Exact, not a
     * fudge - time values here are whole milliseconds, so {@code [x, bound]} and {@code [x, bound + 1)} contain
     * exactly the same instants. At {@link Long#MAX_VALUE} the {@code +1} would overflow (narrowing the range to
     * nothing), so the bound is dropped instead - unbounded is the widest, and a wider hint is always safe.
     *
     * @return the exclusive bound, or null (no upper bound) if widening would overflow.
     */
    private static @Nullable Long exclusiveUpperBound(final long inclusiveMs) {
        return inclusiveMs == Long.MAX_VALUE
                ? null
                : inclusiveMs + 1;
    }

    private static String bareFieldName(final String rawField) {
        final int dot = rawField.indexOf('.');
        return dot < 0 ? rawField : rawField.substring(dot + 1);
    }

    /** The <i>enabled</i> direct term children of {@code predicate}'s top-level AND (or nothing if it isn't an
     *  enabled AND) - the same "top-level conjuncts" notion {@code AutoWhereFilterSplitRule} uses, a small
     *  local copy since that class is package-private in a different module. A disabled item is opaque (Task
     *  8.4): it is ignored at evaluation, so a disabled time term deriving a bound would narrow the scan below
     *  what the evaluated predicate matches. */
    private static List<ExpressionTerm> topLevelTerms(final ExpressionOperator predicate) {
        if (!predicate.enabled()) {
            return List.of();
        }
        final ExpressionOperator.Op op = predicate.getOp() == null ? ExpressionOperator.Op.AND : predicate.getOp();
        if (op != ExpressionOperator.Op.AND) {
            return List.of();
        }
        final List<ExpressionItem> children = predicate.getChildren() == null ? List.of() : predicate.getChildren();
        final List<ExpressionTerm> terms = new ArrayList<>();
        for (final ExpressionItem child : children) {
            if (child instanceof final ExpressionTerm term && term.enabled()) {
                terms.add(term);
            }
        }
        return terms;
    }
}
