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
import stroom.learningflow.transformation.LearnedStylesheetCheck;
import stroom.learningflow.transformation.StreamFacts;
import stroom.learningflow.transformation.XsltStep;
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

/// The literals scorer, which checks whether a translation has values from this stream hard-coded into
/// its stylesheet.
///
/// A translation is learned from one stream and serves every stream after it, so a value copied from the
/// stream it was learned on is wrong for all the others, and wrong silently, because the translation
/// still compiles, still validates and still produces real content. The year is the clearest case: a
/// syslog date with no year, and `2023` written into the stylesheet.
///
/// It looks for three kinds of value, each of which has a correct alternative the translation should use
/// instead:
///
/// - **A year** (or a whole date), which should come from `stroom:format-date` and the stream's own time.
/// - **A time zone**, which should come from `stroom:meta('ResolvedTimeZone')`.
/// - **One of this stream's header values** (e.g. its feed, environment or system), which should come from
///   `stroom:feed-name()` or `stroom:meta`.
///
/// The check presets make it advisory, except under Strict where it is a gate, because a value that
/// happens to match a header is not certainly a fault, and the check should prove itself on real feeds
/// before it is allowed to refuse candidates.
public final class LiteralsScorer implements Scorer {

    private static final ElementId LITERALS = new ElementId("Literals");

    /// A year on its own, in the range a log might contain. A date pattern such as `yyyy-MM-dd` is not
    /// matched, because it is not a number.
    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");
    /// A date or datetime beginning with a year, e.g. `2023-10-19T09:06:02.000Z`.
    private static final Pattern DATED = Pattern.compile("((19|20)\\d{2})-\\d{2}-\\d{2}.*");

    /// The headers whose values a translation should read rather than hard-code: the ones that describe
    /// the stream rather than its content, and so differ between the streams one translation serves.
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

    /// Checks one literal value from the stylesheet.
    ///
    /// @param literal A value the stylesheet writes out literally.
    /// @param facts   Facts about this stream, whose own values must not be hard-coded.
    /// @return A message saying what is wrong with it, or null where there is nothing wrong.
    private static String said(final String literal, final StreamFacts facts) {
        final String trimmed = literal.strip();
        if (trimmed.length() < SHORTEST) {
            return null;
        }
        for (final String header : HEADERS) {
            // Read through the facts rather than the attribute map, because a stream's feed and type are
            // its own fields, not headers, and they are the values most likely to be copied.
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
    /// @return Whether it is a region-based zone id such as `Europe/London`. `UTC` and offsets are not
    ///         matched, because a translation writing an ISO instant correctly ends it with `Z`, and
    ///         refusing that would refuse the right answer.
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
