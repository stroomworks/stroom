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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Checks a stylesheet written by the model, without running it, for anything that is not on the
/// [allow-list][AiXsltAllowList]. It runs when a candidate is judged and again when it is promoted, and what
/// it refuses is given back to the model as the reason, like any other check.
///
/// **It refuses what it cannot read.** A stylesheet that does not parse, an XPath it cannot take apart, a
/// function reference such as `stroom:http-call#5`, or a namespace prefix that is not declared are each
/// refused rather than passed, because the point of an allow-list is that an unfamiliar route is closed.
/// Nothing enforces the list at run time, so a route this check misses would run unchecked on a node.
public final class LearnedStylesheetCheck {

    /// A name followed by an opening bracket: the shape of a function call, and of several things that are
    /// not calls, which [#NOT_CALLS] filters out.
    private static final Pattern CALL = Pattern.compile(
            "(?<![-\\w.])(?:([\\w.-]+):)?([\\w.-]+)\\s*\\(");

    /// A reference to a function by name and arity, such as `concat#3`, which lets it be called without the
    /// call appearing. It is refused outright, whatever the function.
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![-\\w.])(?:([\\w.-]+):)?([\\w.-]+)\\s*#\\s*\\d+");

    /// Words that can be followed by a bracket without being a function call: XPath's kind tests and
    /// sequence types, and the keywords of its expressions. None of them reaches outside the document being
    /// transformed.
    private static final Set<String> NOT_CALLS = Set.of(
            // kind tests and sequence types
            "node", "text", "comment", "processing-instruction", "element", "attribute", "document-node",
            "schema-element", "schema-attribute", "namespace-node", "item", "empty-sequence", "function",
            "map", "array",
            // expression keywords that may be followed by a bracket
            "if", "for", "some", "every", "let", "return", "then", "else", "and", "or",
            "div", "idiv", "mod", "union", "intersect", "except", "to", "instance", "of", "castable",
            "cast", "as", "treat", "is", "eq", "ne", "lt", "le", "gt", "ge", "in", "satisfies", "switch",
            "typeswitch", "try", "catch");

    /// The attributes of an XSLT element that hold an XPath expression outright, rather than text that may
    /// contain one in braces.
    static final Set<String> XPATH_ATTRIBUTES = Set.of(
            "select", "test", "match", "use", "group-by", "group-adjacent", "group-starting-with",
            "group-ending-with", "from", "count", "value", "key", "initial-value", "predicate", "with",
            "context-item", "on-completion");

    private LearnedStylesheetCheck() {
    }

    /// Reads a stylesheet and lists what it uses that it may not.
    ///
    /// @param stylesheet The candidate stylesheet.
    /// @param allowList  What it may use.
    /// @return One sentence per refusal, in the order they were met, and empty where the stylesheet uses
    ///         only what is allowed. A stylesheet that cannot be read gives one refusal saying so.
    public static List<String> refusals(final String stylesheet, final AiXsltAllowList allowList) {
        if (stylesheet == null || stylesheet.isBlank()) {
            return List.of("The stylesheet is empty.");
        }
        final Refusals refusals = new Refusals();
        final StylesheetScan scan;
        try {
            scan = StylesheetScan.of(stylesheet);
        } catch (final RuntimeException e) {
            return List.of("The stylesheet could not be read, so what it uses cannot be checked: "
                           + e.getMessage());
        }
        for (final StylesheetScan.Instruction instruction : scan.instructions()) {
            if (!allowList.allowsInstruction(instruction.localName())) {
                refusals.add(refusalForInstruction(instruction.localName(), allowList));
            }
        }
        for (final StylesheetScan.Expression expression : scan.expressions()) {
            check(expression, scan.declaredFunctions(), allowList, refusals);
        }
        return refusals.messages();
    }

    /// Whether a stylesheet uses only what the allow-list allows.
    ///
    /// @param stylesheet The candidate stylesheet.
    /// @param allowList  What it may use.
    /// @return Whether the stylesheet uses only what is allowed.
    public static boolean passes(final String stylesheet, final AiXsltAllowList allowList) {
        return refusals(stylesheet, allowList).isEmpty();
    }

    /// Returns every literal value in a stylesheet: the text of literal result elements, and the string
    /// literals in its XPath. The literals check uses these to find values copied from the stream the
    /// translation was learned on.
    ///
    /// @param stylesheet The stylesheet.
    /// @return The values, in the order they appear; empty where it cannot be read, since a stylesheet
    ///         that will not parse is for the compile check to report, not this one.
    public static List<String> literals(final String stylesheet) {
        if (stylesheet == null || stylesheet.isBlank()) {
            return List.of();
        }
        final StylesheetScan scan;
        try {
            scan = StylesheetScan.of(stylesheet);
        } catch (final RuntimeException e) {
            return List.of();
        }
        final List<String> literals = new ArrayList<>(scan.literals());
        for (final StylesheetScan.Expression expression : scan.expressions()) {
            literals.addAll(quoted(expression.text()));
        }
        return literals;
    }

    /// Returns the contents of each non-empty string literal in an XPath expression.
    ///
    /// @param text The expression.
    /// @return What each literal holds, without its quotes.
    static List<String> quoted(final String text) {
        final List<String> found = new ArrayList<>();
        char quote = 0;
        StringBuilder current = null;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote == 0 && (c == '\'' || c == '"')) {
                quote = c;
                current = new StringBuilder();
            } else if (quote != 0 && c == quote) {
                quote = 0;
                if (!current.isEmpty()) {
                    found.add(current.toString());
                }
                current = null;
            } else if (quote != 0) {
                current.append(c);
            }
        }
        return found;
    }

    private static void check(final StylesheetScan.Expression expression,
                              final Set<String> declaredFunctions,
                              final AiXsltAllowList allowList,
                              final Refusals refusals) {
        final String text = withoutLiterals(expression.text());

        final Matcher reference = REFERENCE.matcher(text);
        while (reference.find()) {
            final String name = qualified(reference.group(1), reference.group(2));
            refusals.add("A learned stylesheet may not refer to a function by name and arity ('"
                         + name + "#'): it calls a function without the call being visible.");
        }

        final Matcher call = CALL.matcher(text);
        while (call.find()) {
            final String prefix = call.group(1);
            final String localName = call.group(2);
            if (prefix == null && NOT_CALLS.contains(localName)) {
                continue;
            }
            if (declaredFunctions.contains(qualified(prefix, localName))) {
                // A function the stylesheet declares itself; its body is checked like any other expression
                continue;
            }
            final String namespaceUri = expression.resolve(prefix);
            if (namespaceUri == null) {
                refusals.add("'" + qualified(prefix, localName) + "' uses the namespace prefix '" + prefix
                             + "', which the stylesheet does not declare, so what it calls cannot be checked.");
            } else if (!allowList.allowsFunction(namespaceUri, localName)) {
                refusals.add(refusalForFunction(qualified(prefix, localName), namespaceUri, localName,
                        allowList));
            }
        }
    }

    private static String refusalForInstruction(final String localName, final AiXsltAllowList allowList) {
        final String reason = allowList.reasonAgainst(null, localName);
        return "A learned stylesheet may not use 'xsl:" + localName + "'"
               + (reason == null
                       ? "."
                       : ": " + reason + ".");
    }

    private static String refusalForFunction(final String name,
                                             final String namespaceUri,
                                             final String localName,
                                             final AiXsltAllowList allowList) {
        final String reason = allowList.reasonAgainst(namespaceUri, localName);
        return "A learned stylesheet may not call '" + name + "'"
               + (reason == null
                       ? "."
                       : ": " + reason + ".");
    }

    private static String qualified(final String prefix, final String localName) {
        return prefix == null
                ? localName
                : prefix + ":" + localName;
    }

    /// Replaces the contents of every string literal with spaces, so that a name inside one is not read as
    /// a call. XPath escapes a quote by doubling it, which this handles without special treatment: the first
    /// quote ends the literal and the second opens another.
    ///
    /// @param text An XPath expression.
    /// @return The expression with its literals blanked, the same length as it was.
    static String withoutLiterals(final String text) {
        final StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (quote == 0 && (c == '\'' || c == '"')) {
                quote = c;
                out.append(c);
            } else if (quote != 0 && c == quote) {
                quote = 0;
                out.append(c);
            } else {
                out.append(quote == 0
                        ? c
                        : ' ');
            }
        }
        return out.toString();
    }


    // --------------------------------------------------------------------------------


    // The refusals found so far, each kept once however many times it is found, in the order first found.
    private static final class Refusals {

        private final Set<String> messages = new LinkedHashSet<>();

        private void add(final String message) {
            messages.add(message);
        }

        private List<String> messages() {
            return new ArrayList<>(messages);
        }
    }
}
