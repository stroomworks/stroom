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
import stroom.query.api.ExplainPlan;
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.language.functions.ExpressionContext;
import stroom.security.mock.MockSecurityContext;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Proves that {@link LegacyQueryCompiler} is behaviourally indistinguishable from calling the
 * {@link SearchRequestFactory} it wraps directly - both for a successful compile and for a query the factory
 * rejects.
 */
class TestLegacyQueryCompiler {

    private static final String SAMPLE_QUERY = "from \"MY-INDEX\" select StreamId, EventId";

    private SearchRequestFactory newSearchRequestFactory() {
        return new SearchRequestFactory(
                (keywordGroup, parentTableSettings) -> null,
                MockDataSourceResolver.getInstance(),
                () -> criteria -> null,
                MockSecurityContext.getInstance());
    }

    private SearchRequest newSeedRequest() {
        final List<ResultRequest> resultRequests = new ArrayList<>(0);
        final QueryKey queryKey = new QueryKey("test");
        final Query query = Query.builder().build();
        final DateTimeSettings dateTimeSettings = DateTimeSettings.builder().referenceTime(0L).build();
        return new SearchRequest(null, queryKey, query, resultRequests, dateTimeSettings, false);
    }

    private ExpressionContext newExpressionContext(final DateTimeSettings dateTimeSettings) {
        return ExpressionContext.builder().dateTimeSettings(dateTimeSettings).maxStringLength(100).build();
    }

    @Test
    void create_delegatesToSearchRequestFactory() {
        final SearchRequest seed = newSeedRequest();
        final ExpressionContext expressionContext = newExpressionContext(seed.getDateTimeSettings());

        final SearchRequest expected = newSearchRequestFactory().create(SAMPLE_QUERY, seed, expressionContext);
        final SearchRequest actual = new LegacyQueryCompiler(newSearchRequestFactory())
                .create(SAMPLE_QUERY, seed, expressionContext);

        assertThat(JsonUtil.writeValueAsConsistentString(actual))
                .isEqualTo(JsonUtil.writeValueAsConsistentString(expected));
    }

    @Test
    void create_rejectsQueryTheLegacyFactoryRejects() {
        final SearchRequest seed = newSeedRequest();
        final ExpressionContext expressionContext = newExpressionContext(seed.getDateTimeSettings());
        final String badQuery = "select * limit 10"; // no `from`; limit after select - legacy rejects this

        final QueryCompiler compiler = new LegacyQueryCompiler(newSearchRequestFactory());

        assertThatThrownBy(() -> newSearchRequestFactory().create(badQuery, seed, expressionContext))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> compiler.create(badQuery, seed, expressionContext))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void extractDataSourceOnly_delegatesToSearchRequestFactory() {
        final AtomicReference<DocRef> expected = new AtomicReference<>();
        final AtomicReference<DocRef> actual = new AtomicReference<>();

        newSearchRequestFactory().extractDataSourceOnly(SAMPLE_QUERY, expected::set);
        new LegacyQueryCompiler(newSearchRequestFactory()).extractDataSourceOnly(SAMPLE_QUERY, actual::set);

        assertThat(actual.get()).isEqualTo(expected.get());
    }

    @Test
    void explain_degradesGracefully_datasourceNameOnlyNoCostEstimate() {
        final SearchRequest seed = newSeedRequest();
        final ExpressionContext expressionContext = newExpressionContext(seed.getDateTimeSettings());

        final ExplainPlan plan = new LegacyQueryCompiler(newSearchRequestFactory())
                .explain(SAMPLE_QUERY, expressionContext);

        assertThat(plan.getDescription()).contains("MY-INDEX").contains("legacy engine");
        assertThat(plan.getEstimatedRows()).isNull();
        assertThat(plan.getEstimatedDurationMs()).isNull();
        assertThat(plan.getConfidence()).isNull();
        assertThat(plan.getChildren()).isEmpty();
    }

    @Test
    void explain_whenNoDatasourceResolves_usesQuestionMarkPlaceholder() {
        // If extractDataSourceOnly never yields a DocRef (here a no-op mock factory never calls the consumer),
        // explain() must fall back to a "?" datasource name rather than NPE on a null DocRef.
        final SearchRequestFactory factory = mock(SearchRequestFactory.class);
        final ExpressionContext expressionContext =
                newExpressionContext(DateTimeSettings.builder().referenceTime(0L).build());

        final ExplainPlan plan = new LegacyQueryCompiler(factory).explain(SAMPLE_QUERY, expressionContext);

        assertThat(plan.getDescription()).contains("Scan ?").contains("legacy engine");
    }

    @Test
    void constructor_rejectsNullSearchRequestFactory() {
        assertThatThrownBy(() -> new LegacyQueryCompiler(null))
                .isInstanceOf(NullPointerException.class);
    }
}
