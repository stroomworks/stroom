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

package stroom.learningflow.stage;

import stroom.learningflow.extraction.RecordSplit;
import stroom.learningflow.learning.LearnedStep;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.Target;
import stroom.learningflow.learning.TargetChecks;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.RegressionSet.Kind;
import stroom.util.shared.NullSafe;
import stroom.util.shared.StoredError;
import stroom.util.shared.TextRange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.IntStream;

/// Builds regression corpus entries and checks candidates against them. An entry is a record a session
/// learned for, held as the single-record document the transform is given, with the output the promoted
/// candidate made of it. A later candidate is run over each entry and must do no worse on it.
final class Corpus {

    private static final String UNKNOWN_DETAIL = "*[local-name() = 'EventDetail']/*[local-name() = 'Unknown']";

    private Corpus() {
    }

    /// The chain from its first element that is not a parser, which is what an entry is run through. An
    /// entry is a record the parser has already split out, so the parser is not run over it again.
    static List<LearnedStep> transforms(final List<LearnedStep> chain) {
        return chain.stream().dropWhile(step -> step.runner().parser()).toList();
    }

    /// Builds the entries a promotion appends: the records of `text` that were learned for, each with what
    /// the candidate makes of it.
    ///
    /// @param chain     The candidate's chain.
    /// @param boundary  What one record is, as the candidate settled it; null if it did not settle one.
    /// @param text      The stream, or the sample of it the model was shown.
    /// @param which     The indices of the records that were learned for, in the parser's order; null for
    ///                  every record of the text, as in a first learning, where every record was learned for.
    /// @param targets   The target events the flow settled for each kind of record, if any.
    /// @param score     The candidate's score on the stream it was accepted from.
    /// @param attemptId The session that accepted them, if one was open.
    /// @param now       The time of acceptance.
    /// @param atMost    The document's cap on corpus entries; only the first this many are returned.
    /// @return The entries, in the parser's order; empty if the text has no records.
    static List<Accepted> entries(final List<LearnedStep> chain,
                                  final RecordBoundary boundary,
                                  final String text,
                                  final Collection<Integer> which,
                                  final List<Target> targets,
                                  final double score,
                                  final Long attemptId,
                                  final long now,
                                  final int atMost) {
        if (chain.isEmpty() || text == null) {
            return List.of();
        }
        final LearnedStep first = chain.get(0);
        final String parsed;
        final List<TextRange> spans;
        if (first.runner().parser()) {
            final StepResult result = first.runner().run(first.configuration(), text);
            if (result.output() == null) {
                return List.of();
            }
            parsed = result.output();
            spans = NullSafe.list(result.recordRanges());
        } else {
            // A chain with no parser is given markup already, so the text is already in parsed form.
            parsed = text;
            spans = List.of();
        }
        final int depth = boundary == null
                ? 1
                : boundary.splitDepth().orElse(1);
        final List<String> records = RecordSplit.split(parsed, depth);
        // If the parser reported where each record began and ended, the record's raw text is kept beside
        // it, along with the text before the first record and after the last. That is enough to put the
        // records back through the parser as a stream. Parsers that don't report positions (the JSON and
        // XML parsers) leave these null.
        final boolean located = !records.isEmpty()
                                && spans.size() == records.size()
                                && spans.stream().allMatch(Corpus::placed);
        final String preamble = located
                ? text.substring(0, start(text, spans.get(0)))
                : null;
        final String postamble = located
                ? text.substring(end(text, spans.get(spans.size() - 1)))
                : null;
        final List<LearnedStep> transforms = transforms(chain);
        final Collection<Integer> indices = which == null
                ? IntStream.range(0, records.size()).boxed().toList()
                : new TreeSet<>(which);
        final List<Accepted> entries = new ArrayList<>();
        for (final int index : indices) {
            if (index < 0 || index >= records.size()) {
                continue;
            }
            if (entries.size() == atMost) {
                break;
            }
            final String record = records.get(index);
            final String output = outputFor(transforms, record, boundary);
            entries.add(new Accepted(record,
                    located
                            ? Inputs.cut(text, spans.get(index)).orElse(null)
                            : null,
                    preamble,
                    postamble,
                    output,
                    kindOf(output),
                    score,
                    attemptId,
                    now,
                    targetFor(targets, record, located
                            ? Inputs.cut(text, spans.get(index)).orElse(null)
                            : null)));
        }
        return entries;
    }

    /// Builds entries for the records that fell short in a full pass over the held stream, each as the
    /// parser split it, with what the candidate makes of it. These records may lie beyond the prefix the
    /// session has in memory (they were read from the stream as the fragment ran over it), so no entry has
    /// its raw text or the text around it, and an improvement session cannot be learned from them. A
    /// relearning from a stream can.
    ///
    /// @param held The records, each as a single-record document.
    static List<Accepted> heldEntries(final List<LearnedStep> chain,
                                      final RecordBoundary boundary,
                                      final List<String> held,
                                      final List<Target> targets,
                                      final double score,
                                      final Long attemptId,
                                      final long now,
                                      final int atMost) {
        final List<LearnedStep> transforms = transforms(chain);
        final List<Accepted> entries = new ArrayList<>();
        for (final String record : held) {
            if (entries.size() == atMost) {
                break;
            }
            final String output = outputFor(transforms, record, boundary);
            entries.add(new Accepted(record, null, null, null, output, kindOf(output), score, attemptId, now,
                    targetFor(targets, record, null)));
        }
        return entries;
    }

    /// Gets what a chain makes of one entry's record: the output of its transform steps. For a chain that
    /// is only a parser, whose output is its records, it is the record itself, so that a parser-only binding
    /// is held to what it emits rather than to nothing.
    static String outputFor(final List<LearnedStep> transforms, final String record, final RecordBoundary boundary) {
        return transforms.isEmpty()
                ? record
                : lastOutput(Judging.rerun(transforms, record, boundary));
    }

    /// Checks whether a candidate did worse on an entry than the accepted output. Its output must clear the
    /// bar, a record that was translated must not become an Unknown, and a record that yielded an event must
    /// still yield one.
    ///
    /// @param entry    The corpus entry.
    /// @param verdicts The candidate's verdicts over the entry's record.
    /// @param output   What the candidate made of the record.
    /// @return Why it fell short, or empty if it did not.
    static Optional<String> shortfall(final Accepted entry, final List<Verdict> verdicts, final String output) {
        final Kind now = kindOf(output);
        if (entry.kind() == Kind.TRANSLATION && now == Kind.UNKNOWN) {
            return Optional.of("turns a record that translated into an Unknown");
        }
        if (entry.kind() != Kind.NONE && now == Kind.NONE) {
            return Optional.of("makes nothing of a record that yielded an event");
        }
        for (final Verdict verdict : verdicts) {
            if (!verdict.gatesPassed() || !verdict.failingRecords().isEmpty()) {
                return Optional.of("falls short of the bar on a record it was accepted on: "
                                   + verdict.feedback().stream()
                                           .map(StoredError::getMessage)
                                           .findFirst()
                                           .orElse("a gate failed"));
            }
        }
        return Optional.empty();
    }

    /// Describes an entry for a person, by its position in the corpus and the session that accepted it.
    static String name(final int ordinal, final int of, final Accepted entry) {
        return "record " + ordinal + " of " + of + " in the corpus"
               + (entry.attemptId() == null
                ? ""
                : ", accepted by attempt " + entry.attemptId());
    }

    /// Reassembles the corpus as a stream, for an improvement session, which has no stream of its own: the
    /// text before the first record, every record's raw text, then the text after the last (both taken
    /// from the first entry). Empty if any entry lacks its raw text, since such a record cannot be put back
    /// through a parser.
    static Optional<String> stream(final List<Accepted> entries) {
        if (entries.isEmpty() || !entries.stream().allMatch(Accepted::runnable)) {
            return Optional.empty();
        }
        final StringBuilder stream = new StringBuilder(NullSafe.string(entries.get(0).preamble()));
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                stream.append('\n');
            }
            stream.append(entries.get(i).text());
        }
        return Optional.of(stream.append(NullSafe.string(entries.get(0).postamble())).toString());
    }

    /// Classifies an output as no event, a marked Unknown (if any record's detail is `Unknown`), or a
    /// translation.
    static Kind kindOf(final String output) {
        if (output == null || output.isBlank()) {
            return Kind.NONE;
        }
        final Optional<OutputRecords> parsed = OutputRecords.parse(output);
        if (parsed.isEmpty() || parsed.get().records().isEmpty()) {
            return Kind.NONE;
        }
        return parsed.get().records().stream().anyMatch(record -> parsed.get().holds(record, UNKNOWN_DETAIL))
                ? Kind.UNKNOWN
                : Kind.TRANSLATION;
    }

    private static String lastOutput(final List<Attempted> attempted) {
        return attempted.isEmpty()
                ? null
                : attempted.get(attempted.size() - 1).result().output();
    }

    /// Finds the target event settled for this record's kind. A target names a representative record as the
    /// split produced it (its text for a text format, otherwise its markup), and a record's kind is found by
    /// the same skeleton the flow used to choose representatives.
    private static String targetFor(final List<Target> targets, final String record, final String text) {
        if (targets.isEmpty()) {
            return null;
        }
        final String kind = TargetChecks.kindOf(text == null
                ? record
                : text);
        return targets.stream()
                .filter(target -> kind.equals(TargetChecks.kindOf(target.record())))
                .findFirst()
                .flatMap(Target::event)
                .orElse(null);
    }

    private static boolean placed(final TextRange span) {
        return span != null && span.getFrom() != null && span.getTo() != null;
    }

    /// The offset of a span's first character in the text.
    static int start(final String text, final TextRange span) {
        return offset(text, span.getFrom().getLineNo(), span.getFrom().getColNo() - 1);
    }

    /// The offset just past a span's last character. The end column is the last character, so it is
    /// included.
    static int end(final String text, final TextRange span) {
        return offset(text, span.getTo().getLineNo(), span.getTo().getColNo());
    }

    private static int offset(final String text, final int lineNo, final int column) {
        int lineStart = 0;
        for (int line = 1; line < lineNo; line++) {
            final int newline = text.indexOf('\n', lineStart);
            if (newline < 0) {
                return text.length();
            }
            lineStart = newline + 1;
        }
        return Math.min(text.length(), lineStart + Math.max(column, 0));
    }
}
