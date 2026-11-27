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

package stroom.learningflow.stage;

import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.stage.Attempts.Attempt;
import stroom.learningflow.stage.Attempts.Recorded;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the reason recorded for a stream waiting on the attempt that holds its learner's claim.
///
/// The reason is worded according to what that attempt is doing, and directs a person to the tab where they can
/// act on it.
class TestStage {

    @Test
    void aStreamBehindAPausedRunWaitsForAPersonOnTheLearningStatusTab() {
        final String reason = Sessions.waitingFor(Optional.of(recorded(AttemptStatus.PARKED,
                "No record boundary after 5 candidates.")));
        assertThat(reason)
                .startsWith("Waiting for learning run 14, which is paused for a person "
                            + "(no record boundary after 5 candidates)")
                .contains("pipeline's Learning Status tab")
                .doesNotContain("Feeds tab")
                .doesNotContain("is learning");
    }

    @Test
    void aStreamBehindADraftWaitsForApprovalOnTheLearningStatusTab() {
        final String reason = Sessions.waitingFor(Optional.of(recorded(AttemptStatus.AWAITING_REVIEW, null)));
        assertThat(reason)
                .contains("approved or rejected on the pipeline's Learning Status tab")
                .doesNotContain("Feeds tab");
    }

    @Test
    void aStreamBehindARunWaitingForTheModelSaysSo() {
        assertThat(Sessions.waitingFor(Optional.of(recorded(AttemptStatus.AWAITING_MODEL, null))))
                .isEqualTo("Waiting for learning run 14, which is waiting for the model to answer.");
    }

    @Test
    void aStreamBehindARunInProgressNamesItsNode() {
        assertThat(Sessions.waitingFor(Optional.of(recorded(AttemptStatus.IN_PROGRESS, null))))
                .isEqualTo("Waiting for learning run 14, which is learning on node node-a; this stream takes "
                           + "what it learns.");
    }

    @Test
    void aClaimReleasedBeforeItWasReadIsTriedAgain() {
        assertThat(Sessions.waitingFor(Optional.empty())).contains("is tried again");
    }

    private static Recorded recorded(final AttemptStatus status, final String decision) {
        final Attempt attempt = new Attempt(new Learner("pipeline", "learningFlow"), "doc", "FEED", "Raw Events",
                1L, "node-a", null, 0L);
        return new Recorded(14L, attempt, status, decision, null, null, 0L, 0L, 0L, List.of());
    }
}
