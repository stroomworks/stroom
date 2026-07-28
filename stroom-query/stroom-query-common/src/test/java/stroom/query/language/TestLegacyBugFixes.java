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

import stroom.query.api.DateTimeSettings;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.language.functions.ExpressionContext;
import stroom.security.mock.MockSecurityContext;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demonstrates the two confirmed bugs in legacy's {@code SearchRequestFactory}/{@code Tokeniser} that the new
 * grammar-based compiler deliberately does <b>not</b> reproduce
 * for the full write-up (root cause, evidence, and rationale for classifying each as a bug rather than a
 * deliberate rule). {@link TestQueryCompilerParity} and {@link TestQueryCompilerGenerativeParity} both carve out
 * these exact shapes; this class is where the resulting behaviour is pinned down explicitly, one bug at a time,
 * so the difference is a first-class, intentional test case rather than an exception buried in a comparison
 * test's exclusion list.
 */
class TestLegacyBugFixes {

    private QueryCompiler legacy() {
        return new LegacyQueryCompiler(new SearchRequestFactory(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
                MockSecurityContext.getInstance()));
    }

    private QueryCompiler optimising() {
        return new OptimisingQueryCompiler(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
                MockSecurityContext.getInstance(),
                EmptyFieldInfoSource.INSTANCE,
                (feedName, from, to) -> Optional.empty(),
                (indexName, from, to) -> Optional.empty(),
                storeName -> Optional.empty());
    }

    private SearchRequest compile(final QueryCompiler compiler, final String query) {
        final List<ResultRequest> resultRequests = new ArrayList<>(0);
        final QueryKey queryKey = new QueryKey("test");
        final Query seedQuery = Query.builder().build();
        final DateTimeSettings dateTimeSettings = DateTimeSettings.builder().referenceTime(0L).build();
        final SearchRequest seedRequest = new SearchRequest(
                null, queryKey, seedQuery, resultRequests, dateTimeSettings, false);
        final ExpressionContext expressionContext = ExpressionContext.builder()
                .dateTimeSettings(dateTimeSettings)
                .maxStringLength(100)
                .build();
        return compiler.create(query, seedRequest, expressionContext);
    }

    @Test
    void bracketAdjacentNot_legacyRejectsButOptimisingAccepts() {
        final String query = "from \"index_view\" where (not StreamId = 1) select StreamId";

        assertThatThrownBy(() -> compile(legacy(), query))
                .as("legacy's regex-based tokeniser fails to tag \"not\" as a keyword when immediately adjacent "
                        + "to '(' with no space - see Tokeniser.tagKeyword's \"preceded by\" alternation, which "
                        + "has no '(' case")
                .isInstanceOf(RuntimeException.class);

        final SearchRequest result = compile(optimising(), query);
        assertThat(result.getQuery().getExpression()).isEqualTo(
                ExpressionOperator.builder()
                        .op(Op.NOT)
                        .children(List.of(ExpressionTerm.builder()
                                .field("StreamId")
                                .condition(Condition.EQUALS)
                                .value("1")
                                .build()))
                        .build());
    }

    @Test
    void bracketAdjacentNot_withSpaceWorksOnBothSides() {
        // Control case: legacy DOES accept this once there's a space after '(' - confirming the divergence
        // above is specifically about bracket-adjacency, not "not" inside brackets in general.
        final String query = "from \"index_view\" where ( not StreamId = 1) select StreamId";

        final SearchRequest legacyResult = compile(legacy(), query);
        final SearchRequest optimisingResult = compile(optimising(), query);

        assertThat(optimisingResult.getQuery().getExpression())
                .isEqualTo(legacyResult.getQuery().getExpression());
    }

    @Test
    void isNull_legacyRejectsButOptimisingAccepts() {
        final String query = "from \"index_view\" where StreamId is null select StreamId";

        assertThatThrownBy(() -> compile(legacy(), query))
                .as("legacy's tokeniser emits an IS_NULL token for \"is null\" but SearchRequestFactory.createTerm's"
                        + " 3-token minimum check rejects the resulting 2-token term with \"Incomplete term\" - "
                        + "the feature was never finished, not deliberately excluded")
                .isInstanceOf(RuntimeException.class);

        final SearchRequest result = compile(optimising(), query);
        assertThat(result.getQuery().getExpression()).isEqualTo(
                ExpressionOperator.builder()
                        .children(List.of(ExpressionTerm.builder()
                                .field("StreamId")
                                .condition(Condition.IS_NULL)
                                .build()))
                        .build());
    }

    @Test
    void isNotNull_legacyRejectsButOptimisingAccepts() {
        final String query = "from \"index_view\" where StreamId is not null select StreamId";

        assertThatThrownBy(() -> compile(legacy(), query))
                .isInstanceOf(RuntimeException.class);

        final SearchRequest result = compile(optimising(), query);
        assertThat(result.getQuery().getExpression()).isEqualTo(
                ExpressionOperator.builder()
                        .children(List.of(ExpressionTerm.builder()
                                .field("StreamId")
                                .condition(Condition.IS_NOT_NULL)
                                .build()))
                        .build());
    }
}
