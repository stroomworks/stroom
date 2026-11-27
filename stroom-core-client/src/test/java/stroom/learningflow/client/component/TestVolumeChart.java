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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.VolumeDay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TestVolumeChart {

    private static final Pattern BAR = Pattern.compile("class=\"quality-tab__chart-bar\" d=\"M[^ ]+ V([0-9.]+)");

    @Test
    void eachDayIsABarAsTallAsItsRecordsAgainstTheBusiestDay() {
        final String svg = VolumeChart.draw(List.of(day(0, 1L, 50L), day(1, 2L, 100L), day(2, 0L, 0L)),
                ms -> "day " + ms / 86_400_000L);

        assertThat(svg).startsWith("<svg").endsWith("</svg>");
        assertThat(svg).contains("role=\"img\"")
                .contains("aria-label=\"Records translated each day, 3 days: 150 in all\"");
        // Every day has a tooltip and a hit target, but a day with no records has no bar
        assertThat(svg.split("<title>", -1)).hasSize(4);
        assertThat(svg.split("quality-tab__chart-hit", -1)).hasSize(4);
        final List<Double> tops = tops(svg);
        assertThat(tops).hasSize(2);
        assertThat(tops.get(1)).describedAs("the busiest day reaches higher").isLessThan(tops.get(0));
        assertThat(svg).contains("<title>day 1: 100 records in 2 streams</title>")
                .contains("<title>day 0: 50 records in 1 stream</title>");
        assertThat(svg).contains(">day 0</text>").contains(">day 2</text>");
    }

    @Test
    void daysWhoseRecordsWerentCountedAreDrawnByStreams() {
        final List<VolumeDay> days = List.of(day(0, 3L, 0L), day(1, 1L, 0L));
        assertThat(VolumeChart.byRecords(days)).isFalse();
        final String svg = VolumeChart.draw(days, ms -> "d");
        assertThat(svg).contains("aria-label=\"Streams translated each day, 2 days: 4 in all\"");
        assertThat(tops(svg)).hasSize(2);
    }

    @Test
    void labelsAreEscaped() {
        final String svg = VolumeChart.draw(List.of(day(0, 1L, 1L)), ms -> "<b>&");
        assertThat(svg).contains("&lt;b&gt;&amp;").doesNotContain("<b>");
    }

    @Test
    void noDaysIsABaselineAlone() {
        final String svg = VolumeChart.draw(List.of(), ms -> "");
        assertThat(svg).contains("quality-tab__chart-axis").doesNotContain("quality-tab__chart-bar");
    }

    private static List<Double> tops(final String svg) {
        final Matcher matcher = BAR.matcher(svg);
        final List<Double> tops = new ArrayList<>();
        while (matcher.find()) {
            tops.add(Double.parseDouble(matcher.group(1)));
        }
        return tops;
    }

    private static VolumeDay day(final int day, final Long streams, final Long records) {
        return new VolumeDay(day * 86_400_000L, streams, records);
    }
}
