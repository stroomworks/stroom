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

package stroom.query.planner.cypher;

import stroom.query.grammar.ast.cypher.AstComparisonOp;

import java.util.Objects;

/**
 * A {@code WHERE} comparison of two matched-element properties, e.g. {@code a.balance > b.balance}. Carried
 * separately from the {@code ExpressionOperator} {@code WHERE} tree (on {@link CompiledCypherPlan}) rather than as
 * an {@code ExpressionTerm}, because {@code ExpressionTerm}'s value is a single literal string with no slot for a
 * second field reference, and that IR is shared with the relational executor - see the implementation plan's
 * design decision (b). The graph executor evaluates these as an extra AND-combined per-row predicate.
 *
 * <p>Only ever built for a comparison that is a top-level conjunct of a {@code WHERE} clause (a field-vs-field
 * comparison nested inside {@code OR}/{@code NOT} is rejected at compile time). {@code op} is always one of the
 * six relational operators (=, &lt;&gt;, &lt;, &lt;=, &gt;, &gt;=) - string operators are rejected for
 * field-vs-field.</p>
 *
 * @param leftRowKey  never null; the {@code "variable.property"} row key of the left operand.
 * @param op          never null; a relational comparison operator.
 * @param rightRowKey never null; the {@code "variable.property"} row key of the right operand.
 */
public record FieldComparison(String leftRowKey, AstComparisonOp op, String rightRowKey) {

    public FieldComparison {
        Objects.requireNonNull(leftRowKey, "leftRowKey");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(rightRowKey, "rightRowKey");
    }
}
