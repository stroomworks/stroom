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

import java.util.Objects;

/**
 * {@code having <expr>} - a post-aggregation filter. Legacy auto-adds any field referenced only here as a
 * hidden column (see {@code SearchRequestFactory}'s {@code additionalFields}/{@code inHaving}) - a Task 1.4
 * concern, not represented in this node.
 *
 * @param expr     never null.
 * @param position never null.
 */
public record AstHavingClause(AstOrExpr expr, AstPosition position) implements AstClause {

    public AstHavingClause {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(position, "position");
    }
}
