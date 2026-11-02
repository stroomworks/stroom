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


package stroom.shapeshifter.client.component;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class TestScoreValues {

    @Test
    void testPercent() {
        assertThat(ScoreValues.toPercent(0.8)).isEqualTo(80);
        assertThat(ScoreValues.toPercent(0.125)).isEqualTo(13);
        assertThat(ScoreValues.toPercent(0)).isZero();
        assertThat(ScoreValues.toPercent(1)).isEqualTo(100);
        assertThat(ScoreValues.fromPercent(80)).isEqualTo(0.8);
        assertThat(ScoreValues.fromPercent(0)).isZero();
        assertThat(ScoreValues.fromPercent(100)).isEqualTo(1.0);
    }

    @Test
    void testDays() {
        assertThat(ScoreValues.toDays(null)).isZero();
        assertThat(ScoreValues.toDays(30)).isEqualTo(30);
        assertThat(ScoreValues.fromDays(0)).isNull();
        assertThat(ScoreValues.fromDays(30)).isEqualTo(30);
    }

    @Test
    void testLines() {
        assertThat(ScoreValues.toLines(null)).isEmpty();
        assertThat(ScoreValues.toLines(List.of("a", "b"))).isEqualTo("a\nb");
        assertThat(ScoreValues.fromLines(null)).isEmpty();
        assertThat(ScoreValues.fromLines("")).isEmpty();
        assertThat(ScoreValues.fromLines(" a \n\n  \nb\n")).containsExactly("a", "b");
    }

    @Test
    void testFormat() {
        assertThat(ScoreValues.format(1.0)).isEqualTo("1");
        assertThat(ScoreValues.format(0)).isEqualTo("0");
        assertThat(ScoreValues.format(0.75)).isEqualTo("0.75");
        assertThat(ScoreValues.format(Double.POSITIVE_INFINITY)).isEqualTo("Infinity");
    }

    @Test
    void testZeroOrMore() {
        final Function<String, String> check = ScoreValues.zeroOrMore();
        assertThat(check.apply("0")).isNull();
        assertThat(check.apply(" 2.5 ")).isNull();
        assertThat(check.apply("-1")).isEqualTo("Must be a number of zero or more");
        assertThat(check.apply("abc")).isEqualTo("Must be a number of zero or more");
        assertThat(check.apply("")).isEqualTo("Must be a number of zero or more");
        assertThat(check.apply(null)).isEqualTo("Must be a number of zero or more");
    }

    @Test
    void testAboveZero() {
        final Function<String, String> check = ScoreValues.aboveZero();
        assertThat(check.apply("0.1")).isNull();
        assertThat(check.apply("0")).isEqualTo("Must be a number above zero");
        assertThat(check.apply("-3")).isEqualTo("Must be a number above zero");
        assertThat(check.apply("x")).isEqualTo("Must be a number above zero");
    }
}
