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

package stroom.query.planner.bind;

import stroom.domaintype.shared.DomainType;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.ConditionSet;
import stroom.query.api.datasource.QueryField;
import stroom.query.grammar.ast.AstAndExpr;
import stroom.query.grammar.ast.AstBetweenTerm;
import stroom.query.grammar.ast.AstClause;
import stroom.query.grammar.ast.AstComparisonCond;
import stroom.query.grammar.ast.AstComparisonTerm;
import stroom.query.grammar.ast.AstEvalClause;
import stroom.query.grammar.ast.AstFilterClause;
import stroom.query.grammar.ast.AstFrom;
import stroom.query.grammar.ast.AstGroupClause;
import stroom.query.grammar.ast.AstHavingClause;
import stroom.query.grammar.ast.AstInDictionaryTerm;
import stroom.query.grammar.ast.AstInTerm;
import stroom.query.grammar.ast.AstIsNullTerm;
import stroom.query.grammar.ast.AstJoin;
import stroom.query.grammar.ast.AstJoinCondition;
import stroom.query.grammar.ast.AstLimitClause;
import stroom.query.grammar.ast.AstNamedJoinSource;
import stroom.query.grammar.ast.AstNotExpr;
import stroom.query.grammar.ast.AstOrExpr;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.grammar.ast.AstPrimary;
import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.ast.AstSelectClause;
import stroom.query.grammar.ast.AstSelectField;
import stroom.query.grammar.ast.AstSelectFunction;
import stroom.query.grammar.ast.AstSelectItem;
import stroom.query.grammar.ast.AstSelectParam;
import stroom.query.grammar.ast.AstSelectStar;
import stroom.query.grammar.ast.AstShowClause;
import stroom.query.grammar.ast.AstSortClause;
import stroom.query.grammar.ast.AstSortItem;
import stroom.query.grammar.ast.AstSubQueryJoinSource;
import stroom.query.grammar.ast.AstTerm;
import stroom.query.grammar.ast.AstToken;
import stroom.query.grammar.ast.AstValue;
import stroom.query.grammar.ast.AstWhereClause;
import stroom.query.grammar.ast.AstWindowClause;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.planner.cypher.CypherCompileException;
import stroom.query.planner.cypher.CypherJoinSchema;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.GraphJoinSource;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.JoinType;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.SortKey;
import stroom.query.planner.logical.Window;
import stroom.query.planner.port.FieldInfoSource;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code (AstQuery, FieldInfoSource) -> LogicalPlan} - see {@code docs/query-optimiser-implementation-plan.md},
 * Task 2.2. Fails fast with a {@link BindException} on the first unresolvable field, unsupported condition,
 * unknown join alias, or domain-incompatible join key.
 *
 * <p><b>Operator order is a provisional, documented choice, not a proven-correct execution order</b> (this
 * binder is not yet wired into any executor - see the design doc's Task 2.2): {@code Scan}/{@code Join} →
 * {@code Filter} ({@code where} + {@code filter}) → {@code Window} → {@code Project} ({@code eval} +
 * {@code select}) → {@code Aggregate} (one per {@code group by}, nested) → {@code Having} → {@code Sort} →
 * {@code Limit}. Revisit once Phase 3+ studies physical execution semantics.</p>
 *
 * <p><b>Multiple clauses of the same kind</b> (e.g. two {@code where} clauses) are combined defensively rather
 * than rejected or silently dropped: {@code where}/{@code filter}/{@code having} AND together; {@code eval} always
 * accumulates in order (as legacy does); {@code group by} nests one {@link Aggregate} per clause (as legacy's
 * {@code groupDepth} does); {@code sort by} concatenates items across clauses; {@code window} and {@code limit}
 * take the last clause seen. None of this is asserted against legacy behaviour - the corpus has no such queries -
 * it exists so an unusual-but-syntactically-valid query gets a sensible bound plan rather than a silently dropped
 * clause.</p>
 */
public final class Binder {

    private final FieldInfoSource fieldInfoSource;

    public Binder(final FieldInfoSource fieldInfoSource) {
        this.fieldInfoSource = Objects.requireNonNull(fieldInfoSource, "fieldInfoSource");
    }

    /**
     * @param query never null.
     * @return never null; the bound logical plan.
     * @throws BindException on the first unresolvable field, unsupported condition, unknown join alias, or
     *                        domain-incompatible join key.
     */
    public LogicalPlan bind(final AstQuery query) {
        Objects.requireNonNull(query, "query");
        final Scope scope = new Scope();
        LogicalPlan plan = bindFromAndJoins(query.from(), scope);

        ExpressionOperator wherePredicate = null;
        ExpressionOperator filterPredicate = null;
        ExpressionOperator havingPredicate = null;
        // Track the where and filter clause positions separately so the bound Filter node reports the clause it
        // actually came from (a where-only query previously reported the `from` clause position, misdirecting
        // any where-predicate error/EXPLAIN location - LogicalPlan.position() is documented as "the clause this
        // node was bound from"). Both default to the from position only when neither clause is present.
        AstPosition wherePosition = query.from().position();
        AstPosition filterPosition = query.from().position();
        AstPosition havingPosition = query.from().position();
        final List<ProjectField> projectFields = new ArrayList<>();
        final List<List<QualifiedField>> groupLevels = new ArrayList<>();
        AstPosition groupPosition = query.from().position();
        final List<AstSortItem> sortItems = new ArrayList<>();
        AstPosition sortPosition = query.from().position();
        @Nullable AstWindowClause pendingWindow = null;
        @Nullable List<Long> limitValues = null;
        AstPosition limitPosition = query.from().position();

        for (final AstClause clause : query.clauses()) {
            if (clause instanceof final AstWhereClause c) {
                final ExpressionOperator bound = bindExpression(c.expr(), scope, false);
                if (wherePredicate == null) {
                    wherePosition = c.position();
                }
                wherePredicate = wherePredicate == null ? bound : and(wherePredicate, bound);
            } else if (clause instanceof final AstFilterClause c) {
                final ExpressionOperator bound = bindExpression(c.expr(), scope, true);
                filterPredicate = filterPredicate == null ? bound : and(filterPredicate, bound);
                filterPosition = c.position();
            } else if (clause instanceof final AstHavingClause c) {
                final ExpressionOperator bound = bindExpression(c.expr(), scope, true);
                havingPredicate = havingPredicate == null ? bound : and(havingPredicate, bound);
                havingPosition = c.position();
            } else if (clause instanceof final AstEvalClause c) {
                final String name = evalFieldName(c.name());
                scope.evalFieldNames.add(name);
                projectFields.add(new ProjectField(name, c.expressionText(), false, null, c.position()));
            } else if (clause instanceof final AstWindowClause c) {
                pendingWindow = c;
            } else if (clause instanceof final AstSortClause c) {
                sortItems.addAll(c.items());
                sortPosition = c.position();
            } else if (clause instanceof final AstGroupClause c) {
                groupLevels.add(c.fields().stream()
                        .map(t -> resolveField(t, scope, true))
                        .collect(Collectors.toList()));
                groupPosition = c.position();
            } else if (clause instanceof final AstSelectClause c) {
                for (final AstSelectItem item : c.items()) {
                    projectFields.add(bindSelectItem(item, scope));
                }
            } else if (clause instanceof final AstLimitClause c) {
                limitValues = c.values().stream()
                        .map(this::parseLimitValue)
                        .collect(Collectors.toList());
                limitPosition = c.position();
            } else if (clause instanceof final AstShowClause c) {
                throw new BindException("show is not yet supported by the optimising binder", c.position());
            } else {
                throw new IllegalStateException("Unrecognised clause type: " + clause.getClass());
            }
        }

        if (wherePredicate != null || filterPredicate != null) {
            // Position the Filter at the where clause when a where contributed (the common case), else the
            // filter clause - never the from clause unless neither predicate exists (impossible in this branch).
            final AstPosition predicatePosition = wherePredicate != null ? wherePosition : filterPosition;
            plan = new Filter(plan, wherePredicate, filterPredicate, predicatePosition);
        }
        if (pendingWindow != null) {
            plan = new Window(
                    plan,
                    resolveField(pendingWindow.field(), scope, true),
                    pendingWindow.windowSize().unescapedText(),
                    pendingWindow.advanceSize() == null ? null : pendingWindow.advanceSize().unescapedText(),
                    pendingWindow.usingFunction() == null ? null : pendingWindow.usingFunction().unescapedText(),
                    pendingWindow.position());
        }
        if (!projectFields.isEmpty()) {
            plan = new Project(plan, projectFields, query.position());
        }
        for (final List<QualifiedField> level : groupLevels) {
            plan = new Aggregate(plan, level, groupPosition);
        }
        if (havingPredicate != null) {
            plan = new Having(plan, havingPredicate, havingPosition);
        }
        if (!sortItems.isEmpty()) {
            final List<SortKey> keys = sortItems.stream()
                    .map(item -> new SortKey(
                            resolveField(item.field(), scope, true),
                            isDescending(item.direction())))
                    .collect(Collectors.toList());
            plan = new Sort(plan, keys, sortPosition);
        }
        if (limitValues != null) {
            plan = new Limit(plan, limitValues, limitPosition);
        }
        return plan;
    }

    // ------------------------------------------------------------------------------------------------------
    // from / join
    // ------------------------------------------------------------------------------------------------------

    private LogicalPlan bindFromAndJoins(final AstFrom from, final Scope scope) {
        final String primaryAlias = aliasOrSourceName(from.alias(), from.source());
        final Scan primaryScan = new Scan(primaryAlias, from.source().unescapedText(), from.position());
        scope.sourcesByAlias.put(primaryAlias, new ScanSource(primaryScan));

        LogicalPlan plan = primaryScan;
        for (final AstJoin join : from.joins()) {
            final String joinAlias = joinAlias(join);
            if (scope.sourcesByAlias.containsKey(joinAlias)) {
                throw new BindException("Duplicate source alias '" + joinAlias + "'", join.position());
            }
            final LogicalPlan joinOperand = bindJoinSource(join, joinAlias, scope);

            final List<EquiKey> equiKeys = new ArrayList<>(join.conditions().size());
            for (final AstJoinCondition condition : join.conditions()) {
                final QualifiedField left = resolveQualifiedFieldStrict(condition.left(), scope);
                final QualifiedField right = resolveQualifiedFieldStrict(condition.right(), scope);
                validateDomainTypeCompatibility(left, right, scope, condition.position());
                equiKeys.add(new EquiKey(left, right));
            }
            final JoinType joinType = join.joinType() == AstJoin.JoinType.LEFT ? JoinType.LEFT : JoinType.INNER;
            plan = new Join(plan, joinOperand, joinType, equiKeys, join.position());
        }
        return plan;
    }

    private static String aliasOrSourceName(final @Nullable AstToken alias, final AstToken source) {
        return alias != null ? alias.unescapedText() : source.unescapedText();
    }

    /**
     * @return {@code join}'s alias - explicit if given, else (a named source only - {@code AstBuilder} enforces a
     *         sub-query source always carries one, see {@link AstSubQueryJoinSource}'s Javadoc) the source name.
     */
    private static String joinAlias(final AstJoin join) {
        if (join.alias() != null) {
            return join.alias().unescapedText();
        }
        if (join.source() instanceof final AstNamedJoinSource named) {
            return named.token().unescapedText();
        }
        throw new IllegalStateException(
                "A sub-query join source must carry an alias - AstBuilder should have rejected this already");
    }

    /**
     * Binds one join's source into the {@link LogicalPlan} operand {@link Join} embeds, registering its alias in
     * {@code scope} so later {@code on}/{@code where}/{@code select} references resolve against it - a plain
     * {@link Scan} for a named source, or (Phase P1/P2, docs/graphdb-stroomql-join-implementation-plan.md) a
     * {@link GraphJoinSource} for a Cypher sub-query source, whose schema is derived from its own
     * {@code RETURN ... AS} list via {@link CypherJoinSchema}.
     */
    private LogicalPlan bindJoinSource(final AstJoin join, final String joinAlias, final Scope scope) {
        return switch (join.source()) {
            case final AstNamedJoinSource named -> {
                final Scan joinScan = new Scan(joinAlias, named.token().unescapedText(), join.position());
                scope.sourcesByAlias.put(joinAlias, new ScanSource(joinScan));
                yield joinScan;
            }
            case final AstSubQueryJoinSource subQuery -> bindGraphJoinSource(subQuery, joinAlias, scope);
        };
    }

    /**
     * Parses and compiles {@code subQuery}'s raw Cypher text far enough to derive its join-side schema (Phase
     * P2), registers that schema in {@code scope} under {@code alias}, and returns the {@link GraphJoinSource}
     * leaf {@link #bindFromAndJoins} embeds as the {@link Join} operand. The compiled plan itself is discarded
     * immediately afterwards - only the raw text survives, to be re-parsed/re-compiled again at Phase P3 (see
     * {@link GraphJoinSource}'s Javadoc for why).
     *
     * <p>Today the only sub-query body this method knows how to interpret is Cypher - there is no StroomQL-
     * specific parsing of the graph body attempted (a nested StroomQL join side is a separate, larger feature,
     * out of scope for Workstream C). A parse/compile failure - including a sub-query that simply isn't valid
     * Cypher at all - is reported as a {@link BindException} positioned at the sub-query's opening bracket, since
     * the inner grammar's own line/column are relative to the extracted text, not the outer query.</p>
     *
     * @throws BindException if {@code subQuery}'s text fails to parse/compile as Cypher, or violates
     *                        {@link CypherJoinSchema}'s C0 contract (a missing {@code AS} alias, or a
     *                        no-scalar-shape {@code RETURN} item).
     */
    private GraphJoinSource bindGraphJoinSource(
            final AstSubQueryJoinSource subQuery, final String alias, final Scope scope) {
        final String cypherText = subQuery.rawText();
        final LogicalPlan compiledPlan;
        try {
            final AstCypherQuery ast = CypherQueryParser.parse(cypherText);
            compiledPlan = new CypherToLogicalPlan().compile(ast).plan();
        } catch (final RuntimeException e) {
            throw new BindException(
                    "Join side '" + alias + "' is not a valid Cypher sub-query (a StroomQL/other sub-query join "
                    + "source is not supported): " + e.getMessage(), subQuery.position());
        }

        final List<ProjectField> columns;
        try {
            columns = CypherJoinSchema.deriveJoinColumns(compiledPlan);
        } catch (final CypherCompileException e) {
            throw new BindException("Join side '" + alias + "': " + e.getMessage(), subQuery.position());
        }

        final List<QueryField> queryFields = columns.stream()
                .map(field -> toGraphQueryField(field.name()))
                .collect(Collectors.toList());
        scope.sourcesByAlias.put(alias, new GraphSource(alias, queryFields));
        return new GraphJoinSource(alias, cypherText, subQuery.position());
    }

    /**
     * Synthesises the {@link QueryField} a graph join side's derived column is exposed as - per
     * {@link CypherJoinSchema}'s C0 contract, a conservative "unknown" type: no {@code domainType} (so
     * {@link #validateDomainTypeCompatibility} degrades gracefully rather than rejecting a legitimate join) and
     * no {@code ConditionSet} (left {@code null} by never setting {@code fldType} on the builder - see
     * {@code QueryField.Builder#build}, which only defaults a {@code ConditionSet} when a {@code fldType} was
     * actually set) - so an outer clause referencing it is never rejected for "using an unsupported condition".
     * {@code queryable(false)}: not pushable to any real datasource - belt-and-braces, since
     * {@code PlanRewriteUtil.collectScans} already excludes a {@link GraphJoinSource}'s alias from the push-down
     * candidate set entirely.
     */
    private static QueryField toGraphQueryField(final String name) {
        return QueryField.builder().fldName(name).queryable(false).build();
    }

    // ------------------------------------------------------------------------------------------------------
    // boolean expressions (where / filter / having) - mirrors AstToSearchRequestMapper's fold, independently
    // (see this class's Javadoc and Task 2.2's write-up in the implementation plan for why this is not shared)
    // ------------------------------------------------------------------------------------------------------

    private ExpressionOperator bindExpression(final AstOrExpr expr, final Scope scope, final boolean allowEvalFields) {
        return asOperator(foldOr(expr, scope, allowEvalFields));
    }

    private ExpressionItem foldOr(final AstOrExpr orExpr, final Scope scope, final boolean allowEvalFields) {
        return foldPairwise(
                orExpr.operands().stream().map(a -> foldAnd(a, scope, allowEvalFields)).collect(Collectors.toList()),
                Op.OR);
    }

    private ExpressionItem foldAnd(final AstAndExpr andExpr, final Scope scope, final boolean allowEvalFields) {
        return foldPairwise(
                andExpr.operands().stream().map(n -> foldNot(n, scope, allowEvalFields)).collect(Collectors.toList()),
                Op.AND);
    }

    private static ExpressionItem foldPairwise(final List<ExpressionItem> items, final Op op) {
        ExpressionItem result = items.getFirst();
        for (int i = 1; i < items.size(); i++) {
            result = ExpressionOperator.builder().op(op).children(List.of(result, items.get(i))).build();
        }
        return result;
    }

    private ExpressionItem foldNot(final AstNotExpr notExpr, final Scope scope, final boolean allowEvalFields) {
        if (notExpr.negated()) {
            final ExpressionItem inner = foldNot(notExpr.inner(), scope, allowEvalFields);
            return ExpressionOperator.builder().op(Op.NOT).children(List.of(inner)).build();
        }
        return foldPrimary(notExpr.primary(), scope, allowEvalFields);
    }

    private ExpressionItem foldPrimary(final AstPrimary primary, final Scope scope, final boolean allowEvalFields) {
        if (primary.bracketed() != null) {
            return asOperator(foldOr(primary.bracketed(), scope, allowEvalFields));
        }
        return bindTerm(primary.term(), scope, allowEvalFields);
    }

    private static ExpressionOperator asOperator(final ExpressionItem item) {
        if (item instanceof final ExpressionOperator operator) {
            return operator;
        }
        return ExpressionOperator.builder().children(List.of(item)).build();
    }

    private static ExpressionOperator and(final ExpressionOperator a, final ExpressionOperator b) {
        return ExpressionOperator.builder().op(Op.AND).children(List.of(a, b)).build();
    }

    private ExpressionTerm bindTerm(final AstTerm term, final Scope scope, final boolean allowEvalFields) {
        final QualifiedField field = resolveField(term.field(), scope, allowEvalFields);
        final Condition condition = conditionOf(term);
        validateCondition(field, condition, term.position(), scope);

        final ExpressionTerm.Builder builder = ExpressionTerm.builder()
                .field(qualifiedName(field))
                .condition(condition);
        if (term instanceof final AstComparisonTerm t) {
            return builder.value(t.value().sourceText()).build();
        } else if (term instanceof final AstBetweenTerm t) {
            return builder.value(t.lower().sourceText() + ", " + t.upper().sourceText()).build();
        } else if (term instanceof final AstInTerm t) {
            return builder.value(t.values().stream().map(AstValue::sourceText)
                    .collect(Collectors.joining(", "))).build();
        } else if (term instanceof AstInDictionaryTerm) {
            // Dictionary DocRef resolution is deferred - out of scope for this pass (no dictionary-lookup port
            // has been defined yet; see the implementation plan's Task 2.2 scope note).
            return builder.build();
        } else if (term instanceof AstIsNullTerm) {
            return builder.build();
        }
        throw new IllegalStateException("Unrecognised term type: " + term.getClass());
    }

    private static Condition conditionOf(final AstTerm term) {
        if (term instanceof final AstComparisonTerm t) {
            return switch (t.cond()) {
                case EQUALS -> Condition.EQUALS;
                case NOT_EQUALS -> Condition.NOT_EQUALS;
                case GREATER_THAN -> Condition.GREATER_THAN;
                case GREATER_THAN_OR_EQUAL_TO -> Condition.GREATER_THAN_OR_EQUAL_TO;
                case LESS_THAN -> Condition.LESS_THAN;
                case LESS_THAN_OR_EQUAL_TO -> Condition.LESS_THAN_OR_EQUAL_TO;
            };
        } else if (term instanceof AstBetweenTerm) {
            return Condition.BETWEEN;
        } else if (term instanceof AstInTerm) {
            return Condition.IN;
        } else if (term instanceof AstInDictionaryTerm) {
            return Condition.IN_DICTIONARY;
        } else if (term instanceof final AstIsNullTerm t) {
            return t.negated() ? Condition.IS_NOT_NULL : Condition.IS_NULL;
        }
        throw new IllegalStateException("Unrecognised term type: " + term.getClass());
    }

    private static String qualifiedName(final QualifiedField field) {
        return field.alias() == null ? field.field() : field.alias() + "." + field.field();
    }

    // ------------------------------------------------------------------------------------------------------
    // select
    // ------------------------------------------------------------------------------------------------------

    private ProjectField bindSelectItem(final AstSelectItem item, final Scope scope) {
        if (item instanceof final AstSelectStar star) {
            return new ProjectField("*", "*", true, aliasText(star.alias()), star.position());
        } else if (item instanceof final AstSelectFunction fn) {
            final String alias = aliasText(fn.alias());
            return new ProjectField(alias != null ? alias : fn.expressionText(), fn.expressionText(), true, alias,
                    fn.position());
        } else if (item instanceof final AstSelectField f) {
            final QualifiedField resolved = resolveField(f.field(), scope, true);
            final String alias = aliasText(f.alias());
            return new ProjectField(alias != null ? alias : resolved.field(), f.field().rawText(), true, alias,
                    f.position());
        } else if (item instanceof final AstSelectParam p) {
            final String alias = aliasText(p.alias());
            return new ProjectField(alias != null ? alias : p.field().unescapedText(), p.field().rawText(), true,
                    alias, p.position());
        }
        throw new IllegalStateException("Unrecognised select item type: " + item.getClass());
    }

    private static @Nullable String aliasText(final @Nullable AstToken alias) {
        return alias == null ? null : alias.unescapedText();
    }

    // ------------------------------------------------------------------------------------------------------
    // field / condition / domain-type resolution
    // ------------------------------------------------------------------------------------------------------

    /**
     * Resolves a plain (possibly {@code alias.}-qualified) field reference used outside a join condition -
     * {@code where}/{@code filter}/{@code having} terms, {@code group by}, {@code sort by}, {@code window}'s
     * field, and {@code select}'s plain-field item.
     *
     * @param allowEvalFields whether an unqualified name may resolve to an {@code eval}-defined name introduced
     *                        earlier in the query - false for {@code where} (pushed to the datasource, which has
     *                        no notion of an {@code eval}-computed column), true everywhere else.
     */
    private QualifiedField resolveField(final AstToken token, final Scope scope, final boolean allowEvalFields) {
        if (token.kind() == AstToken.Kind.PARAM) {
            return new QualifiedField(null, token.unescapedText());
        }

        final String text = token.kind() == AstToken.Kind.BAREWORD ? token.rawText() : token.unescapedText();
        if (token.kind() == AstToken.Kind.BAREWORD) {
            final int dot = text.indexOf('.');
            if (dot >= 0) {
                final String possibleAlias = text.substring(0, dot);
                if (scope.sourcesByAlias.containsKey(possibleAlias)) {
                    final String fieldName = text.substring(dot + 1);
                    if (findQueryField(scope.sourcesByAlias.get(possibleAlias), fieldName).isEmpty()) {
                        throw new BindException(
                                "Unknown field '" + fieldName + "' on '" + possibleAlias + "'", token.position());
                    }
                    return new QualifiedField(possibleAlias, fieldName);
                }
            }
        }

        if (allowEvalFields && scope.evalFieldNames.contains(text)) {
            return new QualifiedField(null, text);
        }

        final List<String> matches = new ArrayList<>();
        for (final Map.Entry<String, BoundSource> entry : scope.sourcesByAlias.entrySet()) {
            if (findQueryField(entry.getValue(), text).isPresent()) {
                matches.add(entry.getKey());
            }
        }
        if (matches.isEmpty()) {
            throw new BindException("Unknown field '" + text + "'", token.position());
        }
        if (matches.size() > 1) {
            throw new BindException(
                    "Ambiguous field '" + text + "' - present on multiple sources (" + String.join(", ", matches)
                    + "); qualify with a source alias", token.position());
        }
        return new QualifiedField(scope.sourcesByAlias.size() > 1 ? matches.getFirst() : null, text);
    }

    /**
     * Resolves a join condition's field - unlike {@link #resolveField}, always requires explicit
     * {@code alias.field} qualification (an unqualified name in an {@code on} clause is ambiguous by
     * construction - there are always at least two sources - so it is rejected rather than guessed at).
     */
    private QualifiedField resolveQualifiedFieldStrict(final AstToken token, final Scope scope) {
        if (token.kind() != AstToken.Kind.BAREWORD) {
            throw new BindException(
                    "Join condition fields must be an alias-qualified reference, e.g. 'a.field'", token.position());
        }
        final String text = token.rawText();
        final int dot = text.indexOf('.');
        if (dot < 0) {
            throw new BindException(
                    "Join condition field '" + text + "' must be qualified with a source alias, e.g. 'a." + text
                    + "'", token.position());
        }
        final String alias = text.substring(0, dot);
        final String fieldName = text.substring(dot + 1);
        final BoundSource source = scope.sourcesByAlias.get(alias);
        if (source == null) {
            throw new BindException("Unknown alias '" + alias + "'", token.position());
        }
        if (findQueryField(source, fieldName).isEmpty()) {
            throw new BindException("Unknown field '" + fieldName + "' on '" + alias + "'", token.position());
        }
        return new QualifiedField(alias, fieldName);
    }

    private void validateCondition(
            final QualifiedField field, final Condition condition, final AstPosition position, final Scope scope) {
        if (field.alias() == null && scope.evalFieldNames.contains(field.field())) {
            return;
        }
        final BoundSource source;
        if (field.alias() != null) {
            source = scope.sourcesByAlias.get(field.alias());
        } else if (scope.sourcesByAlias.size() == 1) {
            source = scope.onlySource();
        } else {
            // An unqualified reference with no alias in a multi-source query - the only way this is reached is a
            // PARAM term field (e.g. `where ${p} = 1`): resolveField already qualifies every *real* field with
            // its source alias, and eval fields short-circuit above. A param is a runtime value with no
            // datasource field metadata, so there is nothing to condition-validate here - skip, exactly like the
            // queryField.isEmpty() path below (and never fall through to onlySource(), which would throw on 2+
            // sources - the raw IllegalStateException this replaces).
            return;
        }
        final Optional<QueryField> queryField = findQueryField(source, field.field());
        if (queryField.isEmpty()) {
            // A PARAM reference, or (single-source, unqualified) an eval field already handled above - nothing
            // further to validate at bind time.
            return;
        }
        final ConditionSet conditionSet = queryField.get().getConditionSet();
        if (conditionSet != null && !conditionSet.supportsCondition(condition)) {
            throw new BindException(
                    "Condition " + condition + " is not supported for field '" + field.field() + "'", position);
        }
    }

    private void validateDomainTypeCompatibility(
            final QualifiedField left, final QualifiedField right, final Scope scope, final AstPosition position) {
        final Optional<QueryField> leftField =
                findQueryField(scope.sourcesByAlias.get(left.alias()), left.field());
        final Optional<QueryField> rightField =
                findQueryField(scope.sourcesByAlias.get(right.alias()), right.field());
        if (leftField.isEmpty() || rightField.isEmpty()) {
            return;
        }
        final String leftDomainType = leftField.get().getDomainType();
        final String rightDomainType = rightField.get().getDomainType();
        if (leftDomainType == null || rightDomainType == null) {
            // Advisory only - degrade gracefully when either side lacks a domain type, per the design doc (also
            // how every graph join side's synthetic QueryField behaves - see toGraphQueryField).
            return;
        }
        final DomainType leftType = new DomainType(leftDomainType);
        final DomainType rightType = new DomainType(rightDomainType);
        if (!leftType.canAccept(rightType) && !rightType.canAccept(leftType)) {
            throw new BindException(
                    "Join key domain types are incompatible: '" + qualifiedName(left) + "' is " + leftDomainType
                    + ", '" + qualifiedName(right) + "' is " + rightDomainType, position);
        }
    }

    /**
     * @param source the bound source to look the field up on - either a plain named datasource ({@link
     *               ScanSource}, resolved via {@link #fieldInfoSource}) or a graph join side's derived schema
     *               ({@link GraphSource}, resolved against its own {@code RETURN ... AS} columns) - see
     *               {@link BoundSource}'s Javadoc.
     */
    private Optional<QueryField> findQueryField(final BoundSource source, final String fieldName) {
        final List<QueryField> fields = switch (source) {
            case final ScanSource s -> fieldInfoSource.getFields(s.scan().dataSourceName());
            case final GraphSource g -> g.columns();
        };
        return fields.stream()
                .filter(f -> f.getFldName().equals(fieldName))
                .findFirst();
    }

    private static String evalFieldName(final AstToken name) {
        return name.kind() == AstToken.Kind.PARAM ? name.unescapedText() : name.rawText();
    }

    private long parseLimitValue(final AstToken token) {
        try {
            return Long.parseLong(token.unescapedText().trim());
        } catch (final NumberFormatException e) {
            throw new BindException("Limit value '" + token.unescapedText() + "' is not a number", token.position());
        }
    }

    private static boolean isDescending(final @Nullable AstToken direction) {
        return direction != null && "desc".equalsIgnoreCase(direction.unescapedText());
    }

    /**
     * One bound {@code from}/{@code join} source's field-lookup contract - either a plain named datasource
     * ({@link ScanSource}, resolved via {@link #fieldInfoSource}) or a Cypher sub-query's derived schema
     * ({@link GraphSource}, docs/graphdb-stroomql-join-implementation-plan.md, Phase P2). {@code Scope} was
     * previously keyed directly by {@link Scan}; generalised to this sealed choice so a graph join side's alias
     * resolves {@code alias.field} references the same way as any other source, without asking
     * {@link #fieldInfoSource} about a datasource name it has never heard of (a Cypher sub-query is not a
     * registered datasource in that sense - its own {@code RETURN ... AS} list is the only schema it has).
     */
    private sealed interface BoundSource {

        String alias();
    }

    private record ScanSource(Scan scan) implements BoundSource {

        @Override
        public String alias() {
            return scan.alias();
        }
    }

    /**
     * A graph join side's derived schema (Phase P2) - {@code columns} is exactly what {@link CypherJoinSchema}
     * derived from the sub-query's {@code RETURN ... AS} list, converted to synthetic {@link QueryField}s (see
     * {@link #toGraphQueryField}).
     */
    private record GraphSource(String alias, List<QueryField> columns) implements BoundSource {
    }

    /**
     * Mutable per-query binding state: the sources bound so far (in {@code from}/{@code join} order, keyed by
     * alias) and the {@code eval}-defined names introduced so far (order doesn't matter for lookup, but a
     * {@link LinkedHashSet} keeps error messages/iteration deterministic).
     */
    private static final class Scope {

        private final Map<String, BoundSource> sourcesByAlias = new LinkedHashMap<>();
        private final Set<String> evalFieldNames = new LinkedHashSet<>();

        /**
         * @return the query's only {@link BoundSource}.
         * @throws IllegalStateException if called when other than exactly one source is in scope. This is an
         *                                internal invariant, not a user-facing error: every caller guards the
         *                                call with its own {@code sourcesByAlias.size() == 1} check first (a
         *                                multi-source unqualified/param reference is handled without calling
         *                                this), so reaching the throw indicates a binder bug, not bad input.
         */
        private BoundSource onlySource() {
            if (sourcesByAlias.size() != 1) {
                throw new IllegalStateException(
                        "onlySource() called with " + sourcesByAlias.size() + " sources in scope");
            }
            return sourcesByAlias.values().iterator().next();
        }
    }
}
