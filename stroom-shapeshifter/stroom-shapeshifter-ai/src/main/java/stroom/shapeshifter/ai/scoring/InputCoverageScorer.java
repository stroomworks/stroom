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

import stroom.shapeshifter.ai.extraction.InputCoverage;
import stroom.shapeshifter.shared.ScorerParameters;
import stroom.shapeshifter.shared.ScorerType;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Input coverage (rulings A11, A36) as a scorer: the share of the input's characters the split
 * consumed. Lines are counted and named in the diagnostic, so the model is told which it missed, but
 * do not set the score: on a sample of seven lines a header line is a seventh by lines and a
 * sixteenth by characters, and the live runs (design 02 §6.3) found that difference deciding
 * promotions. A dropped header costs what it is worth; a dropped record costs its size. Applies only
 * to a step that reports record ranges — an extraction step — and only when it produced output; a step
 * that did not run has nothing to cover and is the compile gate's to fail.
 */
public final class InputCoverageScorer implements Scorer {

    private static final ElementId COVERAGE = new ElementId("InputCoverage");
    private static final int UNCOVERED_LINES_SHOWN = 5;

    @Override
    public ScorerType type() {
        return ScorerType.INPUT_COVERAGE;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters, final Attempted step) {
        if (step.result().output() == null || step.result().recordRanges().isEmpty()) {
            return Optional.empty();
        }
        final InputCoverage coverage = InputCoverage.measure(step.input(), step.result().recordRanges());
        final double value = coverage.charRatio();
        final List<StoredError> diagnostics = value < 1.0 || coverage.lineRatio() < 1.0
                ? List.of(new StoredError(Severity.WARNING, null, COVERAGE,
                "The split consumed " + coverage.linesCovered() + " of " + coverage.linesTotal()
                + " lines and " + coverage.charsCovered() + " of " + coverage.charsTotal()
                + " characters; the rest of the input was discarded" + uncovered(coverage)))
                : List.of();
        return Optional.of(new Score(type(), value, diagnostics));
    }

    /**
     * The first few lines nothing consumed, so the model can see what it missed rather than only how much.
     */
    private static String uncovered(final InputCoverage coverage) {
        final List<Integer> lines = coverage.linesUncovered();
        if (lines.isEmpty()) {
            return "";
        }
        final List<Integer> shown = lines.subList(0, Math.min(lines.size(), UNCOVERED_LINES_SHOWN));
        return ". Lines not consumed: " + shown.stream().map(String::valueOf).collect(Collectors.joining(", "))
               + (lines.size() > shown.size()
                ? " and " + (lines.size() - shown.size()) + " more"
                : "");
    }
}
