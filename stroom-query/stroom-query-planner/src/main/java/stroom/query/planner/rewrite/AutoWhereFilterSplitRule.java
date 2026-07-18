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

package stroom.query.planner.rewrite;

import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.datasource.QueryField;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;
import stroom.query.planner.logical.Window;
import stroom.query.planner.port.FieldInfoSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Auto-derives the {@code where}/{@code filter} split: for a {@link Filter} node whose
 * {@link Filter#filterPredicate()} is {@code null} pre-rewrite (the query relied solely on {@code where}), splits
 * {@link Filter#wherePredicate()}'s top-level AND-conjuncts by index-eligibility into an index-pushed remainder
 * (stays {@code wherePredicate}) and a non-eligible remainder (becomes {@code filterPredicate}) - see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 2.3.
 *
 * <p><b>Invariant</b>: if the query already has an explicit {@code filter} clause
 * ({@code filterPredicate != null} before this rule runs), it is a no-op - the query rewrites to itself.</p>
 *
 * <p>A term is index-eligible when its field is {@link QueryField#queryable()} <b>and</b> its condition is in
 * that field's {@link QueryField#getConditionSet()}. Anything this rule cannot resolve with confidence - an
 * unknown field, a missing {@code ConditionSet}, or a predicate whose top-level operator isn't {@code AND}
 * (an {@code OR}/{@code NOT} can't be partially pushed without evaluating all its branches at the datasource) -
 * is treated conservatively as <i>not</i> eligible, never as eligible: a wrong "leave it in {@code where}" guess
 * changes nothing (still safe, if suboptimal); a wrong "push it" guess could send an unsupported condition to a
 * datasource that rejects it.</p>
 */
public final class AutoWhereFilterSplitRule implements RewriteRule {

    private final FieldInfoSource fieldInfoSource;

    public AutoWhereFilterSplitRule(final FieldInfoSource fieldInfoSource) {
        this.fieldInfoSource = Objects.requireNonNull(fieldInfoSource, "fieldInfoSource");
    }

    @Override
    public LogicalPlan apply(final LogicalPlan plan) {
        return switch (plan) {
            case final Scan scan -> scan;
            case final Filter f -> splitFilter(f);
            case final Project p -> new Project(apply(p.input()), p.fields(), p.position());
            case final Join j -> new Join(apply(j.left()), apply(j.right()), j.joinType(), j.equiKeys(), j.position());
            case final Aggregate a -> new Aggregate(apply(a.input()), a.groupFields(), a.position());
            case final Having h -> new Having(apply(h.input()), h.predicate(), h.position());
            case final Window w -> new Window(
                    apply(w.input()), w.field(), w.windowSize(), w.advanceSize(), w.usingFunction(), w.position());
            case final Sort s -> new Sort(apply(s.input()), s.keys(), s.position());
            case final Limit l -> new Limit(apply(l.input()), l.values(), l.position());
            // Graph nodes (Task PoC.2): a where/filter split is a relational Scan/QueryField concern this rule
            // resolves via fieldInfoSource; a NodeScan's property anchor and Expand/VarLengthExpand have no
            // equivalent split to perform, so leave them unchanged, recursing through the wrappers.
            case final NodeScan ns -> ns;
            case final Expand e -> new Expand(apply(e.input()), e.edgeType(), e.direction(), e.targetVariable(),
                    e.position());
            case final VarLengthExpand vle -> new VarLengthExpand(apply(vle.input()), vle.edgeType(),
                    vle.direction(), vle.minHops(), vle.maxHops(), vle.targetVariable(), vle.position());
        };
    }

    private LogicalPlan splitFilter(final Filter filter) {
        final LogicalPlan newInput = apply(filter.input());

        if (filter.filterPredicate() != null || filter.wherePredicate() == null) {
            return new Filter(newInput, filter.wherePredicate(), filter.filterPredicate(), filter.position());
        }

        final ExpressionOperator where = filter.wherePredicate();
        final Op op = where.getOp() == null ? Op.AND : where.getOp();
        if (op != Op.AND) {
            return new Filter(newInput, where, null, filter.position());
        }

        final List<ExpressionItem> conjuncts = where.getChildren() == null ? List.of() : where.getChildren();
        final Map<String, Scan> scans = PlanRewriteUtil.collectScans(newInput);

        final List<ExpressionItem> eligible = new ArrayList<>();
        final List<ExpressionItem> ineligible = new ArrayList<>();
        for (final ExpressionItem conjunct : conjuncts) {
            if (conjunct instanceof final ExpressionTerm term && isIndexEligible(term, scans)) {
                eligible.add(term);
            } else {
                ineligible.add(conjunct);
            }
        }

        if (ineligible.isEmpty()) {
            return new Filter(newInput, where, null, filter.position());
        }
        if (eligible.isEmpty()) {
            return new Filter(newInput, null, where, filter.position());
        }
        return new Filter(newInput, asOperator(eligible), asOperator(ineligible), filter.position());
    }

    private boolean isIndexEligible(final ExpressionTerm term, final Map<String, Scan> scans) {
        final Optional<QueryField> field = lookupField(term.getField(), scans);
        if (field.isEmpty()) {
            return false;
        }
        final QueryField queryField = field.get();
        return queryField.queryable()
               && queryField.getConditionSet() != null
               && queryField.getConditionSet().supportsCondition(term.getCondition());
    }

    private Optional<QueryField> lookupField(final String rawField, final Map<String, Scan> scans) {
        final String alias = PlanRewriteUtil.aliasOf(rawField);
        if (alias != null) {
            final Scan scan = scans.get(alias);
            if (scan == null) {
                return Optional.empty();
            }
            return findByName(fieldInfoSource.getFields(scan.dataSourceName()), rawField.substring(alias.length() + 1));
        }
        for (final Scan scan : scans.values()) {
            final Optional<QueryField> found = findByName(fieldInfoSource.getFields(scan.dataSourceName()), rawField);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<QueryField> findByName(final List<QueryField> fields, final String name) {
        return fields.stream().filter(f -> f.getFldName().equals(name)).findFirst();
    }

    private static ExpressionOperator asOperator(final List<ExpressionItem> items) {
        if (items.size() == 1 && items.getFirst() instanceof final ExpressionOperator operator) {
            return operator;
        }
        return ExpressionOperator.builder().op(Op.AND).children(items).build();
    }
}
