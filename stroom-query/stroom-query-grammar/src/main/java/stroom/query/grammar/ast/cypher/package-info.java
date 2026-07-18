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
 * The typed, immutable AST built from a {@code Cypher.g4} parse tree by
 * {@link stroom.query.grammar.parse.CypherQueryParser} (see {@code docs/temporal-cypher-graph-implementation-plan.md},
 * Task PoC.1), decoupling downstream code (Task PoC.3's {@code CypherToLogicalPlan}) from ANTLR types entirely.
 * A separate sub-package from {@link stroom.query.grammar.ast} avoids record-name collisions with StroomQL's AST;
 * {@link stroom.query.grammar.ast.AstPosition} is reused directly since it is grammar-agnostic.
 */
@NullMarked
package stroom.query.grammar.ast.cypher;

import org.jspecify.annotations.NullMarked;
