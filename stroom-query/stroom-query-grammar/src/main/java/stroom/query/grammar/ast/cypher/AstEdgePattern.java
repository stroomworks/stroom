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
import java.util.Objects;

/**
 * {@code -[variable:type*min..max {props}]->} (or {@code <-...-} / {@code -...-} for direction) - at most one
 * relationship type per pattern (see {@code Cypher.g4}'s file header for why: multi-type patterns are a real,
 * deliberately deferred feature, not a grammar oversight).
 *
 * @param variable  nullable - most edges in a query are never referenced again.
 * @param type      nullable - an untyped edge pattern (bare {@code -->}) matches any edge type.
 * @param direction never null.
 * @param varLength nullable - absent means a single hop.
 * @param properties never null; possibly empty; in source order.
 * @param position  never null.
 */
public record AstEdgePattern(
        @Nullable String variable,
        @Nullable String type,
        AstEdgeDirection direction,
        @Nullable AstVarLength varLength,
        List<AstPropertyKeyValue> properties,
        AstPosition position) {

    public AstEdgePattern {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(position, "position");
        properties = List.copyOf(properties);
    }
}
