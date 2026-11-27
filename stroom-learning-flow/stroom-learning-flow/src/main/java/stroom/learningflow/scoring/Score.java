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

import stroom.learningflow.shared.Check;
import stroom.util.shared.StoredError;

import java.util.List;
import java.util.Set;

/// One scorer's measure of one step: a value in [0, 1] and the diagnostics that explain it, which are
/// what the model is told when the score is less than perfect.
///
/// @param failingRecords For a scorer that judges records individually, which of the step's output records
///                       fell short, as zero-based indexes in output order, as [OutputRecords] lists them.
///                       Empty for a scorer that measures the step as a whole, and where every record
///                       passed.
public record Score(Check type, double value, List<StoredError> diagnostics, Set<Integer> failingRecords) {

    public Score {
        if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
            throw new IllegalArgumentException("A score is a value in [0, 1], not " + value);
        }
        diagnostics = List.copyOf(diagnostics);
        failingRecords = Set.copyOf(failingRecords);
    }

    /// Creates a score with no per-record failures, for a scorer that measures the step as a whole.
    public Score(final Check type, final double value, final List<StoredError> diagnostics) {
        this(type, value, diagnostics, Set.of());
    }
}
