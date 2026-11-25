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

package stroom.learningflow.element;

import stroom.docref.DocRef;
import stroom.learningflow.learning.Exchange;
import stroom.learningflow.learning.Question;
import stroom.learningflow.scoring.Judgement;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.learningflow.shared.StageScore;
import stroom.learningflow.shared.StageVerdict;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.learningflow.stage.Decision;
import stroom.learningflow.stage.Decision.Bound;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Drafted;
import stroom.learningflow.stage.Decision.GivenUp;
import stroom.learningflow.stage.Decision.Kept;
import stroom.learningflow.stage.Decision.Parked;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Decision.Rebound;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Decision.Would;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.stage.StageRun;
import stroom.meta.shared.MetaFields;
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

    public static LearningFlowStepDetails of(final StageRun run, final Input input, final boolean dryRun) {
        final Produced produced = run.produced();
        final DocRef fragment = produced != null
                ? produced.fragment()
                : null;
        return new LearningFlowStepDetails(
                dryRun,
                run.doc().asDocRef(),
                stream(input),
                run.decision().getClass().getSimpleName(),
                describe(run.decision()),
                produced == null
                        ? null
                        : produced.bindingUuid(),
                fragment,
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

    /// What was decided, in one line, in the terms design 02 §4 uses.
    ///
    /// **Every** decision has a line. A surface that says nothing for the outcomes nobody thought about
    /// is worse than no surface, and the error stream and the stage pane say the same thing because
    /// they say it from here — a second switch elsewhere is a second place to forget an outcome.
    public static String describe(final Decision decision) {
        return switch (decision) {
            case Bound bound -> bound.binding() == null
                    ? "Served by the fragment that produced this input's output before"
                    : "Served by " + named(bound.binding());
            case Promoted promoted -> "Learned " + named(promoted.binding()) + " from this stream and served it, "
                                      + "scoring " + percent(promoted.score());
            case Rebound rebound -> "Relearned: binding " + rebound.binding().getUuid() + " rebound, scoring "
                                    + rebound.score();
            case Kept kept -> "Relearned and the incumbent kept: " + kept.reason();
            case Drafted drafted -> "Awaiting review: draft binding " + drafted.binding().getUuid() + " binds "
                                    + drafted.binding().getPipeline().getName();
            case GivenUp givenUp -> "Stopped learning: " + givenUp.reason();
            case Sentinel sentinel -> sentinel.reason();
            case Deferred deferred -> "Deferred: " + deferred.reason();
            case Parked parked -> "Parked for a person: " + parked.reason();
            case Would would -> would.said();
        };
    }

    /// What was decided as lines for the Error stream: [#describe], and where the decision stopped a
    /// learning run with a report, its details and suggestions beneath, each labelled ([StopReport#lines()]).
    ///
    /// @param decision What was decided.
    /// @return The lines, the first of them [#describe]'s.
    public static List<String> lines(final Decision decision) {
        final StopReport report = switch (decision) {
            case Parked parked -> parked.report();
            case GivenUp givenUp -> givenUp.report();
            default -> null;
        };
        final List<String> lines = new ArrayList<>();
        lines.add(describe(decision));
        if (report != null) {
            // The error line is the decision's own reason, said in the first line already
            lines.addAll(report.lines().subList(1, report.lines().size()));
        }
        return lines;
    }

    // A translation by its fragment's name, as a person knows it, not its binding's UUID
    private static String named(final Binding binding) {
        final String name = NullSafe.get(binding.getPipeline(), DocRef::getName);
        return name == null
                ? "binding " + binding.getUuid()
                : "\"" + name + "\"";
    }

    private static String percent(final double score) {
        return Math.round(score * 100) + "%";
    }

    private static List<StageVerdict> verdicts(final List<Verdict> verdicts) {
        final List<StageVerdict> mapped = new ArrayList<>();
        for (int step = 0; step < verdicts.size(); step++) {
            final Verdict verdict = verdicts.get(step);
            final List<StageScore> scores = verdict.judgements().stream()
                    .map(StepDetails::score)
                    .toList();
            mapped.add(new StageVerdict(step + 1, verdict.score(), verdict.gatesPassed(), scores));
        }
        return mapped;
    }

    private static StageScore score(final Judgement judgement) {
        return new StageScore(
                judgement.setting().getCheck(),
                judgement.score().value(),
                judgement.setting().getPassingValue(),
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
        return turns;
    }
}
