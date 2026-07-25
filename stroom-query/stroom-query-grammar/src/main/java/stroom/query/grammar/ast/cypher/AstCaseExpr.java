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

import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A CASE value expression (openCypher's two forms):
 * <ul>
 *   <li><b>simple</b> - {@link #input} is non-null; each {@link AstCaseWhen#testValue()} is compared for equality
 *       against it (e.g. {@code CASE a.status WHEN 1 THEN 'on' ELSE 'off' END}).</li>
 *   <li><b>searched</b> - {@link #input} is null; each {@link AstCaseWhen#testCondition()} is a boolean predicate
 *       (e.g. {@code CASE WHEN a.balance > 0 THEN 'credit' ELSE 'debit' END}).</li>
 * </ul>
 * {@link #elseResult} is the value produced when no arm matches; when absent (null) an unmatched CASE yields null.
 */
public record AstCaseExpr(
        @Nullable AstExpression input,
        List<AstCaseWhen> whens,
        @Nullable AstExpression elseResult,
        AstPosition position) implements AstExpression {

}
