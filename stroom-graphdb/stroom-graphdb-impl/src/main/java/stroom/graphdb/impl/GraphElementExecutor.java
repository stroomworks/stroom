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
import stroom.query.language.functions.Type;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.language.functions.ValString;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.cypher.DiffContext;
import stroom.query.planner.cypher.TemporalContext;
import stroom.query.planner.logical.LogicalPlan;

import org.jspecify.annotations.Nullable;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Executes a {@code RETURN GRAPH} query (see {@code docs/temporal-cypher-diff-operator.md} &sect;4.4 / {@code
 * docs/graphdb-cytoscape-visualisation.html} &sect;3): the element-row output mode, plain or combined with {@code
 * DIFF} (the annotated-subgraph mode). Mirrors {@link DiffExecutor}'s role for the delta table - a small,
 * dependency-free static entry point over {@link GraphTraversalEngine} - but is kept as its own class rather than
 * folded into {@link DiffExecutor}, since a <em>plain</em> {@code RETURN GRAPH} has no diff/classification step at
 * all.
 *
 * <p><b>Plain form</b> ({@code diffContext == null}): {@link GraphTraversalEngine#executeGraphBindings} already
 * returns the de-duplicated, {@link ElementId}-keyed element union for one snapshot - one row per entry, no
 * {@code changeKind} column.</p>
 *
 * <p><b>{@code DIFF ... RETURN GRAPH} form</b> ({@code diffContext != null}): collects the element union at each
 * instant ({@link GraphTraversalEngine#executeGraphBindingsAsOf}), converts each instant's {@code Map<ElementId,
 * ElementDetail>} to a {@code List<DiffMatch>} of <b>singleton-identity</b> matches (one {@link ElementId} per
 * identity, {@link ElementDetail#properties()} as the flat row), and feeds both to the existing, unchanged {@link
 * DiffOperator#classify} - per-element classification (as &sect;5.6 requires: "each node/edge carries its own
 * changeKind, not a path roll-up") falls straight out of that generic path-identity machinery once identity is a
 * one-element list. {@code UNCHANGED} elements are <b>kept</b> here (unlike {@link DiffExecutor}, which suppresses
 * them for the delta table) - they are the connectivity context &sect;5.6 says the annotated-subgraph mode exists
 * to preserve.</p>
 */
public final class GraphElementExecutor {

    private GraphElementExecutor() {
    }

    /**
     * @param readTxn          the read transaction to traverse under; never null.
     * @param engine           the traversal engine bound to the target graph's stores; never null.
     * @param stores           the same graph's stores, used to reverse-resolve external ids for the {@code id}/
     *                         {@code source}/{@code target} columns; never null.
     * @param plan             the compiled {@code RETURN GRAPH} plan (see {@code CypherToLogicalPlan
     *                         .compileReturnGraph}); never null.
     * @param temporalContext  the plain form's resolved temporal clause, or {@code null} for "latest" - ignored
     *                         (must be {@code null}) when {@code diffContext} is non-null, matching {@code
     *                         CompiledCypherPlan}'s "state query or diff, never both" invariant.
     * @param diffContext      {@code null} for the plain form; the resolved baseline/comparison instants for the
     *                         annotated-subgraph form.
     * @param dateTimeSettings never null.
     * @return one {@code Val[]} row per element, in {@link CypherToLogicalPlan#ELEMENT_ROW_COLUMNS} order (plus a
     *         trailing {@code changeKind} when {@code diffContext != null}); never null (may be empty).
     */
    public static List<Val[]> execute(final Txn<ByteBuffer> readTxn, final GraphTraversalEngine engine,
                                      final GraphStores stores, final LogicalPlan plan,
                                      final @Nullable TemporalContext temporalContext,
                                      final @Nullable DiffContext diffContext,
                                      final DateTimeSettings dateTimeSettings) {
        Objects.requireNonNull(readTxn, "readTxn");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(stores, "stores");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(dateTimeSettings, "dateTimeSettings");

        if (diffContext != null) {
            return executeDiff(readTxn, engine, stores, plan, diffContext, dateTimeSettings);
        }

        final Map<ElementId, ElementDetail> elements =
                engine.executeGraphBindings(readTxn, plan, temporalContext, dateTimeSettings);
        final List<Val[]> rows = new ArrayList<>(elements.size());
        for (final Map.Entry<ElementId, ElementDetail> entry : elements.entrySet()) {
            rows.add(renderRow(readTxn, stores, entry.getKey(), entry.getValue(), null));
        }
        return rows;
    }

    private static List<Val[]> executeDiff(final Txn<ByteBuffer> readTxn, final GraphTraversalEngine engine,
                                           final GraphStores stores, final LogicalPlan plan,
                                           final DiffContext diffContext, final DateTimeSettings dateTimeSettings) {
        final Map<ElementId, ElementDetail> baselineElements =
                engine.executeGraphBindingsAsOf(readTxn, plan, diffContext.baseline(), dateTimeSettings);
        final Map<ElementId, ElementDetail> comparisonElements =
                engine.executeGraphBindingsAsOf(readTxn, plan, diffContext.comparison(), dateTimeSettings);

        final List<ClassifiedMatch> classified = DiffOperator.classify(
                toDiffMatches(baselineElements), toDiffMatches(comparisonElements));

        final List<Val[]> rows = new ArrayList<>(classified.size());
        for (final ClassifiedMatch match : classified) {
            final ElementId id = match.identity().getFirst();
            final ElementDetail detail = match.changeKind() == ChangeKind.REMOVED
                    ? baselineElements.get(id)
                    : comparisonElements.get(id);
            rows.add(renderRow(readTxn, stores, id, detail, match.changeKind()));
        }
        return rows;
    }

    /**
     * Converts a snapshot's element union into {@link DiffOperator#classify}'s expected shape: one {@link
     * DiffMatch} per element, whose identity is the <b>singleton</b> list {@code [elementId]} and whose flat row
     * is that element's own (unprefixed) property map - reusing the generic path-identity classifier for a
     * per-element classification, exactly as {@link TestDiffOperator}'s own {@code node(uid, flatRow)} test helper
     * already does by hand.
     */
    private static List<DiffMatch> toDiffMatches(final Map<ElementId, ElementDetail> elements) {
        final List<DiffMatch> matches = new ArrayList<>(elements.size());
        for (final Map.Entry<ElementId, ElementDetail> entry : elements.entrySet()) {
            matches.add(new DiffMatch(List.of(entry.getKey()), entry.getValue().properties()));
        }
        return matches;
    }

    /**
     * Renders one element to a {@code Val[]} row in {@link CypherToLogicalPlan#ELEMENT_ROW_COLUMNS} order:
     * {@code kind}, {@code id}, {@code labels}, {@code source}, {@code target}, {@code properties} - plus a 7th
     * {@code changeKind} column when {@code changeKind} is non-null (the {@code DIFF} form).
     */
    private static Val[] renderRow(final Txn<ByteBuffer> readTxn, final GraphStores stores, final ElementId id,
                                   final ElementDetail detail, final @Nullable ChangeKind changeKind) {
        final boolean isEdge = id instanceof ElementId.Edge;
        final Val[] row = new Val[changeKind == null ? 6 : 7];
        row[0] = ValString.create(isEdge ? "EDGE" : "NODE");
        row[1] = ValString.create(renderElementId(readTxn, stores, id, detail));
        row[2] = ValString.create(String.join(",", detail.labels()));
        row[3] = renderEndpoint(readTxn, stores, detail.source());
        row[4] = renderEndpoint(readTxn, stores, detail.target());
        row[5] = ValString.create(renderPropertiesAsJson(detail.properties()));
        if (changeKind != null) {
            row[6] = ValString.create(changeKind.name());
        }
        return row;
    }

    /**
     * The {@code id} column: a node's external id string; an edge's {@code src|type|dst}, using the edge's own
     * endpoints' external ids and its type name (already carried as {@code detail.labels()}'s single entry -
     * cheaper than a second {@code UidLookupDb} round trip for the edge-type namespace).
     */
    private static String renderElementId(final Txn<ByteBuffer> readTxn, final GraphStores stores,
                                          final ElementId id, final ElementDetail detail) {
        if (id instanceof final ElementId.Node node) {
            return externalId(readTxn, stores, node.uid());
        }
        final ElementId.Edge edge = (ElementId.Edge) id;
        return externalId(readTxn, stores, edge.srcUid())
               + "|" + detail.labels().getFirst() + "|"
               + externalId(readTxn, stores, edge.dstUid());
    }

    private static Val renderEndpoint(final Txn<ByteBuffer> readTxn, final GraphStores stores,
                                      final ElementId.@Nullable Node endpoint) {
        return endpoint == null ? ValNull.INSTANCE : ValString.create(externalId(readTxn, stores, endpoint.uid()));
    }

    private static String externalId(final Txn<ByteBuffer> readTxn, final GraphStores stores, final long nodeUid) {
        return GraphTraversalEngine.decodeUidName(readTxn, stores.getNodeUids(), nodeUid);
    }

    /**
     * Renders an element's property map as a single JSON object string (the {@code properties} column - see
     * {@link CypherToLogicalPlan#ELEMENT_ROW_COLUMNS}'s Javadoc for why one JSON column, not one column per
     * property key). Keys are sorted for a deterministic rendering (property insertion order is an LMDB storage
     * detail, not a meaningful ordering). Numeric/boolean values render unquoted; everything else (including
     * dates/durations/xml/error) renders as a quoted, escaped string via {@link Val#toString()}.
     */
    private static String renderPropertiesAsJson(final Map<String, Val> properties) {
        final Map<String, Val> sorted = new TreeMap<>(properties);
        final StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (final Map.Entry<String, Val> entry : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(jsonEscape(entry.getKey())).append("\":").append(renderJsonValue(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String renderJsonValue(final @Nullable Val value) {
        if (value == null || Type.NULL.equals(value.type())) {
            return "null";
        }
        return switch (value.type()) {
            case BOOLEAN -> String.valueOf(value.toBoolean());
            case INTEGER, LONG, BYTE, SHORT -> String.valueOf(value.toLong());
            case DOUBLE, FLOAT -> String.valueOf(value.toDouble());
            default -> "\"" + jsonEscape(value.toString()) + "\"";
        };
    }

    private static String jsonEscape(final String text) {
        final StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
