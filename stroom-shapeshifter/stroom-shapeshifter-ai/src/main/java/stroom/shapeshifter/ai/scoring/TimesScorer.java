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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.learning.TargetSchema;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.TimesParameters;
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

/// Whether an event is dated anywhere near the stream it came from (Track 1 task 3, fix 3).
///
/// This is the check for the fault nobody else catches. A timestamp with no year — `Oct 19 09:06:02`, the
/// shape syslog writes — read without one lands in whatever year the translation guessed, and an event
/// dated three years before its stream still validates against the schema, still carries real content and
/// still obeys every business rule. It scores 1.0 everywhere while being wrong about when it happened.
///
/// **Advice by default** (stage 0b group 4): it shows beside a draft and goes back to the model, but does
/// not refuse a candidate until it has run on real feeds without crying wolf. A Learning Flow may make it
/// a gate, and *Strict* does.
///
/// What the model is told is the whole point of the check, so the feedback names the record, the time it
/// wrote, how far out it is and what to do about it — which is to use `stroom:format-date`, now that the
/// Smart Pipelines list allows it.
public final class TimesScorer implements Scorer {

    private static final ElementId TIMES = new ElementId("Times");
    /// Where an event's time is, in the schema the module writes.
    private static final String TIME_CREATED = "EventTime/TimeCreated";
    /// The same, as a string rather than the element: what the translation wrote, not its markup.
    private static final String TIME_CREATED_TEXT = "string(" + TIME_CREATED + ")";
    /// How many records to name before the feedback stops listing them: a translation that gets the year
    /// wrong gets it wrong for every record, and twenty copies of one fault teach the model nothing more
    /// than three do.
    private static final int SHOWN = 3;

    @Override
    public Check type() {
        return Check.TIMES;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        final Long receivedMs = step.facts().receivedMs();
        if (receivedMs == null || step.parser()) {
            // A parser's records are the input's shape, not the target's, and a run that does not know
            // when the stream arrived has nothing to measure against
            return Optional.empty();
        }
        final Optional<OutputRecords> parsed = OutputRecords.ofTransformed(step);
        if (parsed.isEmpty() || parsed.get().records().isEmpty()) {
            return Optional.empty();
        }
        final TimesParameters window = parameters instanceof TimesParameters given
                ? given
                : new TimesParameters(null, null);
        final OutputRecords output = parsed.get();
        final List<XdmNode> records = output.records();
        final Instant received = Instant.ofEpochMilli(receivedMs);
        final Instant earliest = received.minus(Duration.ofDays(window.getBeforeDays()));
        final Instant latest = received.plus(Duration.ofDays(window.getAfterDays()));

        final List<StoredError> diagnostics = new ArrayList<>();
        int judged = 0;
        int plausible = 0;
        for (int i = 0; i < records.size(); i++) {
            final String written = output.evaluate(records.get(i), TIME_CREATED_TEXT).toString().trim();
            if (written.isEmpty()) {
                // An event with no time at all is the schema's business, not this check's
                continue;
            }
            judged++;
            final Instant time = read(written);
            if (time == null) {
                if (diagnostics.size() < SHOWN) {
                    diagnostics.add(new StoredError(Severity.WARNING, null, TIMES,
                            "Record " + (i + 1) + "'s " + TIME_CREATED + " is \"" + written
                            + "\", which is not an ISO 8601 instant."));
                }
            } else if (time.isBefore(earliest) || time.isAfter(latest)) {
                if (diagnostics.size() < SHOWN) {
                    diagnostics.add(new StoredError(Severity.WARNING, null, TIMES, outOfWindow(i, written,
                            time, received)));
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

    /// The sentence the model is given: when the event says it happened, how far that is from the stream,
    /// and the way to put it right.
    private static String outOfWindow(final int index,
                                      final String written,
                                      final Instant time,
                                      final Instant received) {
        final boolean before = time.isBefore(received);
        final long days = Math.abs(Duration.between(time, received).toDays());
        return "Record " + (index + 1) + "'s " + TIME_CREATED + " is " + written + ", "
               + gap(days) + (before
                       ? " before"
                       : " after")
               + " the stream was received (" + DateUtil.createNormalDateTimeString(received.toEpochMilli())
               + "). A timestamp that does not carry its year must take it from the stream, with "
               + "stroom:format-date(value, pattern), rather than being given one.";
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
    /// @return When that is, or null where it is not an instant this can read.
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
