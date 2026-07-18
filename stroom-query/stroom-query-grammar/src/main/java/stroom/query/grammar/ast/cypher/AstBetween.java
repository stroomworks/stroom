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

import java.util.Objects;

/**
 * {@code BETWEEN <from> AND <to>} - a bounded window {@code [from, to]} (P0.3 outcome: inclusive bounds,
 * interval intersection per edge).
 *
 * @param from     never null.
 * @param to       never null.
 * @param position never null.
 */
public record AstBetween(AstValue from, AstValue to, AstPosition position) implements AstTemporal {

    public AstBetween {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(position, "position");
    }
}
