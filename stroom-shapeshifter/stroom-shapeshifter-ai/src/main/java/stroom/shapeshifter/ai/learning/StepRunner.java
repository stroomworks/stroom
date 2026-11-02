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

import stroom.pipeline.shared.TextConverterDoc.TextConverterType;

import java.util.Optional;

/**
 * Runs one kind of pipeline element headlessly, for the conversation to try a candidate configuration before
 * anything is written to a store (design §7.3 rule 1). One runner per element type the stage can use.
 * <p>
 * This is the stand-in for the headless harness of design §12 item 2 until that is extracted from the
 * stepper. A runner is the element's behaviour with its Stroom plumbing removed, not the element itself.
 */
public interface StepRunner {

    /**
     * @return The pipeline element type this runs, e.g. {@code DSParser}. Matches the document's allowed
     * elements and the type recorded in a fragment.
     */
    String elementType();

    /**
     * @return The id the element takes in a fragment, e.g. {@code dsParser}.
     */
    String elementId();

    /**
     * @return The document type the element consumes and the element property that references it, or
     * empty for an element that takes no configuration and is a run-only step.
     */
    Optional<Configured> configured();

    /**
     * @param configuration The candidate configuration text, or null for a run-only element.
     * @param input         What the element is to process.
     */
    StepResult run(String configuration, String input);

    /**
     * The same configuration, ready to be run many times: the fragment gives its transform one record at
     * a time (§12 item 25), so a candidate is run once per record of the stream, and whatever compiling
     * a configuration costs must be paid once for the candidate and not once for every record of it.
     * <p>
     * The default pays it every time, which is right for an element that compiles nothing; a runner that
     * compiles — a stylesheet, a splitter — overrides it and compiles once.
     */
    default Prepared prepare(final String configuration) {
        return input -> run(configuration, input);
    }

    /**
     * Whether the element parses raw input into records — the extraction position of design 01 §4 —
     * rather than transforming records into records.
     */
    default boolean parser() {
        return false;
    }

    /// The configuration this element always runs with, where it is not the model's to write: the
    /// wrapper a fragment parser needs is the element's own business, not a question worth a candidate
    /// (§12 item 26). No question is asked for it and the document is written all the same, so that a
    /// pipeline has what it needs to run the element.
    ///
    /// @return Empty for an element whose configuration is asked for, which is every element a model
    /// actually writes.
    default Optional<String> fixedConfiguration() {
        return Optional.empty();
    }

    /// What kind of stream this element is given, which says which split question the input calls for
    /// (A31, A35) — the element that is one record for markup, the array whose items are records for
    /// JSON, a configuration that cuts it for raw text.
    ///
    /// Read only of the chain's first element, and only where it parses: what a later element is given
    /// is what the one before it wrote. An element that does not parse is given records, which are
    /// markup.
    default InputKind consumes() {
        return InputKind.XML;
    }

    /**
     * @param documentType  The document type consumed, e.g. {@code TextConverter}.
     * @param propertyName  The element property that references the document, e.g. {@code textConverter}.
     * @param converterType Which kind of text converter, for a step whose document is one; null for
     *                      every other document type. A {@code TextConverterDoc} carries its kind, and
     *                      the element that reads it refuses one of the wrong kind — {@code
     *                      XMLFragmentParser} throws "The assigned text converter is not an XML
     *                      fragment" — so a writer that guessed would write a fragment that passes
     *                      every scorer and fails on every stream a node gives it.
     */
    record Configured(String documentType, String propertyName, TextConverterType converterType) {

        /**
         * A configuration whose document is not a text converter and so has no kind.
         */
        public Configured(final String documentType, final String propertyName) {
            this(documentType, propertyName, null);
        }
    }


    // --------------------------------------------------------------------------------


    /// One configuration, compiled, to be run over one input after another, and then closed.
    ///
    /// Closing matters where preparing took something that has to be given back — a pooled stylesheet,
    /// a built pipeline — and costs nothing where it did not, which is why the default does nothing.
    interface Prepared extends AutoCloseable {

        StepResult run(String input);

        @Override
        default void close() {
        }
    }
}
