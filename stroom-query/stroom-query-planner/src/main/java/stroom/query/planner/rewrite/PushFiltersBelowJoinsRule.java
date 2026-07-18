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
import stroom.query.grammar.ast.AstPosition;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.Window;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * For a {@link Filter} directly above a {@link Join} whose {@code where}/{@code filter} predicate only
 * references one side's fields, pushes that predicate below the {@link Join} onto that side - so each side
 * scans as little as possible before the join runs (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 2.3).
 *
 * <p>{@link Filter#wherePredicate()} and {@link Filter#filterPredicate()} are considered independently - one
 * may push while the other doesn't (or they push to different sides). A predicate is pushed only when
 * <b>every</b> field reference within it is {@code alias.field}-qualified and every alias belongs to the same
 * side; an unqualified field reference, or references spanning both sides, leaves that predicate exactly where
 * it was (conservative - see {@code AutoWhereFilterSplitRule}'s Javadoc for the same reasoning applied here).
 * If both slots end up pushed, the enclosing {@code Filter} node itself is removed.</p>
 */
public final class PushFiltersBelowJoinsRule implements RewriteRule {

    @Override
    public LogicalPlan apply(final LogicalPlan plan) {
        return switch (plan) {
            case final Scan scan -> scan;
            case final Filter f -> rewriteFilter(f);
            case final Project p -> new Project(apply(p.input()), p.fields(), p.position());
            case final Join j -> new Join(apply(j.left()), apply(j.right()), j.joinType(), j.equiKeys(), j.position());
            case final Aggregate a -> new Aggregate(apply(a.input()), a.groupFields(), a.position());
            case final Having h -> new Having(apply(h.input()), h.predicate(), h.position());
            case final Window w -> new Window(
                    apply(w.input()), w.field(), w.windowSize(), w.advanceSize(), w.usingFunction(), w.position());
            case final Sort s -> new Sort(apply(s.input()), s.keys(), s.position());
            case final Limit l -> new Limit(apply(l.input()), l.values(), l.position());
        };
    }

    private LogicalPlan rewriteFilter(final Filter filter) {
        final LogicalPlan newInput = apply(filter.input());
        if (!(newInput instanceof final Join join)) {
            return new Filter(newInput, filter.wherePredicate(), filter.filterPredicate(), filter.position());
        }

        final Set<String> leftAliases = PlanRewriteUtil.collectScans(join.left()).keySet();
        final Set<String> rightAliases = PlanRewriteUtil.collectScans(join.right()).keySet();

        final Placement wherePlacement = classify(filter.wherePredicate(), leftAliases, rightAliases);
        final Placement filterPlacement = classify(filter.filterPredicate(), leftAliases, rightAliases);

        final LogicalPlan newLeft = pushOnto(
                join.left(),
                wherePlacement.side() == Side.LEFT ? wherePlacement.predicate() : null,
                filterPlacement.side() == Side.LEFT ? filterPlacement.predicate() : null,
                filter.position());
        final LogicalPlan newRight = pushOnto(
                join.right(),
                wherePlacement.side() == Side.RIGHT ? wherePlacement.predicate() : null,
                filterPlacement.side() == Side.RIGHT ? filterPlacement.predicate() : null,
                filter.position());
        final Join newJoin = new Join(newLeft, newRight, join.joinType(), join.equiKeys(), join.position());

        final ExpressionOperator remainingWhere =
                wherePlacement.side() == Side.NONE ? wherePlacement.predicate() : null;
        final ExpressionOperator remainingFilter =
                filterPlacement.side() == Side.NONE ? filterPlacement.predicate() : null;
        if (remainingWhere == null && remainingFilter == null) {
            return newJoin;
        }
        return new Filter(newJoin, remainingWhere, remainingFilter, filter.position());
    }

    private static Placement classify(
            final @Nullable ExpressionOperator predicate, final Set<String> leftAliases,
            final Set<String> rightAliases) {
        if (predicate == null) {
            return new Placement(Side.NONE, null);
        }
        final AliasCollector aliases = new AliasCollector();
        aliases.visit(predicate);
        if (aliases.hasUnqualified || aliases.aliases.isEmpty()) {
            return new Placement(Side.NONE, predicate);
        }
        if (leftAliases.containsAll(aliases.aliases)) {
            return new Placement(Side.LEFT, predicate);
        }
        if (rightAliases.containsAll(aliases.aliases)) {
            return new Placement(Side.RIGHT, predicate);
        }
        return new Placement(Side.NONE, predicate);
    }

    private static LogicalPlan pushOnto(
            final LogicalPlan target, final @Nullable ExpressionOperator wherePush,
            final @Nullable ExpressionOperator filterPush, final AstPosition position) {
        if (wherePush == null && filterPush == null) {
            return target;
        }
        if (target instanceof final Filter existing) {
            return new Filter(
                    existing.input(),
                    mergeOperators(existing.wherePredicate(), wherePush),
                    mergeOperators(existing.filterPredicate(), filterPush),
                    existing.position());
        }
        return new Filter(target, wherePush, filterPush, position);
    }

    private static @Nullable ExpressionOperator mergeOperators(
            final @Nullable ExpressionOperator existing, final @Nullable ExpressionOperator additional) {
        if (existing == null) {
            return additional;
        }
        if (additional == null) {
            return existing;
        }
        return ExpressionOperator.builder().op(Op.AND).children(List.of(existing, additional)).build();
    }

    private enum Side {
        LEFT,
        RIGHT,
        NONE
    }

    private record Placement(Side side, @Nullable ExpressionOperator predicate) {
    }

    /** Walks an {@link ExpressionItem} tree collecting every term's field alias, and whether any term had none. */
    private static final class AliasCollector {

        private final Set<String> aliases = new LinkedHashSet<>();
        private boolean hasUnqualified;

        void visit(final ExpressionItem item) {
            if (item instanceof final ExpressionTerm term) {
                final String alias = PlanRewriteUtil.aliasOf(term.getField());
                if (alias == null) {
                    hasUnqualified = true;
                } else {
                    aliases.add(alias);
                }
            } else if (item instanceof final ExpressionOperator operator) {
                final List<ExpressionItem> children = operator.getChildren() == null
                        ? List.of()
                        : operator.getChildren();
                children.forEach(this::visit);
            }
        }
    }
}
