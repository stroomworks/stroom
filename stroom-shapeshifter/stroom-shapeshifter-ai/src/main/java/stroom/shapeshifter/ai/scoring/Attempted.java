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

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.shared.RecordBoundary;

/**
 * One step of a chain as the scorers see it: what went in, what came out, which element did it, and
 * whether that element is a parser — whose records are the input's shape, not the target's, so the
 * scorers that judge meaning leave them alone (design 01 §4).
 *
 * @param boundary What one record is where the input is markup (A35) — the element, or the JSON array's
 *                 items — so that yield counts the records the split settled and not the root's children;
 *                 null where none was settled, and the root's children are the records.
 */
public record Attempted(String elementType,
                        boolean parser,
                        String input,
                        StepResult result,
                        RecordBoundary boundary) {

    public Attempted(final String elementType, final boolean parser, final String input, final StepResult result) {
        this(elementType, parser, input, result, null);
    }

    public static Attempted of(final StepRunner runner, final String input, final StepResult result) {
        return of(runner, input, result, null);
    }

    public static Attempted of(final StepRunner runner,
                               final String input,
                               final StepResult result,
                               final RecordBoundary boundary) {
        return new Attempted(runner.elementType(), runner.parser(), input, result, boundary);
    }
}
