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
 * {@code window <field> by <duration> [advance <duration>] [using <function>]}.
 *
 * @param field         never null; the time field the window is defined over.
 * @param windowSize    never null; a {@code DURATION} token's raw text (e.g. {@code "1h"}).
 * @param advanceSize   nullable; set iff {@code advance <duration>} was present; defaults to
 *                      {@link #windowSize()} when absent (matching legacy's {@code HoppingWindow} default).
 * @param usingFunction nullable; set iff {@code using <function>} was present.
 * @param position      never null.
 */
public record AstWindowClause(AstToken field, AstToken windowSize, @Nullable AstToken advanceSize,
                              @Nullable AstToken usingFunction, AstPosition position) implements AstClause {

    public AstWindowClause {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(windowSize, "windowSize");
        Objects.requireNonNull(position, "position");
    }
}
