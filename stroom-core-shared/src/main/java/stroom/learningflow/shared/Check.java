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

package stroom.learningflow.shared;

import stroom.docref.HasDisplayValue;

/// The checks that judge a candidate (A37; design 01 §10.2; the one model of checks,
/// `docs/learning-flow-checks-model-design.md`): each is Off, Advice or Gate on the Learning Flow
/// ([CheckSetting]), and each step check is the source of a `<check>-short` outcome. Most wrap a scorer of
/// §8.4; `WHOLENESS`, `PRESERVATION` and `FIDELITY` are judged by the conversation itself, and
/// `REGRESSION` (the regression corpus) when a relearn or Improve is promoted. `COMPILE` is always a gate.
/// A new check is new code.
public enum Check implements HasDisplayValue {
    COMPILE("compile", "Compile", true, true, 1.0, false),
    COVERAGE("coverage", "Input coverage", true, true, 0.9, false),
    COMPLETENESS("completeness", "Completeness", true, true, null, false),
    YIELD("yield", "Yield", true, true, 0.5, false),
    WHOLENESS("wholeness", "Whole records", false, true, 0.9, false),
    CONFORMANCE("conformance", "Schema conformance", true, true, 1.0, true),
    QUALITY("quality", "Extraction quality", true, true, 0.7, true),
    RULES("rules", "Business rules", true, true, 1.0, true),
    TIMES("times", "Event times are plausible", true, true, 1.0, true),
    LITERALS("literals", "No data written into the translation", true, true, 1.0, true),
    PERMITTED("permitted", "Uses only what it may", true, true, null, false),
    PRESERVATION("preservation", "Preservation", false, true, null, false),
    FIDELITY("fidelity", "Fidelity", false, true, null, false),
    REGRESSION("regression", "Regression corpus", false, true, null, false);

    private final String displayValue;
    /// The check's name as a message or a list shows it, e.g. "Input coverage": the name its scorer had
    /// before the one model of checks, so that what people read is unchanged.
    private final String name;
    private final boolean scored;
    private final boolean built;
    private final Double defaultThreshold;
    private final boolean judgesRecords;

    Check(final String displayValue,
          final String name,
          final boolean scored,
          final boolean built,
          final Double defaultThreshold,
          final boolean judgesRecords) {
        this.displayValue = displayValue;
        this.name = name;
        this.scored = scored;
        this.built = built;
        this.defaultThreshold = defaultThreshold;
        this.judgesRecords = judgesRecords;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return The check's name as a message or a list shows it, e.g. "Input coverage". [#getDisplayValue]
    ///         is the lower-case word a sentence uses, e.g. "the coverage check is off".
    public String getName() {
        return name;
    }

    /// @return Whether a scorer measures this check, so that it takes parameters and a scorer must be
    ///         installed for it. The rest — whole records, keeping what the targets need, matching the
    ///         agreed targets, the regression corpus — are judged by the conversation or by promotion.
    public boolean isScored() {
        return scored;
    }

    /// @return The threshold a new setting of this check starts with, or null for a check that only passes
    ///         or fails, which needs a value of 1.
    public Double getDefaultThreshold() {
        return defaultThreshold;
    }

    /// @return Whether this check has a threshold a person may set; one that only passes or fails has not.
    public boolean hasThreshold() {
        return defaultThreshold != null && this != COMPILE;
    }

    /// @return Whether this check judges served records one at a time, so that as a gate it is part of the
    ///         serving record bar.
    public boolean judgesRecords() {
        return judgesRecords;
    }

    /// @return Whether a check of this kind is built, so that a setting may turn it on. One declared before
    ///         its code exists can only be Off.
    public boolean isBuilt() {
        return built;
    }

    /// @return Whether this check judges a step of a flow, and so has an outcome a step's rule can name.
    ///         The compile gate's outcome is its own, and the regression corpus judges promotion, not a step.
    public boolean isStepCheck() {
        return this != COMPILE && this != REGRESSION;
    }

    /// The outcome a candidate has when this check falls short: the compile gate's is `COMPILE_FAILED`.
    ///
    /// @return The outcome, or null for the regression corpus, which judges promotion, not a step.
    public StepOutcome shortfall() {
        if (this == COMPILE) {
            return StepOutcome.COMPILE_FAILED;
        }
        if (this == REGRESSION) {
            return null;
        }
        return StepOutcome.valueOf(name() + "_SHORT");
    }

    /// The check whose shortfall is this outcome, for reading a step's rule back: a rule that fires on
    /// `coverage-short` is about the coverage check.
    ///
    /// @param outcome An outcome, or null.
    /// @return The check it is the shortfall of, or null where no check raises it.
    public static Check ofShortfall(final StepOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        for (final Check check : values()) {
            if (outcome == check.shortfall()) {
                return check;
            }
        }
        return null;
    }

    /// @param word A check's word, e.g. `yield`.
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
