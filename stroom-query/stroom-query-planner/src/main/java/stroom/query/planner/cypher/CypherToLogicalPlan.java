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
import stroom.query.grammar.ast.cypher.AstAggregateFunction;
import stroom.query.grammar.ast.cypher.AstAndExpr;
import stroom.query.grammar.ast.cypher.AstArithmeticExpr;
import stroom.query.grammar.ast.cypher.AstArithmeticOp;
import stroom.query.grammar.ast.cypher.AstAround;
import stroom.query.grammar.ast.cypher.AstAsOf;
import stroom.query.grammar.ast.cypher.AstBetween;
import stroom.query.grammar.ast.cypher.AstBooleanExpr;
import stroom.query.grammar.ast.cypher.AstBooleanValue;
import stroom.query.grammar.ast.cypher.AstCaseExpr;
import stroom.query.grammar.ast.cypher.AstCaseWhen;
import stroom.query.grammar.ast.cypher.AstComparisonOp;
import stroom.query.grammar.ast.cypher.AstComparisonPredicate;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.ast.cypher.AstCypherStatement;
import stroom.query.grammar.ast.cypher.AstDiff;
import stroom.query.grammar.ast.cypher.AstDiffAccessorExpr;
import stroom.query.grammar.ast.cypher.AstDiffSide;
import stroom.query.grammar.ast.cypher.AstEdgeDirection;
import stroom.query.grammar.ast.cypher.AstEdgePattern;
import stroom.query.grammar.ast.cypher.AstExistsPredicate;
import stroom.query.grammar.ast.cypher.AstExpression;
import stroom.query.grammar.ast.cypher.AstFunctionValue;
import stroom.query.grammar.ast.cypher.AstInPredicate;
import stroom.query.grammar.ast.cypher.AstIsNullPredicate;
import stroom.query.grammar.ast.cypher.AstListValue;
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
import stroom.query.grammar.ast.cypher.AstWhere;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles a Cypher AST (Task PoC.3) into the
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
 * {@code ORDER BY} wraps the {@link Project} with a {@link Sort} and its {@code LIMIT} with a {@link Limit},
 * exactly as the relational core's own binder does; {@code RETURN DISTINCT} is carried on the
 * {@code CompiledCypherPlan} (the sealed shared IR has no Distinct node) - the graph executor honours all three
 * (sort, de-duplicate, cap). {@code SKIP} is still rejected (the core's {@link Limit} node has no offset slot).
 * Aggregate functions ({@code count}/{@code sum}/{@code avg}/{@code min}/{@code max}) compile to
 * {@link ProjectField} expressions <em>and</em> to a {@link CypherAggregation} description (see
 * {@link #buildAggregation}): every non-aggregate {@code RETURN} item becomes an implicit {@code GROUP BY} key
 * (Cypher's rule), carried on {@link CompiledCypherPlan#aggregation} for the graph executor to group and reduce
 * by - {@code null} when the {@code RETURN} has no aggregate item, so the executor's ordinary per-row projection
 * is unaffected. {@code collect} is not yet in the grammar (a separate, later phase - see
 * Phase 2).</p>
 *
 * <p>A hop's target (non-anchor) node pattern's own labels/inline properties (Task P3.1) compile onto
 * {@link Expand#targetLabels}/{@link Expand#targetPropertyPredicate} (or the {@link VarLengthExpand}
 * equivalents) using the same property-term lowering as an anchor's own {@link NodeScan#propertyAnchor} - the
 * executor enforces these as a post-expand filter (see {@link Expand}'s Javadoc for why a target constraint is a
 * filter, not an alternative access path). This applies identically to every hop in a chain, not just the
 * pattern's last one.</p>
 *
 * <p>A bounded variable-length hop (Task P3.3), e.g. {@code -[:T*1..3]->}, compiles to a single
 * {@link VarLengthExpand} directly over the anchor {@link NodeScan} - {@code minHops} defaults to 1 when
 * {@link AstVarLength#min} is absent (Cypher's own default), {@code maxHops} is always present (the grammar
 * makes an unbounded {@code *} a parse-time error). A var-length hop is only compiled when it is the pattern's
 * <em>sole</em> hop; chaining one with fixed-length hops on either side is a further generalisation this class
 * does not attempt (see below).</p>
 *
 * <p><b>What this class deliberately does NOT lower</b> (throws {@link CypherCompileException} instead of
 * guessing): more than one {@link AstMatch}/{@link AstWith} reading clause; a variable-length hop chained
 * alongside other hops in the same pattern; a {@code WHERE} comparison between two field references (only
 * field-vs-literal comparisons compile); {@code SKIP} (the core's {@link Limit} node has no offset slot yet). All
 * of the above are accepted by {@code Cypher.g4} (per the P0.2-locked v1 subset) but are out of this class's
 * compiled shape; they are progressively tightened across P3's tasks.</p>
 */
public final class CypherToLogicalPlan {

    /** The reserved DIFF pseudo-column naming an element's change classification; only valid in a DIFF query. */
    public static final String CHANGE_KIND_COLUMN = "changeKind";

    /**
     * The frozen {@code RETURN GRAPH} element-row column schema (
     * &sect;4.4 and &sect;3, whose simpler column-mapping table
     * is the authoritative target shape this constant follows): {@code kind} ({@code NODE}/{@code EDGE}),
     * {@code id} (the element's stable external identity - a node's interned external id, or an edge's
     * {@code src|type|dst}), {@code labels} (a node's label set, comma-joined; an edge's single type name),
     * {@code source}/{@code target} (an edge row's endpoint external ids; {@code null} for a node row), and
     * {@code properties} (the element's own property map, rendered as one JSON-object-valued column - a schemaless
     * graph mixing arbitrary node/edge types in one table cannot offer a per-property-key column and still be a
     * <em>fixed</em> schema, which {@code CypherCompiler.buildResultRequests} must advertise before any row is
     * seen). {@link #CHANGE_KIND_COLUMN} is appended as a 7th column only under {@code DIFF ... RETURN GRAPH} (the
     * annotated-subgraph mode) - see {@link #compile}. Public so {@code GraphElementExecutor}
     * (stroom-graphdb-impl) builds its {@code Val[]} rows in exactly this order from the one frozen definition.
     */
    public static final List<String> ELEMENT_ROW_COLUMNS =
            List.of("kind", "id", "labels", "source", "target", "properties");

    private int anonymousVariableCounter;

    /**
     * Compiles a whole statement - one or more single queries combined with {@code UNION} / {@code UNION ALL} -
     * into a {@link CompiledCypherStatement}. Each branch is compiled by {@link #compile(AstCypherQuery)}; a
     * multi-branch (UNION) statement additionally requires every branch to expose the same output column names and
     * forbids {@code RETURN GRAPH} / {@code DIFF} branches (those produce non-scalar row shapes that cannot be
     * union-folded). A single-branch statement is compiled with no extra constraints.
     */
    public CompiledCypherStatement compileStatement(final AstCypherStatement statement) {
        Objects.requireNonNull(statement, "statement");
        final List<CompiledCypherPlan> branches = new ArrayList<>(statement.branches().size());
        for (final AstCypherQuery branch : statement.branches()) {
            branches.add(compile(branch));
        }

        if (statement.branches().size() > 1) {
            final List<String> firstColumns = columnNames(branches.getFirst());
            for (int i = 0; i < branches.size(); i++) {
                final CompiledCypherPlan branch = branches.get(i);
                final AstPosition pos = statement.branches().get(i).position();
                if (branch.returnGraph() || branch.diffContext() != null) {
                    throw new CypherCompileException(
                            "not supported in this version: UNION with a RETURN GRAPH or DIFF branch (branch "
                            + (i + 1) + ")", pos);
                }
                final List<String> columns = columnNames(branch);
                if (!columns.equals(firstColumns)) {
                    throw new CypherCompileException(
                            "all UNION branches must return the same columns in the same order: branch 1 returns "
                            + firstColumns + " but branch " + (i + 1) + " returns " + columns, pos);
                }
            }
        }
        return new CompiledCypherStatement(branches, statement.unionAll());
    }

    private static List<String> columnNames(final CompiledCypherPlan plan) {
        return plan.outputFields().stream().map(ProjectField::name).toList();
    }

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

        // Accepted reading-clause shapes: a single MATCH; a MATCH followed by one OPTIONAL MATCH; or a MATCH
        // followed by one WITH (a single pipe). Every other shape (WITH-chaining, a second mandatory MATCH, a
        // MATCH after a WITH, >2 clauses, a leading OPTIONAL MATCH/WITH) is a later phase.
        final List<AstReadingClause> clauses = query.readingClauses();
        if (clauses.isEmpty() || clauses.size() > 2) {
            throw new CypherCompileException(
                    "not in PoC subset: only a single MATCH, optionally followed by one OPTIONAL MATCH or one "
                    + "WITH, is supported (further pipelining is a later phase), found " + clauses.size()
                    + " reading clauses", query.position());
        }
        if (!(clauses.getFirst() instanceof final AstMatch match)) {
            throw new CypherCompileException(
                    "not supported in this version: a query must begin with a MATCH", clauses.getFirst().position());
        }
        if (match.optional()) {
            throw new CypherCompileException(
                    "not supported in this version: a query cannot begin with OPTIONAL MATCH - it must extend a "
                    + "preceding MATCH", match.position());
        }
        AstMatch optionalMatch = null;
        if (clauses.size() == 2) {
            final AstReadingClause second = clauses.get(1);
            if (second instanceof final AstWith with) {
                return compileWithPipe(query, match, with);
            }
            if (!(second instanceof final AstMatch secondMatch) || !secondMatch.optional()) {
                throw new CypherCompileException(
                        "not in PoC subset: a MATCH may only be followed by an OPTIONAL MATCH or a WITH (a second "
                        + "mandatory MATCH is a later phase)", second.position());
            }
            optionalMatch = secondMatch;
        }

        final PatternResult patternResult = compilePattern(match.pattern());
        LogicalPlan plan = patternResult.plan();

        TemporalContext temporalContext = null;
        DiffContext diffContext = null;
        if (match.temporal() instanceof final AstDiff diff) {
            diffContext = resolveDiff(diff);
            rejectVarLengthUnderDiff(match.pattern());
        } else if (match.temporal() != null) {
            temporalContext = resolveTemporal(match.temporal());
        }

        if (query.returnClause().graph()) {
            if (optionalMatch != null) {
                throw new CypherCompileException(
                        "not supported in this version: OPTIONAL MATCH combined with RETURN GRAPH",
                        optionalMatch.position());
            }
            return compileReturnGraph(query, match, plan, temporalContext, diffContext);
        }

        // changeKind / before(...) / after(...) are DIFF-only accessors; reject them in a non-diff query at
        // compile time (with a positioned error) rather than letting them fail obscurely at execution.
        if (diffContext == null) {
            rejectDiffConstructsOutsideDiff(query.returnClause(), match.where());
        } else if (match.where() != null) {
            // v1 delta-table: the WHERE clause is evaluated per snapshot (pattern predicates only). Filtering on
            // changeKind / before(...) / after(...) is a post-classification (HAVING-like) step deferred to a
            // later phase - reject it here rather than silently evaluating it against a single snapshot's row
            // where those keys are absent (.1).
            rejectDiffConstructsInDiffWhere(match.where().expr());
        }

        // Fold an OPTIONAL MATCH's single hop onto the mandatory plan as an `optional` Expand - before the WHERE
        // Filter, so the Filter stays directly under the terminal Project (as unwrap expects) and the optional
        // hop is the plan's last hop. optionalVariables feeds count(...) lowering below.
        Set<String> optionalVariables = Set.of();
        if (optionalMatch != null) {
            if (diffContext != null) {
                throw new CypherCompileException(
                        "not supported in this version: OPTIONAL MATCH combined with DIFF", optionalMatch.position());
            }
            final OptionalFold fold = foldOptionalMatch(plan, patternResult.terminalVariable(), optionalMatch);
            plan = fold.plan();
            optionalVariables = fold.optionalVariables();
        }

        List<FieldComparison> fieldComparisons = List.of();
        List<CypherExists> existsPredicates = List.of();
        if (match.where() != null) {
            final WhereCompilation where = compileWhere(match.where().expr());
            if (diffContext != null && !where.fieldComparisons().isEmpty()) {
                throw new CypherCompileException(
                        "not supported in this version: comparing two fields (e.g. a.x > b.y) in a DIFF query's "
                        + "WHERE", match.where().position());
            }
            if (diffContext != null && !where.existsPredicates().isEmpty()) {
                throw new CypherCompileException(
                        "not supported in this version: EXISTS { ... } in a DIFF query's WHERE",
                        match.where().position());
            }
            fieldComparisons = where.fieldComparisons();
            existsPredicates = where.existsPredicates();
            if (where.literalPredicate() != null) {
                plan = new Filter(plan, where.literalPredicate(), null, match.where().position());
            }
        }

        final List<ProjectField> fields = buildProjectFields(query.returnClause());
        final CypherAggregation aggregation = buildAggregation(query.returnClause(), optionalVariables);
        if (diffContext != null && aggregation != null) {
            // v1 delta-table diffs one path at a time; grouping/reducing the classified delta table
            // (diff-aggregation) is deferred (.1).
            throw new CypherCompileException(
                    "not supported in this version: an aggregate in a DIFF query's RETURN (diff-aggregation is a "
                    + "later phase)", query.returnClause().position());
        }
        if (aggregation != null && query.returnClause().orderBy() != null) {
            // Compile-time check (not deferred to the executor): once RETURN aggregates rows, there is no
            // per-row traversal map left at execution time to sort by - the executor only ever sees the final,
            // one-row-per-group output tuple. Checked here, not in GraphTraversalEngine, so the rejection carries
            // the precise AST position of the offending ORDER BY item, matching this class's "fail fast with a
            // clear position" contract used throughout (see e.g. the SKIP rejection below).
            validateOrderByAgainstAggregation(query.returnClause().orderBy(), fields);
        }

        plan = compileReturn(plan, query.returnClause(), fields);

        return new CompiledCypherPlan(
                plan, temporalContext, query.returnClause().distinct(), aggregation, diffContext, false,
                fieldComparisons, existsPredicates, null);
    }

    // ------------------------------------------------------------------------------------------------------
    // WITH pipe: MATCH ... [WHERE] WITH <aliased items> [WHERE having] RETURN <final> (a single pipe)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Compiles a single {@code MATCH ... WITH ... RETURN} pipe. The {@code WITH} is compiled as stage one's
     * terminal projection/aggregation (reusing {@link #buildProjectFields}/{@link #buildAggregation}/
     * {@link #compileReturn}), producing one row per {@code WITH} column; the {@code WITH}'s own {@code WHERE}
     * (Cypher's HAVING) and the final {@code RETURN} become a {@link WithStage} the executor applies to those
     * rows. Every reference in the {@code HAVING}/final {@code RETURN} is validated to name a {@code WITH} column
     * (Cypher's WITH-scoping rule), so an out-of-scope reference fails loud rather than resolving to null.
     *
     * <p>v1 restrictions, each a fail-loud rejection: exactly one {@code WITH} and no second {@code MATCH}; every
     * {@code WITH} item must be aliased ({@code <expr> AS <name>}); no {@code ORDER BY}/{@code SKIP}/{@code LIMIT}
     * on the {@code WITH} or on the final {@code RETURN}; no aggregate in the final {@code RETURN}; not combined
     * with {@code DIFF}/{@code RETURN GRAPH}.</p>
     */
    private CompiledCypherPlan compileWithPipe(final AstCypherQuery query, final AstMatch match,
                                               final AstWith with) {
        final AstReturnClause finalReturn = query.returnClause();
        if (finalReturn.graph()) {
            throw new CypherCompileException(
                    "not supported in this version: RETURN GRAPH after a WITH", finalReturn.position());
        }
        if (match.temporal() instanceof AstDiff) {
            throw new CypherCompileException(
                    "not supported in this version: DIFF combined with WITH", match.position());
        }
        if (with.orderBy() != null || with.skip() != null || with.limit() != null) {
            throw new CypherCompileException(
                    "not supported in this version: ORDER BY / SKIP / LIMIT on a WITH", with.position());
        }
        if (finalReturn.orderBy() != null || finalReturn.skip() != null || finalReturn.limit() != null) {
            throw new CypherCompileException(
                    "not supported in this version: ORDER BY / SKIP / LIMIT on the RETURN after a WITH",
                    finalReturn.position());
        }
        // Every WITH item must be aliased, so each stage-one column is a clean name the second stage references.
        for (final AstReturnItem item : with.items()) {
            if (item.alias() == null) {
                throw new CypherCompileException(
                        "not supported in this version: every WITH item must be aliased, e.g. `WITH p.surname AS "
                        + "surname, count(c) AS crimes`", item.position());
            }
        }

        // --- stage one: the MATCH pattern, its pre-WHERE, then the WITH's projection/aggregation ---
        LogicalPlan plan = compilePattern(match.pattern()).plan();

        List<FieldComparison> fieldComparisons = List.of();
        List<CypherExists> existsPredicates = List.of();
        if (match.where() != null) {
            final WhereCompilation where = compileWhere(match.where().expr());
            fieldComparisons = where.fieldComparisons();
            existsPredicates = where.existsPredicates();
            if (where.literalPredicate() != null) {
                plan = new Filter(plan, where.literalPredicate(), null, match.where().position());
            }
        }

        final AstReturnClause withAsReturn = new AstReturnClause(
                false, false, with.items(), null, null, null, with.position());
        final List<ProjectField> withFields = buildProjectFields(withAsReturn);
        final CypherAggregation withAggregation = buildAggregation(withAsReturn, Set.of());
        plan = compileReturn(plan, withAsReturn, withFields);

        final List<String> stageColumns = withFields.stream().map(ProjectField::name).toList();
        final Set<String> scope = new HashSet<>(stageColumns);

        // --- stage two: HAVING (the WITH's WHERE) + the final RETURN, validated against the WITH's scope ---
        ExpressionOperator having = null;
        if (with.where() != null) {
            validateBooleanInScope(with.where().expr(), scope);
            having = compileBooleanExpr(with.where().expr());
        }
        for (final AstReturnItem item : finalReturn.items()) {
            validateExpressionInScope(item.expression(), scope);
        }
        final List<ProjectField> finalFields = buildProjectFields(finalReturn);

        final WithStage secondStage = new WithStage(stageColumns, having, finalFields, finalReturn.distinct());
        final TemporalContext temporalContext =
                match.temporal() == null ? null : resolveTemporal(match.temporal());
        return new CompiledCypherPlan(
                plan, temporalContext, false, withAggregation, null, false, fieldComparisons, existsPredicates,
                secondStage);
    }

    /** Validates that every reference in a {@code HAVING} boolean tree names a {@code WITH} column. */
    private static void validateBooleanInScope(final AstBooleanExpr expr, final Set<String> scope) {
        switch (expr) {
            case final AstOrExpr or -> or.operands().forEach(o -> validateBooleanInScope(o, scope));
            case final AstAndExpr and -> and.operands().forEach(o -> validateBooleanInScope(o, scope));
            case final AstNotExpr not -> validateBooleanInScope(not.operand(), scope);
            case final AstExistsPredicate exists -> throw new CypherCompileException(
                    "not supported in this version: EXISTS { ... } in a WITH's WHERE (HAVING)", exists.position());
            case final AstComparisonPredicate cmp -> {
                validateExpressionInScope(cmp.left(), scope);
                validateExpressionInScope(cmp.right(), scope);
            }
            case final AstInPredicate in -> {
                validateExpressionInScope(in.left(), scope);
                validateExpressionInScope(in.right(), scope);
            }
            case final AstIsNullPredicate isNull -> validateExpressionInScope(isNull.operand(), scope);
        }
    }

    /**
     * Validates that an expression (a {@code HAVING} operand or a final-{@code RETURN} item) references only
     * {@code WITH} columns - Cypher's WITH-scoping rule. A property access, aggregate, or {@code before}/
     * {@code after} is out of scope after a {@code WITH} (only the projected scalar columns survive).
     */
    private static void validateExpressionInScope(final AstExpression expr, final Set<String> scope) {
        switch (expr) {
            case final AstVariableExpr v -> {
                if (!scope.contains(v.name())) {
                    throw new CypherCompileException(
                            "'" + v.name() + "' is not a column produced by the WITH (in scope after WITH: "
                            + String.join(", ", scope) + ")", v.position());
                }
            }
            case final AstPropertyAccessExpr p -> throw new CypherCompileException(
                    "not supported in this version: after a WITH only its projected columns are in scope, so the "
                    + "property access '" + p.variable() + "." + p.property() + "' is out of scope - project it in "
                    + "the WITH (e.g. `WITH " + p.variable() + "." + p.property() + " AS x`)", p.position());
            case final AstAggregateExpr a -> throw new CypherCompileException(
                    "not supported in this version: an aggregate in the RETURN after a WITH (aggregate in the "
                    + "WITH instead)", a.position());
            case final AstDiffAccessorExpr d -> throw new CypherCompileException(
                    "not supported in this version: before()/after() in the RETURN after a WITH", d.position());
            case final AstLiteralExpr lit -> {
                if (lit.value() instanceof final AstFunctionValue f) {
                    f.arguments().forEach(a -> validateExpressionInScope(a, scope));
                }
            }
            case final AstArithmeticExpr arithmetic -> {
                validateExpressionInScope(arithmetic.left(), scope);
                validateExpressionInScope(arithmetic.right(), scope);
            }
            case final AstCaseExpr caseExpr -> {
                if (caseExpr.input() != null) {
                    validateExpressionInScope(caseExpr.input(), scope);
                }
                for (final AstCaseWhen when : caseExpr.whens()) {
                    if (when.testValue() != null) {
                        validateExpressionInScope(when.testValue(), scope);
                    }
                    if (when.testCondition() != null) {
                        validateBooleanInScope(when.testCondition(), scope);
                    }
                    validateExpressionInScope(when.result(), scope);
                }
                if (caseExpr.elseResult() != null) {
                    validateExpressionInScope(caseExpr.elseResult(), scope);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN GRAPH: the element-row output mode (.4,
    // )
    // ------------------------------------------------------------------------------------------------------

    /**
     * Compiles the {@code RETURN GRAPH} form: the pattern/{@code WHERE} lower exactly as for the scalar form
     * above, but the terminal {@code Project} carries the frozen {@link #ELEMENT_ROW_COLUMNS} schema (plus
     * {@link #CHANGE_KIND_COLUMN} under {@code DIFF}) instead of user {@code RETURN} items - synthesising a
     * {@code Project} (rather than a new plan-tree shape) is what lets {@code CypherCompiler.buildResultRequests}
     * advertise these columns with no changes of its own (it already turns any {@code Project}'s visible fields
     * into result columns). {@code GraphTraversalEngine}/{@code GraphElementExecutor} recognise this shape via
     * {@link CompiledCypherPlan#returnGraph}, not by inspecting the field names.
     *
     * <p>Var-length patterns are rejected here exactly as {@link #rejectVarLengthUnderDiff} rejects them for the
     * scalar {@code DIFF} form (v1 scope decision, mirroring that restriction): the per-element identity/label
     * tracking {@code GraphTraversalEngine}'s element collector needs is only wired for the fixed-length chain
     * shape. A {@code DIFF ... RETURN GRAPH} query is already covered by {@link #rejectVarLengthUnderDiff} above
     * (it ran before this method was reached); this call is what extends the same restriction to a <em>plain</em>
     * {@code RETURN GRAPH} (no {@code DIFF}).</p>
     */
    private CompiledCypherPlan compileReturnGraph(final AstCypherQuery query, final AstMatch match,
                                                  final LogicalPlan input,
                                                  final @Nullable TemporalContext temporalContext,
                                                  final @Nullable DiffContext diffContext) {
        LogicalPlan plan = input;
        if (diffContext == null) {
            rejectVarLengthUnderReturnGraph(match.pattern());
        }
        if (match.where() != null) {
            if (diffContext != null) {
                // Mirrors the scalar DIFF form's v1 restriction: changeKind/before/after filtering is a
                // post-classification step, deferred - a RETURN GRAPH's WHERE is still pattern-only.
                rejectDiffConstructsInDiffWhere(match.where().expr());
            }
            final ExpressionOperator predicate = compileBooleanExpr(match.where().expr());
            plan = new Filter(plan, predicate, null, match.where().position());
        }

        plan = new Project(plan, elementRowFields(query.returnClause().position(), diffContext != null),
                query.returnClause().position());

        // RETURN GRAPH now accepts an optional LIMIT (Cypher.g4). It bounds the nodes in the result (plus the edges
        // between them) - GraphTraversalEngine reads it off the plan's Limit node. A DIFF ... RETURN GRAPH LIMIT is
        // rejected: "which classified elements to keep" is not defined for a delta subgraph in this version.
        final var graphLimit = query.returnClause().limit();
        if (graphLimit != null) {
            if (diffContext != null) {
                throw new CypherCompileException(
                        "not supported in this version: LIMIT on a DIFF ... RETURN GRAPH", graphLimit.position());
            }
            plan = new Limit(plan, List.of(graphLimit.value()), graphLimit.position());
        }

        return new CompiledCypherPlan(
                plan, temporalContext, false, null, diffContext, true, List.of(), List.of(), null);
    }

    private void rejectVarLengthUnderReturnGraph(final AstPathPattern pattern) {
        for (final AstPatternHop hop : pattern.hops()) {
            if (hop.edge().varLength() != null) {
                throw new CypherCompileException(
                        "not supported in this version: RETURN GRAPH over a variable-length pattern - use a "
                        + "fixed-length pattern", hop.edge().position());
            }
        }
    }

    /**
     * Builds the fixed {@code RETURN GRAPH} element-row {@link ProjectField}s in {@link #ELEMENT_ROW_COLUMNS}
     * order (plus {@link #CHANGE_KIND_COLUMN} under {@code DIFF}). Each field's {@code rawExpression} is a
     * placeholder {@code ${name}} text purely so it renders sensibly in debug/explain output - {@code
     * GraphElementExecutor} builds these rows directly from the matched elements, never through {@code
     * GraphTraversalEngine}'s ordinary {@code ${...}} row-map evaluation.
     */
    private static List<ProjectField> elementRowFields(final AstPosition position, final boolean underDiff) {
        final List<String> names = new ArrayList<>(ELEMENT_ROW_COLUMNS);
        if (underDiff) {
            names.add(CHANGE_KIND_COLUMN);
        }
        final List<ProjectField> fields = new ArrayList<>(names.size());
        for (final String name : names) {
            fields.add(new ProjectField(name, "${" + name + "}", true, null, position));
        }
        return fields;
    }

    // ------------------------------------------------------------------------------------------------------
    // pattern: NodeScan [+ Expand chain]
    // ------------------------------------------------------------------------------------------------------

    private record PatternResult(LogicalPlan plan, String terminalVariable) {
    }

    private PatternResult compilePattern(final AstPathPattern pattern) {
        final NodeScan anchor = compileNodeScan(pattern.anchor());

        if (pattern.hops().size() == 1 && pattern.hops().getFirst().edge().varLength() != null) {
            final VarLengthExpand vle = compileVarLengthExpand(anchor, pattern.hops().getFirst());
            return new PatternResult(vle, vle.targetVariable());
        }

        LogicalPlan plan = anchor;
        String terminalVariable = anchor.variable();
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
                    edge.variable(),
                    targetVariable,
                    hop.node().labels(),
                    compilePropertyPredicate(hop.node().properties()),
                    hop.position());
            terminalVariable = targetVariable;
        }
        return new PatternResult(plan, terminalVariable);
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

    private record OptionalFold(LogicalPlan plan, Set<String> optionalVariables) {
    }

    /**
     * Folds an {@code OPTIONAL MATCH}'s single hop onto the mandatory plan as an {@code optional} {@link Expand}.
     * v1 restrictions, each a fail-loud rejection: the optional pattern must start from a bare variable that is
     * the mandatory pattern's terminal (frontier) variable, extend it by exactly one fixed-length hop, and carry
     * no {@code WHERE}/temporal clause of its own.
     *
     * <p><b>Null status:</b> no parameter is nullable; never returns null.</p>
     */
    private OptionalFold foldOptionalMatch(final LogicalPlan mandatoryPlan, final String mandatoryTerminal,
                                           final AstMatch optionalMatch) {
        if (optionalMatch.where() != null) {
            throw new CypherCompileException(
                    "not supported in this version: a WHERE on an OPTIONAL MATCH (constraining the optional "
                    + "pattern) is a later phase", optionalMatch.where().position());
        }
        if (optionalMatch.temporal() != null) {
            throw new CypherCompileException(
                    "not supported in this version: a temporal clause on an OPTIONAL MATCH",
                    optionalMatch.position());
        }
        final AstPathPattern pattern = optionalMatch.pattern();
        final AstNodePattern anchorNode = pattern.anchor();
        if (anchorNode.variable() == null
                || !anchorNode.labels().isEmpty()
                || !anchorNode.properties().isEmpty()) {
            throw new CypherCompileException(
                    "not supported in this version: an OPTIONAL MATCH must start from a bare variable already "
                    + "bound by the preceding MATCH, e.g. OPTIONAL MATCH (p)-[:R]->(c)", anchorNode.position());
        }
        if (!anchorNode.variable().equals(mandatoryTerminal)) {
            throw new CypherCompileException(
                    "not supported in this version: an OPTIONAL MATCH must extend the preceding MATCH's final "
                    + "variable ('" + mandatoryTerminal + "'), found '" + anchorNode.variable() + "'",
                    anchorNode.position());
        }
        if (pattern.hops().size() != 1) {
            throw new CypherCompileException(
                    "not supported in this version: an OPTIONAL MATCH supports exactly one hop, e.g. "
                    + "OPTIONAL MATCH (p)-[:R]->(c)", pattern.position());
        }
        final AstPatternHop hop = pattern.hops().getFirst();
        if (hop.edge().varLength() != null) {
            throw new CypherCompileException(
                    "not supported in this version: a variable-length OPTIONAL MATCH", hop.edge().position());
        }
        final String targetVariable = hop.node().variable() != null
                ? hop.node().variable()
                : nextAnonymousVariable();
        final LogicalPlan plan = new Expand(
                mandatoryPlan,
                hop.edge().type(),
                toDirection(hop.edge().direction()),
                hop.edge().variable(),
                targetVariable,
                hop.node().labels(),
                compilePropertyPredicate(hop.node().properties()),
                true,
                hop.position());
        final Set<String> optionalVariables = new HashSet<>();
        optionalVariables.add(targetVariable);
        if (hop.edge().variable() != null) {
            optionalVariables.add(hop.edge().variable());
        }
        return new OptionalFold(plan, optionalVariables);
    }

    /**
     * Lowers a node pattern's inline {@code {key: value, ...}} property map to an equality predicate tree, shared
     * between an anchor's {@link NodeScan#propertyAnchor} and a hop target's
     * {@link Expand#targetPropertyPredicate} (Task P3.1) - both are the same AND-of-equalities shape, just
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
        } else if (expr instanceof final AstInPredicate in) {
            return ExpressionOperator.builder().addTerm(compileInTerm(in)).build();
        } else if (expr instanceof final AstIsNullPredicate isNull) {
            return ExpressionOperator.builder().addTerm(compileIsNullTerm(isNull)).build();
        } else if (expr instanceof AstExistsPredicate) {
            throw new CypherCompileException(
                    "not supported in this version: EXISTS { ... } is only supported as a top-level WHERE conjunct "
                    + "of an ordinary (non-DIFF, non-RETURN GRAPH) query - not nested inside OR, and not combined "
                    + "with a non-EXISTS NOT", expr.position());
        }
        throw new CypherCompileException("Unrecognised WHERE expression", expr.position());
    }

    private ExpressionItem compileBooleanExprAsItem(final AstBooleanExpr expr) {
        if (expr instanceof final AstComparisonPredicate predicate) {
            return compileComparisonTerm(predicate);
        } else if (expr instanceof final AstInPredicate in) {
            return compileInTerm(in);
        } else if (expr instanceof final AstIsNullPredicate isNull) {
            return compileIsNullTerm(isNull);
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
                    "not supported in this version: comparing two field references is only supported as a "
                    + "top-level conjunct of a WHERE (ANDed), not nested inside OR/NOT or with an aggregate "
                    + "operand", predicate.right().position());
        }
        final String value = renderLiteralValue(literal.value());
        // Regex safety: cap the pattern length at compile time. This is a coarse guard against pathological
        // (catastrophic-backtracking / oversized) patterns - it deliberately does NOT attempt static ReDoS
        // detection, only bounds the input the engine's StringRegex will compile at query time.
        if (predicate.op() == AstComparisonOp.REGEX && value.length() > MAX_REGEX_PATTERN_LENGTH) {
            throw new CypherCompileException(
                    "the =~ regular expression is too long (max " + MAX_REGEX_PATTERN_LENGTH + " characters)",
                    predicate.right().position());
        }
        return ExpressionTerm.builder()
                .field(field)
                .condition(toCondition(predicate.op()))
                .value(value)
                .build();
    }

    /**
     * Upper bound on the length of a {@code =~} regular-expression literal, enforced at compile time. A coarse
     * safety cap, not a ReDoS analysis - see {@link #compileComparisonTerm}.
     */
    private static final int MAX_REGEX_PATTERN_LENGTH = 1000;

    /**
     * Lowers {@code left IN [a, b, ...]} to a single {@link Condition#IN} term. The left side must resolve to a
     * field via {@link #fieldNameOf}; the right side must be a literal list ({@link AstListValue}) of scalar
     * literals. The element literals are joined with {@code ", "} - the shared comma delimiter that
     * {@code ExpressionPredicateFactory.StringIn} parses (its comma-split + trim was established in the Phase 0
     * fix). An empty list renders to an empty value, which {@code StringIn} treats as "matches nothing".
     *
     * <p><b>Null status:</b> {@code predicate} non-null; never returns null.
     */
    private ExpressionTerm compileInTerm(final AstInPredicate predicate) {
        final String field = fieldNameOf(predicate.left());
        if (field == null) {
            throw new CypherCompileException(
                    "not in PoC subset: the left side of IN must be a property access or variable reference",
                    predicate.left().position());
        }
        if (!(predicate.right() instanceof final AstLiteralExpr literal)
                || !(literal.value() instanceof final AstListValue list)) {
            throw new CypherCompileException(
                    "not in PoC subset: the right side of IN must be a literal list, e.g. ['a', 'b']",
                    predicate.right().position());
        }
        // renderLiteralValue rejects any non-scalar element (a nested list or function), so `IN [['a']]` fails
        // loud here rather than producing a wrong term. Join with ", " to match StringIn's comma delimiter.
        final String value = list.elements().stream()
                .map(CypherToLogicalPlan::renderLiteralValue)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return ExpressionTerm.builder()
                .field(field)
                .condition(Condition.IN)
                .value(value)
                .build();
    }

    /**
     * Lowers {@code operand IS [NOT] NULL} to a single {@link Condition#IS_NULL}/{@link Condition#IS_NOT_NULL}
     * term (no value). The operand must be a property access - a bare pattern variable ({@code v IS NULL}) is
     * rejected here, since a whole matched node/edge has no existence representation in this subset yet (that is a
     * later, OPTIONAL MATCH, concern).
     *
     * <p><b>Null status:</b> {@code predicate} non-null; never returns null.
     */
    private ExpressionTerm compileIsNullTerm(final AstIsNullPredicate predicate) {
        if (!(predicate.operand() instanceof AstPropertyAccessExpr)) {
            throw new CypherCompileException(
                    "not in PoC subset: IS NULL is only supported on a property, e.g. `a.name IS NULL`",
                    predicate.operand().position());
        }
        final String field = fieldNameOf(predicate.operand());
        return ExpressionTerm.builder()
                .field(field)
                .condition(predicate.negated() ? Condition.IS_NOT_NULL : Condition.IS_NULL)
                .build();
    }

    /**
     * The two products of lowering a {@code WHERE} clause: the literal {@link ExpressionOperator} predicate tree
     * (field-vs-literal / {@code IN} / {@code IS NULL} terms, evaluated by the shared
     * {@code ExpressionPredicateFactory}), and a separate list of field-vs-field {@link FieldComparison}s the
     * shared IR cannot represent (see {@link FieldComparison}). {@code literalPredicate} is null when every
     * top-level conjunct was a field-vs-field comparison.
     */
    private record WhereCompilation(@Nullable ExpressionOperator literalPredicate,
                                    List<FieldComparison> fieldComparisons,
                                    List<CypherExists> existsPredicates) {
    }

    /**
     * Splits a {@code WHERE} expression into its literal predicate tree and its top-level field-vs-field
     * comparisons. A field-vs-field comparison ({@code a.x > b.y}) is only extracted when it is a top-level
     * conjunct (a direct operand of the root {@code AND}, or the whole {@code WHERE}); one nested inside
     * {@code OR}/{@code NOT} (or inside parentheses) instead reaches {@link #compileComparisonTerm} and is
     * rejected there - the v1 scope line.
     *
     * <p><b>Null status:</b> {@code expr} non-null; never returns null (the {@link WhereCompilation} may carry a
     * null {@code literalPredicate}).</p>
     */
    private WhereCompilation compileWhere(final AstBooleanExpr expr) {
        final List<FieldComparison> fieldComparisons = new ArrayList<>();
        final List<CypherExists> existsPredicates = new ArrayList<>();
        final ExpressionOperator literal = splitGraphLocalPredicates(expr, fieldComparisons, existsPredicates);
        return new WhereCompilation(literal, fieldComparisons, existsPredicates);
    }

    /**
     * Splits a {@code WHERE} expression into its literal predicate tree, its top-level field-vs-field comparisons,
     * and its top-level {@code [NOT] EXISTS { ... }} predicates. A graph-local predicate (field-vs-field or EXISTS)
     * is only extracted when it is a top-level conjunct (a direct operand of the root {@code AND}, or the whole
     * {@code WHERE}); one nested inside {@code OR}/{@code NOT} (of a non-EXISTS) reaches {@link #compileBooleanExpr}
     * and is rejected there - the v1 scope line.
     */
    private @Nullable ExpressionOperator splitGraphLocalPredicates(final AstBooleanExpr expr,
                                                                   final List<FieldComparison> fieldOut,
                                                                   final List<CypherExists> existsOut) {
        if (expr instanceof final AstComparisonPredicate cmp && isFieldVsField(cmp)) {
            fieldOut.add(toFieldComparison(cmp));
            return null;
        }
        if (tryExtractExists(expr, existsOut)) {
            return null;
        }
        if (expr instanceof final AstAndExpr and) {
            final List<ExpressionItem> literalChildren = new ArrayList<>();
            for (final AstBooleanExpr operand : and.operands()) {
                if (operand instanceof final AstComparisonPredicate cmp && isFieldVsField(cmp)) {
                    fieldOut.add(toFieldComparison(cmp));
                } else if (tryExtractExists(operand, existsOut)) {
                    // extracted as a graph-local existence predicate
                } else {
                    literalChildren.add(compileBooleanExprAsItem(operand));
                }
            }
            if (literalChildren.isEmpty()) {
                return null;
            }
            return ExpressionOperator.builder().op(Op.AND).children(literalChildren).build();
        }
        // OR / NOT / field-vs-literal comparison / IN / IS NULL: compile normally. A field-vs-field comparison or a
        // stray EXISTS nested inside OR/NOT reaches compileBooleanExpr/compileComparisonTerm, which rejects it.
        return compileBooleanExpr(expr);
    }

    /**
     * Extracts a top-level {@code EXISTS { ... }} (or {@code NOT EXISTS { ... }}) into {@code out}, returning
     * {@code true} when {@code expr} was such a predicate. Any other shape returns {@code false} (the caller
     * handles it).
     */
    private boolean tryExtractExists(final AstBooleanExpr expr, final List<CypherExists> out) {
        if (expr instanceof final AstExistsPredicate exists) {
            out.add(compileExists(exists, false));
            return true;
        }
        if (expr instanceof final AstNotExpr not && not.operand() instanceof final AstExistsPredicate exists) {
            out.add(compileExists(exists, true));
            return true;
        }
        return false;
    }

    /**
     * Compiles a correlated {@code EXISTS { (x)-[:TYPE]->(y) }} to a {@link CypherExists}. v1 restrictions (each a
     * fail-loud rejection): the inner anchor is a bare variable (bound by the outer MATCH - a labels/property
     * constraint on it, or an anonymous anchor, is rejected), extended by exactly one typed fixed-length hop.
     */
    private CypherExists compileExists(final AstExistsPredicate exists, final boolean negated) {
        final AstPathPattern pattern = exists.pattern();
        final AstNodePattern anchor = pattern.anchor();
        if (anchor.variable() == null || !anchor.labels().isEmpty() || !anchor.properties().isEmpty()) {
            throw new CypherCompileException(
                    "not supported in this version: EXISTS { ... } must start from a bare variable already bound "
                    + "by the MATCH, e.g. EXISTS { (a)-[:R]->(b) }", anchor.position());
        }
        if (pattern.hops().size() != 1) {
            throw new CypherCompileException(
                    "not supported in this version: EXISTS { ... } supports exactly one hop, e.g. "
                    + "EXISTS { (a)-[:R]->(b) }", pattern.position());
        }
        final AstPatternHop hop = pattern.hops().getFirst();
        if (hop.edge().varLength() != null) {
            throw new CypherCompileException(
                    "not supported in this version: a variable-length hop inside EXISTS { ... }",
                    hop.edge().position());
        }
        if (hop.edge().type() == null) {
            throw new CypherCompileException(
                    "not supported in this version: EXISTS { ... } requires a typed edge, e.g. "
                    + "EXISTS { (a)-[:R]->(b) }", hop.edge().position());
        }
        return new CypherExists(
                anchor.variable(),
                hop.edge().type(),
                toDirection(hop.edge().direction()),
                hop.node().labels(),
                compilePropertyPredicate(hop.node().properties()),
                negated);
    }

    /** A comparison of two property accesses (e.g. {@code a.x > b.y}); a literal or bare-variable operand is not. */
    private static boolean isFieldVsField(final AstComparisonPredicate cmp) {
        return cmp.left() instanceof AstPropertyAccessExpr && cmp.right() instanceof AstPropertyAccessExpr;
    }

    private static FieldComparison toFieldComparison(final AstComparisonPredicate cmp) {
        final AstComparisonOp op = cmp.op();
        if (op != AstComparisonOp.EQ && op != AstComparisonOp.NEQ && op != AstComparisonOp.LT
                && op != AstComparisonOp.LE && op != AstComparisonOp.GT && op != AstComparisonOp.GE) {
            throw new CypherCompileException(
                    "not supported in this version: comparing two fields is only supported with =, <>, <, <=, >, "
                    + ">= (not string operators)", cmp.position());
        }
        return new FieldComparison(fieldNameOf(cmp.left()), op, fieldNameOf(cmp.right()));
    }

    private static String fieldNameOf(final AstExpression expression) {
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return propertyAccess.variable() + "." + propertyAccess.property();
        } else if (expression instanceof final AstVariableExpr variable) {
            return variable.name();
        }
        return null;
    }

    private static Condition toCondition(final AstComparisonOp op) {
        return switch (op) {
            case EQ -> Condition.EQUALS;
            case NEQ -> Condition.NOT_EQUALS;
            case LT -> Condition.LESS_THAN;
            case LE -> Condition.LESS_THAN_OR_EQUAL_TO;
            case GT -> Condition.GREATER_THAN;
            case GE -> Condition.GREATER_THAN_OR_EQUAL_TO;
            case STARTS_WITH -> Condition.STARTS_WITH;
            case CONTAINS -> Condition.CONTAINS;
            case ENDS_WITH -> Condition.ENDS_WITH;
            case REGEX -> Condition.MATCHES_REGEX;
        };
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN / ORDER BY / SKIP / LIMIT
    // ------------------------------------------------------------------------------------------------------

    /**
     * <b>Preconditions:</b> {@code fields} is {@code buildProjectFields(returnClause)}'s result for the same
     * {@code returnClause} - callers that also need the raw field list (e.g. {@link #compile} for aggregation
     * validation) build it once and pass it here rather than this method recomputing it, since
     * {@link #buildProjectFields} is otherwise a pure function of {@code returnClause} alone.
     * <b>Null status:</b> no parameter is nullable; never returns null.
     */
    private LogicalPlan compileReturn(final LogicalPlan input, final AstReturnClause returnClause,
                                      final List<ProjectField> fields) {
        LogicalPlan plan = new Project(input, fields, returnClause.position());

        // RETURN DISTINCT is carried on the CompiledCypherPlan (see compile), not lowered to a plan node, since
        // the sealed shared IR has no Distinct node; the graph executor de-duplicates the projected rows.
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

    /**
     * Builds one {@link ProjectField} per {@code RETURN} item, in source order - shared between
     * {@link #compileReturn} (which wraps them in a {@link Project}) and {@link #compile} (which needs the same
     * list, before it is wrapped, to validate an aggregated query's {@code ORDER BY} - see
     * {@link #validateOrderByAgainstAggregation}).
     * <b>Null status:</b> {@code returnClause} is never null; the returned list and its elements are never null.
     */
    private List<ProjectField> buildProjectFields(final AstReturnClause returnClause) {
        final List<ProjectField> fields = new ArrayList<>(returnClause.items().size());
        for (final AstReturnItem item : returnClause.items()) {
            fields.add(compileReturnItem(item));
        }
        return fields;
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
        } else if (expression instanceof final AstDiffAccessorExpr accessor) {
            // A ${}-free, human-readable default name that also serves as the FieldIndex/column key, e.g.
            // "before(a.balance)" / "after(a.balance)" (mirrors defaultAggregateName's rationale).
            final String side = accessor.side() == AstDiffSide.BEFORE ? "before" : "after";
            return side + "(" + accessor.target().variable() + "." + accessor.target().property() + ")";
        } else if (expression instanceof final AstAggregateExpr aggregate) {
            // Code-review fix: the fallback below (renderExpression) would otherwise produce a nested "${...}"
            // reference (e.g. "count(${a.balance})") as the column NAME - unusable as a FieldIndex/column
            // identifier once CypherCompiler wraps it as "${" + name + "}" (see that class's buildResultRequests).
            // An aggregate's default name is instead the plain, ${}-free "function(argument)" text.
            return defaultAggregateName(aggregate);
        }
        // A literal with no alias - the raw expression text is as good a default name as any.
        return renderExpression(expression);
    }

    /**
     * Renders an unaliased aggregate's default output column name, e.g. {@code "count(*)"},
     * {@code "count(a.balance)"}, {@code "sum(a.balance)"} - deliberately {@code ${...}}-free (unlike
     * {@link #renderExpression}'s aggregate rendering, kept for explain/debug text only), since this becomes a
     * real {@link ProjectField#name} / {@code FieldIndex} key (see {@link #defaultColumnName}).
     */
    private static String defaultAggregateName(final AstAggregateExpr aggregate) {
        final String fn = aggregate.function().name().toLowerCase(Locale.ROOT);
        // Render DISTINCT so count(distinct a.x) and count(a.x) get distinct column keys / FieldIndex entries.
        final String distinct = aggregate.distinct() ? "distinct " : "";
        return aggregate.star()
                ? fn + "(*)"
                : fn + "(" + distinct + defaultColumnName(aggregate.argument()) + ")";
    }

    // ------------------------------------------------------------------------------------------------------
    // aggregation: implicit GROUP BY inference over a RETURN mixing an aggregate with other items
    // ------------------------------------------------------------------------------------------------------

    /**
     * <b>Preconditions:</b> {@code returnClause} is not null.
     * <b>Postconditions:</b> returns {@code null} if no item in {@code returnClause} is an aggregate call
     * (the ordinary, non-aggregated execution path applies); otherwise returns a {@link CypherAggregation} with
     * exactly one {@link OutputColumn} per {@code returnClause} item, in the same order as
     * {@link #buildProjectFields} builds its {@link ProjectField}s for the same clause (see
     * {@link CypherAggregation}'s Javadoc for why this alignment matters).
     * <b>Null status:</b> the parameter is never null; the return value is nullable.
     *
     * @throws CypherCompileException if a non-aggregate item is not a property access (see
     *                                 {@link #compileOutputColumn}), or if an aggregate call uses an argument
     *                                 shape this PoC subset does not support (see
     *                                 {@link #compileAggregateColumn}).
     */
    private @Nullable CypherAggregation buildAggregation(final AstReturnClause returnClause,
                                                         final Set<String> optionalVariables) {
        Objects.requireNonNull(returnClause, "returnClause");
        boolean anyAggregate = false;
        for (final AstReturnItem item : returnClause.items()) {
            if (item.expression() instanceof AstAggregateExpr) {
                anyAggregate = true;
                break;
            }
        }
        if (!anyAggregate) {
            return null;
        }
        final List<OutputColumn> columns = new ArrayList<>(returnClause.items().size());
        for (final AstReturnItem item : returnClause.items()) {
            columns.add(compileOutputColumn(item, optionalVariables));
        }
        return new CypherAggregation(columns);
    }

    /**
     * Lowers one {@code RETURN} item to an {@link OutputColumn} once the clause is known to mix an aggregate with
     * other items - an {@link AstAggregateExpr} becomes an {@link AggregateColumn} (see
     * {@link #compileAggregateColumn}); anything else must be a property access (an implicit {@code GROUP BY}
     * key, Cypher's rule for every non-aggregate item in this shape), since a bare pattern variable has no single
     * value to group by (the same "whole matched node/edge has no single value" rule
     * {@code GraphTraversalEngine.evaluate}'s bare-variable rejection already enforces for the non-aggregated
     * path).
     */
    private OutputColumn compileOutputColumn(final AstReturnItem item, final Set<String> optionalVariables) {
        final AstExpression expression = item.expression();
        if (expression instanceof final AstAggregateExpr aggregate) {
            return compileAggregateColumn(aggregate, optionalVariables);
        }
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return new GroupKeyColumn(propertyAccess.variable() + "." + propertyAccess.property());
        }
        throw new CypherCompileException(
                "not in PoC subset: when RETURN mixes an aggregate with other items, each non-aggregate item must "
                + "be a property access (e.g. 'a.id') to serve as an implicit GROUP BY key - a bare pattern "
                + "variable or literal has no single value to group by", item.position());
    }

    /**
     * Lowers one {@link AstAggregateExpr} to an {@link AggregateColumn}, accepting exactly the argument shapes
     * {@link AggregateColumn}'s Javadoc documents: {@code count(*)} (star; rejected for every other function -
     * {@code sum(*)}/{@code avg(*)}/{@code min(*)}/{@code max(*)} are not meaningful), a property access (any
     * function), or a bare pattern variable (count only - equivalent to {@code count(*)} since this subset has no
     * {@code OPTIONAL MATCH} to make the variable ever unbound; {@code sum}/{@code avg}/{@code min}/{@code max}
     * over a bare variable are rejected, since a whole node/edge has no single value to reduce).
     */
    private OutputColumn compileAggregateColumn(final AstAggregateExpr aggregate,
                                                final Set<String> optionalVariables) {
        final AstAggregateFunction function = aggregate.function();
        final String functionName = function.name().toLowerCase(Locale.ROOT);
        final boolean distinct = aggregate.distinct();
        // DISTINCT is supported on count(DISTINCT <property>) and collect(DISTINCT <property>). Reject it loudly
        // on sum/avg/min/max (rather than silently ignoring it and returning a wrong result) - fail loud, never
        // wrong.
        if (distinct && function != AstAggregateFunction.COUNT && function != AstAggregateFunction.COLLECT) {
            throw new CypherCompileException(
                    "not supported in this version: DISTINCT is only supported on count(...) and collect(...), not "
                    + functionName + "(...)", aggregate.position());
        }
        if (aggregate.star()) {
            if (function != AstAggregateFunction.COUNT) {
                throw new CypherCompileException(
                        "not in PoC subset: " + functionName + "(*) is not supported - only count(*) is "
                        + "meaningful over a whole row; " + functionName + " needs a property to aggregate, e.g. "
                        + functionName + "(a.balance)", aggregate.position());
            }
            if (distinct) {
                throw new CypherCompileException(
                        "not supported in this version: count(DISTINCT *) is not meaningful - use "
                        + "count(DISTINCT a.property)", aggregate.position());
            }
            return new AggregateColumn(function, null, true, false, false);
        }

        final AstExpression argument = aggregate.argument();
        if (argument instanceof final AstPropertyAccessExpr propertyAccess) {
            return new AggregateColumn(
                    function, propertyAccess.variable() + "." + propertyAccess.property(), false, false, distinct);
        }
        if (argument instanceof final AstVariableExpr variable) {
            if (function != AstAggregateFunction.COUNT) {
                throw new CypherCompileException(
                        "not in PoC subset: " + functionName + "(" + variable.name() + ") would aggregate a "
                        + "whole matched node/edge, which has no single value - aggregate one of its properties "
                        + "instead, e.g. " + functionName + "(" + variable.name() + ".someProperty)",
                        aggregate.position());
            }
            if (distinct) {
                throw new CypherCompileException(
                        "not supported in this version: count(DISTINCT " + variable.name() + ") over a whole "
                        + "matched node/edge is not supported - use count(DISTINCT " + variable.name()
                        + ".someProperty)", aggregate.position());
            }
            if (optionalVariables.contains(variable.name())) {
                // count(<optional variable>): count the rows where the OPTIONAL MATCH actually bound it, via its
                // bound-marker key (the null-padded rows emitted for a non-match lack this key). Without this,
                // count(c) would return 1 for a row with no optional match instead of 0. See OptionalMatchSupport.
                return new AggregateColumn(
                        function, OptionalMatchSupport.boundKey(variable.name()), false, false, false);
            }
            return new AggregateColumn(function, null, false, true, false);
        }
        throw new CypherCompileException(
                "not in PoC subset: an aggregate argument must be a property access (e.g. 'a.balance') or, for "
                + "count only, a bare pattern variable", argument.position());
    }

    /**
     * Rejects an {@code ORDER BY} item that does not reference any column the (aggregated) {@code RETURN}
     * actually produces - once rows are grouped/reduced there is no traversal row map left at execution time
     * (only the final, one-row-per-group output tuple), so, unlike the non-aggregated path, an {@code ORDER BY}
     * item here must name a returned column by its property access or {@code AS} alias, not an arbitrary bound
     * variable/property. Reuses {@link #toQualifiedField}'s {@code (alias, field)} split and the same
     * {@code alias == null ? field : alias + "." + field} reconstruction the graph executor's row lookups use, so
     * a match here is exactly a match against a {@link ProjectField#name}.
     *
     * @param fields {@link #buildProjectFields}'s result for the same {@code RETURN} clause {@code orderBy}
     *               belongs to.
     * @throws CypherCompileException if any {@code orderBy} item does not name a column in {@code fields}.
     */
    private void validateOrderByAgainstAggregation(final AstOrderBy orderBy, final List<ProjectField> fields) {
        final Set<String> outputNames = new HashSet<>();
        for (final ProjectField field : fields) {
            outputNames.add(field.name());
        }
        for (final AstOrderItem item : orderBy.items()) {
            final QualifiedField qualified = toQualifiedField(item.expression(), item.position());
            final String reconstructed = qualified.alias() == null
                    ? qualified.field()
                    : qualified.alias() + "." + qualified.field();
            if (!outputNames.contains(reconstructed)) {
                throw new CypherCompileException(
                        "not in PoC subset: ORDER BY '" + reconstructed + "' is not a returned column - once "
                        + "RETURN aggregates rows, ORDER BY may only reference a RETURN column by its property "
                        + "access or AS alias, not an arbitrary bound variable/property", item.position());
            }
        }
    }

    private List<SortKey> compileOrderBy(final AstOrderBy orderBy) {
        final List<SortKey> keys = new ArrayList<>(orderBy.items().size());
        for (final AstOrderItem item : orderBy.items()) {
            keys.add(new SortKey(toQualifiedField(item.expression(), item.position()), item.descending()));
        }
        return keys;
    }

    /**
     * Splits an {@code ORDER BY} item directly into {@link QualifiedField}'s {@code (alias, field)} shape (rather
     * than into a merged {@code "variable.property"} string with {@code alias} left {@code null}), matching the
     * split every other part of the planner relies on ({@link stroom.query.planner.bind.Binder} always resolves a
     * qualified reference into a real {@code (alias, field)} pair). A property access's pattern variable IS the
     * {@code Scan} alias {@link QualifiedField} expects; a bare variable reference has no separate field component
     * of its own, so it is carried as an unqualified field name ({@code alias} null). The graph executor rebuilds
     * the row-map key from this pair as {@code alias == null ? field : alias + "." + field}.
     */
    private static QualifiedField toQualifiedField(final AstExpression expression, final AstPosition position) {
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return new QualifiedField(propertyAccess.variable(), propertyAccess.property());
        }
        if (expression instanceof final AstVariableExpr variable) {
            return new QualifiedField(null, variable.name());
        }
        throw new CypherCompileException(
                "not in PoC subset: an ORDER BY item must be a property access or variable reference", position);
    }


    // ------------------------------------------------------------------------------------------------------
    // expression / value rendering (StroomQL-style text for ProjectField.rawExpression)
    // ------------------------------------------------------------------------------------------------------

    private static String arithmeticSymbol(final AstArithmeticOp op) {
        return switch (op) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
            case MODULO -> "%";
            case POWER -> "^";
        };
    }

    private static String renderExpression(final AstExpression expression) {
        if (expression instanceof final AstPropertyAccessExpr propertyAccess) {
            return "${" + propertyAccess.variable() + "." + propertyAccess.property() + "}";
        } else if (expression instanceof final AstVariableExpr variable) {
            return "${" + variable.name() + "}";
        } else if (expression instanceof final AstLiteralExpr literal) {
            return renderValueAsExpression(literal.value());
        } else if (expression instanceof final AstAggregateExpr aggregate) {
            // Code-review fix: toLowerCase with no Locale uses the platform default, which corrupts fixed
            // enum-name text like "MIN" under a Turkish-variant locale (dotless-i folding). These five function
            // names are ASCII-only literals, not user-locale-sensitive text, so Locale.ROOT is the correct fold.
            final String fn = aggregate.function().name().toLowerCase(Locale.ROOT);
            final String distinct = aggregate.distinct() ? "distinct " : "";
            return aggregate.star() ? fn + "()" : fn + "(" + distinct + renderExpression(aggregate.argument()) + ")";
        } else if (expression instanceof final AstDiffAccessorExpr accessor) {
            // before(a.p)/after(a.p) project the property's value from the baseline (t1) / comparison (t2)
            // snapshot. DiffExecutor populates the delta-table row with a "before.<var>.<prop>" /
            // "after.<var>.<prop>" key per accessor, so this ${...} reference resolves against that side's value
            // (.3).
            return "${" + diffAccessorRowKey(accessor) + "}";
        } else if (expression instanceof final AstArithmeticExpr arithmetic) {
            // Stroom's expression engine evaluates infix + - * / ^; render parenthesised to preserve the AST's
            // precedence structure (Phase 10 - runtime is free).
            return "(" + renderExpression(arithmetic.left()) + " " + arithmeticSymbol(arithmetic.op()) + " "
                   + renderExpression(arithmetic.right()) + ")";
        } else if (expression instanceof final AstCaseExpr caseExpr) {
            return renderCaseExpression(caseExpr);
        }
        throw new CypherCompileException("Unrecognised expression", expression.position());
    }

    /**
     * Lowers a CASE value expression to Stroom's expression engine:
     * <ul>
     *   <li><b>simple</b> ({@code CASE input WHEN t THEN r ... [ELSE e] END}) -&gt; Stroom's exact-match switch
     *       {@code case(input, t1, r1, ..., tN, rN, otherwise)}.</li>
     *   <li><b>searched</b> ({@code CASE WHEN cond THEN r ... [ELSE e] END}) -&gt; right-nested
     *       {@code if(cond1, r1, if(cond2, r2, ..., otherwise))}.</li>
     * </ul>
     * A missing {@code ELSE} becomes {@code null} (openCypher yields null for an unmatched CASE).
     */
    private static String renderCaseExpression(final AstCaseExpr caseExpr) {
        final String otherwise = caseExpr.elseResult() == null
                ? "null()"
                : renderExpression(caseExpr.elseResult());

        if (caseExpr.input() != null) {
            final StringBuilder sb = new StringBuilder("case(");
            sb.append(renderExpression(caseExpr.input()));
            for (final AstCaseWhen when : caseExpr.whens()) {
                sb.append(", ").append(renderExpression(when.testValue()))
                        .append(", ").append(renderExpression(when.result()));
            }
            return sb.append(", ").append(otherwise).append(")").toString();
        }

        // Searched form: fold from the last arm inwards so arm 1 is the outermost if.
        String acc = otherwise;
        final List<AstCaseWhen> whens = caseExpr.whens();
        for (int i = whens.size() - 1; i >= 0; i--) {
            final AstCaseWhen when = whens.get(i);
            acc = "if(" + renderBooleanCondition(when.testCondition()) + ", "
                  + renderExpression(when.result()) + ", " + acc + ")";
        }
        return acc;
    }

    /**
     * Renders a boolean predicate (a searched-CASE {@code WHEN} condition) to a Stroom expression-engine string that
     * evaluates to a boolean - reusing Stroom's {@code and}/{@code or}/{@code not}/{@code isNull} functions and its
     * infix comparison operators. This is the string-rendering counterpart of the WHERE lowering (which instead
     * targets {@code ExpressionTerm} conditions); the two paths are separate because a CASE condition must live
     * inside a value expression, not a filter.
     */
    private static String renderBooleanCondition(final AstBooleanExpr condition) {
        return switch (condition) {
            case final AstAndExpr and -> renderBooleanFunction("and", and.operands());
            case final AstOrExpr or -> renderBooleanFunction("or", or.operands());
            case final AstNotExpr not -> "not(" + renderBooleanCondition(not.operand()) + ")";
            case final AstIsNullPredicate isNull -> {
                final String test = "isNull(" + renderExpression(isNull.operand()) + ")";
                yield isNull.negated() ? "not(" + test + ")" : test;
            }
            case final AstComparisonPredicate cmp -> renderComparisonCondition(cmp);
            case final AstInPredicate in -> throw new CypherCompileException(
                    "not supported in this version: an IN predicate inside a CASE WHEN condition (use nested "
                    + "comparisons, or filter in WHERE)", in.position());
            case final AstExistsPredicate exists -> throw new CypherCompileException(
                    "not supported in this version: EXISTS { ... } inside a CASE WHEN condition", exists.position());
        };
    }

    private static String renderBooleanFunction(final String fn, final List<AstBooleanExpr> operands) {
        final StringBuilder sb = new StringBuilder(fn).append("(");
        for (int i = 0; i < operands.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderBooleanCondition(operands.get(i)));
        }
        return sb.append(")").toString();
    }

    private static String renderComparisonCondition(final AstComparisonPredicate cmp) {
        final String op = switch (cmp.op()) {
            case EQ -> "=";
            case NEQ -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case STARTS_WITH, CONTAINS, ENDS_WITH, REGEX -> throw new CypherCompileException(
                    "not supported in this version: a string predicate (STARTS WITH / CONTAINS / ENDS WITH / =~) "
                    + "inside a CASE WHEN condition", cmp.position());
        };
        return "(" + renderExpression(cmp.left()) + " " + op + " " + renderExpression(cmp.right()) + ")";
    }

    /**
     * The delta-table row key a {@code before(var.prop)} / {@code after(var.prop)} accessor resolves against:
     * {@code "before." + var + "." + prop} or {@code "after." + var + "." + prop}. {@code DiffExecutor} populates
     * exactly these keys from the baseline / comparison snapshot rows (kept in one place so both sides agree).
     * Package-private so {@code DiffExecutor} shares the identical convention. Never returns null.
     */
    public static String diffAccessorRowKey(final AstDiffAccessorExpr accessor) {
        final String side = accessor.side() == AstDiffSide.BEFORE ? "before" : "after";
        return side + "." + accessor.target().variable() + "." + accessor.target().property();
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
            return renderFunctionCall(f);
        }
        throw new CypherCompileException("Unrecognised value", value.position());
    }

    /**
     * Lowers a Cypher {@code RETURN} function call to Stroom's expression-engine syntax: the function name is
     * mapped/validated via {@link CypherFunctions} (unknown/withheld functions fail loud), and each argument is
     * rendered as a Stroom expression. An aggregate or {@code before}/{@code after} argument is rejected - a
     * scalar function evaluates per row, so it cannot host an aggregate.
     */
    private static String renderFunctionCall(final AstFunctionValue f) {
        final List<String> args = f.arguments().stream()
                .map(CypherToLogicalPlan::renderFunctionArgument)
                .toList();
        final String namespace = f.namespace();

        // `stroom.`-qualified: a Stroom-native extension, rendered to its raw engine name (Phase 13).
        if (namespace != null) {
            if (!CypherFunctions.STROOM_NAMESPACE.equals(namespace)) {
                throw new CypherCompileException(
                        "not supported in this version: unknown function namespace '" + namespace
                        + "' - use a bare Cypher function or the stroom.* namespace", f.position());
            }
            if (!CypherFunctions.isStroomFunction(f.name())) {
                throw new CypherCompileException(
                        "not supported in this version: 'stroom." + f.name() + "' is not a recognised Stroom "
                        + "function (available: " + CypherFunctions.stroomNames() + ")", f.position());
            }
            return f.name() + "(" + String.join(", ", args) + ")";
        }

        // Bare = Cypher-standard. Signature-adapted Cypher functions first (Phase 9/10/11), then the 1:1-mapped ones.
        final String adapted = renderCypherAdaptedFunction(f, args);
        if (adapted != null) {
            return adapted;
        }
        final String render = CypherFunctions.cypherRenderName(f.name());
        if (render != null) {
            return render + "(" + String.join(", ", args) + ")";
        }
        // Not a Cypher-standard function. If it is a Stroom extension, point at the stroom. form.
        if (CypherFunctions.isStroomFunction(f.name())) {
            throw new CypherCompileException(
                    "not supported in this version: '" + f.name() + "' is a Stroom extension - call it as stroom."
                    + f.name() + "(...)", f.position());
        }
        throw new CypherCompileException(
                "not supported in this version: function '" + f.name() + "' is not available in a Cypher RETURN",
                f.position());
    }

    /**
     * Renders the Cypher functions whose signature differs from Stroom's by rewriting the call to an equivalent
     * Stroom expression - Cypher-exact semantics over Stroom's existing engine (Phase 9). Returns {@code null} if
     * {@code f} is not one of these adapted functions, so the caller falls back to the plain name-alias path.
     */
    private static @Nullable String renderCypherAdaptedFunction(final AstFunctionValue f, final List<String> args) {
        return switch (f.name()) {
            case "substring" -> {
                // Cypher substring(s, start[, length]); Stroom substring(s, start[, endIndex]). The 2-arg (to-end)
                // form is 1:1; the 3-arg form's length becomes an end index of start + length.
                if (args.size() == 2) {
                    yield "substring(" + args.get(0) + ", " + args.get(1) + ")";
                }
                if (args.size() == 3) {
                    yield "substring(" + args.get(0) + ", " + args.get(1) + ", add(" + args.get(1) + ", "
                          + args.get(2) + "))";
                }
                throw new CypherCompileException(
                        "substring takes 2 or 3 arguments: substring(string, start[, length])", f.position());
            }
            case "left" -> {
                requireArity(f, args, 2, "left(string, length)");
                yield "substring(" + args.get(0) + ", 0, " + args.get(1) + ")";
            }
            case "right" -> {
                requireArity(f, args, 2, "right(string, length)");
                final String s = args.get(0);
                final String n = args.get(1);
                // Last n chars: substring(s, stringLength(s) - n, stringLength(s)); subtraction via add + negate.
                yield "substring(" + s + ", add(stringLength(" + s + "), negate(" + n + ")), stringLength(" + s
                      + "))";
            }
            case "size" -> {
                requireArity(f, args, 1, "size(string)");
                // v1: string length only - size(list) waits on a real list value (ValList; see Phase 4).
                yield "stringLength(" + args.get(0) + ")";
            }
            case "coalesce" -> {
                if (args.isEmpty()) {
                    throw new CypherCompileException("coalesce needs at least one argument", f.position());
                }
                yield renderCoalesce(args, 0);
            }
            case "id" -> {
                requireArity(f, args, 1, "id(variable)");
                yield "${" + GraphIdentity.nodeIdKey(identityVariable(f, "id")) + "}";
            }
            case "type" -> {
                requireArity(f, args, 1, "type(variable)");
                yield "${" + GraphIdentity.edgeTypeKey(identityVariable(f, "type")) + "}";
            }
            case "labels", "keys", "properties" -> throw new CypherCompileException(
                    "not supported in this version: " + f.name() + "() returns a list/map, which needs the "
                    + "list-valued Val type (a later phase)", f.position());
            default -> null;
        };
    }

    /** The bare pattern-variable name argument of {@code id(v)}/{@code type(r)} - rejects any other argument. */
    private static String identityVariable(final AstFunctionValue f, final String function) {
        if (f.arguments().getFirst() instanceof final AstVariableExpr v) {
            return v.name();
        }
        throw new CypherCompileException(
                function + "(...) requires a bare pattern variable, e.g. " + function + "(a)", f.position());
    }

    /**
     * {@code coalesce(a, b, ..., z)} - Cypher's first-non-null - rendered over Stroom's {@code if}/{@code isNull}
     * as {@code if(isNull(a), if(isNull(b), ..., z), a)}.
     */
    private static String renderCoalesce(final List<String> args, final int index) {
        if (index == args.size() - 1) {
            return args.get(index);
        }
        return "if(isNull(" + args.get(index) + "), " + renderCoalesce(args, index + 1) + ", "
               + args.get(index) + ")";
    }

    private static void requireArity(final AstFunctionValue f, final List<String> args, final int arity,
                                     final String usage) {
        if (args.size() != arity) {
            throw new CypherCompileException(
                    f.name() + " takes " + arity + " argument" + (arity == 1 ? "" : "s") + ": " + usage,
                    f.position());
        }
    }

    private static String renderFunctionArgument(final AstExpression arg) {
        if (arg instanceof AstAggregateExpr || arg instanceof AstDiffAccessorExpr) {
            throw new CypherCompileException(
                    "not supported in this version: an aggregate or before()/after() cannot be an argument to a "
                    + "scalar function", arg.position());
        }
        return renderExpression(arg);
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
            final Instant from = instant.minus(duration);
            final Instant to = instant.plus(duration);
            requireOrderedWindow(from, to, around.position());
            return TemporalContext.window(TemporalContext.Mode.AROUND, from, to);
        } else if (temporal instanceof final AstBetween between) {
            final Instant from = resolveInstant(between.from());
            final Instant to = resolveInstant(between.to());
            requireOrderedWindow(from, to, between.position());
            return TemporalContext.window(TemporalContext.Mode.BETWEEN, from, to);
        }
        throw new CypherCompileException("Unrecognised temporal clause", temporal.position());
    }

    /**
     * Rejects a reversed {@code AROUND}/{@code BETWEEN} window ({@code from > to}), mirroring
     * {@link #resolveDiff}'s {@code baseline < comparison} guard. Unlike {@code DIFF}, a zero-width window
     * ({@code from == to}) is valid here (e.g. an {@code AROUND} with a zero duration), so the check is
     * {@code from <= to} rather than strictly-before. Without this guard, a swapped {@code BETWEEN} or a
     * negative-duration {@code AROUND} would silently intersect an unrelated version instead of failing to
     * compile finding F7.
     */
    private static void requireOrderedWindow(final Instant from, final Instant to, final AstPosition position) {
        if (from.isAfter(to)) {
            throw new CypherCompileException(
                    "Temporal window requires from <= to; got from=" + from + ", to=" + to, position);
        }
    }

    /**
     * Resolves a {@code DIFF FROM <baseline> TO <comparison>} clause to a {@link DiffContext}, enforcing the
     * {@code baseline < comparison} (t1 &lt; t2) precondition with a positioned error (equal or reversed instants
     * are a compile-time mistake, per &sect;5.5).
     */
    private DiffContext resolveDiff(final AstDiff diff) {
        final Instant baseline = resolveInstant(diff.baseline());
        final Instant comparison = resolveInstant(diff.comparison());
        if (!baseline.isBefore(comparison)) {
            throw new CypherCompileException(
                    "DIFF requires baseline < comparison (FROM t1 TO t2 with t1 before t2); got baseline="
                    + baseline + ", comparison=" + comparison, diff.position());
        }
        return new DiffContext(baseline, comparison);
    }

    /**
     * Rejects a variable-length pattern under a {@code DIFF} clause. v1 diffs fixed-length patterns only
     * (a var-length traversal run twice is deferred - &sect;12.1).
     */
    private void rejectVarLengthUnderDiff(final AstPathPattern pattern) {
        for (final AstPatternHop hop : pattern.hops()) {
            if (hop.edge().varLength() != null) {
                throw new CypherCompileException(
                        "not supported in this version: DIFF over a variable-length pattern - use a fixed-length "
                        + "pattern", hop.edge().position());
            }
        }
    }

    /**
     * Rejects {@code changeKind} / {@code before(...)} / {@code after(...)} in a query that has no {@code DIFF}
     * clause - they are only meaningful inside a diff. Scans the {@code RETURN} items, any {@code ORDER BY} items,
     * and the {@code WHERE} clause.
     */
    private void rejectDiffConstructsOutsideDiff(final AstReturnClause returnClause, final @Nullable AstWhere where) {
        for (final AstReturnItem item : returnClause.items()) {
            rejectIfDiffConstruct(item.expression());
        }
        if (returnClause.orderBy() != null) {
            for (final AstOrderItem item : returnClause.orderBy().items()) {
                rejectIfDiffConstruct(item.expression());
            }
        }
        if (where != null) {
            rejectIfDiffConstruct(where.expr());
        }
    }

    private void rejectIfDiffConstruct(final AstExpression expression) {
        switch (expression) {
            case final AstDiffAccessorExpr accessor -> throw new CypherCompileException(
                    accessor.side().name().toLowerCase(java.util.Locale.ROOT)
                    + "(...) is only valid inside a DIFF query", accessor.position());
            case final AstVariableExpr variable -> {
                if (CHANGE_KIND_COLUMN.equals(variable.name())) {
                    throw new CypherCompileException(
                            "'" + CHANGE_KIND_COLUMN + "' is only valid inside a DIFF query", variable.position());
                }
            }
            case final AstAggregateExpr aggregate -> {
                if (aggregate.argument() != null) {
                    rejectIfDiffConstruct(aggregate.argument());
                }
            }
            default -> {
                // AstPropertyAccessExpr / AstLiteralExpr carry no diff construct.
            }
        }
    }

    private void rejectIfDiffConstruct(final AstBooleanExpr booleanExpr) {
        switch (booleanExpr) {
            case final AstOrExpr or -> or.operands().forEach(this::rejectIfDiffConstruct);
            case final AstAndExpr and -> and.operands().forEach(this::rejectIfDiffConstruct);
            case final AstNotExpr not -> rejectIfDiffConstruct(not.operand());
            case final AstComparisonPredicate cmp -> {
                rejectIfDiffConstruct(cmp.left());
                rejectIfDiffConstruct(cmp.right());
            }
            case final AstInPredicate in -> {
                rejectIfDiffConstruct(in.left());
                rejectIfDiffConstruct(in.right());
            }
            case final AstIsNullPredicate isNull -> rejectIfDiffConstruct(isNull.operand());
            case final AstExistsPredicate ignored -> {
                // The EXISTS pattern contains only graph elements (no value expressions), so there is no diff
                // construct to reject here; EXISTS-under-DIFF is rejected in compile via existsPredicates.
            }
        }
    }

    /**
     * Rejects {@code changeKind} / {@code before(...)} / {@code after(...)} anywhere in a {@code DIFF} query's
     * {@code WHERE} clause (v1 delta-table limitation - see {@link #compile}). Walks the boolean tree exactly like
     * {@link #rejectIfDiffConstruct(AstBooleanExpr)} but raises a v1-specific message directing the author to use
     * these constructs in {@code RETURN} instead.
     */
    private void rejectDiffConstructsInDiffWhere(final AstBooleanExpr booleanExpr) {
        switch (booleanExpr) {
            case final AstOrExpr or -> or.operands().forEach(this::rejectDiffConstructsInDiffWhere);
            case final AstAndExpr and -> and.operands().forEach(this::rejectDiffConstructsInDiffWhere);
            case final AstNotExpr not -> rejectDiffConstructsInDiffWhere(not.operand());
            case final AstComparisonPredicate cmp -> {
                rejectDiffConstructInDiffWhere(cmp.left());
                rejectDiffConstructInDiffWhere(cmp.right());
            }
            case final AstInPredicate in -> {
                rejectDiffConstructInDiffWhere(in.left());
                rejectDiffConstructInDiffWhere(in.right());
            }
            case final AstIsNullPredicate isNull -> rejectDiffConstructInDiffWhere(isNull.operand());
            case final AstExistsPredicate ignored -> {
                // No value expressions inside an EXISTS pattern; EXISTS-under-DIFF is rejected in compile.
            }
        }
    }

    private void rejectDiffConstructInDiffWhere(final AstExpression expression) {
        switch (expression) {
            case final AstDiffAccessorExpr accessor -> throw new CypherCompileException(
                    "not supported in this version: "
                    + accessor.side().name().toLowerCase(Locale.ROOT)
                    + "(...) in a DIFF WHERE clause (filtering on it is a later phase); it is supported in RETURN",
                    accessor.position());
            case final AstVariableExpr variable -> {
                if (CHANGE_KIND_COLUMN.equals(variable.name())) {
                    throw new CypherCompileException(
                            "not supported in this version: '" + CHANGE_KIND_COLUMN + "' in a DIFF WHERE clause "
                            + "(filtering on it is a later phase); it is supported in RETURN", variable.position());
                }
            }
            default -> {
                // AstPropertyAccessExpr / AstLiteralExpr / AstAggregateExpr carry no diff construct we reject here.
            }
        }
    }

    private static Instant resolveInstant(final AstValue value) {
        if (value instanceof final AstFunctionValue f
                && f.namespace() == null
                && f.name().equalsIgnoreCase("datetime")
                && f.arguments().size() == 1
                && f.arguments().getFirst() instanceof final AstLiteralExpr lit
                && lit.value() instanceof final AstStringValue s) {
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
                && f.namespace() == null
                && f.name().equalsIgnoreCase("duration")
                && f.arguments().size() == 1
                && f.arguments().getFirst() instanceof final AstLiteralExpr lit
                && lit.value() instanceof final AstStringValue s) {
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
