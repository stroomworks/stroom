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
 * One {@code left = right} equality within a join's {@code on} clause. Reserved for Phase 6 - see {@link AstJoin}.
 * Each side's text may contain a {@code qualifier.field} dotted reference; legacy's bareword character class
 * already permits {@code .} (see {@code StroomQL.g4}'s file header), so splitting on it is a Phase 6 binder
 * concern, not something this node does.
 *
 * @param left     never null.
 * @param right    never null.
 * @param position never null.
 */
public record AstJoinCondition(AstToken left, AstToken right, AstPosition position) {

    public AstJoinCondition {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(position, "position");
    }
}
