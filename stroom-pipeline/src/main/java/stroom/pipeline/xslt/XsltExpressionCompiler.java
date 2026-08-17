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

import net.sf.saxon.expr.Expression;
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.DocumentBuilder;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XdmNode;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;

/**
 * The Saxon plumbing: builds the stylesheet tree, and compiles the expressions found inside it.
 * <p>
 * Compiling an XPath expression lifted out of an attribute is not quite the same as compiling one in
 * isolation, and two adjustments make it work:
 * <ul>
 *     <li><b>Namespaces come from the element.</b> Prefixes are arbitrary - a stylesheet may bind
 *     {@code xmlns:s="stroom"} and call {@code s:dictionary()} - so each expression is compiled with the
 *     prefixes in scope at the element it came from, and functions are recognised by namespace URI.</li>
 *     <li><b>Variables are allowed to be undeclared.</b> The parser resolves variables itself, from the
 *     stylesheet's own declarations, so Saxon must not reject a reference to one it has not been told
 *     about.</li>
 * </ul>
 * <p>
 * The {@link Processor} is created once and shared. Saxon permits concurrent compilation against one
 * configuration, and a fresh {@link XPathCompiler} per element keeps one element's prefix bindings from
 * leaking into another's.
 */
class XsltExpressionCompiler {

    private final Processor processor;

    XsltExpressionCompiler() {
        processor = new Processor(false);
        // Two families of function would otherwise fail to compile, taking the whole expression - and
        // anything the parser was looking for inside it - with them.
        processor.getUnderlyingConfiguration()
                .getBuiltInExtensionLibraryList()
                .addFunctionLibrary(StubFunctionLibrary.anyStroomFunction());
        processor.getUnderlyingConfiguration()
                .getBuiltInExtensionLibraryList()
                .addFunctionLibrary(StubFunctionLibrary.xsltOnlyFunctions());
    }

    /**
     * Read an XSLT body as a tree.
     * <p>
     * Parsed through a deliberately restricted reader - see {@link #newSecureXmlReader()} - because this
     * runs on the save path and the body is whatever a user typed.
     *
     * @param xsltData The body. Must not be null.
     * @return the document node.
     * @throws SaxonApiException if the body is not well-formed XML, or uses XML features that are refused.
     */
    XdmNode buildTree(final String xsltData) throws SaxonApiException {
        Objects.requireNonNull(xsltData, "Null xsltData supplied");
        final DocumentBuilder documentBuilder = processor.newDocumentBuilder();
        // So a finding can point at a line rather than merely describing the value.
        documentBuilder.setLineNumbering(true);
        try {
            return documentBuilder.build(
                    new SAXSource(newSecureXmlReader(), new InputSource(new StringReader(xsltData))));
        } catch (final ParserConfigurationException | SAXException e) {
            // Cannot configure a safe parser, so decline to parse at all rather than fall back to an
            // unsafe one. The caller treats this as an unreadable document, which is the safe outcome.
            throw new SaxonApiException("Unable to create a secure XML reader: " + e.getMessage(), e);
        }
    }

    /**
     * An XML reader that will not fetch anything and will not expand an entity indefinitely.
     * <p>
     * Both matter here in a way they would not for trusted input. This parses on save, so the document is
     * arbitrary text from any user permitted to edit a stylesheet, and anything the parser extracts is
     * shown straight back in the editor.
     * <ul>
     *     <li><b>External entities are refused.</b> Otherwise
     *     {@code <!ENTITY x SYSTEM "file:///etc/passwd">} followed by {@code <map>&x;</map>} has the parser
     *     read that file and report its contents as a map name - arbitrary file disclosure, triggered by
     *     saving a document and read back from the References tab.</li>
     *     <li><b>Entity expansion is bounded</b> by secure processing. Otherwise a few nested entity
     *     definitions expand to gigabytes inside the XML parse, which the parser's own timeout cannot help
     *     with because it only checks between elements, after the tree has been built.</li>
     * </ul>
     * <p>
     * Internal entities within those limits still work, so a stylesheet using a DTD for its own convenience
     * is unaffected. A stylesheet that relies on fetching something does not, and that is the intent.
     */
    private static XMLReader newSecureXmlReader() throws ParserConfigurationException, SAXException {
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        // Namespace awareness is not optional: the parser identifies both XSLT elements and Stroom
        // functions by namespace URI, never by prefix.
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newSAXParser().getXMLReader();
    }

    /**
     * Compile an XPath expression as written at the given element.
     *
     * @param element    The element the expression was taken from, supplying the in-scope namespaces.
     * @param expression The expression text.
     * @return the compiled expression tree.
     * @throws SaxonApiException if the expression is not valid XPath.
     */
    Expression compileExpression(final XdmNode element, final String expression) throws SaxonApiException {
        return compilerFor(element).compile(expression).getUnderlyingExpression().getInternalExpression();
    }

    /**
     * Compile a match pattern, e.g. {@code xsl:template/@match}. Patterns are compiled rather than
     * skipped because their predicates can hold function calls, as in
     * {@code match="record[stroom:lookup('M', @a)]"}.
     *
     * @param element The element the pattern was taken from, supplying the in-scope namespaces.
     * @param pattern The pattern text.
     * @return the compiled pattern as an expression tree.
     * @throws SaxonApiException if the pattern is not valid.
     */
    Expression compilePattern(final XdmNode element, final String pattern) throws SaxonApiException {
        return compilerFor(element).compilePattern(pattern).getUnderlyingExpression().getInternalExpression();
    }

    private XPathCompiler compilerFor(final XdmNode element) {
        Objects.requireNonNull(element, "Null element supplied");
        final XPathCompiler compiler = processor.newXPathCompiler();
        // The parser resolves variables from the stylesheet, so Saxon must tolerate references to
        // variables it has not been told about.
        compiler.setAllowUndeclaredVariables(true);
        // The most permissive version available, so that syntax the runtime would accept does not come
        // back as unanalysable here. Failing to compile costs a finding; accepting too much costs nothing,
        // since nothing is ever evaluated.
        compiler.setLanguageVersion("3.1");
        element.axisIterator(Axis.NAMESPACE).forEachRemaining(namespaceNode -> {
            // A namespace node's name is the prefix, absent for the default namespace, and its value is
            // the URI.
            final String prefix = namespaceNode.getNodeName() == null
                    ? ""
                    : namespaceNode.getNodeName().getLocalName();
            compiler.declareNamespace(prefix, namespaceNode.getStringValue());
        });
        return compiler;
    }

    /**
     * Split an attribute value template into the expressions it embeds.
     * <p>
     * Scans rather than pattern-matches, because the content of the braces is XPath and may itself hold
     * braces inside string literals. Doubled braces are literal text and yield nothing.
     *
     * @param attributeValue The raw attribute value. Must not be null.
     * @return the embedded expressions, in order, empty where there are none.
     */
    static List<String> splitAttributeValueTemplate(final String attributeValue) {
        Objects.requireNonNull(attributeValue, "Null attributeValue supplied");
        final List<String> expressions = new ArrayList<>();

        int index = 0;
        while (index < attributeValue.length()) {
            final char c = attributeValue.charAt(index);
            if (c == '{') {
                if (isDoubled(attributeValue, index, '{')) {
                    index += 2;
                } else {
                    final int end = findExpressionEnd(attributeValue, index + 1);
                    if (end < 0) {
                        // Unbalanced. Not our business to report - the stylesheet will not compile
                        // anyway - so take what is there and stop.
                        break;
                    }
                    final String expression = attributeValue.substring(index + 1, end).trim();
                    if (!expression.isEmpty()) {
                        expressions.add(expression);
                    }
                    index = end + 1;
                }
            } else if (c == '}' && isDoubled(attributeValue, index, '}')) {
                index += 2;
            } else {
                index++;
            }
        }
        return expressions;
    }

    private static boolean isDoubled(final String value, final int index, final char c) {
        return index + 1 < value.length() && value.charAt(index + 1) == c;
    }

    /**
     * @return the index of the closing brace of an embedded expression, or -1 if there is none. Quoted
     * strings are skipped so that a brace inside one does not end the expression early.
     */
    private static int findExpressionEnd(final String value, final int start) {
        char quote = 0;
        for (int index = start; index < value.length(); index++) {
            final char c = value.charAt(index);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '}') {
                return index;
            }
        }
        return -1;
    }
}
