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
 * {@code AROUND <instant> ± <duration>} - a bounded window {@code [instant - duration, instant + duration]}
 * (P0.3 outcome: inclusive bounds, interval intersection per edge).
 *
 * @param instant  never null; the window's centre.
 * @param duration never null; the window's half-width.
 * @param position never null.
 */
public record AstAround(AstValue instant, AstValue duration, AstPosition position) implements AstTemporal {

    public AstAround {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(position, "position");
    }
}
