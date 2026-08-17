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

import stroom.pipeline.shared.XsltReferenceReason;

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
import net.sf.saxon.s9api.Axis;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmNodeKind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Works out what an expression will be worth, without running it.
 * <p>
 * The interesting part is variable resolution, which follows the two XSLT scoping rules rather than one:
 * a <b>local</b> declaration is in scope for its following siblings and their descendants, so it is found
 * by walking up through ancestors inspecting <i>preceding</i> siblings; a <b>top-level</b> declaration is
 * in scope throughout the stylesheet irrespective of document order. Applying the preceding-sibling rule
 * to globals would refuse a reference to a global declared below the template that uses it, which is
 * legal and common. Locals are looked for first, so a local shadows a global.
 * <p>
 * Parameters are never resolved from their defaults, even a literal default with no visible
 * {@code xsl:with-param} overriding it. Proving no caller overrides one needs call-graph analysis across
 * {@code apply-templates} with modes, and a value supplied from outside says nothing about the value that
 * will be used.
 */
class XsltValueResolver {

    static final String XSLT_NS = NamespaceConstant.XSLT;

    private static final String NAME_ATTRIBUTE = "name";
    private static final String SELECT_ATTRIBUTE = "select";

    private static final String VARIABLE_ELEMENT = "variable";
    private static final String PARAM_ELEMENT = "param";
    private static final String CHOOSE_ELEMENT = "choose";
    private static final String WHEN_ELEMENT = "when";
    private static final String OTHERWISE_ELEMENT = "otherwise";
    private static final String IF_ELEMENT = "if";
    private static final String VALUE_OF_ELEMENT = "value-of";
    private static final String TEXT_ELEMENT = "text";
    private static final String STYLESHEET_ELEMENT = "stylesheet";
    private static final String TRANSFORM_ELEMENT = "transform";

    /**
     * Bounds recursion through variables that refer to other variables. Reached only by pathological or
     * cyclic input, since real stylesheets nest a handful of levels at most.
     */
    private final int maxDepth;
    private final XsltExpressionCompiler compiler;

    XsltValueResolver(final XsltExpressionCompiler compiler, final int maxDepth) {
        this.compiler = Objects.requireNonNull(compiler, "Null compiler supplied");
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1, got " + maxDepth);
        }
        this.maxDepth = maxDepth;
    }

    /**
     * Resolve an expression to the value or values it can take.
     *
     * @param expression The compiled expression. Must not be null.
     * @param site       The element the expression was written on, which fixes the scope variable
     *                   references are resolved in. Must not be null.
     * @return what the expression is worth, resolved, unresolved, or partly both.
     */
    XsltValue resolve(final Expression expression, final XdmNode site) {
        Objects.requireNonNull(expression, "Null expression supplied");
        Objects.requireNonNull(site, "Null site supplied");
        return resolve(expression, site, new LinkedHashSet<>(), 0);
    }

    private XsltValue resolve(final Expression expression,
                              final XdmNode site,
                              final Set<String> variablesInProgress,
                              final int depth) {
        if (depth > maxDepth) {
            return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
        }

        final Expression unwrapped = unwrap(expression);

        if (unwrapped instanceof final StringLiteral stringLiteral) {
            return XsltValue.resolved(stringLiteral.getStringValue());
        }
        if (unwrapped instanceof final Literal literal) {
            return resolveLiteral(literal);
        }
        if (unwrapped instanceof final Choose choose) {
            return resolveChoose(choose, site, variablesInProgress, depth);
        }
        if (unwrapped instanceof final VariableReference variableReference) {
            return resolveVariableReference(variableReference, site, variablesInProgress, depth);
        }
        if (unwrapped instanceof final FunctionCall functionCall && isConcat(functionCall)) {
            return resolveConcat(functionCall, site, variablesInProgress, depth);
        }
        return XsltValue.unresolved(reasonFor(unwrapped));
    }

    /**
     * Saxon wraps expressions in atomizers and type checkers, so {@code concat('a', $v)} holds a
     * {@code SingletonAtomizer} rather than the variable reference itself. These wrappers say nothing
     * about the value, so they are stepped through.
     */
    private static Expression unwrap(final Expression expression) {
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

    private static XsltValue resolveLiteral(final Literal literal) {
        try {
            if (literal.getValue().getLength() != 1) {
                // The empty sequence, or several items. Neither is a name.
                return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
            }
            return XsltValue.resolved(literal.getValue().getStringValue());
        } catch (final Exception e) {
            return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
        }
    }

    /**
     * Every reachable branch contributes. Two possible values yield two references, not none - and where
     * one branch is a literal and another is not, the literal is still recorded, alongside the reason the
     * other could not be.
     */
    private XsltValue resolveChoose(final Choose choose,
                                    final XdmNode site,
                                    final Set<String> variablesInProgress,
                                    final int depth) {
        final List<XsltValue> branches = new ArrayList<>();
        // actions() rather than operands(), so the conditions are excluded. A condition such as
        // @type = 'user' holds a literal that is emphatically not a reference.
        for (final Operand action : choose.actions()) {
            branches.add(resolve(action.getChildExpression(), site, variablesInProgress, depth + 1));
        }
        return XsltValue.merge(branches);
    }

    private static boolean isConcat(final FunctionCall functionCall) {
        return functionCall.getFunctionName() != null
               && NamespaceConstant.FN.equals(functionCall.getFunctionName().getURI())
               && "concat".equals(functionCall.getFunctionName().getLocalPart());
    }

    /**
     * Fold {@code concat()} over resolvable arguments.
     * <p>
     * Saxon folds a concat of pure literals during compilation, so what reaches here is a concat with at
     * least one argument Saxon could not fold - typically a variable, which the parser can often resolve
     * when Saxon cannot, because it reads the declarations.
     * <p>
     * Where an argument can take several values the result is the cross product, which is bounded to keep
     * a nest of conditionals from exploding.
     */
    private XsltValue resolveConcat(final FunctionCall functionCall,
                                    final XdmNode site,
                                    final Set<String> variablesInProgress,
                                    final int depth) {
        final List<XsltValue> arguments = new ArrayList<>();
        for (int index = 0; index < functionCall.getArity(); index++) {
            arguments.add(resolve(functionCall.getArg(index), site, variablesInProgress, depth + 1));
        }
        return concatenate(arguments);
    }

    /**
     * Join parts that will appear one after another in the output.
     * <p>
     * Used for the arguments of {@code concat()} and for the nodes of an element's content, which are the
     * same problem: both are sequences whose string values run together. Not to be confused with
     * {@link XsltValue#merge}, which is for genuine alternatives such as the arms of an
     * {@code xsl:choose} - only one of those happens.
     * <p>
     * Where a part can take several values the result is the cross product, bounded so that a nest of
     * conditionals cannot explode.
     *
     * @param parts The parts in order. An empty list yields the empty string, which is what empty content
     *              produces.
     * @return the joined value, or unresolved if any part is - a string is only known if all of it is.
     */
    private static XsltValue concatenate(final List<XsltValue> parts) {
        final int maxCombinations = 16;
        if (parts.isEmpty()) {
            return XsltValue.resolved("");
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }

        List<String> combinations = new ArrayList<>();
        combinations.add("");
        for (final XsltValue part : parts) {
            if (!part.hasValues()) {
                // One unknown part makes the whole string unknown; its reason is the useful one.
                return XsltValue.unresolved(
                        Objects.requireNonNullElse(part.reason(), XsltReferenceReason.NON_LITERAL_BINDING));
            }
            if (combinations.size() * part.values().size() > maxCombinations) {
                return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
            }

            final List<String> expanded = new ArrayList<>();
            for (final String prefix : combinations) {
                for (final String value : part.values()) {
                    expanded.add(prefix + value);
                }
            }
            combinations = expanded;
        }
        return XsltValue.resolved(combinations);
    }

    private XsltValue resolveVariableReference(final VariableReference variableReference,
                                               final XdmNode site,
                                               final Set<String> variablesInProgress,
                                               final int depth) {
        final String name = localName(variableReference.getDisplayName());
        if (variablesInProgress.contains(name)) {
            // A cycle. Report it as an undeterminable binding rather than recursing forever.
            return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
        }

        final XdmNode binding = findBinding(name, site);
        if (binding == null) {
            // Not declared in this document, so presumably declared in one it imports. The parser reads
            // one document and does not follow imports.
            return XsltValue.unresolved(XsltReferenceReason.IMPORTED);
        }
        if (PARAM_ELEMENT.equals(binding.getNodeName().getLocalName())) {
            return XsltValue.unresolved(XsltReferenceReason.PARAMETER);
        }

        final Set<String> nowInProgress = new LinkedHashSet<>(variablesInProgress);
        nowInProgress.add(name);
        return resolveBoundValue(binding, nowInProgress, depth + 1);
    }

    /**
     * Resolve what an {@code xsl:variable} is bound to, whether by {@code @select} or by its content.
     */
    private XsltValue resolveBoundValue(final XdmNode binding,
                                        final Set<String> variablesInProgress,
                                        final int depth) {
        final String select = binding.attribute(SELECT_ATTRIBUTE);
        if (select != null) {
            try {
                final Expression expression = compiler.compileExpression(binding, select);
                // The binding element becomes the site, so variables it refers to resolve in its scope
                // rather than in the scope of whatever referred to it.
                return resolve(expression, binding, variablesInProgress, depth);
            } catch (final SaxonApiException e) {
                return XsltValue.unresolved(XsltReferenceReason.UNPARSEABLE);
            }
        }
        return resolveElementContent(binding, variablesInProgress, depth);
    }

    /**
     * Resolve the content of an element to the text it will produce, from the top.
     *
     * @param element The element whose content to resolve. Must not be null.
     * @return what the content is worth.
     */
    XsltValue resolveElementContent(final XdmNode element) {
        Objects.requireNonNull(element, "Null element supplied");
        return resolveElementContent(element, new LinkedHashSet<>(), 0);
    }

    /**
     * Resolve the content of an element to the text it will produce.
     * <p>
     * Used for an {@code xsl:variable} with a body and for a {@code <map>} element in the output, which are
     * the same problem. The content is a <b>sequence</b>: every node in it contributes, and their string
     * values run together. So the parts are {@link #concatenate concatenated}, not merged - merging is for
     * the arms of an {@code xsl:choose}, where only one of them happens.
     * <p>
     * Getting that distinction wrong is not a subtle matter of taste. Treating
     * {@code <xsl:value-of select="'A'"/><xsl:value-of select="'B'"/>} as alternatives yields {@code A} and
     * {@code B} where the runtime produces {@code AB} - two references that do not exist, and the one that
     * does missed.
     * <p>
     * Whitespace-only text nodes are skipped, because XSLT strips them from a stylesheet before it runs, so
     * indentation between elements contributes nothing. Text that is not whitespace-only is taken verbatim,
     * including any surrounding spaces, because the runtime keeps those too.
     */
    XsltValue resolveElementContent(final XdmNode element,
                                    final Set<String> variablesInProgress,
                                    final int depth) {
        Objects.requireNonNull(element, "Null element supplied");
        if (depth > maxDepth) {
            return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
        }

        final List<XsltValue> parts = new ArrayList<>();
        final java.util.Iterator<XdmNode> children = element.axisIterator(Axis.CHILD);
        while (children.hasNext()) {
            final XdmNode child = children.next();
            if (child.getNodeKind() == XdmNodeKind.TEXT) {
                final String text = child.getStringValue();
                if (!text.isBlank()) {
                    parts.add(XsltValue.resolved(text));
                }
            } else if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                parts.add(resolveContentElement(child, variablesInProgress, depth));
            }
            // Comments and processing instructions produce no output, so contribute nothing.
        }
        return concatenate(parts);
    }

    /**
     * Resolve one element within another's content.
     */
    private XsltValue resolveContentElement(final XdmNode child,
                                            final Set<String> variablesInProgress,
                                            final int depth) {
        if (!XSLT_NS.equals(child.getNodeName().getNamespaceURI())) {
            // A literal result element inside the content means the value is structured, not a name.
            return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
        }

        switch (child.getNodeName().getLocalName()) {
            case TEXT_ELEMENT -> {
                // xsl:text is the one place whitespace in a stylesheet is deliberate, so it is kept as is.
                return XsltValue.resolved(child.getStringValue());
            }
            case CHOOSE_ELEMENT -> {
                final List<XsltValue> arms = new ArrayList<>();
                for (final XdmNode arm : childElements(child)) {
                    final String armName = arm.getNodeName().getLocalName();
                    if (WHEN_ELEMENT.equals(armName) || OTHERWISE_ELEMENT.equals(armName)) {
                        arms.add(resolveElementContent(arm, variablesInProgress, depth + 1));
                    }
                }
                // Genuine alternatives, and only here: exactly one arm runs.
                return XsltValue.merge(arms);
            }
            case IF_ELEMENT -> {
                // Either its content or nothing at all, which is an outcome the caller must allow for. The
                // empty string is the honest second alternative - not an unresolvable value, since if the
                // test fails the content contributes precisely nothing.
                return XsltValue.merge(List.of(
                        resolveElementContent(child, variablesInProgress, depth + 1),
                        XsltValue.resolved("")));
            }
            case VALUE_OF_ELEMENT -> {
                final String select = child.attribute(SELECT_ATTRIBUTE);
                if (select == null) {
                    return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
                }
                try {
                    return resolve(
                            compiler.compileExpression(child, select),
                            child,
                            variablesInProgress,
                            depth + 1);
                } catch (final SaxonApiException e) {
                    return XsltValue.unresolved(XsltReferenceReason.UNPARSEABLE);
                }
            }
            default -> {
                return XsltValue.unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
            }
        }
    }

    /**
     * Find the declaration a variable reference binds to.
     *
     * @param name The variable's local name.
     * @param site The element the reference was written on.
     * @return the declaring {@code xsl:variable} or {@code xsl:param}, or null if this document declares
     * none of that name.
     */
    private @Nullable XdmNode findBinding(final String name, final XdmNode site) {
        final XdmNode stylesheet = stylesheetElement(site);

        // Rule 1 - locals. Walk up, inspecting preceding siblings at each level. A declaration after the
        // reference in document order is not in scope, so resolving to it would be wrong.
        XdmNode node = site;
        while (node != null && !node.equals(stylesheet)) {
            final XdmNode declaration = precedingDeclaration(node, name);
            if (declaration != null) {
                return declaration;
            }
            node = node.getParent();
        }

        // Rule 2 - top level, in scope throughout regardless of position. Reached only if no local
        // binding was found, so a local shadows a global.
        if (stylesheet != null) {
            for (final XdmNode child : childElements(stylesheet)) {
                if (isDeclarationOf(child, name)) {
                    return child;
                }
            }
        }
        return null;
    }

    private static @Nullable XdmNode precedingDeclaration(final XdmNode node, final String name) {
        // The preceding-sibling axis is reverse document order, so the first match is the nearest.
        final java.util.Iterator<XdmNode> iterator = node.axisIterator(Axis.PRECEDING_SIBLING);
        while (iterator.hasNext()) {
            final XdmNode sibling = iterator.next();
            if (isDeclarationOf(sibling, name)) {
                return sibling;
            }
        }
        return null;
    }

    private static boolean isDeclarationOf(final XdmNode node, final String name) {
        if (node.getNodeKind() != XdmNodeKind.ELEMENT
            || !XSLT_NS.equals(node.getNodeName().getNamespaceURI())) {
            return false;
        }
        final String localName = node.getNodeName().getLocalName();
        if (!VARIABLE_ELEMENT.equals(localName) && !PARAM_ELEMENT.equals(localName)) {
            return false;
        }
        final String declaredName = node.attribute(NAME_ATTRIBUTE);
        return declaredName != null && name.equals(localName(declaredName));
    }

    /**
     * @return the {@code xsl:stylesheet} or {@code xsl:transform} element, or null for a simplified
     * stylesheet, which has no top level and so no global declarations.
     */
    private static @Nullable XdmNode stylesheetElement(final XdmNode node) {
        XdmNode current = node;
        while (current != null) {
            if (current.getNodeKind() == XdmNodeKind.ELEMENT
                && XSLT_NS.equals(current.getNodeName().getNamespaceURI())
                && (STYLESHEET_ELEMENT.equals(current.getNodeName().getLocalName())
                    || TRANSFORM_ELEMENT.equals(current.getNodeName().getLocalName()))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    static List<XdmNode> childElements(final XdmNode element) {
        final List<XdmNode> children = new ArrayList<>();
        element.axisIterator(Axis.CHILD).forEachRemaining(child -> {
            if (child.getNodeKind() == XdmNodeKind.ELEMENT) {
                children.add(child);
            }
        });
        return children;
    }

    /**
     * Variable names are QNames, but are unprefixed in practice. Comparing local parts keeps a prefixed
     * declaration matching a prefixed reference without resolving both against their namespace contexts.
     */
    private static String localName(final String name) {
        final int colon = name.indexOf(':');
        return colon < 0
                ? name
                : name.substring(colon + 1);
    }

    /**
     * @return why a non-literal expression is not determinable. An expression that touches the input
     * document is data driven; anything else is simply not foldable.
     */
    private static XsltReferenceReason reasonFor(final Expression expression) {
        return referencesInput(expression)
                ? XsltReferenceReason.DATA_DRIVEN
                : XsltReferenceReason.NON_LITERAL_BINDING;
    }

    private static boolean referencesInput(final Expression expression) {
        if (expression instanceof AxisExpression
            || expression instanceof ContextItemExpression
            || expression instanceof RootExpression) {
            return true;
        }
        for (final Operand operand : expression.operands()) {
            if (referencesInput(operand.getChildExpression())) {
                return true;
            }
        }
        return false;
    }

}
