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

package stroom.learningflow.stage;

import stroom.docref.DocRef;
import stroom.learningflow.extraction.PerRecord;
import stroom.learningflow.fragment.FragmentRunner;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.scoring.Scorer;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.RecordBoundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/// Judges what the stage runs, for both serving and learning: it runs a fragment as a pipeline and scores
/// it, re-runs a learned chain as the fragment would run it, builds the bar a served record is held to, and
/// checks whether all of a stream's input was accounted for.
public final class Judging {

    private final List<Scorer> scorers;
    private final TargetSchemas targetSchemas;
    private final FragmentRunner fragmentRunner;

    /// @param scorers        The installed scorers.
    /// @param targetSchemas  Resolves a document's target schema.
    /// @param fragmentRunner Runs a fragment in the same way the pipeline will.
    public Judging(final List<Scorer> scorers,
                   final TargetSchemas targetSchemas,
                   final FragmentRunner fragmentRunner) {
        this.scorers = List.copyOf(scorers);
        this.targetSchemas = targetSchemas;
        this.fragmentRunner = fragmentRunner;
    }

    Judged judge(final Scorecard scorecard,
                         final DocRef fragment,
                         final String input,
                         final RecordBoundary boundary) {
        // The events are taken here, straight after the run, and kept with the judgement they belong to.
        // Which run will be served is not known until the decision is made (a candidate may be run and
        // discarded, a serving binding run and kept), and the runner keeps only its last output, so
        // reading it anywhere else could return another run's events.
        final List<Attempted> attempted = fragmentRunner.run(fragment, input, boundary);
        return Judged.of(attempted, scorecard, fragmentRunner.lastOutput().orElse(null));
    }

    /// Judges a chain as it was *learned*: step by step through the element runners, before it has been
    /// written as a fragment and so before anything has run it as a pipeline.
    ///
    /// The judgement has no events, because nothing produced any. Whoever serves the stream gets them from
    /// [Stage#serve] once the fragment exists. Taking the runner's last output here instead would give this
    /// stream the events of whatever ran before it, such as a candidate rejected earlier in the same call,
    /// or an earlier stream in the same pipeline scope.
    static Judged asLearned(final List<Attempted> attempted, final Scorecard scorecard) {
        return Judged.of(attempted, scorecard, null);
    }

    /// Builds the bar a served record is held to: the Learning Flow's checks that judge records one at a
    /// time. Those that are gates hold a record back; those that are advice only count towards the rolling
    /// score. The checks that measure a whole stream (compile, coverage, yield) judged the candidate when it
    /// was promoted and have nothing to say about a single record.
    Scorecard recordBar(final LearningFlowDoc doc) {
        return Scorecard.of(doc.getChecks().stream()
                .filter(setting -> setting.getCheck().judgesRecords())
                .toList(), scorers, targetSchemas.of(doc).orElse(null));
    }

    /// Runs a learned chain over an input, step by step, in the same way the fragment will run it. If a step
    /// produces nothing, the rest are not run; a step that produces output with errors is followed by the
    /// next step, as [FragmentRunner] does.
    public static List<Attempted> rerun(final List<LearnedStep> chain,
                                         final String input,
                                         final RecordBoundary boundary) {
        final List<Attempted> attempted = new ArrayList<>();
        // Mimic the fragment's SplitFilter: from the first element that is not a parser, the chain is run
        // one record at a time, because that is how the fragment will run it.
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        String current = input;
        for (final LearnedStep step : chain) {
            final boolean first = !split && !step.runner().parser() && depth.isPresent();
            split = split || first;
            // The first element after the parser is given the parser's records; every element after that
            // is given what the one before it wrote for each record, which is one level deep. A fragment
            // has one SplitFilter, not one per element.
            final StepResult result = split
                    ? PerRecord.run(step.runner(), step.configuration(), current, first
                            ? depth.getAsInt()
                            : PerRecord.WRITTEN)
                    : step.runner().run(step.configuration(), current);
            final Attempted attempt = Attempted.of(step.runner(), current, result, boundary);
            attempted.add(attempt);
            if (attempt.result().output() == null) {
                break;
            }
            current = attempt.result().output();
        }
        return attempted;
    }

    /// Checks whether all of a served stream's **input** was accounted for, using the Learning Flow's own
    /// checks.
    ///
    /// Every other check judges the output. A record the parser could not read produces no event, so
    /// nothing judges it and the stream would be recorded as clean (the fault described in
    /// `docs/learning-flow-parser-drift-not-detected.md`). This is the only place that checks the input.
    ///
    /// **A stream of which the parser read nothing is deferred whatever the checks say.** That is not a
    /// quality judgement with a threshold; it means the translation does not work for this stream at all.
    /// Anything less (records lost, or a parser reporting records it could not read) **defers only if the
    /// relevant check is a gate**, in the same way as the record bar, so that what refuses a candidate
    /// while learning is also what refuses a stream while serving. A document that has made input coverage
    /// advice-only therefore goes on serving a feed its parser reads badly, and its rolling score falls
    /// instead, which is what a *Lenient* Learning Flow asks for.
    ///
    /// @param doc    The document.
    /// @param served The result of serving the stream.
    /// @return Why the stream should not be served as it is, or empty if it may be.
    static Optional<String> inputShortfall(final LearningFlowDoc doc, final ServedStream served) {
        if (!served.input().counted() || served.input().empty()) {
            // A fragment given records that something else split has no input of its own to account for,
            // and an empty stream is nobody's fault
            return Optional.empty();
        }
        if (served.input().records() == 0) {
            // Not a configurable check: a translation that reads nothing at all of a non-empty stream is
            // not translating it. This is the serving equivalent of the compile check, which is always a
            // gate for the same reason. Leaving it to the coverage check would miss the fault on documents
            // that have coverage switched off.
            return Optional.of("The parser read no records from this stream: the translation no longer "
                               + "reads this feed.");
        }
        final int lost = served.lost();
        if (lost > 0 && gated(doc, Check.COMPLETENESS)) {
            return Optional.of(lost + " of " + served.input().records() + " record(s) the parser read "
                               + "became neither an event nor a declared skip.");
        }
        final CheckSetting coverage = setting(doc, Check.COVERAGE);
        if (coverage != null && coverage.isGate() && served.coverage() < coverage.getPassingValue()) {
            return Optional.of("The parser read " + served.input().records() + " record(s) and reported "
                               + served.input().errors() + " it could not, which is "
                               + Math.round(served.coverage() * 100) + "% of the stream against the "
                               + Math.round(coverage.getPassingValue() * 100) + "% this Learning Flow "
                               + "asks for.");
        }
        return Optional.empty();
    }

    /// @param doc   The document.
    /// @param check The check.
    /// @return Whether the Learning Flow makes the check a gate, so that it may hold a stream back.
    private static boolean gated(final LearningFlowDoc doc, final Check check) {
        final CheckSetting setting = setting(doc, check);
        return setting != null && setting.isGate();
    }

    /// @param doc   The document.
    /// @param check The check.
    /// @return The Learning Flow's setting for the check, or the default if the document has none.
    private static CheckSetting setting(final LearningFlowDoc doc, final Check check) {
        return CheckSettings.complete(doc.getChecks()).stream()
                .filter(candidate -> candidate.getCheck() == check)
                .findFirst()
                .orElse(null);
    }
}
