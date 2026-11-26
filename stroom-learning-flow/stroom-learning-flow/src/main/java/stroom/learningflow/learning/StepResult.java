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

package stroom.learningflow.learning;

import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.util.shared.TextRange;

import java.util.List;

/// What one element of the chain produced when a [StepRunner] ran it over its input, and every diagnostic it
/// raised. The output of one step is the input of the next.
///
/// @param output       What the element emitted, or null if it could not run at all.
/// @param diagnostics  Compile and run-time diagnostics, in the order raised.
/// @param recordRanges For a Data Splitter step, the span of input each output record was split from, in
///                     output order, as the Data Splitter reports them. The input coverage scorer uses them.
///                     Empty for any other step.
public record StepResult(String output, List<StoredError> diagnostics, List<TextRange> recordRanges) {

    public StepResult(final String output, final List<StoredError> diagnostics) {
        this(output, diagnostics, List.of());
    }

    /// Whether the step ran to completion and raised nothing at error severity or above. Warnings pass.
    /// This is only the minimum; the scorers judge quality beyond it, and are not applied here.
    ///
    /// @return True if the step produced output with no errors.
    public boolean passed() {
        return output != null && diagnostics.stream()
                .map(StoredError::getSeverity)
                .noneMatch(severity -> severity.greaterThanOrEqual(Severity.ERROR));
    }
}
