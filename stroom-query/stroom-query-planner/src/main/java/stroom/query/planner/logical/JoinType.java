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

package stroom.query.planner.logical;

/**
 * Mirrors {@code stroom.query.grammar.ast.AstJoin.JoinType} (the grammar's reserved {@code [left] join} syntax -
 * see {@code StroomQL.g4}'s file header) at the logical-plan level - same two values, same names, so the binder's
 * mapping between them is a trivial 1:1 translation, not a design decision in its own right.
 */
public enum JoinType {
    LEFT,
    INNER
}
