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
 * A scalar parameter reference, e.g. {@code $deviceId}. Only scalar parameters are in the locked v1 subset -
 * parameter maps/destructuring are out of scope (see {@code Cypher.g4}'s file header).
 *
 * @param name     never null; the parameter name without its leading {@code $}.
 * @param position never null.
 */
public record AstParameterValue(String name, AstPosition position) implements AstValue {

    public AstParameterValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(position, "position");
    }
}
