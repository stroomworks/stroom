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
 * {@code DIFF FROM <baseline> TO <comparison>} - Stroom's temporal <i>change</i> clause (see
 * {@code docs/temporal-cypher-diff-operator.md}). {@code baseline} is the "before" instant ({@code t1}),
 * {@code comparison} the "after" ({@code t2}); direction is what distinguishes an addition from a removal, so it
 * is syntactically explicit (never positional-by-accident). Both are still unresolved {@link AstValue}s here
 * (typically a {@code datetime('...')} literal); {@code CypherToLogicalPlan} resolves them to instants, enforces
 * {@code baseline < comparison}, and lowers this to a {@code DiffContext} (Task Phase 2).
 *
 * @param baseline   never null - the {@code t1} ("before") instant expression.
 * @param comparison never null - the {@code t2} ("after") instant expression.
 * @param position   never null.
 */
public record AstDiff(AstValue baseline, AstValue comparison, AstPosition position) implements AstTemporal {

    public AstDiff {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(position, "position");
    }
}
