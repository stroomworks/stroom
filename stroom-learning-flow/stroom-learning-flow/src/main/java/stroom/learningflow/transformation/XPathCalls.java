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

import stroom.util.xml.SaxonPolicy;
import stroom.util.xml.SaxonSecurity;

import net.sf.saxon.Configuration;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.Literal;
import net.sf.saxon.expr.StaticContext;
import net.sf.saxon.expr.parser.Token;
import net.sf.saxon.expr.parser.XPathParser;
import net.sf.saxon.functions.FunctionLibrary;
import net.sf.saxon.functions.FunctionLibraryList;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.pattern.Pattern;
import net.sf.saxon.query.AnnotationList;
import net.sf.saxon.sxpath.IndependentContext;
import net.sf.saxon.trans.SymbolicName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.z.IntSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Finds the function calls in one XPath expression or XSLT pattern using Saxon's own parser, so that what
/// counts as a call is exactly what Saxon will call. Comments, string literals, `=>` arrows and `Q{…}` names
/// are all read the way the engine reads them, which a text scan cannot guarantee.
///
/// Nothing is run. Each call the parser finds is resolved through a function library that records the
/// call's namespace, local name and arity and substitutes an empty sequence for it, so the expression is
/// taken apart without binding any real function, Stroom's or the standard library's.
final class XPathCalls {

    private static final Configuration CONFIGURATION =
            SaxonSecurity.newConfiguration(SaxonPolicy.LOCKED);

    // XPath 3.1, the level Stroom's XSLT 3.0 stylesheets are compiled at
    private static final int XPATH_LEVEL = 31;

    private XPathCalls() {
    }

    /// Finds the function calls in an XPath expression.
    ///
    /// @param expression The expression, with the namespace prefixes in scope where it sits.
    /// @return Every static call in it, in the order the parser found them.
    /// @throws Unreadable Where it cannot be read, or uses something this check refuses to read past.
    static List<Call> inExpression(final StylesheetScan.Expression expression) throws Unreadable {
        final Recorder recorder = new Recorder();
        final IndependentContext context = context(expression.scope(), recorder);
        try {
            new RecordingParser().parse(expression.text(), 0, Token.EOF, context);
        } catch (final Refused e) {
            throw new Unreadable(e.refusal);
        } catch (final XPathException | RuntimeException e) {
            throw unreadable(expression.text(), e);
        }
        return recorder.calls;
    }

    /// Finds the function calls in an XSLT match pattern, such as a template's `match` or a number's
    /// `count`.
    ///
    /// @param pattern The pattern, with the namespace prefixes in scope where it sits.
    /// @return Every static call in it, in the order the parser found them.
    /// @throws Unreadable Where it cannot be read.
    static List<Call> inPattern(final StylesheetScan.Expression pattern) throws Unreadable {
        final Recorder recorder = new Recorder();
        final IndependentContext context = context(pattern.scope(), recorder);
        try {
            Pattern.make(pattern.text(), context, context.getPackageData());
        } catch (final XPathException | RuntimeException e) {
            throw unreadable(pattern.text(), e);
        }
        return recorder.calls;
    }

    private static IndependentContext context(final Map<String, String> scope, final Recorder recorder) {
        final IndependentContext context = new IndependentContext(CONFIGURATION);
        scope.forEach((prefix, uri) -> {
            // The default namespace applies to elements, not functions, and a call without a prefix always
            // goes to the standard library, so only the prefixed declarations matter here.
            if (prefix != null && !prefix.isEmpty() && uri != null) {
                context.declareNamespace(prefix, uri);
            }
        });
        // Variables are declared elsewhere in the stylesheet. Whether one is declared is for the compiler to
        // check, and the expression that sets its value is checked where the variable is declared.
        context.setAllowUndeclaredVariables(true);
        context.setXPathLanguageLevel(XPATH_LEVEL);
        final FunctionLibraryList libraries = new FunctionLibraryList();
        libraries.addFunctionLibrary(recorder);
        context.setFunctionLibrary(libraries);
        return context;
    }

    private static Unreadable unreadable(final String text, final Exception e) {
        return new Unreadable("The XPath '" + text + "' could not be read, so what it calls cannot be checked: "
                              + e.getMessage());
    }


    // --------------------------------------------------------------------------------


    /// A static function call.
    ///
    /// @param name  Its resolved name: the namespace URI, and the prefix as written.
    /// @param arity How many arguments it was given.
    record Call(StructuredQName name, int arity) {

        String namespaceUri() {
            return name.getURI();
        }

        String localName() {
            return name.getLocalPart();
        }

        /// @return The name as the stylesheet wrote it, for a refusal the model will recognise.
        String displayName() {
            if (!name.getPrefix().isEmpty()) {
                return name.getPrefix() + ":" + name.getLocalPart();
            }
            if (AiXsltAllowList.FUNCTIONS_NAMESPACE.equals(name.getURI())) {
                return name.getLocalPart();
            }
            return name.getEQName();
        }
    }


    // --------------------------------------------------------------------------------


    /// Thrown for an expression this check cannot read, or will not read past. The message is the refusal.
    static final class Unreadable extends Exception {

        Unreadable(final String refusal) {
            super(refusal);
        }
    }


    // --------------------------------------------------------------------------------


    // Treats every function the parser asks for as available, records it, and substitutes an empty sequence
    // for the call, so that nothing real is bound.
    private static final class Recorder implements FunctionLibrary {

        private final List<Call> calls = new ArrayList<>();

        @Override
        public boolean isAvailable(final SymbolicName.F functionName) {
            return true;
        }

        @Override
        public Expression bind(final SymbolicName.F functionName,
                               final Expression[] staticArgs,
                               final StaticContext env,
                               final List<String> reasons) {
            calls.add(new Call(functionName.getComponentName(), functionName.getArity()));
            return Literal.makeEmptySequence();
        }

        @Override
        public FunctionLibrary copy() {
            return this;
        }
    }


    // --------------------------------------------------------------------------------


    // Saxon's parser, changed to refuse, with a plain reason, the constructs that call a function without
    // the call appearing, and to report an undeclared prefix in the same words as the rest of the check.
    private static final class RecordingParser extends XPathParser {

        RecordingParser() {
            setLanguage(XPATH, XPATH_LEVEL);
        }

        @Override
        protected StructuredQName resolveFunctionName(final String fname) throws XPathException {
            final int colon = fname.indexOf(':');
            if (colon > 0 && !fname.startsWith("Q{")) {
                final String prefix = fname.substring(0, colon);
                if (env.getNamespaceResolver().getURIForPrefix(prefix, false) == null) {
                    throw new Refused("'" + fname + "' uses the namespace prefix '" + prefix
                                      + "', which the stylesheet does not declare, so what it calls cannot be "
                                      + "checked.");
                }
            }
            return super.resolveFunctionName(fname);
        }

        @Override
        protected Expression parseNamedFunctionReference() throws XPathException {
            throw new Refused("A learned stylesheet may not refer to a function by name and arity ('"
                              + t.currentTokenValue + "#'): it calls a function without the call being "
                              + "visible.");
        }

        @Override
        protected Expression parseInlineFunction(final AnnotationList annotations) throws XPathException {
            throw new Refused("A learned stylesheet may not declare an inline function: Stroom's XSLT engine "
                              + "does not support them. Use xsl:function instead.");
        }

        @Override
        protected Expression makeCurriedFunction(final int offset,
                                                 final StructuredQName name,
                                                 final Expression[] args,
                                                 final IntSet placeMarkers) throws XPathException {
            throw new Refused("A learned stylesheet may not use an argument placeholder ('?') in a call to '"
                              + name.getDisplayName() + "': Stroom's XSLT engine does not support them.");
        }
    }


    // --------------------------------------------------------------------------------


    // A refusal raised inside the parser, thrown as the exception type the parser declares.
    private static final class Refused extends XPathException {

        private final String refusal;

        private Refused(final String refusal) {
            super(refusal);
            this.refusal = refusal;
        }
    }
}
