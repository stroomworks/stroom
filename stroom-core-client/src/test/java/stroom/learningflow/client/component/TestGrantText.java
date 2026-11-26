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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.SupervisorAttempt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the text the grant dialog shows a person before they grant more budget to a session: what has been
/// spent and granted against the document's budgets, and that the time budget applies to each run separately
/// while tokens are counted over the session's life.
class TestGrantText {

    @Test
    void itSaysWhatWasSpentAndGrantedAgainstTheBudgets() {
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("doc-1")
                .tokenBudget(30_000L)
                .attemptBudgetMs(600_000L)
                .build();

        assertThat(GrantText.soFar(attempt(40_000L, 10_000L, 90_000L), doc))
                .isEqualTo("It has spent 40000 tokens of its Learning Flow's 30000, with 10000 more granted. Each run "
                           + "of it may take 10 min by its Learning Flow and 90 s more granted. Tokens are counted "
                           + "over the session's life; time over each run, so a run carried on has the whole of "
                           + "it again.");
    }

    @Test
    void aDocumentNotYetReadOrWithoutATokenBudgetIsSaidSo() {
        assertThat(GrantText.soFar(attempt(5L, 0L, 0L), null))
                .startsWith("It has spent 5 tokens. Each run of it may take its Learning Flow's time budget.");
        final LearningFlowDoc unbounded = LearningFlowDoc.builder().uuid("doc-1").tokenBudget(0L).build();
        assertThat(GrantText.soFar(attempt(5L, 0L, 0L), unbounded))
                .startsWith("It has spent 5 tokens, and its Learning Flow sets no token budget.");
    }

    @Test
    void aDurationIsWholeMinutesOrSeconds() {
        assertThat(GrantText.duration(300_000L)).isEqualTo("5 min");
        assertThat(GrantText.duration(90_000L)).isEqualTo("90 s");
    }

    private static SupervisorAttempt attempt(final long spent, final long grantedTokens, final long grantedMs) {
        return new SupervisorAttempt(1L, null, null, "FEED", "Raw Events", 1L, "node-1", PromotionMode.AUTOMATIC,
                AttemptStatus.PARKED, "Parked: budget spent", null, null, spent, grantedTokens, grantedMs, false,
                0L, 0L, List.of(), false);
    }
}
