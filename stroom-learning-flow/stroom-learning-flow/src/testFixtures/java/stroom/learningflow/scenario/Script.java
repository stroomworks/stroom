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

package stroom.learningflow.scenario;

import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/// A scripted stand-in for the model in scenario tests: an ordered list of expected questions and the
/// replies to give.
///
/// Each question asked is checked against the next expected question and answered with its reply. A
/// question the script did not expect fails the scenario, printing both, and a script with lines left
/// over fails at [#verifyExhausted()]. The script never looks at a question to decide what to say; its
/// author has fixed the outcome in advance.
public final class Script implements Advisor {

    private final List<Line> lines = new ArrayList<>();
    private final List<Question> asked = new ArrayList<>();
    /// Tokens charged per question answered, for a scenario about a budget. Zero by default.
    private long charge;
    private final List<Structure> structures = new ArrayList<>();
    private int next;

    private Script() {
    }

    public static Script of() {
        return new Script();
    }

    public Expecting expect(final QuestionMatcher matcher) {
        return new Expecting() {
            @Override
            public Script reply(final String reply) {
                return reply(() -> reply);
            }

            @Override
            public Script reply(final Supplier<String> reply) {
                lines.add(new Line(matcher, reply));
                return Script.this;
            }
        };
    }

    /// Charges tokens for every question answered from now on, so that a scenario can use up a budget or
    /// a grant.
    public Script charging(final long tokensPerAsk) {
        this.charge = tokensPerAsk;
        return this;
    }

    @Override
    public long tokensUsed() {
        return asked.size() * charge;
    }

    /// Answers the structural questions (the split and the targets) from the configurations this script
    /// will give, so that the scripted lines need only state the questions the scenario is about. A line
    /// that explicitly expects a split or target question still takes precedence.
    public Script structure(final Structure structure) {
        this.structures.add(structure);
        return this;
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        asked.add(question);
        if (!structures.isEmpty() && (next >= lines.size() || !lines.get(next).matcher().matches(question))) {
            final Optional<String> answer = structures.stream()
                    .filter(candidate -> structures.size() == 1 || candidate.fits(question))
                    .findFirst()
                    .flatMap(candidate -> candidate.answer(question));
            if (answer.isPresent()) {
                return answer.get();
            }
        }
        if (next >= lines.size()) {
            throw new AssertionError("The conversation asked a question " + (next + 1)
                                     + " the script did not expect:\n" + question);
        }
        final Line line = lines.get(next++);
        if (!line.matcher().matches(question)) {
            throw new AssertionError("Question " + next + " did not match the script.\nExpected: "
                                     + line.matcher().describe() + "\nActual:   " + question);
        }
        return line.reply().get();
    }

    /// Returns every question asked, in order, for assertions after the run.
    public List<Question> asked() {
        return List.copyOf(asked);
    }

    /// Returns the questions asked other than split and target questions, which a [Structure] usually
    /// answers, so that a scenario about the configurations can count and index those alone.
    public List<Question> scripted() {
        return asked.stream()
                .filter(question -> !(question instanceof Question.Split) && !(question instanceof Question.TargetFor))
                .toList();
    }

    public void verifyExhausted() {
        if (next < lines.size()) {
            throw new AssertionError("The script had " + (lines.size() - next)
                                     + " reply(ies) the conversation never asked for; next expected: "
                                     + lines.get(next).matcher().describe());
        }
    }

    public interface Expecting {

        Script reply(String reply);

        /// A reply worked out when the question is asked rather than when the script is written, for a
        /// scenario about what another node does while this attempt is still running.
        Script reply(Supplier<String> reply);
    }

    private record Line(QuestionMatcher matcher, Supplier<String> reply) {

    }
}
