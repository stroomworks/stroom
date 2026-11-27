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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ScorerParameters;
import stroom.learningflow.shared.TimesParameters;
import stroom.util.date.DateUtil;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.s9api.XdmNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The times scorer, which checks whether each event is dated anywhere near the time its stream was
/// received.
///
/// It catches a fault no other check does. A timestamp with no year (e.g. `Oct 19 09:06:02`, as syslog
/// writes it) lands in whatever year the translation guessed, and an event dated three years before its
/// stream still validates against the schema, still has real content and still obeys every business
/// rule. It would score 1.0 on every other check while being wrong about when it happened.
///
/// **It is advice by default**: it is shown beside a draft and fed back to the model, but does not refuse
/// a candidate until it has been shown on real feeds not to give false failures. A Learning Flow may make
/// it a gate, and the *Strict* preset does.
///
/// The feedback to the model is what makes the check useful, so it names the record, the time it wrote,
/// how far out it is, and how to fix it, which is to use `stroom:format-date`.
public final class TimesScorer implements Scorer {

    private static final ElementId TIMES = new ElementId("Times");
    /// Where an event's time is in event-logging, which is the field judged where the Learning Flow names
    /// no other.
    private static final String TIME_CREATED = "EventTime/TimeCreated";
    /// The maximum number of records the feedback names. A translation that gets the year wrong gets it
    /// wrong for every record, and twenty copies of one fault teach the model nothing more than three do.
    private static final int SHOWN = 3;

    @Override
    public Check type() {
        return Check.TIMES;
    }

    @Override
    public void validate(final ScorerParameters parameters) {
        if (parameters instanceof final TimesParameters times && times.getTimeField() != null) {
            OutputRecords.check(times.getTimeField());
        }
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        final Long receivedMs = step.facts().receivedMs();
        if (receivedMs == null || step.parser()) {
            // A parser's records follow the input's structure, not the target's, and a run that does not
            // know when the stream arrived has nothing to measure against.
            return Optional.empty();
        }
        final Optional<OutputRecords> parsed = OutputRecords.ofTransformed(step);
        if (parsed.isEmpty() || parsed.get().records().isEmpty()) {
            return Optional.empty();
        }
        final TimesParameters window = parameters instanceof TimesParameters given
                ? given
                : new TimesParameters(null, null);
        final String field = timeField(window, target);
        if (field == null) {
            // Another schema keeps its time elsewhere, and the Learning Flow has not said where.
            return Optional.empty();
        }
        final String fieldText = "string(" + field + ")";
        final OutputRecords output = parsed.get();
        final List<XdmNode> records = output.records();
        final Instant received = Instant.ofEpochMilli(receivedMs);
        final Instant earliest = received.minus(Duration.ofDays(window.getBeforeDays()));
        final Instant latest = received.plus(Duration.ofDays(window.getAfterDays()));

        final List<StoredError> diagnostics = new ArrayList<>();
        int judged = 0;
        int plausible = 0;
        for (int i = 0; i < records.size(); i++) {
            final String written = output.evaluate(records.get(i), fieldText).toString().trim();
            if (written.isEmpty()) {
                // An event with no time at all is for the schema check to judge, not this one.
                continue;
            }
            judged++;
            final Instant time = read(written);
            if (time == null) {
                if (diagnostics.size() < SHOWN) {
                    diagnostics.add(new StoredError(Severity.WARNING, null, TIMES,
                            "Record " + (i + 1) + "'s " + field + " is \"" + written
                            + "\", which is not an ISO 8601 instant."));
                }
            } else if (time.isBefore(earliest) || time.isAfter(latest)) {
                if (diagnostics.size() < SHOWN) {
                    diagnostics.add(new StoredError(Severity.WARNING, null, TIMES, outOfWindow(i, field,
                            written, time, received)));
                }
            } else {
                plausible++;
            }
        }
        if (judged == 0) {
            return Optional.empty();
        }
        return Optional.of(new Score(type(), (double) plausible / judged, diagnostics));
    }

    /// Builds the message the model is given: when the event says it happened, how far that is from when
    /// the stream was received, and how to fix it.
    private static String outOfWindow(final int index,
                                      final String field,
                                      final String written,
                                      final Instant time,
                                      final Instant received) {
        final boolean before = time.isBefore(received);
        final long days = Math.abs(Duration.between(time, received).toDays());
        return "Record " + (index + 1) + "'s " + field + " is " + written + ", "
               + gap(days) + (before
                       ? " before"
                       : " after")
               + " the stream was received (" + DateUtil.createNormalDateTimeString(received.toEpochMilli())
               + "). A timestamp that does not carry its year must take it from the stream, with "
               + "stroom:format-date(value, pattern), rather than being given one.";
    }

    /// Chooses which time field is judged: the one the Learning Flow names, otherwise event-logging's where
    /// the target is event-logging or there is no target.
    ///
    /// @return An XPath from a record, or null where there is none to judge.
    private static String timeField(final TimesParameters window, final TargetSchema target) {
        if (window.getTimeField() != null) {
            return window.getTimeField();
        }
        return TargetSchema.isEventLoggingOrNone(target)
                ? TIME_CREATED
                : null;
    }

    private static String gap(final long days) {
        if (days >= 365) {
            final long years = days / 365;
            return years == 1
                    ? "about a year"
                    : "about " + years + " years";
        }
        return days == 1
                ? "a day"
                : days + " days";
    }

    /// @param written An event's time as the translation wrote it.
    /// @return The instant, or null where it cannot be parsed as one.
    private static Instant read(final String written) {
        try {
            return Instant.ofEpochMilli(DateUtil.parseNormalDateTimeString(written));
        } catch (final RuntimeException e) {
            try {
                return Instant.parse(written);
            } catch (final RuntimeException ignored) {
                return null;
            }
        }
    }
}
