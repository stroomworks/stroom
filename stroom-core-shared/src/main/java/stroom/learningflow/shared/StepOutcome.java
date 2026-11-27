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

/// How judging one candidate ended. This is the fixed set of outcomes that a flow's transitions are written
/// against. `PASSED` goes to the next step; `REFUSED` means a reply that does not fit the question's expected
/// form; `COMPILE_FAILED` means a configuration that did not compile; `RUN_FAILED` means an element that raised
/// errors on its input; and there is one shortfall outcome per [Check]. Running out of budget is not an
/// outcome, because it ends the session from outside the flow.
public enum StepOutcome implements HasDisplayValue {
    PASSED("passed"),
    REFUSED("refused"),
    COMPILE_FAILED("compile-failed"),
    RUN_FAILED("run-failed"),
    COVERAGE_SHORT("coverage-short"),
    COMPLETENESS_SHORT("completeness-short"),
    YIELD_SHORT("yield-short"),
    WHOLENESS_SHORT("wholeness-short"),
    CONFORMANCE_SHORT("conformance-short"),
    QUALITY_SHORT("quality-short"),
    RULES_SHORT("rules-short"),
    TIMES_SHORT("times-short"),
    LITERALS_SHORT("literals-short"),
    PERMITTED_SHORT("permitted-short"),
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
}
