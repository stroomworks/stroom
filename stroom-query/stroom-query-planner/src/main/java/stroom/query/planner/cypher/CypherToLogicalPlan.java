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
import stroom.query.grammar.ast.cypher.AstPropertyAccessExpr;
import stroom.query.grammar.ast.cypher.AstPropertyKeyValue;
import stroom.query.grammar.ast.cypher.AstReadingClause;
import stroom.query.grammar.ast.cypher.AstReturnClause;
import stroom.query.grammar.ast.cypher.AstReturnItem;
import stroom.query.grammar.ast.cypher.AstStringValue;
import stroom.query.grammar.ast.cypher.AstTemporal;
import stroom.query.grammar.ast.cypher.AstValue;
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
 * anchor node with at most one hop, e.g. {@code MATCH (a:L {p:v})-[:T]->(b:L2) [WHERE ...] RETURN ...} lowers to
 * {@code Project(Filter?(Expand(NodeScan(a,[L],p=v), T, OUT, b)), returnItems)} (a bare anchor with no hop skips
 * the {@link Expand} entirely). {@code RETURN}'s {@code ORDER BY}/{@code SKIP}/{@code LIMIT} wrap the
 * {@link Project} with {@link Sort}/{@link Limit} exactly as the relational core's own binder does; aggregate
 * functions ({@code count}/{@code sum}/{@code avg}/{@code min}/{@code max}) compile to {@link ProjectField}
 * expressions only - GROUP-BY inference for a mixed aggregate/plain {@code RETURN} is not yet implemented (a
 * genuine gap tracked for a later phase, not silently wrong: such a query still compiles, but does not yet group
 * distinct combinations of the non-aggregate columns).</p>
 *
 * <p><b>What this class deliberately does NOT lower</b> (throws {@link CypherCompileException} instead of
 * guessing): more than one {@link AstMatch}/{@link AstWith} reading clause; a path pattern with more than one
 * hop; a hop with a variable-length ({@code *min..max}) edge; a {@code WHERE} comparison between two field
 * references (only field-vs-literal comparisons compile); a hop's non-anchor (target) node pattern's own labels
 * or inline properties, which - matching the design doc's own worked compilation example verbatim - are not yet
 * represented in the compiled plan and are silently not enforced (a known PoC limitation: the compiled
 * {@link Expand} node has no slot for a target node's label/property constraint until the traversal executor's
 * node representation is fixed in PoC.4/PoC.5). All of the above are accepted by {@code Cypher.g4} (per the
 * P0.2-locked v1 subset) but are out of this PoC's compiled shape; they are progressively tightened in P3.</p>
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
    // pattern: NodeScan [+ one Expand]
    // ------------------------------------------------------------------------------------------------------

    private record PatternResult(LogicalPlan plan) {
    }

    private PatternResult compilePattern(final AstPathPattern pattern) {
        if (pattern.hops().size() > 1) {
            throw new CypherCompileException(
                    "not in PoC subset: multi-hop chains beyond one edge are not yet compiled (P3), found "
                    + pattern.hops().size() + " hops",
                    pattern.position());
        }

        final NodeScan anchor = compileNodeScan(pattern.anchor());
        if (pattern.hops().isEmpty()) {
            return new PatternResult(anchor);
        }

        final var hop = pattern.hops().getFirst();
        final AstEdgePattern edge = hop.edge();
        if (edge.varLength() != null) {
            throw new CypherCompileException(
                    "not in PoC subset: variable-length paths are not yet compiled (P3)", edge.position());
        }

        // The hop's target node's own labels/properties are not yet represented in the compiled plan - see
        // this class's Javadoc for why (matches the design doc's worked compilation example verbatim).
        final String targetVariable = hop.node().variable() != null
                ? hop.node().variable()
                : nextAnonymousVariable();

        final Expand expand = new Expand(
                anchor,
                edge.type(),
                toDirection(edge.direction()),
                targetVariable,
                hop.position());
        return new PatternResult(expand);
    }

    private NodeScan compileNodeScan(final AstNodePattern node) {
        final String variable = node.variable() != null ? node.variable() : nextAnonymousVariable();

        ExpressionOperator propertyAnchor = null;
        if (!node.properties().isEmpty()) {
            final List<ExpressionTerm> terms = new ArrayList<>(node.properties().size());
            for (final AstPropertyKeyValue property : node.properties()) {
                terms.add(ExpressionTerm.builder()
                        .field(property.key())
                        .condition(Condition.EQUALS)
                        .value(renderLiteralValue(property.value()))
                        .build());
            }
            propertyAnchor = ExpressionOperator.builder().op(Op.AND).addTerms(terms).build();
        }

        return new NodeScan(variable, node.labels(), propertyAnchor, node.position());
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
