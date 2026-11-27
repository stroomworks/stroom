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

import stroom.pipeline.shared.TextConverterDoc.TextConverterType;

import java.util.Optional;

/// Runs one kind of pipeline element outside a pipeline, so that [Conversation] can try a candidate
/// configuration before anything is written to a document store. There is one runner per element type a
/// learning flow can use.
///
/// A runner reproduces the element's behaviour without its Stroom pipeline plumbing; it is not the element
/// itself. Where that would differ from the element in ways that matter, as it would for a stylesheet, a node
/// runs the real element in a pipeline instead ([stroom.learningflow.element.PipelineStepRunner]), and uses
/// the runner only for its description of the element.
public interface StepRunner {

    /// @return The pipeline element type this runs, e.g. `DSParser`. Matches the document's allowed
    /// elements and the type recorded in a fragment.
    String elementType();

    /// @return The id the element takes in a fragment, e.g. `dsParser`.
    String elementId();

    /// @return The document type the element consumes and the element property that references it, or
    /// empty for an element that takes no configuration and is a run-only step.
    Optional<Configured> configured();

    /// Runs the element once with a configuration.
    ///
    /// @param configuration The candidate configuration text, or null for a run-only element.
    /// @param input         What the element is to process.
    /// @return What the element produced and the diagnostics it raised.
    StepResult run(String configuration, String input);

    /// Prepares a configuration to be run many times. The fragment gives its transform one record at a time,
    /// so a candidate is run once per record of the stream, and the cost of compiling a configuration should
    /// be paid once per candidate, not once per record.
    ///
    /// The default compiles on every run, which is right for an element that compiles nothing. A runner
    /// that compiles (a stylesheet, a splitter) overrides it to compile once.
    ///
    /// @param configuration The candidate configuration text, or null for a run-only element.
    /// @return The prepared configuration, which the caller must close.
    default Prepared prepare(final String configuration) {
        return input -> run(configuration, input);
    }

    /// Whether the element parses raw input into records, as the first element of a chain does, rather than
    /// transforming records into records.
    ///
    /// @return True for a parser.
    default boolean parser() {
        return false;
    }

    /// The configuration this element always runs with, where the model is not asked to write it. For
    /// example, the root wrapper an XML fragment parser needs is fixed, and not worth a question. No question
    /// is asked for it, but the document is still written, so that a pipeline has what it needs to run the
    /// element.
    ///
    /// @return The fixed configuration, or empty for an element whose configuration the model is asked to
    /// write.
    default Optional<String> fixedConfiguration() {
        return Optional.empty();
    }

    /// What kind of input this element is given, which decides which split question is asked: the element
    /// that is one record for XML, the array whose items are records for JSON, or a configuration that cuts
    /// the text for raw text.
    ///
    /// It is read only for the chain's first element, and only where that element is a parser, because a
    /// later element is given what the one before it wrote. An element that does not parse is given
    /// records, which are XML.
    ///
    /// @return The kind of input.
    default InputKind consumes() {
        return InputKind.XML;
    }

    /// The document an element is configured with, and how the element refers to it.
    ///
    /// @param documentType  The document type consumed, e.g. `TextConverter`.
    /// @param propertyName  The element property that references the document, e.g. `textConverter`.
    /// @param converterType Which kind of text converter, for a step whose document is one; null for
    ///                      every other document type. A `TextConverterDoc` records its kind, and the
    ///                      element that reads it refuses one of the wrong kind (`XMLFragmentParser` throws
    ///                      "The assigned text converter is not an XML fragment"). A writer that guessed
    ///                      the kind would write a fragment that passes every scorer but fails on every
    ///                      stream a node gives it.
    record Configured(String documentType, String propertyName, TextConverterType converterType) {

        /// A configuration whose document is not a text converter and so has no kind.
        public Configured(final String documentType, final String propertyName) {
            this(documentType, propertyName, null);
        }
    }


    // --------------------------------------------------------------------------------


    /// One compiled configuration, to be run over one input after another and then closed.
    ///
    /// Closing matters where preparing took something that has to be given back, such as a pooled
    /// stylesheet or a built pipeline. Where it did not, there is nothing to do, which is why the default
    /// does nothing.
    interface Prepared extends AutoCloseable {

        StepResult run(String input);

        @Override
        default void close() {
        }
    }
}
