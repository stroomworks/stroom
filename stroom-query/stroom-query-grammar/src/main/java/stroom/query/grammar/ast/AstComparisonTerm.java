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
 * {@code field <cond> value} - e.g. {@code UserId = user5}.
 *
 * @param field    never null.
 * @param cond     never null.
 * @param value    never null.
 * @param position never null.
 */
public record AstComparisonTerm(AstToken field, AstComparisonCond cond, AstValue value,
                                AstPosition position) implements AstTerm {

    public AstComparisonTerm {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(cond, "cond");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(position, "position");
    }
}
