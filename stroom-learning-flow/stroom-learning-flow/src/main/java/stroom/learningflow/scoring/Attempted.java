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

import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.transformation.StreamFacts;

/**
 * One step of a chain as the scorers see it: what went in, what came out, which element did it, and
 * whether that element is a parser — whose records are the input's shape, not the target's, so the
 * scorers that judge meaning leave them alone (design 01 §4).
 *
 * @param boundary      What one record is where the input is markup (A35) — the element, or the JSON
 *                      array's items — so that yield counts the records the split settled and not the
 *                      root's children; null where none was settled, and the root's children are the
 *                      records.
 * @param configuration What the model wrote for this element — the stylesheet, or the parser's
 *                      configuration — which the permitted check reads to see what it uses (decision N1).
 *                      Null where the step has none, such as a chain being chosen.
 */
public record Attempted(String elementType,
                        boolean parser,
                        String input,
                        StepResult result,
                        RecordBoundary boundary,
                        String configuration,
                        StreamFacts facts) {

    public Attempted {
        facts = facts == null
                ? StreamFacts.none()
                : facts;
    }

    public Attempted(final String elementType,
                     final boolean parser,
                     final String input,
                     final StepResult result,
                     final RecordBoundary boundary,
                     final String configuration) {
        this(elementType, parser, input, result, boundary, configuration, StreamFacts.none());
    }

    public Attempted(final String elementType, final boolean parser, final String input, final StepResult result) {
        this(elementType, parser, input, result, null, null);
    }

    public static Attempted of(final StepRunner runner, final String input, final StepResult result) {
        return of(runner, input, result, null);
    }

    public static Attempted of(final StepRunner runner,
                               final String input,
                               final StepResult result,
                               final RecordBoundary boundary) {
        return of(runner, input, result, boundary, null);
    }

    public static Attempted of(final StepRunner runner,
                               final String input,
                               final StepResult result,
                               final RecordBoundary boundary,
                               final String configuration) {
        return of(runner, input, result, boundary, configuration, StreamFacts.none());
    }

    public static Attempted of(final StepRunner runner,
                               final String input,
                               final StepResult result,
                               final RecordBoundary boundary,
                               final String configuration,
                               final StreamFacts facts) {
        return new Attempted(runner.elementType(), runner.parser(), input, result, boundary, configuration,
                facts);
    }
}
