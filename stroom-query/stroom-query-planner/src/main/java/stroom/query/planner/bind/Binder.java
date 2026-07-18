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
import stroom.query.grammar.ast.AstTerm;
import stroom.query.grammar.ast.AstToken;
import stroom.query.grammar.ast.AstValue;
import stroom.query.grammar.ast.AstWhereClause;
import stroom.query.grammar.ast.AstWindowClause;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.EquiKey;
import stroom.query.planner.logical.Filter;
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
            plan = new Filter(plan, wherePredicate, filterPredicate, filterPosition);
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
        scope.scansByAlias.put(primaryAlias, primaryScan);

        LogicalPlan plan = primaryScan;
        for (final AstJoin join : from.joins()) {
            final String joinAlias = aliasOrSourceName(join.alias(), join.source());
            if (scope.scansByAlias.containsKey(joinAlias)) {
                throw new BindException("Duplicate source alias '" + joinAlias + "'", join.position());
            }
            final Scan joinScan = new Scan(joinAlias, join.source().unescapedText(), join.position());
            scope.scansByAlias.put(joinAlias, joinScan);

            final List<EquiKey> equiKeys = new ArrayList<>(join.conditions().size());
            for (final AstJoinCondition condition : join.conditions()) {
                final QualifiedField left = resolveQualifiedFieldStrict(condition.left(), scope);
                final QualifiedField right = resolveQualifiedFieldStrict(condition.right(), scope);
                validateDomainTypeCompatibility(left, right, scope, condition.position());
                equiKeys.add(new EquiKey(left, right));
            }
            final JoinType joinType = join.joinType() == AstJoin.JoinType.LEFT ? JoinType.LEFT : JoinType.INNER;
            plan = new Join(plan, joinScan, joinType, equiKeys, join.position());
        }
        return plan;
    }

    private static String aliasOrSourceName(final @Nullable AstToken alias, final AstToken source) {
        return alias != null ? alias.unescapedText() : source.unescapedText();
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
                if (scope.scansByAlias.containsKey(possibleAlias)) {
                    final String fieldName = text.substring(dot + 1);
                    if (findQueryField(scope.scansByAlias.get(possibleAlias), fieldName).isEmpty()) {
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
        for (final Map.Entry<String, Scan> entry : scope.scansByAlias.entrySet()) {
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
        return new QualifiedField(scope.scansByAlias.size() > 1 ? matches.getFirst() : null, text);
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
        final Scan scan = scope.scansByAlias.get(alias);
        if (scan == null) {
            throw new BindException("Unknown alias '" + alias + "'", token.position());
        }
        if (findQueryField(scan, fieldName).isEmpty()) {
            throw new BindException("Unknown field '" + fieldName + "' on '" + alias + "'", token.position());
        }
        return new QualifiedField(alias, fieldName);
    }

    private void validateCondition(
            final QualifiedField field, final Condition condition, final AstPosition position, final Scope scope) {
        if (field.alias() == null && scope.evalFieldNames.contains(field.field())) {
            return;
        }
        final Scan scan = field.alias() != null ? scope.scansByAlias.get(field.alias()) : scope.onlyScan();
        final Optional<QueryField> queryField = findQueryField(scan, field.field());
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
        final Optional<QueryField> leftField = findQueryField(scope.scansByAlias.get(left.alias()), left.field());
        final Optional<QueryField> rightField = findQueryField(scope.scansByAlias.get(right.alias()), right.field());
        if (leftField.isEmpty() || rightField.isEmpty()) {
            return;
        }
        final String leftDomainType = leftField.get().getDomainType();
        final String rightDomainType = rightField.get().getDomainType();
        if (leftDomainType == null || rightDomainType == null) {
            // Advisory only - degrade gracefully when either side lacks a domain type, per the design doc.
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

    private Optional<QueryField> findQueryField(final Scan scan, final String fieldName) {
        return fieldInfoSource.getFields(scan.dataSourceName()).stream()
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
     * Mutable per-query binding state: the sources bound so far (in {@code from}/{@code join} order, keyed by
     * alias) and the {@code eval}-defined names introduced so far (order doesn't matter for lookup, but a
     * {@link LinkedHashSet} keeps error messages/iteration deterministic).
     */
    private static final class Scope {

        private final Map<String, Scan> scansByAlias = new LinkedHashMap<>();
        private final Set<String> evalFieldNames = new LinkedHashSet<>();

        /**
         * @return the query's only {@link Scan}.
         * @throws IllegalStateException if called when more than one source is in scope - callers must only
         *                                call this after establishing (via {@link #resolveField}'s own logic)
         *                                that the reference is unqualified, which only happens for a
         *                                single-source query or an eval field (checked separately).
         */
        private Scan onlyScan() {
            if (scansByAlias.size() != 1) {
                throw new IllegalStateException(
                        "onlyScan() called with " + scansByAlias.size() + " sources in scope");
            }
            return scansByAlias.values().iterator().next();
        }
    }
}
