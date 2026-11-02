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

package stroom.shapeshifter.ai.extraction;

import stroom.pipeline.shared.TextConverterDoc;
import stroom.pipeline.shared.TextConverterDoc.TextConverterType;
import stroom.shapeshifter.ai.learning.InputKind;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.ConfinedXml;
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

/// Stroom's `XMLFragmentParser` as a step (§12 item 26): a stream of markup fragments — one
/// `<Event>…</Event>` per line, no root — made into one document by wrapping them in a root, so that the
/// records are the root's children and everything downstream sees ordinary XML.
///
/// A stream like this is not a document, and without this it is treated as text: held out by lines, its
/// split unanswerable and its transform run over something it cannot parse, so the attempt is abandoned.
///
/// The wrapper is the element's configuration, and it is not the model's to write: the pipeline's
/// `XMLFragmentParser` takes a `TextConverter` whose document is a root with `&fragment;` where the
/// stream goes, and this step carries a built-in one. So no configuration question is asked for it
/// ([#fixedConfiguration]) and the document is written with the fragment all the same, which is what a
/// pipeline needs to run it.
public final class XmlFragmentStep implements StepRunner {

    public static final String ELEMENT_TYPE = "XMLFragmentParser";

    /// The wrapper as the pipeline's parser takes it: a root element with the entity where the stream's
    /// fragments go. `records` rather than anything cleverer, because the name is the model's to ignore
    /// — what it names is a container the records sit in, and the split question asks which of those
    /// children is one record.
    public static final String WRAPPER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE records [<!ENTITY fragment SYSTEM "fragment">]>
            <records>&fragment;</records>""";

    private static final ElementId ELEMENT = new ElementId("xmlFragmentParser");
    private static final Configured CONFIGURED = new Configured(TextConverterDoc.TYPE, "textConverter",
            TextConverterType.XML_FRAGMENT);
    private static final String ROOT = "records";

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

    /// The wrapper, which the conversation uses without asking and the writer writes as the element's
    /// `TextConverter`.
    @Override
    public Optional<String> fixedConfiguration() {
        return Optional.of(WRAPPER);
    }

    @Override
    public boolean parser() {
        return true;
    }

    /// Markup: the fragments are the stream's own elements, so the split question is the XML one — which
    /// element is one record — and not the JSON one, although like a JSON parser this element is never
    /// asked for a configuration.
    @Override
    public InputKind consumes() {
        return InputKind.XML;
    }

    /// @param configuration A wrapper of the document's own, or null for the built-in one. Whatever it
    ///                     is, it must be a document with one place for the fragments to go.
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
            // Read rather than merely concatenated: a fragment that is not well formed is the stream's
            // fault and is said here, where the model can be told, rather than three elements later.
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

    /// The stream in place of the wrapper's entity, with the DOCTYPE that declared it removed: the
    /// entity is how the pipeline's parser is told where the fragments go, and it is not something the
    /// document that comes out should still be carrying — the readers downstream refuse a DOCTYPE
    /// (§11), as they should.
    private static String wrap(final String wrapper, final String input) {
        // The internal subset is where the entity is declared, and it holds angle brackets of its own, so
        // the declaration ends at "]>" and not at the first ">" after it.
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
