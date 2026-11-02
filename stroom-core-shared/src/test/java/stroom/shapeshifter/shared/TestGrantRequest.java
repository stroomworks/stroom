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

package stroom.shapeshifter.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestGrantRequest {

    @Test
    void aGrantAddsTokensMinutesOrBoth() {
        assertThat(GrantRequest.ofMinutes(10_000L, 0L).problem()).isNull();
        assertThat(GrantRequest.ofMinutes(0L, 5L).getMs()).isEqualTo(300_000L);
        assertThat(GrantRequest.ofMinutes(0L, 5L).problem()).isNull();
        assertThat(GrantRequest.ofMinutes(1L, 1L).problem()).isNull();
    }

    @Test
    void nothingOrLessIsNoGrant() {
        assertThat(GrantRequest.ofMinutes(0L, 0L).problem()).contains("more tokens, more minutes");
        assertThat(GrantRequest.ofMinutes(-1L, 5L).problem()).contains("cannot take anything away");
        assertThat(GrantRequest.ofMinutes(5L, -1L).problem()).contains("cannot take anything away");
    }
}
