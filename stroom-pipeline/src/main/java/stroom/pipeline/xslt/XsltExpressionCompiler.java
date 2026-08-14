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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.transform.stream.StreamSource;

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
        processor.getUnderlyingConfiguration()
                .getBuiltInExtensionLibraryList()
                .addFunctionLibrary(new AnyStroomFunctionLibrary());
    }

    /**
     * Read an XSLT body as a tree.
     *
     * @param xsltData The body. Must not be null.
     * @return the document node.
     * @throws SaxonApiException if the body is not well-formed XML.
     */
    XdmNode buildTree(final String xsltData) throws SaxonApiException {
        Objects.requireNonNull(xsltData, "Null xsltData supplied");
        final DocumentBuilder documentBuilder = processor.newDocumentBuilder();
        // So a finding can point at a line rather than merely describing the value.
        documentBuilder.setLineNumbering(true);
        return documentBuilder.build(new StreamSource(new StringReader(xsltData)));
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
