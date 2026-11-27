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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ScorerParameters;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/// The yield scorer: output records per unit of input, scored by how close the ratio comes to the
/// expected one, as `min(actual, expected) / max(actual, expected)`, so too many records lose marks just as
/// too few do. A record is a child element of the output's root (or as the record boundary says), which
/// is what a record is in both `records:2` and `event-logging:3` documents. It applies to a step that
/// produced XML; when the basis is input records and the input is not XML, there is nothing to count
/// against, so the scorer does not apply.
public final class YieldScorer implements Scorer {

    private static final ElementId YIELD = new ElementId("Yield");

    @Override
    public Check type() {
        return Check.YIELD;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        final YieldParameters yield = (YieldParameters) parameters;
        final String output = step.result().output();
        if (output == null || !isXml(output)) {
            return Optional.empty();
        }
        // Count by the record boundary at both ends, otherwise the two disagree: a parser's output of one
        // map holding twelve items is twelve records to the stage, but would be one here.
        final int records = Math.max(0, Records.parsed(output, step.boundary()));
        // The basis describes raw input. A step whose input is already records (a transform after the
        // parser) is judged as one record out per record in, whatever the document's basis; only a basis
        // of records applies the document's expected ratio there (e.g. for a transform that filters out
        // some records).
        final int parsed = isXml(step.input())
                ? Records.parsed(step.input(), step.boundary())
                : -1;
        final boolean recordsIn = parsed >= 0;
        final double units = switch (yield.getBasis()) {
            case RECORDS -> recordsIn
                    ? parsed
                    : -1;
            case LINES -> recordsIn
                    ? parsed
                    : step.input().lines().filter(line -> !line.isBlank()).count();
            case BYTES -> recordsIn
                    ? parsed
                    : step.input().length();
        };
        if (units < 0) {
            return Optional.empty();
        }
        final double actual = units == 0
                ? 0.0
                : records / units;
        final double expected = recordsIn && yield.getBasis() != YieldBasis.RECORDS
                ? 1.0
                : yield.getExpectedRatio();
        final double value = actual == expected
                ? 1.0
                : Math.min(actual, expected) / Math.max(actual, expected);
        final List<StoredError> diagnostics = value < 1.0
                ? List.of(new StoredError(Severity.WARNING, null, YIELD,
                records + " record(s) from " + (long) units + " input " + unitName(recordsIn
                        ? YieldBasis.RECORDS
                        : yield.getBasis())
                + " is " + actual + " per unit against an expected " + expected))
                : List.of();
        return Optional.of(new Score(type(), value, diagnostics));
    }

    /// Whether text looks like XML. A leading `<` is not enough: syslog's priority prefix, e.g. `<38>`,
    /// begins a text line, and reading it as XML would count a syslog stream as having no records. Text
    /// that looks like XML but holds no document (fragments, or a line beginning with a bracketed word)
    /// is treated as text too, which the caller discovers when counting.
    private static boolean isXml(final String text) {
        return text != null && RecordSkeleton.isMarkup(text);
    }

    private static String unitName(final YieldBasis basis) {
        return switch (basis) {
            case RECORDS -> "record(s)";
            case LINES -> "line(s)";
            case BYTES -> "byte(s)";
        };
    }
}
