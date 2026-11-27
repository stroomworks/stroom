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

import stroom.learningflow.stage.Deferrals.Deferral;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that a stream that has waited longer than the document's maximum deferral age is flagged as raised.
///
/// A raised stream goes on waiting; the flag only draws attention to it. It is computed from the stream's age
/// when the row is read, not stored.
class TestDeferralRaised {

    @Test
    void raisedOncePastTheMaximumAgeAndNotBefore() {
        final Deferral row = new Deferral(new Learner("pipeline", "learningFlow"), "doc", 1L, "F", "T", null,
                "waiting", 1, 1_000L, 1);
        assertThat(row.raised(500L, 1_400L)).isFalse();
        assertThat(row.raised(500L, 1_500L)).describedAs("at the age exactly, not yet").isFalse();
        assertThat(row.raised(500L, 1_501L)).isTrue();
    }
}
