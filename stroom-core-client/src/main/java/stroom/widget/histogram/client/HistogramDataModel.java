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

    /**
     * Places counts that the server has already bucketed.
     *
     * <p>The counterpart to {@link #process(TableResult)}, which counts individual events. Where the
     * query groups by a time bucket, each row is one bucket and a count, so the number of rows is
     * bounded by the range rather than by how many events the store holds — which is the whole
     * reason for grouping server-side.</p>
     *
     * <p><b>Columns are taken by position, not by name.</b> The caller generated the query, so it
     * knows the first column is the bucket and the second is its count; matching on a name would
     * couple this to the exact text of a query it does not own, and an aggregate's default column
     * name is not something to depend on.</p>
     *
     * <p>Bins are sized to the range at the given width, so one bin is one bucket and no
     * redistribution is needed. A bucket outside the visible range is skipped rather than clamped to
     * an edge bin, for the same reason {@link #process(TableResult)} skips it: clamping would pile
     * activity from outside the range onto the first and last bars.</p>
     *
     * @param tableResult  the grouped result; a null or empty one yields empty bins
     * @param bucketWidthMs the width each row covers, which must match the width the query grouped
     *                      by, or counts land in the wrong bars
     * @return the per-bin counts, also passed to the data handler
     */
    public int[] processBuckets(final TableResult tableResult, final long bucketWidthMs) {
        final long range = rangeEnd - rangeStart;
        if (bucketWidthMs <= 0 || range <= 0) {
            final int[] empty = new int[binCount];
            notifyDataHandler(empty);
            return empty;
        }

        final long firstBucket = floorTo(rangeStart, bucketWidthMs);
        final int bins = (int) (((floorTo(rangeEnd, bucketWidthMs) - firstBucket) / bucketWidthMs) + 1L);
        final int[] counts = new int[Math.max(1, bins)];

        if (tableResult == null || tableResult.getRows() == null) {
            notifyDataHandler(counts);
            return counts;
        }

        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;

        for (final Row row : tableResult.getRows()) {
            final List<String> values = row.getValues();
            if (values == null || values.size() < 2) {
                continue;
            }
            final Long bucketStart = parseTime(values.get(0));
            if (bucketStart == null) {
                continue;
            }

            // The extent is the data's, not the visible range's, so "Show All" can reach data
            // outside what is currently shown. A bucket stands for everything within its width.
            if (bucketStart < minTime) {
                minTime = bucketStart;
            }
            if (bucketStart + bucketWidthMs > maxTime) {
                maxTime = bucketStart + bucketWidthMs;
            }

            if (bucketStart < firstBucket || bucketStart > rangeEnd) {
                continue;
            }
            final int index = (int) ((bucketStart - firstBucket) / bucketWidthMs);
            if (index >= 0 && index < counts.length) {
                counts[index] += parseCount(values.get(1));
            }
        }

        if (minTime <= maxTime && dataRangeHandler != null) {
            dataRangeHandler.accept(new long[]{minTime, maxTime});
        }

        notifyDataHandler(counts);
        return counts;
    }

    /** Floors to a multiple of {@code width}, matching {@code floorTime}, which floors to the epoch. */
    private static long floorTo(final long time, final long width) {
        final long remainder = time % width;
        return remainder >= 0
                ? time - remainder
                : time - remainder - width;
    }

    /** An ISO-8601 instant as epoch millis, or null where it will not parse. */
    private static Long parseTime(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            final UTCDate date = UTCDate.create(value);
            return date == null
                    ? null
                    : (long) date.getTime();
        } catch (final Exception e) {
            return null;
        }
    }

    /** A count column as an int; anything unreadable counts as zero rather than failing the bar. */
    private static int parseCount(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
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
