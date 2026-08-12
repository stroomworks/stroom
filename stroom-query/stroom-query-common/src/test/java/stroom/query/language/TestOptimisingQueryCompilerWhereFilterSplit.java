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
import stroom.query.api.DateTimeSettings;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.port.FieldInfoSource;
import stroom.security.mock.MockSecurityContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5.3: proves {@link OptimisingQueryCompiler#create} routes the index-ineligible remainder of a bare {@code
 * where} clause to extraction-time filtering ({@code TableSettings.valueFilter}) instead of leaving it in the
 * scan-time {@code Query.expression}, Phase 5. The legacy divergence this fixes is a deliberate, documented one.
 *
 * <p><b>The Task 8.1 executed-predicate gate</b> lives here too (see
 * {@link #assertExecutedPredicateMatchesLegacy}): for a corpus of queries that <i>do</i> trigger the split -
 * exactly the queries request-level parity ({@code TestQueryCompilerParity}) stops looking at, because a
 * rewritten request is legitimately expected to differ - the conjuncts the optimised request executes
 * ({@code Query.expression} plus {@code valueFilter}, combined) must be <b>exactly</b> the conjuncts the legacy
 * compiler executes: same fields, same conditions, same <i>values</i> (unescaped, not raw source text) and same
 * dictionary {@code DocRef}s. This is a compile-time proxy for row-level parity, not a row comparison - no
 * datasource executes in this module's unit tests - but term-for-term equality of an AND-only predicate is
 * semantic equality of what will be evaluated, modulo where each conjunct is evaluated (index vs extraction),
 * which is the split's one sanctioned difference.</p>
 */
class TestOptimisingQueryCompilerWhereFilterSplit {

    // StreamId is queryable with the default numeric ConditionSet - eligible for pushdown.
    private static final QueryField STREAM_ID = QueryField.builder()
            .fldName("StreamId").fldType(FieldType.LONG).queryable(true).build();
    // The NonIndexed* fields are NOT queryable - AutoWhereFilterSplitRule treats them as ineligible and moves
    // them to filter. One per value shape the Task 8.1 gate exercises (TEXT/LONG/DATE ConditionSets differ).
    private static final QueryField NON_INDEXED_FIELD = QueryField.builder()
            .fldName("NonIndexedField").fldType(FieldType.TEXT).queryable(false).build();
    private static final QueryField NON_INDEXED_NUM = QueryField.builder()
            .fldName("NonIndexedNum").fldType(FieldType.LONG).queryable(false).build();
    private static final QueryField NON_INDEXED_TIME = QueryField.builder()
            .fldName("NonIndexedTime").fldType(FieldType.DATE).queryable(false).build();

    private static final FieldInfoSource FIELD_INFO_SOURCE = new FieldInfoSource() {
        @Override
        public List<QueryField> getFields(final String dataSourceName) {
            return "Events".equals(dataSourceName)
                    ? List.of(STREAM_ID, NON_INDEXED_FIELD, NON_INDEXED_NUM, NON_INDEXED_TIME)
                    : List.of();
        }

        @Override
        public Optional<QueryField> getTimeField(final String dataSourceName) {
            return Optional.empty();
        }
    };

    private OptimisingQueryCompiler compiler() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
                MockSecurityContext.getInstance(),
                FIELD_INFO_SOURCE,
                (feedName, from, to) -> Optional.empty(),
                (indexName, from, to) -> Optional.empty(),
                storeName -> Optional.empty());
    }

    private QueryCompiler legacy() {
        return new LegacyQueryCompiler(new SearchRequestFactory(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
                MockSecurityContext.getInstance()));
    }

    private ExpressionContext expressionContext() {
        return ExpressionContext.builder()
                .dateTimeSettings(DateTimeSettings.builder().referenceTime(0L).build())
                .maxStringLength(100)
                .build();
    }

    private static SearchRequest emptySeedRequest() {
        return new SearchRequest(null, null, null, null, null, false, null);
    }

    /**
     * The gate's seed, mirroring {@code TestQueryCompilerParity}'s: the legacy token-based
     * {@link SearchRequestFactory} needs a non-null seed {@link Query} and date-time settings.
     */
    private static SearchRequest gateSeedRequest() {
        return new SearchRequest(
                null,
                new QueryKey("test"),
                Query.builder().build(),
                new ArrayList<>(0),
                DateTimeSettings.builder().referenceTime(0L).build(),
                false);
    }

    @Test
    void mixedEligibility_movesIneligibleTermToValueFilter() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 and NonIndexedField = 'x' select StreamId",
                emptySeedRequest(),
                expressionContext());

        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
        assertThat(result.getQuery().getExpression().toString()).doesNotContain("NonIndexedField");

        final TableSettings tableSettings = tableSettingsOf(result);
        assertThat(tableSettings.getValueFilter()).isNotNull();
        assertThat(tableSettings.getValueFilter().toString()).contains("NonIndexedField");
    }

    @Test
    void allTermsEligible_leavesExpressionAndValueFilterUnchanged() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 select StreamId",
                emptySeedRequest(),
                expressionContext());

        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
        assertThat(tableSettingsOf(result).getValueFilter()).isNull();
    }

    @Test
    void explicitFilterClauseAlready_isUntouched() {
        final SearchRequest result = compiler().create(
                "from \"Events\" where StreamId = 1 filter NonIndexedField = 'x' select StreamId",
                emptySeedRequest(),
                expressionContext());

        // AutoWhereFilterSplitRule is a documented no-op when the query already has its own filter clause -
        // valueFilter is exactly what the user wrote, unmodified by this task's override.
        assertThat(result.getQuery().getExpression().toString()).contains("StreamId");
        assertThat(tableSettingsOf(result).getValueFilter()).isNotNull();
        assertThat(tableSettingsOf(result).getValueFilter().toString()).contains("NonIndexedField");
    }

    // ------------------------------------------------------------------------------------------------------
    // Task 8.1 gate - executed values, not just field names. Each test uses one eligible term (so the split
    // genuinely fires) plus ineligible term(s) carrying the value shape under test, then asserts the moved
    // term's value is the legacy-resolved one AND that the full executed conjunct set equals legacy's.
    // ------------------------------------------------------------------------------------------------------

    @Test
    void gate_quotedStringValue_executesUnquoted() {
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and NonIndexedField = 'admin' select StreamId");

        // The defect Task 8.1 closes: the Binder's raw source text would execute as 'admin' WITH the quotes,
        // silently matching nothing. The executed value must be the legacy-unescaped one.
        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactly(
                ExpressionTerm.builder()
                        .field("NonIndexedField").condition(Condition.EQUALS).value("admin").build());
    }

    @Test
    void gate_numericValue_survivesUnchanged() {
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and NonIndexedNum = 42 select StreamId");

        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactly(
                ExpressionTerm.builder()
                        .field("NonIndexedNum").condition(Condition.EQUALS).value("42").build());
    }

    @Test
    void gate_dateValue_survivesUnchanged() {
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and NonIndexedTime >= 2016-01-02T03:04:05.000Z "
                + "select StreamId");

        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactly(
                ExpressionTerm.builder()
                        .field("NonIndexedTime")
                        .condition(Condition.GREATER_THAN_OR_EQUAL_TO)
                        .value("2016-01-02T03:04:05.000Z")
                        .build());
    }

    @Test
    void gate_betweenValues_executeUnquoted() {
        // Quoted bounds so the legacy-resolved value ("bound, bound", quotes stripped) provably differs from
        // the Binder's raw re-join of source text ("'bound', 'bound'").
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and NonIndexedTime between "
                + "'2016-01-02T00:00:00.000Z' and '2016-01-03T00:00:00.000Z' select StreamId");

        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactly(
                ExpressionTerm.builder()
                        .field("NonIndexedTime")
                        .condition(Condition.BETWEEN)
                        .value("2016-01-02T00:00:00.000Z, 2016-01-03T00:00:00.000Z")
                        .build());
    }

    @Test
    void gate_inValues_executeUnquoted() {
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and NonIndexedField in ('alpha', 'beta') select StreamId");

        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactly(
                ExpressionTerm.builder()
                        .field("NonIndexedField").condition(Condition.IN).value("alpha, beta").build());
    }

    @Test
    void gate_twoDifferentDictionaries_bothSurviveWithTheirResolvedDocRefs() {
        // Task 8.2's two failure modes, observed at the executed request: the terms must not collapse to one
        // (two different dictionaries are two different predicates) and each must carry the DocRef the legacy
        // compiler resolved - a docRef-less IN_DICTIONARY term throws "Dictionary not set for field" at search
        // time, or filters on nothing in the value filter.
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and NonIndexedField in dictionary \"dict_a\" "
                + "and NonIndexedField in dictionary \"dict_b\" select StreamId");

        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactlyInAnyOrder(
                ExpressionTerm.builder()
                        .field("NonIndexedField")
                        .condition(Condition.IN_DICTIONARY)
                        .docRef(DocRef.builder().type("Dictionary").uuid("dict_a").name("dict_a").build())
                        .build(),
                ExpressionTerm.builder()
                        .field("NonIndexedField")
                        .condition(Condition.IN_DICTIONARY)
                        .docRef(DocRef.builder().type("Dictionary").uuid("dict_b").name("dict_b").build())
                        .build());
    }

    @Test
    void gate_threePlusConjuncts_eligibleTermsStayInTheIndexExpression() {
        // Both compilers fold `a and b and c` into nested pairwise ANDs. The split must classify per term (the
        // nested AND is flattened - a semantic identity), not treat the nested AND(a,b) as one opaque,
        // never-eligible unit that dumps every eligible term into extraction-time filtering.
        final SearchRequest on = assertExecutedPredicateMatchesLegacy(
                "from \"Events\" where StreamId = 1 and StreamId = 2 and NonIndexedField = 'x' select StreamId");

        assertThat(conjunctsOf(on.getQuery().getExpression())).containsExactly(
                ExpressionTerm.builder().field("StreamId").condition(Condition.EQUALS).value("1").build(),
                ExpressionTerm.builder().field("StreamId").condition(Condition.EQUALS).value("2").build());
        assertThat(conjunctsOf(tableSettingsOf(on).getValueFilter())).containsExactly(
                ExpressionTerm.builder()
                        .field("NonIndexedField").condition(Condition.EQUALS).value("x").build());
    }

    /**
     * The Task 8.1 gate: compiles {@code query} with the legacy compiler (mode {@code OFF}) and the optimising
     * compiler (mode {@code ON}), asserts the split actually fired (a gate that never engages the dangerous
     * path proves nothing), then asserts {@code ON}'s executed conjuncts - {@code Query.expression} and the
     * table's {@code valueFilter}, flattened through enabled {@code AND}s and combined - are exactly
     * {@code OFF}'s, compared by full {@link ExpressionTerm} equality (field, condition, <b>value</b>, docRef).
     *
     * <p>For an AND-only predicate this is semantic equality of what will be evaluated; what it deliberately
     * does <i>not</i> pin is tree shape (a split restructures by design) or <i>where</i> each conjunct is
     * evaluated - relocating an ineligible conjunct from the index expression to extraction-time filtering is
     * the split's one sanctioned, documented divergence from legacy.</p>
     *
     * @return the optimised ({@code ON}) request, for follow-up value assertions.
     */
    private SearchRequest assertExecutedPredicateMatchesLegacy(final String query) {
        final SearchRequest off = legacy().create(query, gateSeedRequest(), expressionContext());
        final SearchRequest on = compiler().create(query, gateSeedRequest(), expressionContext());

        final ExpressionOperator valueFilter = tableSettingsOf(on).getValueFilter();
        assertThat(valueFilter)
                .as("the where/filter split must actually fire for: %s", query)
                .isNotNull();

        final List<ExpressionItem> offConjuncts = conjunctsOf(off.getQuery().getExpression());
        final List<ExpressionItem> onExecuted = new ArrayList<>(conjunctsOf(on.getQuery().getExpression()));
        onExecuted.addAll(conjunctsOf(valueFilter));

        assertThat(onExecuted)
                .as("executed conjuncts (Query.expression + valueFilter) must be exactly the legacy-compiled "
                    + "conjuncts - same fields, conditions, values and docRefs - for: %s", query)
                .containsExactlyInAnyOrderElementsOf(offConjuncts);
        return on;
    }

    /**
     * Flattens {@code item} through enabled {@code AND} operators into its conjunct leaves - the semantic
     * content of an AND-only predicate, independent of the (pairwise-nested vs flat) tree shape each compiler
     * happens to build. An empty {@code AND} contributes nothing; anything that isn't an enabled {@code AND}
     * (a term, or an {@code OR}/{@code NOT}/disabled sub-tree) is one conjunct, compared whole.
     */
    private static List<ExpressionItem> conjunctsOf(final ExpressionItem item) {
        final List<ExpressionItem> out = new ArrayList<>();
        collectConjuncts(item, out);
        return out;
    }

    private static void collectConjuncts(final ExpressionItem item, final List<ExpressionItem> out) {
        if (item instanceof final ExpressionOperator operator
            && operator.enabled()
            && (operator.getOp() == null || Op.AND.equals(operator.getOp()))) {
            if (operator.getChildren() != null) {
                for (final ExpressionItem child : operator.getChildren()) {
                    collectConjuncts(child, out);
                }
            }
        } else {
            out.add(item);
        }
    }

    private static TableSettings tableSettingsOf(final SearchRequest searchRequest) {
        final ResultRequest tableResultRequest = searchRequest.getResultRequests().stream()
                .filter(rr -> SearchRequestFactory.TABLE_COMPONENT_ID.equals(rr.getComponentId()))
                .findFirst()
                .orElseThrow();
        return tableResultRequest.getMappings().getFirst();
    }
}
