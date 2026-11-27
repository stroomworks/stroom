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

/// The scored result of running a candidate or binding over a whole stream, which promotion, provisional
/// binding, retraction and relearning all decide on.
///
/// @param records   How many records the stream brought: its lines if it is text, its records by the record
///                  boundary if one was settled, otherwise the children of the root element. This is the
///                  evidence the judgement rests on. It is counted on the input, so that a candidate that
///                  drops records is judged on them rather than excused.
/// @param fellShort The indices of the records that fell short, if known from a served or full pass; null
///                  to take them from the last verdict.
/// @param held      The records that fell short, each as the parser split it, if a full pass kept them.
///                  The next question's candidates are held to these. Empty otherwise.
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

    /// Judges a stream that was served as it was read and stopped at the first record that fell short.
    /// All that is known is the number of records judged up to and including that one, and the verdict on
    /// it. There is no output, since nothing was kept, and the stream is not read again. It scores zero: a
    /// stream with a record short of the bar did not clear it, and a candidate that clears the floor must
    /// not be kept out by the mean score of the records before the one that failed.
    static Judged shortOf(final ServedStream served, final RecordShortfall shortfall) {
        return new Judged(null, List.of(shortfall.verdict()), 0.0, served.records(),
                null, served.diagnostics(), Set.of(shortfall.index()));
    }

    /// Judges a served stream whose **input** was not all accounted for: the parser read nothing, or read
    /// records that became neither an event nor a declared skip, or reported errors. There is no output
    /// record to point at, because the records went missing before anything could judge them, so there are
    /// no verdicts and no failing index. The reason is used instead, as the evidence the next question
    /// opens with. It scores zero, because the stream lost records.
    ///
    /// @param served The result of the run.
    /// @param reason A description of what was not accounted for.
    /// @return The judgement.
    static Judged lostFrom(final ServedStream served, final String reason) {
        final List<StoredError> evidence = new ArrayList<>();
        evidence.add(new StoredError(Severity.ERROR, null, Stage.STAGE, reason));
        evidence.addAll(served.diagnostics());
        return new Judged(null, List.of(), 0.0, served.input().counted()
                ? served.input().records()
                : served.records(), null, List.copyOf(evidence), Set.of());
    }

    /// Judges a held stream that a session ran in full, keeping the records that fell short as evidence:
    /// their verdicts, and diagnostics showing what each record was and what it became, which the next
    /// question opens with. It scores zero, because a record fell short of the bar.
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
        // Notices from checks that passed go on the error stream with the elements' diagnostics. For
        // example, a translation that declared records skipped is not failing, but a person approving it
        // needs to be told
        final List<StoredError> said = new ArrayList<>(diagnostics(attempted));
        verdicts.forEach(verdict -> said.addAll(verdict.notices()));
        return new Judged(output, verdicts, candidateScore(verdicts), records, events,
                List.copyOf(said));
    }


    /// The elements' diagnostics, in chain order. When a run is served rather than discarded, whoever serves
    /// it puts these on the pipeline's error stream. The fragment runs under its own error receiver, so that
    /// a candidate's errors go to the model rather than the operator; the run that is served must therefore
    /// report its errors explicitly, or they would be lost.
    private static List<StoredError> diagnostics(final List<Attempted> attempted) {
        return attempted.stream()
                .flatMap(step -> step.result().diagnostics().stream())
                .toList();
    }

    /// @return Whether every gate passed on every step of the chain, and every record of the stream cleared
    ///         the gates that judge records, which is the bar it will be served against (serving uses the
    ///         same gates). There is no total score to reach, so a check that is only advice never refuses
    ///         a candidate.
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

    /// The records of the chain's output that fell short of the bar. These are taken from the last step,
    /// since that is what is emitted, and the scorers that judge records one by one judge a transform's
    /// records rather than a parser's. Zero-based, in output order.
    Set<Integer> failingRecords() {
        if (fellShort != null) {
            return fellShort;
        }
        return verdicts.isEmpty()
                ? Set.of()
                : verdicts.get(verdicts.size() - 1).failingRecords();
    }

    /// The candidate's score as shown to people: the average of the checks that ran on it, gates and advice
    /// alike, each counted once at its lowest value over the chain's steps. It decides nothing; only the
    /// gates decide whether the candidate is promoted. A candidate with no steps scores zero.
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
