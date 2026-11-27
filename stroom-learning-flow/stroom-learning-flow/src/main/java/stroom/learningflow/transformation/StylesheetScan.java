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

package stroom.learningflow.transformation;

import stroom.learningflow.scoring.ConfinedXml;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// A stylesheet broken down far enough for [LearnedStylesheetCheck] to check it: the XSLT elements it uses,
/// every XPath expression in it with the namespace prefixes in scope where that expression sits, the
/// functions it declares itself, and its literal text.
///
/// It is parsed with the restricted parser the rest of the module uses ([ConfinedXml]), so reading the
/// stylesheet cannot itself fetch a DTD or an external entity.
final class StylesheetScan {

    private final List<Instruction> instructions;
    private final List<Expression> expressions;
    private final Set<String> declaredFunctions;
    private final List<String> literals;

    private StylesheetScan(final List<Instruction> instructions,
                           final List<Expression> expressions,
                           final Set<String> declaredFunctions,
                           final List<String> literals) {
        this.instructions = instructions;
        this.expressions = expressions;
        this.declaredFunctions = declaredFunctions;
        this.literals = literals;
    }

    /// Reads a stylesheet.
    ///
    /// @param stylesheet The stylesheet.
    /// @return The parts of the stylesheet.
    /// @throws IllegalArgumentException where it does not parse.
    static StylesheetScan of(final String stylesheet) {
        final Handler handler = new Handler();
        try {
            final XMLReader reader = ConfinedXml.reader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new org.xml.sax.InputSource(new java.io.StringReader(stylesheet)));
        } catch (final SAXException | java.io.IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return new StylesheetScan(handler.instructions, handler.expressions, handler.declaredFunctions,
                handler.literals);
    }

    List<Instruction> instructions() {
        return instructions;
    }

    List<Expression> expressions() {
        return expressions;
    }

    Set<String> declaredFunctions() {
        return declaredFunctions;
    }

    /// Returns every piece of non-blank text in the stylesheet, stripped, which is what it writes out as
    /// itself rather than reading from the record. String literals in its XPath are not included here;
    /// [LearnedStylesheetCheck#literals] adds those.
    ///
    /// @return The text, in the order it appears.
    List<String> literals() {
        return literals;
    }


    // --------------------------------------------------------------------------------


    // Collects the elements, expressions, declarations and text as SAX reports them, keeping track of the
    // namespace prefixes in scope so that each expression can resolve its own.
    private static final class Handler extends DefaultHandler {

        private final List<Instruction> instructions = new ArrayList<>();
        private final List<Expression> expressions = new ArrayList<>();
        private final Set<String> declaredFunctions = new HashSet<>();
        private final List<String> literals = new ArrayList<>();
        private final Deque<Map<String, String>> scopes = new ArrayDeque<>();
        private Map<String, String> pending = new HashMap<>();

        @Override
        public void startPrefixMapping(final String prefix, final String uri) {
            pending.put(prefix, uri);
        }

        @Override
        public void startElement(final String uri,
                                 final String localName,
                                 final String qName,
                                 final Attributes attributes) {
            final Map<String, String> scope = new HashMap<>(scopes.isEmpty()
                    ? Map.of()
                    : scopes.peek());
            scope.putAll(pending);
            pending = new HashMap<>();
            scopes.push(scope);

            final boolean xslt = AiXsltAllowList.XSLT_NAMESPACE.equals(uri);
            if (xslt) {
                instructions.add(new Instruction(localName));
                if ("function".equals(localName)) {
                    final String name = attributes.getValue("name");
                    if (name != null) {
                        declaredFunctions.add(name.trim());
                    }
                }
            }
            for (int i = 0; i < attributes.getLength(); i++) {
                final String attributeName = attributes.getLocalName(i);
                final String value = attributes.getValue(i);
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (xslt && LearnedStylesheetCheck.XPATH_ATTRIBUTES.contains(attributeName)) {
                    expressions.add(new Expression(value, scope));
                } else {
                    // Any other attribute may hold attribute value templates, and a literal result
                    // element's attributes are the usual place for them
                    for (final String braced : braced(value)) {
                        expressions.add(new Expression(braced, scope));
                    }
                }
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) {
            scopes.pop();
        }

        @Override
        public void characters(final char[] ch, final int start, final int length) {
            // Text holds expressions in braces only where expand-text is on (a text value template), which
            // this scan does not track; checking braces in all text costs nothing and closes the gap
            final String text = new String(ch, start, length);
            if (!text.isBlank()) {
                // Text the stylesheet writes out as itself is the likeliest place for a value copied from
                // the stream it was learned on
                literals.add(text.strip());
            }
            if (text.indexOf('{') >= 0) {
                final Map<String, String> scope = scopes.isEmpty()
                        ? Map.of()
                        : scopes.peek();
                for (final String braced : braced(text)) {
                    expressions.add(new Expression(braced, scope));
                }
            }
        }

        // Returns the expressions inside the braces of an attribute value template, in order. `{{` is read as
        // an escaped brace rather than the start of an expression.
        private static List<String> braced(final String value) {
            final List<String> found = new ArrayList<>();
            int i = 0;
            while (i < value.length()) {
                final char c = value.charAt(i);
                if (c == '{') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '{') {
                        i += 2;
                        continue;
                    }
                    final int close = closing(value, i + 1);
                    if (close < 0) {
                        // Don't skip an unclosed brace: pass the rest to the check, which refuses what it
                        // cannot make sense of
                        found.add(value.substring(i + 1));
                        return found;
                    }
                    found.add(value.substring(i + 1, close));
                    i = close + 1;
                } else {
                    i++;
                }
            }
            return found;
        }

        // Returns the index of the closing brace at or after `from` (just after the opening brace), ignoring
        // braces inside string literals, or -1 if there is none.
        private static int closing(final String value, final int from) {
            char quote = 0;
            for (int i = from; i < value.length(); i++) {
                final char c = value.charAt(i);
                if (quote == 0 && (c == '\'' || c == '"')) {
                    quote = c;
                } else if (quote != 0 && c == quote) {
                    quote = 0;
                } else if (quote == 0 && c == '}') {
                    return i;
                }
            }
            return -1;
        }
    }


    // --------------------------------------------------------------------------------


    /// An element of the XSLT namespace.
    ///
    /// @param localName Its local name, e.g. `value-of`.
    record Instruction(String localName) {

    }


    // --------------------------------------------------------------------------------


    /// An XPath expression, with the namespace prefixes in scope where it sits.
    ///
    /// @param text  The expression.
    /// @param scope The prefixes in scope, by prefix.
    record Expression(String text, Map<String, String> scope) {

        /// Resolves a function name's prefix to its namespace.
        ///
        /// @param prefix The prefix, or null for none.
        /// @return The namespace it stands for; the standard function library for no prefix, and null for a
        ///         prefix the stylesheet does not declare.
        String resolve(final String prefix) {
            if (prefix == null) {
                return AiXsltAllowList.FUNCTIONS_NAMESPACE;
            }
            return scope.get(prefix);
        }
    }
}
