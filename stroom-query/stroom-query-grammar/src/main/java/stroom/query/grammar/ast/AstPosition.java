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

package stroom.query.grammar.ast;

/**
 * A 1-based line and 0-based column (matching ANTLR's own convention) identifying where an AST node started in
 * the original query text, for precise error reporting (see Task 1.3).
 *
 * @param line   1-based source line.
 * @param column 0-based column offset within {@link #line}.
 */
public record AstPosition(int line, int column) {
}
