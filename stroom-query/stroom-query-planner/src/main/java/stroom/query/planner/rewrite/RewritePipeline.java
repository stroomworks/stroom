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

import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.port.FieldInfoSource;

import java.util.List;
import java.util.Objects;

/**
 * Runs a fixed, ordered sequence of {@link RewriteRule}s over a {@link LogicalPlan} (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 2.3).
 */
public final class RewritePipeline {

    private final List<RewriteRule> rules;

    public RewritePipeline(final List<RewriteRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    /**
     * The standard v1 pipeline, in the order each rule needs the previous one's output to be most effective:
     * structural cleanup ({@link ConstantFoldingRule}, {@link RedundantTermPruningRule}) first, so the predicate
     * {@link AutoWhereFilterSplitRule} partitions is already in its simplest shape; then the split itself; then
     * {@link PushFiltersBelowJoinsRule}, which relocates whichever predicates the split just placed in
     * {@code where}/{@code filter}.
     *
     * @param fieldInfoSource never null; passed through to {@link AutoWhereFilterSplitRule}.
     * @return never null.
     */
    public static RewritePipeline standard(final FieldInfoSource fieldInfoSource) {
        return new RewritePipeline(List.of(
                new ConstantFoldingRule(),
                new RedundantTermPruningRule(),
                new AutoWhereFilterSplitRule(fieldInfoSource),
                new PushFiltersBelowJoinsRule()));
    }

    /**
     * @param plan never null.
     * @return never null; {@code plan} with every rule applied in sequence, each consuming the previous rule's
     *         output.
     */
    public LogicalPlan run(final LogicalPlan plan) {
        Objects.requireNonNull(plan, "plan");
        LogicalPlan result = plan;
        for (final RewriteRule rule : rules) {
            result = rule.apply(result);
        }
        return result;
    }
}
