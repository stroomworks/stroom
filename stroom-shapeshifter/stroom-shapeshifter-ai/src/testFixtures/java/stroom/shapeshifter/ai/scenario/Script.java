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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The simulated model of design 02 §3: an ordered list of expected question and scripted reply. Each
 * question the conversation asks is checked against the next expectation and answered with its reply; a
 * question the script did not expect fails the scenario with both printed, and a script with lines
 * left over fails it at {@link #verifyExhausted()}. The script never looks at a question to decide
 * what to say — its author fixed the outcome.
 */
public final class Script implements Advisor {

    private final List<Line> lines = new ArrayList<>();
    private final List<Question> asked = new ArrayList<>();
    /// Tokens charged per question answered, for a scenario about a budget; zero, as a script is free.
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

    /**
     * Answer the structural questions of A31 — split and targets — from the configurations this script
     * will give, so that the scripted lines need state only the questions the scenario is about. A line
     * that expects a split or target question explicitly still takes precedence.
     */
    /// Charge tokens for every question answered from here on, so that a budget can be spent (A5, A77).
    public Script charging(final long tokensPerAsk) {
        this.charge = tokensPerAsk;
        return this;
    }

    @Override
    public long tokensUsed() {
        return asked.size() * charge;
    }

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

    /**
     * Every question asked, in order, for assertions after the run.
     */
    public List<Question> asked() {
        return List.copyOf(asked);
    }

    /**
     * The questions asked that the scenario scripted — everything but the split and target turns the
     * structure answered — so that a scenario about the configurations can count and index those alone.
     */
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

        /// A reply worked out when the question is asked rather than when the script is written: for a
        /// scenario about what another node does while this attempt is still running.
        Script reply(Supplier<String> reply);
    }

    private record Line(QuestionMatcher matcher, Supplier<String> reply) {

    }
}
