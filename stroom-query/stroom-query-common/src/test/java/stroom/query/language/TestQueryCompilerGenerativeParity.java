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
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.language.functions.ExpressionContext;
import stroom.security.mock.MockSecurityContext;
import stroom.util.json.JsonUtil;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Fuzzes beyond {@code TestQueryCompilerParity}'s ~30 hand-picked queries (see
 * .7): {@link StroomQlGenerator} produces random-but-valid
 * StroomQL, both compilers compile each one, and the results must match exactly - the same
 * byte-for-byte bar as the hand corpus, since every generated query is constructed to be valid (unlike the hand
 * corpus, which deliberately includes malformed queries too) - with one deliberate, documented exception (see
 * {@link #BRACKET_ADJACENT_LOGICAL_KEYWORD}).
 *
 * <p>Runs with a FIXED seed so failures are 100% reproducible from this file alone with no external state.
 * {@link #REGRESSION_SEEDS} is where any seed that ever turns up a mismatch is pinned permanently, so it keeps
 * being exercised even after the main seed or generator changes - see the design plan's "loop-until-dry" /
 * regression-fixture guidance for generative tests.</p>
 */
class TestQueryCompilerGenerativeParity {

    private static final long MAIN_SEED = 42L;
    private static final int ITERATIONS = 200;

    /**
     * Matches the one shape this generator produces that legacy rejects due to a confirmed legacy bug (not a
     * deliberate rule) that this rewrite does NOT reproduce and
     * {@code TestLegacyBugFixes} for the dedicated, explicit demonstration of this divergence. Legacy's
     * regex-based tokeniser only tags "and"/"or"/"not" as keywords when immediately preceded by whitespace,
     * start-of-input, or ')' - never '(' with no space - so `(not <term>)` is rejected by legacy but correctly
     * accepted here.
     */
    private static final Pattern BRACKET_ADJACENT_LOGICAL_KEYWORD =
            Pattern.compile("\\((?:not|and|or)\\b", Pattern.CASE_INSENSITIVE);

    /** Seeds that have previously produced a mismatch, pinned here so they are always re-checked. Empty at the
     *  time of writing - Task 1.7's 200-iteration run with {@link #MAIN_SEED} found none. */
    private static final long[] REGRESSION_SEEDS = {};

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

    private String compile(final QueryCompiler compiler, final String query) {
        try {
            final List<ResultRequest> resultRequests = new ArrayList<>(0);
            final QueryKey queryKey = new QueryKey("test");
            final Query seedQuery = Query.builder().build();
            final DateTimeSettings dateTimeSettings = DateTimeSettings.builder().referenceTime(0L).build();
            SearchRequest searchRequest = new SearchRequest(
                    null, queryKey, seedQuery, resultRequests, dateTimeSettings, false);
            final ExpressionContext expressionContext = ExpressionContext.builder()
                    .dateTimeSettings(dateTimeSettings)
                    .maxStringLength(100)
                    .build();
            searchRequest = compiler.create(query, searchRequest, expressionContext);
            return JsonUtil.writeValueAsConsistentString(searchRequest);
        } catch (final RuntimeException e) {
            return "ERROR: " + e;
        }
    }

    @Test
    void generatedQueriesMatchAcrossBothCompilers() {
        final QueryCompiler legacy = legacy();
        final QueryCompiler optimising = optimising();

        SoftAssertions.assertSoftly(softly -> {
            for (final long seed : allSeeds()) {
                final StroomQlGenerator generator = new StroomQlGenerator(new Random(seed));
                for (int i = 0; i < ITERATIONS; i++) {
                    final String query = generator.generate();
                    final String legacyResult = compile(legacy, query);
                    final String optimisingResult = compile(optimising, query);
                    if (BRACKET_ADJACENT_LOGICAL_KEYWORD.matcher(query).find()) {
                        // Known, one-directional divergence (see BRACKET_ADJACENT_LOGICAL_KEYWORD's Javadoc):
                        // assert the specific asymmetry rather than skipping the pair entirely, so a future
                        // change that makes EITHER side stop behaving as expected here still fails loudly.
                        softly.assertThat(legacyResult)
                                .as("seed=%d, iteration=%d: %s (legacy is expected to still reject this - its "
                                        + "bracket-adjacency bug is not something a passing generator run should "
                                        + "depend on having disappeared)", seed, i, query)
                                .startsWith("ERROR: ");
                        softly.assertThat(optimisingResult)
                                .as("seed=%d, iteration=%d: %s (optimising must NOT reproduce legacy's "
                                        + "bracket-adjacency bug)", seed, i, query)
                                .doesNotStartWith("ERROR: ");
                        continue;
                    }
                    softly.assertThat(optimisingResult)
                            .as("seed=%d, iteration=%d: %s", seed, i, query)
                            .isEqualTo(legacyResult);
                }
            }
        });
    }

    private long[] allSeeds() {
        final long[] seeds = new long[REGRESSION_SEEDS.length + 1];
        seeds[0] = MAIN_SEED;
        System.arraycopy(REGRESSION_SEEDS, 0, seeds, 1, REGRESSION_SEEDS.length);
        return seeds;
    }
}
