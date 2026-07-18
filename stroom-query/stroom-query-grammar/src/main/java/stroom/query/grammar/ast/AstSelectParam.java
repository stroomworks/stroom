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
 * A {@code ${param}} reference used as a select column, e.g. {@code select ${Stream Id}}. Legacy also treats a
 * param whose unescaped text contains {@code *} as a starred-field expansion (see
 * {@code SearchRequestFactory.addColumn}), a Task 1.4 concern.
 *
 * @param field    never null; {@link AstToken#kind()} is always {@link AstToken.Kind#PARAM}.
 * @param alias    nullable.
 * @param position never null.
 */
public record AstSelectParam(AstToken field, @Nullable AstToken alias, AstPosition position)
        implements AstSelectItem {

    public AstSelectParam {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(position, "position");
    }
}
