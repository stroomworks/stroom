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
import stroom.query.api.Column;
import stroom.query.api.GraphSpec;
import stroom.query.api.GroupSelection;
import stroom.query.api.Query;
import stroom.query.api.ResultRequest;
import stroom.query.api.ResultRequest.Fetch;
import stroom.query.api.ResultRequest.ResultStyle;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
import stroom.query.grammar.ast.AstPosition;
import stroom.query.grammar.ast.cypher.AstCypherQuery;
import stroom.query.grammar.ast.cypher.AstCypherStatement;
import stroom.query.grammar.parse.CypherQueryParser;
import stroom.query.language.SearchRequestFactory;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.cypher.CompiledCypherStatement;
import stroom.query.planner.cypher.CypherCompileException;
import stroom.query.planner.cypher.CypherToLogicalPlan;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.Sort;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Cypher analogue of {@code stroom.query.language.QueryCompiler}/{@code OptimisingQueryCompiler} (Task
 * PoC.6): turns Cypher source text into a {@link SearchRequest} that {@link GraphSearchProvider} can execute.
 *
 * <p>Deliberately does <b>not</b> implement the {@code QueryCompiler} interface (which lives in
 * {@code stroom-query-common} and also declares {@code extractDataSourceOnly}/{@code explain}) - routing a
 * submitted query to this seam vs. the StroomQL one, and an {@code ExplainPlan} rendering for Cypher, are later-
 * phase concerns (see the implementation plan's P5+ outline) out of scope for a single-hop PoC.</p>
 */
public final class CypherCompiler {

    private final DocFinder docFinder;

    /**
     * @param docFinder never null; used only when a query is compiled standalone (see {@link #create}'s
     *                  Javadoc) to resolve its own leading {@code from "X"} clause to a {@code GraphDbDoc}
     *                  {@link DocRef} - mirrors {@link GraphSearchProvider}'s use of the same {@code DocFinder}.
     */
    public CypherCompiler(final DocFinder docFinder) {
        this.docFinder = Objects.requireNonNull(docFinder, "docFinder");
    }

    /**
     * <b>Preconditions:</b> none of the three parameters is null; {@code in.getQuery} is not null.
     * <b>Postconditions:</b> the returned request's {@code Query.dataSource} names the target {@code GraphDbDoc}
     * - Workstream A: {@code in.getQuery.getDataSource} wins
     * when already set (the normal path, pre-resolved by {@code QueryServiceImpl.mapRequest} from either
     * {@code SearchRequestSource.ownerDocRef} or the query text's own leading {@code from "X"}); otherwise
     * {@code cypher}'s own leading {@code from "X"} clause is resolved here via {@link #docFinder} (a caller
     * invoking this compiler directly, bypassing {@code mapRequest} entirely). Its {@code Query.graphSpec}
     * carries {@code cypher} verbatim - see {@link GraphSpec}'s Javadoc for why the compiled plan itself is not
     * attached to the wire request. Throws {@link CypherCompileException} - for a query outside the locked v1
     * subset, or when neither a pre-set data source nor a leading {@code from "X"} clause names a resolvable
     * {@code GraphDbDoc} - or a grammar {@code SyntaxException}, so a bad query fails fast here rather than
     * reaching {@link GraphSearchProvider} silently.
     * <b>Null status:</b> no parameter or the return value is nullable.
     *
     * @param cypher            never null; the Cypher source text.
     * @param in                never null; only {@code Query.dataSource} is read from it - everything else
     *                          (params, time range) is carried through unchanged.
     * @param expressionContext never null; accepted for parity with the StroomQL compiler seam - unused because
     *                          the v1 Cypher subset has no expression-context-dependent terms (e.g. dashboard
     *                          {@code ${param}} substitution) of its own.
     * @return never null.
     */
    public SearchRequest create(final String cypher,
                                 final SearchRequest in,
                                 final ExpressionContext expressionContext) {
        Objects.requireNonNull(cypher, "cypher");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(expressionContext, "expressionContext");
        final Query inQuery = Objects.requireNonNull(in.getQuery(), "in.getQuery()");

        // Fail fast: parse + compile now so a query outside the v1 subset is rejected at compile time, not
        // silently deferred to GraphSearchProvider re-parsing it at execution time. A UNION statement is compiled
        // in full here (so a bad branch / mismatched columns fails fast); its branches all share output columns and
        // datasource, so the first branch's plan describes the result columns.
        final AstCypherStatement statement = CypherQueryParser.parseStatement(cypher);
        final CompiledCypherStatement compiled = new CypherToLogicalPlan().compileStatement(statement);
        final AstCypherQuery ast = statement.branches().getFirst();
        final LogicalPlan plan = compiled.first().plan();

        final DocRef dataSource = resolveDataSource(inQuery.getDataSource(), ast);

        final Query query = inQuery.copy()
                .dataSource(dataSource)
                .graphSpec(GraphSpec.builder().cypher(cypher).build())
                .build();
        return in.copy()
                .query(query)
                .resultRequests(buildResultRequests(in, plan))
                .build();
    }

    /**
     * <b>Preconditions:</b> {@code ast} is not null.
     * <b>Postconditions:</b> {@code preSetDataSource} always wins when present - see {@link #create}'s Javadoc for
     * why. Throws {@link CypherCompileException} if neither it nor {@code ast.dataSourceName} is present, or the
     * named graph cannot be resolved to exactly one {@code GraphDbDoc}.
     * <b>Null status:</b> {@code preSetDataSource} is nullable; neither {@code ast} nor the return value is
     * nullable.
     */
    private DocRef resolveDataSource(final @Nullable DocRef preSetDataSource, final AstCypherQuery ast) {
        if (preSetDataSource != null) {
            return preSetDataSource;
        }
        final String dataSourceName = ast.dataSourceName();
        if (dataSourceName == null) {
            throw new CypherCompileException(
                    "No target GraphDb: the query has neither a caller-supplied data source nor its own "
                    + "leading from \"...\" clause.",
                    ast.position());
        }
        return findGraphDb(dataSourceName, ast.position());
    }

    /**
     * Resolves {@code name} to a {@code GraphDbDoc} {@link DocRef}, by UUID then by name - the same two-step
     * lookup {@link stroom.query.language.DataSourceResolver#resolveDataSourceRef} uses, but locked to
     * {@link GraphDbDoc#TYPE} since a standalone Cypher query's own {@code from "X"} clause can only ever name a
     * graph.
     *
     * @throws CypherCompileException if {@code name} resolves to zero or more than one {@code GraphDbDoc}.
     */
    private DocRef findGraphDb(final String name, final AstPosition position) {
        final DocRef candidate = new DocRef(GraphDbDoc.TYPE, name);
        final Optional<DocRef> byUuid = docFinder.decorateIfExists(candidate);
        if (byUuid.isPresent()) {
            return byUuid.get();
        }

        final List<DocRef> docRefs = docFinder.findByName(GraphDbDoc.TYPE, name, false);
        if (docRefs.isEmpty()) {
            throw new CypherCompileException(GraphDbDoc.TYPE + " \"" + name + "\" not found", position);
        } else if (docRefs.size() > 1) {
            throw new CypherCompileException(
                    "Multiple " + GraphDbDoc.TYPE + " items found with name \"" + name + "\"", position);
        }
        return docRefs.getFirst();
    }

    /**
     * Derives a single table {@link ResultRequest} from the {@code RETURN} clause's visible {@link ProjectField}s
     * - the Cypher analogue of {@code AstToSearchRequestMapper#addTableSettings}. Each column's expression is a
     * plain {@code ${name}} field reference rather than a re-parsed Cypher expression: {@link GraphSearchProvider}
     * resolves a row's values by {@link ProjectField#name} directly against the same {@code FieldIndex}, so a
     * {@code ${name}} reference (which also resolves via {@code fieldIndex.create(name)}, see
     * {@code ParamFactory#createRef}) lines up with that mapping without needing to understand Cypher expression
     * syntax at all.
     */
    private List<ResultRequest> buildResultRequests(final SearchRequest in, final LogicalPlan plan) {
        final List<ProjectField> fields = terminalProject(plan).fields();

        final TableSettings.Builder tableSettingsBuilder = TableSettings.builder().extractValues(false);
        for (final ProjectField field : fields) {
            if (field.visible()) {
                tableSettingsBuilder.addColumns(Column.builder()
                        .id(field.name())
                        .name(field.name())
                        .expression("${" + field.name() + "}")
                        .visible(true)
                        .build());
            }
        }

        final ResultRequest tableResultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .searchRequestSource(in.getSearchRequestSource())
                .mappings(Collections.singletonList(tableSettingsBuilder.build()))
                .resultStyle(ResultStyle.TABLE)
                .fetch(Fetch.ALL)
                .groupSelection(new GroupSelection())
                .build();
        return Collections.singletonList(tableResultRequest);
    }

    /**
     * Walks past any {@link Limit} then {@link Sort} wrapper to the plan's terminal {@link Project} node - the same
     * unwrapping {@code CompiledCypherPlan#outputFields} and {@code CypherJoinSchema#terminalProjectFields}
     * perform, kept as a small separate copy here rather than shared. Keep all three in step if what
     * {@code CypherToLogicalPlan} emits changes.
     */
    private static Project terminalProject(final LogicalPlan plan) {
        LogicalPlan current = plan;
        while (current instanceof final Limit limit) {
            current = limit.input();
        }
        while (current instanceof final Sort sort) {
            current = sort.input();
        }
        if (!(current instanceof final Project project)) {
            throw new IllegalArgumentException(
                    "Unsupported compiled plan shape for graph traversal: expected a Project node (after "
                    + "unwrapping Limit/Sort), found " + current.getClass().getSimpleName());
        }
        return project;
    }
}
