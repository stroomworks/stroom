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

package stroom.shapeshifter.shared;

import stroom.docref.HasDisplayValue;

/// The checks that judge a step, as a flow names them (A37; design 01 §10.2): the closed list a step's
/// `checks` may draw from, and the source of every `<check>-short` outcome. Seven wrap the scorers of
/// §8.4; `WHOLENESS`, `PRESERVATION` and `FIDELITY` are the checks of §10.1 that have nothing to weight
/// or threshold. A new check is new code.
public enum Check implements HasDisplayValue {
    COVERAGE("coverage", ScorerType.INPUT_COVERAGE),
    YIELD("yield", ScorerType.YIELD),
    WHOLENESS("wholeness", null),
    CONFORMANCE("conformance", ScorerType.SCHEMA_CONFORMANCE),
    QUALITY("quality", ScorerType.EXTRACTION_QUALITY),
    RULES("rules", ScorerType.BUSINESS_RULES),
    ERRORS("errors", ScorerType.ERROR_LOAD),
    CLASSIFICATION("classification", ScorerType.EVENT_CLASSIFICATION),
    PRESERVATION("preservation", null),
    FIDELITY("fidelity", null);

    private final String displayValue;
    private final ScorerType scorer;

    Check(final String displayValue, final ScorerType scorer) {
        this.displayValue = displayValue;
        this.scorer = scorer;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// The scorer this check wraps, or null for a check of §10.1 that is not a document scorer.
    public ScorerType getScorer() {
        return scorer;
    }

    /// The outcome a candidate has when this check falls short.
    public StepOutcome shortfall() {
        return StepOutcome.valueOf(name() + "_SHORT");
    }

    /// The check that wraps a scorer, or null for the compile gate, which is an outcome of its own.
    public static Check of(final ScorerType scorer) {
        for (final Check check : values()) {
            if (check.scorer == scorer) {
                return check;
            }
        }
        return null;
    }

    /// @return The check written as its word, or null for anything else.
    public static Check parse(final String word) {
        for (final Check check : values()) {
            if (check.displayValue.equalsIgnoreCase(word)) {
                return check;
            }
        }
        return null;
    }
}
