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

import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What a script expects of one question: its kind, and as much of its content as the scenario cares
 * to pin down. Built fluently; every {@code with…} adds a condition and a phrase to the description.
 */
public final class QuestionMatcher {

    private final List<Predicate<Question>> conditions = new ArrayList<>();
    private final StringBuilder description = new StringBuilder();

    private QuestionMatcher(final String kind, final Predicate<Question> isKind) {
        description.append(kind);
        conditions.add(isKind);
    }

    public static QuestionMatcher chain() {
        return new QuestionMatcher("chain question", q -> q instanceof Chain);
    }

    public static QuestionMatcher split() {
        return new QuestionMatcher("split question", q -> q instanceof Split);
    }

    public static QuestionMatcher target() {
        return new QuestionMatcher("target question", q -> q instanceof TargetFor);
    }

    public QuestionMatcher forRecord(final String record) {
        return with("for the record " + record,
                q -> q instanceof final TargetFor t && t.record().equals(record));
    }

    public QuestionMatcher withTargets(final int count) {
        return with("with " + count + " target(s)",
                q -> q instanceof final Configuration c && c.targets().size() == count);
    }

    public static QuestionMatcher configuration(final String elementType) {
        return new QuestionMatcher("configuration question for " + elementType,
                q -> q instanceof final Configuration c && c.elementType().equals(elementType) && !c.admitting());
    }

    /// The exhaustion step's question (design 04 §4): the transform's configuration asked for once more,
    /// with the admitted form in it.
    public static QuestionMatcher admission(final String elementType) {
        return new QuestionMatcher("admission question for " + elementType,
                q -> q instanceof final Configuration c && c.elementType().equals(elementType) && c.admitting());
    }

    public QuestionMatcher allowing(final String... elementTypes) {
        return with("allowing " + List.of(elementTypes),
                q -> q instanceof final Chain s && s.allowedElements().equals(List.of(elementTypes)));
    }

    public QuestionMatcher withKey(final String name, final String value) {
        return with("with key " + name + "=" + value,
                q -> value.equals(keyValues(q).get(name)));
    }

    public QuestionMatcher withInput(final String input) {
        return with("with input of " + input.length() + " chars",
                q -> q instanceof final Configuration c && input.equals(c.input()));
    }

    public QuestionMatcher withoutFeedback() {
        return with("without feedback", q -> q.feedback().isEmpty());
    }

    public QuestionMatcher withFeedbackMentioning(final String text) {
        return with("with feedback mentioning '" + text + "'",
                q -> q.feedback().stream().map(StoredError::getMessage).anyMatch(m -> m.contains(text)));
    }

    public QuestionMatcher withPreviousConfiguration(final String configuration) {
        return with("with the previous configuration",
                q -> q instanceof final Configuration c && configuration.equals(c.previousConfiguration()));
    }

    private QuestionMatcher with(final String phrase, final Predicate<Question> condition) {
        description.append(", ").append(phrase);
        conditions.add(condition);
        return this;
    }

    boolean matches(final Question question) {
        return conditions.stream().allMatch(condition -> condition.test(question));
    }

    String describe() {
        return description.toString();
    }

    private static java.util.Map<String, String> keyValues(final Question question) {
        return switch (question) {
            case Chain s -> s.sample().headers();
            case Split s -> s.sample().headers();
            case TargetFor t -> t.sample().headers();
            case Configuration c -> c.sample().headers();
        };
    }
}
