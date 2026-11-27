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

import stroom.docref.DocRef;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckResult;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.SupervisorAttempt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestEvidence {

    @Test
    void theGatesComeFirstThenTheAdviceEachSayingWhetherItWasMet() {
        final List<CheckResult> results = List.of(
                new CheckResult(Check.TIMES, CheckState.ADVICE, 0.5, 1.0),
                new CheckResult(Check.COMPILE, CheckState.GATE, 1.0, 1.0),
                new CheckResult(Check.QUALITY, CheckState.ADVICE, 0.8, 0.7),
                new CheckResult(Check.CONFORMANCE, CheckState.GATE, 0.9, 1.0));

        assertThat(Evidence.lines(results)).containsExactly(
                "Compiles: 100%, a gate it passed",
                "Valid against the target schema: 90%, a gate it fell short of, needing 100%",
                "Event times are plausible: 50%, advice it fell short of, which asks for 100%",
                "Events carry real content: 80%, advice it met");
    }

    @Test
    void noResultsSayNothing() {
        assertThat(Evidence.lines(null)).isEmpty();
        assertThat(Evidence.lines(List.of())).isEmpty();
    }

    @Test
    void whoDecidedIsSaidWithWhen() {
        assertThat(Evidence.reviewed(run(AttemptStatus.PROMOTED, "jo", 1_000L), ms -> "at " + ms))
                .isEqualTo("Approved by jo, at 1000");
        assertThat(Evidence.reviewed(run(AttemptStatus.REJECTED, "ann", null), ms -> "at " + ms))
                .isEqualTo("Rejected by ann");
        assertThat(Evidence.reviewed(run(AttemptStatus.AWAITING_REVIEW, null, null), ms -> "at " + ms))
                .isNull();
        assertThat(Evidence.reviewed(null, ms -> "at " + ms)).isNull();
    }

    private static SupervisorAttempt run(final AttemptStatus status, final String by, final Long when) {
        return new SupervisorAttempt(7L, new DocRef("LearningFlow", "doc-1"),
                new LearnerRef("p-1", "learningFlow"), "FIREWALL", "Raw Events", 1L, "node1a",
                PromotionMode.REVIEW, status, null, "b-1", 0.9, 0L, 0L, 0L, false, 0L, 0L, List.of(), false,
                null, List.of(), by, when);
    }
}
