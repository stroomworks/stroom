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
 * A list literal, e.g. {@code ['Powell', 'Smith']}, used as the right-hand side of an {@code IN} predicate. The
 * grammar admits a list of arbitrary {@link AstValue}s; {@code CypherToLogicalPlan} restricts the elements to
 * scalar (string/number/boolean) literals when lowering an {@code IN} term.
 *
 * @param elements never null; defensively copied. May be empty (an empty {@code IN []} list matches nothing).
 * @param position never null.
 */
public record AstListValue(List<AstValue> elements, AstPosition position) implements AstValue {

    public AstListValue {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(position, "position");
        elements = List.copyOf(elements);
    }
}
