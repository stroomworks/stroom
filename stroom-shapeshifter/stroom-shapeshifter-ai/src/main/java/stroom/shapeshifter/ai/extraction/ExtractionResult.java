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

package stroom.shapeshifter.ai.extraction;

import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.util.shared.TextRange;

import java.util.List;

/**
 * What one run of a compiled Data Splitter configuration over one input produced: the records document,
 * the span of input each record was split from, and every diagnostic raised on the way.
 * <p>
 * This is the raw material for the extraction-stage scorers of design §8.4. Yield is the record count,
 * input coverage (ruling A11) is derived from the record ranges, and error load from the diagnostics.
 *
 * @param records      The {@code records:2} document the split produced, serialised and indented exactly as
 *                     the stepper and the {@code TestDS3} corpus present it, so that it diffs against a
 *                     golden output directly.
 * @param recordRanges The span of input consumed by each record, in output order. One-based, inclusive at
 *                     both ends, and never including a line break, as the Data Splitter reports them.
 * @param diagnostics  Every warning, error and fatal raised while running, in the order raised.
 */
public record ExtractionResult(String records,
                               List<TextRange> recordRanges,
                               List<StoredError> diagnostics) {

    public int recordCount() {
        return recordRanges.size();
    }

    public long count(final Severity severity) {
        return diagnostics.stream()
                .filter(diagnostic -> diagnostic.getSeverity() == severity)
                .count();
    }

    public InputCoverage coverage(final String input) {
        return InputCoverage.measure(input, recordRanges);
    }
}
