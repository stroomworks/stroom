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
import stroom.query.planner.cypher.CompiledCypherStatement;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.logical.ProjectField;
import stroom.security.api.SecurityContext;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.lmdbjava.Txn;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    private final Provider<GraphDbConfig> configProvider;

    @Inject
    public GraphSearchProvider(final GraphDbDocCache graphDbDocCache,
                               final GraphDbDocStore graphDbDocStore,
                               final GraphStoreManager graphStoreManager,
                               final CoprocessorsFactory coprocessorsFactory,
                               final ResultStoreFactory resultStoreFactory,
                               final ExpressionPredicateFactory expressionPredicateFactory,
                               final SecurityContext securityContext,
                               final FieldInfoResultPageFactory fieldInfoResultPageFactory,
                               final DocFinder docFinder,
                               final Provider<GraphDbConfig> configProvider) {
        this.graphDbDocCache = graphDbDocCache;
        this.graphDbDocStore = graphDbDocStore;
        this.graphStoreManager = graphStoreManager;
        this.coprocessorsFactory = coprocessorsFactory;
        this.resultStoreFactory = resultStoreFactory;
        this.expressionPredicateFactory = expressionPredicateFactory;
        this.securityContext = securityContext;
        this.fieldInfoResultPageFactory = fieldInfoResultPageFactory;
        this.docFinder = docFinder;
        this.configProvider = configProvider;
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

        // Code-review fix: coprocessors/fieldIndex/resultStore creation is deliberately moved ahead of the
        // execution-time compile below (previously the reverse order) so that compile's failures - like every
        // other failure once a ResultStore exists to attach an error to - are caught by the try block instead of
        // propagating raw out of this method. Building these needs only searchRequest, not the compiled plan, so
        // reordering changes nothing about what they're built from.
        final CoprocessorsImpl coprocessors = coprocessorsFactory.create(
                searchRequest, DataStoreSettings.createBasicSearchResultStoreSettings());
        final FieldIndex fieldIndex = coprocessors.getFieldIndex();
        final ResultStore resultStore = resultStoreFactory.create(
                searchRequest.getSearchRequestSource(), coprocessors);
        try {
            final CompiledCypherStatement statement = new CypherToLogicalPlan().compileStatement(
                    CypherQueryParser.parseStatement(graphSpec.getCypher()));
            // All UNION branches share the same output columns (checked at compile time), so the first branch's
            // columns describe the result. A WITH pipe's output columns are the final RETURN's fields (the second
            // stage), not stage one's WITH columns; every other query's are its terminal Project's - see
            // CompiledCypherPlan.outputFields().
            final int[] mapping = buildFieldMapping(fieldIndex, statement.first().outputFields());

            final GraphStores stores = graphStoreManager.getForQuery(doc);
            final GraphTraversalEngine engine = new GraphTraversalEngine(
                    stores, expressionPredicateFactory,
                    GraphTraversalLimits.from(configProvider.get()));
            final List<Val[]> rows = stores.read(readTxn ->
                    executeStatement(readTxn, engine, stores, statement, searchRequest));
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
     * Runs a whole statement, folding its {@code UNION} branches left-to-right: {@code UNION ALL} concatenates,
     * plain {@code UNION} additionally de-duplicates the accumulated rows by value (first-appearance order). A
     * single-branch (non-UNION) statement is just its one branch executed unchanged.
     */
    private static List<Val[]> executeStatement(final Txn<ByteBuffer> readTxn, final GraphTraversalEngine engine,
                                                 final GraphStores stores, final CompiledCypherStatement statement,
                                                 final SearchRequest searchRequest) {
        List<Val[]> result = executeBranch(readTxn, engine, stores, statement.branches().getFirst(), searchRequest);
        for (int i = 1; i < statement.branches().size(); i++) {
            final List<Val[]> branchRows =
                    executeBranch(readTxn, engine, stores, statement.branches().get(i), searchRequest);
            result = foldUnion(result, branchRows, statement.unionAll().get(i - 1));
        }
        return result;
    }

    /**
     * Executes one compiled branch to its {@code Val[]} rows. Three execution shapes, all fed identically
     * downstream: {@code RETURN GRAPH} (the element-row union, plain or under DIFF - {@link GraphElementExecutor});
     * a scalar DIFF (the delta table - {@link DiffExecutor}); every other query (a single ordinary traversal).
     */
    private static List<Val[]> executeBranch(final Txn<ByteBuffer> readTxn, final GraphTraversalEngine engine,
                                              final GraphStores stores, final CompiledCypherPlan compiled,
                                              final SearchRequest searchRequest) {
        if (compiled.returnGraph()) {
            return GraphElementExecutor.execute(readTxn, engine, stores, compiled.plan(),
                    compiled.temporalContext(), compiled.diffContext(), searchRequest.getDateTimeSettings());
        }
        return compiled.diffContext() != null
                ? DiffExecutor.execute(readTxn, engine, compiled.plan(), compiled.diffContext(),
                        searchRequest.getDateTimeSettings(), compiled.distinct())
                : engine.execute(readTxn, compiled.plan(), compiled.temporalContext(),
                        searchRequest.getDateTimeSettings(), compiled.distinct(), compiled.aggregation(),
                        compiled.fieldComparisons(), compiled.existsPredicates(), compiled.secondStage());
    }

    /**
     * Concatenates {@code accumulated} with {@code branch}; when {@code unionAll} is false (a plain {@code UNION})
     * de-duplicates the combined rows by value, keeping first appearance. Row equality is element-wise {@link Val}
     * equality (via {@link Arrays#asList}), matching the engine's {@code RETURN DISTINCT} de-duplication.
     */
    private static List<Val[]> foldUnion(final List<Val[]> accumulated, final List<Val[]> branch,
                                         final boolean unionAll) {
        final List<Val[]> combined = new ArrayList<>(accumulated.size() + branch.size());
        combined.addAll(accumulated);
        combined.addAll(branch);
        if (unionAll) {
            return combined;
        }
        final Set<List<Val>> seen = new HashSet<>();
        final List<Val[]> distinct = new ArrayList<>(combined.size());
        for (final Val[] row : combined) {
            if (seen.add(Arrays.asList(row))) {
                distinct.add(row);
            }
        }
        return distinct;
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
