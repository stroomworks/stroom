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

/// How judging one candidate ended (A37; design 01 §10.2): the closed vocabulary a flow's transitions are
/// written over. `PASSED` goes to the next step; `REFUSED` is a reply outside the question's grammar;
/// `COMPILE_FAILED` a configuration that did not compile, `RUN_FAILED` an element that raised errors on its
/// input; and one shortfall per [Check]. The attempt's budget is not an outcome — it ends the attempt from
/// outside the flow.
public enum StepOutcome implements HasDisplayValue {
    PASSED("passed"),
    REFUSED("refused"),
    COMPILE_FAILED("compile-failed"),
    RUN_FAILED("run-failed"),
    COVERAGE_SHORT("coverage-short"),
    YIELD_SHORT("yield-short"),
    WHOLENESS_SHORT("wholeness-short"),
    CONFORMANCE_SHORT("conformance-short"),
    QUALITY_SHORT("quality-short"),
    RULES_SHORT("rules-short"),
    ERRORS_SHORT("errors-short"),
    CLASSIFICATION_SHORT("classification-short"),
    PRESERVATION_SHORT("preservation-short"),
    FIDELITY_SHORT("fidelity-short");

    private final String displayValue;

    StepOutcome(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// @return The outcome written as its word, e.g. `coverage-short`, or null for anything else.
    public static StepOutcome parse(final String word) {
        for (final StepOutcome outcome : values()) {
            if (outcome.displayValue.equalsIgnoreCase(word)) {
                return outcome;
            }
        }
        return null;
    }
}
