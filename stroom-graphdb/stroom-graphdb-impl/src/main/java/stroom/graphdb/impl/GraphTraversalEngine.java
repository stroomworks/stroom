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
import stroom.query.language.functions.ValString;
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
import stroom.query.planner.logical.VarLengthExpand;

import org.jspecify.annotations.Nullable;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p><b>Deliberately unsupported here (throws {@link UnsupportedOperationException} rather than a wrong
 * result)</b>: an anchor {@link NodeScan} with no label or no property predicate (the property index has no
 * "all nodes of this label" scan - only equality lookups); {@code AROUND}/{@code BETWEEN} temporal clauses (P0.3
 * resolved these as a window-intersection scan, a P4 deliverable - this engine only performs the as-of floor
 * lookup PoC.4's stores implement); a {@code RETURN} item other than a bare property/variable reference (a
 * literal, aggregate, or function call needs the full {@code ExpressionParser}, not wired to a graph row here).
 * Each hop's non-anchor node's own labels/properties (Task P3.1) are enforced as a post-expand filter in
 * {@link #acceptChainNeighbour}/{@link #expandVarLength}, exactly mirroring how {@link #resolveAnchors} validates
 * an anchor's property predicate - not an alternative access path, since a neighbour is always reached via the
 * edge. The outer {@code WHERE} predicate is evaluated only once a row carries every hop's bound variable, i.e.
 * only after the pattern's last (fixed-length) hop (Task P3.2), or at every depth within
 * {@code [minHops, maxHops]} for a var-length hop (Task P3.3) - matching how a single-hop plan already evaluated
 * it against the fully merged anchor+target row, not a per-hop partial evaluation.</p>
 */
public final class GraphTraversalEngine {

    private final GraphStores stores;
    private final ExpressionPredicateFactory expressionPredicateFactory;

    public GraphTraversalEngine(final GraphStores stores,
                                final ExpressionPredicateFactory expressionPredicateFactory) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
    }

    /**
     * <b>Preconditions:</b> no parameter is null except {@code temporalContext}; {@code plan} must have the
     * shape {@code [Sort/Limit ->] Project -> [Filter ->] [Expand ->]* NodeScan} (see this class's Javadoc for
     * what happens otherwise).
     * <b>Postconditions:</b> one {@code Val[]} per surviving row, in {@code Project}'s field order; never null
     * (possibly empty).
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
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");

        final Instant asOf = resolveAsOf(temporalContext);
        final PlanShape shape = unwrap(plan);

        final Predicate<Map<String, Val>> wherePredicate = shape.where == null
                ? row -> true
                : expressionPredicateFactory
                        .createOptional(shape.where, rowAccessors(), dateTimeSettings)
                        .orElse(row -> true);

        final List<Map<String, Val>> rows = new ArrayList<>();

        if (shape.varLengthExpand != null) {
            for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan, asOf)) {
                final Optional<GraphNodeDb.NodeVersion> anchor = stores.getNodes().getNode(readTxn, anchorUid, asOf);
                if (anchor.isEmpty()) {
                    continue;
                }
                final Map<String, Val> anchorRow = rowFor(shape.nodeScan.variable(), anchor.get().properties());
                expandVarLength(
                        readTxn, anchorUid, asOf, shape.varLengthExpand, anchorRow, wherePredicate, rows);
            }
            return project(rows, shape.project);
        }

        List<Frontier> frontier = new ArrayList<>();
        for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan, asOf)) {
            final Optional<GraphNodeDb.NodeVersion> anchor = stores.getNodes().getNode(readTxn, anchorUid, asOf);
            if (anchor.isEmpty()) {
                continue;
            }
            frontier.add(new Frontier(anchorUid, rowFor(shape.nodeScan.variable(), anchor.get().properties())));
        }

        if (shape.hops.isEmpty()) {
            for (final Frontier f : frontier) {
                if (wherePredicate.test(f.row())) {
                    rows.add(f.row());
                }
            }
            return project(rows, shape.project);
        }

        for (int i = 0; i < shape.hops.size(); i++) {
            final Expand hop = shape.hops.get(i);
            final boolean isLastHop = i == shape.hops.size() - 1;
            final List<Frontier> next = new ArrayList<>();
            for (final Frontier f : frontier) {
                expandChainHop(readTxn, f.nodeUid(), asOf, hop, f.row(), isLastHop, wherePredicate, next, rows);
            }
            frontier = next;
        }

        return project(rows, shape.project);
    }

    /** A traversal frontier entry: the node reached so far, and the accumulated row of every variable bound. */
    private record Frontier(long nodeUid, Map<String, Val> row) {
    }

    private void expandChainHop(final Txn<ByteBuffer> readTxn, final long fromUid, final Instant asOf,
                                final Expand hop, final Map<String, Val> rowSoFar, final boolean isLastHop,
                                final Predicate<Map<String, Val>> wherePredicate, final List<Frontier> nextFrontier,
                                final List<Map<String, Val>> finalRows) {
        // GraphAdjacencyDb/GraphInEdgeDb key every edge by a concrete edgeTypeUid; an untyped pattern
        // (edgeType == null, matching any type) has no single prefix to scan, and neither store exposes an
        // "any edge type" access path.
        final Long edgeTypeUid = hop.edgeType() == null
                ? null
                : lookupUid(readTxn, stores.getEdgeTypeUids(), hop.edgeType()).orElse(null);
        if (edgeTypeUid == null && hop.edgeType() == null) {
            throw new UnsupportedOperationException(
                    "not yet supported: an untyped edge pattern (matching any edge type) has no access path "
                    + "over the per-type-keyed adjacency stores");
        }
        if (hop.edgeType() != null && edgeTypeUid == null) {
            return;
        }

        final Consumer<Long> onNeighbourUid = neighbourUid -> acceptChainNeighbour(
                readTxn, neighbourUid, asOf, hop, rowSoFar, isLastHop, wherePredicate, nextFrontier, finalRows);

        // Task P1.1: dispatch on Expand.direction() - previously this always read the out-edge store regardless
        // of direction, so a Cypher <-[:TYPE]- or -[:TYPE]- pattern silently executed as -[:TYPE]->.
        switch (hop.direction()) {
            case OUT -> stores.getOutEdges().expandOut(
                    readTxn, fromUid, edgeTypeUid, asOf, neighbour -> onNeighbourUid.accept(neighbour.dstUid()));
            case IN -> stores.getInEdges().expandIn(
                    readTxn, fromUid, edgeTypeUid, asOf, neighbour -> onNeighbourUid.accept(neighbour.srcUid()));
            case BOTH -> {
                stores.getOutEdges().expandOut(
                        readTxn, fromUid, edgeTypeUid, asOf,
                        neighbour -> onNeighbourUid.accept(neighbour.dstUid()));
                stores.getInEdges().expandIn(
                        readTxn, fromUid, edgeTypeUid, asOf,
                        neighbour -> onNeighbourUid.accept(neighbour.srcUid()));
            }
        }
    }

    private void acceptChainNeighbour(final Txn<ByteBuffer> readTxn, final long neighbourUid, final Instant asOf,
                                      final Expand hop, final Map<String, Val> rowSoFar, final boolean isLastHop,
                                      final Predicate<Map<String, Val>> wherePredicate,
                                      final List<Frontier> nextFrontier, final List<Map<String, Val>> finalRows) {
        final Optional<GraphNodeDb.NodeVersion> target = stores.getNodes().getNode(readTxn, neighbourUid, asOf);
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
    private void expandVarLength(final Txn<ByteBuffer> readTxn, final long anchorUid, final Instant asOf,
                                 final VarLengthExpand varLengthExpand, final Map<String, Val> anchorRow,
                                 final Predicate<Map<String, Val>> wherePredicate,
                                 final List<Map<String, Val>> rows) {
        // GraphAdjacencyDb/GraphInEdgeDb key every edge by a concrete edgeTypeUid; an untyped pattern
        // (edgeType == null, matching any type) has no single prefix to scan, and neither store exposes an
        // "any edge type" access path.
        final Long edgeTypeUid = varLengthExpand.edgeType() == null
                ? null
                : lookupUid(readTxn, stores.getEdgeTypeUids(), varLengthExpand.edgeType()).orElse(null);
        if (edgeTypeUid == null && varLengthExpand.edgeType() == null) {
            throw new UnsupportedOperationException(
                    "not yet supported: an untyped edge pattern (matching any edge type) has no access path "
                    + "over the per-type-keyed adjacency stores");
        }
        if (varLengthExpand.edgeType() != null && edgeTypeUid == null) {
            return;
        }

        if (varLengthExpand.minHops() == 0) {
            // A zero-length path binds the target variable to the anchor node itself.
            final Optional<GraphNodeDb.NodeVersion> anchorNode = stores.getNodes().getNode(readTxn, anchorUid, asOf);
            anchorNode.ifPresent(node -> acceptVarLengthRow(readTxn, varLengthExpand, anchorRow, node,
                    wherePredicate, rows));
        }

        List<PathState> frontier = List.of(new PathState(anchorUid, anchorRow, Set.of(anchorUid)));
        for (int depth = 1; depth <= varLengthExpand.maxHops() && !frontier.isEmpty(); depth++) {
            final List<PathState> next = new ArrayList<>();
            for (final PathState state : frontier) {
                final List<Long> neighbourUids = new ArrayList<>();
                collectNeighbours(
                        readTxn, state.nodeUid(), edgeTypeUid, asOf, varLengthExpand.direction(), neighbourUids::add);

                for (final long neighbourUid : neighbourUids) {
                    if (state.visited().contains(neighbourUid)) {
                        continue;
                    }
                    final Optional<GraphNodeDb.NodeVersion> target =
                            stores.getNodes().getNode(readTxn, neighbourUid, asOf);
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

    private void collectNeighbours(final Txn<ByteBuffer> readTxn, final long fromUid, final long edgeTypeUid,
                                   final Instant asOf, final Direction direction, final Consumer<Long> collector) {
        switch (direction) {
            case OUT -> stores.getOutEdges().expandOut(
                    readTxn, fromUid, edgeTypeUid, asOf, neighbour -> collector.accept(neighbour.dstUid()));
            case IN -> stores.getInEdges().expandIn(
                    readTxn, fromUid, edgeTypeUid, asOf, neighbour -> collector.accept(neighbour.srcUid()));
            case BOTH -> {
                stores.getOutEdges().expandOut(
                        readTxn, fromUid, edgeTypeUid, asOf, neighbour -> collector.accept(neighbour.dstUid()));
                stores.getInEdges().expandIn(
                        readTxn, fromUid, edgeTypeUid, asOf, neighbour -> collector.accept(neighbour.srcUid()));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // anchor resolution
    // ------------------------------------------------------------------------------------------------------

    private List<Long> resolveAnchors(final Txn<ByteBuffer> readTxn, final NodeScan nodeScan, final Instant asOf) {
        if (nodeScan.labels().isEmpty()) {
            throw new UnsupportedOperationException(
                    "not yet supported: an anchor MATCH requires at least one label to seek the property index "
                    + "(a full unlabelled scan is not indexed in PoC.5)");
        }
        final List<ExpressionItem> terms = nodeScan.propertyAnchor() == null
                ? List.of()
                : nodeScan.propertyAnchor().getChildren();
        if (terms == null || terms.isEmpty()) {
            throw new UnsupportedOperationException(
                    "not yet supported: an anchor MATCH requires at least one property predicate (a "
                    + "label-only \"scan every node with this label\" access path is not indexed in PoC.5)");
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
            final Optional<GraphNodeDb.NodeVersion> node = stores.getNodes().getNode(readTxn, candidate, asOf);
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
                             Project project) {
    }

    private static PlanShape unwrap(final LogicalPlan plan) {
        LogicalPlan current = plan;
        while (current instanceof final Sort sort) {
            current = sort.input();
        }
        while (current instanceof final Limit limit) {
            current = limit.input();
        }
        if (!(current instanceof final Project project)) {
            throw new IllegalArgumentException(
                    "Unsupported compiled plan shape for graph traversal: expected a Project node (after "
                    + "unwrapping Sort/Limit), found " + current.getClass().getSimpleName());
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
        return new PlanShape(nodeScan, hops, varLengthExpand, where, project);
    }

    private static List<Val[]> project(final List<Map<String, Val>> rows, final Project project) {
        final List<ProjectField> fields = project.fields();
        final List<Val[]> out = new ArrayList<>(rows.size());
        for (final Map<String, Val> row : rows) {
            final Val[] values = new Val[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                values[i] = evaluate(fields.get(i), row);
            }
            out.add(values);
        }
        return out;
    }

    private static Val evaluate(final ProjectField field, final Map<String, Val> row) {
        final String expr = field.rawExpression();
        if (expr.startsWith("${") && expr.endsWith("}")) {
            final String reference = expr.substring(2, expr.length() - 1);
            if (row.containsKey(reference)) {
                return row.get(reference);
            }
            // A bare variable reference (e.g. "${a}", the whole matched node) has no single Val representation
            // yet - a real gap, not silently wrong: report it as a string so a query naming a bare variable at
            // least returns *something* identifying the row, rather than throwing mid-projection.
            return ValString.create(reference);
        }
        throw new UnsupportedOperationException(
                "not yet supported: RETURN item '" + expr + "' is not a bare property/variable reference - "
                + "literals, aggregates and function calls need the full ExpressionParser, not wired to a "
                + "graph traversal row in PoC.5");
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

    private static Instant resolveAsOf(final @Nullable TemporalContext temporalContext) {
        if (temporalContext == null) {
            return LATEST;
        }
        return switch (temporalContext.mode()) {
            case AS_OF -> temporalContext.instant();
            case AROUND, BETWEEN -> throw new UnsupportedOperationException(
                    "not yet supported: AROUND/BETWEEN window-intersection scans are a P4 deliverable (P0.3) - "
                    + "this PoC.5 engine only performs the as-of floor lookup PoC.4's stores implement");
        };
    }
}
