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
 */
class TestScanTimeRangeExtractor {

    private static final AstPosition POS = new AstPosition(1, 0);
    private static final QueryField EVENT_TIME = QueryField.builder()
            .fldName("EventTime").fldType(FieldType.DATE).build();

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

    @Test
    void lessThan_yieldsAToBoundOnly() {
        final ScanTimeBounds bounds = extract(timeTerm(Condition.LESS_THAN, "2020-02-01T00:00:00.000Z"));

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isEqualTo(
                java.time.Instant.parse("2020-02-01T00:00:00.000Z").toEpochMilli());
        assertThat(bounds.selectivityTerms()).isEmpty();
    }

    @Test
    void lessThanOrEqualTo_yieldsAToBoundOnly() {
        final ScanTimeBounds bounds = extract(timeTerm(Condition.LESS_THAN_OR_EQUAL_TO, "2020-02-01T00:00:00.000Z"));

        assertThat(bounds.fromTimeMs()).isNull();
        assertThat(bounds.toTimeMs()).isNotNull();
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
