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
 * The scorecard's verdict on one step: every judgement, the weighted total of the scores that carry
 * weight, and whether the step passes — every gate met and every threshold met. The feedback is what
 * the model is told to try again: each failing scorer's diagnostics, prefaced by which scorer and by
 * how much it fell short.
 *
 * @param judgements    One per scorer that applied, in the document's order.
 * @param weightedTotal The weight-averaged score in [0, 1], or 1.0 if no scorer carried weight.
 */
public record Verdict(List<Judgement> judgements, double weightedTotal) {

    private static final ElementId SCORECARD = new ElementId("Scorecard");

    public boolean passed() {
        return judgements.stream().allMatch(Judgement::metThreshold);
    }

    public boolean gatesPassed() {
        return judgements.stream().noneMatch(Judgement::failedGate);
    }

    /// The records that fell short of the bar (design 04 §3, A55): a record clears it when every gate
    /// scorer that judges records one by one passed on *that* record, so this is the union of the gates'
    /// failing records. Zero-based, in output order. Empty where every record cleared it — or where no
    /// gate judges per record, which is a document that has chosen not to have a bar.
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
                        judgement.setting().getType().getDisplayValue() + " scored "
                        + judgement.score().value() + " against a threshold of "
                        + judgement.setting().getThreshold()
                        + (judgement.setting().isGate()
                                ? " (a gate)"
                                : "")));
                feedback.addAll(judgement.score().diagnostics());
            }
        }
        return feedback;
    }
}
