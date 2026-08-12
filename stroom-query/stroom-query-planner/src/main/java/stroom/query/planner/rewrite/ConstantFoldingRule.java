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
import stroom.query.planner.logical.LogicalPlan;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Structural simplification of {@code where}/{@code filter}/{@code having} predicates - purely mechanical, no
 * field metadata or legacy oracle needed (legacy does nothing like this today; see
 * Task 2.3).
 *
 * <p>StroomQL terms have no boolean-literal ({@code true}/{@code false}) equivalent, so this rule folds
 * structural redundancy rather than literal constants: double negation (<code>NOT(NOT(x))</code> &rarr;
 * {@code x}) and single-child {@code AND}/{@code OR} wrapping (<code>AND(x)</code>/<code>OR(x)</code> &rarr;
 * {@code x}) - both provably equivalent rewrites of the {@link ExpressionOperator} tree the binder built,
 * independent of what any term actually tests.</p>
 *
 * <p><b>A disabled item is opaque</b> (Task 8.4): {@link ExpressionItem#enabled()} marks an item the evaluator
 * ignores, so the equivalences above only hold between <i>enabled</i> items. A disabled sub-tree is returned
 * unchanged (never recursed into), and a collapse is only performed when the operator being removed <i>and</i>
 * the child being hoisted are both enabled - collapsing through a disabled operator, or hoisting a disabled
 * child into its grandparent, would change which items the evaluator consults and could silently re-enable a
 * predicate the caller switched off.</p>
 */
public final class ConstantFoldingRule implements RewriteRule {

    @Override
    public LogicalPlan apply(final LogicalPlan plan) {
        return PlanRewriteUtil.mapPredicates(plan, this::fold);
    }

    private ExpressionOperator fold(final ExpressionOperator operator) {
        return asOperator(foldItem(operator));
    }

    private ExpressionItem foldItem(final ExpressionItem item) {
        if (!(item instanceof final ExpressionOperator operator)) {
            return item;
        }
        if (!operator.enabled()) {
            // A disabled sub-tree is opaque (Task 8.4) - the evaluator ignores it, so there is nothing here the
            // structural equivalences apply to. Returned untouched, children and all.
            return operator;
        }

        final Op op = operator.getOp() == null ? Op.AND : operator.getOp();
        final List<ExpressionItem> rawChildren = operator.getChildren() == null
                ? List.of()
                : operator.getChildren();
        final List<ExpressionItem> foldedChildren = rawChildren.stream()
                .map(this::foldItem)
                .collect(Collectors.toList());

        if (op == Op.NOT && foldedChildren.size() == 1) {
            final ExpressionItem inner = foldedChildren.getFirst();
            if (inner instanceof final ExpressionOperator innerOperator
                    && innerOperator.enabled()
                    && (innerOperator.getOp() == null ? Op.AND : innerOperator.getOp()) == Op.NOT) {
                final List<ExpressionItem> innerChildren = innerOperator.getChildren() == null
                        ? List.of()
                        : innerOperator.getChildren();
                if (innerChildren.size() == 1 && innerChildren.getFirst().enabled()) {
                    // NOT(NOT(x)) -> x, only when both NOTs and x itself are enabled: a disabled inner NOT or a
                    // disabled x means the evaluator does not see a double negation at all, so hoisting x would
                    // change (or re-enable) what is evaluated.
                    return innerChildren.getFirst();
                }
            }
        }

        if ((op == Op.AND || op == Op.OR) && foldedChildren.size() == 1 && foldedChildren.getFirst().enabled()) {
            // AND(x) -> x, OR(x) -> x - only for an enabled x; hoisting a disabled child would splice an item
            // the evaluator ignores into a position where the caller's intent (a disabled predicate inside an
            // enabled wrapper) is no longer represented.
            return foldedChildren.getFirst();
        }

        return rebuild(operator, op, foldedChildren);
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
