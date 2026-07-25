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

package stroom.query.grammar.ast.cypher;

import org.jspecify.annotations.Nullable;

/**
 * One {@code WHEN ... THEN ...} arm of an {@link AstCaseExpr}. Exactly one of {@link #testValue} /
 * {@link #testCondition} is non-null, matching the enclosing CASE form:
 * <ul>
 *   <li><b>simple</b> ({@link AstCaseExpr#input()} != null): {@link #testValue} is a value expression compared for
 *       equality against the CASE input.</li>
 *   <li><b>searched</b> ({@link AstCaseExpr#input()} == null): {@link #testCondition} is a boolean predicate.</li>
 * </ul>
 * {@link #result} is the value produced when the arm matches.
 */
public record AstCaseWhen(
        @Nullable AstExpression testValue,
        @Nullable AstBooleanExpr testCondition,
        AstExpression result) {

}
