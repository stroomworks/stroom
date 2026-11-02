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

import stroom.pipeline.xml.converter.json.JSONParserFactory;
import stroom.shapeshifter.ai.learning.InputKind;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.TransformerFactoryImpl;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;

/// Stroom's `JSONParser` as a step: JSON in, the XML of the `http://www.w3.org/2013/XSL/json` vocabulary out,
/// through the same reader the pipeline element uses. It takes no configuration, so it is a run-only step
/// (design 01 §10): the chain question may choose it, and no configuration question is asked for it.
public final class JsonStep implements StepRunner {

    public static final String ELEMENT_TYPE = "JSONParser";
    private static final ElementId ELEMENT = new ElementId("jsonParser");

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
        return Optional.empty();
    }

    @Override
    public boolean parser() {
        return true;
    }

    /// JSON, so the split question is the array whose items are records (A31).
    @Override
    public InputKind consumes() {
        return InputKind.JSON;
    }

    @Override
    public StepResult run(final String configuration, final String input) {
        final List<StoredError> diagnostics = new ArrayList<>();
        final StringWriter output = new StringWriter();
        try {
            final TransformerHandler serialiser = ((SAXTransformerFactory) new TransformerFactoryImpl())
                    .newTransformerHandler();
            serialiser.getTransformer().setOutputProperty(OutputKeys.INDENT, "yes");
            serialiser.setResult(new StreamResult(output));
            final XMLReader reader = new JSONParserFactory().getParser();
            reader.setContentHandler(serialiser);
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
            reader.parse(new InputSource(new StringReader(input)));
        } catch (final TransformerConfigurationException | SAXException | IOException e) {
            if (diagnostics.stream().noneMatch(error -> error.getSeverity() == Severity.FATAL_ERROR)) {
                diagnostics.add(new StoredError(Severity.FATAL_ERROR, null, ELEMENT, String.valueOf(e.getMessage())));
            }
            return new StepResult(null, List.copyOf(diagnostics));
        }
        return new StepResult(output.toString(), List.copyOf(diagnostics));
    }

    private static StoredError reported(final Severity severity, final SAXParseException e) {
        return new StoredError(severity, null, ELEMENT, e.getMessage());
    }
}
