/*
 * Copyright 2026 Crown Copyright
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
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Guidance.Given;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// The advisor a rebuild walks with (decision N9): it answers every question from the record, as a resume
/// does, and keeps the prompt each recorded turn was put to the model with — the system text with the
/// guidance the turn carried, and the question — in the words the run was asked in. It never asks anyone:
/// a question the record does not answer stops the walk, which is where the run itself stopped.
///
/// The earlier exchanges that went with each question are not repeated here: they are the turns before
/// it, which the conversation already shows.
public final class PromptCapture implements Advisor {

    private final RecordedAdvisor replay;
    private final QuestionText words;
    private final List<Given> standing;
    private final Map<Integer, String> prompts = new HashMap<>();
    private final Map<Integer, Integer> withdrawn = new HashMap<>();

    /// @param turns    The run's recorded turns.
    /// @param words    The run's own words, as [QuestionText#of(stroom.learningflow.shared.LearningFlowDoc,
    ///                 AskedWith, TargetSchema)] builds them from what it recorded.
    /// @param standing The guidance standing now, from which each turn's is read back by the ids it
    ///                 recorded carrying; anything since withdrawn is no longer there to show.
    public PromptCapture(final List<Turn> turns, final QuestionText words, final List<Given> standing) {
        this.replay = new RecordedAdvisor(turns, RecordedAdvisor.awaiting());
        this.words = words;
        this.standing = List.copyOf(standing);
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question) {
        return ask(transcript, question, List.of());
    }

    @Override
    public String ask(final List<Exchange> transcript, final Question question, final List<Given> guidance) {
        // Rendered before the record is consulted: a question the record doesn't answer throws, and its
        // prompt is no part of what was sent
        final String rendered = words.render(question);
        final String answer = replay.ask(transcript, question, guidance);
        final Optional<Turn> turn = replay.lastReplayed();
        turn.ifPresent(answered -> {
            final Set<Long> ids = Set.copyOf(answered.carried());
            final List<Given> carried = standing.stream()
                    .filter(given -> ids.contains(given.id()))
                    .collect(Collectors.toList());
            prompts.put(answered.number(), words.system() + Guidance.asSaid(carried) + "\n\n" + rendered);
            if (carried.size() < ids.size()) {
                withdrawn.put(answered.number(), ids.size() - carried.size());
            }
        });
        return answer;
    }

    @Override
    public boolean replaying() {
        return replay.replaying();
    }

    @Override
    public List<Long> carried(final List<Given> offered) {
        return replay.carried(offered);
    }

    /// @return The prompt each turn the walk reached was put to the model with, by its number.
    public Map<Integer, String> prompts() {
        return Map.copyOf(prompts);
    }

    /// @return How many notes each turn carried that have since been withdrawn, and so can't be shown, by
    ///         its number; turns with none are absent.
    public Map<Integer, Integer> withdrawn() {
        return Map.copyOf(withdrawn);
    }
}
