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

/**
 * The direction an edge pattern was written in - which arrowhead (if either) was present. Mirrors the planner's
 * {@code stroom.query.planner.logical.Expand.Direction} (Task PoC.2); kept as a separate AST-level enum since
 * this package must not depend on {@code stroom-query-planner}.
 */
public enum AstEdgeDirection {
    OUT,
    IN,
    BOTH
}
