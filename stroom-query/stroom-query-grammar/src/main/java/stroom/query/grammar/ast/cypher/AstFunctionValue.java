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

import java.util.List;
import java.util.Objects;

/**
 * A function-call literal, e.g. {@code datetime('2026-07-01T09:00:00Z')} or {@code duration('PT1H')} - the
 * temporal clause's instant/duration/bound values are always this shape (see the design doc's worked example).
 *
 * @param name      never null; the function name.
 * @param arguments never null; possibly empty; in source order.
 * @param position  never null.
 */
public record AstFunctionValue(String name, List<AstValue> arguments, AstPosition position) implements AstValue {

    public AstFunctionValue {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(position, "position");
        arguments = List.copyOf(arguments);
    }
}
