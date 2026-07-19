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

package stroom.query.grammar.ast;

import stroom.query.grammar.antlr.StroomQLParser;
import stroom.query.grammar.antlr.StroomQLParser.AndExprContext;
import stroom.query.grammar.antlr.StroomQLParser.BetweenTermContext;
import stroom.query.grammar.antlr.StroomQLParser.ComparisonCondContext;
import stroom.query.grammar.antlr.StroomQLParser.ComparisonTermContext;
import stroom.query.grammar.antlr.StroomQLParser.EvalClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.FieldRefContext;
import stroom.query.grammar.antlr.StroomQLParser.FilterClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.FromClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.GroupClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.HavingClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.InDictionaryTermContext;
import stroom.query.grammar.antlr.StroomQLParser.InTermContext;
import stroom.query.grammar.antlr.StroomQLParser.IsNullTermContext;
import stroom.query.grammar.antlr.StroomQLParser.JoinClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.JoinConditionContext;
import stroom.query.grammar.antlr.StroomQLParser.LimitClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.LimitValueContext;
import stroom.query.grammar.antlr.StroomQLParser.NameTokenContext;
import stroom.query.grammar.antlr.StroomQLParser.NotExprContext;
import stroom.query.grammar.antlr.StroomQLParser.OrExprContext;
import stroom.query.grammar.antlr.StroomQLParser.PrimaryContext;
import stroom.query.grammar.antlr.StroomQLParser.QualifiedFieldContext;
import stroom.query.grammar.antlr.StroomQLParser.QueryContext;
import stroom.query.grammar.antlr.StroomQLParser.SelectClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.SelectFieldContext;
import stroom.query.grammar.antlr.StroomQLParser.SelectFunctionContext;
import stroom.query.grammar.antlr.StroomQLParser.SelectItemContext;
import stroom.query.grammar.antlr.StroomQLParser.SelectParamContext;
import stroom.query.grammar.antlr.StroomQLParser.SelectStarContext;
import stroom.query.grammar.antlr.StroomQLParser.ShowClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.SortClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.SortItemContext;
import stroom.query.grammar.antlr.StroomQLParser.TermContext;
import stroom.query.grammar.antlr.StroomQLParser.TermValueContext;
import stroom.query.grammar.antlr.StroomQLParser.TopLevelClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.WhereClauseContext;
import stroom.query.grammar.antlr.StroomQLParser.WindowClauseContext;
import stroom.query.grammar.parse.SyntaxException;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts an ANTLR {@code StroomQL.g4} parse tree into a plain-Java {@link AstQuery}, decoupling downstream code
 * (Tasks 1.3+) from ANTLR types entirely.
 *
 * <p>Deliberately implemented as a direct set of typed helper methods rather than by extending the generated
 * {@code StroomQLBaseVisitor} - this grammar leans heavily on labelled alternatives (e.g. {@code term},
 * {@code selectItem}), so each call site already statically knows the expected context type; a generic
 * {@code Object}-returning visitor would only add casts without adding safety.</p>
 *
 * <p>Per {@code StroomQL.g4}'s file header, {@code fexpr} (eval/select computed expressions) and term values are
 * NOT parsed into a deep expression tree here - they are captured as exact original source text (via
 * {@link TokenStream#getText(ParserRuleContext)}, which preserves whitespace exactly as legacy's slice-based
 * {@code AbstractToken.getText()} does) for Task 1.4 to hand verbatim to the existing, unchanged
 * {@code ExpressionParser}/{@code Tokeniser}.</p>
 */
@NullMarked
public final class AstBuilder {

    private final TokenStream tokenStream;

    /**
     * @param tokenStream the token stream the parse tree being built from was parsed from. Must not be null;
     *                    must be the same stream (so its buffered tokens cover the same source) used to build
     *                    any {@link ParserRuleContext} passed to {@link #build(QueryContext)}.
     */
    public AstBuilder(final TokenStream tokenStream) {
        this.tokenStream = Objects.requireNonNull(tokenStream, "tokenStream");
    }

    /**
     * @param ctx the root parse tree node, as produced by {@code StroomQLParser.query()}. Must not be null.
     * @return never null; a full, decoupled AST for the whole query.
     */
    public AstQuery build(final QueryContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        final AstFrom from = buildFrom(ctx.fromClause());
        final List<AstClause> clauses = new ArrayList<>(ctx.topLevelClause().size());
        for (final TopLevelClauseContext clauseCtx : ctx.topLevelClause()) {
            clauses.add(buildTopLevelClause(clauseCtx));
        }
        return new AstQuery(from, clauses, position(ctx));
    }

    // ------------------------------------------------------------------------------------------------------
    // from / join
    // ------------------------------------------------------------------------------------------------------

    private AstFrom buildFrom(final FromClauseContext ctx) {
        final List<AstJoin> joins = new ArrayList<>(ctx.joinClause().size());
        for (final JoinClauseContext joinCtx : ctx.joinClause()) {
            joins.add(buildJoin(joinCtx));
        }
        return new AstFrom(
                buildToken(ctx.source),
                ctx.alias == null ? null : buildToken(ctx.alias),
                joins,
                position(ctx));
    }

    private AstJoin buildJoin(final JoinClauseContext ctx) {
        final AstJoin.JoinType joinType;
        if (ctx.joinType == null) {
            joinType = null;
        } else {
            joinType = switch (ctx.joinType.getType()) {
                case StroomQLParser.LEFT -> AstJoin.JoinType.LEFT;
                case StroomQLParser.INNER -> AstJoin.JoinType.INNER;
                default -> throw new IllegalStateException("Unexpected join type token: " + ctx.joinType);
            };
        }
        final List<AstJoinCondition> conditions = new ArrayList<>(ctx.joinCondition().size());
        for (final JoinConditionContext conditionCtx : ctx.joinCondition()) {
            conditions.add(buildJoinCondition(conditionCtx));
        }
        return new AstJoin(
                joinType,
                buildToken(ctx.source),
                ctx.alias == null ? null : buildToken(ctx.alias),
                conditions,
                position(ctx));
    }

    private AstJoinCondition buildJoinCondition(final JoinConditionContext ctx) {
        return new AstJoinCondition(buildQualifiedField(ctx.left), buildQualifiedField(ctx.right), position(ctx));
    }

    private AstToken buildQualifiedField(final QualifiedFieldContext ctx) {
        return buildToken(ctx.nameToken());
    }

    // ------------------------------------------------------------------------------------------------------
    // top-level clauses
    // ------------------------------------------------------------------------------------------------------

    private AstClause buildTopLevelClause(final TopLevelClauseContext ctx) {
        if (ctx.whereClause() != null) {
            return buildWhere(ctx.whereClause());
        } else if (ctx.evalClause() != null) {
            return buildEval(ctx.evalClause());
        } else if (ctx.windowClause() != null) {
            return buildWindow(ctx.windowClause());
        } else if (ctx.filterClause() != null) {
            return buildFilter(ctx.filterClause());
        } else if (ctx.sortClause() != null) {
            return buildSort(ctx.sortClause());
        } else if (ctx.groupClause() != null) {
            return buildGroup(ctx.groupClause());
        } else if (ctx.havingClause() != null) {
            return buildHaving(ctx.havingClause());
        } else if (ctx.selectClause() != null) {
            return buildSelect(ctx.selectClause());
        } else if (ctx.limitClause() != null) {
            return buildLimit(ctx.limitClause());
        } else if (ctx.showClause() != null) {
            return buildShow(ctx.showClause());
        }
        throw new IllegalStateException("Unrecognised top level clause: " + ctx.getText());
    }

    private AstWhereClause buildWhere(final WhereClauseContext ctx) {
        return new AstWhereClause(buildOrExpr(ctx.expr().orExpr()), position(ctx));
    }

    private AstFilterClause buildFilter(final FilterClauseContext ctx) {
        return new AstFilterClause(buildOrExpr(ctx.expr().orExpr()), position(ctx));
    }

    private AstHavingClause buildHaving(final HavingClauseContext ctx) {
        return new AstHavingClause(buildOrExpr(ctx.expr().orExpr()), position(ctx));
    }

    private AstEvalClause buildEval(final EvalClauseContext ctx) {
        return new AstEvalClause(buildFieldRef(ctx.name), tokenStream.getText(ctx.fexpr()), position(ctx));
    }

    private AstWindowClause buildWindow(final WindowClauseContext ctx) {
        // The grammar accepts any bareword where the 'advance'/'using' keywords go (they are labelled
        // advanceKw/usingKw but are ordinary nameTokens), so a mistyped keyword parses cleanly. Legacy
        // (SearchRequestFactory.processWindow) rejects anything but the literal words; reject it here too rather
        // than silently discarding the keyword and mis-mapping the clause (a real parity + error-reporting gap).
        requireWindowKeyword(ctx.advanceKw, "advance");
        requireWindowKeyword(ctx.usingKw, "using");
        return new AstWindowClause(
                buildFieldRef(ctx.field),
                buildTerminalToken(ctx.windowSize, AstToken.Kind.BAREWORD),
                ctx.advanceSize == null ? null : buildTerminalToken(ctx.advanceSize, AstToken.Kind.BAREWORD),
                ctx.usingFunction == null ? null : buildToken(ctx.usingFunction),
                position(ctx));
    }

    private static void requireWindowKeyword(final NameTokenContext keyword, final String expected) {
        if (keyword == null) {
            return;
        }
        final String text = keyword.getText();
        if (!expected.equalsIgnoreCase(text)) {
            final Token start = keyword.getStart();
            throw new SyntaxException(
                    "Unexpected token '" + text + "' in window clause - expected '" + expected + "'",
                    start.getLine(), start.getCharPositionInLine(), List.of("'" + expected + "'"));
        }
    }

    private AstSortClause buildSort(final SortClauseContext ctx) {
        final List<AstSortItem> items = new ArrayList<>(ctx.sortItem().size());
        for (final SortItemContext itemCtx : ctx.sortItem()) {
            items.add(new AstSortItem(
                    buildFieldRef(itemCtx.field),
                    itemCtx.direction == null ? null : buildToken(itemCtx.direction),
                    position(itemCtx)));
        }
        return new AstSortClause(items, position(ctx));
    }

    private AstGroupClause buildGroup(final GroupClauseContext ctx) {
        final List<AstToken> fields = new ArrayList<>(ctx.fieldRef().size());
        for (final FieldRefContext fieldCtx : ctx.fieldRef()) {
            fields.add(buildFieldRef(fieldCtx));
        }
        return new AstGroupClause(fields, position(ctx));
    }

    private AstSelectClause buildSelect(final SelectClauseContext ctx) {
        final List<AstSelectItem> items = new ArrayList<>(ctx.selectItem().size());
        for (final SelectItemContext itemCtx : ctx.selectItem()) {
            items.add(buildSelectItem(itemCtx));
        }
        return new AstSelectClause(items, position(ctx));
    }

    private AstSelectItem buildSelectItem(final SelectItemContext ctx) {
        if (ctx instanceof final SelectStarContext starCtx) {
            return new AstSelectStar(
                    starCtx.alias == null ? null : buildToken(starCtx.alias),
                    position(starCtx));
        } else if (ctx instanceof final SelectFunctionContext functionCtx) {
            return new AstSelectFunction(
                    tokenStream.getText(functionCtx.functionCall()),
                    functionCtx.alias == null ? null : buildToken(functionCtx.alias),
                    position(functionCtx));
        } else if (ctx instanceof final SelectParamContext paramCtx) {
            return new AstSelectParam(
                    buildTerminalToken(paramCtx.field, AstToken.Kind.PARAM),
                    paramCtx.alias == null ? null : buildToken(paramCtx.alias),
                    position(paramCtx));
        } else if (ctx instanceof final SelectFieldContext fieldCtx) {
            return new AstSelectField(
                    buildToken(fieldCtx.field),
                    fieldCtx.alias == null ? null : buildToken(fieldCtx.alias),
                    position(fieldCtx));
        }
        throw new IllegalStateException("Unrecognised select item: " + ctx.getText());
    }

    private AstLimitClause buildLimit(final LimitClauseContext ctx) {
        final List<AstToken> values = new ArrayList<>(ctx.limitValue().size());
        for (final LimitValueContext valueCtx : ctx.limitValue()) {
            values.add(buildLimitValue(valueCtx));
        }
        return new AstLimitClause(values, position(ctx));
    }

    private AstToken buildLimitValue(final LimitValueContext ctx) {
        if (ctx.NUMBER() != null) {
            return buildTerminalToken(ctx.NUMBER().getSymbol(), AstToken.Kind.BAREWORD);
        }
        return buildToken(ctx.nameToken());
    }

    private AstShowClause buildShow(final ShowClauseContext ctx) {
        return new AstShowClause(buildToken(ctx.name), position(ctx));
    }

    // ------------------------------------------------------------------------------------------------------
    // boolean expression (where / filter / having)
    // ------------------------------------------------------------------------------------------------------

    private AstOrExpr buildOrExpr(final OrExprContext ctx) {
        final List<AstAndExpr> operands = new ArrayList<>(ctx.andExpr().size());
        for (final AndExprContext andCtx : ctx.andExpr()) {
            operands.add(buildAndExpr(andCtx));
        }
        return new AstOrExpr(operands, position(ctx));
    }

    private AstAndExpr buildAndExpr(final AndExprContext ctx) {
        final List<AstNotExpr> operands = new ArrayList<>(ctx.notExpr().size());
        for (final NotExprContext notCtx : ctx.notExpr()) {
            operands.add(buildNotExpr(notCtx));
        }
        return new AstAndExpr(operands, position(ctx));
    }

    private AstNotExpr buildNotExpr(final NotExprContext ctx) {
        if (ctx.NOT() != null) {
            return new AstNotExpr(true, buildNotExpr(ctx.notExpr()), null, position(ctx));
        }
        return new AstNotExpr(false, null, buildPrimary(ctx.primary()), position(ctx));
    }

    private AstPrimary buildPrimary(final PrimaryContext ctx) {
        if (ctx.expr() != null) {
            return new AstPrimary(buildOrExpr(ctx.expr().orExpr()), null, position(ctx));
        }
        return new AstPrimary(null, buildTerm(ctx.term()), position(ctx));
    }

    private AstTerm buildTerm(final TermContext ctx) {
        if (ctx instanceof final ComparisonTermContext comparisonCtx) {
            return new AstComparisonTerm(
                    buildFieldRef(comparisonCtx.field),
                    buildComparisonCond(comparisonCtx.cond),
                    buildValue(comparisonCtx.value),
                    position(comparisonCtx));
        } else if (ctx instanceof final BetweenTermContext betweenCtx) {
            return new AstBetweenTerm(
                    buildFieldRef(betweenCtx.field),
                    buildValue(betweenCtx.lower),
                    buildValue(betweenCtx.upper),
                    position(betweenCtx));
        } else if (ctx instanceof final InTermContext inCtx) {
            final List<AstValue> values = new ArrayList<>(inCtx.termValue().size());
            for (final TermValueContext valueCtx : inCtx.termValue()) {
                values.add(buildValue(valueCtx));
            }
            return new AstInTerm(buildFieldRef(inCtx.field), values, position(inCtx));
        } else if (ctx instanceof final InDictionaryTermContext inDictionaryCtx) {
            return new AstInDictionaryTerm(
                    buildFieldRef(inDictionaryCtx.field),
                    buildToken(inDictionaryCtx.dictionaryName),
                    position(inDictionaryCtx));
        } else if (ctx instanceof final IsNullTermContext isNullCtx) {
            final boolean negated =
                    isNullCtx.isNull.getType() == StroomQLParser.IS_NOT_NULL;
            return new AstIsNullTerm(buildFieldRef(isNullCtx.field), negated, position(isNullCtx));
        }
        throw new IllegalStateException("Unrecognised term: " + ctx.getText());
    }

    private AstToken buildFieldRef(final FieldRefContext ctx) {
        if (ctx.nameToken() != null) {
            return buildToken(ctx.nameToken());
        }
        return buildTerminalToken(ctx.PARAM().getSymbol(), AstToken.Kind.PARAM);
    }

    private AstComparisonCond buildComparisonCond(final ComparisonCondContext ctx) {
        final Token token = ctx.getStart();
        return switch (token.getType()) {
            case StroomQLParser.EQUALS -> AstComparisonCond.EQUALS;
            case StroomQLParser.NOT_EQUALS -> AstComparisonCond.NOT_EQUALS;
            case StroomQLParser.GREATER_THAN -> AstComparisonCond.GREATER_THAN;
            case StroomQLParser.GREATER_THAN_OR_EQUAL_TO ->
                    AstComparisonCond.GREATER_THAN_OR_EQUAL_TO;
            case StroomQLParser.LESS_THAN -> AstComparisonCond.LESS_THAN;
            case StroomQLParser.LESS_THAN_OR_EQUAL_TO ->
                    AstComparisonCond.LESS_THAN_OR_EQUAL_TO;
            default -> throw new IllegalStateException("Unrecognised comparison condition: " + ctx.getText());
        };
    }

    private AstValue buildValue(final TermValueContext ctx) {
        return new AstValue(tokenStream.getText(ctx), position(ctx));
    }

    // ------------------------------------------------------------------------------------------------------
    // shared leaves
    // ------------------------------------------------------------------------------------------------------

    private AstToken buildToken(final NameTokenContext ctx) {
        final AstToken.Kind kind;
        if (ctx.SINGLE_QUOTED_STRING() != null) {
            kind = AstToken.Kind.SINGLE_QUOTED;
        } else if (ctx.DOUBLE_QUOTED_STRING() != null) {
            kind = AstToken.Kind.DOUBLE_QUOTED;
        } else {
            kind = AstToken.Kind.BAREWORD;
        }
        return new AstToken(kind, ctx.getText(), position(ctx));
    }

    private AstToken buildTerminalToken(final Token token, final AstToken.Kind kind) {
        return new AstToken(kind, token.getText(), position(token));
    }

    private AstPosition position(final ParserRuleContext ctx) {
        return position(ctx.getStart());
    }

    private AstPosition position(final Token token) {
        return new AstPosition(token.getLine(), token.getCharPositionInLine());
    }
}
