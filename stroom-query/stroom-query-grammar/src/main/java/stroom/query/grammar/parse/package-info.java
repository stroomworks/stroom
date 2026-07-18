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
 * The single entry point ({@code StroomQlParser}) that wires the ANTLR lexer/parser, precise syntax-error
 * reporting, and {@link stroom.query.grammar.ast.AstBuilder} together into one {@code text -> AstQuery} call (see
 * {@code docs/query-optimiser-implementation-plan.md}, Task 1.3).
 */
@NullMarked
package stroom.query.grammar.parse;

import org.jspecify.annotations.NullMarked;
