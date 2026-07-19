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
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.Sort;

import org.jspecify.annotations.Nullable;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Executes a compiled Cypher {@link LogicalPlan} over a {@link GraphStores}' physical stores (design doc
 * &sect;5.5's {@code expand} operator; implementation plan Task PoC.5): anchor scan (via
 * {@link GraphPropertyIndex}) &rarr; single-hop {@code expand} (via {@link GraphAdjacencyDb}, dereferencing each
 * neighbour via {@link GraphNodeDb}) &rarr; the outer {@code WHERE} predicate &rarr; {@code RETURN} projection.
 * Reuses {@link ExpressionPredicateFactory} exactly as {@code stroom.searchable.impl.JoinSearchProvider
 * #whereRowPredicate} does, over a {@code "variable.property" -> Val} row map instead of a joined {@code Values}
 * row - the graph analogue of that class's combined-row predicate.
 *
 * <p><b>Contract (PoC.5's scope - see this class's Javadoc for what's deliberately not yet handled):</b>
 * single-hop only (an {@link Expand} node; {@code VarLengthExpand} is P3); single-shard only (cross-shard is
 * P8); streams {@code Val[]} rows - does not itself build coprocessors (PoC.6 does that from these rows).</p>
 *
 * <p><b>Deliberately unsupported here (throws {@link UnsupportedOperationException} rather than a wrong
 * result)</b>: an anchor {@link NodeScan} with no label or no property predicate (the property index has no
 * "all nodes of this label" scan - only equality lookups); {@code AROUND}/{@code BETWEEN} temporal clauses (P0.3
 * resolved these as a window-intersection scan, a P4 deliverable - this engine only performs the as-of floor
 * lookup PoC.4's stores implement); a {@code RETURN} item other than a bare property/variable reference (a
 * literal, aggregate, or function call needs the full {@code ExpressionParser}, not wired to a graph row here).
 * A hop's non-anchor node's own labels/properties are not enforced, matching {@code CypherToLogicalPlan}'s own
 * documented PoC limitation.</p>
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
     * shape {@code [Sort/Limit ->] Project -> [Filter ->] [Expand ->] NodeScan} (see this class's Javadoc for
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
        for (final long anchorUid : resolveAnchors(readTxn, shape.nodeScan, asOf)) {
            final Optional<GraphNodeDb.NodeVersion> anchor = stores.getNodes().getNode(readTxn, anchorUid, asOf);
            if (anchor.isEmpty()) {
                continue;
            }
            final Map<String, Val> anchorRow = rowFor(shape.nodeScan.variable(), anchor.get().properties());

            if (shape.expand == null) {
                if (wherePredicate.test(anchorRow)) {
                    rows.add(anchorRow);
                }
                continue;
            }

            final Long edgeTypeUid = shape.expand.edgeType() == null
                    ? null
                    : lookupUid(readTxn, stores.getEdgeTypeUids(), shape.expand.edgeType()).orElse(null);
            if (shape.expand.edgeType() != null && edgeTypeUid == null) {
                continue;
            }
            expandOneHop(readTxn, anchorUid, edgeTypeUid, asOf, shape.expand, anchorRow, wherePredicate, rows);
        }

        return project(rows, shape.project);
    }

    private void expandOneHop(final Txn<ByteBuffer> readTxn, final long anchorUid, final @Nullable Long edgeTypeUid,
                              final Instant asOf, final Expand expand, final Map<String, Val> anchorRow,
                              final Predicate<Map<String, Val>> wherePredicate, final List<Map<String, Val>> rows) {
        // GraphAdjacencyDb/GraphInEdgeDb key every edge by a concrete edgeTypeUid; an untyped pattern
        // (edgeType == null, matching any type) has no single prefix to scan, and neither store exposes an
        // "any edge type" access path.
        if (edgeTypeUid == null && expand.edgeType() == null) {
            throw new UnsupportedOperationException(
                    "not yet supported: an untyped edge pattern (matching any edge type) has no access path "
                    + "over the per-type-keyed adjacency stores");
        }

        final Consumer<Long> onNeighbourUid = neighbourUid ->
                acceptNeighbour(readTxn, neighbourUid, asOf, expand, anchorRow, wherePredicate, rows);

        // Task P1.1: dispatch on Expand.direction() - previously this always read the out-edge store regardless
        // of direction, so a Cypher <-[:TYPE]- or -[:TYPE]- pattern silently executed as -[:TYPE]->.
        switch (expand.direction()) {
            case OUT -> stores.getOutEdges().expandOut(
                    readTxn, anchorUid, edgeTypeUid, asOf, neighbour -> onNeighbourUid.accept(neighbour.dstUid()));
            case IN -> stores.getInEdges().expandIn(
                    readTxn, anchorUid, edgeTypeUid, asOf, neighbour -> onNeighbourUid.accept(neighbour.srcUid()));
            case BOTH -> {
                stores.getOutEdges().expandOut(
                        readTxn, anchorUid, edgeTypeUid, asOf,
                        neighbour -> onNeighbourUid.accept(neighbour.dstUid()));
                stores.getInEdges().expandIn(
                        readTxn, anchorUid, edgeTypeUid, asOf,
                        neighbour -> onNeighbourUid.accept(neighbour.srcUid()));
            }
        }
    }

    private void acceptNeighbour(final Txn<ByteBuffer> readTxn, final long neighbourUid, final Instant asOf,
                                 final Expand expand, final Map<String, Val> anchorRow,
                                 final Predicate<Map<String, Val>> wherePredicate, final List<Map<String, Val>> rows) {
        final Optional<GraphNodeDb.NodeVersion> target = stores.getNodes().getNode(readTxn, neighbourUid, asOf);
        if (target.isEmpty()) {
            return;
        }
        final Map<String, Val> row = new HashMap<>(anchorRow);
        row.putAll(rowFor(expand.targetVariable(), target.get().properties()));
        if (wherePredicate.test(row)) {
            rows.add(row);
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

    private record PlanShape(NodeScan nodeScan, @Nullable Expand expand, @Nullable ExpressionOperator where,
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

        Expand expand = null;
        if (below instanceof final Expand e) {
            expand = e;
            below = e.input();
        }

        if (!(below instanceof final NodeScan nodeScan)) {
            throw new IllegalArgumentException(
                    "Unsupported compiled plan shape for graph traversal: expected a NodeScan leaf, found "
                    + below.getClass().getSimpleName());
        }
        return new PlanShape(nodeScan, expand, where, project);
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
