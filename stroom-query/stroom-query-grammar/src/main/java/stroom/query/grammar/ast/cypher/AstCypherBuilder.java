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

package stroom.query.grammar.ast.cypher;

import stroom.query.grammar.antlr.CypherParser;
import stroom.query.grammar.antlr.CypherParser.AddExprContext;
import stroom.query.grammar.antlr.CypherParser.AggregateCallContext;
import stroom.query.grammar.antlr.CypherParser.AndExprContext;
import stroom.query.grammar.antlr.CypherParser.AtomContext;
import stroom.query.grammar.antlr.CypherParser.AroundClauseContext;
import stroom.query.grammar.antlr.CypherParser.AsOfClauseContext;
import stroom.query.grammar.antlr.CypherParser.BetweenClauseContext;
import stroom.query.grammar.antlr.CypherParser.BooleanValueContext;
import stroom.query.grammar.antlr.CypherParser.ComparisonOpContext;
import stroom.query.grammar.antlr.CypherParser.ComparisonPredicateContext;
import stroom.query.grammar.antlr.CypherParser.DiffAccessorContext;
import stroom.query.grammar.antlr.CypherParser.DiffClauseContext;
import stroom.query.grammar.antlr.CypherParser.EdgeBothContext;
import stroom.query.grammar.antlr.CypherParser.EdgeDetailContext;
import stroom.query.grammar.antlr.CypherParser.EdgeInContext;
import stroom.query.grammar.antlr.CypherParser.EdgeOutContext;
import stroom.query.grammar.antlr.CypherParser.EdgePatternContext;
import stroom.query.grammar.antlr.CypherParser.ExpressionContext;
import stroom.query.grammar.antlr.CypherParser.FunctionCallContext;
import stroom.query.grammar.antlr.CypherParser.FunctionValueContext;
import stroom.query.grammar.antlr.CypherParser.InPredicateContext;
import stroom.query.grammar.antlr.CypherParser.IsNullPredicateContext;
import stroom.query.grammar.antlr.CypherParser.LimitClauseContext;
import stroom.query.grammar.antlr.CypherParser.ListValueContext;
import stroom.query.grammar.antlr.CypherParser.MatchClauseContext;
import stroom.query.grammar.antlr.CypherParser.MulExprContext;
import stroom.query.grammar.antlr.CypherParser.NodeLabelsContext;
import stroom.query.grammar.antlr.CypherParser.NodePatternContext;
import stroom.query.grammar.antlr.CypherParser.NotExprContext;
import stroom.query.grammar.antlr.CypherParser.NumberValueContext;
import stroom.query.grammar.antlr.CypherParser.OrExprContext;
import stroom.query.grammar.antlr.CypherParser.OrderByClauseContext;
import stroom.query.grammar.antlr.CypherParser.OrderItemContext;
import stroom.query.grammar.antlr.CypherParser.ParamValueContext;
import stroom.query.grammar.antlr.CypherParser.PatternContext;
import stroom.query.grammar.antlr.CypherParser.PatternHopContext;
import stroom.query.grammar.antlr.CypherParser.PowExprContext;
import stroom.query.grammar.antlr.CypherParser.PrimaryContext;
import stroom.query.grammar.antlr.CypherParser.PropertyAccessContext;
import stroom.query.grammar.antlr.CypherParser.PropertyKeyValueContext;
import stroom.query.grammar.antlr.CypherParser.PropertyMapContext;
import stroom.query.grammar.antlr.CypherParser.QueryContext;
import stroom.query.grammar.antlr.CypherParser.ReadingClauseContext;
import stroom.query.grammar.antlr.CypherParser.ReturnClauseContext;
import stroom.query.grammar.antlr.CypherParser.ReturnGraphClauseContext;
import stroom.query.grammar.antlr.CypherParser.ReturnItemContext;
import stroom.query.grammar.antlr.CypherParser.ReturnItemsClauseContext;
import stroom.query.grammar.antlr.CypherParser.SkipClauseContext;
import stroom.query.grammar.antlr.CypherParser.StringValueContext;
import stroom.query.grammar.antlr.CypherParser.TemporalClauseContext;
import stroom.query.grammar.antlr.CypherParser.ValueContext;
import stroom.query.grammar.antlr.CypherParser.VarLengthContext;
import stroom.query.grammar.antlr.CypherParser.WhereClauseContext;
import stroom.query.grammar.antlr.CypherParser.WithClauseContext;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.grammar.parse.SyntaxException;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts an ANTLR {@code Cypher.g4} parse tree into a plain-Java {@link AstCypherQuery}, decoupling downstream
 * code (Task PoC.3's {@code CypherToLogicalPlan}) from ANTLR types entirely - mirrors
 * {@code stroom.query.grammar.ast.AstBuilder}'s approach for StroomQL exactly (a direct set of typed helper
 * methods, not a generated-visitor subclass, since labelled alternatives already give each call site a
 * statically-known context type).
 */
public final class AstCypherBuilder {

    /**
     * <b>Preconditions:</b> {@code ctx} is not null.
     * <b>Postconditions:</b> returns a full, decoupled AST for the whole query.
     * <b>Null status:</b> neither the parameter nor the return value is nullable.
     *
     * @param ctx the root parse tree node, as produced by {@code CypherParser.query()}.
     * @return never null.
     */
    public AstCypherQuery build(final QueryContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        final String dataSourceName = ctx.fromClause() == null
                ? null
                : unescapeString(ctx.fromClause().STRING().getText());
        final List<AstReadingClause> readingClauses = new ArrayList<>(ctx.readingClause().size());
        for (final ReadingClauseContext readingClauseCtx : ctx.readingClause()) {
            readingClauses.add(buildReadingClause(readingClauseCtx));
        }
        return new AstCypherQuery(dataSourceName, readingClauses, buildReturn(ctx.returnClause()), position(ctx));
    }

    // ------------------------------------------------------------------------------------------------------
    // reading clauses: MATCH / WITH
    // ------------------------------------------------------------------------------------------------------

    private AstReadingClause buildReadingClause(final ReadingClauseContext ctx) {
        if (ctx.matchClause() != null) {
            return buildMatch(ctx.matchClause());
        } else if (ctx.withClause() != null) {
            return buildWith(ctx.withClause());
        }
        throw new IllegalStateException("Unrecognised reading clause: " + ctx.getText());
    }

    private AstMatch buildMatch(final MatchClauseContext ctx) {
        return new AstMatch(
                buildPattern(ctx.pattern()),
                ctx.temporalClause() == null ? null : buildTemporal(ctx.temporalClause()),
                ctx.whereClause() == null ? null : buildWhere(ctx.whereClause()),
                ctx.OPTIONAL() != null,
                position(ctx));
    }

    private AstWith buildWith(final WithClauseContext ctx) {
        final List<AstReturnItem> items = new ArrayList<>(ctx.returnItem().size());
        for (final ReturnItemContext itemCtx : ctx.returnItem()) {
            items.add(buildReturnItem(itemCtx));
        }
        return new AstWith(
                items,
                ctx.whereClause() == null ? null : buildWhere(ctx.whereClause()),
                ctx.orderByClause() == null ? null : buildOrderBy(ctx.orderByClause()),
                ctx.skipClause() == null ? null : buildSkip(ctx.skipClause()),
                ctx.limitClause() == null ? null : buildLimit(ctx.limitClause()),
                position(ctx));
    }

    private AstReturnClause buildReturn(final ReturnClauseContext ctx) {
        if (ctx instanceof final ReturnGraphClauseContext graphCtx) {
            final AstLimit graphLimit = graphCtx.limitClause() == null
                    ? null
                    : buildLimit(graphCtx.limitClause());
            return new AstReturnClause(true, false, List.of(), null, null, graphLimit, position(graphCtx));
        }
        final ReturnItemsClauseContext itemsCtx = (ReturnItemsClauseContext) ctx;
        final List<AstReturnItem> items = new ArrayList<>(itemsCtx.returnItem().size());
        for (final ReturnItemContext itemCtx : itemsCtx.returnItem()) {
            items.add(buildReturnItem(itemCtx));
        }
        return new AstReturnClause(
                false,
                itemsCtx.DISTINCT() != null,
                items,
                itemsCtx.orderByClause() == null ? null : buildOrderBy(itemsCtx.orderByClause()),
                itemsCtx.skipClause() == null ? null : buildSkip(itemsCtx.skipClause()),
                itemsCtx.limitClause() == null ? null : buildLimit(itemsCtx.limitClause()),
                position(itemsCtx));
    }

    // ------------------------------------------------------------------------------------------------------
    // graph patterns
    // ------------------------------------------------------------------------------------------------------

    private AstPathPattern buildPattern(final PatternContext ctx) {
        final List<AstPatternHop> hops = new ArrayList<>(ctx.patternHop().size());
        for (final PatternHopContext hopCtx : ctx.patternHop()) {
            hops.add(buildPatternHop(hopCtx));
        }
        return new AstPathPattern(buildNodePattern(ctx.nodePattern()), hops, position(ctx));
    }

    private AstPatternHop buildPatternHop(final PatternHopContext ctx) {
        return new AstPatternHop(
                buildEdgePattern(ctx.edgePattern()),
                buildNodePattern(ctx.nodePattern()),
                position(ctx));
    }

    private AstNodePattern buildNodePattern(final NodePatternContext ctx) {
        final List<String> labels;
        if (ctx.nodeLabels() != null) {
            labels = buildNodeLabels(ctx.nodeLabels());
        } else {
            labels = List.of();
        }
        final List<AstPropertyKeyValue> properties;
        if (ctx.propertyMap() != null) {
            properties = buildPropertyMap(ctx.propertyMap());
        } else {
            properties = List.of();
        }
        return new AstNodePattern(
                ctx.variable == null ? null : ctx.variable.getText(),
                labels,
                properties,
                position(ctx));
    }

    private List<String> buildNodeLabels(final NodeLabelsContext ctx) {
        final List<String> labels = new ArrayList<>(ctx.label.size());
        for (final Token labelToken : ctx.label) {
            labels.add(labelToken.getText());
        }
        return labels;
    }

    private AstEdgePattern buildEdgePattern(final EdgePatternContext ctx) {
        final AstEdgeDirection direction;
        final EdgeDetailContext detailCtx;
        if (ctx instanceof final EdgeInContext inCtx) {
            direction = AstEdgeDirection.IN;
            detailCtx = inCtx.edgeDetail();
        } else if (ctx instanceof final EdgeOutContext outCtx) {
            direction = AstEdgeDirection.OUT;
            detailCtx = outCtx.edgeDetail();
        } else if (ctx instanceof final EdgeBothContext bothCtx) {
            direction = AstEdgeDirection.BOTH;
            detailCtx = bothCtx.edgeDetail();
        } else {
            throw new IllegalStateException("Unrecognised edge pattern: " + ctx.getText());
        }

        if (detailCtx == null) {
            return new AstEdgePattern(null, null, direction, null, List.of(), position(ctx));
        }
        return new AstEdgePattern(
                detailCtx.variable == null ? null : detailCtx.variable.getText(),
                detailCtx.edgeType == null ? null : detailCtx.edgeType.getText(),
                direction,
                detailCtx.varLength() == null ? null : buildVarLength(detailCtx.varLength()),
                detailCtx.propertyMap() == null ? List.of() : buildPropertyMap(detailCtx.propertyMap()),
                position(ctx));
    }

    private AstVarLength buildVarLength(final VarLengthContext ctx) {
        return new AstVarLength(
                ctx.min == null ? null : parseBoundedInt(ctx.min, "variable-length path minimum hop count"),
                parseBoundedInt(ctx.max, "variable-length path maximum hop count"),
                position(ctx));
    }

    private List<AstPropertyKeyValue> buildPropertyMap(final PropertyMapContext ctx) {
        final List<AstPropertyKeyValue> properties = new ArrayList<>(ctx.propertyKeyValue().size());
        for (final PropertyKeyValueContext propertyCtx : ctx.propertyKeyValue()) {
            properties.add(buildPropertyKeyValue(propertyCtx));
        }
        return properties;
    }

    private AstPropertyKeyValue buildPropertyKeyValue(final PropertyKeyValueContext ctx) {
        return new AstPropertyKeyValue(ctx.key.getText(), buildValue(ctx.value()), position(ctx));
    }

    // ------------------------------------------------------------------------------------------------------
    // temporal clause
    // ------------------------------------------------------------------------------------------------------

    private AstTemporal buildTemporal(final TemporalClauseContext ctx) {
        if (ctx instanceof final AsOfClauseContext asOfCtx) {
            return new AstAsOf(buildValue(asOfCtx.instant), position(ctx));
        } else if (ctx instanceof final AroundClauseContext aroundCtx) {
            return new AstAround(buildValue(aroundCtx.instant), buildValue(aroundCtx.duration), position(ctx));
        } else if (ctx instanceof final BetweenClauseContext betweenCtx) {
            return new AstBetween(buildValue(betweenCtx.from), buildValue(betweenCtx.to), position(ctx));
        } else if (ctx instanceof final DiffClauseContext diffCtx) {
            return new AstDiff(buildValue(diffCtx.baseline), buildValue(diffCtx.comparison), position(ctx));
        }
        throw new IllegalStateException("Unrecognised temporal clause: " + ctx.getText());
    }

    // ------------------------------------------------------------------------------------------------------
    // WHERE: boolean expression
    // ------------------------------------------------------------------------------------------------------

    private AstWhere buildWhere(final WhereClauseContext ctx) {
        return new AstWhere(buildOrExpr(ctx.expr().orExpr()), position(ctx));
    }

    private AstBooleanExpr buildOrExpr(final OrExprContext ctx) {
        if (ctx.andExpr().size() == 1) {
            return buildAndExpr(ctx.andExpr(0));
        }
        final List<AstBooleanExpr> operands = new ArrayList<>(ctx.andExpr().size());
        for (final AndExprContext andCtx : ctx.andExpr()) {
            operands.add(buildAndExpr(andCtx));
        }
        return new AstOrExpr(operands, position(ctx));
    }

    private AstBooleanExpr buildAndExpr(final AndExprContext ctx) {
        if (ctx.notExpr().size() == 1) {
            return buildNotExpr(ctx.notExpr(0));
        }
        final List<AstBooleanExpr> operands = new ArrayList<>(ctx.notExpr().size());
        for (final NotExprContext notCtx : ctx.notExpr()) {
            operands.add(buildNotExpr(notCtx));
        }
        return new AstAndExpr(operands, position(ctx));
    }

    private AstBooleanExpr buildNotExpr(final NotExprContext ctx) {
        if (ctx.NOT() != null) {
            return new AstNotExpr(buildNotExpr(ctx.notExpr()), position(ctx));
        }
        return buildPrimary(ctx.primary());
    }

    private AstBooleanExpr buildPrimary(final PrimaryContext ctx) {
        if (ctx.expr() != null) {
            return buildOrExpr(ctx.expr().orExpr());
        } else if (ctx.inPredicate() != null) {
            return buildInPredicate(ctx.inPredicate());
        } else if (ctx.isNullPredicate() != null) {
            return buildIsNullPredicate(ctx.isNullPredicate());
        }
        return buildComparisonPredicate(ctx.comparisonPredicate());
    }

    private AstInPredicate buildInPredicate(final InPredicateContext ctx) {
        return new AstInPredicate(buildExpression(ctx.left), buildExpression(ctx.right), position(ctx));
    }

    private AstIsNullPredicate buildIsNullPredicate(final IsNullPredicateContext ctx) {
        return new AstIsNullPredicate(buildExpression(ctx.operand), ctx.NOT() != null, position(ctx));
    }

    private AstComparisonPredicate buildComparisonPredicate(final ComparisonPredicateContext ctx) {
        return new AstComparisonPredicate(
                buildExpression(ctx.left),
                buildComparisonOp(ctx.op),
                buildExpression(ctx.right),
                position(ctx));
    }

    private AstComparisonOp buildComparisonOp(final ComparisonOpContext ctx) {
        return switch (ctx.getStart().getType()) {
            case CypherParser.EQ -> AstComparisonOp.EQ;
            case CypherParser.NEQ -> AstComparisonOp.NEQ;
            case CypherParser.LT -> AstComparisonOp.LT;
            case CypherParser.LE -> AstComparisonOp.LE;
            case CypherParser.GT -> AstComparisonOp.GT;
            case CypherParser.GE -> AstComparisonOp.GE;
            // `STARTS WITH` / `ENDS WITH` are two-token phrases; the start token (STARTS / ENDS) identifies them.
            case CypherParser.STARTS -> AstComparisonOp.STARTS_WITH;
            case CypherParser.ENDS -> AstComparisonOp.ENDS_WITH;
            case CypherParser.CONTAINS -> AstComparisonOp.CONTAINS;
            case CypherParser.REGEX -> AstComparisonOp.REGEX;
            default -> throw new IllegalStateException("Unrecognised comparison operator: " + ctx.getText());
        };
    }

    // ------------------------------------------------------------------------------------------------------
    // expressions: RETURN/WITH/ORDER BY items, WHERE operands
    // ------------------------------------------------------------------------------------------------------

    private AstExpression buildExpression(final ExpressionContext ctx) {
        return buildAddExpr(ctx.addExpr());
    }

    // addExpr / mulExpr fold left-to-right over their operator lists (left-associative); powExpr recurses right
    // (right-associative). A level with no operator collapses to its single child, so a plain `a.name` produces
    // the same AST as before arithmetic existed.
    private AstExpression buildAddExpr(final AddExprContext ctx) {
        AstExpression left = buildMulExpr(ctx.mulExpr(0));
        for (int i = 0; i < ctx.op.size(); i++) {
            final AstArithmeticOp op = ctx.op.get(i).getType() == CypherParser.PLUS
                    ? AstArithmeticOp.ADD
                    : AstArithmeticOp.SUBTRACT;
            left = new AstArithmeticExpr(left, op, buildMulExpr(ctx.mulExpr(i + 1)), position(ctx));
        }
        return left;
    }

    private AstExpression buildMulExpr(final MulExprContext ctx) {
        AstExpression left = buildPowExpr(ctx.powExpr(0));
        for (int i = 0; i < ctx.op.size(); i++) {
            final AstArithmeticOp op = ctx.op.get(i).getType() == CypherParser.STAR
                    ? AstArithmeticOp.MULTIPLY
                    : AstArithmeticOp.DIVIDE;
            left = new AstArithmeticExpr(left, op, buildPowExpr(ctx.powExpr(i + 1)), position(ctx));
        }
        return left;
    }

    private AstExpression buildPowExpr(final PowExprContext ctx) {
        final AstExpression base = buildAtom(ctx.atom());
        if (ctx.powExpr() == null) {
            return base;
        }
        return new AstArithmeticExpr(base, AstArithmeticOp.POWER, buildPowExpr(ctx.powExpr()), position(ctx));
    }

    private AstExpression buildAtom(final AtomContext ctx) {
        if (ctx.aggregateCall() != null) {
            return buildAggregateCall(ctx.aggregateCall());
        } else if (ctx.diffAccessor() != null) {
            return buildDiffAccessor(ctx.diffAccessor());
        } else if (ctx.propertyAccess() != null) {
            return buildPropertyAccess(ctx.propertyAccess());
        } else if (ctx.variableRef != null) {
            return new AstVariableExpr(ctx.variableRef.getText(), position(ctx));
        } else if (ctx.value() != null) {
            return new AstLiteralExpr(buildValue(ctx.value()), position(ctx));
        } else if (ctx.expression() != null) {
            // Parenthesised sub-expression.
            return buildExpression(ctx.expression());
        }
        throw new IllegalStateException("Unrecognised expression atom: " + ctx.getText());
    }

    private AstAggregateExpr buildAggregateCall(final AggregateCallContext ctx) {
        final AstAggregateFunction function = switch (ctx.fn.getType()) {
            case CypherParser.COUNT -> AstAggregateFunction.COUNT;
            case CypherParser.SUM -> AstAggregateFunction.SUM;
            case CypherParser.AVG -> AstAggregateFunction.AVG;
            case CypherParser.MIN -> AstAggregateFunction.MIN;
            case CypherParser.MAX -> AstAggregateFunction.MAX;
            case CypherParser.COLLECT -> AstAggregateFunction.COLLECT;
            default -> throw new IllegalStateException("Unrecognised aggregate function: " + ctx.getText());
        };
        final boolean distinct = ctx.DISTINCT() != null;
        if (ctx.STAR() != null) {
            return new AstAggregateExpr(function, null, true, distinct, position(ctx));
        }
        return new AstAggregateExpr(function, buildExpression(ctx.expression()), false, distinct, position(ctx));
    }

    private AstPropertyAccessExpr buildPropertyAccess(final PropertyAccessContext ctx) {
        return new AstPropertyAccessExpr(ctx.variable.getText(), ctx.property.getText(), position(ctx));
    }

    private AstDiffAccessorExpr buildDiffAccessor(final DiffAccessorContext ctx) {
        final AstDiffSide side = ctx.side.getType() == CypherParser.BEFORE
                ? AstDiffSide.BEFORE
                : AstDiffSide.AFTER;
        return new AstDiffAccessorExpr(side, buildPropertyAccess(ctx.propertyAccess()), position(ctx));
    }

    private AstFunctionValue buildFunctionCall(final FunctionCallContext ctx) {
        final List<AstExpression> arguments = new ArrayList<>(ctx.expression().size());
        for (final ExpressionContext exprCtx : ctx.expression()) {
            arguments.add(buildExpression(exprCtx));
        }
        final String namespace = ctx.namespace == null ? null : ctx.namespace.getText();
        return new AstFunctionValue(namespace, ctx.name.getText(), arguments, position(ctx));
    }

    private AstValue buildValue(final ValueContext ctx) {
        if (ctx instanceof final StringValueContext stringCtx) {
            return new AstStringValue(unescapeString(stringCtx.STRING().getText()), position(ctx));
        } else if (ctx instanceof final NumberValueContext numberCtx) {
            return new AstNumberValue(numberCtx.NUMBER().getText(), position(ctx));
        } else if (ctx instanceof final BooleanValueContext booleanCtx) {
            return new AstBooleanValue(booleanCtx.TRUE() != null, position(ctx));
        } else if (ctx instanceof final ParamValueContext paramCtx) {
            // Strip the leading '$'.
            return new AstParameterValue(paramCtx.PARAM().getText().substring(1), position(ctx));
        } else if (ctx instanceof final FunctionValueContext functionCtx) {
            return buildFunctionCall(functionCtx.functionCall());
        } else if (ctx instanceof final ListValueContext listCtx) {
            final List<AstValue> elements = new ArrayList<>(listCtx.value().size());
            for (final ValueContext valueCtx : listCtx.value()) {
                elements.add(buildValue(valueCtx));
            }
            return new AstListValue(elements, position(ctx));
        }
        throw new IllegalStateException("Unrecognised value: " + ctx.getText());
    }

    /**
     * Strips the surrounding quote characters and resolves {@code \x} backslash escapes (matching
     * {@code Cypher.g4}'s {@code ESC: '\\' . ;} fragment - a backslash escapes exactly the one following
     * character, whatever it is).
     */
    private String unescapeString(final String quoted) {
        final String inner = quoted.substring(1, quoted.length() - 1);
        final StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                i++;
                sb.append(inner.charAt(i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN/WITH items, ORDER BY, SKIP, LIMIT
    // ------------------------------------------------------------------------------------------------------

    private AstReturnItem buildReturnItem(final ReturnItemContext ctx) {
        return new AstReturnItem(
                buildExpression(ctx.expression()),
                ctx.alias == null ? null : ctx.alias.getText(),
                position(ctx));
    }

    private AstOrderBy buildOrderBy(final OrderByClauseContext ctx) {
        final List<AstOrderItem> items = new ArrayList<>(ctx.orderItem().size());
        for (final OrderItemContext itemCtx : ctx.orderItem()) {
            items.add(buildOrderItem(itemCtx));
        }
        return new AstOrderBy(items, position(ctx));
    }

    private AstOrderItem buildOrderItem(final OrderItemContext ctx) {
        return new AstOrderItem(buildExpression(ctx.expression()), ctx.DESC() != null, position(ctx));
    }

    private AstSkip buildSkip(final SkipClauseContext ctx) {
        return new AstSkip(parseBoundedLong(ctx.NUMBER().getSymbol(), "SKIP row count"), position(ctx));
    }

    private AstLimit buildLimit(final LimitClauseContext ctx) {
        return new AstLimit(parseBoundedLong(ctx.NUMBER().getSymbol(), "LIMIT row count"), position(ctx));
    }

    // ------------------------------------------------------------------------------------------------------
    // shared leaf
    // ------------------------------------------------------------------------------------------------------

    private AstPosition position(final ParserRuleContext ctx) {
        final Token token = ctx.getStart();
        return new AstPosition(token.getLine(), token.getCharPositionInLine());
    }

    /**
     * Code-review fix: the {@code NUMBER} lexer rule ({@code DIGIT+ ('.' DIGIT+)?}) matches decimals and runs of
     * digits of any length, but a {@code SKIP}/{@code LIMIT}/variable-length bound must be a whole number within
     * the field it parses into. A fractional literal (e.g. {@code SKIP 3.5}) or one with more digits than fit
     * (e.g. {@code 99999999999999999999}) used to reach {@code Integer.parseInt}/{@code Long.parseLong} directly
     * and blow up with a raw, positionless {@link NumberFormatException} instead of the {@link SyntaxException}
     * {@link stroom.query.grammar.parse.CypherQueryParser#parse} documents as its only thrown-error contract.
     *
     * @param token       the {@code NUMBER} token to parse; never null.
     * @param description a short, human-readable name for what this number represents, used in the error message.
     * @return the parsed value.
     * @throws SyntaxException if {@code token}'s text is not a whole number that fits in a 32-bit int.
     */
    private static int parseBoundedInt(final Token token, final String description) {
        try {
            return Integer.parseInt(token.getText());
        } catch (final NumberFormatException e) {
            throw invalidNumber(token, description);
        }
    }

    /**
     * As {@link #parseBoundedInt(Token, String)}, but for the 64-bit {@code SKIP}/{@code LIMIT} row counts.
     *
     * @throws SyntaxException if {@code token}'s text is not a whole number that fits in a 64-bit long.
     */
    private static long parseBoundedLong(final Token token, final String description) {
        try {
            return Long.parseLong(token.getText());
        } catch (final NumberFormatException e) {
            throw invalidNumber(token, description);
        }
    }

    private static SyntaxException invalidNumber(final Token token, final String description) {
        final String text = token.getText();
        // NUMBER also matches decimals, so parse failure means either "not a whole number" or "too many digits";
        // report which, rather than blaming magnitude for a fractional literal like 3.5.
        final String reason = text.indexOf('.') >= 0
                ? "must be a whole number"
                : "is too large a number to use here";
        return new SyntaxException(
                description + " '" + text + "' " + reason,
                token.getLine(),
                token.getCharPositionInLine(),
                List.of());
    }
}
