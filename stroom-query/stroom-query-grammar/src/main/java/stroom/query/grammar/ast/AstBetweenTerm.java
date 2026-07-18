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
 * {@code field between lower and upper}.
 *
 * @param field    never null.
 * @param lower    never null.
 * @param upper    never null.
 * @param position never null.
 */
public record AstBetweenTerm(AstToken field, AstValue lower, AstValue upper,
                             AstPosition position) implements AstTerm {

    public AstBetweenTerm {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
        Objects.requireNonNull(position, "position");
    }
}
