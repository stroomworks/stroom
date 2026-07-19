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

package stroom.query.planner.cypher;

import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.grammar.ast.cypher.AstAggregateExpr;
import stroom.query.grammar.ast.cypher.AstAndExpr;
import stroom.query.grammar.ast.cypher.AstAround;
import stroom.query.grammar.ast.cypher.AstAsOf;
import stroom.query.grammar.ast.cypher.AstBetween;
import stroom.query.grammar.ast.cypher.AstBooleanExpr;
import stroom.query.grammar.ast.cypher.AstBooleanValue;
import stroom.query.grammar.ast.cypher.AstComparisonPredicate;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.ast.cypher.AstEdgeDirection;
import stroom.query.grammar.ast.cypher.AstEdgePattern;
import stroom.query.grammar.ast.cypher.AstExpression;
import stroom.query.grammar.ast.cypher.AstFunctionValue;
import stroom.query.grammar.ast.cypher.AstLiteralExpr;
import stroom.query.grammar.ast.cypher.AstMatch;
import stroom.query.grammar.ast.cypher.AstNodePattern;
import stroom.query.grammar.ast.cypher.AstNotExpr;
import stroom.query.grammar.ast.cypher.AstNumberValue;
import stroom.query.grammar.ast.cypher.AstOrExpr;
import stroom.query.grammar.ast.cypher.AstOrderBy;
import stroom.query.grammar.ast.cypher.AstOrderItem;
import stroom.query.grammar.ast.cypher.AstParameterValue;
import stroom.query.grammar.ast.cypher.AstPathPattern;
import stroom.query.grammar.ast.cypher.AstPatternHop;
import stroom.query.grammar.ast.cypher.AstPropertyAccessExpr;
import stroom.query.grammar.ast.cypher.AstPropertyKeyValue;
import stroom.query.grammar.ast.cypher.AstReadingClause;
import stroom.query.grammar.ast.cypher.AstReturnClause;
import stroom.query.grammar.ast.cypher.AstReturnItem;
import stroom.query.grammar.ast.cypher.AstStringValue;
import stroom.query.grammar.ast.cypher.AstTemporal;
import stroom.query.grammar.ast.cypher.AstValue;
import stroom.query.grammar.ast.cypher.AstVarLength;
import stroom.query.grammar.ast.cypher.AstVariableExpr;
import stroom.query.grammar.ast.cypher.AstWith;
import stroom.query.planner.logical.Direction;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.SortKey;
import stroom.query.planner.logical.VarLengthExpand;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compiles a Cypher AST (see {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.3) into the
 * shared {@link LogicalPlan} IR the relational query core already uses - Cypher is a second front-end onto one
 * IR, not a forked engine (design doc &sect;5.3).
 *
 * <p><b>Compiled shape (the only shape this class lowers):</b> a single {@link AstMatch} whose pattern is an
 * anchor node with zero or more fixed-length hops (Task P3.2), e.g.
 * {@code MATCH (a:L {p:v})-[:T]->(b:L2)-[:U]->(c:L3) [WHERE ...] RETURN ...} lowers to
 * {@code Project(Filter?(Expand(Expand(NodeScan(a,[L],p=v), T, OUT, b), U, OUT, c)), returnItems)} - each hop
 * folds left-to-right, anchor-first, in exactly the pattern's source order (a bare anchor with no hop skips
 * {@link Expand} entirely; never re-ordered by any selectivity heuristic, since only the anchor has a
 * property-index-seekable access path in this v1 subset - see {@link Expand}'s Javadoc). {@code RETURN}'s
 * {@code ORDER BY}/{@code SKIP}/{@code LIMIT} wrap the {@link Project} with {@link Sort}/{@link Limit} exactly as
 * the relational core's own binder does; aggregate functions ({@code count}/{@code sum}/{@code avg}/{@code min}/
 * {@code max}) compile to {@link ProjectField} expressions only - GROUP-BY inference for a mixed aggregate/plain
 * {@code RETURN} is not yet implemented (a genuine gap tracked for a later phase, not silently wrong: such a
 * query still compiles, but does not yet group distinct combinations of the non-aggregate columns).</p>
 *
 * <p>A hop's target (non-anchor) node pattern's own labels/inline properties (Task P3.1) compile onto
 * {@link Expand#targetLabels()}/{@link Expand#targetPropertyPredicate()} (or the {@link VarLengthExpand}
 * equivalents) using the same property-term lowering as an anchor's own {@link NodeScan#propertyAnchor()} - the
 * executor enforces these as a post-expand filter (see {@link Expand}'s Javadoc for why a target constraint is a
 * filter, not an alternative access path). This applies identically to every hop in a chain, not just the
 * pattern's last one.</p>
 *
 * <p>A bounded variable-length hop (Task P3.3), e.g. {@code -[:T*1..3]->}, compiles to a single
 * {@link VarLengthExpand} directly over the anchor {@link NodeScan} - {@code minHops} defaults to 1 when
 * {@link AstVarLength#min()} is absent (Cypher's own default), {@code maxHops} is always present (the grammar
 * makes an unbounded {@code *} a parse-time error). A var-length hop is only compiled when it is the pattern's
 * <em>sole</em> hop; chaining one with fixed-length hops on either side is a further generalisation this class
 * does not attempt (see below).</p>
 *
 * <p><b>What this class deliberately does NOT lower</b> (throws {@link CypherCompileException} instead of
 * guessing): more than one {@link AstMatch}/{@link AstWith} reading clause; a variable-length hop chained
 * alongside other hops in the same pattern; a {@code WHERE} comparison between two field references (only
 * field-vs-literal comparisons compile). All of the above are accepted by {@code Cypher.g4} (per the
 * P0.2-locked v1 subset) but are out of this class's compiled shape; they are progressively tightened across
 * P3's tasks.</p>
 */
public final class CypherToLogicalPlan {

    private int anonymousVariableCounter;

    /**
     * <b>Preconditions:</b> {@code query} is not null.
     * <b>Postconditions:</b> returns a plan whose leaves reference the graph datasource (a {@link NodeScan}),
     * with the query's resolved temporal context (if any) alongside it.
     * <b>Null status:</b> neither the parameter nor the return value is nullable.
     *
     * @param query the parsed Cypher query.
     * @return never null.
     * @throws CypherCompileException if {@code query} is outside the compiled shape described in this class's
     *                                 Javadoc.
     */
    public CompiledCypherPlan compile(final AstCypherQuery query) {
        Objects.requireNonNull(query, "query");

        if (query.readingClauses().size() != 1) {
            throw new CypherCompileException(
                    "not in PoC subset: exactly one MATCH clause is supported (WITH-chaining and multiple "
                    + "MATCH clauses are a later phase), found " + query.readingClauses().size(),
                    query.position());
        }
        final AstReadingClause readingClause = query.readingClauses().getFirst();
        if (!(readingClause instanceof final AstMatch match)) {
            throw new CypherCompileException(
                    "not in PoC subset: a WITH clause is not yet compiled", readingClause.position());
        }

        final PatternResult patternResult = compilePattern(match.pattern());
        LogicalPlan plan = patternResult.plan();

        TemporalContext temporalContext = null;
        if (match.temporal() != null) {
            temporalContext = resolveTemporal(match.temporal());
        }

        if (match.where() != null) {
            final ExpressionOperator predicate = compileBooleanExpr(match.where().expr());
            plan = new Filter(plan, predicate, null, match.where().position());
        }

        plan = compileReturn(plan, query.returnClause());

        return new CompiledCypherPlan(plan, temporalContext);
    }

    // ------------------------------------------------------------------------------------------------------
    // pattern: NodeScan [+ Expand chain]
    // ------------------------------------------------------------------------------------------------------

    private record PatternResult(LogicalPlan plan) {
    }

    private PatternResult compilePattern(final AstPathPattern pattern) {
        final NodeScan anchor = compileNodeScan(pattern.anchor());

        if (pattern.hops().size() == 1 && pattern.hops().getFirst().edge().varLength() != null) {
            return new PatternResult(compileVarLengthExpand(anchor, pattern.hops().getFirst()));
        }

        LogicalPlan plan = anchor;
        for (final var hop : pattern.hops()) {
            final AstEdgePattern edge = hop.edge();
            if (edge.varLength() != null) {
                throw new CypherCompileException(
                        "not in PoC subset: chaining a variable-length hop with other hops in the same pattern "
                        + "is not yet compiled - a variable-length hop must be the pattern's only hop",
                        edge.position());
            }

            final String targetVariable = hop.node().variable() != null
                    ? hop.node().variable()
                    : nextAnonymousVariable();

            plan = new Expand(
                    plan,
                    edge.type(),
                    toDirection(edge.direction()),
                    targetVariable,
                    hop.node().labels(),
                    compilePropertyPredicate(hop.node().properties()),
                    hop.position());
        }
        return new PatternResult(plan);
    }

    private VarLengthExpand compileVarLengthExpand(final NodeScan anchor, final AstPatternHop hop) {
        final AstEdgePattern edge = hop.edge();
        final AstVarLength varLength = edge.varLength();
        final String targetVariable = hop.node().variable() != null
                ? hop.node().variable()
                : nextAnonymousVariable();
        final int minHops = varLength.min() != null ? varLength.min() : 1;

        return new VarLengthExpand(
                anchor,
                edge.type(),
                toDirection(edge.direction()),
                minHops,
                varLength.max(),
                targetVariable,
                hop.node().labels(),
                compilePropertyPredicate(hop.node().properties()),
                hop.position());
    }

    private NodeScan compileNodeScan(final AstNodePattern node) {
        final String variable = node.variable() != null ? node.variable() : nextAnonymousVariable();
        return new NodeScan(variable, node.labels(), compilePropertyPredicate(node.properties()), node.position());
    }

    /**
     * Lowers a node pattern's inline {@code {key: value, ...}} property map to an equality predicate tree, shared
     * between an anchor's {@link NodeScan#propertyAnchor()} and a hop target's
     * {@link Expand#targetPropertyPredicate()} (Task P3.1) - both are the same AND-of-equalities shape, just
     * consumed differently by the executor (an anchor seeks by it; a target filters by it post-expand).
     *
     * @param properties never null; possibly empty.
     * @return {@code null} if {@code properties} is empty, otherwise never null.
     */
    private static @Nullable ExpressionOperator compilePropertyPredicate(final List<AstPropertyKeyValue> properties) {
        if (properties.isEmpty()) {
            return null;
        }
        final List<ExpressionTerm> terms = new ArrayList<>(properties.size());
        for (final AstPropertyKeyValue property : properties) {
            terms.add(ExpressionTerm.builder()
                    .field(property.key())
                    .condition(Condition.EQUALS)
                    .value(renderLiteralValue(property.value()))
                    .build());
        }
        return ExpressionOperator.builder().op(Op.AND).addTerms(terms).build();
    }

    private String nextAnonymousVariable() {
        return "$$anon" + (anonymousVariableCounter++);
    }

    private static Direction toDirection(final AstEdgeDirection direction) {
        return switch (direction) {
            case OUT -> Direction.OUT;
            case IN -> Direction.IN;
            case BOTH -> Direction.BOTH;
        };
    }

    // ------------------------------------------------------------------------------------------------------
    // WHERE: AstBooleanExpr -> ExpressionOperator
    // ------------------------------------------------------------------------------------------------------

    private ExpressionOperator compileBooleanExpr(final AstBooleanExpr expr) {
        if (expr instanceof final AstOrExpr or) {
            return ExpressionOperator.builder()
                    .op(Op.OR)
                    .children(or.operands().stream().map(this::compileBooleanExprAsItem).toList())
                    .build();
        } else if (expr instanceof final AstAndExpr and) {
            return ExpressionOperator.builder()
                    .op(Op.AND)
                    .children(and.operands().stream().map(this::compileBooleanExprAsItem).toList())
                    .build();
        } else if (expr instanceof final AstNotExpr not) {
            return ExpressionOperator.builder()
                    .op(Op.NOT)
                    .children(List.of(compileBooleanExprAsItem(not.operand())))
                    .build();
        } else if (expr instanceof final AstComparisonPredicate predicate) {
            return ExpressionOperator.builder().addTerm(compileComparisonTerm(predicate)).build();
        }
        throw new CypherCompileException("Unrecognised WHERE expression", expr.position());
    }

    private ExpressionItem compileBooleanExprAsItem(final AstBooleanExpr expr) {
        if (expr instanceof final AstComparisonPredicate predicate) {
            return compileComparisonTerm(predicate);
        }
        return compileBooleanExpr(expr);
    }

    private ExpressionTerm compileComparisonTerm(final AstComparisonPredicate predicate) {
        final String field = fieldNameOf(predicate.left());
        if (field == null) {
            throw new CypherCompileException(
                    "not in PoC subset: the left side of a WHERE comparison must be a property access or "
                    + "variable reference", predicate.left().position());
        }
        if (!(predicate.right() instanceof final AstLiteralExpr literal)) {
            throw new CypherCompileException(
                    "not in PoC subset: comparing two field references (or an aggregate) is not yet supported "
                    + "- the right side of a WHERE comparison must be a literal", predicate.right().position());
        }
        return ExpressionTerm.builder()
                .field(field)
                .condition(toCondition(predicate.op()))
                .value(renderLiteralValue(literal.value()))
                .build();
    }

    private static String fieldNameOf(final AstExpression expression) {
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return propertyAccess.variable() + "." + propertyAccess.property();
        } else if (expression instanceof final AstVariableExpr variable) {
            return variable.name();
        }
        return null;
    }

    private static Condition toCondition(final stroom.query.grammar.ast.cypher.AstComparisonOp op) {
        return switch (op) {
            case EQ -> Condition.EQUALS;
            case NEQ -> Condition.NOT_EQUALS;
            case LT -> Condition.LESS_THAN;
            case LE -> Condition.LESS_THAN_OR_EQUAL_TO;
            case GT -> Condition.GREATER_THAN;
            case GE -> Condition.GREATER_THAN_OR_EQUAL_TO;
        };
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN / ORDER BY / SKIP / LIMIT
    // ------------------------------------------------------------------------------------------------------

    private LogicalPlan compileReturn(final LogicalPlan input, final AstReturnClause returnClause) {
        final List<ProjectField> fields = new ArrayList<>(returnClause.items().size());
        for (final AstReturnItem item : returnClause.items()) {
            fields.add(compileReturnItem(item));
        }
        LogicalPlan plan = new Project(input, fields, returnClause.position());

        if (returnClause.orderBy() != null) {
            plan = new Sort(plan, compileOrderBy(returnClause.orderBy()), returnClause.orderBy().position());
        }
        if (returnClause.skip() != null || returnClause.limit() != null) {
            // The relational core's Limit models only a maximum row count (see Limit's Javadoc); Cypher's SKIP
            // has no equivalent slot here yet, so a query using SKIP is rejected rather than silently ignored.
            if (returnClause.skip() != null) {
                throw new CypherCompileException(
                        "not in PoC subset: SKIP is not yet compiled (the core's Limit node has no offset "
                        + "slot)", returnClause.skip().position());
            }
            plan = new Limit(plan, List.of(returnClause.limit().value()), returnClause.limit().position());
        }
        return plan;
    }

    private ProjectField compileReturnItem(final AstReturnItem item) {
        final String rawExpression = renderExpression(item.expression());
        final String name = item.alias() != null ? item.alias() : defaultColumnName(item.expression());
        return new ProjectField(name, rawExpression, true, item.alias(), item.position());
    }

    private static String defaultColumnName(final AstExpression expression) {
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return propertyAccess.variable() + "." + propertyAccess.property();
        } else if (expression instanceof final AstVariableExpr variable) {
            return variable.name();
        }
        // A literal or aggregate with no alias - the raw expression text is as good a default name as any.
        return renderExpression(expression);
    }

    private List<SortKey> compileOrderBy(final AstOrderBy orderBy) {
        final List<SortKey> keys = new ArrayList<>(orderBy.items().size());
        for (final AstOrderItem item : orderBy.items()) {
            final String field = fieldNameOf(item.expression());
            if (field == null) {
                throw new CypherCompileException(
                        "not in PoC subset: an ORDER BY item must be a property access or variable reference",
                        item.position());
            }
            keys.add(new SortKey(new QualifiedField(null, field), item.descending()));
        }
        return keys;
    }

    // ------------------------------------------------------------------------------------------------------
    // expression / value rendering (StroomQL-style text for ProjectField.rawExpression)
    // ------------------------------------------------------------------------------------------------------

    private static String renderExpression(final AstExpression expression) {
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return "${" + propertyAccess.variable() + "." + propertyAccess.property() + "}";
        } else if (expression instanceof final AstVariableExpr variable) {
            return "${" + variable.name() + "}";
        } else if (expression instanceof final AstLiteralExpr literal) {
            return renderValueAsExpression(literal.value());
        } else if (expression instanceof final AstAggregateExpr aggregate) {
            final String fn = aggregate.function().name().toLowerCase();
            return aggregate.star() ? fn + "()" : fn + "(" + renderExpression(aggregate.argument()) + ")";
        }
        throw new CypherCompileException("Unrecognised expression", expression.position());
    }

    private static String renderValueAsExpression(final AstValue value) {
        if (value instanceof final AstStringValue s) {
            return "'" + s.value().replace("\\", "\\\\").replace("'", "\\'") + "'";
        } else if (value instanceof final AstNumberValue n) {
            return n.rawText();
        } else if (value instanceof final AstBooleanValue b) {
            return String.valueOf(b.value());
        } else if (value instanceof final AstParameterValue p) {
            return "${" + p.name() + "}";
        } else if (value instanceof final AstFunctionValue f) {
            return f.name() + "(" + f.arguments().stream().map(CypherToLogicalPlan::renderValueAsExpression)
                    .reduce((a, b) -> a + ", " + b).orElse("") + ")";
        }
        throw new CypherCompileException("Unrecognised value", value.position());
    }

    /**
     * Renders a literal for use as an {@code ExpressionTerm} value (unquoted content, unlike
     * {@link #renderValueAsExpression}, which produces source-embeddable text) - a property-map value or a
     * WHERE comparison's literal side.
     */
    private static String renderLiteralValue(final AstValue value) {
        if (value instanceof final AstStringValue s) {
            return s.value();
        } else if (value instanceof final AstNumberValue n) {
            return n.rawText();
        } else if (value instanceof final AstBooleanValue b) {
            return String.valueOf(b.value());
        }
        throw new CypherCompileException(
                "not in PoC subset: only string/number/boolean literals are supported here", value.position());
    }

    // ------------------------------------------------------------------------------------------------------
    // temporal clause resolution
    // ------------------------------------------------------------------------------------------------------

    private TemporalContext resolveTemporal(final AstTemporal temporal) {
        if (temporal instanceof final AstAsOf asOf) {
            return TemporalContext.asOf(resolveInstant(asOf.instant()));
        } else if (temporal instanceof final AstAround around) {
            final Instant instant = resolveInstant(around.instant());
            final Duration duration = resolveDuration(around.duration());
            return TemporalContext.window(
                    TemporalContext.Mode.AROUND, instant.minus(duration), instant.plus(duration));
        } else if (temporal instanceof final AstBetween between) {
            return TemporalContext.window(
                    TemporalContext.Mode.BETWEEN, resolveInstant(between.from()), resolveInstant(between.to()));
        }
        throw new CypherCompileException("Unrecognised temporal clause", temporal.position());
    }

    private static Instant resolveInstant(final AstValue value) {
        if (value instanceof final AstFunctionValue f
                && f.name().equalsIgnoreCase("datetime")
                && f.arguments().size() == 1
                && f.arguments().getFirst() instanceof final AstStringValue s) {
            return parseInstant(s.value(), value.position());
        } else if (value instanceof final AstStringValue s) {
            return parseInstant(s.value(), value.position());
        }
        throw new CypherCompileException(
                "not in PoC subset: a temporal instant must be datetime('...') or a bare ISO-8601 string",
                value.position());
    }

    private static Instant parseInstant(final String text, final AstPosition position) {
        try {
            return Instant.parse(text);
        } catch (final java.time.format.DateTimeParseException e) {
            throw new CypherCompileException("Invalid ISO-8601 instant: '" + text + "'", position);
        }
    }

    private static Duration resolveDuration(final AstValue value) {
        if (value instanceof final AstFunctionValue f
                && f.name().equalsIgnoreCase("duration")
                && f.arguments().size() == 1
                && f.arguments().getFirst() instanceof final AstStringValue s) {
            return parseDuration(s.value(), value.position());
        } else if (value instanceof final AstStringValue s) {
            return parseDuration(s.value(), value.position());
        }
        throw new CypherCompileException(
                "not in PoC subset: a temporal duration must be duration('...') or a bare ISO-8601 duration "
                + "string", value.position());
    }

    private static Duration parseDuration(final String text, final AstPosition position) {
        try {
            return Duration.parse(text);
        } catch (final java.time.format.DateTimeParseException e) {
            throw new CypherCompileException("Invalid ISO-8601 duration: '" + text + "'", position);
        }
    }
}
