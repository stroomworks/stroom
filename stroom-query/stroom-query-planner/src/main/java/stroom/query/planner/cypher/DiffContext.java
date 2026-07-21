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

package stroom.query.planner.cypher;

import java.time.Instant;
import java.util.Objects;

/**
 * The resolved two-instant context of a {@code DIFF FROM <baseline> TO <comparison>} query (see
 * {@code docs/temporal-cypher-diff-operator.md}). Carried on {@link CompiledCypherPlan} alongside (never instead
 * of) {@link TemporalContext}: a query is either a state query (a {@code TemporalContext}, or neither for
 * "latest") or a diff (a {@code DiffContext}), never both.
 *
 * <p>Deliberately <b>not</b> a fourth {@link TemporalContext.Mode}: a diff does not resolve to a single access
 * mode. It executes as the existing {@code AS OF} path run twice - once at {@link #baseline} ({@code t1}) and once
 * at {@link #comparison} ({@code t2}) - and the two result sets are then classified/merged (Strategy A). Keeping
 * it separate leaves {@code TemporalContext} and the engine's {@code resolveAccess} untouched.</p>
 *
 * @param baseline   never null; the "before" instant ({@code t1}).
 * @param comparison never null; the "after" instant ({@code t2}); strictly after {@code baseline} (the compiler
 *                   enforces this and rejects equal/reversed instants with a positioned error - this constructor's
 *                   check is the defensive backstop).
 */
public record DiffContext(Instant baseline, Instant comparison) {

    public DiffContext {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(comparison, "comparison");
        if (!baseline.isBefore(comparison)) {
            throw new IllegalArgumentException(
                    "DIFF baseline (" + baseline + ") must be strictly before comparison (" + comparison + ")");
        }
    }
}
