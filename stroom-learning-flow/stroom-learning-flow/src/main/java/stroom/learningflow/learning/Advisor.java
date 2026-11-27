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

import stroom.learningflow.stage.Guidance.Given;

import java.util.List;

/// Puts one question to the model and returns its reply. [Conversation] asks every question of a learning
/// session through this interface, passing the exchanges before it so the model sees the session's history.
///
/// A node implements it over `stroom-ai`, and tests implement it with scripted replies, so the conversation
/// does not know which it is talking to.
public interface Advisor {

    /// Asks the model one question.
    ///
    /// @param transcript Every exchange of the attempt so far, oldest first. Empty for the first question.
    /// @param question   The question to put to the model.
    /// @return The model's reply, verbatim. Parsing it is the caller's job.
    String ask(List<Exchange> transcript, Question question);

    /// Asks the model one question, with the guidance a person has left for this learner. The guidance is
    /// put in front of the question as context, not sent as a turn of its own.
    ///
    /// The default ignores the guidance, because an advisor that does not talk to a model (such as a
    /// scripted one in a test) has no use for it.
    ///
    /// @param transcript Every exchange of the attempt so far, oldest first. Empty for the first question.
    /// @param question   The question to put to the model.
    /// @param guidance   All the guidance in force when the question is asked, oldest first. It is read for
    ///                   each question rather than once per attempt, so that a hint given while an attempt
    ///                   is parked is included in the turn that resumes it.
    /// @return The model's reply, verbatim.
    default String ask(final List<Exchange> transcript,
                       final Question question,
                       final List<Given> guidance) {
        return ask(transcript, question);
    }

    /// Returns the ids of the guidance the last answer was actually given with, for the turn to record.
    ///
    /// For an advisor that put the question to the model, that is the guidance it was offered. For one that
    /// replayed a recorded answer while resuming an attempt, it is the guidance the *recorded* turn had,
    /// because a replay repeats what was used rather than what has been added since. Only the advisor knows
    /// which of the two just happened.
    ///
    /// @param offered The guidance passed to the [#ask] that has just returned.
    /// @return The ids of the guidance the answer was given with.
    default List<Long> carried(final List<Given> offered) {
        return offered.stream().map(Given::id).toList();
    }

    /// The tokens the model has charged this advisor for so far, where the model reports them, or zero where
    /// it does not. The conversation reads it before and after each question to keep an attempt within its
    /// token budget.
    ///
    /// @return The tokens used so far.
    default long tokensUsed() {
        return 0;
    }

    /// Whether the next question will be answered from a recorded turn rather than put to the model. It is
    /// true for an advisor that is replaying a resumed attempt while recorded turns remain, and false for
    /// every other.
    ///
    /// @return True while answers are being replayed from the record.
    default boolean replaying() {
        return false;
    }
}
