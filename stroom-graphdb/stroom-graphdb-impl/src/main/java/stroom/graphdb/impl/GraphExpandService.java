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

import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphElementTable;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.cypher.DiffContext;
import stroom.query.planner.cypher.TemporalContext;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Serves the Data-tab graph view's "Expand neighbours" action: given a node's external id, returns that node plus
 * its neighbours (all edge types, both directions) as {@code RETURN GRAPH} element rows, for the client to merge
 * into the already-loaded graph. See {@link GraphTraversalEngine#expandNodeNeighbours}.
 */
class GraphExpandService {

    /** Bounds how many neighbour nodes a single expand returns, so a hub does not flood the view. */
    private static final int MAX_NEIGHBOURS = 50;

    private final GraphStoreManager graphStoreManager;
    private final ExpressionPredicateFactory expressionPredicateFactory;
    private final Provider<GraphDbConfig> configProvider;

    @Inject
    GraphExpandService(final GraphStoreManager graphStoreManager,
                       final ExpressionPredicateFactory expressionPredicateFactory,
                       final Provider<GraphDbConfig> configProvider) {
        this.graphStoreManager = Objects.requireNonNull(graphStoreManager, "graphStoreManager");
        this.expressionPredicateFactory =
                Objects.requireNonNull(expressionPredicateFactory, "expressionPredicateFactory");
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
    }

    /**
     * @param doc   the graph.
     * @param nodeId the external id of the node to expand.
     * @param query the query text that produced the currently displayed graph; its temporal clause is honoured so
     *              the expansion matches the displayed instant/window (null/blank or un-compilable -> latest).
     */
    GraphElementTable expand(final GraphDbDoc doc, final String nodeId, final String query) {
        Objects.requireNonNull(doc, "doc");
        if (nodeId == null || nodeId.isEmpty()) {
            return new GraphElementTable(CypherToLogicalPlan.ELEMENT_ROW_COLUMNS, List.of());
        }
        final TemporalContext temporalContext = resolveTemporalContext(query);
        return graphStoreManager.useForQuery(doc, stores -> stores.read(readTxn -> {
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, expressionPredicateFactory,
                    GraphTraversalLimits.from(configProvider.get()));
            final List<Val[]> valRows = GraphElementExecutor.executeExpand(
                    readTxn, engine, stores, nodeId, MAX_NEIGHBOURS, temporalContext);

            final List<List<String>> rows = new ArrayList<>(valRows.size());
            for (final Val[] valRow : valRows) {
                final List<String> row = new ArrayList<>(valRow.length);
                for (final Val val : valRow) {
                    row.add(val == null || val == ValNull.INSTANCE ? null : val.toString());
                }
                rows.add(row);
            }
            return new GraphElementTable(CypherToLogicalPlan.ELEMENT_ROW_COLUMNS, rows);
        }));
    }

    /**
     * Recovers the temporal context of the displayed graph by compiling its query text, so an expand runs at the
     * same instant/window. A {@code DIFF} query has no single instant - expand at its comparison ("after") instant.
     * A null/blank or un-compilable query falls back to the latest snapshot (null context).
     */
    private static @Nullable TemporalContext resolveTemporalContext(final String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            final CompiledCypherPlan compiled = new CypherToLogicalPlan().compile(CypherQueryParser.parse(query));
            final DiffContext diffContext = compiled.diffContext();
            if (diffContext != null) {
                return new TemporalContext(TemporalContext.Mode.AS_OF, diffContext.comparison(), null, null);
            }
            return compiled.temporalContext();
        } catch (final RuntimeException e) {
            // A query that no longer compiles (e.g. mid-edit) must not break expand - just use the latest snapshot.
            return null;
        }
    }
}
