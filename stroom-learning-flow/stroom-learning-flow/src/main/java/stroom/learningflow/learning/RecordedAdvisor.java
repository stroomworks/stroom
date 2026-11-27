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

import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Guidance.Given;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/// An advisor used to resume an attempt. It answers questions from the turns a previous run recorded, in
/// the order they were asked, and once those run out it passes questions to another advisor: the model, or
/// [#awaiting()] where the attempt is to stop and wait.
///
/// The conversation does not save its own state. Instead it is walked again from the start with the answers
/// it was given, which derives again everything those answers produced (the chain, the boundary, the
/// records, the targets, each element's configuration and output), because all of it follows from the
/// sample and the answers, and both are kept. Deriving these again is cheaper than storing them, and much
/// of it is the stream's own text, which should not be copied.
///
/// A replayed answer is judged by the walk exactly as it was the first time. That the walk has reached the
/// same place is checked rather than assumed: each replayed answer is given only to the question the record
/// says it answered, and a walk that asks anything else has diverged and is refused (see [ReplayDiverged]).
/// So a resumed attempt either reaches the state it stopped in or says it could not, and never binds a
/// configuration learned from a mixture of two walks.
public final class RecordedAdvisor implements Advisor {

    private final List<Turn> answered = new ArrayList<>();
    private final Advisor then;
    private int next;
    /// The turn the last question was answered from, or null where it was put to the next advisor. It
    /// decides whether the guidance recorded for the turn is the recorded turn's or today's.
    private Turn replayed;

    /// @param turns The turns of the attempt being resumed. They are sorted by number; turns with no answer
    ///              are where it stopped, and are not replayed.
    /// @param then  The advisor that answers once the recorded turns run out: the model for an attempt
    ///              being resumed, or [#awaiting()] for one that is to stop and wait for the worker.
    public RecordedAdvisor(final List<Turn> turns, final Advisor then) {
        turns.stream()
                .sorted(Comparator.comparingInt(Turn::number))
                .filter(turn -> turn.answer() != null)
                .forEach(answered::add);
        this.then = then;
    }

    /// Returns an advisor that answers nothing, by throwing [AwaitingAnswer]. The conversation stops at the
    /// first question the record does not answer, and the attempt waits for the worker or a person to
    /// answer it.
    ///
    /// @return The advisor.
    public static Advisor awaiting() {
        return (transcript, question) -> {
            throw new AwaitingAnswer(question);
        };
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        return ask(transcript, question, List.of());
    }

    /// Answers from the record first, then from the next advisor.
    ///
    /// Guidance is passed on to the next advisor but not applied to recorded answers. A replayed turn was
    /// asked with the guidance in force then, and recording today's guidance against it would claim the
    /// question was asked with a hint it never had.
    @Override
    public String ask(final List<Exchange> transcript, final Question question, final List<Given> guidance) {
        if (next < answered.size()) {
            final Turn turn = answered.get(next++);
            // The identity covers the kind of question and what it is about. The count of shortfalls is left
            // out, because a replay over a fragment the session itself bound is told of that fragment's
            // shortfalls from its first question, which the original questions were not.
            if (!question.identity().equals(Question.identityOf(turn.question()))) {
                throw new ReplayDiverged(turn, question);
            }
            replayed = turn;
            return turn.answer();
        }
        replayed = null;
        return then.ask(transcript, question, guidance);
    }

    /// @return The recorded turn the last question was answered from; empty where it was passed to the
    ///         next advisor, or nothing has been asked yet.
    public Optional<Turn> lastReplayed() {
        return Optional.ofNullable(replayed);
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
        // Recorded turns were charged when they were first asked, so only the next advisor's tokens count.
        return then.tokensUsed();
    }


    // --------------------------------------------------------------------------------


    /// Thrown when a resumed walk asks something other than what the record says was asked at that turn.
    /// The flow, the document or the sample has changed since, so the recorded answers are not answers to
    /// the questions now being asked. The attempt is refused rather than resumed, since an answer to the
    /// wrong question would be judged, configured and possibly bound.
    public static final class ReplayDiverged extends RuntimeException {

        ReplayDiverged(final Turn turn, final Question question) {
            super("The attempt cannot be resumed: turn " + turn.number() + " answered '" + turn.question()
                  + "' but the attempt now asks '" + question.summary() + "'");
        }
    }


    // --------------------------------------------------------------------------------


    /// Thrown when an attempt reaches a question that nothing present can answer. The attempt stops there,
    /// and the worker or a person takes it up. The conversation fills in where in the walk it stopped,
    /// since only it knows, so that the turn can be recorded unanswered and a person can answer it.
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

        /// @return The id of the step whose question it stopped at.
        public String stepId() {
            return stepId;
        }

        /// @return Which candidate of that step it stopped at.
        public int candidate() {
            return candidate;
        }

        /// @return The number the unanswered turn would have in the transcript.
        public int number() {
            return number;
        }
    }
}
