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

package stroom.pathways.impl;

import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.util.shared.time.SimpleDuration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Puts a span's children into an order that is the same every time the same work happens, so that the
 * key built from their names identifies the route rather than the run.
 *
 * <p>Children that ran one after another keep their order: the program decided it, so a change in it is
 * a change in behaviour and the model should say so. Children that ran at the same time do not: which
 * of them started first is whatever the scheduler did that second, and treating it as a route would
 * make every run a new one.
 *
 * <p>So the children are gathered into groups of spans that overlap in time — allowing
 * {@code tolerance} for spans that are close enough to be concurrent without quite touching, which is
 * usual for short calls made from several threads at once. Groups cannot overlap each other, so they
 * are ordered by when they happened; within a group the order is by name.
 *
 * <p>This is a function over the whole list rather than a comparison between two spans, and that is
 * what makes it safe. Asking "is this one before that one" with a tolerance gives answers that
 * contradict each other — a before b, b before c, c before a — so the result depends on the order the
 * spans arrived in and {@code List.sort} may refuse it outright. Deciding the groups first, and only
 * then ordering, cannot contradict itself.
 */
public class CanonicalSpanOrder {

    /**
     * Used where a document does not say. Short calls made from several threads at once land tens of
     * microseconds apart, so nothing smaller than this gathers them; and children of a span that are
     * genuinely separate steps are normally further apart than this.
     */
    private static final long DEFAULT_TOLERANCE_NANOS = 1_000_000L;

    private final long toleranceNanos;

    public CanonicalSpanOrder(final SimpleDuration simpleDuration) {
        this.toleranceNanos = simpleDuration == null
                ? DEFAULT_TOLERANCE_NANOS
                : toNanos(simpleDuration);
    }

    public CanonicalSpanOrder(final NanoDuration tolerance) {
        this.toleranceNanos = tolerance == null
                ? DEFAULT_TOLERANCE_NANOS
                : tolerance.getNanos();
    }

    public List<Span> sort(final List<Span> spans) {
        if (spans == null || spans.size() < 2) {
            return spans == null
                    ? List.of()
                    : new ArrayList<>(spans);
        }

        final List<Timed> timed = new ArrayList<>(spans.size());
        for (final Span span : spans) {
            timed.add(Timed.of(span));
        }
        // Start from a fixed order so the grouping below sees the same input for the same spans.
        timed.sort(Comparator.comparingLong((Timed t) -> t.start)
                .thenComparingLong(t -> t.end)
                .thenComparing(t -> t.span.getName()));

        final List<Span> ordered = new ArrayList<>(spans.size());
        int groupStart = 0;
        long groupEnd = timed.getFirst().end;
        for (int i = 1; i <= timed.size(); i++) {
            // A span joins the group while it begins before the group has finished, give or take the
            // tolerance. The group's end moves out to cover it, so a chain of overlapping spans is one
            // group even where the first and last do not touch.
            if (i < timed.size() && timed.get(i).start - toleranceNanos <= groupEnd) {
                groupEnd = Math.max(groupEnd, timed.get(i).end);
            } else {
                addGroup(timed.subList(groupStart, i), ordered);
                if (i < timed.size()) {
                    groupStart = i;
                    groupEnd = timed.get(i).end;
                }
            }
        }
        return ordered;
    }

    // Within a group nothing about the timing is worth keeping, so the order is by name. Spans sharing
    // a name are already in start order from the sort above, which keeps this repeatable.
    private static void addGroup(final List<Timed> group, final List<Span> ordered) {
        final List<Timed> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparing(t -> t.span.getName()));
        sorted.forEach(t -> ordered.add(t.span));
    }

    private static long toNanos(final SimpleDuration simpleDuration) {
        return switch (simpleDuration.getTimeUnit()) {
            case NANOSECONDS -> simpleDuration.getTime();
            case MILLISECONDS -> simpleDuration.getTime() * 1_000_000L;
            case SECONDS -> simpleDuration.getTime() * 1_000_000_000L;
            case MINUTES -> simpleDuration.getTime() * 60L * 1_000_000_000L;
            case HOURS -> simpleDuration.getTime() * 60L * 60L * 1_000_000_000L;
            default -> throw new IllegalArgumentException(
                    "Unable to convert duration unit " + simpleDuration.getTimeUnit());
        };
    }

    // Epoch nanos rather than NanoTime, because the grouping is arithmetic and a long holds a
    // timestamp until the year 2262.
    private record Timed(Span span, long start, long end) {

        private static Timed of(final Span span) {
            final long start = NanoTime.fromString(span.getStartTimeUnixNano()).toEpochNanos();
            // A span with no end has not finished, so it covers no more than its start.
            final long end = span.getEndTimeUnixNano() == null
                    ? start
                    : Math.max(start, NanoTime.fromString(span.getEndTimeUnixNano()).toEpochNanos());
            return new Timed(span, start, end);
        }
    }
}
