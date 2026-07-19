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
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cypher.TemporalContext;
import stroom.query.planner.logical.Direction;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final GraphStores stores;
    private final ExpressionPredicateFactory expressionPredicateFactory;
    private final long maxVarLengthPathStates;
    private final Duration maxTraversalDuration;

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
        this.stores = Objects.requireNonNull(stores, "stores");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
        this.maxVarLengthPathStates = maxVarLengthPathStates;
        this.maxTraversalDuration = Objects.requireNonNull(maxTraversalDuration, "maxTraversalDuration");
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
        return execute(readTxn, plan, temporalContext, dateTimeSettings, false);
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
     * early-exits the traversal as before.</p>
     *
     * @param distinct whether to de-duplicate the projected rows ({@code RETURN DISTINCT}).
     */
    public List<Val[]> execute(final Txn<ByteBuffer> readTxn, final LogicalPlan plan,
                               final @Nullable TemporalContext temporalContext,
                               final DateTimeSettings dateTimeSettings, final boolean distinct) {
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
        // ORDER BY/DISTINCT (code-review follow-up): early-exit is only sound when the first N rows traversed are
        // a valid answer. With ORDER BY the N smallest are not necessarily the first N traversed, and with
        // DISTINCT the first N raw rows may collapse to fewer than N distinct ones - so in either case we must
        // traverse everything and apply the LIMIT after sorting/de-duplicating (see finalizeRows).
        final boolean postProcess = !shape.sortKeys().isEmpty() || distinct;
        final long rowCap = postProcess || shape.limit() == null
                ? Long.MAX_VALUE
                : Math.max(0L, shape.limit());
        final Instant deadline = Instant.now().plus(maxTraversalDuration);

        final List<Map<String, Val>> rows = new ArrayList<>();

        if (shape.varLengthExpand != null) {
            for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan, access)) {
                if (rows.size() >= rowCap) {
                    break;
                }
                final Optional<GraphNodeDb.NodeVersion> anchor = access.getNode(readTxn, anchorUid);
                if (anchor.isEmpty()) {
                    continue;
                }
                final Map<String, Val> anchorRow = rowFor(shape.nodeScan.variable(), anchor.get().properties());
                expandVarLength(readTxn, anchorUid, access, shape.varLengthExpand, anchorRow, wherePredicate,
                        rows, rowCap, deadline);
            }
            return finalizeRows(rows, shape, distinct);
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
                if (rows.size() >= rowCap) {
                    break;
                }
                if (wherePredicate.test(f.row())) {
                    rows.add(f.row());
                }
            }
            return finalizeRows(rows, shape, distinct);
        }

        for (int i = 0; i < shape.hops.size(); i++) {
            checkDeadline(deadline);
            if (rows.size() >= rowCap) {
                break;
            }
            final Expand hop = shape.hops.get(i);
            final boolean isLastHop = i == shape.hops.size() - 1;
            final List<Frontier> next = new ArrayList<>();
            for (final Frontier f : frontier) {
                if (rows.size() >= rowCap) {
                    break;
                }
                expandChainHop(readTxn, f.nodeUid(), access, hop, f.row(), isLastHop, wherePredicate, next, rows,
                        rowCap, deadline);
            }
            frontier = next;
        }

        return finalizeRows(rows, shape, distinct);
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

    /** A traversal frontier entry: the node reached so far, and the accumulated row of every variable bound. */
    private record Frontier(long nodeUid, Map<String, Val> row) {
    }

    private void expandChainHop(final Txn<ByteBuffer> readTxn, final long fromUid, final TemporalAccess access,
                                final Expand hop, final Map<String, Val> rowSoFar, final boolean isLastHop,
                                final Predicate<Map<String, Val>> wherePredicate, final List<Frontier> nextFrontier,
                                final List<Map<String, Val>> finalRows, final long rowCap,
                                final Instant deadline) {
        final Optional<Long> edgeTypeUid = resolveRequiredEdgeTypeUid(readTxn, hop.edgeType());
        if (edgeTypeUid.isEmpty()) {
            return;
        }

        final Consumer<Long> onNeighbourUid = neighbourUid -> acceptChainNeighbour(
                readTxn, neighbourUid, access, hop, rowSoFar, isLastHop, wherePredicate, nextFrontier, finalRows,
                rowCap, deadline);

        collectNeighbours(readTxn, fromUid, edgeTypeUid.get(), access, hop.direction(), onNeighbourUid);
    }

    private void acceptChainNeighbour(final Txn<ByteBuffer> readTxn, final long neighbourUid,
                                      final TemporalAccess access,
                                      final Expand hop, final Map<String, Val> rowSoFar, final boolean isLastHop,
                                      final Predicate<Map<String, Val>> wherePredicate,
                                      final List<Frontier> nextFrontier, final List<Map<String, Val>> finalRows,
                                      final long rowCap, final Instant deadline) {
        // Task P7.2: once a compiled LIMIT is satisfied, stop accumulating further rows at this hop - does not
        // abort a cursor scan already in flight (the DAO layer has no cancellation hook), but does stop this
        // traversal from expanding to further frontier nodes/hops once the cap is reached.
        if (isLastHop && finalRows.size() >= rowCap) {
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
        if (isLastHop) {
            if (wherePredicate.test(row)) {
                finalRows.add(row);
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
                                 final List<Map<String, Val>> rows, final long rowCap, final Instant deadline) {
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

        if (varLengthExpand.minHops() == 0 && rows.size() < rowCap) {
            // A zero-length path binds the target variable to the anchor node itself.
            final Optional<GraphNodeDb.NodeVersion> anchorNode = access.getNode(readTxn, anchorUid);
            anchorNode.ifPresent(node -> acceptVarLengthRow(readTxn, varLengthExpand, anchorRow, node,
                    wherePredicate, rows));
        }

        // Task P7.2: a running total of BFS path-states explored across every depth of THIS call - guards the
        // case the hop-range ceiling alone does not: a modest maxHops against a high-fan-out hub node can still
        // explore an exponential number of paths, all materialised in memory at once.
        long pathStatesExplored = 1;

        List<PathState> frontier = List.of(new PathState(anchorUid, anchorRow, Set.of(anchorUid)));
        for (int depth = 1; depth <= varLengthExpand.maxHops() && !frontier.isEmpty() && rows.size() < rowCap;
                depth++) {
            checkDeadline(deadline);
            final List<PathState> next = new ArrayList<>();
            for (final PathState state : frontier) {
                if (rows.size() >= rowCap) {
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
                        neighbourUid -> {
                            checkDeadline(deadline);
                            neighbourUids.add(neighbourUid);
                        });

                for (final long neighbourUid : neighbourUids) {
                    if (rows.size() >= rowCap) {
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
                        acceptVarLengthRow(readTxn, varLengthExpand, row, target.get(), wherePredicate, rows);
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
                                    final List<Map<String, Val>> rows) {
        if (!matchesTargetConstraint(
                readTxn, varLengthExpand.targetLabels(), varLengthExpand.targetPropertyPredicate(), target)) {
            return;
        }
        if (wherePredicate.test(row)) {
            rows.add(row);
        }
    }

    private static void collectNeighbours(final Txn<ByteBuffer> readTxn, final long fromUid, final long edgeTypeUid,
                                          final TemporalAccess access, final Direction direction,
                                          final Consumer<Long> collector) {
        switch (direction) {
            case OUT -> access.expandOut(readTxn, fromUid, edgeTypeUid, collector);
            case IN -> access.expandIn(readTxn, fromUid, edgeTypeUid, collector);
            case BOTH -> {
                access.expandOut(readTxn, fromUid, edgeTypeUid, collector);
                access.expandIn(readTxn, fromUid, edgeTypeUid, collector);
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

        void expandOut(Txn<ByteBuffer> readTxn, long srcUid, long edgeTypeUid, Consumer<Long> dstUidConsumer);

        void expandIn(Txn<ByteBuffer> readTxn, long dstUid, long edgeTypeUid, Consumer<Long> srcUidConsumer);
    }

    private TemporalAccess asOfAccess(final Instant asOf) {
        return new TemporalAccess() {
            @Override
            public Optional<GraphNodeDb.NodeVersion> getNode(final Txn<ByteBuffer> readTxn, final long nodeUid) {
                return stores.getNodes().getNode(readTxn, nodeUid, asOf);
            }

            @Override
            public void expandOut(final Txn<ByteBuffer> readTxn, final long srcUid, final long edgeTypeUid,
                                  final Consumer<Long> dstUidConsumer) {
                stores.getOutEdges().expandOut(
                        readTxn, srcUid, edgeTypeUid, asOf, neighbour -> dstUidConsumer.accept(neighbour.dstUid()));
            }

            @Override
            public void expandIn(final Txn<ByteBuffer> readTxn, final long dstUid, final long edgeTypeUid,
                                 final Consumer<Long> srcUidConsumer) {
                stores.getInEdges().expandIn(
                        readTxn, dstUid, edgeTypeUid, asOf, neighbour -> srcUidConsumer.accept(neighbour.srcUid()));
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
                                  final Consumer<Long> dstUidConsumer) {
                stores.getOutEdges().expandOutWindow(readTxn, srcUid, edgeTypeUid, from, to,
                        neighbour -> dstUidConsumer.accept(neighbour.dstUid()));
            }

            @Override
            public void expandIn(final Txn<ByteBuffer> readTxn, final long dstUid, final long edgeTypeUid,
                                 final Consumer<Long> srcUidConsumer) {
                stores.getInEdges().expandInWindow(readTxn, dstUid, edgeTypeUid, from, to,
                        neighbour -> srcUidConsumer.accept(neighbour.srcUid()));
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
