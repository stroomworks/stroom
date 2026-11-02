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

package stroom.shapeshifter.ai.transformation;

import stroom.pipeline.shared.XsltDoc;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.scoring.ConfinedXml;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.ElementId;
import stroom.util.shared.ErrorType;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.Configuration;
import net.sf.saxon.TransformerFactoryImpl;
import net.sf.saxon.jaxp.TransformerImpl;
import net.sf.saxon.lib.FeatureKeys;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import net.sf.saxon.trans.XPathException;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.SourceLocator;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamResult;

/**
 * The {@code XSLTFilter} element as a step of the A21 conversation: compile the candidate stylesheet, then
 * transform the input with it. Saxon is driven directly, as the filter drives it, but without the filter's
 * pooling, reference-data loaders or Stroom function library — so a candidate that calls a
 * {@code stroom:} function fails here where the filter would have served it. That is the restricted
 * library of design §11 arriving early, and it is the one respect in which this step is stricter than the
 * element it stands in for.
 * <p>
 * The stylesheet is untrusted (§11): it is refused every way out of the process — {@code document()},
 * {@code unparsed-text()}, {@code xsl:include}, {@code xsl:import}, external entities and extension
 * functions — and a candidate that tries any of them fails with a diagnostic saying so.
 */
public final class XsltStep implements StepRunner {

    public static final String ELEMENT_TYPE = "XSLTFilter";
    private static final Configured CONFIGURED = new Configured(XsltDoc.TYPE, "xslt");
    private static final ElementId ELEMENT_ID = new ElementId("xsltFilter");
    private static final String REFUSED = "A candidate stylesheet may not read external resources: ";
    private static final URIResolver REFUSING_URI_RESOLVER = (href, base) -> {
        throw new TransformerException(REFUSED + href);
    };

    @Override
    public String elementType() {
        return ELEMENT_TYPE;
    }

    @Override
    public String elementId() {
        return ELEMENT_ID.getId();
    }

    @Override
    public Optional<Configured> configured() {
        return Optional.of(CONFIGURED);
    }

    @Override
    public StepResult run(final String configuration, final String input) {
        return prepare(configuration).run(input);
    }

    /// The stylesheet compiled once, to be run over one record after another (§12 item 25): compiling is
    /// the expensive half and it does not depend on the input, so a candidate is compiled for the
    /// candidate and not for every record of the stream it is judged on.
    ///
    /// A stylesheet that will not compile fails once, here, and every record it would have been given
    /// reports that one failure rather than its own copy of it.
    @Override
    public Prepared prepare(final String configuration) {
        final Diagnostics compiling = new Diagnostics();
        final TransformerFactoryImpl factory = new TransformerFactoryImpl();
        factory.setErrorListener(compiling);
        confine(factory);
        final Templates templates;
        try {
            templates = factory.newTemplates(ConfinedXml.source(configuration));
        } catch (final TransformerException e) {
            if (compiling.errors.stream().noneMatch(error -> error.getSeverity() == Severity.FATAL_ERROR)) {
                compiling.record(Severity.FATAL_ERROR, e);
            }
            final List<StoredError> failed = List.copyOf(compiling.errors);
            return input -> new StepResult(null, failed);
        }
        final List<StoredError> compiled = List.copyOf(compiling.errors);
        return input -> {
            final Diagnostics diagnostics = new Diagnostics();
            diagnostics.errors.addAll(compiled);
            final StringWriter output = new StringWriter();
            try {
                final Transformer transformer = templates.newTransformer();
                transformer.setErrorListener(diagnostics);
                ((TransformerImpl) transformer).getUnderlyingXsltTransformer()
                        .setMessageListener(diagnostics::message);
                transformer.setURIResolver(REFUSING_URI_RESOLVER);
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                transformer.transform(ConfinedXml.source(input), new StreamResult(output));
            } catch (final TransformerException e) {
                // Saxon reports through the listener before throwing, so this is only the rare fault
                // nobody has yet recorded.
                if (diagnostics.errors.stream().noneMatch(error -> error.getSeverity() == Severity.FATAL_ERROR)) {
                    diagnostics.record(Severity.FATAL_ERROR, e);
                }
                return new StepResult(null, List.copyOf(diagnostics.errors));
            }
            return new StepResult(output.toString(), List.copyOf(diagnostics.errors));
        };
    }

    /**
     * Closes every route by which a stylesheet could read outside its input. The include/import resolver
     * is set on the factory, the {@code document()} resolver on each transformer — Saxon keeps them apart —
     * and both refuse rather than return nothing. Saxon would then recover from a refused
     * {@code document()} with a warning and an empty sequence, which is not a failure; recovery is turned
     * off so that the attempt fails as the attempt it was.
     */
    private static void confine(final TransformerFactoryImpl factory) {
        final Configuration configuration = factory.getConfiguration();
        factory.setURIResolver(REFUSING_URI_RESOLVER);
        configuration.setUnparsedTextURIResolver((uri, encoding, config) -> {
            throw new XPathException(REFUSED + uri);
        });
        // collection() and uri-collection() resolve file: URIs themselves, consulting neither resolver.
        configuration.setCollectionFinder((context, uri) -> {
            throw new XPathException(REFUSED + uri);
        });
        configuration.setConfigurationProperty(FeatureKeys.ALLOW_EXTERNAL_FUNCTIONS, false);
        configuration.setRecoveryPolicy(Configuration.DO_NOT_RECOVER);
    }

    /**
     * Collects what Saxon reports, at the severity Saxon gave it, located as Saxon located it.
     */
    private static final class Diagnostics implements ErrorListener {

        private final List<StoredError> errors = new ArrayList<>();

        @Override
        public void warning(final TransformerException e) {
            record(Severity.WARNING, e);
        }

        @Override
        public void error(final TransformerException e) {
            record(Severity.ERROR, e);
        }

        @Override
        public void fatalError(final TransformerException e) {
            record(Severity.FATAL_ERROR, e);
        }

        /**
         * An {@code xsl:message} as Stroom's {@code XSLTFilter} reads one: an error unless its first child
         * element names a severity ({@code <warn>}, {@code <info>}), fatal if it terminates; the transform's
         * own channel for a business rule it found broken (design 01 §8.4). Marked as code so that the
         * business-rules scorer can tell the transform's messages from Saxon's.
         */
        private void message(final XdmNode content, final boolean terminate, final SourceLocator locator) {
            Severity severity = terminate
                    ? Severity.FATAL_ERROR
                    : Severity.ERROR;
            String text = content == null
                    ? ""
                    : content.getStringValue();
            if (!terminate && content != null) {
                for (final XdmNode child : content.children()) {
                    if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                        final Severity named = Severity.getSeverity(child.getNodeName().getLocalName());
                        if (named != null) {
                            severity = named;
                            text = child.getStringValue();
                        }
                    }
                    break;
                }
            }
            errors.add(new StoredError(
                    severity,
                    locator == null
                            ? null
                            : DefaultLocation.of(locator.getLineNumber(), locator.getColumnNumber()),
                    ELEMENT_ID,
                    text.isEmpty()
                            ? "NO MESSAGE"
                            : text,
                    ErrorType.CODE));
        }

        private void record(final Severity severity, final TransformerException e) {
            final SourceLocator locator = e.getLocator();
            errors.add(new StoredError(
                    severity,
                    locator == null
                            ? null
                            : DefaultLocation.of(locator.getLineNumber(), locator.getColumnNumber()),
                    ELEMENT_ID,
                    message(e)));
        }

        /**
         * Saxon wraps a parser's or resolver's failure in a generic message of its own; the detail the
         * model needs is in the cause.
         */
        private static String message(final TransformerException e) {
            final Throwable cause = e.getCause();
            return cause == null || cause.getMessage() == null || e.getMessage().contains(cause.getMessage())
                    ? e.getMessage()
                    : e.getMessage() + ": " + cause.getMessage();
        }
    }
}
