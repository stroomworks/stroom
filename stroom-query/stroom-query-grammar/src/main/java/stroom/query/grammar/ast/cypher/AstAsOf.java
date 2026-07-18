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
 * {@code AS OF <instant>} - one snapshot instant, applied per-edge to every hop (P0.3 outcome).
 *
 * @param instant  never null; typically a {@link AstFunctionValue} like {@code datetime('...')}.
 * @param position never null.
 */
public record AstAsOf(AstValue instant, AstPosition position) implements AstTemporal {

    public AstAsOf {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(position, "position");
    }
}
