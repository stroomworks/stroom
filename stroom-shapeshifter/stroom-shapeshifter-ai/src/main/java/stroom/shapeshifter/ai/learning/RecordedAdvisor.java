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

import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Guidance.Given;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/// An advisor that answers from what an attempt was already answered (A28): the turns a previous run
/// recorded, in the order they were put, and then the model — or nothing, where the attempt is to stop
/// and wait.
///
/// This is how an attempt resumes. The conversation is not a state machine that saves its own workings: it is
/// re-walked from the start with the answers it was given, which re-derives everything those answers
/// produced — the chain, the boundary, the records, the targets, each element's configuration and output
/// — because all of it follows from the sample and the answers, both of which are kept. What the model
/// said is a record; what running produced is a consequence, and consequences are cheaper to re-derive
/// than to store, being the stream's own text besides (A38).
///
/// A replayed answer is not re-scored by hand: the walk judges it exactly as it judged it the first time.
/// That the walk has reached the same place is checked rather than assumed — every replayed answer is
/// given only to the question the record says it answered, and a walk that asks anything else has
/// diverged and is refused (see [ReplayDiverged]). An attempt that resumes therefore either reaches the
/// state it stopped in or says it could not, and never binds a configuration learned from a mixture of
/// two walks.
public final class RecordedAdvisor implements Advisor {

    private final List<Turn> answered = new ArrayList<>();
    private final Advisor then;
    private int next;
    /// The turn the last question was answered from, or null where it was put afresh: what says whether
    /// the guidance a re-walk records is the record's or today's.
    private Turn replayed;

    /// @param turns The turns of the attempt being resumed, in order; those with no answer are the ones it
    ///              stopped at and are not replayed.
    /// @param then  Who answers once the record runs out: the model for an attempt being carried on, or
    ///              [#awaiting()] for one that is to stop and wait for the worker.
    public RecordedAdvisor(final List<Turn> turns, final Advisor then) {
        turns.stream()
                .sorted(Comparator.comparingInt(Turn::number))
                .filter(turn -> turn.answer() != null)
                .forEach(answered::add);
        this.then = then;
    }

    /// An advisor that answers nothing: the conversation stops at the first question the record does not
    /// answer, and the attempt waits for whoever will (A28).
    public static Advisor awaiting() {
        return (transcript, question) -> {
            throw new AwaitingAnswer(question);
        };
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        return ask(transcript, question, List.of());
    }

    /// The record first, then whoever answers what it does not.
    ///
    /// Guidance is passed on to the one that has to be persuaded and withheld from the record, which has
    /// already answered: a replayed turn was asked under what stood then, and saying otherwise would put
    /// a hint into the history of a question nobody asked with it.
    @Override
    public String ask(final List<Exchange> transcript, final Question question, final List<Given> guidance) {
        if (next < answered.size()) {
            final Turn turn = answered.get(next++);
            // The identity names the kind of question and what it is about, so one comparison covers
            // both; how much the step had been told is left out of it, since a re-walk over a fragment
            // the session itself bound is told of that fragment's shortfalls from its first question.
            if (!question.identity().equals(Question.identityOf(turn.question()))) {
                throw new ReplayDiverged(turn, question);
            }
            replayed = turn;
            return turn.answer();
        }
        replayed = null;
        return then.ask(transcript, question, guidance);
    }

    @Override
    public boolean replaying() {
        return next < answered.size();
    }

    @Override
    public List<Long> carried(final List<Given> offered) {
        return replayed == null
                ? then.carried(offered)
                : replayed.carried();
    }

    @Override
    public long tokensUsed() {
        // What the record cost was charged when it was first asked; this advisor charges for what it adds.
        return then.tokensUsed();
    }


    // --------------------------------------------------------------------------------


    /// Raised where a resumed walk asks something other than what the record says it asked at that turn
    /// (A45): the flow, the document or the sample has changed under the attempt, so the answers kept are
    /// not answers to the questions now being put. The attempt is refused rather than carried on, since an
    /// answer given to the wrong question would be judged, configured and possibly bound.
    public static final class ReplayDiverged extends RuntimeException {

        ReplayDiverged(final Turn turn, final Question question) {
            super("The attempt cannot be resumed: turn " + turn.number() + " answered '" + turn.question()
                  + "' but the attempt now asks '" + question.summary() + "'");
        }
    }


    // --------------------------------------------------------------------------------


    /// Raised where an attempt has reached a question nobody present can answer: it stops here, and the
    /// worker or a person takes it up (A28). Where in the walk it stopped is filled in by the conversation,
    /// which alone knows it, so that the turn can be recorded unanswered and a person can answer it.
    public static final class AwaitingAnswer extends RuntimeException {

        private final transient Question question;
        private final String stepId;
        private final int candidate;
        private final int number;

        AwaitingAnswer(final Question question) {
            this(question, null, 1, 0);
        }

        public AwaitingAnswer(final Question question, final String stepId, final int candidate,
                              final int number) {
            super("The attempt awaits an answer to its next question");
            this.question = question;
            this.stepId = stepId;
            this.candidate = candidate;
            this.number = number;
        }

        public Question question() {
            return question;
        }

        /// The step whose question it stopped at.
        public String stepId() {
            return stepId;
        }

        /// Which candidate of that step.
        public int candidate() {
            return candidate;
        }

        /// The turn it would have been, by its number in the transcript.
        public int number() {
            return number;
        }
    }
}
