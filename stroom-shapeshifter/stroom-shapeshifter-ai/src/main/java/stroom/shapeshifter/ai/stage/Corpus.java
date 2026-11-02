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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.extraction.RecordSplit;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.Target;
import stroom.shapeshifter.ai.learning.TargetChecks;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.NullSafe;
import stroom.util.shared.StoredError;
import stroom.util.shared.TextRange;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.IntStream;

/// What the corpus is made of and held to (design 04 §4.1, A56, A66): the records a session learned for,
/// each as the one document the transform is given, with the output the candidate made of it; and the
/// gate that runs a later candidate over each and says whether it fell short.
final class Corpus {

    private static final String UNKNOWN_DETAIL = "*[local-name() = 'EventDetail']/*[local-name() = 'Unknown']";

    private Corpus() {
    }

    /// The chain from its first element that is not a parser: what an entry is run through. An entry is a
    /// record the parser already cut, so the parser is not run again over it.
    static List<LearnedStep> transforms(final List<LearnedStep> chain) {
        return chain.stream().dropWhile(step -> step.runner().parser()).toList();
    }

    /// The entries a promotion appends: the records of `text` that were learned for, each with what the
    /// candidate makes of it.
    ///
    /// @param chain     The candidate's chain.
    /// @param boundary  What one record is, as the candidate settled it; null where it did not.
    /// @param text      The stream, or the sample of it the model was shown.
    /// @param which     The indices of the records that were learned for, in the parser's order; null for
    ///                  every record of the text, which is a first learning, where every record was.
    /// @param targets   What the flow said each kind of record should become (A31), where it settled any.
    /// @param score     The candidate's score on the stream it was accepted from.
    /// @param attemptId The attempt that accepted them, where one was open.
    /// @param now       When.
    /// @param atMost    The document's cap (A63): no more than this many, the first of them.
    /// @return The entries, in the parser's order; none where the text has no records.
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
            // A chain with no parser is given markup already (A35): the text is the parsed form.
            parsed = text;
            spans = List.of();
        }
        final int depth = boundary == null
                ? 1
                : boundary.splitDepth().orElse(1);
        final List<String> records = RecordSplit.split(parsed, depth);
        // Where the parser said where each record began and ended, the record's own text is kept beside
        // it, with what the stream wrapped its records in: enough to put the records back through the
        // parser as a stream. A parser that does not say — the JSON and XML parsers — leaves them null.
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

    /// The entries for the records a full pass kept (design 04 §4, A56): each as the parser cut it, with what
    /// the candidate makes of it. These records lie beyond the text the session has — they were read from
    /// the stream as the fragment ran over it — so no record carries its own text or what the stream
    /// wrapped it in, and an improvement (A46) cannot be learned from them; a relearning from a stream can.
    ///
    /// @param held The records, each as one document of its own.
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

    /// What a chain makes of one entry's record: its transform steps' output, or — for a chain that is a
    /// parser alone, whose output is its records — the record itself, so that a parser-only binding is held
    /// to what it emits and not to nothing.
    static String outputFor(final List<LearnedStep> transforms, final String record, final RecordBoundary boundary) {
        return transforms.isEmpty()
                ? record
                : lastOutput(Stage.rerun(transforms, record, boundary));
    }

    /// Whether a candidate fell short on an entry (A66): its output must clear the bar, a translated
    /// record must not become an Unknown, and a record that yielded something must still yield something.
    ///
    /// @param entry    The entry.
    /// @param verdicts The candidate's verdicts over the entry's record.
    /// @param output   What the candidate made of it.
    /// @return Why it fell short, or empty where it did not.
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

    /// How an entry is named to a person: by its place in the corpus and the attempt that accepted it.
    static String name(final int ordinal, final int of, final Accepted entry) {
        return "record " + ordinal + " of " + of + " in the corpus"
               + (entry.attemptId() == null
                ? ""
                : ", accepted by attempt " + entry.attemptId());
    }

    /// The corpus put back together as a stream, for a session that has no stream of its own (A46):
    /// what the stream wrapped its records in, then every record as it was received. Empty where any
    /// entry's parser did not say where it cut, since such a record cannot be put back through one.
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

    /// What an output is: nothing, an admission, or a translation.
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

    /// The target settled for this record's kind (A31): a target names a representative record as the
    /// split yielded it — its text for a text format, its markup otherwise — and a record is of its kind
    /// by the same skeleton the flow chose representatives by.
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

    /// The offset just past a span's last character: the end column names the last character, so it is
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
