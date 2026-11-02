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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.stage.Guidance.Given;

import java.util.List;

/**
 * The AI seam of design §10, as the conversation of A21 sees it: one question at a time, each carrying the
 * exchanges before it. A node implements this over {@code stroom-ai}; a test implements it with canned
 * replies. Nothing on this side of the seam knows which.
 */
public interface Advisor {

    /**
     * @param transcript Every exchange of the attempt so far, oldest first. Empty for the first question.
     * @param question   The question to put to the model.
     * @return The model's reply, verbatim. Parsing it is the caller's job.
     */
    String ask(List<Exchange> transcript, Question question);

    /// The same, with what a supervisor has said about this learner (A46) — carried into the question, in
    /// front of it, as context rather than as a turn of its own.
    ///
    /// Defaulted so that an advisor with no use for it needs to know nothing: a scripted one answers
    /// what it was told to answer, and guidance is for the thing that has to be persuaded.
    ///
    /// @param guidance Everything standing at the moment the question is asked, oldest first. Read per
    ///                 question rather than per attempt, so that a hint given while an attempt is parked
    ///                 is carried by the turn that resumes it (A5, A28).
    default String ask(final List<Exchange> transcript,
                       final Question question,
                       final List<Given> guidance) {
        return ask(transcript, question);
    }

    /// What the answer just given was actually asked with (A46): the ids a turn records having carried.
    ///
    /// For an advisor that put the question, that is the guidance it was offered. For one that replayed
    /// an answer from the record (A45), it is what the *recorded* turn carried — because a re-walk
    /// replays what was used and not what has since been added, and only the advisor knows which of the
    /// two just happened.
    ///
    /// @param offered What was given to the [#ask] that has just returned.
    default List<Long> carried(final List<Given> offered) {
        return offered.stream().map(Given::id).toList();
    }

    /**
     * Tokens the model has charged this advisor for so far, where it says; zero where it does not. The
     * conversation reads it before and after each question to hold an attempt to its token budget (A5).
     */
    default long tokensUsed() {
        return 0;
    }

    /// Whether the next question would be answered from a record rather than put to anyone (A45): true
    /// of an advisor re-walking an attempt while its recorded turns last, false of every other.
    default boolean replaying() {
        return false;
    }
}
