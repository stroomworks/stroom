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

import java.util.Objects;

/**
 * {@code before(a.prop)} / {@code after(a.prop)} - a {@code DIFF}-only accessor naming a property value in the
 * baseline ({@code t1}) or comparison ({@code t2}) snapshot (
 * &sect;4.3). Only valid inside a {@code DIFF} query; {@code CypherToLogicalPlan} rejects it elsewhere. The
 * argument is always a {@link AstPropertyAccessExpr} (a whole-element form like {@code before(a)} is deferred with
 * {@code RETURN GRAPH}).
 *
 * @param side     never null - which snapshot to read.
 * @param target   never null - the property access whose value in that snapshot is wanted.
 * @param position never null.
 */
public record AstDiffAccessorExpr(
        AstDiffSide side,
        AstPropertyAccessExpr target,
        AstPosition position) implements AstExpression {

    public AstDiffAccessorExpr {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(position, "position");
    }
}
