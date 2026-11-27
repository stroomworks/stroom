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

import com.google.gwt.safehtml.shared.SafeHtmlUtils;

import java.util.List;
import java.util.function.LongFunction;

/// Draws the small bar chart of a Smart Pipeline's daily translated volume on the pipeline's Quality Assurance
/// tab.
///
/// There is one bar per day, oldest on the left. A bar's height is the number of records translated that day,
/// or, if no day has a record count (e.g. streams translated before record counts were kept), the number of
/// streams. With only one measure there is one colour and no legend. Each bar's tooltip gives its day and
/// numbers.
///
/// The chart is SVG markup with `role="img"` and an accessible label describing it. It is built as a string
/// here so that it can be unit tested without a browser. The same numbers are also given in words above the
/// chart and in a day-by-day table beneath it.
public final class VolumeChart {

    static final int WIDTH = 560;
    static final int HEIGHT = 120;
    private static final int PLOT_TOP = 6;
    private static final int PLOT_BOTTOM = 96;
    private static final int GAP = 2;
    private static final int RADIUS = 4;
    private static final int LABEL_BASELINE = 114;

    private VolumeChart() {
        // Static utility
    }

    /// @param days The days, oldest first.
    /// @return True if the bars show records, or false if no day has a record count and they show streams.
    public static boolean byRecords(final List<VolumeDay> days) {
        return days.stream().anyMatch(day -> day.getRecords() > 0);
    }

    /// Draws the chart.
    ///
    /// @param days     The days, oldest first.
    /// @param dayLabel Formats the start of a day in milliseconds as a label, e.g. "17 Nov".
    /// @return The SVG markup, with all text in it HTML-escaped.
    public static String draw(final List<VolumeDay> days, final LongFunction<String> dayLabel) {
        final boolean records = byRecords(days);
        long most = 0;
        long total = 0;
        for (final VolumeDay day : days) {
            most = Math.max(most, value(day, records));
            total += value(day, records);
        }
        final String measure = records
                ? "Records"
                : "Streams";
        final StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"quality-tab__chart-svg\" role=\"img\" ")
                .append("viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT).append("\" aria-label=\"")
                .append(escape(measure + " translated each day, " + days.size() + " days: " + total + " in all"))
                .append("\">");
        // The baseline, styled to be unobtrusive
        svg.append("<line class=\"quality-tab__chart-axis\" x1=\"0\" x2=\"").append(WIDTH).append("\" y1=\"")
                .append(PLOT_BOTTOM).append("\" y2=\"").append(PLOT_BOTTOM).append("\"/>");
        if (days.isEmpty()) {
            return svg.append("</svg>").toString();
        }
        final double slot = (double) WIDTH / days.size();
        final double barWidth = Math.max(1, slot - GAP);
        for (int i = 0; i < days.size(); i++) {
            final VolumeDay day = days.get(i);
            final long value = value(day, records);
            final double x = i * slot + GAP / 2.0;
            final String tip = dayLabel.apply(day.getDayMs()) + ": " + day.getRecords() + " records in "
                               + day.getStreams() + (day.getStreams() == 1
                    ? " stream"
                    : " streams");
            svg.append("<g class=\"quality-tab__chart-day\"><title>").append(escape(tip)).append("</title>");
            // An invisible target the full height of the plot, so that a short bar's tooltip is easy to reach
            svg.append("<rect class=\"quality-tab__chart-hit\" x=\"").append(format(x)).append("\" y=\"")
                    .append(PLOT_TOP).append("\" width=\"").append(format(barWidth)).append("\" height=\"")
                    .append(PLOT_BOTTOM - PLOT_TOP).append("\"/>");
            if (value > 0) {
                final double height = Math.max(2, (double) (PLOT_BOTTOM - PLOT_TOP) * value / most);
                svg.append("<path class=\"quality-tab__chart-bar\" d=\"").append(bar(x, barWidth, height))
                        .append("\"/>");
            }
            svg.append("</g>");
        }
        // Label the first and last days only
        svg.append(label(0, "start", dayLabel.apply(days.get(0).getDayMs())));
        svg.append(label(WIDTH, "end", dayLabel.apply(days.get(days.size() - 1).getDayMs())));
        svg.append("</svg>");
        return svg.toString();
    }

    private static long value(final VolumeDay day, final boolean records) {
        return records
                ? day.getRecords()
                : day.getStreams();
    }

    // The SVG path for a bar standing on the baseline, with its top corners rounded
    private static String bar(final double x, final double width, final double height) {
        final double radius = Math.min(RADIUS, Math.min(width / 2, height));
        final double top = PLOT_BOTTOM - height;
        return "M" + format(x) + "," + PLOT_BOTTOM
               + " V" + format(top + radius)
               + " Q" + format(x) + "," + format(top) + " " + format(x + radius) + "," + format(top)
               + " H" + format(x + width - radius)
               + " Q" + format(x + width) + "," + format(top) + " " + format(x + width) + "," + format(top + radius)
               + " V" + PLOT_BOTTOM + " Z";
    }

    private static String label(final int x, final String anchor, final String text) {
        return "<text class=\"quality-tab__chart-label\" x=\"" + x + "\" y=\"" + LABEL_BASELINE
               + "\" text-anchor=\"" + anchor + "\">" + escape(text) + "</text>";
    }

    // Formats a number with at most one decimal place, independent of locale
    private static String format(final double value) {
        final long tenths = Math.round(value * 10);
        return tenths % 10 == 0
                ? String.valueOf(tenths / 10)
                : (tenths / 10) + "." + Math.abs(tenths % 10);
    }

    private static String escape(final String text) {
        return SafeHtmlUtils.htmlEscape(text);
    }
}
