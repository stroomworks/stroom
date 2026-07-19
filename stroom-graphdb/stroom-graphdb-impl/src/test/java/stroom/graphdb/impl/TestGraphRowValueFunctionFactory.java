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

package stroom.graphdb.impl;

import stroom.query.api.datasource.FieldType;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValDate;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphRowValueFunctionFactory} in isolation - every other test in this module only exercises it
 * indirectly through {@link GraphTraversalEngine}'s {@code WHERE}/anchor-property predicate tests. Mirrors
 * {@code stroom.query.common.v2.ValuesFunctionFactory}'s own extractor contract, so these tests check the same
 * null/type-coercion edge cases that class's own tests would.
 */
class TestGraphRowValueFunctionFactory {

    private static final String KEY = "a.id";

    private GraphRowValueFunctionFactory factory() {
        return new GraphRowValueFunctionFactory(KEY);
    }

    @Test
    void createNullCheck_trueForAbsentKey() {
        final Function<Map<String, Val>, Boolean> nullCheck = factory().createNullCheck();
        assertThat(nullCheck.apply(Map.of())).isTrue();
    }

    @Test
    void createNullCheck_trueForValNullInstance() {
        final Function<Map<String, Val>, Boolean> nullCheck = factory().createNullCheck();
        assertThat(nullCheck.apply(Map.of(KEY, ValNull.INSTANCE))).isTrue();
    }

    @Test
    void createNullCheck_falseForARealValue() {
        final Function<Map<String, Val>, Boolean> nullCheck = factory().createNullCheck();
        assertThat(nullCheck.apply(Map.of(KEY, ValString.create("d-42")))).isFalse();
    }

    @Test
    void createStringExtractor_extractsTheStringForm() {
        final Function<Map<String, Val>, String> extractor = factory().createStringExtractor();
        assertThat(extractor.apply(Map.of(KEY, ValString.create("d-42")))).isEqualTo("d-42");
        assertThat(extractor.apply(Map.of(KEY, ValLong.create(42)))).isEqualTo("42");
    }

    @Test
    void createStringExtractor_nullForAbsentKey() {
        final Function<Map<String, Val>, String> extractor = factory().createStringExtractor();
        assertThat(extractor.apply(Map.of())).isNull();
    }

    @Test
    void createNumberExtractor_extractsANumericValueFromANumberOrANumericString() {
        final Function<Map<String, Val>, Double> extractor = factory().createNumberExtractor();
        assertThat(extractor.apply(Map.of(KEY, ValLong.create(200)))).isEqualTo(200.0);
        assertThat(extractor.apply(Map.of(KEY, ValString.create("3.5")))).isEqualTo(3.5);
    }

    @Test
    void createNumberExtractor_nullForANonNumericValueOrAnAbsentKey() {
        final Function<Map<String, Val>, Double> extractor = factory().createNumberExtractor();
        assertThat(extractor.apply(Map.of(KEY, ValString.create("not-a-number")))).isNull();
        assertThat(extractor.apply(Map.of())).isNull();
    }

    @Test
    void createDateExtractor_readsALongOrDateValDirectlyAsEpochMillis() {
        final Function<Map<String, Val>, Long> extractor = factory().createDateExtractor();
        assertThat(extractor.apply(Map.of(KEY, ValLong.create(123456789L)))).isEqualTo(123456789L);
        assertThat(extractor.apply(Map.of(KEY, ValDate.create(123456789L)))).isEqualTo(123456789L);
    }

    @Test
    void createDateExtractor_parsesAnIso8601StringValue() {
        final Function<Map<String, Val>, Long> extractor = factory().createDateExtractor();
        final Long millis = extractor.apply(Map.of(KEY, ValString.create("2026-01-01T00:00:00.000Z")));
        assertThat(millis).isEqualTo(Instant.parse("2026-01-01T00:00:00.000Z").toEpochMilli());
    }

    @Test
    void createDateExtractor_nullForAnAbsentKey() {
        final Function<Map<String, Val>, Long> extractor = factory().createDateExtractor();
        assertThat(extractor.apply(Map.of())).isNull();
    }

    @Test
    void createDateExtractor_anUnparsableStringYieldsNullNotAThrow() {
        // Code-review fix: DateUtil.parseNormalDateTimeString throws IllegalArgumentException (not
        // NumberFormatException) for an unparsable string. The catch clause used to catch only
        // NumberFormatException, so a non-date string propagated the exception and aborted predicate evaluation
        // for the whole query; it now catches IllegalArgumentException and yields null (no match) instead.
        final Function<Map<String, Val>, Long> extractor = factory().createDateExtractor();
        assertThat(extractor.apply(Map.of(KEY, ValString.create("not-a-date")))).isNull();
    }

    @Test
    void getFieldType_isText() {
        assertThat(factory().getFieldType()).isEqualTo(FieldType.TEXT);
    }
}
