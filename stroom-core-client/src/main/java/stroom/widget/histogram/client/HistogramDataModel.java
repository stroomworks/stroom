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

    private final int binCount;
    private long rangeStart;
    private long rangeEnd;

    /** Called when bin data is ready. */
    private Consumer<int[]> dataHandler;

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

    /**
     * Places counts that the server has already bucketed.
     *
     * <p>Where the query groups by a time bucket, each row is one bucket and a count, so the number
     * of rows is bounded by the range rather than by how many events the store holds — which is the
     * whole reason for grouping server-side. This replaced a path that returned every event and
     * bucketed them here; that path is gone, along with the timestamp-column sniffing it needed.</p>
     *
     * <p><b>Columns are taken by position, not by name.</b> The caller generated the query, so it
     * knows the first column is the bucket and the second is its count; matching on a name would
     * couple this to the exact text of a query it does not own, and an aggregate's default column
     * name is not something to depend on.</p>
     *
     * <p><b>It reports no data extent.</b> The bars are bounded below at the visible range, so the
     * buckets returned can never start earlier than what is already shown — an extent taken from
     * them could only ever grow forwards, which is not what "Show All" means. The extent comes from
     * its own unbounded query instead.</p>
     *
     * <p>Bins are sized to the given width, so one bin is one bucket and no
     * redistribution is needed. A bucket outside the visible range is skipped rather than clamped to
     * an edge bin: clamping would pile activity from outside the range onto the first and last
     * bars.</p>
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

        for (final Row row : tableResult.getRows()) {
            final List<String> values = row.getValues();
            if (values == null || values.size() < 2) {
                continue;
            }
            final Long bucketStart = parseTime(values.get(0));
            if (bucketStart == null) {
                continue;
            }

            if (bucketStart < firstBucket || bucketStart > rangeEnd) {
                continue;
            }
            final int index = (int) ((bucketStart - firstBucket) / bucketWidthMs);
            if (index >= 0 && index < counts.length) {
                counts[index] += parseCount(values.get(1));
            }
        }

        notifyDataHandler(counts);
        return counts;
    }

    /**
     * The extent of a bucketed result: the first bucket's start, and the last bucket's start plus a
     * width.
     *
     * <p>Separate from {@link #processBuckets} because the two answer different queries. The bars
     * are bounded below at the visible range and so can never see data earlier than what is already
     * shown; the extent has to come from an unbounded read, which is the whole point of "Show All".</p>
     *
     * <p>Returns the bracket rather than the exact first and last event times: a bucket stands for
     * everything within its width, so the last event lies somewhere inside the final bucket and the
     * honest answer is its end. Widening rather than narrowing is the safe direction — "Show All"
     * showing a little dead air beats it cutting data off.</p>
     *
     * <p>Assumes rows sorted by bucket ascending, which the extent query asks for, but does not rely
     * on it: it takes the min and max rather than the first and last row.</p>
     *
     * @return {@code {startInclusive, endExclusive}}, or {@code null} if the result holds no
     *         parseable bucket — an empty store, or a failed read
     */
    public static long[] extentOf(final TableResult tableResult, final long bucketWidthMs) {
        if (tableResult == null || tableResult.getRows() == null || bucketWidthMs <= 0) {
            return null;
        }

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (final Row row : tableResult.getRows()) {
            final List<String> values = row.getValues();
            if (values == null || values.isEmpty()) {
                continue;
            }
            final Long bucketStart = parseTime(values.get(0));
            if (bucketStart == null) {
                continue;
            }
            if (bucketStart < min) {
                min = bucketStart;
            }
            if (bucketStart > max) {
                max = bucketStart;
            }
        }

        return min <= max
                ? new long[]{min, max + bucketWidthMs}
                : null;
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
}
