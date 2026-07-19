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

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The resolved form of a Cypher {@code AS OF}/{@code AROUND}/{@code BETWEEN} clause - the compiled,
 * ready-to-execute counterpart of {@code stroom.query.grammar.ast.cypher.AstTemporal} (function-call literals
 * like {@code datetime(...)}/{@code duration(...)} already evaluated to real {@link Instant}/{@link Duration}
 * values). Threaded alongside the {@link LogicalPlan} a {@link CypherToLogicalPlan#compile} call produces (see
 * {@code CompiledCypherPlan}), since temporal execution is a per-query context, not a plan node in its own
 * right.
 *
 * <p>Per the P0.3 spike outcome (design doc &sect;5.4; implementation plan Task 0.3): {@code AS OF} is applied
 * per-edge at a single snapshot {@code instant}; {@code AROUND}/{@code BETWEEN} both resolve to an inclusive
 * window {@code [from, to]} that a version intersects using the rule
 * {@code validFrom <= to AND nextValidFrom > from}.</p>
 *
 * @param mode     never null.
 * @param instant  the as-of snapshot instant; non-null only for {@link Mode#AS_OF}. {@code null} for both
 *                 {@link Mode#AROUND} and {@link Mode#BETWEEN} - {@code AROUND}'s window centre is not stored
 *                 here, it is expanded into {@code from}/{@code to} by {@link CypherToLogicalPlan} before this
 *                 record is built (see {@link #window}).
 * @param from     the inclusive window start ({@link Mode#AROUND}/{@link Mode#BETWEEN}); {@code null} for
 *                 {@link Mode#AS_OF}.
 * @param to       the inclusive window end ({@link Mode#AROUND}/{@link Mode#BETWEEN}); {@code null} for
 *                 {@link Mode#AS_OF}.
 */
public record TemporalContext(
        Mode mode,
        @Nullable Instant instant,
        @Nullable Instant from,
        @Nullable Instant to) {

    /**
     * <b>Preconditions:</b> the nullability of {@code instant}/{@code from}/{@code to} must match {@code mode}
     * exactly, per each field's Javadoc.
     */
    public TemporalContext {
        Objects.requireNonNull(mode, "mode");
        final boolean shapeOk = switch (mode) {
            case AS_OF -> instant != null && from == null && to == null;
            case AROUND, BETWEEN -> instant == null && from != null && to != null;
        };
        if (!shapeOk) {
            throw new IllegalArgumentException(
                    "instant/from/to do not match mode " + mode + " (instant=" + instant
                    + ", from=" + from + ", to=" + to + ")");
        }
    }

    /**
     * @return an {@link Mode#AS_OF} context at {@code instant}.
     */
    public static TemporalContext asOf(final Instant instant) {
        return new TemporalContext(Mode.AS_OF, instant, null, null);
    }

    /**
     * @return a windowed context ({@link Mode#AROUND} or {@link Mode#BETWEEN}) over the inclusive
     * {@code [from, to]} range.
     */
    public static TemporalContext window(final Mode mode, final Instant from, final Instant to) {
        return new TemporalContext(mode, null, from, to);
    }

    /**
     * The three forms of Stroom's Cypher temporal extension (design doc &sect;5.4). No clause at all means
     * "latest" and is represented by the absence of a {@link TemporalContext} entirely, not a fourth value here.
     */
    public enum Mode {
        AS_OF,
        AROUND,
        BETWEEN
    }
}
