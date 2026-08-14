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
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StructuredQName;
import net.sf.saxon.trans.SymbolicName;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.SequenceType;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Lets <b>any</b> function in the Stroom namespace compile, without knowing which functions exist.
 * <p>
 * Saxon rejects an unknown function at compile time, which is a problem for a parser that reads one
 * document in isolation. The runtime avoids it by registering every real function
 * ({@code StroomXsltFunctionLibrary.init}), but each of those definitions is constructed with a
 * {@code Provider} of a pipeline-scoped object, and the parser must work with no pipeline scope - from a
 * migration, or a unit test.
 * <p>
 * A fixed list of the functions the parser cares about would not do either. Given
 * {@code stroom:lookup('MAP', stroom:meta('id'))}, an unregistered {@code stroom:meta} fails the whole
 * expression, losing the lookup that was the point. Any list would also drift as functions are added.
 * <p>
 * So this binds every {@code {stroom}*} name to a signature-only stub of the requested arity. Nothing is
 * ever evaluated - {@link StubCall#call} throws if anything tries - and no type checking happens, since
 * every argument and result is {@code item()*}. The parser needs the shape of the call, not its
 * behaviour.
 */
class AnyStroomFunctionLibrary implements FunctionLibrary {

    @Override
    public boolean isAvailable(final SymbolicName.F functionName) {
        return isStroomFunction(functionName);
    }

    @Override
    public @Nullable Expression bind(final SymbolicName.F functionName,
                                     final Expression[] arguments,
                                     final StaticContext env,
                                     final List<String> reasonsForFailure) {
        if (!isStroomFunction(functionName)) {
            // Returning null lets Saxon try the next library in the list, which is how an unknown
            // function in some other namespace still gets reported as an error.
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
        // Stateless, so there is nothing to copy.
        return this;
    }

    @Override
    public void setConfiguration(final Configuration config) {
        // No configuration needed.
    }

    private static boolean isStroomFunction(final SymbolicName.F functionName) {
        return functionName != null
               && functionName.getComponentName() != null
               && NamespaceConstants.STROOM.equals(functionName.getComponentName().getURI());
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
     * A call that cannot be called. The parser compiles expressions and reads the tree; it never
     * evaluates anything, and this makes an attempt to do so loud rather than silent.
     */
    private static class StubCall extends ExtensionFunctionCall {

        /**
         * The {@code arguments} parameter is a raw {@code Sequence[]} because Saxon declares it that way,
         * and Java will not let an implementor tighten it: {@code Sequence<?>[]} has the same erasure, so
         * it neither overrides nor clashes cleanly and the class fails to compile. The return type is not
         * so constrained, and is parameterised.
         */
        @Override
        public Sequence<?> call(final XPathContext context, final Sequence[] arguments) throws XPathException {
            throw new XPathException("The XSLT reference parser never evaluates expressions");
        }
    }
}
