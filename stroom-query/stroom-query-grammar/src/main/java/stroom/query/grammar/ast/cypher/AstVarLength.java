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

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * {@code *min..max} on an edge pattern - a bounded variable-length path. {@code Cypher.g4} makes {@code max}
 * mandatory at the grammar level (unbounded {@code *}/{@code *n..} is out of the locked v1 subset and does not
 * parse at all), so this record can never represent an unbounded path.
 *
 * @param min      the minimum hop count, or {@code null} if omitted (Cypher's own default is 1).
 * @param max      the maximum hop count; always present.
 * @param position never null.
 */
public record AstVarLength(@Nullable Integer min, int max, AstPosition position) {

    /**
     * <b>Preconditions:</b> {@code max} must be &ge; 1, and &ge; {@code min} when {@code min} is present.
     */
    public AstVarLength {
        Objects.requireNonNull(position, "position");
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1, was " + max);
        }
        if (min != null && min > max) {
            throw new IllegalArgumentException("min (" + min + ") must not exceed max (" + max + ")");
        }
    }
}
