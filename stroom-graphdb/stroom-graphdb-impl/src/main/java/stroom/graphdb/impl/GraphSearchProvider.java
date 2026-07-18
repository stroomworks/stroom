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

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.api.GraphSpec;
import stroom.query.api.Query;
import stroom.query.api.SearchRequest;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.IndexField;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.CoprocessorsFactory;
import stroom.query.common.v2.CoprocessorsImpl;
import stroom.query.common.v2.DataStoreSettings;
import stroom.query.common.v2.ExpressionPredicateFactory;
import stroom.query.common.v2.FieldInfoResultPageFactory;
import stroom.query.common.v2.IndexFieldProvider;
import stroom.query.common.v2.ResultStore;
import stroom.query.common.v2.ResultStoreFactory;
import stroom.query.common.v2.SearchProvider;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.Val;
import stroom.query.language.functions.ValNull;
import stroom.query.planner.cypher.CompiledCypherPlan;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.Sort;
import stroom.security.api.SecurityContext;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A {@link SearchProvider} for {@link GraphDbDoc} (Task PoC.6): resolves the target doc from
 * {@code Query.dataSource}, opens its internal stores through {@link GraphStoreManager} (never addressing a
 * Plan B/Index doc directly), re-parses and re-compiles the {@link GraphSpec#getCypher()} text carried on the
 * query (see that class's Javadoc for why the compiled plan itself is not carried on the wire), runs
 * {@link GraphTraversalEngine} over the doc's stores, and feeds the resulting rows into real coprocessors at the
 * {@link FieldIndex} positions the {@code RETURN} columns claimed.
 *
 * <p>Synchronous, like {@code stroom.searchable.impl.JoinSearchProvider} - not async like
 * {@code stroom.planb.impl.StateSearchProvider} - because {@link GraphTraversalEngine#execute} is itself a
 * synchronous, in-memory call scoped to a single LMDB read transaction, with no shard/network fan-out to
 * dispatch asynchronously.</p>
 *
 * <p>Unlike {@code JoinSearchProvider}, no separate {@code whereRowPredicate} is needed here: a Cypher query's
 * {@code WHERE} clause is embedded in {@link GraphSpec#getCypher()} and already applied inside
 * {@link GraphTraversalEngine#execute} - {@code Query.expression} plays no part in a graph query.</p>
 */
public class GraphSearchProvider implements SearchProvider, IndexFieldProvider {

    private final GraphDbDocCache graphDbDocCache;
    private final GraphDbDocStore graphDbDocStore;
    private final GraphStoreManager graphStoreManager;
    private final CoprocessorsFactory coprocessorsFactory;
    private final ResultStoreFactory resultStoreFactory;
    private final ExpressionPredicateFactory expressionPredicateFactory;
    private final SecurityContext securityContext;
    private final FieldInfoResultPageFactory fieldInfoResultPageFactory;
    private final DocFinder docFinder;

    @Inject
    public GraphSearchProvider(final GraphDbDocCache graphDbDocCache,
                               final GraphDbDocStore graphDbDocStore,
                               final GraphStoreManager graphStoreManager,
                               final CoprocessorsFactory coprocessorsFactory,
                               final ResultStoreFactory resultStoreFactory,
                               final ExpressionPredicateFactory expressionPredicateFactory,
                               final SecurityContext securityContext,
                               final FieldInfoResultPageFactory fieldInfoResultPageFactory,
                               final DocFinder docFinder) {
        this.graphDbDocCache = graphDbDocCache;
        this.graphDbDocStore = graphDbDocStore;
        this.graphStoreManager = graphStoreManager;
        this.coprocessorsFactory = coprocessorsFactory;
        this.resultStoreFactory = resultStoreFactory;
        this.expressionPredicateFactory = expressionPredicateFactory;
        this.securityContext = securityContext;
        this.fieldInfoResultPageFactory = fieldInfoResultPageFactory;
        this.docFinder = docFinder;
    }

    private GraphDbDoc getGraphDbDoc(final DocRef docRef) {
        return securityContext.useAsReadResult(() -> {
            Objects.requireNonNull(docRef, "Null doc reference");
            Objects.requireNonNull(docRef.getName(), "Null doc key");
            final GraphDbDoc doc = graphDbDocCache.get(docRef.getName());
            Objects.requireNonNull(doc, "Null graph db doc");
            return doc;
        });
    }

    @Override
    public String getDataSourceType() {
        return GraphDbDoc.TYPE;
    }

    @Override
    public List<DocRef> getDataSourceDocRefs() {
        return graphDbDocStore.list();
    }

    @Override
    public List<DocRef> findDataSourceByName(final String name) {
        return docFinder.findByName(getDataSourceType(), name);
    }

    @Override
    public ResultPage<QueryField> getFieldInfo(final FindFieldCriteria criteria) {
        // A GraphDbDoc has no fixed field schema - a node/edge's properties are whatever GraphPropsCodec decoded
        // at ingest, not a declared column list like a PlanB/Index doc's - so there is no static field list to
        // report yet. Introspecting nodeTypeMappings-derived domain-type schemas is a later phase, not PoC.6.
        return fieldInfoResultPageFactory.create(criteria, List.of());
    }

    @Override
    public int getFieldCount(final DocRef docRef) {
        return 0;
    }

    @Override
    public IndexField getIndexField(final DocRef docRef, final String fieldName) {
        return null;
    }

    @Override
    public ResultStore createResultStore(final SearchRequest searchRequest) {
        final Query query = Objects.requireNonNull(searchRequest.getQuery(), "searchRequest.getQuery()");
        final GraphSpec graphSpec = query.getGraphSpec();
        if (graphSpec == null) {
            throw new IllegalArgumentException(
                    "SearchRequest routed to " + GraphDbDoc.TYPE + " must carry a GraphSpec");
        }
        final DocRef docRef = Objects.requireNonNull(query.getDataSource(), "query.getDataSource()");
        final GraphDbDoc doc = getGraphDbDoc(docRef);

        final CompiledCypherPlan compiled = new CypherToLogicalPlan().compile(
                CypherQueryParser.parse(graphSpec.getCypher()));
        final List<ProjectField> projectFields = terminalProject(compiled.plan()).fields();

        final CoprocessorsImpl coprocessors = coprocessorsFactory.create(
                searchRequest, DataStoreSettings.createBasicSearchResultStoreSettings());
        final FieldIndex fieldIndex = coprocessors.getFieldIndex();
        final int[] mapping = buildFieldMapping(fieldIndex, projectFields);

        final ResultStore resultStore = resultStoreFactory.create(
                searchRequest.getSearchRequestSource(), coprocessors);
        try {
            final GraphStores stores = graphStoreManager.getOrOpen(doc);
            final GraphTraversalEngine engine = new GraphTraversalEngine(stores, expressionPredicateFactory);
            final List<Val[]> rows = stores.read(readTxn ->
                    engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                            searchRequest.getDateTimeSettings()));
            for (final Val[] row : rows) {
                coprocessors.accept(assembleRow(row, mapping, fieldIndex.size()));
            }
        } catch (final RuntimeException e) {
            resultStore.addError(e);
        } finally {
            resultStore.signalComplete();
        }
        return resultStore;
    }

    /**
     * Walks past any {@link Sort}/{@link Limit} wrapper to the plan's terminal {@link Project} node - mirrors
     * {@code GraphTraversalEngine.unwrap}'s own first step (kept as a small separate copy rather than exposing
     * that private method, since this is all the caller needs from the plan shape).
     */
    private static Project terminalProject(final LogicalPlan plan) {
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
        return project;
    }

    /**
     * For each of {@link GraphTraversalEngine}'s {@code Val[]} positions (in {@code fields} order), resolves the
     * {@link FieldIndex} position its {@link ProjectField#name()} is registered at - creating the entry if the
     * caller's own {@code TableSettings} column expressions did not already claim it. Mirrors
     * {@code JoinSearchProvider.buildFieldMapping}'s name-based re-mapping, adapted from "left/right column" to
     * "compiled Project field" as the internal row shape.
     */
    static int[] buildFieldMapping(final FieldIndex fieldIndex, final List<ProjectField> fields) {
        final int[] mapping = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            mapping[i] = fieldIndex.create(fields.get(i).name());
        }
        return mapping;
    }

    /**
     * Mirrors {@code JoinSearchProvider.assembleRow}: scatters {@code projectedRow} (in {@code Project} field
     * order) into a {@code fieldIndexSize}-wide row at the positions {@code mapping} resolved, leaving every
     * other position {@link ValNull#INSTANCE}.
     */
    static Val[] assembleRow(final Val[] projectedRow, final int[] mapping, final int fieldIndexSize) {
        final Val[] out = new Val[fieldIndexSize];
        Arrays.fill(out, ValNull.INSTANCE);
        for (int i = 0; i < mapping.length; i++) {
            if (mapping[i] >= 0) {
                out[mapping[i]] = projectedRow[i];
            }
        }
        return out;
    }
}
