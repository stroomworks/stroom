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

package stroom.graphdb.impl;

import stroom.query.api.DateTimeSettings;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.cypher.DiffContext;
import stroom.query.planner.logical.LogicalPlan;

import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes a {@code DIFF FROM t1 TO t2} query's delta-table mode (see
 *  &sect;7.1 "Strategy A"): it runs the compiled pattern's bindings at
 * each instant via {@link GraphTraversalEngine#executeDiffBindings}, classifies the two match sets with the pure
 * {@link DiffOperator}, then builds one delta-table row per changed path and projects it through the engine's
 * ordinary {@code RETURN}/{@code ORDER BY}/{@code DISTINCT}/{@code LIMIT} pipeline
 * ({@link GraphTraversalEngine#projectDiffRows}).
 *
 * <p>{@code UNCHANGED} paths are suppressed by default in delta-table mode - they are dropped here
 * before projection. This class is stateless (only a static entry point) and does no I/O of its own beyond the two
 * engine traversals it delegates.</p>
 */
public final class DiffExecutor {

    /** Row-key prefixes for {@code before(...)} / {@code after(...)} accessor values - must match
     * {@code CypherToLogicalPlan.diffAccessorRowKey}'s convention so the compiled {@code ${before.x.p}} /
     * {@code ${after.x.p}} references resolve. */
    private static final String BEFORE_PREFIX = "before.";
    private static final String AFTER_PREFIX = "after.";

    private DiffExecutor() {
    }

    /**
     * Runs the diff and returns its projected delta-table rows.
     *
     * @param readTxn          the read transaction to traverse under; never null. Both snapshot traversals run in
     *                         this one transaction, so they see a single consistent view of the store.
     * @param engine           the traversal engine bound to the target graph's stores; never null.
     * @param plan             the compiled fixed-length DIFF plan; never null.
     * @param diffContext      the resolved baseline/comparison instants ({@code baseline < comparison} already
     *                         enforced at compile time); never null.
     * @param dateTimeSettings never null; used to evaluate any pattern {@code WHERE} date comparisons per snapshot.
     * @param distinct         whether {@code RETURN DISTINCT} was requested.
     * @return the projected {@code Val[]} rows for the changed paths (in the plan's {@code Project} field order),
     *         never null (may be empty).
     */
    public static List<Val[]> execute(final Txn<ByteBuffer> readTxn, final GraphTraversalEngine engine,
                                      final LogicalPlan plan, final DiffContext diffContext,
                                      final DateTimeSettings dateTimeSettings, final boolean distinct) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(diffContext, "diffContext");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");

        final List<DiffMatch> baseline = engine.executeDiffBindings(
                readTxn, plan, diffContext.baseline(), dateTimeSettings);
        final List<DiffMatch> comparison = engine.executeDiffBindings(
                readTxn, plan, diffContext.comparison(), dateTimeSettings);
        final List<ClassifiedMatch> classified = DiffOperator.classify(baseline, comparison);

        final List<Map<String, Val>> deltaRows = new ArrayList<>(classified.size());
        for (final ClassifiedMatch match : classified) {
            if (match.changeKind() == ChangeKind.UNCHANGED) {
                continue;
            }
            deltaRows.add(toDeltaRow(match));
        }

        return engine.projectDiffRows(plan, deltaRows, distinct);
    }

    /**
     * Builds one delta-table row from a classified path: the present-snapshot values (so a bare {@code a.id}
     * resolves), the {@code changeKind} pseudo-column, and every baseline / comparison property under its
     * {@code before.} / {@code after.} accessor key (so {@code before(a.p)} / {@code after(a.p)} resolve; absent
     * on the side the path is missing from). Never returns null.
     */
    private static Map<String, Val> toDeltaRow(final ClassifiedMatch match) {
        final Map<String, Val> row = new HashMap<>(match.presentRow());
        row.put(CypherToLogicalPlan.CHANGE_KIND_COLUMN, ValString.create(match.changeKind().name()));
        if (match.baselineRow() != null) {
            match.baselineRow().forEach((key, value) -> row.put(BEFORE_PREFIX + key, value));
        }
        if (match.comparisonRow() != null) {
            match.comparisonRow().forEach((key, value) -> row.put(AFTER_PREFIX + key, value));
        }
        return row;
    }
}
