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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.stage.RecordSkeleton;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;

/**
 * Yield (design §8.4): output records per unit of input, scored as how close the ratio comes to the
 * expected one — {@code min(actual, expected) / max(actual, expected)}, so too many records lose marks
 * as too few do. A record is a child element of the output's root, which is what both {@code records:2}
 * and {@code event-logging:3} documents make of one. Applies to a step that produced XML; when the basis
 * is input records and the input is not XML, there is nothing to count against, and the scorer does
 * not apply.
 */
public final class YieldScorer implements Scorer {

    private static final ElementId YIELD = new ElementId("Yield");

    @Override
    public ScorerType type() {
        return ScorerType.YIELD;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters, final Attempted step) {
        final YieldParameters yield = (YieldParameters) parameters;
        final String output = step.result().output();
        if (output == null || !isXml(output)) {
            return Optional.empty();
        }
        // Counted by the boundary at both ends, or the two disagree: a parser's output of one map holding
        // twelve items is twelve records to the stage and would be one here.
        final int records = Math.max(0, Records.parsed(output, step.boundary()));
        // The basis describes raw input. A step whose input is already records — a transform after the
        // parser — is judged record for record, one out per one in, whatever the document's basis; only a
        // basis of records applies the document's ratio there (a transform that filters, scenario 7).
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

    /**
     * Whether text looks like markup. A leading {@code <} is not enough: syslog's priority prefix, {@code <38>},
     * begins a text line, and reading it as XML counted a syslog stream as no records. Text that looks like
     * markup but holds no document — fragments, or a line beginning with a bracketed word — is text too, which
     * the caller settles by counting.
     */
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
