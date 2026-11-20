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

package stroom.shapeshifter.ai.scoring;

import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The scorecard's verdict on one step: every judgement, and their average. Whether the step passes is
 * [Scorecard#outcome]'s to say, since an advice check that falls short fails a step only where the step
 * has a rule for it (the one model of checks, D1). The feedback is what the model is told to try again:
 * each check that fell short, by how much, and its diagnostics.
 *
 * @param judgements One per check that applied, in the order of the checks.
 * @param score      The average of the checks that ran, gates and advice alike, in [0, 1]; 1.0 where none
 *                   ran.
 */
public record Verdict(List<Judgement> judgements, double score) {

    private static final ElementId SCORECARD = new ElementId("Scorecard");

    /// @param judgements One per check that applied.
    /// @return The verdict, with the average of the judgements as its score.
    public static Verdict of(final List<Judgement> judgements) {
        return new Verdict(List.copyOf(judgements), judgements.isEmpty()
                ? 1.0
                : judgements.stream().mapToDouble(judgement -> judgement.score().value()).average().orElse(1.0));
    }

    /// @return Whether no gate fell below its threshold.
    public boolean gatesPassed() {
        return judgements.stream().noneMatch(Judgement::failedGate);
    }

    /// The records that fell short of the bar (design 04 §3, A55): a record clears it when every gate
    /// that judges records one by one passed on *that* record, so this is the union of the gates'
    /// failing records. Zero-based, in output order. Empty where every record cleared it — or where no
    /// gate judges per record.
    public Set<Integer> failingRecords() {
        final Set<Integer> failing = new TreeSet<>();
        for (final Judgement judgement : judgements) {
            if (judgement.setting().isGate()) {
                failing.addAll(judgement.score().failingRecords());
            }
        }
        return failing;
    }

    public List<StoredError> feedback() {
        final List<StoredError> feedback = new ArrayList<>();
        for (final Judgement judgement : judgements) {
            if (!judgement.metThreshold()) {
                feedback.add(new StoredError(
                        Severity.ERROR,
                        null,
                        SCORECARD,
                        Scorecard.name(judgement.setting().getCheck()) + " scored "
                        + judgement.score().value() + " against a threshold of "
                        + judgement.setting().getPassingValue()
                        + (judgement.setting().isGate()
                                ? " (a gate)"
                                : " (advice)")));
                feedback.addAll(judgement.score().diagnostics());
            }
        }
        return feedback;
    }
}
