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

package stroom.learningflow.learning;

import stroom.learningflow.shared.QuestionKind;
import stroom.util.shared.StoredError;

import java.util.List;

/// The kinds of question a learning session asks the model. Each carries what the model needs to answer it,
/// but nothing about how it is worded in a prompt, which is the job of [QuestionText] and the [Advisor].
public sealed interface Question {

    /// The text that joins a question's identity to its count of shortfalls in its [#summary()].
    String SHORTFALLS_AFTER = ", after ";

    /// Diagnostics from the previous answer to the same question, if it was refused or failed.
    ///
    /// Empty the first time a question is asked, unless the attempt was opened with feedback (for example,
    /// when relearning a learner whose current binding fell short), in which case the first asking of each
    /// question carries why it fell short.
    ///
    /// @return The diagnostics, or an empty list.
    List<StoredError> feedback();

    /// Returns which kind of question this is, as a recorded turn names it. It is static rather than an
    /// instance method because two of these records already have a `kind` component that means something
    /// else.
    ///
    /// @param question The question.
    /// @return Its kind.
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

    /// Returns this question in one line: its kind, what it was about, and how many shortfalls it had been
    /// told of when it was asked. This is what a turn records as the question asked. A person reads it, and
    /// a resumed attempt checks its replay against it. Everything in it is derived again by walking the same
    /// flow over the same sample, so two walks that reach the same place produce the same line.
    ///
    /// It is not the rendered prompt, because the prompt contains the stream's text, and recording it would
    /// keep a second copy of real data under the attempt's retention rather than the stream's. The prompt
    /// is instead rebuilt from the recorded templates for someone with *View Data* permission.
    ///
    /// @return The one-line summary.
    default String summary() {
        return identity()
               + (feedback().isEmpty()
                ? ""
                : SHORTFALLS_AFTER + feedback().size() + " shortfall(s)");
    }

    /// Returns the question's kind and what it is about. A resumed attempt compares its replay against this
    /// to check that each recorded answer still answers the question being asked.
    ///
    /// The count of shortfalls is left out of the comparison on purpose. A session that bound a fragment of
    /// its own and then parked is replayed with the shortfalls of what it bound, which its first questions,
    /// asked before the binding, did not have, so the counts would differ even though the questions match.
    ///
    /// @return The kind and subject of the question.
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

    /// Recovers the [#identity()] of a question from the [#summary()] a turn recorded.
    ///
    /// @param summary The recorded summary.
    /// @return The identity part of it.
    static String identityOf(final String summary) {
        final int after = summary.lastIndexOf(SHORTFALLS_AFTER);
        return after > 0 && summary.endsWith(" shortfall(s)")
                ? summary.substring(0, after)
                : summary;
    }

    /// Asks which chain of pipeline elements fits the sample, chosen from the document's allowed elements.
    /// This is the first question of a session. The sample's feed and type are shown with it, as they can
    /// hint at the format before the text is read.
    record Chain(Sample sample,
                 List<String> allowedElements,
                 List<StoredError> feedback) implements Question {

    }

    /// Asks what one record is in this input. It is asked before anything about meaning, for every kind of
    /// input, and answered without a target.
    ///
    /// For raw text the reply is a Data Splitter configuration that cuts the input into records and emits
    /// each record's whole text as one value, finding the boundary and nothing else. It is judged by
    /// coverage, yield and wholeness. For XML the reply is the element that is one record (or `name/*` for
    /// every child of a container). For JSON it is the key of the array whose items are records, or `root`.
    record Split(Sample sample,
                 String elementType,
                 String documentType,
                 InputKind kind,
                 List<StoredError> feedback) implements Question {

    }

    /// Asks what one kind of record should become. The model is shown one representative record, as the
    /// split cut it, with the schema's rules and the document's instructions. The reply is the target event
    /// as a single document, or `none` for a kind that yields no event. The reply is validated at once by the
    /// scorers that judge meaning, so a re-ask carries the shortfalls of the event the model itself wrote.
    ///
    /// @param record The representative record's text.
    /// @param kind   Which of the sample's record kinds this is, one-based.
    /// @param total  How many kinds the sample has.
    record TargetFor(Sample sample,
                     String record,
                     int kind,
                     int total,
                     List<StoredError> feedback) implements Question {

    }

    /// How many records the stream holds and how many kinds they are of, as the split found them.
    ///
    /// @param total How many records the stream holds.
    /// @param kinds How many kinds of record the stream holds, as the split tells them apart: one where every
    ///              record is alike, more where a feed reports several kinds of thing.
    record Records(int total, int kinds) {

        public static final Records UNKNOWN = new Records(0, 0);
    }

    /// Asks for the configuration document for one element of the chain. It carries the real input that
    /// element will receive (the sample for the first element, the previous element's output after that),
    /// and the previous configuration if this is a re-ask.
    ///
    /// @param elementType           The pipeline element type, e.g. `XSLTFilter`.
    /// @param documentType          The document type the element consumes, e.g. `XSLT`.
    /// @param sample                The input sample and headers the whole attempt is about.
    /// @param input                 What this element will actually be given to process.
    /// @param previousConfiguration The configuration this question is asking to improve on, or null.
    /// @param split                 The record boundary already settled: the configuration that cuts the
    ///                              records, for a parser that must extract within it, or the element
    ///                              that is one record, for a transform over XML. Null where the flow has
    ///                              no split step or the split does not concern this element.
    /// @param targets               What each kind of record must become. The parser's records must carry
    ///                              every value these need, and the transform must produce exactly these.
    ///                              Empty where the flow has no target step.
    /// @param oneRecord             Whether the input shown is one record rather than the whole stream.
    ///                              The fragment's `SplitFilter` gives this element one record at a time,
    ///                              so the model is asked for a configuration that handles one record, and
    ///                              told so, just as a person writing a Stroom stylesheet is shown one.
    /// @param records               How many records the stream holds, and how many kinds they are of,
    ///                              where the input shown is one of them. One record is limited evidence,
    ///                              and the configuration will be run over all of them, so the model is
    ///                              told what it is not being shown.
    /// @param otherKinds            One record of each *other* kind the split found, where the flow has not
    ///                              yet settled its targets. Saying that a second kind exists is not the
    ///                              same as showing it, and a configuration written from a login record
    ///                              alone will drop the logouts. Empty where the targets already show a
    ///                              record of each kind beside the event it must become, and where the
    ///                              stream has one kind. **Not necessarily every other kind**: the number
    ///                              of examples is capped, and [Records#kinds()] gives how many kinds there
    ///                              are, so a question built from this must not tell the model it has
    ///                              seen them all.
    /// @param admitting             Whether this is the exhaustion step's question, which asks for a
    ///                              transform that writes marked `Unknown` events for the records nothing
    ///                              better could be done with.
    /// @param feedback              Diagnostics from the previous answer, if it was refused or failed.
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
