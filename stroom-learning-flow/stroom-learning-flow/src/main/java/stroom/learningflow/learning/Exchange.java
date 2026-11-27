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

import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.TurnScore;

import java.util.List;

/// One turn of a learning session's conversation with the model, as recorded in the transcript.
///
/// @param question  The question as it was put.
/// @param reply     The reply as it was given.
/// @param step      The id of the flow step that asked it, or null for a turn not placed in a flow.
/// @param candidate Which candidate of that step the turn belongs to.
/// @param outcome   How the candidate was judged. Null until it is judged, and stays null for a turn that
///                  was never judged, such as one whose reply took the session over its budget.
/// @param score     The score of the check that decided the outcome, or null where no scorer decided it.
/// @param carried   The ids of the guidance the question was asked with, so that a resumed attempt
///                  replays what was actually used rather than guidance added since.
public record Exchange(Question question,
                       String reply,
                       String step,
                       int candidate,
                       StepOutcome outcome,
                       TurnScore score,
                       List<Long> carried) {

    public Exchange {
        carried = List.copyOf(carried);
    }

    /// A turn not placed in a flow, as an advisor is given its transcript.
    public Exchange(final Question question, final String reply) {
        this(question, reply, null, 0, null, null, List.of());
    }

    public Exchange(final Question question,
                    final String reply,
                    final String step,
                    final int candidate,
                    final StepOutcome outcome) {
        this(question, reply, step, candidate, outcome, null, List.of());
    }

    /// A turn with the guidance it was asked with, usually not yet judged (`outcome` null).
    public Exchange(final Question question,
                    final String reply,
                    final String step,
                    final int candidate,
                    final StepOutcome outcome,
                    final List<Long> carried) {
        this(question, reply, step, candidate, outcome, null, carried);
    }

    /// Returns a copy of this turn with its outcome, and the score of the check that decided it where one
    /// did.
    public Exchange judged(final StepOutcome outcome, final TurnScore score) {
        return new Exchange(question, reply, step, candidate, outcome, score, carried);
    }
}
