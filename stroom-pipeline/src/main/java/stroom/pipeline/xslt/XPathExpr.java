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

import net.sf.saxon.expr.AtomicSequenceConverter;
import net.sf.saxon.expr.Atomizer;
import net.sf.saxon.expr.AxisExpression;
import net.sf.saxon.expr.CardinalityChecker;
import net.sf.saxon.expr.ContextItemExpression;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.FunctionCall;
import net.sf.saxon.expr.ItemChecker;
import net.sf.saxon.expr.Literal;
import net.sf.saxon.expr.Operand;
import net.sf.saxon.expr.RootExpression;
import net.sf.saxon.expr.SingletonAtomizer;
import net.sf.saxon.expr.StringLiteral;
import net.sf.saxon.expr.UntypedSequenceConverter;
import net.sf.saxon.expr.VariableReference;
import net.sf.saxon.expr.instruct.Choose;
import net.sf.saxon.lib.NamespaceConstant;
import net.sf.saxon.om.StructuredQName;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One node of a compiled XPath expression, as much of it as this parser needs to know.
 * <p>
 * <b>Why this class exists.</b> Saxon exposes no public API for reading a compiled expression. Its
 * {@code s9api} package will compile an expression and evaluate it, but says nothing about its shape,
 * and shape is the whole question here: which string literals appear as the first argument of which
 * function. So the parser reaches past {@code s9api} into Saxon's internal expression tree -
 * {@code Expression}, {@code Operand}, {@code StringLiteral}, {@code Choose} and the rest - none of
 * which is public API, and any of which a Saxon upgrade may change with no deprecation cycle.
 * <p>
 * That risk cannot be removed while the parser uses Saxon. It can be <b>confined</b>, and this class is
 * where it is confined to. Nothing outside this class and {@link XsltExpressionCompiler} names a Saxon
 * expression type, so an upgrade that changes the AST is an edit here rather than an audit of the whole
 * package. The rules the parser applies - what counts as a reference, what a name resolves to, when a
 * value is unknowable - live in {@link XsltValueResolver} and {@link XsltReferenceParserImpl} and are
 * expressed entirely in terms of this interface.
 * <p>
 * <b>Wrappers are stepped through on construction.</b> Saxon inserts atomizers and type checkers that
 * say nothing about a value - {@code concat('a', $v)} holds a {@code SingletonAtomizer} rather than the
 * variable reference itself - so every instance handed out represents the first node that carries
 * meaning. Callers therefore never unwrap, and cannot forget to. This loses nothing: a wrapper is never
 * itself a function call or a literal, and its child is still reached.
 */
final class XPathExpr {

    /**
     * What sort of node this is, in the terms the parser cares about. Everything Saxon can produce that
     * is none of these is {@link #OTHER} - deliberately, since the parser's answer for anything it
     * cannot fold is the same regardless of what it happens to be.
     */
    enum Kind {
        /**
         * A string literal, e.g. the {@code 'GeoIP'} in {@code stroom:dictionary('GeoIP')}.
         */
        STRING_LITERAL,
        /**
         * Some other literal - a number, a boolean, the empty sequence, or a sequence of items.
         */
        LITERAL,
        /**
         * A conditional, whose branches are genuine alternatives: exactly one of them happens.
         */
        ALTERNATIVES,
        /**
         * A reference to a variable or parameter.
         */
        VARIABLE_REFERENCE,
        /**
         * A function call, of any namespace.
         */
        FUNCTION_CALL,
        /**
         * Anything else.
         */
        OTHER
    }

    private final Expression expression;

    private XPathExpr(final Expression expression) {
        this.expression = expression;
    }

    /**
     * @param expression The Saxon expression to view. Must not be null.
     * @return a view of the first node from {@code expression} downwards that carries meaning.
     */
    static XPathExpr of(final Expression expression) {
        Objects.requireNonNull(expression, "Null expression supplied");
        return new XPathExpr(stepThroughWrappers(expression));
    }

    Kind kind() {
        if (expression instanceof StringLiteral) {
            return Kind.STRING_LITERAL;
        }
        if (expression instanceof Literal) {
            return Kind.LITERAL;
        }
        if (expression instanceof Choose) {
            return Kind.ALTERNATIVES;
        }
        if (expression instanceof VariableReference) {
            return Kind.VARIABLE_REFERENCE;
        }
        if (expression instanceof FunctionCall) {
            return Kind.FUNCTION_CALL;
        }
        return Kind.OTHER;
    }

    /**
     * @return the value of a {@link Kind#STRING_LITERAL}.
     * @throws IllegalStateException if this is not a string literal.
     */
    String stringLiteral() {
        if (expression instanceof final StringLiteral stringLiteral) {
            return stringLiteral.getStringValue();
        }
        throw new IllegalStateException("Not a string literal: " + kind());
    }

    /**
     * @return the string value of a {@link Kind#LITERAL} that holds exactly one item, or empty where it
     * holds none or several - neither of which can be a name. Also empty where the value cannot be read
     * as a string at all, which Saxon reports by throwing.
     */
    Optional<String> singleLiteralValue() {
        if (!(expression instanceof final Literal literal)) {
            return Optional.empty();
        }
        try {
            if (literal.getValue().getLength() != 1) {
                return Optional.empty();
            }
            return Optional.ofNullable(literal.getValue().getStringValue());
        } catch (final Exception e) {
            return Optional.empty();
        }
    }

    /**
     * @return the branches of a {@link Kind#ALTERNATIVES}, excluding the conditions that select between
     * them. A condition such as {@code @type = 'user'} holds a literal that is emphatically not a
     * reference, so it must not be mistaken for one of the values. Empty for any other kind.
     */
    List<XPathExpr> alternatives() {
        if (!(expression instanceof final Choose choose)) {
            return List.of();
        }
        final List<XPathExpr> branches = new ArrayList<>();
        for (final Operand action : choose.actions()) {
            branches.add(of(action.getChildExpression()));
        }
        return branches;
    }

    /**
     * @return the name of a {@link Kind#VARIABLE_REFERENCE} as written, prefix included. Empty for any
     * other kind.
     */
    Optional<String> variableName() {
        return expression instanceof final VariableReference variableReference
                ? Optional.ofNullable(variableReference.getDisplayName())
                : Optional.empty();
    }

    /**
     * @return the namespace URI of a {@link Kind#FUNCTION_CALL}, or empty for any other kind or for a
     * call whose name Saxon does not know.
     */
    Optional<String> functionNamespace() {
        return functionName().map(StructuredQName::getURI);
    }

    /**
     * @return the local name of a {@link Kind#FUNCTION_CALL}, or empty as for
     * {@link #functionNamespace()}.
     */
    Optional<String> functionLocalName() {
        return functionName().map(StructuredQName::getLocalPart);
    }

    /**
     * @return true if this is a call to the standard {@code fn:concat}. Named specifically because
     * concatenation is the one function whose result the parser can work out for itself.
     */
    boolean isConcat() {
        return functionNamespace().filter(NamespaceConstant.FN::equals).isPresent()
               && functionLocalName().filter("concat"::equals).isPresent();
    }

    /**
     * @return how many arguments a {@link Kind#FUNCTION_CALL} takes, or 0 for any other kind.
     */
    int arity() {
        return expression instanceof final FunctionCall functionCall
                ? functionCall.getArity()
                : 0;
    }

    /**
     * @param index The argument's position, from 0.
     * @return that argument of a {@link Kind#FUNCTION_CALL}.
     * @throws IllegalStateException     if this is not a function call.
     * @throws IndexOutOfBoundsException if there is no such argument.
     */
    XPathExpr argument(final int index) {
        if (!(expression instanceof final FunctionCall functionCall)) {
            throw new IllegalStateException("Not a function call: " + kind());
        }
        if (index < 0 || index >= functionCall.getArity()) {
            throw new IndexOutOfBoundsException(
                    "Argument " + index + " of a call taking " + functionCall.getArity());
        }
        return of(functionCall.getArg(index));
    }

    /**
     * @return every subexpression, in Saxon's order. For a conditional this includes the conditions as
     * well as the branches, unlike {@link #alternatives()} - a reference inside a condition is a real
     * reference, since evaluating the condition performs the lookup.
     */
    List<XPathExpr> children() {
        final List<XPathExpr> children = new ArrayList<>();
        for (final Operand operand : expression.operands()) {
            children.add(of(operand.getChildExpression()));
        }
        return children;
    }

    /**
     * Does this expression, anywhere within it, read the document being transformed?
     * <p>
     * The distinction it serves is what to tell an author about a value the parser could not determine.
     * "Comes from the input data" is a different statement from "built at run time", and only one of
     * them suggests the stylesheet is doing something the parser could ever have resolved.
     *
     * @return true if any part of this expression reads a node, an axis, or the context item.
     */
    boolean readsInput() {
        if (expression instanceof AxisExpression
            || expression instanceof ContextItemExpression
            || expression instanceof RootExpression) {
            return true;
        }
        for (final XPathExpr child : children()) {
            if (child.readsInput()) {
                return true;
            }
        }
        return false;
    }

    private Optional<StructuredQName> functionName() {
        return expression instanceof final FunctionCall functionCall
                ? Optional.ofNullable(functionCall.getFunctionName())
                : Optional.empty();
    }

    /**
     * Step through the atomizers and type checkers Saxon inserts, which say nothing about a value.
     * Stops at a wrapper with anything other than exactly one subexpression, there being no single
     * child to step to.
     */
    private static Expression stepThroughWrappers(final Expression expression) {
        Expression current = expression;
        while (current instanceof Atomizer
               || current instanceof SingletonAtomizer
               || current instanceof ItemChecker
               || current instanceof CardinalityChecker
               || current instanceof UntypedSequenceConverter
               || current instanceof AtomicSequenceConverter) {
            final Expression child = onlyChild(current);
            if (child == null) {
                return current;
            }
            current = child;
        }
        return current;
    }

    private static @Nullable Expression onlyChild(final Expression expression) {
        Expression child = null;
        for (final Operand operand : expression.operands()) {
            if (child != null) {
                return null;
            }
            child = operand.getChildExpression();
        }
        return child;
    }

    @Override
    public String toString() {
        return kind() + "(" + expression.getClass().getSimpleName() + ")";
    }
}
