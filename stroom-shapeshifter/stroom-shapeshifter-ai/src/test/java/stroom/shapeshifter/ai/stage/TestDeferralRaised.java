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

import stroom.shapeshifter.ai.stage.Deferrals.Deferral;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// A57, A78, A79: a stream that has waited past the document's maximum deferral age is raised, and still
/// waits; the flag is computed as the row is read.
class TestDeferralRaised {

    @Test
    void raisedOncePastTheMaximumAgeAndNotBefore() {
        final Deferral row = new Deferral(new Learner("pipeline", "shapeshifterAi"), "doc", 1L, "F", "T", null,
                "waiting", 1, 1_000L, 1);
        assertThat(row.raised(500L, 1_400L)).isFalse();
        assertThat(row.raised(500L, 1_500L)).describedAs("at the age exactly, not yet").isFalse();
        assertThat(row.raised(500L, 1_501L)).isTrue();
    }
}
