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

import stroom.lmdb.serde.UnsignedBytesInstances;
import stroom.planb.impl.dao.UidLookupDb;
import stroom.query.api.DateTimeSettings;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactories;
import stroom.query.language.functions.Type;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValDouble;
import stroom.query.language.functions.ValLong;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cypher.AggregateColumn;
import stroom.query.planner.cypher.CypherAggregation;
import stroom.query.planner.cypher.GroupKeyColumn;
import stroom.query.planner.cypher.OutputColumn;
import stroom.query.planner.cypher.TemporalContext;
import stroom.query.planner.logical.Direction;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.SortKey;
import stroom.query.planner.logical.VarLengthExpand;

import org.jspecify.annotations.Nullable;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Executes a compiled Cypher {@link LogicalPlan} over a {@link GraphStores}' physical stores (design doc
 * &sect;5.5's {@code expand} operator; implementation plan Task PoC.5): anchor scan (via
 * {@link GraphPropertyIndex}) &rarr; a chain of zero or more {@code expand} hops (Task P3.2; via
 * {@link GraphAdjacencyDb}/{@link GraphInEdgeDb}, dereferencing each neighbour via {@link GraphNodeDb}) &rarr; the
 * outer {@code WHERE} predicate &rarr; {@code RETURN} projection. Reuses {@link ExpressionPredicateFactory}
 * exactly as {@code stroom.searchable.impl.JoinSearchProvider#whereRowPredicate} does, over a
 * {@code "variable.property" -> Val} row map instead of a joined {@code Values} row - the graph analogue of that
 * class's combined-row predicate.
 *
 * <p><b>Contract (see this class's Javadoc for what's deliberately not yet handled):</b> a fixed-length chain of
 * {@link Expand} hops, anchor-first, matching compiled source order exactly - not re-ordered by any selectivity
 * heuristic, since only the anchor has a property-index-seekable access path in this v1 subset (a middle hop's
 * own label/property constraint, Task P3.1, is a post-expand filter, never an alternative seek point); OR a
 * single bounded {@link VarLengthExpand} hop (Task P3.3), executed as a bounded breadth-first search with a
 * per-path cycle guard (see {@link #expandVarLength}) - never both in the same plan, since a var-length hop only
 * compiles as a pattern's sole hop. Single-shard only (cross-shard is P8); streams {@code Val[]} rows - does not
 * itself build coprocessors (PoC.6 does that from these rows).</p>
 *
 * <p><b>Temporal dispatch (Task P4.2):</b> every node lookup and hop expansion goes through a {@link
 * TemporalAccess} - built once per {@link #execute} call from the plan's {@code TemporalContext} - so the rest
 * of this class need not know whether it is running an {@code AS OF}/no-clause floor lookup or an
 * {@code AROUND}/{@code BETWEEN} window-intersection scan (Task P4.1's {@code *Window} DAO methods). Per the
 * P0.3 frozen rule, a single query's temporal clause applies uniformly to the anchor and every hop - there is no
 * per-hop-differing mode.</p>
 *
 * <p><b>Deliberately unsupported here (throws {@link UnsupportedOperationException} rather than a wrong
 * result)</b>: an anchor {@link NodeScan} with no label or no property predicate (the property index has no
 * "all nodes of this label" scan - only equality lookups); a {@code RETURN} item other than a bare
 * property/variable reference (a literal, aggregate, or function call needs the full {@code ExpressionParser},
 * not wired to a graph row here). Each hop's non-anchor node's own labels/properties (Task P3.1) are enforced as
 * a post-expand filter in {@link #acceptChainNeighbour}/{@link #expandVarLength}, exactly mirroring how
 * {@link #resolveAnchors} validates an anchor's property predicate - not an alternative access path, since a
 * neighbour is always reached via the edge. The outer {@code WHERE} predicate is evaluated only once a row
 * carries every hop's bound variable, i.e. only after the pattern's last (fixed-length) hop (Task P3.2), or at
 * every depth within {@code [minHops, maxHops]} for a var-length hop (Task P3.3) - matching how a single-hop
 * plan already evaluated it against the fully merged anchor+target row, not a per-hop partial evaluation.</p>
 */
public final class GraphTraversalEngine {

    /**
     * Task P7.2: a variable-length hop range wider than this is rejected up front, before any traversal work -
     * {@code Cypher.g4} makes {@code maxHops} mandatory (so {@code -[:T*]->} is a parse-time error) but places no
     * ceiling on the value itself, so {@code -[:T*1..100000]->} was previously accepted and attempted verbatim.
     */
    private static final int MAX_VAR_LENGTH_HOPS = 50;

    /**
     * Task P7.2: a hard ceiling on the total number of BFS path-states {@link #expandVarLength} will explore
     * across every depth of a single variable-length hop. Guards the case the hop-range cap alone does not: a
     * modest {@code maxHops} (e.g. 3-4) combined with a high-fan-out hub node can still explore an exponential
     * number of paths, all materialised in memory at once (see this class's Javadoc note on
     * {@link #expandVarLength}).
     *
     * <p><b>This budget is per anchor, not per query:</b> {@link #execute} calls {@link #expandVarLength} once
     * per matching anchor, and each call starts a fresh counter - a query matching N anchors therefore gets N
     * independent budgets of this size, not one global ceiling shared across the whole query.</p>
     */
    private static final int MAX_VAR_LENGTH_PATH_STATES = 200_000;

    /**
     * Task P7.2: a fixed wall-clock budget for one {@link #execute} call. {@code GraphSearchProvider} runs a
     * traversal synchronously on the calling thread (by design - see that class's Javadoc); this is the backstop
     * against a pathological query (e.g. a hub-heavy fixed-length chain) simply running for an unbounded time with
     * no way for the caller to cancel it.
     */
    private static final Duration MAX_TRAVERSAL_DURATION = Duration.ofSeconds(30);

    /**
     * Review finding F3 fix: a hard ceiling on the total number of rows a single {@link #execute} call will
     * accumulate in memory, independent of any compiled {@code LIMIT} - the essential OOM safety net. {@code
     * rowCap} (see {@link #execute}'s own Javadoc) is {@link Long#MAX_VALUE} whenever {@code ORDER BY}/
     * {@code DISTINCT}/aggregation is present or no {@code LIMIT} was compiled, so before this fix a broad
     * {@code MATCH} or any of those clauses could accumulate an unbounded number of rows before the wall-clock
     * {@link #MAX_TRAVERSAL_DURATION} deadline even fired. Once accumulation would exceed this ceiling, {@link
     * #execute} fails loud with a {@link GraphTraversalLimitExceededException} instead of silently truncating or
     * risking an {@code OutOfMemoryError} - see {@link UnboundedRowSink}.
     *
     * <p><b>This default (1,000,000 rows) is a tunable, not an architectural limit:</b> it is picked high enough
     * that a legitimate, reasonably-scoped interactive query should never trip it, and low enough that hitting it
     * still leaves real heap headroom on a typical node. A deployment with a larger heap and a genuine need for
     * bigger single-query result sets can raise it (via the test/production seam constructor below); the better
     * fix for a query that trips this is almost always a tighter pattern, an added/reduced {@code LIMIT}, or a
     * narrower {@code WHERE}.</p>
     */
    private static final long MAX_ACCUMULATED_ROWS = 1_000_000L;

    /**
     * The node cap for an unanchored {@code MATCH (n) RETURN GRAPH} whole-graph preview when the query gives no
     * {@code LIMIT} (a bare preview must still be bounded - it walks the store rather than seeking an index). A
     * query's own {@code LIMIT} overrides this. See {@link #dumpWholeGraph}.
     */
    private static final int DEFAULT_WHOLE_GRAPH_NODE_CAP = 100;

    private final GraphStores stores;
    private final ExpressionPredicateFactory expressionPredicateFactory;
    private final long maxVarLengthPathStates;
    private final Duration maxTraversalDuration;
    private final long maxAccumulatedRows;
    private final int wholeGraphNodeCap;

    public GraphTraversalEngine(final GraphStores stores,
                                final ExpressionPredicateFactory expressionPredicateFactory) {
        this(stores, expressionPredicateFactory, MAX_VAR_LENGTH_PATH_STATES, MAX_TRAVERSAL_DURATION);
    }

    /**
     * Task P7.2: test-only seam - lets a test exercise the {@link #MAX_VAR_LENGTH_PATH_STATES} ceiling
     * deterministically over a small fixture (a handful of edges) rather than needing to seed hundreds of
     * thousands of them to reach the real production default.
     */
    GraphTraversalEngine(final GraphStores stores,
                        final ExpressionPredicateFactory expressionPredicateFactory,
                        final long maxVarLengthPathStates) {
        this(stores, expressionPredicateFactory, maxVarLengthPathStates, MAX_TRAVERSAL_DURATION);
    }

    /**
     * Code-review fix: test-only seam - lets a test exercise {@link #MAX_TRAVERSAL_DURATION}'s deadline
     * deterministically (e.g. a duration of zero) rather than needing to wait out the real 30-second production
     * default.
     */
    GraphTraversalEngine(final GraphStores stores,
                        final ExpressionPredicateFactory expressionPredicateFactory,
                        final long maxVarLengthPathStates,
                        final Duration maxTraversalDuration) {
        this(stores, expressionPredicateFactory, maxVarLengthPathStates, maxTraversalDuration,
                MAX_ACCUMULATED_ROWS);
    }

    /**
     * Review finding F3 fix: test-only seam - lets a test exercise the {@link #MAX_ACCUMULATED_ROWS} ceiling
     * deterministically over a small fixture (a handful of rows) rather than needing to seed a million-plus rows
     * to reach the real production default.
     */
    GraphTraversalEngine(final GraphStores stores,
                        final ExpressionPredicateFactory expressionPredicateFactory,
                        final long maxVarLengthPathStates,
                        final Duration maxTraversalDuration,
                        final long maxAccumulatedRows) {
        this(stores, expressionPredicateFactory, maxVarLengthPathStates, maxTraversalDuration, maxAccumulatedRows,
                DEFAULT_WHOLE_GRAPH_NODE_CAP);
    }

    /**
     * Test-only seam - lets a test exercise the whole-graph dump's node cap ({@link #dumpWholeGraph}) over a small
     * fixture rather than needing to seed {@link #DEFAULT_WHOLE_GRAPH_NODE_CAP}+ nodes.
     */
    GraphTraversalEngine(final GraphStores stores,
                        final ExpressionPredicateFactory expressionPredicateFactory,
                        final long maxVarLengthPathStates,
                        final Duration maxTraversalDuration,
                        final long maxAccumulatedRows,
                        final int wholeGraphNodeCap) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
        this.maxVarLengthPathStates = maxVarLengthPathStates;
        this.maxTraversalDuration = Objects.requireNonNull(maxTraversalDuration, "maxTraversalDuration");
        this.maxAccumulatedRows = maxAccumulatedRows;
        this.wholeGraphNodeCap = wholeGraphNodeCap;
    }

    /**
     * <b>Preconditions:</b> no parameter is null except {@code temporalContext}; {@code plan} must have the
     * shape {@code [Limit ->] [Sort ->] Project -> [Filter ->] [Expand ->]* NodeScan} (see this class's Javadoc
     * for what happens otherwise).
     * <b>Postconditions:</b> one {@code Val[]} per surviving row, in {@code Project}'s field order; never null
     * (possibly empty). This overload treats the {@code RETURN} as non-{@code DISTINCT}.
     *
     * @param readTxn          the read transaction to traverse under.
     * @param plan             the compiled plan.
     * @param temporalContext  {@code null} for "latest".
     * @param dateTimeSettings never null; used to evaluate any date comparisons in the {@code WHERE} clause.
     * @return never null.
     */
    public List<Val[]> execute(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                               final @Nullable TemporalContext temporalContext,
                               final DateTimeSettings dateTimeSettings) {
        return execute(readTxn, plan, temporalContext, dateTimeSettings, false, null);
    }

    /**
     * As {@link #execute(Txn, LogicalPlan, TemporalContext, DateTimeSettings)}, but honouring {@code RETURN
     * DISTINCT}: {@code distinct} de-duplicates the projected rows (by value, in first-appearance order after any
     * {@code ORDER BY}). {@code DISTINCT} is carried on {@code CompiledCypherPlan}, not in the plan tree, because
     * the sealed shared IR has no Distinct node.
     *
     * <p>Ordering of post-traversal steps mirrors Cypher: rows are sorted by any {@code ORDER BY} keys, projected
     * to output tuples, de-duplicated when {@code distinct}, then capped by any {@code LIMIT}. When {@code ORDER
     * BY} or {@code DISTINCT} is present the traversal-time row cap is disabled (every matching row must be seen
     * before sort/de-dup can pick the correct {@code LIMIT} rows); a bare {@code LIMIT} with neither still
     * early-exits the traversal as before. Review finding F3 fix: when {@code LIMIT} is disabled this way,
     * {@link #MAX_ACCUMULATED_ROWS} still bounds how many rows may be held in memory (see {@link
     * UnboundedRowSink}) - and when {@code ORDER BY}+{@code LIMIT} is present without {@code DISTINCT}/
     * aggregation, accumulation itself never exceeds the {@code LIMIT} (see {@link TopNRowSink}).</p>
     *
     * @param distinct whether to de-duplicate the projected rows ({@code RETURN DISTINCT}).
     */
    public List<Val[]> execute(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                               final @Nullable TemporalContext temporalContext,
                               final DateTimeSettings dateTimeSettings, final boolean distinct) {
        return execute(readTxn, plan, temporalContext, dateTimeSettings, distinct, null);
    }

    /**
     * As {@link #execute(Txn, LogicalPlan, TemporalContext, DateTimeSettings, boolean)}, but additionally
     * honouring a compiled aggregation description (see
     * {@code docs/graphdb-analytic-functions-implementation-plan.md}, Task 1.2/1.3): when {@code aggregation} is
     * non-null, the traversal's matched rows are grouped by its {@link GroupKeyColumn}s and reduced by its
     * {@link AggregateColumn}s into one output row per group (see {@link #finalizeAggregatedRows}), instead of
     * the ordinary one-output-row-per-surviving-row projection {@link #finalizeRows} performs.
     *
     * @param aggregation {@code null} for the ordinary (non-aggregated) execution path, unchanged from every
     *                    other overload; otherwise the compiled {@code RETURN}'s aggregation description,
     *                    aligned 1:1, in order, with {@code plan}'s terminal {@code Project}'s fields (see
     *                    {@link CypherAggregation}'s Javadoc for why that alignment is a precondition here, not
     *                    re-checked).
     */
    public List<Val[]> execute(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                               final @Nullable TemporalContext temporalContext,
                               final DateTimeSettings dateTimeSettings, final boolean distinct,
                               final @Nullable CypherAggregation aggregation) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");

        final TemporalAccess access = resolveAccess(temporalContext);
        final PlanShape shape = unwrap(plan);

        final Predicate<Map<String, Val>> wherePredicate = shape.where == null
                ? row -> true
                : expressionPredicateFactory
                        .createOptional(shape.where, rowAccessors(), dateTimeSettings)
                        .orElse(row -> true);

        // Task P7.2: rowCap enforces a compiled Cypher LIMIT as an early-exit bound on row accumulation itself,
        // not merely a post-hoc trim of an already-fully-computed result (which is all DataStoreSettings' store
        // size cap, applied by GraphSearchProvider afterwards, ever did before this task). deadline is a fixed
        // wall-clock safety backstop, since GraphSearchProvider runs a traversal synchronously on the calling
        // thread with no task-cancellation hook (see that class's Javadoc for why).
        //
        // ORDER BY/DISTINCT/aggregation (code-review follow-up): early-exit is only sound when the first N rows
        // traversed are a valid answer. With ORDER BY the N smallest are not necessarily the first N traversed,
        // with DISTINCT the first N raw rows may collapse to fewer than N distinct ones, and with aggregation
        // every matching row must be seen before rows can be grouped/reduced correctly - so in every case we must
        // traverse everything and apply the LIMIT afterwards (see finalizeRows/finalizeAggregatedRows).
        final boolean postProcess = !shape.sortKeys().isEmpty() || distinct || aggregation != null;
        final long rowCap = postProcess || shape.limit() == null
                ? Long.MAX_VALUE
                : Math.max(0L, shape.limit());
        final Instant deadline = Instant.now().plus(maxTraversalDuration);

        // Review finding F3 fix: rowCap alone is not a memory guardrail - it is Long.MAX_VALUE for exactly the
        // shapes (no LIMIT, or ORDER BY/DISTINCT/aggregation) where accumulation would otherwise be unbounded.
        // TopNRowSink closes the common "ORDER BY ... LIMIT n" case at the source (never accumulates more than
        // n rows); every other shape falls back to UnboundedRowSink, which still enforces the hard
        // MAX_ACCUMULATED_ROWS ceiling so a broad/sorted/deduped/aggregated query fails loud instead of
        // exhausting the heap. DISTINCT and aggregation are excluded from the top-N sink because de-duplication
        // and grouping both need every matching row to decide correctly which survive - a size-n heap keyed only
        // on the ORDER BY comparator cannot answer that. A LIMIT larger than the ceiling itself is also excluded
        // (a heap that size would defeat the point), so that case degrades to the ceiling-guarded unbounded sink
        // rather than allocating an equally unbounded heap.
        final boolean topNEligible = !shape.sortKeys().isEmpty() && !distinct && aggregation == null
                && shape.limit() != null && shape.limit() > 0 && shape.limit() <= maxAccumulatedRows;
        final RowSink rowSink = topNEligible
                ? new TopNRowSink((int) (long) shape.limit(), rowComparator(shape.sortKeys()))
                : new UnboundedRowSink(maxAccumulatedRows);

        if (shape.varLengthExpand != null) {
            for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan, access)) {
                if (rowSink.size() >= rowCap) {
                    break;
                }
                final Optional<GraphNodeDb.NodeVersion> anchor = access.getNode(readTxn, anchorUid);
                if (anchor.isEmpty()) {
                    continue;
                }
                final Map<String, Val> anchorRow = rowFor(shape.nodeScan.variable(), anchor.get().properties());
                expandVarLength(readTxn, anchorUid, access, shape.varLengthExpand, anchorRow, wherePredicate,
                        rowSink, rowCap, deadline);
            }
            return finalize(rowSink.drain(), shape, distinct, aggregation);
        }

        List<Frontier> frontier = new ArrayList<>();
        for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan, access)) {
            final Optional<GraphNodeDb.NodeVersion> anchor = access.getNode(readTxn, anchorUid);
            if (anchor.isEmpty()) {
                continue;
            }
            frontier.add(new Frontier(anchorUid, rowFor(shape.nodeScan.variable(), anchor.get().properties())));
        }

        if (shape.hops.isEmpty()) {
            for (final Frontier f : frontier) {
                if (rowSink.size() >= rowCap) {
                    break;
                }
                if (wherePredicate.test(f.row())) {
                    rowSink.add(f.row());
                }
            }
            return finalize(rowSink.drain(), shape, distinct, aggregation);
        }

        for (int i = 0; i < shape.hops.size(); i++) {
            checkDeadline(deadline);
            if (rowSink.size() >= rowCap) {
                break;
            }
            final Expand hop = shape.hops.get(i);
            final boolean isLastHop = i == shape.hops.size() - 1;
            final List<Frontier> next = new ArrayList<>();
            for (final Frontier f : frontier) {
                if (rowSink.size() >= rowCap) {
                    break;
                }
                expandChainHop(readTxn, f.nodeUid(), access, hop, f.row(), isLastHop, wherePredicate, next, rowSink,
                        rowCap, deadline);
            }
            frontier = next;
        }

        return finalize(rowSink.drain(), shape, distinct, aggregation);
    }

    // ------------------------------------------------------------------------------------------------------
    // DIFF bindings (temporal-cypher-diff-operator.md §5)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Traverses {@code plan} at a single instant for a {@code DIFF} query, returning each matched path as a
     * {@link DiffMatch} - its bound-value row plus the ordered tuple of bound element identities that
     * {@link DiffOperator} keys on (see {@code docs/temporal-cypher-diff-operator.md} &sect;5.2). This is the
     * per-snapshot half of {@code DIFF}: the executor calls it twice (once with {@code baseline}, once with
     * {@code comparison}) and merges the two results.
     *
     * <p>It reuses the ordinary fixed-length traversal machinery unchanged ({@link #resolveAnchors},
     * {@link #collectNeighbours}, {@link #matchesTargetConstraint}, {@link #rowFor}, the {@code AS OF}
     * {@link TemporalAccess}) but threads a growing identity tuple alongside each frontier row: the anchor
     * contributes an {@link ElementId.Node}, and each hop appends the traversed edge's {@link ElementId.Edge}
     * triple then the target {@link ElementId.Node}. The pattern's own {@code WHERE} predicate (which the compiler
     * has already restricted to pattern-only terms - {@code changeKind}/{@code before}/{@code after} are applied
     * post-classification) is evaluated per snapshot, exactly as {@link #execute} does.</p>
     *
     * <p><b>Preconditions:</b> {@code plan} is a fixed-length pattern (variable-length is rejected under
     * {@code DIFF} at compile time; a {@link VarLengthExpand} here throws). No {@code LIMIT}/{@code ORDER BY}/
     * aggregation is applied here - those act on the merged delta table, not a single snapshot.</p>
     *
     * @param readTxn          the read transaction; never null.
     * @param plan             the compiled fixed-length plan; never null.
     * @param asOf             the instant to resolve the graph at; never null.
     * @param dateTimeSettings never null; used to evaluate any {@code WHERE} date comparisons.
     * @return one {@link DiffMatch} per matched path at {@code asOf}; never null (may be empty). Identity tuples
     *         are unique per path, so the result is directly usable as a {@link DiffOperator} side.
     */
    public List<DiffMatch> executeDiffBindings(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                                               final Instant asOf, final DateTimeSettings dateTimeSettings) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");

        final TemporalAccess access = asOfAccess(asOf);
        final PlanShape shape = unwrap(plan);
        if (shape.varLengthExpand() != null) {
            throw new UnsupportedOperationException(
                    "variable-length patterns are not supported under DIFF (rejected at compile time)");
        }

        final Predicate<Map<String, Val>> wherePredicate = shape.where() == null
                ? row -> true
                : expressionPredicateFactory
                        .createOptional(shape.where(), rowAccessors(), dateTimeSettings)
                        .orElse(row -> true);

        final Instant deadline = Instant.now().plus(maxTraversalDuration);

        List<DiffFrontier> frontier = new ArrayList<>();
        for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan(), access)) {
            final Optional<GraphNodeDb.NodeVersion> anchor = access.getNode(readTxn, anchorUid);
            if (anchor.isEmpty()) {
                continue;
            }
            frontier.add(new DiffFrontier(
                    anchorUid,
                    rowFor(shape.nodeScan().variable(), anchor.get().properties()),
                    List.of(new ElementId.Node(anchorUid))));
        }

        final List<DiffMatch> matches = new ArrayList<>();
        if (shape.hops().isEmpty()) {
            for (final DiffFrontier f : frontier) {
                if (wherePredicate.test(f.row())) {
                    matches.add(new DiffMatch(f.identity(), f.row()));
                }
            }
            return matches;
        }

        for (int i = 0; i < shape.hops().size(); i++) {
            checkDeadline(deadline);
            final Expand hop = shape.hops().get(i);
            final boolean isLastHop = i == shape.hops().size() - 1;
            final List<DiffFrontier> next = new ArrayList<>();
            for (final DiffFrontier f : frontier) {
                expandDiffHop(readTxn, f, access, hop, isLastHop, wherePredicate, next, matches, deadline);
            }
            frontier = next;
        }

        return matches;
    }

    /**
     * Applies the compiled {@code RETURN} projection (plus any {@code ORDER BY} / {@code DISTINCT} / {@code LIMIT})
     * to a {@code DIFF} query's pre-built delta-table rows, reusing the ordinary {@link #finalizeRows} pipeline. A
     * delta-table row is a plain {@code "key" -> Val} map that {@code DiffExecutor} has already populated with the
     * projected present-snapshot values, the {@code changeKind} pseudo-column, and the {@code before.<var>.<prop>}
     * / {@code after.<var>.<prop>} accessor values (see {@code CypherToLogicalPlan.diffAccessorRowKey}), so each
     * {@link ProjectField}'s {@code ${...}} expression resolves against it exactly as a traversal row would.
     *
     * <p>The {@code plan}'s traversal shape (its {@code NodeScan}/{@code Expand}s) is irrelevant here - only its
     * terminal {@code Project}/{@code Sort}/{@code Limit} wrappers are read. Aggregation is not a valid DIFF shape
     * (rejected at compile time), so this always takes the non-aggregated path.</p>
     *
     * @param plan     the compiled DIFF plan; never null.
     * @param diffRows the classified, projected delta-table rows in {@code DiffExecutor}'s emission order; never
     *                 null (may be empty). Mutated in place by any {@code ORDER BY} sort.
     * @param distinct whether {@code RETURN DISTINCT} was requested.
     * @return one output tuple per surviving delta-table row, aligned to the plan's {@code Project} fields; never
     *         null.
     */
    public List<Val[]> projectDiffRows(final LogicalPlan plan, final List<Map<String, Val>> diffRows,
                                       final boolean distinct) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(diffRows, "diffRows");
        return finalizeRows(diffRows, unwrap(plan), distinct);
    }

    /**
     * A {@code DIFF} traversal frontier entry: as {@link Frontier}, plus the ordered identity tuple built so far
     * (anchor node, then each hop's edge + target node). {@code identity} is never null and never empty.
     */
    private record DiffFrontier(long nodeUid, Map<String, Val> row, List<ElementId> identity) {
    }

    /**
     * The {@code DIFF} counterpart of {@link #expandChainHop}/{@link #acceptChainNeighbour}: expands one hop from
     * {@code from}, and for each surviving neighbour extends the row (target-node props, and the edge's props if
     * the hop named a relationship variable) and the identity tuple (edge triple + target node). Because the edge
     * triple's {@code (src, dst)} orientation depends on which direction produced the neighbour, {@code OUT} and
     * {@code IN} are expanded separately here (rather than via {@link #collectNeighbours}'s direction-erasing
     * {@code BOTH}) so each neighbour's edge identity is oriented correctly.
     */
    private void expandDiffHop(final Txn<ByteBuffer> readTxn, final DiffFrontier from, final TemporalAccess access,
                               final Expand hop, final boolean isLastHop,
                               final Predicate<Map<String, Val>> wherePredicate,
                               final List<DiffFrontier> nextFrontier, final List<DiffMatch> matches,
                               final Instant deadline) {
        final Optional<Long> edgeTypeUid = resolveRequiredEdgeTypeUid(readTxn, hop.edgeType());
        if (edgeTypeUid.isEmpty()) {
            return;
        }
        final long typeUid = edgeTypeUid.get();

        switch (hop.direction()) {
            case OUT -> access.expandOut(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptDiffNeighbour(
                    readTxn, from, access, hop, edgeStep,
                    new ElementId.Edge(from.nodeUid(), typeUid, edgeStep.neighbourUid()),
                    isLastHop, wherePredicate, nextFrontier, matches, deadline));
            case IN -> access.expandIn(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptDiffNeighbour(
                    readTxn, from, access, hop, edgeStep,
                    new ElementId.Edge(edgeStep.neighbourUid(), typeUid, from.nodeUid()),
                    isLastHop, wherePredicate, nextFrontier, matches, deadline));
            case BOTH -> {
                access.expandOut(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptDiffNeighbour(
                        readTxn, from, access, hop, edgeStep,
                        new ElementId.Edge(from.nodeUid(), typeUid, edgeStep.neighbourUid()),
                        isLastHop, wherePredicate, nextFrontier, matches, deadline));
                access.expandIn(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptDiffNeighbour(
                        readTxn, from, access, hop, edgeStep,
                        new ElementId.Edge(edgeStep.neighbourUid(), typeUid, from.nodeUid()),
                        isLastHop, wherePredicate, nextFrontier, matches, deadline));
            }
        }
    }

    /**
     * Accepts one neighbour of a {@code DIFF} hop: validates the target-node constraint, extends {@code from}'s
     * row (target props + optional edge props) and identity tuple ({@code edgeId} + target {@link ElementId.Node}),
     * then either records a {@link DiffMatch} (last hop, if it passes {@code WHERE}) or pushes a new frontier entry.
     * Mirrors {@link #acceptChainNeighbour}; {@code edgeId} is the direction-oriented identity of the traversed
     * edge (built by {@link #expandDiffHop}).
     */
    private void acceptDiffNeighbour(final Txn<ByteBuffer> readTxn, final DiffFrontier from,
                                     final TemporalAccess access, final Expand hop, final EdgeStep edgeStep,
                                     final ElementId.Edge edgeId, final boolean isLastHop,
                                     final Predicate<Map<String, Val>> wherePredicate,
                                     final List<DiffFrontier> nextFrontier, final List<DiffMatch> matches,
                                     final Instant deadline) {
        checkDeadline(deadline);
        final long neighbourUid = edgeStep.neighbourUid();
        final Optional<GraphNodeDb.NodeVersion> target = access.getNode(readTxn, neighbourUid);
        if (target.isEmpty()
            || !matchesTargetConstraint(readTxn, hop.targetLabels(), hop.targetPropertyPredicate(), target.get())) {
            return;
        }
        final Map<String, Val> row = new HashMap<>(from.row());
        row.putAll(rowFor(hop.targetVariable(), target.get().properties()));
        if (hop.edgeVariable() != null) {
            row.putAll(rowFor(hop.edgeVariable(), edgeStep.edgeProperties()));
        }
        final List<ElementId> identity = new ArrayList<>(from.identity().size() + 2);
        identity.addAll(from.identity());
        identity.add(edgeId);
        identity.add(new ElementId.Node(neighbourUid));

        if (isLastHop) {
            if (wherePredicate.test(row)) {
                matches.add(new DiffMatch(identity, row));
            }
        } else {
            nextFrontier.add(new DiffFrontier(neighbourUid, row, identity));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // RETURN GRAPH element collection (docs/temporal-cypher-diff-operator.md §4.4/§5.6,
    // docs/graphdb-cytoscape-visualisation.html §3)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Collects the {@code RETURN GRAPH} element-row union for a plain (non-{@code DIFF}) query: every distinct
     * node and edge on a path that fully matches {@code plan}'s pattern (and its {@code WHERE}, if any), keyed by
     * {@link ElementId} - the dedup key ({@link ElementId} equality is by interned UID(s), never a projected
     * value), honouring whichever temporal clause the query carries (or "latest" if none - see {@link
     * #resolveAccess}).
     *
     * <p><b>Preconditions:</b> {@code plan} is a fixed-length pattern (a {@link VarLengthExpand} throws - rejected
     * earlier at compile time for {@code RETURN GRAPH}, see {@code CypherToLogicalPlan.compileReturnGraph}).</p>
     *
     * @return never null (may be empty); insertion order is deterministic (first-encountered-on-a-surviving-path),
     *         but callers should not rely on any particular order beyond that.
     */
    public Map<ElementId, ElementDetail> executeGraphBindings(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                                                              final @Nullable TemporalContext temporalContext,
                                                              final DateTimeSettings dateTimeSettings) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");
        final PlanShape shape = unwrap(plan);
        // A MATCH (n) or MATCH (n:Label) RETURN GRAPH ("show me the graph[, of these]") has no property anchor to
        // seek and no hops, so resolveAnchors cannot serve it. Route it to a bounded whole-graph / label-scoped
        // preview instead. (Only the plain path - not the DIFF per-instant path - offers this; a diff still needs
        // an anchored pattern.)
        final Map<ElementId, ElementDetail> elements = isWholeGraphPreview(shape)
                ? dumpWholeGraph(readTxn, shape, temporalContext, dateTimeSettings)
                : collectGraphElements(readTxn, plan, resolveAccess(temporalContext), dateTimeSettings);

        // A RETURN GRAPH LIMIT n bounds the result to n nodes plus the edges between them. The dump already scans
        // only n nodes; the anchored path collects the full matched union, so cap it here. No-op without a LIMIT,
        // and the DIFF path (which rejects LIMIT at compile time) never routes through here.
        if (shape.limit() != null && shape.limit() > 0) {
            return capToNodeLimit(elements, shape.limit());
        }
        return elements;
    }

    /**
     * Caps a {@code RETURN GRAPH} element union to the first {@code nodeLimit} distinct nodes (in insertion order)
     * plus every edge whose endpoints are both among those kept nodes - so a {@code LIMIT} never leaves a dangling
     * edge to a dropped node.
     */
    private static Map<ElementId, ElementDetail> capToNodeLimit(final Map<ElementId, ElementDetail> elements,
                                                                final long nodeLimit) {
        final Set<Long> keptNodeUids = new HashSet<>();
        final Map<ElementId, ElementDetail> capped = new LinkedHashMap<>();
        for (final Map.Entry<ElementId, ElementDetail> entry : elements.entrySet()) {
            if (entry.getKey() instanceof final ElementId.Node node && keptNodeUids.size() < nodeLimit) {
                keptNodeUids.add(node.uid());
                capped.put(entry.getKey(), entry.getValue());
            }
        }
        for (final Map.Entry<ElementId, ElementDetail> entry : elements.entrySet()) {
            if (entry.getKey() instanceof final ElementId.Edge edge
                && keptNodeUids.contains(edge.srcUid())
                && keptNodeUids.contains(edge.dstUid())) {
                capped.put(entry.getKey(), entry.getValue());
            }
        }
        return capped;
    }

    private static boolean isWholeGraphPreview(final PlanShape shape) {
        return shape.nodeScan().propertyAnchor() == null
                && shape.hops().isEmpty()
                && shape.varLengthExpand() == null;
    }

    /**
     * The graph preview behind a {@code MATCH (n) RETURN GRAPH} (the Graph DB Data tab's default query) or its
     * label-scoped form {@code MATCH (n:Label) RETURN GRAPH}: the first {@code LIMIT} (or
     * {@link #DEFAULT_WHOLE_GRAPH_NODE_CAP}) distinct nodes present at the instant that carry the required label(s),
     * plus every edge between two of those included nodes. It lets an analyst see that a graph holds data and what
     * shape it takes, or browse one label's nodes, without knowing a specific id up front - the one access path that
     * walks the store rather than seeking the property index, so it is deliberately bounded.
     *
     * <p>Label filter: a label-only anchor has no property index to seek, so the required labels are applied as a
     * post-read filter on each scanned node ({@link GraphNodeDb.NodeVersion#labelUids()}); an unknown label matches
     * nothing. Edges: there is no untyped adjacency index (the stores are per-edge-type keyed), so this enumerates
     * the interned edge types and reuses the ordinary as-of {@link TemporalAccess#expandOut} for each included node
     * - keeping the hardened temporal edge read as the single source of truth rather than re-decoding edge versions
     * here. Only the {@code AS OF}/latest access is supported; {@code AROUND}/{@code BETWEEN} over an unanchored
     * pattern is rejected (add an anchor, or use {@code AS OF}).</p>
     */
    private Map<ElementId, ElementDetail> dumpWholeGraph(final Txn<ByteBuffer> readTxn, final PlanShape shape,
                                                         final @Nullable TemporalContext temporalContext,
                                                         final DateTimeSettings dateTimeSettings) {
        final Instant asOf = resolveDumpInstant(temporalContext);
        final TemporalAccess access = asOfAccess(asOf);
        final long nodeCap = shape.limit() != null && shape.limit() > 0
                ? shape.limit()
                : wholeGraphNodeCap;

        final Predicate<Map<String, Val>> wherePredicate = shape.where() == null
                ? row -> true
                : expressionPredicateFactory
                        .createOptional(shape.where(), rowAccessors(), dateTimeSettings)
                        .orElse(row -> true);

        final Instant deadline = Instant.now().plus(maxTraversalDuration);
        final Map<ElementId, ElementDetail> elements = new LinkedHashMap<>();

        // 0. Resolve any label constraint (MATCH (n:Label) RETURN GRAPH). An unknown label matches nothing.
        final Set<Long> requiredLabelUids = new HashSet<>();
        for (final String label : shape.nodeScan().labels()) {
            final Optional<Long> labelUid = lookupUid(readTxn, stores.getLabelUids(), label);
            if (labelUid.isEmpty()) {
                return elements;
            }
            requiredLabelUids.add(labelUid.get());
        }

        // 1. Nodes: distinct nodes present at the instant that carry the required label(s) and pass any WHERE,
        //    capped at nodeCap. Streaming lets the scan stop once nodeCap matches are collected.
        final Set<Long> includedNodeUids = new HashSet<>();
        stores.getNodes().forEachDistinctNodeUid(readTxn, nodeUid -> {
            if (includedNodeUids.size() >= nodeCap) {
                return false;
            }
            checkDeadline(deadline);
            final Optional<GraphNodeDb.NodeVersion> node = access.getNode(readTxn, nodeUid);
            if (node.isPresent()
                && node.get().labelUids().containsAll(requiredLabelUids)
                && wherePredicate.test(rowFor(shape.nodeScan().variable(), node.get().properties()))) {
                includedNodeUids.add(nodeUid);
                elements.put(new ElementId.Node(nodeUid), nodeDetail(readTxn, node.get()));
            }
            return true;
        });

        // 2. Edges strictly between two included nodes, per interned edge type.
        for (final long edgeTypeUid : enumerateEdgeTypeUids(readTxn)) {
            if (elements.size() >= MAX_ACCUMULATED_ROWS) {
                break;
            }
            final String edgeTypeName = decodeUidName(readTxn, stores.getEdgeTypeUids(), edgeTypeUid);
            for (final long srcUid : includedNodeUids) {
                checkDeadline(deadline);
                access.expandOut(readTxn, srcUid, edgeTypeUid, step -> {
                    final long dstUid = step.neighbourUid();
                    if (includedNodeUids.contains(dstUid)) {
                        elements.put(
                                new ElementId.Edge(srcUid, edgeTypeUid, dstUid),
                                new ElementDetail(List.of(edgeTypeName), step.edgeProperties(),
                                        new ElementId.Node(srcUid), new ElementId.Node(dstUid)));
                    }
                });
            }
        }
        return elements;
    }

    private static Instant resolveDumpInstant(final @Nullable TemporalContext temporalContext) {
        if (temporalContext == null) {
            return LATEST;
        }
        return switch (temporalContext.mode()) {
            case AS_OF -> temporalContext.instant();
            case AROUND, BETWEEN -> throw new UnsupportedOperationException(
                    "not yet supported: an unanchored MATCH (n) RETURN GRAPH with AROUND/BETWEEN - add a label and "
                    + "property anchor, or use AS OF");
        };
    }

    /** Enumerates every interned edge-type UID (there is usually only a handful), decoded to a long exactly as
     * {@link #lookupUid} decodes a name lookup's result. */
    private List<Long> enumerateEdgeTypeUids(final Txn<ByteBuffer> readTxn) {
        final List<Long> typeUids = new ArrayList<>();
        stores.getEdgeTypeUids().forEachUid(readTxn, uidBuffer ->
                typeUids.add(UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate())));
        return typeUids;
    }

    /**
     * Expand-on-demand: the element union of the node with external id {@code externalId} plus its neighbours
     * across <em>all</em> edge types, both directions, at the latest instant - the graph view's right-click
     * "Expand neighbours". Unlike a Cypher anchor, the centre node is sought by its <em>identity</em>
     * ({@link GraphStores#getNodeUids} maps external id &harr; UID), so it works for any node regardless of which
     * property (if any) is indexed. There is no untyped adjacency index, so all edge types are enumerated and the
     * hardened as-of/window {@link TemporalAccess#expandOut}/{@link TemporalAccess#expandIn} are reused per type.
     * Bounded by {@code maxNeighbours} distinct neighbour nodes (a hub is not fully materialised). The
     * {@code temporalContext} is honoured (via {@link #resolveAccess}) so an expand matches the instant/window of
     * the query that produced the displayed graph - {@code null} means the latest snapshot.
     *
     * @return the centre node, its included neighbours, and the edges connecting them; empty if the id is unknown
     *         or the node is absent at the resolved instant.
     */
    public Map<ElementId, ElementDetail> expandNodeNeighbours(final Txn<ByteBuffer> readTxn, final String externalId,
                                                              final int maxNeighbours,
                                                              final @Nullable TemporalContext temporalContext) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(externalId, "externalId");

        final Map<ElementId, ElementDetail> elements = new LinkedHashMap<>();
        final Optional<Long> centreUid = lookupUid(readTxn, stores.getNodeUids(), externalId);
        if (centreUid.isEmpty()) {
            return elements;
        }
        final long uid = centreUid.get();
        final TemporalAccess access = resolveAccess(temporalContext);
        final Optional<GraphNodeDb.NodeVersion> centre = access.getNode(readTxn, uid);
        if (centre.isEmpty()) {
            return elements;
        }
        elements.put(new ElementId.Node(uid), nodeDetail(readTxn, centre.get()));

        final Instant deadline = Instant.now().plus(maxTraversalDuration);
        final int[] neighbourCount = {0};

        for (final long edgeTypeUid : enumerateEdgeTypeUids(readTxn)) {
            if (neighbourCount[0] >= maxNeighbours) {
                break;
            }
            final String typeName = decodeUidName(readTxn, stores.getEdgeTypeUids(), edgeTypeUid);
            // Outgoing: centre -> neighbour.
            access.expandOut(readTxn, uid, edgeTypeUid, step ->
                    addNeighbour(readTxn, access, elements, neighbourCount, maxNeighbours, deadline,
                            uid, edgeTypeUid, typeName, step.neighbourUid(), step.edgeProperties(), true));
            // Incoming: neighbour -> centre.
            access.expandIn(readTxn, uid, edgeTypeUid, step ->
                    addNeighbour(readTxn, access, elements, neighbourCount, maxNeighbours, deadline,
                            uid, edgeTypeUid, typeName, step.neighbourUid(), step.edgeProperties(), false));
        }
        return elements;
    }

    private void addNeighbour(final Txn<ByteBuffer> readTxn, final TemporalAccess access,
                              final Map<ElementId, ElementDetail> elements, final int[] neighbourCount,
                              final int maxNeighbours, final Instant deadline, final long centreUid,
                              final long edgeTypeUid, final String typeName, final long neighbourUid,
                              final Map<String, Val> edgeProperties, final boolean outgoing) {
        if (neighbourCount[0] >= maxNeighbours) {
            return;
        }
        checkDeadline(deadline);
        final Optional<GraphNodeDb.NodeVersion> neighbour = access.getNode(readTxn, neighbourUid);
        if (neighbour.isEmpty()) {
            return;
        }
        final ElementId.Node neighbourNode = new ElementId.Node(neighbourUid);
        if (elements.putIfAbsent(neighbourNode, nodeDetail(readTxn, neighbour.get())) == null) {
            neighbourCount[0]++;
        }
        final long srcUid = outgoing ? centreUid : neighbourUid;
        final long dstUid = outgoing ? neighbourUid : centreUid;
        elements.put(
                new ElementId.Edge(srcUid, edgeTypeUid, dstUid),
                new ElementDetail(List.of(typeName), edgeProperties,
                        new ElementId.Node(srcUid), new ElementId.Node(dstUid)));
    }

    /**
     * As {@link #executeGraphBindings}, but at a single fixed instant - the per-snapshot half of a {@code DIFF
     * ... RETURN GRAPH} query, called once for the baseline and once for the comparison instant (mirrors {@link
     * #executeDiffBindings}'s own {@code Instant asOf} overload). The caller ({@code GraphElementExecutor}) turns
     * each instant's result into singleton-identity {@link DiffMatch}es and feeds both to the existing, unchanged
     * {@link DiffOperator#classify} - the per-element classification the annotated-subgraph mode needs falls out
     * of that generic path-identity machinery for free once identity is a one-element list.
     */
    public Map<ElementId, ElementDetail> executeGraphBindingsAsOf(final Txn<ByteBuffer> readTxn,
                                                                  final LogicalPlan plan, final Instant asOf,
                                                                  final DateTimeSettings dateTimeSettings) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");
        return collectGraphElements(readTxn, plan, asOfAccess(asOf), dateTimeSettings);
    }

    private Map<ElementId, ElementDetail> collectGraphElements(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                                                               final TemporalAccess access,
                                                               final DateTimeSettings dateTimeSettings) {
        final PlanShape shape = unwrap(plan);
        if (shape.varLengthExpand() != null) {
            throw new UnsupportedOperationException(
                    "not yet supported: RETURN GRAPH over a variable-length pattern (rejected at compile time)");
        }

        final Predicate<Map<String, Val>> wherePredicate = shape.where() == null
                ? row -> true
                : expressionPredicateFactory
                        .createOptional(shape.where(), rowAccessors(), dateTimeSettings)
                        .orElse(row -> true);

        final Instant deadline = Instant.now().plus(maxTraversalDuration);
        final Map<ElementId, ElementDetail> allElements = new LinkedHashMap<>();

        List<GraphFrontier> frontier = new ArrayList<>();
        for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan(), access)) {
            final Optional<GraphNodeDb.NodeVersion> anchor = access.getNode(readTxn, anchorUid);
            if (anchor.isEmpty()) {
                continue;
            }
            final Map<ElementId, ElementDetail> elements = new LinkedHashMap<>();
            elements.put(new ElementId.Node(anchorUid), nodeDetail(readTxn, anchor.get()));
            frontier.add(new GraphFrontier(
                    anchorUid, rowFor(shape.nodeScan().variable(), anchor.get().properties()), elements));
        }

        if (shape.hops().isEmpty()) {
            for (final GraphFrontier f : frontier) {
                if (wherePredicate.test(f.row())) {
                    allElements.putAll(f.elements());
                }
            }
            return allElements;
        }

        for (int i = 0; i < shape.hops().size(); i++) {
            checkDeadline(deadline);
            final Expand hop = shape.hops().get(i);
            final boolean isLastHop = i == shape.hops().size() - 1;
            final List<GraphFrontier> next = new ArrayList<>();
            for (final GraphFrontier f : frontier) {
                expandGraphHop(readTxn, f, access, hop, isLastHop, wherePredicate, next, allElements, deadline);
            }
            frontier = next;
        }
        return allElements;
    }

    /** A {@code RETURN GRAPH} traversal frontier entry: as {@link Frontier}, plus every element (node/edge)
     * touched by the path so far, keyed by {@link ElementId} - merged into the caller's shared accumulator only
     * once a path reaches the pattern's end and passes {@code WHERE} (mirrors {@link DiffFrontier}'s identity
     * tuple, but as a detail map rather than an ordered list, since a {@code RETURN GRAPH} row is per-element, not
     * per-path). */
    private record GraphFrontier(long nodeUid, Map<String, Val> row, Map<ElementId, ElementDetail> elements) {
    }

    /** The {@code RETURN GRAPH} counterpart of {@link #expandDiffHop}: expands one hop from {@code from},
     * dispatching to {@link #acceptGraphNeighbour} with the traversed edge's oriented identity and endpoints. */
    private void expandGraphHop(final Txn<ByteBuffer> readTxn, final GraphFrontier from, final TemporalAccess access,
                                final Expand hop, final boolean isLastHop,
                                final Predicate<Map<String, Val>> wherePredicate,
                                final List<GraphFrontier> nextFrontier,
                                final Map<ElementId, ElementDetail> allElements, final Instant deadline) {
        final Optional<Long> edgeTypeUid = resolveRequiredEdgeTypeUid(readTxn, hop.edgeType());
        if (edgeTypeUid.isEmpty()) {
            return;
        }
        final long typeUid = edgeTypeUid.get();
        final ElementId.Node fromNode = new ElementId.Node(from.nodeUid());

        switch (hop.direction()) {
            case OUT -> access.expandOut(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptGraphNeighbour(
                    readTxn, from, access, hop, edgeStep,
                    new ElementId.Edge(from.nodeUid(), typeUid, edgeStep.neighbourUid()),
                    fromNode, new ElementId.Node(edgeStep.neighbourUid()),
                    isLastHop, wherePredicate, nextFrontier, allElements, deadline));
            case IN -> access.expandIn(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptGraphNeighbour(
                    readTxn, from, access, hop, edgeStep,
                    new ElementId.Edge(edgeStep.neighbourUid(), typeUid, from.nodeUid()),
                    new ElementId.Node(edgeStep.neighbourUid()), fromNode,
                    isLastHop, wherePredicate, nextFrontier, allElements, deadline));
            case BOTH -> {
                access.expandOut(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptGraphNeighbour(
                        readTxn, from, access, hop, edgeStep,
                        new ElementId.Edge(from.nodeUid(), typeUid, edgeStep.neighbourUid()),
                        fromNode, new ElementId.Node(edgeStep.neighbourUid()),
                        isLastHop, wherePredicate, nextFrontier, allElements, deadline));
                access.expandIn(readTxn, from.nodeUid(), typeUid, edgeStep -> acceptGraphNeighbour(
                        readTxn, from, access, hop, edgeStep,
                        new ElementId.Edge(edgeStep.neighbourUid(), typeUid, from.nodeUid()),
                        new ElementId.Node(edgeStep.neighbourUid()), fromNode,
                        isLastHop, wherePredicate, nextFrontier, allElements, deadline));
            }
        }
    }

    /** Accepts one neighbour of a {@code RETURN GRAPH} hop: validates the target-node constraint (as {@link
     * #acceptChainNeighbour}), extends the row (for {@code WHERE}) and the element-detail map (the traversed edge
     * plus the target node), then either merges into {@code allElements} (last hop, if {@code WHERE} passes) or
     * pushes a new frontier entry. {@code sourceId}/{@code targetId} are the edge's oriented endpoints, built by
     * {@link #expandGraphHop} exactly as {@link #expandDiffHop} builds {@code edgeId}'s orientation. */
    private void acceptGraphNeighbour(final Txn<ByteBuffer> readTxn, final GraphFrontier from,
                                      final TemporalAccess access, final Expand hop, final EdgeStep edgeStep,
                                      final ElementId.Edge edgeId, final ElementId.Node sourceId,
                                      final ElementId.Node targetId, final boolean isLastHop,
                                      final Predicate<Map<String, Val>> wherePredicate,
                                      final List<GraphFrontier> nextFrontier,
                                      final Map<ElementId, ElementDetail> allElements, final Instant deadline) {
        checkDeadline(deadline);
        final long neighbourUid = edgeStep.neighbourUid();
        final Optional<GraphNodeDb.NodeVersion> target = access.getNode(readTxn, neighbourUid);
        if (target.isEmpty()
            || !matchesTargetConstraint(readTxn, hop.targetLabels(), hop.targetPropertyPredicate(), target.get())) {
            return;
        }
        final Map<String, Val> row = new HashMap<>(from.row());
        row.putAll(rowFor(hop.targetVariable(), target.get().properties()));
        if (hop.edgeVariable() != null) {
            row.putAll(rowFor(hop.edgeVariable(), edgeStep.edgeProperties()));
        }

        final Map<ElementId, ElementDetail> elements = new LinkedHashMap<>(from.elements());
        // hop.edgeType() is guaranteed non-null here: resolveRequiredEdgeTypeUid (called by expandGraphHop before
        // any neighbour is visited) throws for a null/untyped edge pattern, so this callback is only ever reached
        // for a hop that named a concrete edge type.
        elements.put(edgeId, new ElementDetail(
                List.of(hop.edgeType()), edgeStep.edgeProperties(), sourceId, targetId));
        elements.put(new ElementId.Node(neighbourUid), nodeDetail(readTxn, target.get()));

        if (isLastHop) {
            if (wherePredicate.test(row)) {
                allElements.putAll(elements);
            }
        } else {
            nextFrontier.add(new GraphFrontier(neighbourUid, row, elements));
        }
    }

    /** Builds a node's {@link ElementDetail}: its actual stored label set (reverse-resolved to names via {@link
     * #decodeUidName}) and its own property map - never the pattern's declared label constraint, which may be a
     * strict subset of the node's real labels. */
    private ElementDetail nodeDetail(final Txn<ByteBuffer> readTxn, final GraphNodeDb.NodeVersion node) {
        final List<String> labels = new ArrayList<>(node.labelUids().size());
        for (final long labelUid : node.labelUids()) {
            labels.add(decodeUidName(readTxn, stores.getLabelUids(), labelUid));
        }
        return new ElementDetail(labels, node.properties(), null, null);
    }

    /**
     * Reverse-resolves an interned UID back to the original external name it was interned from (see {@code
     * UidLookupDb.getValue(Txn, long)}) - used for a node's label names during traversal ({@link #nodeDetail}) and,
     * package-visible, by {@link GraphElementExecutor} to render a node's external id / an edge's endpoint ids at
     * render time.
     *
     * @throws IllegalStateException if {@code uid} was never interned in {@code db} - should be unreachable, since
     *                                every UID this class hands to this method came from that same interning
     *                                namespace moments earlier.
     */
    static String decodeUidName(final Txn<ByteBuffer> readTxn, final UidLookupDb db, final long uid) {
        return StandardCharsets.UTF_8.decode(db.getValue(readTxn, uid).duplicate()).toString();
    }

    /**
     * Dispatches to {@link #finalizeRows} (the ordinary path) or {@link #finalizeAggregatedRows} (Task 1.3)
     * depending on whether the compiled {@code RETURN} had an aggregate item.
     * <b>Null status:</b> {@code rows}/{@code shape} are never null; {@code aggregation} is nullable; never
     * returns null.
     */
    private static List<Val[]> finalize(final List<Map<String, Val>> rows, final PlanShape shape,
                                        final boolean distinct, final @Nullable CypherAggregation aggregation) {
        return aggregation == null
                ? finalizeRows(rows, shape, distinct)
                : finalizeAggregatedRows(rows, shape, aggregation, distinct);
    }

    /**
     * Task P7.2: throws {@link GraphTraversalLimitExceededException} once {@code deadline} has passed - checked
     * once per neighbour visited (Code-review fix: previously once per hop/BFS-depth iteration only, which did
     * not actually bound a single hop/depth's own wide fan-out - see {@link #acceptChainNeighbour}), the backstop
     * against a pathological query running for an unbounded time on the calling thread (see
     * {@link #MAX_TRAVERSAL_DURATION}'s Javadoc).
     */
    private void checkDeadline(final Instant deadline) {
        if (Instant.now().isAfter(deadline)) {
            throw new GraphTraversalLimitExceededException(
                    "graph traversal exceeded the maximum allowed duration of " + maxTraversalDuration);
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // review finding F3 fix: bounded row accumulation
    // ------------------------------------------------------------------------------------------------------

    /**
     * The accumulation sink every traversal shape (the zero-hop anchor loop, the fixed-length chain via {@link
     * #acceptChainNeighbour}, the var-length BFS via {@link #acceptVarLengthRow}) adds its matched rows into,
     * instead of a bare {@code List<Map<String, Val>>} - so {@link #execute} can swap in either an unbounded,
     * ceiling-guarded sink ({@link UnboundedRowSink}) or a size-bounded top-N heap ({@link TopNRowSink}) without
     * any accumulation call site needing to know which is in play. {@code size()} is read by the existing
     * {@code rowCap} early-exit checks (unchanged by this fix); each implementation enforces its own bound inside
     * {@code add}.
     */
    private interface RowSink {

        /** Offers one matched row for accumulation; may throw {@link GraphTraversalLimitExceededException} (see
         * {@link UnboundedRowSink}), or silently discard the row if it does not qualify for a bounded top-N (see
         * {@link TopNRowSink}) - never silently drops a row that a bound does not require it to. */
        void add(Map<String, Val> row);

        /** The number of rows currently held - read by the existing {@code rowCap} checks, not affected by this
         * fix's own ceiling/bound (those are enforced inside {@link #add}, not surfaced here). */
        int size();

        /** Extracts the accumulated rows for {@link #finalizeRows}/{@link #finalizeAggregatedRows}. Order is not
         * guaranteed to be traversal order (see {@link TopNRowSink#drain}); {@code finalizeRows}' own {@code
         * ORDER BY} sort (unconditional whenever sort keys are present, regardless of how {@code rows} got here)
         * is what fixes the final order in every case. */
        List<Map<String, Val>> drain();
    }

    /**
     * The ordinary accumulation sink: an unbounded {@link ArrayList}, guarded only by the F3 hard ceiling ({@link
     * #MAX_ACCUMULATED_ROWS} in production; a tiny test-seam value in a test - see the test-only constructor).
     * Used whenever a bounded top-N is not sound - no {@code LIMIT}, {@code DISTINCT}, aggregation, or a {@code
     * LIMIT} itself larger than the ceiling (see {@link #execute}'s dispatch) - i.e. whenever every matching row
     * genuinely must be seen before the correct answer can be produced. Once accumulation would exceed the
     * ceiling, {@code add} throws {@link GraphTraversalLimitExceededException} rather than silently continuing
     * (which would eventually {@code OutOfMemoryError}) or silently truncating (which would return a wrong,
     * unflagged partial answer) - the rows already accumulated are discarded with the exception, not returned.
     */
    private static final class UnboundedRowSink implements RowSink {

        private final List<Map<String, Val>> rows = new ArrayList<>();
        private final long maxAccumulatedRows;

        UnboundedRowSink(final long maxAccumulatedRows) {
            this.maxAccumulatedRows = maxAccumulatedRows;
        }

        @Override
        public void add(final Map<String, Val> row) {
            if (rows.size() >= maxAccumulatedRows) {
                throw new GraphTraversalLimitExceededException(
                        "graph traversal accumulated more than the maximum allowed " + maxAccumulatedRows
                        + " rows in memory; narrow the pattern's label/property constraints, add or tighten a "
                        + "LIMIT, or add a WHERE filter to reduce the result set");
            }
            rows.add(row);
        }

        @Override
        public int size() {
            return rows.size();
        }

        @Override
        public List<Map<String, Val>> drain() {
            return rows;
        }
    }

    /**
     * A bounded top-N accumulation sink for the common {@code ORDER BY ... LIMIT n} shape (without {@code
     * DISTINCT} or aggregation - see {@link #execute}'s dispatch): keeps at most {@code limit} rows in memory at
     * any time via a size-{@code limit} max-heap keyed on the compiled {@code ORDER BY} comparator, evicting the
     * current worst-ranked row once full, instead of materialising every matching row before sorting and
     * truncating. This is what closes review finding F3's "sharp edge" - {@code ORDER BY ... LIMIT n} is the
     * common interactive case, and was previously exactly the unbounded one (the {@code LIMIT} disabled the
     * traversal-time {@code rowCap} early-exit so every row could be seen for sorting).
     *
     * <p><b>Correctness:</b> {@link #drain} returns the &le;{@code limit} survivors in heap order, not sorted
     * order - relying on {@code finalizeRows}' own unconditional {@code rows.sort(rowComparator(...))} (run
     * whenever {@code ORDER BY} is present, regardless of how {@code rows} got here) to produce the final,
     * byte-for-byte identical comparator/tie-break/{@code nullsLast} ordering the previous
     * materialise-then-sort-then-truncate path produced - just computed over &le;{@code limit} rows instead of
     * every matching row. Since {@code DISTINCT}/aggregation are excluded from this sink entirely (see {@link
     * #execute}), every row offered here counts toward the limit as itself; there is no de-duplication to
     * reconcile against the bound.</p>
     */
    private static final class TopNRowSink implements RowSink {

        private final int limit;
        private final Comparator<Map<String, Val>> comparator;
        // A max-heap on `comparator`: worstFirst.peek() is always the current worst-ranked survivor (comparator's
        // reversed ordering makes the heap's "least" element the comparator's "greatest"), so a new candidate is
        // only kept once the heap is at capacity if it beats that worst survivor.
        private final PriorityQueue<Map<String, Val>> worstFirst;

        TopNRowSink(final int limit, final Comparator<Map<String, Val>> comparator) {
            this.limit = limit;
            this.comparator = comparator;
            this.worstFirst = new PriorityQueue<>(Math.max(1, limit), comparator.reversed());
        }

        @Override
        public void add(final Map<String, Val> row) {
            if (limit <= 0) {
                return;
            }
            if (worstFirst.size() < limit) {
                worstFirst.add(row);
            } else if (comparator.compare(row, worstFirst.peek()) < 0) {
                worstFirst.poll();
                worstFirst.add(row);
            }
        }

        @Override
        public int size() {
            return worstFirst.size();
        }

        @Override
        public List<Map<String, Val>> drain() {
            return new ArrayList<>(worstFirst);
        }
    }

    /** A traversal frontier entry: the node reached so far, and the accumulated row of every variable bound. */
    private record Frontier(long nodeUid, Map<String, Val> row) {
    }

    private void expandChainHop(final Txn<ByteBuffer> readTxn, final long fromUid, final TemporalAccess access,
                                final Expand hop, final Map<String, Val> rowSoFar, final boolean isLastHop,
                                final Predicate<Map<String, Val>> wherePredicate, final List<Frontier> nextFrontier,
                                final RowSink rowSink, final long rowCap,
                                final Instant deadline) {
        final Optional<Long> edgeTypeUid = resolveRequiredEdgeTypeUid(readTxn, hop.edgeType());
        if (edgeTypeUid.isEmpty()) {
            return;
        }

        final Consumer<EdgeStep> onNeighbour = edgeStep -> acceptChainNeighbour(
                readTxn, edgeStep, access, hop, rowSoFar, isLastHop, wherePredicate, nextFrontier, rowSink,
                rowCap, deadline);

        collectNeighbours(readTxn, fromUid, edgeTypeUid.get(), access, hop.direction(), onNeighbour);
    }

    private void acceptChainNeighbour(final Txn<ByteBuffer> readTxn, final EdgeStep edgeStep,
                                      final TemporalAccess access,
                                      final Expand hop, final Map<String, Val> rowSoFar, final boolean isLastHop,
                                      final Predicate<Map<String, Val>> wherePredicate,
                                      final List<Frontier> nextFrontier, final RowSink rowSink,
                                      final long rowCap, final Instant deadline) {
        final long neighbourUid = edgeStep.neighbourUid();
        // Task P7.2: once a compiled LIMIT is satisfied, stop accumulating further rows at this hop - does not
        // abort a cursor scan already in flight (the DAO layer has no cancellation hook), but does stop this
        // traversal from expanding to further frontier nodes/hops once the cap is reached.
        if (isLastHop && rowSink.size() >= rowCap) {
            return;
        }
        // Code-review fix: previously the wall-clock deadline was only checked once per hop (execute()'s outer
        // loop), so a single hop with a wide fan-out and no LIMIT - the exact scenario MAX_TRAVERSAL_DURATION's
        // own Javadoc cites as its reason for existing - was never actually bounded, since this callback runs
        // once per neighbour inside one uninterrupted cursor scan. Checking here, once per neighbour, closes
        // that gap; throwing from inside this callback propagates up through the cursor's own try-with-resources
        // (see GraphAdjacencyDb/GraphInEdgeDb), so the scan is aborted cleanly rather than merely detected late.
        checkDeadline(deadline);
        final Optional<GraphNodeDb.NodeVersion> target = access.getNode(readTxn, neighbourUid);
        if (target.isEmpty()
            || !matchesTargetConstraint(
                    readTxn, hop.targetLabels(), hop.targetPropertyPredicate(), target.get())) {
            return;
        }
        final Map<String, Val> row = new HashMap<>(rowSoFar);
        row.putAll(rowFor(hop.targetVariable(), target.get().properties()));
        // Bind the traversed edge's own properties to the hop's relationship variable, if it named one, so a
        // projection/filter over e.g. c.startTime resolves (before this, edge data was discarded mid-traversal).
        if (hop.edgeVariable() != null) {
            row.putAll(rowFor(hop.edgeVariable(), edgeStep.edgeProperties()));
        }
        if (isLastHop) {
            if (wherePredicate.test(row)) {
                rowSink.add(row);
            }
        } else {
            nextFrontier.add(new Frontier(neighbourUid, row));
        }
    }

    /**
     * Task P3.1: checks a reached neighbour node against its hop's own {@code targetLabels}/
     * {@code targetPropertyPredicate} (shared by {@link Expand} and {@link VarLengthExpand}) - before this, a
     * pattern like {@code -[:T]->(b:Account {status:'active'})} silently never checked {@code b}'s constraint at
     * all.
     */
    private boolean matchesTargetConstraint(final Txn<ByteBuffer> readTxn, final List<String> targetLabels,
                                            final @Nullable ExpressionOperator targetPropertyPredicate,
                                            final GraphNodeDb.NodeVersion target) {
        for (final String label : targetLabels) {
            final Optional<Long> labelUid = lookupUid(readTxn, stores.getLabelUids(), label);
            if (labelUid.isEmpty() || !target.labelUids().contains(labelUid.get())) {
                return false;
            }
        }
        if (targetPropertyPredicate != null) {
            // Mirrors resolveAnchors()'s own re-validation: targetPropertyPredicate's terms are unqualified
            // (e.g. "status", not "b.status"), so test them directly against the node's bare-named properties,
            // not the "variable.property"-keyed row rowFor() builds.
            final Predicate<Map<String, Val>> propertyPredicate = expressionPredicateFactory
                    .createOptional(targetPropertyPredicate, rowAccessors(), DateTimeSettings.builder().build())
                    .orElse(row -> true);
            if (!propertyPredicate.test(target.properties())) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------
    // variable-length expand (Task P3.3)
    // ------------------------------------------------------------------------------------------------------

    /**
     * One in-progress path of a bounded BFS: the node reached so far, the accumulated row, and {@code visited} -
     * the set of node UIDs on THIS path (a fresh set per branch, not one global per-anchor set - see this
     * method's own Javadoc note below).
     */
    private record PathState(long nodeUid, Map<String, Val> row, Set<Long> visited) {
    }

    /**
     * Task P3.3: a bounded breadth-first search over the adjacency store for a single
     * {@code -[:T*min..max]->} hop. Each depth's neighbours are fully materialised via a single, synchronous
     * {@link GraphAdjacencyDb#expandOut}/{@link GraphInEdgeDb#expandIn} call (each of which already closes its
     * own cursor before returning) before the next depth is explored using the plain materialised list - a
     * nested cursor is never opened from inside another cursor's callback (design doc &sect;8's flagged
     * cursor-lifetime risk).
     *
     * <p>{@code visited} is per-path, not a single set shared across the whole BFS: a node reached via two
     * distinct, non-overlapping paths (or the same node at two different depths within range) is two valid,
     * distinct results - only a node repeating <em>within one path</em> is a cycle to guard against. Termination
     * is guaranteed by the {@code depth <= maxHops} loop bound alone ({@link VarLengthExpand}'s own constructor
     * enforces a finite {@code maxHops} - see its Javadoc), not by the cycle guard, which exists purely for
     * result correctness.</p>
     */
    private void expandVarLength(final Txn<ByteBuffer> readTxn, final long anchorUid, final TemporalAccess access,
                                 final VarLengthExpand varLengthExpand, final Map<String, Val> anchorRow,
                                 final Predicate<Map<String, Val>> wherePredicate,
                                 final RowSink rowSink, final long rowCap, final Instant deadline) {
        // Task P7.2: reject a hop range wider than MAX_VAR_LENGTH_HOPS up front, before any BFS work at all -
        // Cypher.g4 forbids the unbounded -[:T*]-> form but places no ceiling on an explicit range, so
        // -[:T*1..100000]-> was previously accepted and attempted verbatim.
        if (varLengthExpand.maxHops() > MAX_VAR_LENGTH_HOPS) {
            throw new GraphTraversalLimitExceededException(
                    "variable-length hop range [" + varLengthExpand.minHops() + ".." + varLengthExpand.maxHops()
                    + "] exceeds the maximum allowed maxHops of " + MAX_VAR_LENGTH_HOPS);
        }

        final Optional<Long> resolvedEdgeTypeUid = resolveRequiredEdgeTypeUid(readTxn, varLengthExpand.edgeType());
        if (resolvedEdgeTypeUid.isEmpty()) {
            return;
        }
        final long edgeTypeUid = resolvedEdgeTypeUid.get();

        if (varLengthExpand.minHops() == 0 && rowSink.size() < rowCap) {
            // A zero-length path binds the target variable to the anchor node itself.
            final Optional<GraphNodeDb.NodeVersion> anchorNode = access.getNode(readTxn, anchorUid);
            anchorNode.ifPresent(node -> acceptVarLengthRow(readTxn, varLengthExpand, anchorRow, node,
                    wherePredicate, rowSink));
        }

        // Task P7.2: a running total of BFS path-states explored across every depth of THIS call - guards the
        // case the hop-range ceiling alone does not: a modest maxHops against a high-fan-out hub node can still
        // explore an exponential number of paths, all materialised in memory at once.
        long pathStatesExplored = 1;

        List<PathState> frontier = List.of(new PathState(anchorUid, anchorRow, Set.of(anchorUid)));
        for (int depth = 1; depth <= varLengthExpand.maxHops() && !frontier.isEmpty() && rowSink.size() < rowCap;
                depth++) {
            checkDeadline(deadline);
            final List<PathState> next = new ArrayList<>();
            for (final PathState state : frontier) {
                if (rowSink.size() >= rowCap) {
                    break;
                }
                // Code-review fix: the deadline is checked as each neighbour is emitted from the adjacency
                // cursor, not only afterward. Previously the collector was a bare list-add, so a single node's
                // entire fan-out was drained into memory (unbounded scan time and allocation) before any guard
                // could fire - this now genuinely matches the fixed-length chain path, whose collector
                // (acceptChainNeighbour) also checks the deadline during the scan itself.
                final List<Long> neighbourUids = new ArrayList<>();
                collectNeighbours(
                        readTxn, state.nodeUid(), edgeTypeUid, access, varLengthExpand.direction(),
                        edgeStep -> {
                            // Var-length hops don't bind a per-hop edge variable, so only the neighbour UID is
                            // needed here; the edge's properties are ignored.
                            checkDeadline(deadline);
                            neighbourUids.add(edgeStep.neighbourUid());
                        });

                for (final long neighbourUid : neighbourUids) {
                    if (rowSink.size() >= rowCap) {
                        break;
                    }
                    checkDeadline(deadline);
                    if (state.visited().contains(neighbourUid)) {
                        continue;
                    }
                    pathStatesExplored++;
                    if (pathStatesExplored > maxVarLengthPathStates) {
                        throw new GraphTraversalLimitExceededException(
                                "variable-length traversal explored more than " + maxVarLengthPathStates
                                + " path-states; narrow the pattern's label/property constraints or reduce the "
                                + "hop range");
                    }
                    final Optional<GraphNodeDb.NodeVersion> target = access.getNode(readTxn, neighbourUid);
                    if (target.isEmpty()) {
                        continue;
                    }
                    final Map<String, Val> row = new HashMap<>(state.row());
                    row.putAll(rowFor(varLengthExpand.targetVariable(), target.get().properties()));

                    if (depth >= varLengthExpand.minHops()) {
                        acceptVarLengthRow(readTxn, varLengthExpand, row, target.get(), wherePredicate, rowSink);
                    }
                    if (depth < varLengthExpand.maxHops()) {
                        final Set<Long> visited = new HashSet<>(state.visited());
                        visited.add(neighbourUid);
                        next.add(new PathState(neighbourUid, row, visited));
                    }
                }
            }
            frontier = next;
        }
    }

    private void acceptVarLengthRow(final Txn<ByteBuffer> readTxn, final VarLengthExpand varLengthExpand,
                                    final Map<String, Val> row, final GraphNodeDb.NodeVersion target,
                                    final Predicate<Map<String, Val>> wherePredicate,
                                    final RowSink rowSink) {
        if (!matchesTargetConstraint(
                readTxn, varLengthExpand.targetLabels(), varLengthExpand.targetPropertyPredicate(), target)) {
            return;
        }
        if (wherePredicate.test(row)) {
            rowSink.add(row);
        }
    }

    private static void collectNeighbours(final Txn<ByteBuffer> readTxn, final long fromUid, final long edgeTypeUid,
                                          final TemporalAccess access, final Direction direction,
                                          final Consumer<EdgeStep> collector) {
        switch (direction) {
            case OUT -> access.expandOut(readTxn, fromUid, edgeTypeUid, collector);
            case IN -> access.expandIn(readTxn, fromUid, edgeTypeUid, collector);
            case BOTH -> {
                access.expandOut(readTxn, fromUid, edgeTypeUid, collector);
                // F11: a self-loop edge (neighbourUid == fromUid) was already emitted by expandOut above; an
                // undirected (BOTH) hop must still visit every *distinct* in-edge, so only the self-loop is
                // skipped here to avoid double-counting it, not the whole expandIn pass.
                access.expandIn(readTxn, fromUid, edgeTypeUid, edgeStep -> {
                    if (edgeStep.neighbourUid() != fromUid) {
                        collector.accept(edgeStep);
                    }
                });
            }
        }
    }

    /**
     * Resolves a hop's edge-type name to its interned UID - shared by {@link #expandChainHop} and
     * {@link #expandVarLength}, whose "resolve or reject an untyped pattern" logic was previously duplicated
     * verbatim in each. GraphAdjacencyDb/GraphInEdgeDb key every edge by a concrete edge-type UID; an untyped
     * pattern ({@code edgeType == null}, matching any type) has no single prefix to scan, and neither store
     * exposes an "any edge type" access path.
     *
     * @return empty if {@code edgeType} is a real type name that has never been interned (no edges of that type
     * exist) - the caller should treat this as "no matches", not an error.
     * @throws UnsupportedOperationException if {@code edgeType} is {@code null} (an untyped pattern).
     */
    private Optional<Long> resolveRequiredEdgeTypeUid(final Txn<ByteBuffer> readTxn, final @Nullable String edgeType) {
        if (edgeType == null) {
            throw new UnsupportedOperationException(
                    "not yet supported: an untyped edge pattern (matching any edge type) has no access path "
                    + "over the per-type-keyed adjacency stores");
        }
        return lookupUid(readTxn, stores.getEdgeTypeUids(), edgeType);
    }

    // ------------------------------------------------------------------------------------------------------
    // anchor resolution
    // ------------------------------------------------------------------------------------------------------

    private List<Long> resolveAnchors(final Txn<ByteBuffer> readTxn, final NodeScan nodeScan,
                                      final TemporalAccess access) {
        if (nodeScan.labels().isEmpty()) {
            throw new UnsupportedOperationException(
                    "not yet supported: an anchor MATCH requires at least one label to seek the property index "
                    + "(a full unlabelled scan is not indexed)");
        }
        final List<ExpressionItem> terms = nodeScan.propertyAnchor() == null
                ? List.of()
                : nodeScan.propertyAnchor().getChildren();
        if (terms == null || terms.isEmpty()) {
            throw new UnsupportedOperationException(
                    "not yet supported: an anchor MATCH requires at least one property predicate (a "
                    + "label-only \"scan every node with this label\" access path is not indexed)");
        }

        final List<Long> requiredLabelUids = new ArrayList<>(nodeScan.labels().size());
        for (final String label : nodeScan.labels()) {
            final Optional<Long> labelUid = lookupUid(readTxn, stores.getLabelUids(), label);
            if (labelUid.isEmpty()) {
                return List.of();
            }
            requiredLabelUids.add(labelUid.get());
        }

        final ExpressionTerm seekTerm = (ExpressionTerm) terms.getFirst();
        final Optional<Long> propKeyUid = lookupUid(readTxn, stores.getPropertyKeyUids(), seekTerm.getField());
        if (propKeyUid.isEmpty()) {
            return List.of();
        }
        final byte[] seekValueBytes = seekTerm.getValue().getBytes(StandardCharsets.UTF_8);
        final List<Long> candidates = stores.getPropertyIndex().findAnchors(
                readTxn, requiredLabelUids.getFirst(), propKeyUid.get(), seekValueBytes);

        // NodeScan.propertyAnchor()'s terms are unqualified (e.g. field "id", not "d.id" - compileNodeScan never
        // applies the variable prefix a WHERE clause's property accesses get), so re-validate it directly against
        // the node's own bare-named properties, not the "variable.property"-keyed row rowFor() builds.
        final Predicate<Map<String, Val>> propertyPredicate = expressionPredicateFactory
                .createOptional(nodeScan.propertyAnchor(), rowAccessors(), DateTimeSettings.builder().build())
                .orElse(row -> true);

        final List<Long> matched = new ArrayList<>();
        for (final long candidate : candidates) {
            final Optional<GraphNodeDb.NodeVersion> node = access.getNode(readTxn, candidate);
            if (node.isEmpty() || !node.get().labelUids().containsAll(requiredLabelUids)) {
                continue;
            }
            if (propertyPredicate.test(node.get().properties())) {
                matched.add(candidate);
            }
        }
        return matched;
    }

    private static Optional<Long> lookupUid(final Txn<ByteBuffer> readTxn,
                                            final UidLookupDb db, final String name) {
        final ByteBuffer key = directBuffer(name);
        return db.get(readTxn, key, maybeUid -> maybeUid.map(uidBuffer ->
                UnsignedBytesInstances.ofLength(uidBuffer.remaining()).get(uidBuffer.duplicate())));
    }

    private static ByteBuffer directBuffer(final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    // ------------------------------------------------------------------------------------------------------
    // row / predicate helpers
    // ------------------------------------------------------------------------------------------------------

    private static Map<String, Val> rowFor(final String variable, final Map<String, Val> properties) {
        final Map<String, Val> row = new HashMap<>(properties.size());
        for (final Map.Entry<String, Val> entry : properties.entrySet()) {
            row.put(variable + "." + entry.getKey(), entry.getValue());
        }
        return row;
    }

    private static ValueFunctionFactories<Map<String, Val>> rowAccessors() {
        return GraphRowValueFunctionFactory::new;
    }

    // ------------------------------------------------------------------------------------------------------
    // plan shape / projection
    // ------------------------------------------------------------------------------------------------------

    private record PlanShape(NodeScan nodeScan, List<Expand> hops,
                             @Nullable VarLengthExpand varLengthExpand, @Nullable ExpressionOperator where,
                             Project project, List<SortKey> sortKeys, @Nullable Long limit) {
    }

    private static PlanShape unwrap(final LogicalPlan plan) {
        LogicalPlan current = plan;
        // CypherToLogicalPlan compiles RETURN as Project, optionally wrapped in a Sort (ORDER BY), optionally
        // wrapped in a Limit (LIMIT) - so the shape above Project is [Limit ->] [Sort ->] Project. Strip Limit
        // FIRST (it is the outermost when both are present); stripping Sort first would leave the Limit sitting
        // on top and fail the Project check below. Task P7.2: the Limit's value bounds row accumulation itself,
        // not just a post-hoc trim.
        Long limit = null;
        while (current instanceof final Limit limitNode) {
            if (!limitNode.values().isEmpty()) {
                limit = limitNode.values().getFirst();
            }
            current = limitNode.input();
        }
        List<SortKey> sortKeys = List.of();
        while (current instanceof final Sort sortNode) {
            sortKeys = sortNode.keys();
            current = sortNode.input();
        }
        if (!(current instanceof final Project project)) {
            throw new IllegalArgumentException(
                    "Unsupported compiled plan shape for graph traversal: expected a Project node (after "
                    + "unwrapping Limit/Sort), found " + current.getClass().getSimpleName());
        }

        LogicalPlan below = project.input();
        ExpressionOperator where = null;
        if (below instanceof final Filter filter) {
            where = filter.wherePredicate();
            below = filter.input();
        }

        // Task P3.2: a chain of Expand nodes is encountered target-to-anchor while unwrapping (the outermost
        // Expand is the LAST hop compiled), so each hop found is prepended to keep the list in anchor-to-target
        // (compiled source) order. Task P3.3: a VarLengthExpand only ever compiles as a pattern's sole hop
        // directly over the anchor NodeScan, so it is mutually exclusive with a fixed-length Expand chain.
        final List<Expand> hops = new ArrayList<>();
        VarLengthExpand varLengthExpand = null;
        if (below instanceof final VarLengthExpand vle) {
            varLengthExpand = vle;
            below = vle.input();
        } else {
            while (below instanceof final Expand e) {
                hops.add(0, e);
                below = e.input();
            }
        }

        if (!(below instanceof final NodeScan nodeScan)) {
            throw new IllegalArgumentException(
                    "Unsupported compiled plan shape for graph traversal: expected a NodeScan leaf, found "
                    + below.getClass().getSimpleName());
        }
        return new PlanShape(nodeScan, hops, varLengthExpand, where, project, sortKeys, limit);
    }

    /**
     * The post-traversal pipeline, in Cypher's order: sort the matched rows by any {@code ORDER BY} keys, project
     * each to an output tuple, de-duplicate when {@code RETURN DISTINCT}, then cap by any {@code LIMIT}. The
     * {@code LIMIT} is applied here (not during traversal) whenever ordering or de-duplication is in play, so it
     * bounds the correct rows - the smallest by sort order, or the count of <em>distinct</em> tuples - rather than
     * an arbitrary first-N of the raw traversal (see {@link #execute}'s rowCap note). When neither is present the
     * traversal already stopped at {@code LIMIT} rows, so the cap here is a no-op.
     */
    private static List<Val[]> finalizeRows(final List<Map<String, Val>> rows, final PlanShape shape,
                                            final boolean distinct) {
        if (!shape.sortKeys().isEmpty()) {
            rows.sort(rowComparator(shape.sortKeys()));
        }

        final List<ProjectField> fields = shape.project().fields();
        final long limit = shape.limit() == null ? Long.MAX_VALUE : shape.limit();
        final Set<List<Val>> seen = distinct ? new HashSet<>() : null;
        final List<Val[]> out = new ArrayList<>();
        for (final Map<String, Val> row : rows) {
            if (out.size() >= limit) {
                break;
            }
            final Val[] tuple = new Val[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                tuple[i] = evaluate(fields.get(i), row);
            }
            // DISTINCT de-duplicates by projected value (every Val type implements equals/hashCode), preserving
            // the first appearance in the already-sorted order. A duplicate is skipped WITHOUT counting toward
            // the LIMIT, so `RETURN DISTINCT ... LIMIT n` yields n distinct rows, not n raw rows deduped.
            if (seen != null && !seen.add(Arrays.asList(tuple))) {
                continue;
            }
            out.add(tuple);
        }
        return out;
    }

    /**
     * A comparator over row maps built from the {@code ORDER BY} keys, applied in order (the first key is
     * primary, later keys break ties). A key's value is looked up by the same {@code "alias.property"} row-map
     * key {@link #rowFor} builds; a key referencing a value the row lacks (a bare pattern variable, or an absent
     * property) sorts last (ascending) via {@link Comparator#nullsLast}. {@link Val} defines the natural ordering.
     */
    private static Comparator<Map<String, Val>> rowComparator(final List<SortKey> keys) {
        Comparator<Map<String, Val>> comparator = null;
        for (final SortKey key : keys) {
            final String rowKey = key.field().alias() == null
                    ? key.field().field()
                    : key.field().alias() + "." + key.field().field();
            Comparator<Map<String, Val>> next = Comparator.comparing(
                    (Map<String, Val> row) -> row.get(rowKey),
                    Comparator.nullsLast(Comparator.<Val>naturalOrder()));
            if (key.descending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    // ------------------------------------------------------------------------------------------------------
    // aggregation (Task 1.3 of docs/graphdb-analytic-functions-implementation-plan.md)
    // ------------------------------------------------------------------------------------------------------

    /**
     * The aggregated analogue of {@link #finalizeRows}: groups {@code rows} by {@code aggregation}'s
     * {@link GroupKeyColumn}s, reduces each group by its {@link AggregateColumn}s into one output {@code Val[]}
     * (see {@link #reduceGroup}), then applies {@code ORDER BY}/{@code DISTINCT}/{@code LIMIT} over the
     * <em>aggregated</em> output rows - there is no traversal row map left at this point for {@link #rowComparator}
     * to sort by, only the final one-row-per-group tuples.
     *
     * <p><b>Empty-input semantics</b> (Cypher's own rules, not a PoC simplification): a {@code RETURN} with at
     * least one {@link GroupKeyColumn} produces zero output rows over zero matched rows (there are no groups to
     * report); a pure aggregate {@code RETURN} (no group keys at all, e.g. {@code RETURN count(*)}) always
     * produces exactly one output row, reducing over the empty set if {@code rows} is empty.</p>
     *
     * <b>Preconditions:</b> {@code aggregation.columns()} has the same size as, and aligns 1:1 in order with,
     * {@code shape.project().fields()} (a {@code CypherToLogicalPlan.compile} invariant - see
     * {@link CypherAggregation}'s Javadoc); every {@code ORDER BY} key in {@code shape.sortKeys()} names a column
     * {@code aggregation} actually produces (a {@code CypherToLogicalPlan.validateOrderByAgainstAggregation}
     * invariant, checked at compile time, not re-checked here - see {@link #outputColumnIndex}).
     * <b>Null status:</b> no parameter is nullable; never returns null.
     */
    private static List<Val[]> finalizeAggregatedRows(final List<Map<String, Val>> rows, final PlanShape shape,
                                                      final CypherAggregation aggregation, final boolean distinct) {
        final List<OutputColumn> columns = aggregation.columns();

        // Group by the ordered tuple of GroupKeyColumn values, in first-appearance order. A RETURN with no
        // GroupKeyColumn at all (a pure aggregate) is always exactly one group, reducing over `rows` as-is - even
        // when `rows` is empty (Cypher: aggregates over zero rows still produce one row; see this method's
        // Javadoc). A RETURN with at least one GroupKeyColumn and zero input rows correctly produces zero groups.
        final Map<List<Val>, List<Map<String, Val>>> groups = new LinkedHashMap<>();
        if (hasGroupKey(columns)) {
            for (final Map<String, Val> row : rows) {
                groups.computeIfAbsent(groupKeyOf(columns, row), key -> new ArrayList<>()).add(row);
            }
        } else {
            groups.put(List.of(), rows);
        }

        final List<Val[]> reduced = new ArrayList<>(groups.size());
        for (final List<Map<String, Val>> group : groups.values()) {
            reduced.add(reduceGroup(columns, group));
        }

        if (!shape.sortKeys().isEmpty()) {
            reduced.sort(outputRowComparator(shape.sortKeys(), shape.project().fields()));
        }

        final List<Val[]> deduped;
        if (distinct) {
            // Mirrors finalizeRows' own DISTINCT handling: de-duplicate by value, first appearance wins, in the
            // already-sorted order.
            deduped = new ArrayList<>();
            final Set<List<Val>> seen = new HashSet<>();
            for (final Val[] row : reduced) {
                if (seen.add(Arrays.asList(row))) {
                    deduped.add(row);
                }
            }
        } else {
            deduped = reduced;
        }

        final long limit = shape.limit() == null ? Long.MAX_VALUE : shape.limit();
        if (deduped.size() <= limit) {
            return deduped;
        }
        return new ArrayList<>(deduped.subList(0, (int) Math.min(limit, deduped.size())));
    }

    private static boolean hasGroupKey(final List<OutputColumn> columns) {
        for (final OutputColumn column : columns) {
            if (column instanceof GroupKeyColumn) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds one group's key: the ordered tuple of every {@link GroupKeyColumn}'s value in this row, in
     * {@code columns} order - a property this row happens to lack (a schemaless graph) becomes
     * {@link ValNull#INSTANCE}, so rows lacking vs. explicitly null at a group key still group together (matching
     * Cypher's own null-grouping rule).
     */
    private static List<Val> groupKeyOf(final List<OutputColumn> columns, final Map<String, Val> row) {
        final List<Val> key = new ArrayList<>();
        for (final OutputColumn column : columns) {
            if (column instanceof final GroupKeyColumn groupKeyColumn) {
                final Val value = row.get(groupKeyColumn.rowKey());
                key.add(value == null ? ValNull.INSTANCE : value);
            }
        }
        return key;
    }

    /**
     * Reduces one group to a single output {@code Val[]}, one entry per {@code columns} position - a
     * {@link GroupKeyColumn} position takes the group's (uniform, by construction of {@link #groupKeyOf}) value;
     * an {@link AggregateColumn} position is reduced by {@link #reduceAggregate}.
     * <b>Preconditions:</b> if {@code columns} contains a {@link GroupKeyColumn}, {@code group} is non-empty (a
     * group is only ever created from at least one row - see {@link #finalizeAggregatedRows}); a pure-aggregate
     * {@code columns} (no {@link GroupKeyColumn}) may reduce an empty {@code group}.
     */
    private static Val[] reduceGroup(final List<OutputColumn> columns, final List<Map<String, Val>> group) {
        final Val[] out = new Val[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            final OutputColumn column = columns.get(i);
            if (column instanceof final GroupKeyColumn groupKeyColumn) {
                final Val value = group.getFirst().get(groupKeyColumn.rowKey());
                out[i] = value == null ? ValNull.INSTANCE : value;
            } else if (column instanceof final AggregateColumn aggregateColumn) {
                out[i] = reduceAggregate(aggregateColumn, group);
            }
        }
        return out;
    }

    /**
     * Reduces one {@link AggregateColumn} over one group - dispatches to the five PoC-subset functions. Every
     * branch is null-safe/lenient (a row missing or holding a non-numeric value at the argument is skipped, never
     * thrown), matching {@code GraphRowValueFunctionFactory}'s own lenient extraction semantics for the same
     * traversal rows.
     */
    private static Val reduceAggregate(final AggregateColumn aggregateColumn, final List<Map<String, Val>> group) {
        return switch (aggregateColumn.function()) {
            case COUNT -> reduceCount(aggregateColumn, group);
            case SUM -> reduceSum(aggregateColumn, group);
            case AVG -> reduceAvg(aggregateColumn, group);
            case MIN -> reduceMinOrMax(aggregateColumn, group, true);
            case MAX -> reduceMinOrMax(aggregateColumn, group, false);
        };
    }

    /**
     * {@code count(*)} and {@code count(v)} (a bare pattern variable) both count every row in the group - every
     * matched row binds every pattern variable in this PoC subset (there is no {@code OPTIONAL MATCH} to leave one
     * unbound), so the two forms are semantically identical (see {@link AggregateColumn}'s Javadoc).
     * {@code count(a.property)} instead counts only the rows where that property is present and non-null.
     */
    private static Val reduceCount(final AggregateColumn aggregateColumn, final List<Map<String, Val>> group) {
        if (aggregateColumn.star() || aggregateColumn.argIsVariable()) {
            return ValLong.create(group.size());
        }
        if (aggregateColumn.distinct()) {
            // count(DISTINCT a.property): count distinct non-null values at the property. Val has value-based
            // equals/hashCode (the same basis finalizeAggregatedRows' own DISTINCT dedup relies on).
            final Set<Val> distinctValues = new HashSet<>();
            for (final Map<String, Val> row : group) {
                final Val value = row.get(aggregateColumn.argRowKey());
                if (isPresent(value)) {
                    distinctValues.add(value);
                }
            }
            return ValLong.create(distinctValues.size());
        }
        long count = 0;
        for (final Map<String, Val> row : group) {
            if (isPresent(row.get(aggregateColumn.argRowKey()))) {
                count++;
            }
        }
        return ValLong.create(count);
    }

    /** {@code sum} of an empty set of numeric values is {@code 0} (Cypher's rule, unlike
     * {@code avg}/{@code min}/{@code max}). */
    private static Val reduceSum(final AggregateColumn aggregateColumn, final List<Map<String, Val>> group) {
        double sum = 0.0;
        for (final Map<String, Val> row : group) {
            final Double numeric = numericValue(row.get(aggregateColumn.argRowKey()));
            if (numeric != null) {
                sum += numeric;
            }
        }
        // PoC simplification (see docs/graphdb-analytic-functions-implementation-plan.md, Risks table): sum
        // always renders as a double, even over integral properties - integral-type preservation is a deferred
        // display-fidelity refinement, not a correctness gap (the numeric value itself is exact).
        return ValDouble.create(sum);
    }

    /** {@code avg} of an empty (or entirely non-numeric) set is {@code null} - unlike {@code sum}'s {@code 0}. */
    private static Val reduceAvg(final AggregateColumn aggregateColumn, final List<Map<String, Val>> group) {
        double sum = 0.0;
        long count = 0;
        for (final Map<String, Val> row : group) {
            final Double numeric = numericValue(row.get(aggregateColumn.argRowKey()));
            if (numeric != null) {
                sum += numeric;
                count++;
            }
        }
        return count == 0 ? ValNull.INSTANCE : ValDouble.create(sum / count);
    }

    /**
     * {@code min}/{@code max} over the group's non-null values at the aggregate's property, using {@link Val}'s
     * natural ordering ({@link Val#compareTo}) - unlike {@link #reduceSum}/{@link #reduceAvg}, the winning
     * {@link Val}'s original type is preserved (a {@code min} of {@code ValLong}s stays a {@code ValLong}, not a
     * double). An empty (or entirely-null) set yields {@code null} - matching {@code avg}, not {@code sum}.
     */
    private static Val reduceMinOrMax(final AggregateColumn aggregateColumn, final List<Map<String, Val>> group,
                                      final boolean min) {
        Val best = null;
        for (final Map<String, Val> row : group) {
            final Val value = row.get(aggregateColumn.argRowKey());
            if (!isPresent(value)) {
                continue;
            }
            if (best == null || (min ? value.compareTo(best) < 0 : value.compareTo(best) > 0)) {
                best = value;
            }
        }
        return best == null ? ValNull.INSTANCE : best;
    }

    private static boolean isPresent(final @Nullable Val value) {
        return value != null && !Type.NULL.equals(value.type());
    }

    /**
     * Resolves a row value to a numeric double for {@code sum}/{@code avg}, or {@code null} if absent or not
     * numeric - mirrors {@code GraphRowValueFunctionFactory.createNumberExtractor}'s lenient "no match" semantics
     * (never throws) rather than rejecting a non-numeric property under an aggregate.
     */
    private static @Nullable Double numericValue(final @Nullable Val value) {
        if (!isPresent(value)) {
            return null;
        }
        try {
            return value.toDouble();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A comparator over aggregated output rows ({@code Val[]}, in {@code fields} order) built from the
     * {@code ORDER BY} keys - the aggregated analogue of {@link #rowComparator}, which sorts traversal row maps
     * instead (not applicable once rows are reduced to output tuples with no row map left).
     */
    private static Comparator<Val[]> outputRowComparator(final List<SortKey> keys, final List<ProjectField> fields) {
        Comparator<Val[]> comparator = null;
        for (final SortKey key : keys) {
            final int index = outputColumnIndex(key.field(), fields);
            Comparator<Val[]> next = Comparator.comparing(
                    (Val[] row) -> row[index], Comparator.nullsLast(Comparator.<Val>naturalOrder()));
            if (key.descending()) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    /**
     * Resolves an {@code ORDER BY} key to its position in the aggregated output tuple, by matching its
     * reconstructed {@code alias == null ? field : alias + "." + field} name against each {@link ProjectField}'s
     * {@link ProjectField#name()} - the same reconstruction {@link #rowComparator} uses for the non-aggregated
     * path, but matched against output column names rather than looked up in a row map, since no such map exists
     * once rows are aggregated.
     *
     * @throws IllegalStateException if no field matches - this should be unreachable, since
     *                                {@code CypherToLogicalPlan.validateOrderByAgainstAggregation} already
     *                                rejects, at compile time, any {@code ORDER BY} item that does not name a
     *                                returned column; reaching this exception marks a genuine invariant
     *                                violation between that check and this lookup, not a query error.
     */
    private static int outputColumnIndex(final QualifiedField field, final List<ProjectField> fields) {
        final String name = field.alias() == null ? field.field() : field.alias() + "." + field.field();
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException(
                "ORDER BY key '" + name + "' did not resolve to an output column - this should have been "
                + "rejected at compile time by CypherToLogicalPlan.validateOrderByAgainstAggregation");
    }

    private static Val evaluate(final ProjectField field, final Map<String, Val> row) {
        final String expr = field.rawExpression();
        if (expr.startsWith("${") && expr.endsWith("}")) {
            final String reference = expr.substring(2, expr.length() - 1);
            if (row.containsKey(reference)) {
                return row.get(reference);
            }
            // Code-review fix: rowFor() only populates a "variable.property" key when the matched node actually
            // has that property. A graph is schemaless, so a well-formed property reference to a property this
            // node happens to lack (e.g. RETURN a.email where this account has no email) is absent from the row -
            // Cypher's semantics for that is null, so return ValNull rather than crashing an otherwise-valid
            // query. Only a bare pattern-variable reference (no '.', e.g. "RETURN n") is genuinely unsupported:
            // rowFor() never produces a bare "variable" key and a whole matched node/edge has no single Val
            // representation yet, so that case still throws (fail loud) rather than silently returning a fixed
            // string for every row - the failure mode this class's top-level Javadoc says it avoids elsewhere.
            if (reference.indexOf('.') >= 0) {
                return ValNull.INSTANCE;
            }
            throw new UnsupportedOperationException(
                    "not yet supported: RETURN item '" + expr + "' names a bare pattern variable - only a "
                    + "property/variable reference of the form 'variable.property' is wired to a graph traversal "
                    + "row; a whole matched node/edge has no single value representation yet");
        }
        throw new UnsupportedOperationException(
                "not yet supported: RETURN item '" + expr + "' is not a bare property/variable reference - "
                + "literals, aggregates and function calls need the full ExpressionParser, not wired to a "
                + "graph traversal row");
    }

    // ------------------------------------------------------------------------------------------------------
    // temporal
    // ------------------------------------------------------------------------------------------------------

    /**
     * The largest instant {@code MillisecondTimeSerde}'s 6-byte encoding can represent
     * ({@code (1L << 48) - 1} epoch millis, &asymp; year 10920) - used as the floor-lookup instant for "latest".
     * {@link Instant#MAX} cannot be used here: {@code Instant.toEpochMilli()} overflows a {@code long} for it.
     */
    private static final Instant LATEST = Instant.ofEpochMilli((1L << 48) - 1);

    /**
     * Task P4.2: the resolved, ready-to-execute form of a plan's temporal clause - a node lookup and a
     * direction-agnostic hop expansion, already bound to either the as-of floor-lookup DAO methods or the
     * window-intersection ones (Task P4.1), so the rest of this class ({@link #resolveAnchors},
     * {@link #expandChainHop}/{@link #acceptChainNeighbour}, {@link #expandVarLength}/{@link #collectNeighbours})
     * is written once against this interface and never branches on {@link TemporalContext.Mode} itself.
     */
    private interface TemporalAccess {

        Optional<GraphNodeDb.NodeVersion> getNode(Txn<ByteBuffer> readTxn, long nodeUid);

        void expandOut(Txn<ByteBuffer> readTxn, long srcUid, long edgeTypeUid, Consumer<EdgeStep> consumer);

        void expandIn(Txn<ByteBuffer> readTxn, long dstUid, long edgeTypeUid, Consumer<EdgeStep> consumer);
    }

    /**
     * One traversed edge as the engine sees it: the UID of the neighbour node reached (the {@code dst} for an
     * {@code OUT} hop, the {@code src} for an {@code IN} hop) plus that edge's own stored property map. Surfacing
     * the edge properties (previously the DAO's {@code Neighbour} was flattened to just its neighbour UID) is what
     * lets a hop bind them to its relationship variable so {@code RETURN c.startTime} resolves - see
     * {@link Expand#edgeVariable()} and {@link #acceptChainNeighbour}.
     *
     * @param neighbourUid   the node reached by following the edge.
     * @param edgeProperties never null; the edge version's property map (may be empty).
     */
    private record EdgeStep(long neighbourUid, Map<String, Val> edgeProperties) {
    }

    private TemporalAccess asOfAccess(final Instant asOf) {
        return new TemporalAccess() {
            @Override
            public Optional<GraphNodeDb.NodeVersion> getNode(final Txn<ByteBuffer> readTxn, final long nodeUid) {
                return stores.getNodes().getNode(readTxn, nodeUid, asOf);
            }

            @Override
            public void expandOut(final Txn<ByteBuffer> readTxn, final long srcUid, final long edgeTypeUid,
                                  final Consumer<EdgeStep> consumer) {
                stores.getOutEdges().expandOut(readTxn, srcUid, edgeTypeUid, asOf,
                        neighbour -> consumer.accept(new EdgeStep(neighbour.dstUid(), neighbour.edgeProperties())));
            }

            @Override
            public void expandIn(final Txn<ByteBuffer> readTxn, final long dstUid, final long edgeTypeUid,
                                 final Consumer<EdgeStep> consumer) {
                stores.getInEdges().expandIn(readTxn, dstUid, edgeTypeUid, asOf,
                        neighbour -> consumer.accept(new EdgeStep(neighbour.srcUid(), neighbour.edgeProperties())));
            }
        };
    }

    private TemporalAccess windowAccess(final Instant from, final Instant to) {
        return new TemporalAccess() {
            @Override
            public Optional<GraphNodeDb.NodeVersion> getNode(final Txn<ByteBuffer> readTxn, final long nodeUid) {
                return stores.getNodes().getNodeWindow(readTxn, nodeUid, from, to);
            }

            @Override
            public void expandOut(final Txn<ByteBuffer> readTxn, final long srcUid, final long edgeTypeUid,
                                  final Consumer<EdgeStep> consumer) {
                stores.getOutEdges().expandOutWindow(readTxn, srcUid, edgeTypeUid, from, to,
                        neighbour -> consumer.accept(new EdgeStep(neighbour.dstUid(), neighbour.edgeProperties())));
            }

            @Override
            public void expandIn(final Txn<ByteBuffer> readTxn, final long dstUid, final long edgeTypeUid,
                                 final Consumer<EdgeStep> consumer) {
                stores.getInEdges().expandInWindow(readTxn, dstUid, edgeTypeUid, from, to,
                        neighbour -> consumer.accept(new EdgeStep(neighbour.srcUid(), neighbour.edgeProperties())));
            }
        };
    }

    /**
     * Task P4.2: resolves a plan's temporal clause to a {@link TemporalAccess} - {@code AS OF}/no-clause become
     * the as-of floor lookup at the resolved instant (no clause resolves to {@link #LATEST}, unchanged from
     * before this task); {@code AROUND}/{@code BETWEEN} become the window-intersection lookup over
     * {@code [temporalContext.from(), temporalContext.to()]} (Task P4.1) - previously rejected outright.
     */
    private TemporalAccess resolveAccess(final @Nullable TemporalContext temporalContext) {
        if (temporalContext == null) {
            return asOfAccess(LATEST);
        }
        return switch (temporalContext.mode()) {
            case AS_OF -> asOfAccess(temporalContext.instant());
            case AROUND, BETWEEN -> windowAccess(temporalContext.from(), temporalContext.to());
        };
    }
}
