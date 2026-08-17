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

import stroom.pipeline.xml.NamespaceConstants;

import net.sf.saxon.Configuration;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.StaticContext;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.functions.FunctionLibrary;
import net.sf.saxon.functions.IntegratedFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionCall;
import net.sf.saxon.lib.ExtensionFunctionDefinition;
import net.sf.saxon.lib.NamespaceConstant;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.trans.SymbolicName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.SequenceType;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Lets functions compile that a standalone XPath compiler would otherwise reject, by binding them to
 * signature-only stubs.
 * <p>
 * The parser compiles the expressions out of a stylesheet one at a time, which is not the context they were
 * written for, and two whole families of function are unavailable as a result. Neither is optional: an
 * expression that will not compile is an expression the parser cannot read, and a {@code stroom:lookup}
 * nested inside one is simply lost.
 * <p>
 * Nothing is ever evaluated - {@link StubCall#call} throws if anything tries - and no type checking happens,
 * since every argument and result is {@code item()*}. The parser needs the shape of a call, not its
 * behaviour.
 *
 * @see #anyStroomFunction()
 * @see #xsltOnlyFunctions()
 */
class StubFunctionLibrary implements FunctionLibrary {

    /**
     * Functions defined by XSLT rather than by XPath, so unknown to a standalone XPath compiler. Every name
     * here was confirmed rejected by Saxon at XPath 3.1, and names it does implement are deliberately
     * absent - stubbing {@code concat} in particular would break the folding that depends on it.
     * <p>
     * Unlike the Stroom namespace, this list is fixed by the XSLT specification rather than by what Stroom
     * happens to register, so an allow list is appropriate here and a catch-all is not.
     */
    private static final Set<String> XSLT_ONLY_FUNCTIONS = Set.of(
            "current",
            "current-group",
            "current-grouping-key",
            "current-merge-group",
            "current-merge-key",
            "key",
            "regex-group",
            "system-property",
            "available-system-properties",
            "unparsed-entity-uri",
            "unparsed-entity-public-id",
            "element-available",
            "function-available",
            "type-available",
            "document",
            "accumulator-before",
            "accumulator-after");

    private final Predicate<StructuredQName> matches;

    private StubFunctionLibrary(final Predicate<StructuredQName> matches) {
        this.matches = Objects.requireNonNull(matches, "Null matches supplied");
    }

    /**
     * Binds <b>any</b> function in the Stroom namespace, whatever its name.
     * <p>
     * The runtime registers every real one ({@code StroomXsltFunctionLibrary.init}), but each of those
     * definitions is constructed with a {@code Provider} of a pipeline-scoped object, and the parser must
     * work with no pipeline scope - from a migration, or a unit test.
     * <p>
     * A list of just the functions the parser cares about would not do either. Given
     * {@code stroom:lookup('MAP', stroom:meta('id'))}, an unregistered {@code stroom:meta} fails the whole
     * expression, losing the lookup that was the point. Any list would also drift as functions are added.
     */
    static StubFunctionLibrary anyStroomFunction() {
        return new StubFunctionLibrary(name -> NamespaceConstants.STROOM.equals(name.getURI()));
    }

    /**
     * Binds the functions XSLT defines but XPath does not, such as {@code current-grouping-key()} inside an
     * {@code xsl:for-each-group}.
     * <p>
     * Found by running the parser over real content: every expression in a stylesheet that grouped its
     * input came back unanalysable, because those functions exist only within XSLT. Anything the parser
     * looks for inside such an expression was being missed.
     */
    static StubFunctionLibrary xsltOnlyFunctions() {
        return new StubFunctionLibrary(name ->
                NamespaceConstant.FN.equals(name.getURI())
                && XSLT_ONLY_FUNCTIONS.contains(name.getLocalPart()));
    }

    @Override
    public boolean isAvailable(final SymbolicName.F functionName) {
        return isStub(functionName);
    }

    @Override
    public @Nullable Expression bind(final SymbolicName.F functionName,
                                     final Expression[] arguments,
                                     final StaticContext env,
                                     final List<String> reasonsForFailure) {
        if (!isStub(functionName)) {
            // Returning null lets Saxon try the next library in the list, which is how a function this
            // library does not claim still gets resolved, or reported as an error.
            return null;
        }

        final StructuredQName name = functionName.getComponentName();
        final ExtensionFunctionDefinition definition = new StubDefinition(name, arguments.length);
        final ExtensionFunctionCall call = definition.makeCallExpression();
        call.setDefinition(definition);

        final IntegratedFunctionCall functionCall = new IntegratedFunctionCall(name, call);
        functionCall.setArguments(arguments);
        functionCall.setResultType(SequenceType.ANY_SEQUENCE);
        return functionCall;
    }

    @Override
    public FunctionLibrary copy() {
        // Immutable, so there is nothing to copy.
        return this;
    }

    @Override
    public void setConfiguration(final Configuration config) {
        // No configuration needed.
    }

    private boolean isStub(final SymbolicName.F functionName) {
        return functionName != null
               && functionName.getComponentName() != null
               && matches.test(functionName.getComponentName());
    }

    /**
     * A definition that exists only to give a call the right name and arity.
     */
    private static class StubDefinition extends ExtensionFunctionDefinition {

        private final StructuredQName name;
        private final int arity;

        StubDefinition(final StructuredQName name, final int arity) {
            this.name = name;
            this.arity = arity;
        }

        @Override
        public StructuredQName getFunctionQName() {
            return name;
        }

        @Override
        public int getMinimumNumberOfArguments() {
            return arity;
        }

        @Override
        public int getMaximumNumberOfArguments() {
            return arity;
        }

        @Override
        public SequenceType[] getArgumentTypes() {
            // item()* throughout, so no argument is ever rejected or converted. A stylesheet the runtime
            // would refuse must still parse - the parser does not validate.
            final SequenceType[] argumentTypes = new SequenceType[arity];
            Arrays.fill(argumentTypes, SequenceType.ANY_SEQUENCE);
            return argumentTypes;
        }

        @Override
        public SequenceType getResultType(final SequenceType[] suppliedArgumentTypes) {
            return SequenceType.ANY_SEQUENCE;
        }

        @Override
        public ExtensionFunctionCall makeCallExpression() {
            return new StubCall();
        }
    }

    /**
     * A call that cannot be called. The parser compiles expressions and reads the tree; it never evaluates
     * anything, and this makes an attempt to do so loud rather than silent.
     */
    private static class StubCall extends ExtensionFunctionCall {

        /**
         * The {@code arguments} parameter is a raw {@code Sequence[]} because Saxon declares it that way,
         * and Java will not let an implementor tighten it: {@code Sequence<?>[]} has the same erasure, so
         * it neither overrides nor clashes cleanly and the class fails to compile. The return type is not
         * so constrained, and is parameterised.
         */
        @Override
        @SuppressWarnings("rawtypes")
        public Sequence<?> call(final XPathContext context, final Sequence[] arguments) throws XPathException {
            throw new XPathException("The XSLT reference parser never evaluates expressions");
        }
    }
}
