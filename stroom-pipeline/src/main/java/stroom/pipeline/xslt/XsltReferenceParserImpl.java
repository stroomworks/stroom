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

package stroom.pipeline.xslt;

import stroom.dictionary.shared.DictionaryDoc;
import stroom.docref.DocRef;
import stroom.pipeline.shared.XsltDoc;
import stroom.pipeline.shared.XsltReferenceCertainty;
import stroom.pipeline.shared.XsltReferenceDirection;
import stroom.pipeline.shared.XsltReferenceKind;
import stroom.pipeline.shared.XsltReferenceReason;
import stroom.pipeline.xml.NamespaceConstants;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.FunctionCall;
import net.sf.saxon.expr.Operand;
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reads what an XSLT refers to, using Saxon for both the stylesheet tree and the XPath inside its
 * attributes.
 * <p>
 * A regular expression would not do. Prefixes are arbitrary, so text scanning is unreliable in principle
 * rather than at the margins, and it is further defeated by escaped quotes, XPath comments and
 * {@code concat()}. Compiling gives an exact answer instead.
 * <p>
 * The stylesheet is <b>not</b> compiled as a stylesheet, only its expressions. Compiling it would resolve
 * imports, so it would fail on exactly the broken configurations most worth surfacing, and a fault in an
 * imported document would blind the parser to the saved document's own references.
 * <p>
 * Thread safe: the Saxon {@code Processor} is shared and used only for compilation, which Saxon permits
 * concurrently, and each parse builds its own tree and its own compilers.
 */
@Singleton
class XsltReferenceParserImpl implements XsltReferenceParser {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(XsltReferenceParserImpl.class);

    /**
     * Bounds the work one document can cost. Generous: a large stylesheet parses in low tens of
     * milliseconds, so reaching this means something pathological.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Bounds recursion through variables that refer to other variables.
     */
    static final int DEFAULT_MAX_DEPTH = 10;

    // Function local names, identified with the namespace URI rather than a prefix. Declared as literals
    // because the implementing classes are package private to stroom.pipeline.xsltfunctions; the
    // registered names are in CommonXsltFunctionModule and DataStoreXsltFunctionModule.
    private static final String DICTIONARY_FUNCTION = "dictionary";
    private static final String LOOKUP_FUNCTION = "lookup";
    private static final String BITMAP_LOOKUP_FUNCTION = "bitmap-lookup";
    private static final String HTTP_CALL_FUNCTION = "http-call";
    private static final String FETCH_JSON_FUNCTION = "fetch-json";

    private static final String IMPORT_ELEMENT = "import";
    private static final String INCLUDE_ELEMENT = "include";
    private static final String HREF_ATTRIBUTE = "href";
    private static final String MAP_ELEMENT = "map";

    /**
     * Attributes on XSLT elements whose value is an XPath expression.
     */
    private static final Set<String> XPATH_ATTRIBUTES = Set.of(
            "select", "test", "use", "group-by", "group-adjacent", "value", "xpath");

    /**
     * Attributes on XSLT elements whose value is a match pattern. Compiled rather than skipped because a
     * predicate can hold a function call.
     */
    private static final Set<String> PATTERN_ATTRIBUTES = Set.of(
            "match", "count", "from", "group-starting-with", "group-ending-with");

    /**
     * The separator for a chained lookup, e.g. {@code stroom:lookup('MAP1/MAP2', $key)}. Mirrors
     * {@code LookupIdentifier.NEST_SEPARATOR}.
     */
    private static final String MAP_NEST_SEPARATOR = "/";

    private final XsltReferenceLookup lookup;
    private final XsltExpressionCompiler compiler;
    private final XsltValueResolver valueResolver;
    private final Duration timeout;

    @SuppressWarnings("unused")
    @Inject
    XsltReferenceParserImpl(final XsltReferenceLookup lookup) {
        this(lookup, DEFAULT_TIMEOUT, DEFAULT_MAX_DEPTH);
    }

    XsltReferenceParserImpl(final XsltReferenceLookup lookup,
                            final Duration timeout,
                            final int maxDepth) {
        this.lookup = Objects.requireNonNull(lookup, "Null lookup supplied");
        this.timeout = Objects.requireNonNull(timeout, "Null timeout supplied");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        this.compiler = new XsltExpressionCompiler();
        this.valueResolver = new XsltValueResolver(compiler, maxDepth);
    }

    @Override
    public XsltReferences parse(final @Nullable String xsltData) {
        if (NullSafe.isBlankString(xsltData)) {
            // A document with no body yet. Not a failure - there is simply nothing to find.
            return XsltReferences.empty();
        }

        final Walk walk = new Walk();
        try {
            walk.run(compiler.buildTree(xsltData));
        } catch (final SaxonApiException e) {
            // Not well-formed XML, so nothing is legible. Expected while an author is mid-edit, and
            // deliberately not reported to them - they know.
            LOGGER.debug(() -> "XSLT body could not be parsed: " + e.getMessage(), e);
            return XsltReferences.parseFailure(
                    Objects.requireNonNullElseGet(e.getMessage(), () -> e.getClass().getSimpleName()));
        } catch (final RuntimeException e) {
            // The contract is that this never throws, so an unexpected fault becomes a parse failure
            // rather than a failed save.
            LOGGER.error(() -> "Unexpected error parsing XSLT body: " + e.getMessage(), e);
            return XsltReferences.parseFailure("Unexpected error: " + e.getMessage());
        }
        return walk.result();
    }

    /**
     * One pass over one document. Holds the mutable state a parse needs, so the parser itself has none.
     */
    private final class Walk {

        private final List<XsltReference> references = new ArrayList<>();
        private final long deadline = System.nanoTime() + timeout.toNanos();
        private boolean timedOut;

        void run(final XdmNode document) {
            final java.util.Iterator<XdmNode> iterator = document.axisIterator(Axis.DESCENDANT_OR_SELF);
            while (iterator.hasNext()) {
                final XdmNode node = iterator.next();
                if (isPastDeadline()) {
                    return;
                }
                if (node.getNodeKind() == XdmNodeKind.ELEMENT) {
                    visitElement(node);
                }
            }
        }

        XsltReferences result() {
            if (timedOut) {
                // Partial rather than empty: whatever was found before the deadline is still true. The
                // message marks the result incomplete so no consumer mistakes it for a full picture.
                return new XsltReferences(references, "Parsing exceeded " + timeout);
            }
            return XsltReferences.of(references);
        }

        private boolean isPastDeadline() {
            if (!timedOut && System.nanoTime() - deadline > 0) {
                timedOut = true;
            }
            return timedOut;
        }

        private void visitElement(final XdmNode element) {
            final boolean isXsltElement =
                    XsltValueResolver.XSLT_NS.equals(element.getNodeName().getNamespaceURI());

            if (isXsltElement && isImport(element)) {
                visitImport(element);
                // @href is a URI, not an expression, and an import element has nothing else of interest.
                return;
            }
            if (!isXsltElement && MAP_ELEMENT.equalsIgnoreCase(element.getNodeName().getLocalName())) {
                visitMapOutput(element);
            }
            visitAttributes(element, isXsltElement);
        }

        private boolean isImport(final XdmNode element) {
            final String localName = element.getNodeName().getLocalName();
            return IMPORT_ELEMENT.equals(localName) || INCLUDE_ELEMENT.equals(localName);
        }

        /**
         * {@code xsl:import} and {@code xsl:include} both name another XSLT document, resolved exactly as
         * {@code CustomURIResolver} resolves it at runtime.
         */
        private void visitImport(final XdmNode element) {
            final String href = element.attribute(HREF_ATTRIBUTE);
            if (NullSafe.isBlankString(href)) {
                return;
            }
            references.add(resolveImport(href, lineNumberOf(element)));
        }

        /**
         * The literal content of a {@code <map>} element in the output names a reference data map or a
         * Plan B store. Both write schemas use {@code <map>}: {@code reference-data:2} nests it in
         * {@code <reference>}, while the {@code plan-b} schema nests it in {@code <state>},
         * {@code <temporal-state>} and its siblings. Rather than depend on either shape, any
         * non-XSLT-namespace element named {@code map} counts, which also matches the filters' own
         * case-insensitive element matching.
         */
        private void visitMapOutput(final XdmNode element) {
            final int lineNumber = lineNumberOf(element);
            final XsltValue value = valueResolver.resolveElementContent(element);
            for (final String mapName : value.values()) {
                // Trimmed because the surrounding whitespace is the author's formatting rather than part
                // of the name. Note that the runtime filters do not trim, so a pretty printed <map>
                // element is a genuine, pre-existing runtime hazard - out of scope here.
                final String trimmed = mapName.trim();
                if (!trimmed.isEmpty()) {
                    references.add(XsltReference.mapName(
                            XsltReferenceKind.REF_MAP_WRITE, trimmed, value.certainty(), lineNumber));
                }
            }
            if (value.reason() != null) {
                references.add(XsltReference.unresolved(
                        XsltReferenceKind.REF_MAP_WRITE,
                        element.getStringValue().trim(),
                        value.reason(),
                        value.certainty(),
                        lineNumber));
            }
        }

        private void visitAttributes(final XdmNode element, final boolean isXsltElement) {
            for (final XdmNode attribute : attributesOf(element)) {
                if (isPastDeadline()) {
                    return;
                }
                final String name = attribute.getNodeName().getLocalName();
                final String value = attribute.getStringValue();
                if (NullSafe.isBlankString(value)) {
                    continue;
                }

                if (isXsltElement && XPATH_ATTRIBUTES.contains(name)) {
                    analyse(element, value, false);
                } else if (isXsltElement && PATTERN_ATTRIBUTES.contains(name)) {
                    analyse(element, value, true);
                } else {
                    // Anything else may be an attribute value template. Scanning one that is not simply
                    // finds no braces and costs nothing.
                    for (final String embedded : XsltExpressionCompiler.splitAttributeValueTemplate(value)) {
                        analyse(element, embedded, false);
                    }
                }
            }
        }

        /**
         * @return the element's attributes, ordered by name so that findings come out in the same order
         * for the same input regardless of how the parser happens to store them.
         */
        private List<XdmNode> attributesOf(final XdmNode element) {
            final List<XdmNode> attributes = new ArrayList<>();
            element.axisIterator(Axis.ATTRIBUTE).forEachRemaining(attributes::add);
            attributes.sort(Comparator.comparing(node -> node.getNodeName().getLocalName()));
            return attributes;
        }

        private void analyse(final XdmNode element, final String expressionText, final boolean isPattern) {
            final Expression expression;
            try {
                expression = isPattern
                        ? compiler.compilePattern(element, expressionText)
                        : compiler.compileExpression(element, expressionText);
            } catch (final SaxonApiException e) {
                // Only this expression is lost, not the document. A stylesheet with one bad attribute
                // still yields everything else.
                LOGGER.debug(() -> "Could not compile '" + expressionText + "': " + e.getMessage(), e);
                references.add(XsltReference.unresolved(
                        XsltReferenceKind.UNANALYSED,
                        expressionText,
                        XsltReferenceReason.UNPARSEABLE,
                        XsltReferenceCertainty.STATIC,
                        lineNumberOf(element)));
                return;
            }
            findStroomCalls(expression, element, expressionText);
        }

        /**
         * Walk the whole expression tree, not just its root, so that a call nested inside another
         * expression is still found.
         */
        private void findStroomCalls(final Expression expression,
                                    final XdmNode site,
                                    final String expressionText) {
            if (expression instanceof final FunctionCall functionCall
                && functionCall.getFunctionName() != null
                && NamespaceConstants.STROOM.equals(functionCall.getFunctionName().getURI())) {
                visitStroomCall(functionCall, site, expressionText);
            }
            for (final Operand operand : expression.operands()) {
                findStroomCalls(operand.getChildExpression(), site, expressionText);
            }
        }

        private void visitStroomCall(final FunctionCall functionCall,
                                     final XdmNode site,
                                     final String expressionText) {
            if (functionCall.getArity() < 1) {
                return;
            }
            final String localName = functionCall.getFunctionName().getLocalPart();
            final int lineNumber = lineNumberOf(site);
            final Expression firstArgument = functionCall.getArg(0);

            switch (localName) {
                case DICTIONARY_FUNCTION -> emitDictionary(firstArgument, site, expressionText, lineNumber);
                case LOOKUP_FUNCTION, BITMAP_LOOKUP_FUNCTION ->
                        emitMapRead(firstArgument, site, expressionText, lineNumber);
                case HTTP_CALL_FUNCTION -> emitEndpoint(
                        firstArgument, site, expressionText, lineNumber, XsltReferenceDirection.OUT);
                case FETCH_JSON_FUNCTION -> emitEndpoint(
                        firstArgument, site, expressionText, lineNumber, XsltReferenceDirection.IN);
                default -> {
                    // Some other Stroom function. Its arguments are still walked by the caller, so a
                    // lookup nested inside one is not missed.
                }
            }
        }

        private void emitDictionary(final Expression argument,
                                    final XdmNode site,
                                    final String expressionText,
                                    final int lineNumber) {
            final XsltValue value = valueResolver.resolve(argument, site);
            for (final String name : value.values()) {
                if (!name.isEmpty()) {
                    references.add(resolveDictionary(name, value.certainty(), lineNumber));
                }
            }
            addUnresolved(XsltReferenceKind.DICTIONARY, value, expressionText, lineNumber);
        }

        /**
         * A lookup contributes only a map name. Which store it reaches, if any, depends on the pipeline's
         * configured references rather than on the XSLT, so the parser does not attempt to resolve it.
         */
        private void emitMapRead(final Expression argument,
                                 final XdmNode site,
                                 final String expressionText,
                                 final int lineNumber) {
            final XsltValue value = valueResolver.resolve(argument, site);
            for (final String mapName : value.values()) {
                // A chained lookup names several maps in one argument, using each map's value as the key
                // for the next. Only the components are real map names; the joined string is not one.
                for (final String component : mapName.split(MAP_NEST_SEPARATOR, -1)) {
                    if (!component.isEmpty()) {
                        references.add(XsltReference.mapName(
                                XsltReferenceKind.REF_MAP_READ, component, value.certainty(), lineNumber));
                    }
                }
            }
            addUnresolved(XsltReferenceKind.REF_MAP_READ, value, expressionText, lineNumber);
        }

        private void emitEndpoint(final Expression argument,
                                  final XdmNode site,
                                  final String expressionText,
                                  final int lineNumber,
                                  final XsltReferenceDirection direction) {
            final XsltValue value = valueResolver.resolve(argument, site);
            for (final String url : value.values()) {
                if (!url.isEmpty()) {
                    references.add(XsltReference.endpoint(url, direction, value.certainty(), lineNumber));
                }
            }
            addUnresolved(XsltReferenceKind.HTTP, value, expressionText, lineNumber);
        }

        /**
         * Record why an argument could not be determined. The expression as written is used as the raw
         * value, because that is the text an author has to find in the source - the resolved value, by
         * definition, does not exist.
         */
        private void addUnresolved(final XsltReferenceKind kind,
                                   final XsltValue value,
                                   final String expressionText,
                                   final int lineNumber) {
            if (value.reason() != null) {
                references.add(XsltReference.unresolved(
                        kind, expressionText, value.reason(), value.certainty(), lineNumber));
            }
        }

        /**
         * Resolve an {@code xsl:import} or {@code xsl:include} target, by name first and then as a doc-ref
         * string, which is what {@code CustomURIResolver} does at runtime. Where the href is neither,
         * {@code parseDocRef} treats the whole value as a UUID.
         */
        private XsltReference resolveImport(final String href, final int lineNumber) {
            final List<DocRef> byName = lookup.findByName(XsltDoc.TYPE, href);
            if (byName.size() == 1) {
                return XsltReference.document(
                        XsltReferenceKind.IMPORT,
                        href,
                        byName.getFirst(),
                        XsltReferenceCertainty.STATIC,
                        lineNumber);
            }
            if (byName.size() > 1) {
                // The runtime throws here, so this is a broken stylesheet as well as an ambiguous name.
                return XsltReference.ambiguous(
                        XsltReferenceKind.IMPORT, href, byName, XsltReferenceCertainty.STATIC, lineNumber);
            }

            final DocRef parsed = CustomURIResolver.parseDocRef(href);
            final Optional<DocRef> byUuid = lookup.findByUuid(XsltDoc.TYPE, parsed.getUuid());
            return byUuid
                    .map(docRef -> XsltReference.document(
                            XsltReferenceKind.IMPORT,
                            href,
                            docRef,
                            XsltReferenceCertainty.STATIC,
                            lineNumber))
                    .orElseGet(() -> XsltReference.unresolved(
                            XsltReferenceKind.IMPORT,
                            href,
                            XsltReferenceReason.NOT_FOUND,
                            XsltReferenceCertainty.STATIC,
                            lineNumber));
        }

        /**
         * Resolve a {@code stroom:dictionary} argument, by UUID first and then by name, which is what
         * {@code Dictionary} does at runtime.
         * <p>
         * Where a name matches several dictionaries this reports every candidate rather than choosing.
         * The runtime does choose - {@code list.getFirst()} - but its choice is the lowest UUID, filtered
         * by the caller's permissions, so it is arbitrary and not reproducible from here.
         */
        private XsltReference resolveDictionary(final String name,
                                                final XsltReferenceCertainty certainty,
                                                final int lineNumber) {
            final Optional<DocRef> byUuid = lookup.findByUuid(DictionaryDoc.TYPE, name);
            if (byUuid.isPresent()) {
                return XsltReference.document(
                        XsltReferenceKind.DICTIONARY, name, byUuid.get(), certainty, lineNumber);
            }

            final List<DocRef> byName = lookup.findByName(DictionaryDoc.TYPE, name);
            if (byName.size() == 1) {
                return XsltReference.document(
                        XsltReferenceKind.DICTIONARY, name, byName.getFirst(), certainty, lineNumber);
            }
            if (byName.size() > 1) {
                return XsltReference.ambiguous(
                        XsltReferenceKind.DICTIONARY, name, byName, certainty, lineNumber);
            }
            return XsltReference.unresolved(
                    XsltReferenceKind.DICTIONARY,
                    name,
                    XsltReferenceReason.NOT_FOUND,
                    certainty,
                    lineNumber);
        }

        private int lineNumberOf(final XdmNode node) {
            final int lineNumber = node.getUnderlyingNode().getLineNumber();
            return lineNumber > 0
                    ? lineNumber
                    : -1;
        }
    }
}
