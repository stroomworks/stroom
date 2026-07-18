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

import java.util.List;
import java.util.Objects;

/**
 * {@code field in (value, value, ...)}.
 *
 * @param field    never null.
 * @param values   never null; never empty (the grammar requires at least one value).
 * @param position never null.
 */
public record AstInTerm(AstToken field, List<AstValue> values, AstPosition position) implements AstTerm {

    public AstInTerm {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(position, "position");
        values = List.copyOf(values);
    }
}
