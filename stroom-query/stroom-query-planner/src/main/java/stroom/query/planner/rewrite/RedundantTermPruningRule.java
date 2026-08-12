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
import stroom.query.planner.logical.LogicalPlan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drops an {@link ExpressionTerm} subsumed by an identical one in the same AND-conjunction (e.g. {@code x=1 AND
 * x=1}), Task 2.3.
 *
 * <p>Scoped deliberately to "the same AND-conjunction": this rule flattens through nested {@code Op.AND} nodes
 * only (matching the binder's own left-nested pairwise fold - see {@code Binder}'s Javadoc) and de-duplicates
 * the resulting flat list of direct term children. It does not look inside an {@code OR}/{@code NOT} sub-tree
 * for duplicates against a sibling outside it - <code>(x=1 OR y=2) AND x=1</code> must not become
 * <code>(x=1 OR y=2)</code> by dropping the outer {@code x=1}; that would change which rows match whenever
 * {@code y=2} is true and {@code x=1} is false. Each sub-tree is still pruned independently for duplicates
 * <i>within</i> itself.</p>
 *
 * <p><b>A disabled item is opaque</b> (Task 8.4): {@link ExpressionItem#enabled()} marks an item the evaluator
 * ignores. A disabled sub-tree is returned unchanged (never recursed into), a disabled nested {@code AND} is
 * <i>not</i> flattened into its enabled parent (that would hoist - and so silently re-enable - children the
 * caller switched off), and disabled terms neither participate in de-duplication nor get hoisted by the
 * single-survivor collapse.</p>
 */
public final class RedundantTermPruningRule implements RewriteRule {

    @Override
    public LogicalPlan apply(final LogicalPlan plan) {
        return PlanRewriteUtil.mapPredicates(plan, this::prune);
    }

    private ExpressionOperator prune(final ExpressionOperator operator) {
        return asOperator(pruneItem(operator));
    }

    private ExpressionItem pruneItem(final ExpressionItem item) {
        if (!(item instanceof final ExpressionOperator operator)) {
            return item;
        }
        if (!operator.enabled()) {
            // A disabled sub-tree is opaque (Task 8.4) - the evaluator ignores it, so no duplicate inside it is
            // redundant against anything. Returned untouched, children and all.
            return operator;
        }

        final Op op = operator.getOp() == null ? Op.AND : operator.getOp();
        if (op == Op.AND) {
            final List<ExpressionItem> flat = new ArrayList<>();
            flattenAnd(operator, flat);
            final List<ExpressionItem> pruned = flat.stream().map(this::pruneItem).collect(Collectors.toList());

            final Set<ExpressionTerm> seenTerms = new LinkedHashSet<>();
            final List<ExpressionItem> deduped = new ArrayList<>(pruned.size());
            for (final ExpressionItem candidate : pruned) {
                if (candidate instanceof final ExpressionTerm term && term.enabled()) {
                    if (seenTerms.add(term)) {
                        deduped.add(term);
                    }
                } else {
                    // A non-term child (an already-pruned OR/NOT sub-tree, or a disabled item - opaque, Task
                    // 8.4) - kept as-is; de-duplication only applies to identical enabled leaf terms, not to
                    // structurally-equal sub-expressions.
                    deduped.add(candidate);
                }
            }
            if (deduped.size() == 1 && deduped.getFirst().enabled()) {
                // Unwrap a single enabled survivor. A disabled survivor stays wrapped: hoisting it would swap
                // "enabled AND whose only child is ignored" for "a bare disabled item", losing the caller's
                // structure for no gain.
                return deduped.getFirst();
            }
            return rebuild(operator, Op.AND, deduped);
        }

        final List<ExpressionItem> children = operator.getChildren() == null
                ? List.of()
                : operator.getChildren();
        return rebuild(operator, op, children.stream().map(this::pruneItem).collect(Collectors.toList()));
    }

    /** Collects the direct children of {@code operator}'s AND-chain, recursing only through further
     *  <i>enabled</i> {@code Op.AND} nodes - anything else (a term, an OR/NOT sub-tree, or a <i>disabled</i>
     *  {@code AND}, Task 8.4) is added as a single opaque item. Flattening a disabled {@code AND} would hoist
     *  children the evaluator currently ignores into the enabled parent, silently re-enabling them. */
    private static void flattenAnd(final ExpressionOperator operator, final List<ExpressionItem> out) {
        final List<ExpressionItem> children = operator.getChildren() == null
                ? List.of()
                : operator.getChildren();
        for (final ExpressionItem child : children) {
            if (child instanceof final ExpressionOperator childOperator
                    && childOperator.enabled()
                    && (childOperator.getOp() == null ? Op.AND : childOperator.getOp()) == Op.AND) {
                flattenAnd(childOperator, out);
            } else {
                out.add(child);
            }
        }
    }

    private static ExpressionOperator rebuild(
            final ExpressionOperator original, final Op op, final List<ExpressionItem> children) {
        final ExpressionOperator.Builder builder = ExpressionOperator.builder().op(op).children(children);
        if (original.getEnabled() != null) {
            builder.enabled(original.getEnabled());
        }
        return builder.build();
    }

    private static ExpressionOperator asOperator(final ExpressionItem item) {
        if (item instanceof final ExpressionOperator operator) {
            return operator;
        }
        return ExpressionOperator.builder().children(List.of(item)).build();
    }
}
