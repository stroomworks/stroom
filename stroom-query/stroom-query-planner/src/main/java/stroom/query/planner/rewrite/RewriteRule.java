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

/**
 * A pure {@code plan -> plan} transformation that improves physical placement without changing results (see
 * Task 2.3). Implementations must be side-effect-free and
 * must not mutate their input (the {@link LogicalPlan} record tree is immutable, so this falls out naturally).
 */
@FunctionalInterface
public interface RewriteRule {

    /**
     * @param plan never null.
     * @return never null; a plan producing the same results as {@code plan}, possibly restructured. Returns
     *         {@code plan} itself (or an equal tree) when the rule does not apply.
     */
    LogicalPlan apply(LogicalPlan plan);
}
