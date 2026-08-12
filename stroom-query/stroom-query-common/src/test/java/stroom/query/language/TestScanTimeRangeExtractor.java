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
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.port.FieldInfoSource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5.1: direct branch coverage for {@link ScanTimeRangeExtractor} - the open-ended (to-only) bound, the
 * malformed-{@code BETWEEN} guard, and the unparseable-value guard, which a well-formed parsed query can't reach
 * through {@code create()} (so they need a hand-built {@link ExpressionTerm}).
 *
 * <p>Tasks 8.3/8.4: boundary-equality pins for every condition (the derived range may widen the user's bounds
 * but must never narrow them - including the deliberate lower-bound asymmetry), and the disabled-item guards.</p>
 */
class TestScanTimeRangeExtractor {

    private static final AstPosition POS = new AstPosition(1, 0);
    private static final QueryField EVENT_TIME = QueryField.builder()
            .fldName("EventTime").fldType(FieldType.DATE).build();
    private static final String BOUND_TEXT = "2020-02-01T00:00:00.000Z";
    private static final long BOUND_MS = java.time.Instant.parse(BOUND_TEXT).toEpochMilli();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return List.of(EVENT_TIME);
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.of(EVENT_TIME);
        }
    };

    private static ExpressionContext expressionContext() {
        return ExpressionContext.builder()
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .maxStringLength(100)
                .build();
    }

    private static ExpressionTerm timeTerm(final Condition condition, final String value) {
        return ExpressionTerm.builder().field("EventTime").condition(condition).value(value).build();
    }

    private static ScanTimeBounds extract(final ExpressionTerm... terms) {
        final ExpressionOperator where = ExpressionOperator.builder().op(Op.AND).children(List.of(terms)).build();
        return ScanTimeRangeExtractor.extract(
                new Scan("s", "Events", POS),
                new Filter(new Scan("s", "Events", POS), where, null, POS),
                FIELD_INFO_SOURCE,
                expressionContext());
    }

    // ------------------------------------------------------------------------------------------------------
    // Task 8.3 boundary equality: toTimeMs is EXCLUSIVE (Query.timeRange's upper bound is applied as a strict
    // < at search time), so a row at exactly the user's bound must satisfy `rowMs < toTimeMs` whenever the
    // user's condition matches it. fromTimeMs is INCLUSIVE (applied as >=) and deliberately asymmetric - see
    // the greaterThan tests below.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void lessThan_mapsExactly_theBoundaryRowIsExcludedByBothUserAndRange() {
        // User's < already excludes the row at BOUND_MS, and so does `BOUND_MS < BOUND_MS` - exact, no widening.
        final ScanTimeBounds bounds = extract(timeTerm(Condition.LESS_THAN, BOUND_TEXT));

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isEqualTo(BOUND_MS);
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void lessThanOrEqualTo_widensByOneMs_soTheBoundaryRowStaysInsideTheExclusiveRange() {
        // User's <= matches the row at exactly BOUND_MS. toTimeMs is exclusive, so it must be BOUND_MS + 1 -
        // emitting BOUND_MS would make the injected strict < drop that row (returned under mode OFF, silently
        // dropped under ON). +1ms is exact, not a fudge: time values are whole milliseconds.
        final ScanTimeBounds bounds = extract(timeTerm(Condition.LESS_THAN_OR_EQUAL_TO, BOUND_TEXT));

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isEqualTo(BOUND_MS + 1);
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void between_widensOnlyTheInclusiveUpperBound_theLowerMapsExactly() {
        // BETWEEN is inclusive at both ends. The lower lands in the >= slot exactly; the upper must be widened
        // to keep the row at exactly the upper bound inside the exclusive range.
        final ScanTimeBounds bounds = extract(
                timeTerm(Condition.BETWEEN, "2020-01-01T00:00:00.000Z," + BOUND_TEXT));

        assertThat(bounds.fromTimeMs()).isEqualTo(
                java.time.Instant.parse("2020-01-01T00:00:00.000Z").toEpochMilli());
        assertThat(bounds.toTimeMs()).isEqualTo(BOUND_MS + 1);
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void greaterThan_isNotWidened_theAsymmetryWithTheUpperBoundIsDeliberate() {
        // Pins the safe asymmetry so a later "symmetry cleanup" cannot reintroduce the Task 8.3 bug: fromTimeMs
        // is applied as >= at search time, so BOUND_MS here makes the range 1ms WIDER than the user's > - safe,
        // because the retained WHERE still excludes the boundary row. Nudging it to BOUND_MS + 1 would be the
        // narrowing direction. Do NOT "fix" this to mirror lessThanOrEqualTo.
        final ScanTimeBounds bounds = extract(timeTerm(Condition.GREATER_THAN, BOUND_TEXT));

        assertThat(bounds.fromTimeMs()).isEqualTo(BOUND_MS);
        assertThat(bounds.toTimeMs()).isNull();
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void greaterThanOrEqualTo_mapsExactly_theBoundaryRowIsInsideTheInclusiveLowerBound() {
        // >= BOUND_MS and the injected >= agree exactly - the boundary row is inside both. Together with the
        // greaterThan test this pins that BOTH lower conditions map to the same millisecond on purpose.
        final ScanTimeBounds bounds = extract(timeTerm(Condition.GREATER_THAN_OR_EQUAL_TO, BOUND_TEXT));

        assertThat(bounds.fromTimeMs()).isEqualTo(BOUND_MS);
        assertThat(bounds.toTimeMs()).isNull();
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------
    // Task 8.4: a disabled item is opaque - the evaluator ignores it, so letting it derive a bound would
    // narrow the scan below what the evaluated predicate matches (the one direction that is never safe).
    // ------------------------------------------------------------------------------------------------------

    @Test
    void disabledTimeTerm_derivesNoBound_andIsNotASelectivityTerm() {
        // The evaluator ignores a disabled term, so `EventTime < BOUND` disabled constrains nothing - deriving
        // toTimeMs from it would prune shards holding rows the evaluated predicate matches.
        final ExpressionTerm disabled = ExpressionTerm.builder()
                .field("EventTime").condition(Condition.LESS_THAN).value(BOUND_TEXT).enabled(false).build();

        final ScanTimeBounds bounds = extract(disabled);

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isNull();
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void disabledTimeTerm_doesNotNarrowTheBoundAnEnabledTermDerived() {
        // An enabled `< 2020-02-01` alongside a disabled `< 2020-01-01`: only the enabled bound may win. Under
        // the pre-8.4 behaviour the disabled term's earlier date would intersect in and narrow the range.
        final ExpressionTerm disabledNarrower = ExpressionTerm.builder()
                .field("EventTime").condition(Condition.LESS_THAN)
                .value("2020-01-01T00:00:00.000Z").enabled(false).build();

        final ScanTimeBounds bounds = extract(timeTerm(Condition.LESS_THAN, BOUND_TEXT), disabledNarrower);

        assertThat(bounds.toTimeMs()).isEqualTo(BOUND_MS);
    }

    @Test
    void disabledPredicateOperator_derivesNoBoundFromItsChildren() {
        // The whole WHERE operator disabled: none of its children are evaluated, so none may contribute.
        final ExpressionOperator where = ExpressionOperator.builder()
                .op(Op.AND)
                .enabled(false)
                .children(List.of(timeTerm(Condition.LESS_THAN, BOUND_TEXT)))
                .build();

        final ScanTimeBounds bounds = ScanTimeRangeExtractor.extract(
                new Scan("s", "Events", POS),
                new Filter(new Scan("s", "Events", POS), where, null, POS),
                FIELD_INFO_SOURCE,
                expressionContext());

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isNull();
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void malformedBetween_withNoCommaSeparator_isTreatedAsAnOrdinarySelectivityTerm() {
        // A well-formed `between X and Y` always yields a two-part "X,Y" value; a value with no comma can only
        // arise from a hand-built term. The extractor must not throw or invent a bound - it degrades to treating
        // the term as a plain selectivity term.
        final ExpressionTerm malformed = timeTerm(Condition.BETWEEN, "onlyOneValueNoComma");

        final ScanTimeBounds bounds = extract(malformed);

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isNull();
        assertThat(bounds.selectivityTerms()).containsExactly(malformed);
    }

    @Test
    void unparseableTimeValue_isTreatedAsAnOrdinarySelectivityTerm() {
        // DateExpressionParser.getMs throws on a non-date value; the extractor catches it and treats the term as
        // a selectivity term rather than failing the caller.
        final ExpressionTerm unparseable = timeTerm(Condition.GREATER_THAN, "not-a-date");

        final ScanTimeBounds bounds = extract(unparseable);

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isNull();
        assertThat(bounds.selectivityTerms()).containsExactly(unparseable);
    }
}
