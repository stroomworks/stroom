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

/// The checks that judge a candidate translation. Each is set to Off, Advice or Gate on the Learning Flow
/// document (see [CheckSetting]), and each check that judges a step produces its own `<check>-short` outcome
/// when a candidate falls short of it. Most are measured by a scorer. `WHOLENESS`, `PRESERVATION` and
/// `FIDELITY` are judged by the learning conversation itself, and `REGRESSION` (the regression corpus) when a
/// relearning or an improvement is promoted over an existing binding. `COMPILE` is always a gate. Adding a check
/// means writing new code.
public enum Check implements HasDisplayValue {
    COMPILE("compile", "Compile", true, 1.0, false),
    COVERAGE("coverage", "Input coverage", true, 0.9, false),
    COMPLETENESS("completeness", "Completeness", true, null, false),
    YIELD("yield", "Yield", true, 0.5, false),
    WHOLENESS("wholeness", "Whole records", false, 0.9, false),
    CONFORMANCE("conformance", "Schema conformance", true, 1.0, true),
    QUALITY("quality", "Extraction quality", true, 0.7, true),
    RULES("rules", "Business rules", true, 1.0, true),
    TIMES("times", "Event times are plausible", true, 1.0, true),
    LITERALS("literals", "No data written into the translation", true, 1.0, true),
    PERMITTED("permitted", "Uses only what it may", true, null, false),
    PRESERVATION("preservation", "Preservation", false, null, false),
    FIDELITY("fidelity", "Fidelity", false, null, false),
    REGRESSION("regression", "Regression corpus", false, null, false);

    private final String displayValue;
    /// The check's name as a message or a list shows it, e.g. "Input coverage".
    private final String name;
    private final boolean scored;
    private final Double defaultThreshold;
    private final boolean judgesRecords;

    Check(final String displayValue,
          final String name,
          final boolean scored,
          final Double defaultThreshold,
          final boolean judgesRecords) {
        this.displayValue = displayValue;
        this.name = name;
        this.scored = scored;
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

    /// @return Whether a scorer measures this check, so that it may take parameters and a scorer must be
    ///         installed for it. The rest (whole records, keeping what the targets need, matching the
    ///         agreed targets, the regression corpus) are judged by the conversation or by promotion.
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

    /// @return Whether this check judges a step of a flow, and so has an outcome a step's rule (transition)
    ///         can name. The compile check has its own outcome, `compile-failed`, and the regression corpus
    ///         judges promotion, not a step.
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
