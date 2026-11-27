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

package stroom.learningflow.transformation;

import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.scoring.ConfinedXml;
import stroom.util.shared.DefaultLocation;
import stroom.util.shared.ElementId;
import stroom.util.shared.ErrorType;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;
import stroom.util.xml.SaxonPolicy;
import stroom.util.xml.SaxonSecurity;

import net.sf.saxon.Configuration;
import net.sf.saxon.jaxp.SaxonTransformerFactory;
import net.sf.saxon.jaxp.TransformerImpl;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
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

/// Runs the `XSLTFilter` element for the in-memory scenario tests, in place of the real pipeline that runs
/// it on a node ([XsltStep] describes the element, and is what a node wraps). It compiles the candidate
/// stylesheet, then transforms the input with it.
///
/// Saxon is driven directly, as the filter drives it, but without the filter's pooling, reference data
/// loaders or Stroom function library. Instead, a few `stroom:` functions are provided by
/// [StroomStandIns]; a candidate calling any other `stroom:` function fails here where the filter would
/// have run it. That is the one respect in which this step is stricter than the element it stands in for.
///
/// The stylesheet is untrusted, so every way it could read outside its input is refused: `document()`,
/// `unparsed-text()`, `collection()`, `xsl:include`, `xsl:import` and external entities. A candidate
/// that tries any of them fails with a diagnostic saying so. These are refused here as well as by the
/// static check, so that the harness does not depend on the check having run.
public final class StandInXsltStep implements StepRunner {

    private static final XsltStep DESCRIPTION = new XsltStep();
    /// The stream being translated, which is the only thing the stand-in `stroom:` functions may read.
    private final StreamFacts facts;
    private static final ElementId ELEMENT_ID = new ElementId(XsltStep.ELEMENT_ID);
    private static final String REFUSED = "A candidate stylesheet may not read external resources: ";
    private static final URIResolver REFUSING_URI_RESOLVER = (href, base) -> {
        throw new TransformerException(REFUSED + href);
    };

    /// Creates a step that knows nothing about the stream, so that a stylesheet calling a `stroom:`
    /// function that needs it fails, saying so.
    public StandInXsltStep() {
        this(StreamFacts.none());
    }

    /// Creates a step for the given stream.
    ///
    /// @param facts The stream being translated, for the stand-in `stroom:` functions; null means none.
    public StandInXsltStep(final StreamFacts facts) {
        this.facts = facts == null
                ? StreamFacts.none()
                : facts;
    }

    @Override
    public String elementType() {
        return DESCRIPTION.elementType();
    }

    @Override
    public String elementId() {
        return DESCRIPTION.elementId();
    }

    @Override
    public Optional<Configured> configured() {
        return DESCRIPTION.configured();
    }

    @Override
    public StepResult run(final String configuration, final String input) {
        return prepare(configuration).run(input);
    }

    /// Compiles the stylesheet once, to be run over one record after another. Compiling is the expensive
    /// part and does not depend on the input, so a candidate is compiled once rather than for every record
    /// of the stream it is judged on.
    ///
    /// A stylesheet that does not compile fails once, here, and every record it would have been given
    /// reports that one failure.
    @Override
    public Prepared prepare(final String configuration) {
        final Diagnostics compiling = new Diagnostics();
        final SaxonTransformerFactory factory = SaxonSecurity.newTransformerFactory(SaxonPolicy.LOCKED);
        factory.setErrorListener(compiling);
        confine(factory);
        for (final ExtensionFunctionDefinition standIn : StroomStandIns.definitions(facts)) {
            factory.getConfiguration().registerExtensionFunction(standIn);
        }
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
                // Saxon reports through the listener before throwing, so this only records the rare
                // fault that has not been reported already.
                if (diagnostics.errors.stream().noneMatch(error -> error.getSeverity() == Severity.FATAL_ERROR)) {
                    diagnostics.record(Severity.FATAL_ERROR, e);
                }
                return new StepResult(null, List.copyOf(diagnostics.errors));
            }
            return new StepResult(output.toString(), List.copyOf(diagnostics.errors));
        };
    }

    /// Closes every route by which a stylesheet could read outside its input. The `xsl:include` and
    /// `xsl:import` resolver is set on the factory, and the `document()` resolver on each transformer,
    /// because Saxon keeps them separate; both refuse rather than return nothing. Saxon would recover from
    /// a refused `document()` with a warning and an empty sequence, which is not a failure, so recovery is
    /// turned off to make the attempt fail.
    private static void confine(final SaxonTransformerFactory factory) {
        final Configuration configuration = factory.getConfiguration();
        factory.setURIResolver(REFUSING_URI_RESOLVER);
        configuration.setUnparsedTextURIResolver((uri, encoding, config) -> {
            throw new XPathException(REFUSED + uri);
        });
        // collection() and uri-collection() resolve file: URIs themselves, consulting neither resolver.
        configuration.setCollectionFinder((context, uri) -> {
            throw new XPathException(REFUSED + uri);
        });
        // External functions stay off, as SaxonSecurity leaves them, which refuses xsl:result-document
        // and limits system-property(). The `stroom:` stand-ins registered in prepare() still work,
        // because they are registered in Java, which this setting does not cover.
        configuration.setRecoveryPolicy(Configuration.DO_NOT_RECOVER);
    }

    /// Collects what Saxon reports, with the severity and location Saxon gave it.
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

        /// Records an `xsl:message` as Stroom's `XSLTFilter` reads one: an error unless its first child
        /// element names a severity (`<warn>`, `<info>`), and fatal if it terminates. A transform uses
        /// messages to report a business rule it found broken. They are marked as code errors so that the
        /// business rules scorer can tell the transform's messages from Saxon's.
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

        /// Builds the message for an error, adding the cause's message. Saxon wraps a parser's or
        /// resolver's failure in a generic message of its own, and the detail the model needs is in the
        /// cause.
        private static String message(final TransformerException e) {
            final Throwable cause = e.getCause();
            return cause == null || cause.getMessage() == null || e.getMessage().contains(cause.getMessage())
                    ? e.getMessage()
                    : e.getMessage() + ": " + cause.getMessage();
        }
    }
}
