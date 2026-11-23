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

import stroom.learningflow.fragment.RecordShortfall;
import stroom.learningflow.fragment.ServedStream;
import stroom.learningflow.fragment.Shortfalls;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.Judgement;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.Check;
import stroom.pipeline.xml.event.EventList;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A variant's run over a whole stream, scored: what promotion, provisional binding, retraction and
 * relearning all decide on.
 *
 * @param records How many records the stream brought — its lines where it is text, its records by the
 *                boundary where one was settled, else the root's children of markup. The evidence the
 *                judgement rests on (A14), counted on the input so that a variant that drops records is
 *                judged, not excused.
 */
/// @param held The records that fell short, each as the parser cut it, where a full pass kept them
///             (design 04 §4): what the next question's candidates are held to. Empty otherwise.
record Judged(String output, List<Verdict> verdicts, double score, int records,
                      EventList events, List<StoredError> diagnostics,
                      Set<Integer> fellShort, List<String> held) {

    Judged(final String output, final List<Verdict> verdicts, final double score, final int records,
           final EventList events, final List<StoredError> diagnostics,
           final Set<Integer> fellShort) {
        this(output, verdicts, score, records, events, diagnostics, fellShort, List.of());
    }

    Judged(final String output, final List<Verdict> verdicts, final double score, final int records,
           final EventList events, final List<StoredError> diagnostics) {
        this(output, verdicts, score, records, events, diagnostics, null, List.of());
    }

    /// A stream served as it was read, stopped at the record that fell short (design 04 §3): what is
    /// known of it is the records judged before and including that one and the verdict on the one.
    /// No output, since nothing was held; the stream is not read again to learn what was already
    /// found. Its score is nothing: a stream with a record short of the bar did not clear it
    /// (A55), and a candidate that clears the floor must not be kept out by the mean of the records
    /// before the one that failed.
    static Judged shortOf(final ServedStream served, final RecordShortfall shortfall) {
        return new Judged(null, List.of(shortfall.verdict()), 0.0, served.records(),
                null, served.diagnostics(), Set.of(shortfall.index()));
    }

    /// A served stream whose **input** was not accounted for (Track 1 task 2, fix 1): the parser read
    /// nothing, or read records that became neither an event nor a declared skip, or reported errors.
    /// There is no output record to point at — the records went missing before anything could judge
    /// them — so there are no verdicts and no failing index, and the reason stands in their place as
    /// the evidence the next question opens with. Scores nothing, as a stream that lost records did.
    ///
    /// @param served What the run came to.
    /// @param reason What was not accounted for.
    /// @return The judgement.
    static Judged lostFrom(final ServedStream served, final String reason) {
        final List<StoredError> evidence = new ArrayList<>();
        evidence.add(new StoredError(Severity.ERROR, null, Stage.STAGE, reason));
        evidence.addAll(served.diagnostics());
        return new Judged(null, List.of(), 0.0, served.input().counted()
                ? served.input().records()
                : served.records(), null, List.copyOf(evidence), Set.of());
    }

    /// A held stream run in full by a session, with the records that fell short kept as evidence
    /// (A64): their verdicts, and as diagnostics what each was and became, which is what the next
    /// question opens with. Scores nothing, as a stream with a record short of the bar does.
    static Judged shortOf(final Shortfalls pass) {
        final List<Verdict> verdicts = new ArrayList<>();
        final List<StoredError> evidence = new ArrayList<>();
        final Set<Integer> indices = new TreeSet<>();
        for (final Shortfalls.Shortfall shortfall : pass.kept()) {
            verdicts.add(shortfall.verdict());
            indices.add(shortfall.index());
            evidence.add(new StoredError(Severity.ERROR, null, Stage.STAGE, "Record " + (shortfall.index() + 1)
                                                                    + " of the held stream fell short of the bar."
                                                                    + (shortfall.parsed() == null
                    ? ""
                    : " The record as the parser cut it:\n" + shortfall.parsed())
                                                                    + "\nWhat the transform made of it:\n"
                                                                    + shortfall.output()));
        }
        final List<String> held = pass.kept().stream()
                .map(Shortfalls.Shortfall::parsed)
                .filter(Objects::nonNull)
                .toList();
        return new Judged(null, verdicts, 0.0, pass.judged(), null, evidence, indices, held);
    }

    static Judged of(final List<Attempted> attempted, final Scorecard scorecard, final EventList events) {
        final List<Verdict> verdicts = attempted.stream().map(scorecard::judge).toList();
        final String output = attempted.isEmpty()
                ? null
                : attempted.get(attempted.size() - 1).result().output();
        final int records = attempted.isEmpty()
                ? 0
                : LearningPrefix.recordsBrought(attempted.get(0));
        // What the checks that passed still have to say goes on the error stream with what the
        // elements said: a translation that declared records skipped is not failing, and is exactly
        // what a person approving it has to be told about
        final List<StoredError> said = new ArrayList<>(diagnostics(attempted));
        verdicts.forEach(verdict -> said.addAll(verdict.notices()));
        return new Judged(output, verdicts, candidateScore(verdicts), records, events,
                List.copyOf(said));
    }


    /// What the elements said, in chain order. A run that is served rather than discarded has these
    /// put on the pipeline's error stream by whoever serves it (A20): the fragment runs under a
    /// receiver of its own, so that a candidate's complaints go to the model rather than to the
    /// operator, and the one run that is kept must not be silent for the same reason.
    private static List<StoredError> diagnostics(final List<Attempted> attempted) {
        return attempted.stream()
                .flatMap(step -> step.result().diagnostics().stream())
                .toList();
    }

    /// @return Whether every gate passed on every step of the chain, and every record of the stream
    ///         cleared the gates that judge records, which is the bar it will be served to (the one model
    ///         of checks: serving uses the same gates). There is no total to reach, so a check that is
    ///         advice never refuses a candidate.
    boolean clearsGates() {
        return !verdicts.isEmpty()
               && verdicts.stream().allMatch(Verdict::gatesPassed)
               && failingRecords().isEmpty();
    }

    /// @return The gates that failed, named for a person, e.g. "the Schema conformance gate (0.4 of 1.0)".
    String failedGates() {
        final List<String> failed = verdicts.stream()
                .flatMap(verdict -> verdict.judgements().stream())
                .filter(Judgement::failedGate)
                .map(judgement -> judgement.setting().getCheck().getName() + " ("
                                  + judgement.score().value() + " of " + judgement.setting().getPassingValue()
                                  + ")")
                .toList();
        if (failed.isEmpty()) {
            final int records = failingRecords().size();
            return records == 0
                    ? "with nothing to judge"
                    : "the gates that judge records on " + records + " record" + (records == 1
                            ? ""
                            : "s") + " of the stream";
        }
        return "the gate" + (failed.size() == 1
                        ? " "
                        : "s ") + String.join(", ", failed);
    }

    /// The records of the chain's output that fell short of the bar (design 04 §3, A55): the last
    /// step's, since that is what is emitted, and the scorers that judge records one by one judge a
    /// transform's records and not a parser's. Zero-based, in output order.
    Set<Integer> failingRecords() {
        if (fellShort != null) {
            return fellShort;
        }
        return verdicts.isEmpty()
                ? Set.of()
                : verdicts.get(verdicts.size() - 1).failingRecords();
    }

    /**
     * The candidate's score as people see it (the one model of checks, D3): the average of the checks that
     * ran on it, gates and advice alike, each counted once at its lowest value over the chain's steps. It
     * decides nothing: whether the candidate is promoted is its gates' to say. A candidate with no step
     * scores nothing.
     */
    private static double candidateScore(final List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return 0.0;
        }
        final Map<Check, Double> lowest = new EnumMap<>(Check.class);
        for (final Verdict verdict : verdicts) {
            for (final Judgement judgement : verdict.judgements()) {
                lowest.merge(judgement.setting().getCheck(), judgement.score().value(), Math::min);
            }
        }
        return lowest.values().stream().mapToDouble(Double::doubleValue).average().orElse(1.0);
    }
}
