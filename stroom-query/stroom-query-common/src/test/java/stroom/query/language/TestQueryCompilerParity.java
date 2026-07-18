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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Task 1.6 hard merge gate: {@link LegacyQueryCompiler} (wrapping {@link SearchRequestFactory}) and
 * {@link OptimisingQueryCompiler} must produce the same {@link SearchRequest} - compared as
 * {@link JsonUtil#writeValueAsConsistentString(Object)} for readable diffs - over the whole existing
 * {@code TestSearchRequestFactory} corpus.
 *
 * <p><b>For every valid query in the corpus, output must be byte-identical - no exceptions.</b> A small,
 * explicitly enumerated set of the corpus's deliberately malformed queries ({@link #KNOWN_ERROR_TEXT_DEVIATIONS})
 * is instead checked more loosely (both sides must reject the query; the exact exception text is not compared) -
 * this is the design plan's "improvements corpus with a rationale comment" (Task 1.6), inlined here rather than
 * split into a separate file since the corpus itself is shared. Every entry name a genuine reason, verified by
 * hand against both compilers' actual output - see each comment.</p>
 *
 * <p>Reports every mismatch in one run (via {@link SoftAssertions}), not just the first.</p>
 */
class TestQueryCompilerParity {

    private static final Pattern PART_DELIMITER = Pattern.compile("(?:^|\n)-----[^\n]*\n?");

    /**
     * 1-based corpus query index -> why an exact exception-text match is not required (both sides still must
     * throw). Legacy's {@code TokenException} embeds legacy {@code AbstractToken} start/end offsets and
     * occasionally token text this grammar has no equivalent representation for - see
     * {@code AstToSearchRequestMapper}'s class Javadoc ("Scope note on error parity").
     */
    private static final Map<Integer, String> KNOWN_ERROR_TEXT_DEVIATIONS = Map.ofEntries(
            Map.entry(12, "typo'd 'ans' + bare '*' value - both reject, different token-level diagnostics"),
            Map.entry(13, "malformed trailing quote - both reject, different token-level diagnostics"),
            Map.entry(14, "incomplete `eval` (no variable) - legacy rejects semantically, this grammar "
                          + "rejects syntactically (SyntaxException) - arguably an earlier, clearer rejection"),
            Map.entry(15, "incomplete `eval` (no `=`) - same reason as #14"),
            Map.entry(16, "incomplete `eval` (no expression) - same reason as #14"),
            Map.entry(17, "unknown function 'uCase' - SAME message from the shared ExpressionParser on both "
                          + "sides; only the embedded token offset differs, because this grammar hands "
                          + "ExpressionParser an extracted substring (offsets relative to it), while legacy "
                          + "hands it tokens already offset into the whole original query"),
            Map.entry(25, "trailing garbage after a duration ('2dx') - same root cause as #17: identical "
                          + "message, offset relative to the extracted value substring rather than the whole "
                          + "query"));

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

    private List<String> readCorpus() throws Exception {
        final Path inFile = Paths.get(
                "../stroom-query-common/src/test/resources/TestSearchRequestFactory/in.txt");
        final String in = Files.readString(inFile);
        final Matcher matcher = PART_DELIMITER.matcher(in);

        final List<String> inputs = new ArrayList<>();
        int end = 0;
        while (matcher.find(end)) {
            final String substring = in.substring(end, matcher.start());
            if (!substring.isEmpty()) {
                inputs.add(substring);
            }
            end = matcher.end();
        }
        final String substring = in.substring(end);
        if (!substring.isEmpty()) {
            inputs.add(substring);
        }
        return inputs;
    }

    /**
     * @return either the compiled {@link SearchRequest}'s consistent JSON, or (if compilation threw) the
     *         exception's {@code toString()} prefixed with {@code "ERROR: "} so the two cases are never
     *         confusable when compared as plain strings.
     */
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
    void optimisingCompilerMatchesLegacyAcrossTheCorpus() throws Exception {
        final List<String> queries = readCorpus();
        final QueryCompiler legacy = legacy();

        SoftAssertions.assertSoftly(softly -> {
            for (int i = 0; i < queries.size(); i++) {
                final int queryNumber = i + 1;
                final String query = queries.get(i);
                final String legacyResult = compile(legacy, query);
                final String optimisingResult = compile(optimising(), query);

                final String deviationReason = KNOWN_ERROR_TEXT_DEVIATIONS.get(queryNumber);
                if (deviationReason == null) {
                    softly.assertThat(optimisingResult)
                            .as("query #%d: %s", queryNumber, query)
                            .isEqualTo(legacyResult);
                } else {
                    // A known, reasoned deviation (see KNOWN_ERROR_TEXT_DEVIATIONS): both sides must still
                    // reject the query, just not necessarily with the same exception text.
                    softly.assertThat(legacyResult)
                            .as("query #%d should be rejected by the legacy compiler too (%s): %s",
                                    queryNumber, deviationReason, query)
                            .startsWith("ERROR: ");
                    softly.assertThat(optimisingResult)
                            .as("query #%d: %s (%s)", queryNumber, query, deviationReason)
                            .startsWith("ERROR: ");
                }
            }
        });
    }
}
