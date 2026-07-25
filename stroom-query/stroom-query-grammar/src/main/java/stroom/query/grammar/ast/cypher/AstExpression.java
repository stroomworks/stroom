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

/**
 * A value-producing expression, shared by {@code RETURN}/{@code WITH} items, {@code ORDER BY} items, and
 * {@code WHERE} comparison operands (e.g. {@code a.id}, a bare variable, a literal, or an aggregate call). Nothing
 * in this grammar/AST layer restricts which of these may appear in which position (e.g. an aggregate inside
 * {@code WHERE}) - that is a semantic concern for {@code CypherToLogicalPlan} (Task PoC.3), not a parse concern.
 */
public sealed interface AstExpression
        permits AstPropertyAccessExpr, AstVariableExpr, AstLiteralExpr, AstAggregateExpr, AstDiffAccessorExpr,
        AstArithmeticExpr {

    AstPosition position();
}
