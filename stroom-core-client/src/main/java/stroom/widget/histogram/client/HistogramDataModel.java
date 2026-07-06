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

package stroom.widget.histogram.client;

import stroom.query.api.Column;
import stroom.query.api.Row;
import stroom.query.api.TableResult;
import stroom.widget.datepicker.client.UTCDate;

import java.util.List;
import java.util.function.Consumer;

/**
 * Buckets {@link TableResult} timestamps into a fixed number of histogram bins.
 * <p>
 *     The model is agnostic of how the query is run. It just takes result data
 *     and produces {@code int[]} bin counts that can be fed to a histogram widget.
 * </p>
 */
public class HistogramDataModel {

    /** Recognised time column names (case-insensitive). */
    private static final String[] TIME_COLUMN_NAMES = {
        "EffectiveTime", "Effective Time",
        "EventTime", "Event Time",
    };

    private final int binCount;
    private long rangeStart;
    private long rangeEnd;

    /** Called when bin data is ready. */
    private Consumer<int[]> dataHandler;
    /** Called with {min, max} when data extent is discovered. */
    private Consumer<long[]> dataRangeHandler;

    /**
     * Creates a new histogram data model with the given number of bins.
     *
     * @param binCount the number of histogram bins
     */
    public HistogramDataModel(final int binCount) {
        this.binCount = binCount;
    }

    /**
     * Sets the visible time range for bucketing.
     *
     * @param start range start (epoch millis, inclusive)
     * @param end   range end (epoch millis, inclusive)
     */
    public void setRange(final long start, final long end) {
        this.rangeStart = start;
        this.rangeEnd = end;
    }

    public void setDataHandler(final Consumer<int[]> handler) {
        this.dataHandler = handler;
    }

    public void setDataRangeHandler(final Consumer<long[]> handler) {
        this.dataRangeHandler = handler;
    }

    /**
     * Parses a {@link TableResult}, finds the first recognised timestamp column
     * ({@code EffectiveTime}, {@code EventTime}, etc.), buckets the timestamps
     * into {@code binCount} bins across [{@code rangeStart}, {@code rangeEnd}],
     * and returns the per-bin counts.
     * <p>
     *     Also discovers the actual min/max data extent and notifies the
     *     {@link #dataRangeHandler} so callers can implement "Show All".
     * </p>
     *
     * @param tableResult the query result to process
     * @return the per-bin counts array
     */
    public int[] process(final TableResult tableResult) {
        final int[] bins = new int[binCount];

        if (tableResult == null
                || tableResult.getRows() == null
                || tableResult.getColumns() == null) {
            notifyDataHandler(bins);
            return bins;
        }

        final int timeColIdx = findTimeColumnIndex(tableResult.getColumns());

        if (timeColIdx == -1 || rangeEnd <= rangeStart) {
            notifyDataHandler(bins);
            return bins;
        }

        final long range = rangeEnd - rangeStart;
        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        for (final Row row : tableResult.getRows()) {
            final List<String> values = row.getValues();
            if (values == null || values.size() <= timeColIdx) {
                continue;
            }
            final String timeStr = values.get(timeColIdx);
            if (timeStr == null || timeStr.trim().isEmpty()) {
                continue;
            }
            try {
                // Parse ISO-8601 timestamp via UTCDate (e.g. "2026-04-01T09:06:46.000Z").
                final UTCDate date = UTCDate.create(timeStr);
                if (date == null) {
                    continue;
                }
                final long t = (long) date.getTime();

                // Track the overall data extent for "Show All".
                if (t < minTime) {
                    minTime = t;
                }
                if (t > maxTime) {
                    maxTime = t;
                }

                // Skip entries that fall outside the visible range — do not clamp them
                // to the edge bins, as that would make out-of-range data appear at the
                // start or end of the histogram.
                if (t < rangeStart || t > rangeEnd) {
                    continue;
                }

                final int bin = (int) Math.min(binCount - 1,
                        (t - rangeStart) * binCount / range);
                bins[bin]++;
            } catch (final Exception e) {
                // Skip unparseable timestamps.
            }
        }

        // Inform the caller of the actual data extent so "Show All" can be computed.
        if (minTime <= maxTime && dataRangeHandler != null) {
            dataRangeHandler.accept(new long[]{minTime, maxTime});
        }

        notifyDataHandler(bins);
        return bins;
    }

    private void notifyDataHandler(final int[] bins) {
        if (dataHandler != null) {
            dataHandler.accept(bins);
        }
    }

    /**
     * Looks for a well-known timestamp column in the supplied column list.
     * Recognises {@code EffectiveTime} / {@code Effective Time} (used by the
     * SQL Temporal Store facts query) and {@code EventTime} / {@code Event Time}
     * (used by standard Stroom event-source queries).
     *
     * @param columns the table columns to search
     * @return the 0-based column index, or {@code -1} if no known time column is found
     */
    public static int findTimeColumnIndex(final List<Column> columns) {
        for (int i = 0; i < columns.size(); i++) {
            final String name = columns.get(i).getName();
            for (final String timeName : TIME_COLUMN_NAMES) {
                if (timeName.equalsIgnoreCase(name)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
