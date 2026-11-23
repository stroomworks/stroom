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

package stroom.shapeshifter.ai.stage;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.extraction.PerRecord;
import stroom.shapeshifter.ai.fragment.FragmentRunner;
import stroom.shapeshifter.ai.fragment.ServedStream;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.TargetSchemas;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Scorer;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckSettings;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/// How a stage judges what it runs, shared by serving and learning (simplification option iii): a
/// fragment run as a pipeline and scored, a learned chain re-run as the fragment would run it, the bar a
/// served record is held to, and whether a stream's input accounted for itself. Taken out of [Stage].
public final class Judging {

    private final List<Scorer> scorers;
    private final TargetSchemas targetSchemas;
    private final FragmentRunner fragmentRunner;

    /// @param scorers        The installed scorers.
    /// @param targetSchemas  Resolves a document's target schema.
    /// @param fragmentRunner Runs a fragment as the pipeline will.
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
        // The events are taken here, in the call that made the run, and carried with the judgement they
        // belong to. Which of a call's runs is the one being served is not known until the decision is
        // made — a candidate is run and may be discarded, an incumbent is run and may be kept — and the
        // runner keeps only the last, so reading them anywhere else reads somebody else's stream.
        final List<Attempted> attempted = fragmentRunner.run(fragment, input, boundary);
        return Judged.of(attempted, scorecard, fragmentRunner.lastOutput().orElse(null));
    }

    /// A chain judged as it was *learned*: step by step over the element runners, before it has been
    /// written as a fragment and so before anything has run it as a pipeline.
    ///
    /// It carries no events, because there are none to carry. Whoever serves the stream asks for them
    /// with [#stream] once the fragment exists. Taking the runner's last output here instead would hand
    /// this stream the events of whatever ran before it — a variant tried and rejected earlier in this
    /// very call, or an earlier record of the same pipeline scope.
    static Judged asLearned(final List<Attempted> attempted, final Scorecard scorecard) {
        return Judged.of(attempted, scorecard, null);
    }

    /// The bar a record is held to (A55): the Learning Flow's checks that judge records one at a time. Those
    /// that are gates hold a record back; those that are advice only count towards the rolling score. The
    /// checks that measure a stream — compile, coverage, yield — judged the candidate when it was promoted
    /// and have nothing to say about one record.
    Scorecard recordBar(final ShapeshifterAiDoc doc) {
        return Scorecard.of(doc.getChecks().stream()
                .filter(setting -> setting.getCheck().judgesRecords())
                .toList(), scorers, targetSchemas.of(doc).orElse(null));
    }

    /**
     * The chain over an input, step by step. A step that produced nothing leaves the rest unrun; one that
     * produced output with errors is followed, as {@link FragmentRunner} follows it.
     */
    public static List<Attempted> rerun(final List<LearnedStep> chain,
                                         final String input,
                                         final RecordBoundary boundary) {
        final List<Attempted> attempted = new ArrayList<>();
        // What the fragment's SplitFilter will give the transform (§12 item 25): from the first element
        // that is not a parser, the chain is run one record at a time, because that is how it will run.
        final OptionalInt depth = boundary == null
                ? OptionalInt.empty()
                : boundary.splitDepth();
        boolean split = false;
        String current = input;
        for (final LearnedStep step : chain) {
            final boolean first = !split && !step.runner().parser() && depth.isPresent();
            split = split || first;
            // The first element after the parser is given the parser's records; every element after that
            // is given what the one before it wrote for each record, which is one record deep. A fragment
            // carries one SplitFilter, not one per element.
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

    /// What a served stream's **input** says, judged by the Learning Flow's own checks (Track 1 task 2,
    /// fix 1).
    ///
    /// Every other check judges what came out. A record the parser could not read becomes no event, so
    /// nothing judges it and the stream is recorded as clean — which is the fault in
    /// `docs/shapeshifter-ai-parser-drift-not-detected.md`. This is the only place that asks what went
    /// in.
    ///
    /// **A stream the parser read nothing of is deferred whatever the checks say.** That is not a
    /// quality judgement with a threshold; it is the translation not working here at all, and it is the
    /// fault the dev instance showed. Everything short of that — records lost, or a parser reporting
    /// records it could not read — **defers only on a gate**, exactly as the record bar works (A55) and
    /// as the one model of checks requires: what refuses a candidate while it is learned is what refuses
    /// a stream while it is served. So a document that has turned input coverage down to advice goes on
    /// serving a feed its parser reads badly, and its rolling score falls instead — which is what a
    /// *Lenient* Learning Flow is asking for.
    ///
    /// @param doc    The document.
    /// @param served What the run came to.
    /// @return Why the stream should not be served as it stands, or empty.
    static Optional<String> inputShortfall(final ShapeshifterAiDoc doc, final ServedStream served) {
        if (!served.input().counted() || served.input().empty()) {
            // A fragment handed records somebody else cut has no input of its own to account for, and an
            // empty stream is nobody's fault
            return Optional.empty();
        }
        if (served.input().records() == 0) {
            // Not a check, and not a threshold: a translation that reads nothing at all of a stream that
            // holds something is not translating it. The compile gate is fixed for the same reason — a
            // candidate that does not compile is not a candidate — and this is its serving twin. Leaving
            // it to the coverage check would have left the fault uncaught on the very document the dev
            // instance ran, which has coverage off.
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
    /// @return Whether the Learning Flow makes it a gate, so that it may hold a stream back.
    private static boolean gated(final ShapeshifterAiDoc doc, final Check check) {
        final CheckSetting setting = setting(doc, check);
        return setting != null && setting.isGate();
    }

    /// @param doc   The document.
    /// @param check The check.
    /// @return The Learning Flow's setting for it, filled in with the default where it names none.
    private static CheckSetting setting(final ShapeshifterAiDoc doc, final Check check) {
        return CheckSettings.complete(doc.getChecks()).stream()
                .filter(candidate -> candidate.getCheck() == check)
                .findFirst()
                .orElse(null);
    }
}
