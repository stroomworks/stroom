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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.shapeshifter.ai.learning.Exchange;
import stroom.shapeshifter.ai.learning.Question;
import stroom.shapeshifter.ai.scoring.Judgement;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Bound;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Drafted;
import stroom.shapeshifter.ai.stage.Decision.GivenUp;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Parked;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Decision.Provisional;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Decision.Sentinel;
import stroom.shapeshifter.ai.stage.Decision.Would;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.ShapeshifterAiStepDetails;
import stroom.shapeshifter.shared.StageScore;
import stroom.shapeshifter.shared.StageVerdict;
import stroom.shapeshifter.shared.SupervisorTurn;
import stroom.util.shared.NullSafe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The stage pane's contents, from what the stage decided (A30, design 01 §11.7).
///
/// A pure mapping from [StageRun] — which was written as everything a scenario asserts on (design 02
/// §1), and is the same list a person wants to see — onto the shared form the client renders. The one
/// thing it adds is whether this was a step rather than a run, because a dry run's decision is what the
/// stage *would* have done and the pane has to say so.
public final class StepDetails {

    private StepDetails() {
    }

    public static ShapeshifterAiStepDetails of(final StageRun run, final Input input, final boolean dryRun) {
        final Produced produced = run.produced();
        final DocRef fragment = produced != null
                ? produced.fragment()
                : null;
        return new ShapeshifterAiStepDetails(
                dryRun,
                document(run.doc()),
                stream(input),
                run.decision().getClass().getSimpleName(),
                describe(run.decision()),
                produced == null
                        ? null
                        : produced.bindingUuid(),
                fragment,
                produced != null && produced.provisional(),
                produced == null
                        ? null
                        : produced.score(),
                produced == null || produced.boundary() == null
                        ? null
                        : produced.boundary().toString(),
                verdicts(run.verdicts()),
                transcript(run.transcript()),
                // Attached per record by [Supervision], since the decision is the stream's and what each
                // of the fragment's elements made of the input is each record's.
                List.of());
    }

    /// The stream's feed and type, in that order, where it has them: what the model is shown of it.
    private static Map<String, String> stream(final Input input) {
        final Map<String, String> stream = new LinkedHashMap<>();
        if (input.feed() != null) {
            stream.put(MetaFields.FIELD_FEED, input.feed());
        }
        if (input.type() != null) {
            stream.put(MetaFields.FIELD_TYPE, input.type());
        }
        return stream;
    }

    private static DocRef document(final ShapeshifterAiDoc doc) {
        return DocRef.builder()
                .type(ShapeshifterAiDoc.TYPE)
                .uuid(doc.getUuid())
                .name(doc.getName())
                .build();
    }

    /// What was decided, in one line, in the terms design 02 §4 uses.
    ///
    /// **Every** decision has a line. A surface that says nothing for the outcomes nobody thought about
    /// is worse than no surface, and the error stream and the stage pane say the same thing because
    /// they say it from here — a second switch elsewhere is a second place to forget an outcome.
    public static String describe(final Decision decision) {
        return switch (decision) {
            case Bound bound -> bound.binding() == null
                    ? "Served by the fragment that produced this input's output before"
                    : "Served by binding " + bound.binding().getUuid();
            case Promoted promoted -> "Promoted binding " + promoted.binding().getUuid() + ", scoring "
                                      + promoted.score();
            case Provisional provisional -> "Bound provisionally, scoring " + provisional.score() + " on "
                                            + provisional.records() + " records of the "
                                            + provisional.required() + " a held-out judgement needs";
            case Rebound rebound -> "Relearned: binding " + rebound.binding().getUuid() + " rebound, scoring "
                                    + rebound.score();
            case Kept kept -> "Relearned and the incumbent kept: " + kept.reason();
            case Drafted drafted -> "Awaiting review: draft binding " + drafted.binding().getUuid() + " binds "
                                    + drafted.binding().getPipeline().getName();
            case GivenUp givenUp -> "Given up: " + givenUp.reason();
            case Sentinel sentinel -> sentinel.reason();
            case Deferred deferred -> "Deferred: " + deferred.reason();
            case Parked parked -> "Parked for a person: " + parked.reason();
            case Would would -> would.said();
        };
    }

    private static List<StageVerdict> verdicts(final List<Verdict> verdicts) {
        final List<StageVerdict> mapped = new ArrayList<>();
        for (int step = 0; step < verdicts.size(); step++) {
            final Verdict verdict = verdicts.get(step);
            final List<StageScore> scores = verdict.judgements().stream()
                    .map(StepDetails::score)
                    .toList();
            mapped.add(new StageVerdict(step + 1, verdict.weightedTotal(), verdict.passed(), scores));
        }
        return mapped;
    }

    private static StageScore score(final Judgement judgement) {
        return new StageScore(
                judgement.setting().getType(),
                judgement.score().value(),
                judgement.setting().getThreshold(),
                judgement.setting().isGate(),
                judgement.metThreshold());
    }

    /// The transcript as the Supervisor view carries a turn (A28), so that a person reading one in the
    /// stepper and one in the Supervisor is reading the same thing.
    private static List<SupervisorTurn> transcript(final List<Exchange> transcript) {
        final List<SupervisorTurn> turns = new ArrayList<>();
        for (int number = 0; number < transcript.size(); number++) {
            final Exchange exchange = transcript.get(number);
            turns.add(new SupervisorTurn(
                    number + 1,
                    exchange.step(),
                    exchange.candidate(),
                    Question.kindOf(exchange.question()),
                    exchange.question().summary(),
                    exchange.reply(),
                    // Who answered is the attempt's to know (A28); a step reads a transcript the stage
                    // has just made, and everything in it was answered by the model.
                    null,
                    exchange.outcome(),
                    exchange.score()));
        }
        return NullSafe.list(turns);
    }
}
