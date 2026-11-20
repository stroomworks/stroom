/*
 * Copyright 2026 Crown Copyright
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

package stroom.shapeshifter.ai.stage;

import stroom.shapeshifter.ai.state.InMemorySpend;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// What stops a runaway costing money without limit (ruling A24, the policy over A44's counter).
///
/// An attempt's own budgets bound one session. Nothing bounded a feed that keeps opening them — a
/// learner that cannot be taught, a format that changes every hour, a model that charges and fails.
class TestSpendBreaker {

    private static final String DOC = "doc-1";
    private static final String OTHER = "doc-2";

    private final InMemorySpend spend = new InMemorySpend();

    @Test
    void nothingSpentPassesEveryCeiling() {
        assertThat(new SpendBreaker(spend, 1_000L, 10).tripped(doc(null, null))).isEmpty();
    }

    @Test
    void theClusterCeilingStopsANewSession() {
        spend.record(DOC, 1_000L, 1, SpendBreaker.WINDOW_MS);

        assertThat(new SpendBreaker(spend, 1_000L, 0).tripped(doc(null, null)))
                .hasValueSatisfying(why -> assertThat(why)
                        .contains("Smart Pipelines on this cluster spent 1000 token(s)")
                        .contains("ceiling of 1000")
                        .contains("until the hour rolls"));
    }

    @Test
    void oneDocumentCannotSpendAnothersAllowance() {
        // A per-document ceiling cannot bound a cluster: a hundred documents each inside their own are a
        // hundred times it, which is why both ceilings exist
        spend.record(OTHER, 900L, 1, SpendBreaker.WINDOW_MS);
        spend.record(DOC, 200L, 1, SpendBreaker.WINDOW_MS);

        assertThat(new SpendBreaker(spend, 1_000L, 0).tripped(doc(5_000L, null)))
                .describedAs("its own ceiling is nowhere near, but the cluster's is met")
                .isPresent();
    }

    @Test
    void aLearningFlowIsToldAboutItsOwnCeilingRatherThanTheClusters() {
        // A feed spending its own allowance should be told that, not told the cluster is busy
        spend.record(DOC, 500L, 1, SpendBreaker.WINDOW_MS);

        assertThat(new SpendBreaker(spend, 1_000_000L, 0).tripped(doc(500L, null)))
                .hasValueSatisfying(why -> assertThat(why).startsWith("This Learning Flow spent 500 token(s)"));
    }

    @Test
    void theRateLimitIsCountedInQuestionsNotTokens() {
        // For a model priced or throttled by the call rather than by the token
        spend.record(DOC, 1L, 12, SpendBreaker.WINDOW_MS);

        assertThat(new SpendBreaker(spend, 0L, 12).tripped(doc(null, null)))
                .hasValueSatisfying(why -> assertThat(why).contains("12 question(s)"));
        assertThat(new SpendBreaker(spend, 0L, 13).tripped(doc(null, null)))
                .describedAs("one below the ceiling still passes")
                .isEmpty();
    }

    @Test
    void aLearningFlowsOwnCallCeilingIsItsOwn() {
        spend.record(DOC, 1L, 4, SpendBreaker.WINDOW_MS);

        assertThat(new SpendBreaker(spend, 0L, 0).tripped(doc(null, 4)))
                .hasValueSatisfying(why -> assertThat(why)
                        .startsWith("This Learning Flow spent 4 question(s)"));
    }

    @Test
    void zeroIsNoCeilingAtAll() {
        // Which leaves unattended learning with no upper bound on what it can spend, so the configuration
        // says so in as many words
        spend.record(DOC, 9_999_999L, 9_999, SpendBreaker.WINDOW_MS);

        assertThat(new SpendBreaker(spend, 0L, 0).tripped(doc(null, null))).isEmpty();
        assertThat(new SpendBreaker(spend, 0L, 0).tripped(doc(0L, 0))).isEmpty();
    }

    /// @param tokens The Learning Flow's token ceiling, or null for none of its own.
    /// @param calls  Its call ceiling, or null for none of its own.
    /// @return The document.
    private static ShapeshifterAiDoc doc(final Long tokens, final Integer calls) {
        return ShapeshifterAiDoc.builder()
                .uuid(DOC)
                .name("door-access")
                .spendTokensPerHour(tokens)
                .spendCallsPerHour(calls)
                .build();
    }
}
