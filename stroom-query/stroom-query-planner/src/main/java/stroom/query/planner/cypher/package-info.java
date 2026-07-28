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
 * Compiles a Cypher AST ({@code stroom.query.grammar.ast.cypher}) into the shared
 * {@link stroom.query.planner.logical.LogicalPlan} IR (see
 * Task PoC.3) - Cypher's analogue of
 * {@link stroom.query.planner.bind.Binder}.
 */
@NullMarked
package stroom.query.planner.cypher;

import org.jspecify.annotations.NullMarked;
