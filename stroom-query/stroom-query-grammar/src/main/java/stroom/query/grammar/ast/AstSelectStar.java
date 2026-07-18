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

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * {@code select *} - expands to every field of the query's datasource at bind time (see
 * {@code SearchRequestFactory.expandStarredField}, a Task 1.4 concern).
 *
 * @param alias    nullable (an alias on {@code *} is unusual but legacy's grammar does not forbid it).
 * @param position never null.
 */
public record AstSelectStar(@Nullable AstToken alias, AstPosition position) implements AstSelectItem {

    public AstSelectStar {
        Objects.requireNonNull(position, "position");
    }
}
