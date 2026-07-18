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

/**
 * Rewrite rules (see {@code docs/query-optimiser-implementation-plan.md}, Task 2.3): pure
 * {@code LogicalPlan -> LogicalPlan} transformations that improve physical placement without changing results,
 * composed by {@link stroom.query.planner.rewrite.RewritePipeline} in a fixed order.
 */
@NullMarked
package stroom.query.planner.rewrite;

import org.jspecify.annotations.NullMarked;
