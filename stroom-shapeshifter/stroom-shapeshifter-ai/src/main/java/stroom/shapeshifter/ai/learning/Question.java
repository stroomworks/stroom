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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.shared.QuestionKind;
import stroom.util.shared.StoredError;

import java.util.List;

/**
 * The typed questions of the A21 conversation. Each carries what the model needs to answer it and nothing
 * about how it is rendered into a prompt, which is the {@link Advisor}'s concern.
 */
public sealed interface Question {

    /// What joins a question's identity to how much it had been told, in its [#summary()].
    String SHORTFALLS_AFTER = ", after ";

    /**
     * Diagnostics from the previous answer to the same question, if it was refused or failed. Empty the
     * first time a question is asked — unless the attempt is relearning a bound learner (A29), when the
     * first asking of each question carries why the incumbent fell short.
     */
    List<StoredError> feedback();

    /// Which kind of question this is: what a recorded turn names it (A28). Static, because two of these
    /// records carry a `kind` of their own and a question's kind is not theirs.
    static QuestionKind kindOf(final Question question) {
        return switch (question) {
            case Chain ignored -> QuestionKind.CHAIN;
            case Split ignored -> QuestionKind.SPLIT;
            case TargetFor ignored -> QuestionKind.TARGET;
            case Configuration configuration -> configuration.admitting()
                    ? QuestionKind.ADMIT
                    : QuestionKind.CONFIGURE;
        };
    }

    /// This question in one line: the kind, what it was about, and how much the step had been told when
    /// it was asked. This is what a turn records as the question asked (A28), which a person reads and a
    /// resumed attempt compares its re-walk against (A45) — everything in it is re-derived by walking the
    /// same flow over the same sample, so two walks that have reached the same place write the same line.
    ///
    /// Not the rendered prompt: that carries the stream's own text, which may not be stored until it is
    /// redacted (A17, A38), and which the raw exchange is audited with by `stroom-ai` in any case. The
    /// rendered prompt joins the record when redaction is built.
    default String summary() {
        return identity()
               + (feedback().isEmpty()
                ? ""
                : SHORTFALLS_AFTER + feedback().size() + " shortfall(s)");
    }

    /// The kind and what it is about, which is what a resumed attempt compares its re-walk against (A45):
    /// an answer kept is an answer to this, whatever the step had been told by then. How much it had been
    /// told is the record's to show and not the comparison's to make, since a session that bound a
    /// fragment of its own and then parked is re-walked over the shortfalls of what it bound, which its
    /// first questions, asked before it, were not.
    default String identity() {
        final String about = switch (this) {
            case Chain chain -> "choose from " + chain.allowedElements();
            case Split split -> "what one record is, for " + split.elementType();
            case TargetFor target -> "what record " + target.kind() + " of " + target.total() + " becomes";
            case Configuration configuration -> configuration.elementType() + " "
                                                + configuration.documentType();
        };
        return kindOf(this).getDisplayValue() + ": " + about;
    }

    /// The [#identity()] of a question from the [#summary()] a turn recorded.
    static String identityOf(final String summary) {
        final int after = summary.lastIndexOf(SHORTFALLS_AFTER);
        return after > 0 && summary.endsWith(" shortfall(s)")
                ? summary.substring(0, after)
                : summary;
    }

    /**
     * Which chain of elements fits the sample, chosen from the document's allowed elements (A21 step 1).
     * The sample's key values are the cheap half of the answer (A29): a {@code Format} of JSON, where the
     * key includes it, names the parser before the text is read.
     */
    record Chain(Sample sample,
                 List<String> allowedElements,
                 List<StoredError> feedback) implements Question {

    }

    /**
     * What one record is in this input (A31): asked before anything about meaning, for every kind of
     * input, and answered without a target. For raw text the reply is a Data Splitter configuration
     * that cuts the input into records and emits each record's whole text as one field — the boundary
     * and nothing else — judged by coverage and yield alone; for XML the element that is one record
     * (A35); for JSON the key of the array whose items are records, or {@code root}.
     */
    record Split(Sample sample,
                 String elementType,
                 String documentType,
                 InputKind kind,
                 List<StoredError> feedback) implements Question {

    }

    /**
     * What one kind of record should become (A31): one representative record, as the split cut it, put
     * with the schema's rules and the document's instructions; the reply is the event as a single
     * document, or {@code none} for a kind that yields no event. Validated at once by the scorers of
     * meaning, so the re-ask carries the shortfall of the document the model itself wrote.
     *
     * @param record The representative record's text.
     * @param kind   Which of the sample's record kinds this is, one-based.
     * @param total  How many kinds the sample has.
     */
    record TargetFor(Sample sample,
                     String record,
                     int kind,
                     int total,
                     List<StoredError> feedback) implements Question {

    }

    /// How many records the stream holds and how many kinds they are of, as the split found them.
    ///
    /// @param kinds How many kinds of record the stream carries, by the discrimination the split uses:
    ///              one where every record is alike, more where a feed reports several things.
    record Records(int total, int kinds) {

        public static final Records UNKNOWN = new Records(0, 0);
    }

    /**
     * The configuration document for one element of the chain (A21 step 2). Carries the real input that
     * element will receive — the sample for the first element, the previous element's output after that —
     * and the previous configuration if this is a re-ask.
     *
     * @param elementType           The pipeline element type, e.g. {@code XSLTFilter}.
     * @param documentType          The document type the element consumes, e.g. {@code XSLT}.
     * @param sample                The input sample and headers the whole attempt is about.
     * @param input                 What this element will actually be given to process.
     * @param previousConfiguration The configuration this question is asking to improve on, or null.
     * @param split                 The record boundary already settled (A31, A35): the configuration that
     *                              cuts it, for the parser that must extract within it, or the element
     *                              that is one record, for a transform over XML; null where the flow has
     *                              no split or it does not concern this element.
     * @param targets               What each kind of record must become (A31): the parser's records must
     *                              carry every value these need, the transform must produce exactly
     *                              these. Empty where the conversation has no targets.
     * @param oneRecord             Whether the input shown is one record rather than the whole stream:
     *                              the fragment's `SplitFilter` gives this element one record at a time
     *                              (§12 item 25), so it is asked for a configuration that handles one,
     *                              and told so, as a person writing a Stroom stylesheet is shown one.
     * @param records               How many records the stream holds, and how many kinds they are of,
     *                              where the input is one of them: one record shown is one record's
     *                              worth of evidence, and a configuration written for it is run over all
     *                              of them, so what it is not being shown is said rather than hidden.
     * @param otherKinds            One record of each *other* kind the split found (A47), where the flow
     *                              has not yet settled its targets. Saying a second kind exists is not
     *                              the same as showing it, and a configuration written from a login
     *                              alone drops the logouts. Empty where the targets already show a
     *                              record of each kind beside the event it must become, and where the
     *                              stream carries one kind. <b>Not necessarily every other kind</b>: the
     *                              representatives are capped, and {@link Records#kinds()} is what says
     *                              how many there are, so a question built from this must not promise
     *                              the model it has seen them all.
     */
    record Configuration(String elementType,
                         String documentType,
                         Sample sample,
                         String input,
                         String previousConfiguration,
                         Boundary split,
                         List<Target> targets,
                         boolean oneRecord,
                         Records records,
                         List<String> otherKinds,
                         boolean admitting,
                         List<StoredError> feedback) implements Question {

        public Configuration {
            targets = List.copyOf(targets);
            otherKinds = List.copyOf(otherKinds);
        }
    }
}
