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
import stroom.shapeshifter.ai.transformation.LearnedStylesheetCheck;
import stroom.shapeshifter.ai.transformation.StreamFacts;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/// Whether a translation has this stream's own data written into it (Track 1 task 3).
///
/// A translation is learned from one stream and serves every stream after it, so a value copied out of
/// the stream it was learned on is wrong for all the rest — and wrong quietly, because the translation
/// still compiles, still validates and still carries real content. The year is the clearest case and the
/// one the dev instance showed: a syslog date with no year, and a `2023` written into the stylesheet.
///
/// Three kinds are looked for, each of which has a right answer the translation should use instead:
///
/// - **A year**, which belongs to `stroom:format-date` and the stream's own time.
/// - **A time zone**, which belongs to `stroom:meta('ResolvedTimeZone')` (decision N6).
/// - **One of this stream's header values** — its feed, environment or system — which belongs to
///   `stroom:feed-name()` or `stroom:meta`.
///
/// **Advice first, then a gate** (stage 0b group 4): a value that happens to match a header is not
/// certainly a fault, and this should run on real feeds before it refuses anything.
public final class LiteralsScorer implements Scorer {

    private static final ElementId LITERALS = new ElementId("Literals");

    /// A year on its own, in the range a log might carry. A four-digit number in a date pattern such as
    /// `yyyy-MM-dd` is the pattern, not a year, and is not matched because the pattern is not a number.
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    /// A date or datetime beginning with a year, e.g. `2023-10-19T09:06:02.000Z`.
    private static final Pattern DATED = Pattern.compile("((19|20)\\d{2})-\\d{2}-\\d{2}.*");

    /// The headers whose values a translation should read rather than write: the ones that describe the
    /// stream rather than its content, and so differ between the streams one translation serves.
    private static final Set<String> HEADERS = Set.of("Feed", "Environment", "System", "Type",
            "StreamType", "TimeZone");

    /// A value shorter than this is too likely to match something by accident to be worth reporting.
    private static final int SHORTEST = 3;

    @Override
    public Check type() {
        return Check.LITERALS;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        if (step.configuration() == null || !XsltStep.ELEMENT_TYPE.equals(step.elementType())) {
            return Optional.empty();
        }
        final List<String> literals = LearnedStylesheetCheck.literals(step.configuration());
        if (literals.isEmpty()) {
            return Optional.empty();
        }
        final Set<String> found = new LinkedHashSet<>();
        for (final String literal : literals) {
            final String said = said(literal, step.facts());
            if (said != null) {
                found.add(said);
            }
        }
        if (found.isEmpty()) {
            return Optional.of(new Score(type(), 1.0, List.of()));
        }
        final List<StoredError> diagnostics = new ArrayList<>();
        for (final String message : found) {
            diagnostics.add(new StoredError(Severity.WARNING, null, LITERALS, message));
        }
        return Optional.of(new Score(type(), 0.0, diagnostics));
    }

    /// @param literal A value the stylesheet writes out as itself.
    /// @param facts   This stream, whose own values are the ones that must not be written in.
    /// @return What is wrong with it, or null where there is nothing wrong.
    private static String said(final String literal, final StreamFacts facts) {
        final String trimmed = literal.strip();
        if (trimmed.length() < SHORTEST) {
            return null;
        }
        for (final String header : HEADERS) {
            // Through the facts rather than the attribute map: a stream's feed and type are its own
            // fields, not headers, and they are the values most likely to be copied
            final String value = facts.attribute(header);
            if (value != null && value.length() >= SHORTEST && trimmed.equalsIgnoreCase(value)) {
                return "The translation writes \"" + trimmed + "\" into its output, which is this stream's "
                       + header + ". The next stream's will differ: read it with "
                       + ("Feed".equals(header)
                               ? "stroom:feed-name()."
                               : "stroom:meta('" + header + "').");
            }
        }
        if (YEAR.matcher(trimmed).matches()) {
            return "The translation writes the year " + trimmed + " into its output. A stream received in "
                   + "another year would be dated wrongly: take the year from the stream with "
                   + "stroom:format-date(value, pattern).";
        }
        if (DATED.matcher(trimmed).matches()) {
            return "The translation writes the date " + trimmed + " into its output, so every event it "
                   + "makes carries that date: take it from the record with stroom:format-date.";
        }
        if (isZone(trimmed)) {
            return "The translation writes the time zone \"" + trimmed + "\" into its output. A feed's "
                   + "zone is set on its pipeline and may change: read it with "
                   + "stroom:meta('ResolvedTimeZone').";
        }
        return null;
    }

    /// @param value A literal.
    /// @return Whether it names a time zone. `UTC` and an offset are left out: a translation writing an
    ///         ISO instant properly ends it with `Z`, and refusing that would refuse the right answer.
    private static boolean isZone(final String value) {
        if (!value.contains("/")) {
            return false;
        }
        try {
            ZoneId.of(value);
            return true;
        } catch (final DateTimeException e) {
            return false;
        }
    }
}
