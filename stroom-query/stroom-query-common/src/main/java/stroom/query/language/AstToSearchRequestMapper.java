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

package stroom.query.language;

import stroom.docref.DocRef;
import stroom.query.api.Column;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.ExpressionUtil;
import stroom.query.api.GroupSelection;
import stroom.query.api.HoppingWindow;
import stroom.query.api.IncludeExcludeFilter;
import stroom.query.api.ParamUtil;
import stroom.query.api.Query;
import stroom.query.api.ResultRequest;
import stroom.query.api.ResultRequest.Fetch;
import stroom.query.api.ResultRequest.ResultStyle;
import stroom.query.api.SearchRequest;
import stroom.query.api.SearchRequestSource;
import stroom.query.api.Sort;
import stroom.query.api.Sort.SortDirection;
import stroom.query.api.SpecialColumns;
import stroom.query.api.TableSettings;
import stroom.query.api.Window;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.api.datasource.QueryFieldProvider;
import stroom.query.api.token.AbstractToken;
import stroom.query.api.token.KeywordGroup;
import stroom.query.api.token.TokenException;
import stroom.query.api.token.TokenGroup;
import stroom.query.api.token.TokenType;
import stroom.query.common.v2.CompiledWindow;
import stroom.query.common.v2.DateExpressionParser;
import stroom.query.common.v2.DateExpressionParser.DatePoint;
import stroom.query.grammar.ast.AstAndExpr;
import stroom.query.grammar.ast.AstBetweenTerm;
import stroom.query.grammar.ast.AstClause;
import stroom.query.grammar.ast.AstComparisonTerm;
import stroom.query.grammar.ast.AstEvalClause;
import stroom.query.grammar.ast.AstFilterClause;
import stroom.query.grammar.ast.AstFrom;
import stroom.query.grammar.ast.AstGroupClause;
import stroom.query.grammar.ast.AstHavingClause;
import stroom.query.grammar.ast.AstInDictionaryTerm;
import stroom.query.grammar.ast.AstInTerm;
import stroom.query.grammar.ast.AstIsNullTerm;
import stroom.query.grammar.ast.AstJoin;
import stroom.query.grammar.ast.AstLimitClause;
import stroom.query.grammar.ast.AstNotExpr;
import stroom.query.grammar.ast.AstOrExpr;
import stroom.query.grammar.ast.AstPrimary;
import stroom.query.grammar.ast.AstQuery;
import stroom.query.grammar.ast.AstSelectClause;
import stroom.query.grammar.ast.AstSelectField;
import stroom.query.grammar.ast.AstSelectFunction;
import stroom.query.grammar.ast.AstSelectItem;
import stroom.query.grammar.ast.AstSelectParam;
import stroom.query.grammar.ast.AstSelectStar;
import stroom.query.grammar.ast.AstShowClause;
import stroom.query.grammar.ast.AstSortClause;
import stroom.query.grammar.ast.AstSortItem;
import stroom.query.grammar.ast.AstTerm;
import stroom.query.grammar.ast.AstToken;
import stroom.query.grammar.ast.AstValue;
import stroom.query.grammar.ast.AstWhereClause;
import stroom.query.grammar.ast.AstWindowClause;
import stroom.query.grammar.parse.StroomQlParser;
import stroom.query.language.functions.Expression;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.language.functions.ExpressionParser;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.ParamFactory;
import stroom.query.language.token.StructureBuilder;
import stroom.query.language.token.Tokeniser;
import stroom.security.api.SecurityContext;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;

import jakarta.inject.Provider;
import org.jspecify.annotations.NullMarked;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Maps an {@code AstQuery} (see {@code stroom.query.grammar.ast}) to a {@link SearchRequest}, aiming for exact
 * parity with the legacy {@link SearchRequestFactory} for every construct exercised by the parity corpus (see
 * Task 1.4).
 *
 * <p>One instance is used per {@link #create}/{@link #extractDataSourceOnly} call - like
 * {@code SearchRequestFactory.Builder}, this class holds per-compile mutable state (the field index, param map,
 * eval expression map, etc.) and is not safe to reuse across queries.</p>
 *
 * <p><b>Scope note on error parity</b>: valid queries are matched byte-for-byte (see the differential parity test,
 * Task 1.6). For malformed queries, an exception of a matching or reasonable type/message is thrown - matching
 * legacy's exact {@code TokenException} text (which embeds legacy {@code AbstractToken} internals this class has
 * no equivalent for) is treated as a nice-to-have, not a hard requirement; see the design plan's Task 1.6 "two
 * altitudes of same".</p>
 */
@NullMarked
final class AstToSearchRequestMapper {

    private final VisualisationTokenConsumer visualisationTokenConsumer;
    private final DataSourceResolver dataSourceResolver;
    private final Provider<QueryFieldProvider> queryFieldProviderProvider;
    private final SecurityContext securityContext;

    private ExpressionContext expressionContext;
    private final FieldIndex fieldIndex = new FieldIndex();
    private Map<String, String> paramMap = Collections.emptyMap();
    private final Map<String, Expression> expressionMap = new HashMap<>();
    private final Set<String> addedFields = new HashSet<>();
    private final List<AstToken> additionalFields = new ArrayList<>();
    private boolean inHaving;
    private Optional<CompiledWindow> optionalCompiledWindow = Optional.empty();
    private boolean hasJoins;

    AstToSearchRequestMapper(final VisualisationTokenConsumer visualisationTokenConsumer,
                            final DataSourceResolver dataSourceResolver,
                            final Provider<QueryFieldProvider> queryFieldProviderProvider,
                            final SecurityContext securityContext) {
        this.visualisationTokenConsumer = visualisationTokenConsumer;
        this.dataSourceResolver = dataSourceResolver;
        this.queryFieldProviderProvider = queryFieldProviderProvider;
        this.securityContext = securityContext;
    }

    /**
     * Same contract as {@link QueryCompiler#extractDataSourceOnly}.
     *
     * @param query    must not be null. Only the {@code from} clause's source is resolved; the rest of the query
     *                 is parsed (to find that clause) but not otherwise interpreted.
     * @param consumer must not be null; invoked exactly once, with the resolved, never-null {@link DocRef}.
     * @throws stroom.query.grammar.parse.SyntaxException if {@code query} does not parse.
     * @throws TokenException if {@code query}'s {@code from} clause has joins (not yet enabled - see
     *                        {@link #rejectJoinsIfPresent}) or its datasource name cannot be resolved.
     */
    void extractDataSourceOnly(final String query, final Consumer<DocRef> consumer) {
        final AstQuery ast = StroomQlParser.parse(query);
        rejectJoinsIfPresent(ast.from());
        final DocRef dataSourceRef = securityContext.useAsReadResult(() ->
                dataSourceResolver.resolveDataSourceRef(ast.from().source().unescapedText()));
        consumer.accept(dataSourceRef);
    }

    /**
     * Same contract as {@link QueryCompiler#create}. Not safe to call more than once on the same instance - see
     * this class's Javadoc.
     *
     * @param query             must not be null.
     * @param in                must not be null.
     * @param expressionContext must not be null; {@link ExpressionContext#getDateTimeSettings} must not be
     *                          null either.
     * @return never null.
     * @throws stroom.query.grammar.parse.SyntaxException if {@code query} does not parse.
     * @throws TokenException on any semantic rejection (unresolvable datasource, unsupported clause ordering,
     *                        malformed term/value, joins present, etc.) - see this class's "Scope note on error
     *                        parity".
     */
    SearchRequest create(final String query, final SearchRequest in, final ExpressionContext expressionContext) {
        return create(query, in, expressionContext, false);
    }

    /**
     * Task 6.1x (Phase 6): same contract as {@link
     * #create(String, SearchRequest, ExpressionContext)}, except when {@code allowJoins} is true, a query
     * containing join clauses is not rejected. This method still has no concept of a second datasource, though -
     * {@code Query.dataSource} resolves to the {@code from} clause's own (left) source only, which the caller
     * (see {@code OptimisingQueryCompiler}'s join-handling path) must override afterward, alongside setting
     * {@code Query.joinSpec}. {@code select *} (or a starred select-param) is rejected outright when joins are
     * present ({@link #expandStarredField}'s single-{@code DocRef} field lookup would silently omit the right
     * side's fields) - callers must list fields explicitly in a joined query; not a permanent restriction, just
     * this task's documented scope.
     */
    SearchRequest create(final String query, final SearchRequest in, final ExpressionContext expressionContext,
                        final boolean allowJoins) {
        Objects.requireNonNull(in, "Null sample request");
        Objects.requireNonNull(expressionContext, "Null expression context");
        Objects.requireNonNull(expressionContext.getDateTimeSettings(), "Null date time settings");
        this.expressionContext = expressionContext;

        final AstQuery ast = StroomQlParser.parse(query);
        if (allowJoins) {
            this.hasJoins = !ast.from().joins().isEmpty();
        } else {
            rejectJoinsIfPresent(ast.from());
        }

        final Query.Builder queryBuilder = Query.builder();
        if (in.getQuery() != null) {
            paramMap = ParamUtil.createParamMap(in.getQuery().getParams());
            queryBuilder.params(in.getQuery().getParams());
            queryBuilder.timeRange(in.getQuery().getTimeRange());
        }

        final DocRef dataSourceRef = securityContext.useAsReadResult(() ->
                dataSourceResolver.resolveDataSourceRef(ast.from().source().unescapedText()));
        queryBuilder.dataSource(dataSourceRef);

        final List<ResultRequest> resultRequests = addTableSettings(in.getSearchRequestSource(), ast, queryBuilder);

        Query builtQuery = queryBuilder.build();
        if (builtQuery.getDataSource() == null) {
            throw new TokenException(null, "No data source has been specified.");
        }
        if (builtQuery.getExpression() == null) {
            builtQuery = builtQuery.copy().expression(ExpressionOperator.builder().build()).build();
        }

        return new SearchRequest(
                in.getSearchRequestSource(),
                in.getKey(),
                builtQuery,
                resultRequests,
                in.getDateTimeSettings(),
                in.incremental(),
                in.getTimeout());
    }

    /**
     * Joins are reserved syntax (see {@code StroomQL.g4}'s file header) - parsed, but not runnable before
     * Phase 6.
     */
    private void rejectJoinsIfPresent(final AstFrom from) {
        if (!from.joins().isEmpty()) {
            throw new TokenException(null, "Joins are not yet enabled");
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Clause dispatch (mirrors SearchRequestFactory.Builder#addTableSettings)
    // ------------------------------------------------------------------------------------------------------

    private List<ResultRequest> addTableSettings(final SearchRequestSource searchRequestSource,
                                                 final AstQuery ast,
                                                 final Query.Builder queryBuilder) {
        final List<ResultRequest> resultRequests = new ArrayList<>();

        final Map<String, Sort> sortMap = new HashMap<>();
        final Map<String, Integer> groupMap = new HashMap<>();
        final Map<String, IncludeExcludeFilter> filterMap = new HashMap<>();
        // FROM is handled separately (it's a distinct AstQuery field, not part of ast.clauses) but still
        // participates in the shared TokenType ordering maps, so it must be pre-seeded here.
        final List<TokenType> consumedTokens = new ArrayList<>(List.of(TokenType.FROM));
        final int[] groupDepth = {0};

        final TableSettings.Builder tableSettingsBuilder = TableSettings.builder();
        final TableSettings[] visTableSettings = {null};

        for (final AstClause clause : ast.clauses()) {
            switch (clause) {
                case final AstWhereClause where -> {
                    checkTokenOrder(TokenType.WHERE, consumedTokens);
                    queryBuilder.expression(buildExpression(where.expr()));
                    consumedTokens.add(TokenType.WHERE);
                }
                case final AstEvalClause eval -> {
                    checkTokenOrder(TokenType.EVAL, consumedTokens);
                    processEval(eval);
                    consumedTokens.add(TokenType.EVAL);
                }
                case final AstWindowClause window -> {
                    checkTokenOrder(TokenType.WINDOW, consumedTokens);
                    processWindow(window, tableSettingsBuilder);
                    consumedTokens.add(TokenType.WINDOW);
                }
                case final AstFilterClause filter -> {
                    checkTokenOrder(TokenType.FILTER, consumedTokens);
                    tableSettingsBuilder.valueFilter(buildExpression(filter.expr()));
                    consumedTokens.add(TokenType.FILTER);
                }
                case final AstSortClause sort -> {
                    checkTokenOrder(TokenType.SORT, consumedTokens);
                    processSortBy(sort, sortMap);
                    consumedTokens.add(TokenType.SORT);
                }
                case final AstGroupClause group -> {
                    checkTokenOrder(TokenType.GROUP, consumedTokens);
                    processGroupBy(group, groupMap, groupDepth[0]);
                    groupDepth[0]++;
                    consumedTokens.add(TokenType.GROUP);
                }
                case final AstHavingClause having -> {
                    checkTokenOrder(TokenType.HAVING, consumedTokens);
                    inHaving = true;
                    tableSettingsBuilder.aggregateFilter(buildExpression(having.expr()));
                    inHaving = false;
                    consumedTokens.add(TokenType.HAVING);
                }
                case final AstSelectClause select -> {
                    checkTokenOrder(TokenType.SELECT, consumedTokens);
                    processSelect(select, sortMap, groupMap, filterMap, tableSettingsBuilder, queryBuilder);
                    consumedTokens.add(TokenType.SELECT);
                }
                case final AstLimitClause limit -> {
                    checkTokenOrder(TokenType.LIMIT, consumedTokens);
                    processLimit(limit, tableSettingsBuilder);
                    consumedTokens.add(TokenType.LIMIT);
                }
                case final AstShowClause show -> {
                    checkTokenOrder(TokenType.SHOW, consumedTokens);
                    final TableSettings parentTableSettings = tableSettingsBuilder.build();
                    visTableSettings[0] = visualisationTokenConsumer.processVis(
                            reTokeniseAsKeywordGroup(show), parentTableSettings);
                    consumedTokens.add(TokenType.SHOW);
                }
            }
        }

        // Ensure StreamId and EventId fields exist if there is no grouping.
        if (groupDepth[0] == 0) {
            if (!addedFields.contains(SpecialColumns.RESERVED_STREAM_ID)) {
                tableSettingsBuilder.addColumns(SpecialColumns.RESERVED_STREAM_ID_COLUMN);
                addedFields.add(SpecialColumns.RESERVED_STREAM_ID);
            }
            if (!addedFields.contains(SpecialColumns.RESERVED_EVENT_ID)) {
                tableSettingsBuilder.addColumns(SpecialColumns.RESERVED_EVENT_ID_COLUMN);
                addedFields.add(SpecialColumns.RESERVED_EVENT_ID);
            }
            if (!addedFields.contains(SpecialColumns.RESERVED_ANNOTATION_ID)) {
                tableSettingsBuilder.addColumns(SpecialColumns.RESERVED_ANNOTATION_ID_COLUMN);
                addedFields.add(SpecialColumns.RESERVED_ANNOTATION_ID);
            }
        }

        // Add missing fields if needed (referenced only by `having`).
        for (final AstToken token : additionalFields) {
            final String fieldName = token.unescapedText();
            if (!addedFields.contains(fieldName)) {
                final String id = "__" + fieldName.replaceAll("\\s", "_") + "__";
                // Raw text (not unescaped) - see the AstSelectParam case in processSelect for why.
                tableSettingsBuilder.addColumns(createColumn(
                        fieldName, id, token.rawText(), fieldName, false, true, sortMap, groupMap, filterMap));
            }
        }

        final TableSettings tableSettings = tableSettingsBuilder.extractValues(true).build();

        final ResultRequest tableResultRequest = ResultRequest.builder()
                .componentId(SearchRequestFactory.TABLE_COMPONENT_ID)
                .searchRequestSource(searchRequestSource)
                .mappings(Collections.singletonList(tableSettings))
                .resultStyle(ResultStyle.TABLE)
                .fetch(Fetch.ALL)
                .groupSelection(new GroupSelection())
                .build();
        resultRequests.add(tableResultRequest);

        if (visTableSettings[0] != null) {
            final List<TableSettings> tableSettingsList = new ArrayList<>();
            tableSettingsList.add(tableSettings);
            tableSettingsList.add(visTableSettings[0]);
            final ResultRequest qlVisResultRequest = ResultRequest.builder()
                    .componentId(SearchRequestFactory.VIS_COMPONENT_ID)
                    .mappings(tableSettingsList)
                    .resultStyle(ResultStyle.QL_VIS)
                    .fetch(Fetch.ALL)
                    .groupSelection(new GroupSelection())
                    .build();
            resultRequests.add(qlVisResultRequest);
        }
        return resultRequests;
    }

    private void checkTokenOrder(final TokenType tokenType, final List<TokenType> consumedTokens) {
        for (final TokenType requiredType : TokenType.getKeywordsRequiredBefore(tokenType)) {
            if (!consumedTokens.contains(requiredType)) {
                throw new TokenException(null, "Required token " + requiredType + " before " + tokenType);
            }
        }
        final Set<TokenType> validBefore = TokenType.getKeywordsValidBefore(tokenType);
        for (final TokenType consumedType : consumedTokens) {
            if (!validBefore.contains(consumedType)) {
                throw new TokenException(null, "Unexpected token " + tokenType + " after " + consumedType);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // eval / window
    // ------------------------------------------------------------------------------------------------------

    private void processEval(final AstEvalClause eval) {
        final String variable = eval.name().unescapedText();
        final ExpressionParser expressionParser = new ExpressionParser(new ParamFactory(expressionMap));
        try {
            final Expression expression = expressionParser.parse(expressionContext, fieldIndex,
                    eval.expressionText());
            expressionMap.put(variable, expression);
        } catch (final ParseException e) {
            throw new TokenException(null, e.getMessage());
        }
    }

    private void processWindow(final AstWindowClause window, final TableSettings.Builder builder) {
        final HoppingWindow.Builder hoppingWindowBuilder = HoppingWindow.builder();
        hoppingWindowBuilder.timeField(window.field().unescapedText());
        hoppingWindowBuilder.windowSize(window.windowSize().rawText());
        hoppingWindowBuilder.advanceSize(
                window.advanceSize() != null ? window.advanceSize().rawText() : window.windowSize().rawText());
        if (window.usingFunction() != null) {
            hoppingWindowBuilder.function(window.usingFunction().unescapedText());
        }

        final Window builtWindow = hoppingWindowBuilder.build();
        final CompiledWindow compiledWindow = CompiledWindow.create(builtWindow);
        compiledWindow.addWindowFields(expressionContext, fieldIndex, expressionMap);
        optionalCompiledWindow = Optional.of(compiledWindow);
        builder.window(builtWindow);
    }

    private KeywordGroup reTokeniseAsKeywordGroup(final AstShowClause show) {
        // VisualisationTokenConsumer (existing, unchanged) expects a legacy KeywordGroup; re-tokenising the
        // clause's own text with the legacy Tokeniser/StructureBuilder reuses that code path exactly rather
        // than reimplementing visualisation parsing here - see the design plan's reuse principle.
        final String text = "show as " + show.name().rawText();
        final TokenGroup root = StructureBuilder.create(Tokeniser.parse(text));
        return (KeywordGroup) root.getChildren().getFirst();
    }

    // ------------------------------------------------------------------------------------------------------
    // boolean expression (where / filter / having)
    // ------------------------------------------------------------------------------------------------------

    private ExpressionOperator buildExpression(final AstOrExpr orExpr) {
        final ExpressionOperator operator = foldOr(orExpr);
        final ExpressionOperator simplified = ExpressionUtil.simplify(operator);
        return simplified != null ? simplified : ExpressionOperator.builder().build();
    }

    /** Pairwise, left-associative fold - see {@code AstOrExpr}'s Javadoc for why this must not be a flat n-ary
     *  fold. */
    private ExpressionOperator foldOr(final AstOrExpr orExpr) {
        ExpressionItem previous = foldAnd(orExpr.operands().getFirst());
        for (int i = 1; i < orExpr.operands().size(); i++) {
            final ExpressionItem next = foldAnd(orExpr.operands().get(i));
            previous = ExpressionOperator.builder().op(Op.OR).children(List.of(previous, next)).build();
        }
        return asOperator(previous);
    }

    private ExpressionItem foldAnd(final AstAndExpr andExpr) {
        ExpressionItem previous = foldNot(andExpr.operands().getFirst());
        for (int i = 1; i < andExpr.operands().size(); i++) {
            final ExpressionItem next = foldNot(andExpr.operands().get(i));
            previous = ExpressionOperator.builder().op(Op.AND).children(List.of(previous, next)).build();
        }
        return previous;
    }

    private ExpressionItem foldNot(final AstNotExpr notExpr) {
        if (notExpr.negated()) {
            final ExpressionItem inner = foldNot(notExpr.inner());
            return ExpressionOperator.builder().op(Op.NOT).children(List.of(inner)).build();
        }
        return foldPrimary(notExpr.primary());
    }

    private ExpressionItem foldPrimary(final AstPrimary primary) {
        if (primary.bracketed() != null) {
            return asOperator(foldOr(primary.bracketed()));
        }
        return buildTerm(primary.term());
    }

    private ExpressionOperator asOperator(final ExpressionItem item) {
        if (item instanceof final ExpressionOperator operator) {
            return operator;
        }
        return ExpressionOperator.builder().op(Op.AND).children(List.of(item)).build();
    }

    private ExpressionTerm buildTerm(final AstTerm term) {
        final ExpressionTerm expressionTerm = switch (term) {
            case final AstComparisonTerm t -> ExpressionTerm.builder()
                    .field(t.field().unescapedText())
                    .condition(mapCondition(t.cond()))
                    .value(resolveValue(t.value()))
                    .build();
            case final AstBetweenTerm t -> ExpressionTerm.builder()
                    .field(t.field().unescapedText())
                    .condition(Condition.BETWEEN)
                    .value(resolveValue(t.lower()) + ", " + resolveValue(t.upper()))
                    .build();
            case final AstInTerm t -> ExpressionTerm.builder()
                    .field(t.field().unescapedText())
                    .condition(Condition.IN)
                    .value(t.values().stream().map(this::resolveValue).collect(Collectors.joining(", ")))
                    .build();
            case final AstInDictionaryTerm t -> {
                final String dictionaryName = t.dictionaryName().unescapedText().trim();
                final DocRef dictionaryRef;
                try {
                    dictionaryRef = dataSourceResolver.findDictionaryDoc(dictionaryName);
                } catch (final RuntimeException e) {
                    throw new TokenException(null, e.getMessage());
                }
                yield ExpressionTerm.builder()
                        .field(t.field().unescapedText())
                        .condition(Condition.IN_DICTIONARY)
                        .docRef(dictionaryRef)
                        .build();
            }
            case final AstIsNullTerm t -> ExpressionTerm.builder()
                    .field(t.field().unescapedText())
                    .condition(t.negated() ? Condition.IS_NOT_NULL : Condition.IS_NULL)
                    .build();
        };

        if (inHaving) {
            additionalFields.add(term.field());
        }
        return expressionTerm;
    }

    private Condition mapCondition(final stroom.query.grammar.ast.AstComparisonCond cond) {
        return switch (cond) {
            case EQUALS -> Condition.EQUALS;
            case NOT_EQUALS -> Condition.NOT_EQUALS;
            case GREATER_THAN -> Condition.GREATER_THAN;
            case GREATER_THAN_OR_EQUAL_TO -> Condition.GREATER_THAN_OR_EQUAL_TO;
            case LESS_THAN -> Condition.LESS_THAN;
            case LESS_THAN_OR_EQUAL_TO -> Condition.LESS_THAN_OR_EQUAL_TO;
        };
    }

    /**
     * Reproduces {@code SearchRequestFactory.parseValueTokens} exactly: re-tokenises the value's original source
     * text with the existing legacy {@link Tokeniser}/{@link StructureBuilder} (rather than this grammar's own
     * tokens) so that date-expression/numeric classification and validation - including the
     * {@link DateExpressionParser} call - are byte-identical to legacy, regardless of how this grammar's lexer
     * happened to tokenise the same text (see {@code AstValue}'s Javadoc).
     */
    private String resolveValue(final AstValue value) {
        final List<AbstractToken> tokens = StructureBuilder
                .create(Tokeniser.parse(value.sourceText()))
                .getChildren();
        return parseValueTokens(tokens);
    }

    private String parseValueTokens(final List<AbstractToken> tokens) {
        if (tokens.isEmpty()) {
            return "";
        }

        boolean dateExpression = false;
        boolean numericExpression = false;
        final StringBuilder sb = new StringBuilder();
        for (final AbstractToken token : tokens) {
            if (TokenType.FUNCTION_GROUP.equals(token.getTokenType())) {
                final String function = token.getUnescapedText();
                if (function.equalsIgnoreCase("param(")) {
                    // Legacy special-cases a bare `param(...)` value token: see resolveParam - not currently
                    // exercised by the parity corpus, so only the common (non-param) path is fully implemented.
                    throw new TokenException(null, "param() as a bare value is not yet supported");
                }
                boolean found = false;
                for (final DatePoint datePoint : DatePoint.values()) {
                    if (datePoint.getFunction().equals(function)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new TokenException(null, "Unexpected function in value");
                }
                dateExpression = true;
            } else if (TokenType.DURATION.equals(token.getTokenType())) {
                dateExpression = true;
            } else if (TokenType.NUMBER.equals(token.getTokenType())) {
                numericExpression = true;
            }
            sb.append(token.getUnescapedText());
        }

        final String expression = sb.toString();
        if (dateExpression) {
            DateExpressionParser.parse(tokens, expressionContext.getDateTimeSettings());
        } else if (numericExpression) {
            boolean seenSign = false;
            boolean seenNumber = false;
            for (final AbstractToken token : tokens) {
                if (TokenType.PLUS.equals(token.getTokenType()) || TokenType.MINUS.equals(token.getTokenType())) {
                    if (seenSign || seenNumber) {
                        throw new TokenException(null, "Unexpected token");
                    }
                    seenSign = true;
                } else if (TokenType.NUMBER.equals(token.getTokenType())) {
                    if (seenNumber) {
                        throw new TokenException(null, "Unexpected token");
                    }
                    seenNumber = true;
                } else {
                    throw new TokenException(null, "Unexpected token");
                }
            }
        } else if (tokens.size() > 1) {
            throw new TokenException(null, "Unexpected token");
        }
        return expression;
    }

    // ------------------------------------------------------------------------------------------------------
    // select
    // ------------------------------------------------------------------------------------------------------

    private void processSelect(final AstSelectClause select,
                               final Map<String, Sort> sortMap,
                               final Map<String, Integer> groupMap,
                               final Map<String, IncludeExcludeFilter> filterMap,
                               final TableSettings.Builder tableSettingsBuilder,
                               final Query.Builder queryBuilder) {
        final Map<String, AtomicInteger> columnCount = new HashMap<>();
        final List<Column> columns = new ArrayList<>();

        for (final AstSelectItem item : select.items()) {
            switch (item) {
                case final AstSelectStar star -> expandStarredField(
                        sortMap, groupMap, filterMap, queryBuilder, columnCount, columns, "*");
                case final AstSelectFunction function -> {
                    final String columnName = function.alias() != null
                            ? function.alias().unescapedText()
                            : function.expressionText();
                    final Expression fieldExpression = parseExpression(function.expressionText());
                    final String columnId = createColumnId(columnCount, columnName);
                    columns.add(createColumn(columnId, fieldExpression, columnName, sortMap, groupMap, filterMap));
                }
                case final AstSelectParam param -> {
                    final String fieldName = param.field().unescapedText();
                    final String columnName = param.alias() != null ? param.alias().unescapedText() : fieldName;
                    if (columnName.equals("*") || fieldName.contains("*")) {
                        expandStarredField(sortMap, groupMap, filterMap, queryBuilder, columnCount, columns,
                                fieldName);
                    } else {
                        final String columnId = createColumnId(columnCount, columnName);
                        // The RAW text (with its `${ }` wrapper) is what must be handed to ExpressionParser -
                        // its own tokeniser needs to see that wrapper to recognise this as a field reference,
                        // not two barewords ("Stream" "Id") with nothing joining them.
                        columns.add(createColumn(fieldName, columnId, param.field().rawText(), columnName, true,
                                false, sortMap, groupMap, filterMap));
                    }
                }
                case final AstSelectField field -> {
                    final String fieldName = field.field().unescapedText();
                    final String columnName = field.alias() != null ? field.alias().unescapedText() : fieldName;
                    if (columnName.equals("*")) {
                        expandStarredField(
                                sortMap, groupMap, filterMap, queryBuilder, columnCount, columns, fieldName);
                    } else {
                        final String columnId = createColumnId(columnCount, columnName);
                        // Raw text preserves quotes for a quoted field name - see the AstSelectParam case above
                        // for why raw (not unescaped) text must reach ExpressionParser.
                        columns.add(createColumn(fieldName, columnId, field.field().rawText(), columnName, true,
                                false, sortMap, groupMap, filterMap));
                    }
                }
            }
        }

        final List<Column> modifiedColumns = optionalCompiledWindow
                .map(compiledWindow -> compiledWindow.addPeriodColumns(columns, expressionMap))
                .orElse(columns);
        tableSettingsBuilder.addColumns(modifiedColumns);
    }

    private Expression parseExpression(final String expressionText) {
        final ExpressionParser expressionParser = new ExpressionParser(new ParamFactory(expressionMap));
        try {
            return expressionParser.parse(expressionContext, fieldIndex, expressionText);
        } catch (final ParseException e) {
            throw new TokenException(null, e.getMessage());
        }
    }

    private void expandStarredField(final Map<String, Sort> sortMap,
                                    final Map<String, Integer> groupMap,
                                    final Map<String, IncludeExcludeFilter> filterMap,
                                    final Query.Builder queryBuilder,
                                    final Map<String, AtomicInteger> columnCount,
                                    final List<Column> columns,
                                    final String fieldNameFilter) {
        if (hasJoins) {
            throw new TokenException(null,
                    "'select *' (or a starred select-param) is not supported for join queries - "
                    + "list fields explicitly.");
        }
        final DocRef dataSource = queryBuilder.build().getDataSource();
        if (dataSource == null) {
            return;
        }
        String filter = fieldNameFilter;
        if (filter.equals("*")) {
            filter = null;
        } else if (filter.contains("*")) {
            filter = filter.replaceAll("\\*", ".*");
            filter = filter.replaceAll("\\?", ".?");
            filter = "/" + filter;
        }

        final FindFieldCriteria criteria = new FindFieldCriteria(
                PageRequest.createDefault(), FindFieldCriteria.DEFAULT_SORT_LIST, dataSource, filter, null);
        final ResultPage<QueryField> resultPage = queryFieldProviderProvider.get().findFields(criteria);
        for (final QueryField field : resultPage.getValues()) {
            final String fieldName = field.getFldName();
            final String columnId = createColumnId(columnCount, fieldName);
            columns.add(createColumn(fieldName, columnId, fieldName, fieldName, true, false,
                    sortMap, groupMap, filterMap));
        }
    }

    private String createColumnId(final Map<String, AtomicInteger> map, final String name) {
        final String cleanName = name
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "_")
                .replaceAll("_+", "_");
        final int id = map.computeIfAbsent(cleanName, k -> new AtomicInteger()).incrementAndGet();
        return cleanName + "-" + id;
    }

    private Column createColumn(final String fieldName,
                                final String id,
                                final String expressionSourceText,
                                final String columnName,
                                final boolean visible,
                                final boolean special,
                                final Map<String, Sort> sortMap,
                                final Map<String, Integer> groupMap,
                                final Map<String, IncludeExcludeFilter> filterMap) {
        addedFields.add(fieldName);
        Expression expression = expressionMap.get(fieldName);
        if (expression == null) {
            expression = parseExpression(expressionSourceText);
        }
        return Column.builder()
                .id(id)
                .name(columnName)
                .expression(expression.toString())
                .sort(sortMap.get(fieldName))
                .group(groupMap.get(fieldName))
                .filter(filterMap.get(fieldName))
                .visible(visible)
                .special(special)
                .build();
    }

    private Column createColumn(final String id,
                                final Expression expression,
                                final String columnName,
                                final Map<String, Sort> sortMap,
                                final Map<String, Integer> groupMap,
                                final Map<String, IncludeExcludeFilter> filterMap) {
        addedFields.add(columnName);
        return Column.builder()
                .id(id)
                .name(columnName)
                .expression(expression.toString())
                .sort(sortMap.get(columnName))
                .group(groupMap.get(columnName))
                .filter(filterMap.get(columnName))
                .visible(true)
                .special(false)
                .build();
    }

    // ------------------------------------------------------------------------------------------------------
    // limit / sort / group
    // ------------------------------------------------------------------------------------------------------

    private void processLimit(final AstLimitClause limit, final TableSettings.Builder tableSettingsBuilder) {
        for (final AstToken value : limit.values()) {
            try {
                tableSettingsBuilder.addMaxResults(Long.parseLong(value.unescapedText()));
            } catch (final NumberFormatException e) {
                throw new TokenException(null, "Syntax exception, expected number");
            }
        }
    }

    private void processSortBy(final AstSortClause sort, final Map<String, Sort> sortMap) {
        for (final AstSortItem item : sort.items()) {
            final String fieldName = item.field().unescapedText();
            final SortDirection direction = resolveSortDirection(item.direction());
            sortMap.put(fieldName, new Sort(sortMap.size(), direction));
        }
    }

    private SortDirection resolveSortDirection(final AstToken directionToken) {
        if (directionToken == null) {
            return SortDirection.ASCENDING;
        }
        final String text = directionToken.unescapedText();
        if (text.equalsIgnoreCase("asc")) {
            return SortDirection.ASCENDING;
        } else if (text.equalsIgnoreCase("desc")) {
            return SortDirection.DESCENDING;
        }
        try {
            return SortDirection.valueOf(text);
        } catch (final IllegalArgumentException e) {
            throw new TokenException(null, "Syntax exception, expected sort direction 'asc' or 'desc'");
        }
    }

    private void processGroupBy(final AstGroupClause group,
                               final Map<String, Integer> groupMap,
                               final int groupDepth) {
        for (final AstToken field : group.fields()) {
            groupMap.put(field.unescapedText(), groupDepth);
        }
    }
}
