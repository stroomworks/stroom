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

package stroom.learningflow.extraction;

import stroom.learningflow.learning.InputKind;
import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scoring.ConfinedXml;
import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Runs Stroom's `XMLFragmentParser` as one step of a learning session. It turns a stream of XML fragments
/// with no root (for example, one `<Event>…</Event>` per line) into one document by wrapping them in a root
/// element, so that the records are the root's children and everything downstream sees ordinary XML.
///
/// Without this step such a stream would be treated as text: its records would be shown to the model by
/// line, the split question could not be answered, and the transform would be run over something it cannot
/// parse, so the attempt would be abandoned.
///
/// The wrapper is the element's configuration, but the model is not asked to write it. The pipeline's
/// `XMLFragmentParser` takes a `TextConverter` whose document is a root element with `&fragment;` where the
/// stream goes, and this step has a built-in one. No configuration question is asked for it
/// ([#fixedConfiguration]), but the `TextConverter` is still written with the fragment, because a pipeline
/// needs it to run the element.
public final class XmlFragmentStep implements StepRunner {

    public static final String ELEMENT_TYPE = PipelineElementType.TYPE_XML_FRAGMENT_PARSER;

    /// The built-in wrapper, in the form the pipeline's parser takes it: a root element with the entity
    /// where the stream's fragments go. The root is simply called `records`, because its name does not
    /// matter: it is only the container the records sit in, and the split question asks the model which of
    /// its children is one record.
    public static final String WRAPPER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE records [<!ENTITY fragment SYSTEM "fragment">]>
            <records>&fragment;</records>""";

    private static final ElementId ELEMENT = new ElementId("xmlFragmentParser");
    private static final Configured CONFIGURED = new Configured(TextConverterDoc.TYPE, "textConverter",
            TextConverterType.XML_FRAGMENT);

    @Override
    public String elementType() {
        return ELEMENT_TYPE;
    }

    @Override
    public String elementId() {
        return ELEMENT.getId();
    }

    @Override
    public Optional<Configured> configured() {
        return Optional.of(CONFIGURED);
    }

    /// @return The built-in wrapper, which the conversation uses without asking the model, and which is
    /// written as the element's `TextConverter`.
    @Override
    public Optional<String> fixedConfiguration() {
        return Optional.of(WRAPPER);
    }

    @Override
    public boolean parser() {
        return true;
    }

    /// The fragments are XML elements, so the split question is the XML one (which element is one record),
    /// not the JSON one, even though, like the JSON parser, this element is never asked for a
    /// configuration.
    @Override
    public InputKind consumes() {
        return InputKind.XML;
    }

    /// Wraps the fragments in the root element and checks that the result is well-formed XML.
    ///
    /// @param configuration A wrapper to use instead of the built-in one, or null for the built-in one. It
    ///                      must contain `&fragment;` where the fragments go.
    /// @param input         The stream of XML fragments.
    /// @return The wrapped document, or no output with diagnostics if it is not well-formed.
    @Override
    public StepResult run(final String configuration, final String input) {
        final List<StoredError> diagnostics = new ArrayList<>();
        final String wrapped = wrap(configuration == null
                ? WRAPPER
                : configuration, input);
        if (wrapped == null) {
            diagnostics.add(new StoredError(Severity.FATAL_ERROR, null, ELEMENT,
                    "The wrapper has nowhere for the fragments to go: it must hold &fragment; once"));
            return new StepResult(null, List.copyOf(diagnostics));
        }
        try {
            // Parse the result rather than trusting the concatenation, so that a fragment that is not
            // well-formed is reported here, where the model can be told, rather than by a later element.
            final XMLReader reader = ConfinedXml.reader();
            reader.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(final SAXParseException e) {
                    diagnostics.add(reported(Severity.WARNING, e));
                }

                @Override
                public void error(final SAXParseException e) {
                    diagnostics.add(reported(Severity.ERROR, e));
                }

                @Override
                public void fatalError(final SAXParseException e) {
                    diagnostics.add(reported(Severity.FATAL_ERROR, e));
                }
            });
            reader.parse(new InputSource(new StringReader(wrapped)));
        } catch (final SAXException | IOException e) {
            if (diagnostics.stream().noneMatch(error -> error.getSeverity() == Severity.FATAL_ERROR)) {
                diagnostics.add(new StoredError(Severity.FATAL_ERROR, null, ELEMENT,
                        String.valueOf(e.getMessage())));
            }
            return new StepResult(null, List.copyOf(diagnostics));
        }
        return new StepResult(wrapped, List.copyOf(diagnostics));
    }

    // Puts the stream in place of the wrapper's entity and removes the DOCTYPE that declared it. The entity
    // only tells the pipeline's parser where the fragments go, and the output must not keep the DOCTYPE,
    // because the readers downstream refuse any DOCTYPE so that the text cannot make a parse fetch
    // anything.
    private static String wrap(final String wrapper, final String input) {
        // The entity is declared in the DOCTYPE's internal subset, which contains angle brackets of its own,
        // so the declaration ends at "]>" rather than at the first ">".
        final String withoutDoctype = wrapper.replaceAll("(?s)<!DOCTYPE[^>\\[]*(\\[.*?\\])?\\s*>\\s*", "");
        if (!withoutDoctype.contains("&fragment;")) {
            return null;
        }
        return withoutDoctype.replace("&fragment;", input.strip());
    }

    private static StoredError reported(final Severity severity, final SAXParseException e) {
        return new StoredError(severity, null, ELEMENT, e.getMessage());
    }
}
