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

/// Builds the contents of the stepper's Smart Pipeline pane from what the stage decided.
///
/// It is a pure mapping from [StageRun] (which holds everything the scenario tests check, and which is
/// also what a person wants to see) onto the shared form the client renders. The one thing it adds is
/// whether this was a step rather than a real run, because a dry run's decision is what the stage *would*
/// have done and the pane has to say so.
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
                // Attached per record by Supervision, since the decision applies to the whole stream but
                // what each of the fragment's elements made of the input is per record.
                List.of());
    }

    /// The stream's feed and type, in that order, where it has them, for the pane to show.
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

    /// Describes a decision in one line.
    ///
    /// **Every** kind of decision has a line, because a display that shows nothing for unexpected outcomes
    /// is worse than none. The error stream and the stepper pane both use this method so that they say
    /// the same thing, and so that there is only one switch in which an outcome could be forgotten.
    ///
    /// @param decision What was decided.
    /// @return The description.
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

    /// Describes a decision as lines for the error stream: the [#describe] line, followed, where the
    /// decision stopped a learning session with a report, by the report's labelled details and
    /// suggestions ([StopReport#lines()]).
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
            // The report's first line is the decision's own reason, which describe() has already given.
            lines.addAll(report.lines().subList(1, report.lines().size()));
        }
        return lines;
    }

    // Names a translation by its fragment's name, as a person knows it, rather than its binding's UUID.
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

    /// Maps the transcript to the form the Supervisor uses for a turn, so that a person reading one in the
    /// stepper and one in the Supervisor sees the same thing.
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
                    // Who answered is recorded on the attempt. A step reads a transcript the stage has just
                    // made, and everything in it was answered by the model.
                    null,
                    exchange.outcome(),
                    exchange.score()));
        }
        return turns;
    }
}
