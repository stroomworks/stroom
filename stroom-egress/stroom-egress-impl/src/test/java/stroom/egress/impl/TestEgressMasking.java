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

package stroom.egress.impl;

import stroom.egress.shared.EgressKind;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestEgressMasking {

    @Test
    void testLastSegmentOfAWebhookIsMasked() {
        assertThat(EgressMasking.mask(EgressKind.HTTP, "https://hooks.example.org/services/T000/B000"))
                .isEqualTo("https://hooks.example.org/services/T000/****");
    }

    @Test
    void testASingleSegmentIsMasked() {
        assertThat(EgressMasking.mask(EgressKind.HTTP, "https://hooks.example.org/token123"))
                .isEqualTo("https://hooks.example.org/****");
    }

    @Test
    void testAHostOnlyIsUnchanged() {
        assertThat(EgressMasking.mask(EgressKind.HTTP, "https://siem.example.org/"))
                .isEqualTo("https://siem.example.org/");
    }

    @Test
    void testFilesAndEmailAreUnchanged() {
        assertThat(EgressMasking.mask(EgressKind.FILE, "/stroom/output/a.dat")).isEqualTo("/stroom/output/a.dat");
        assertThat(EgressMasking.mask(EgressKind.EMAIL, "a@example.org")).isEqualTo("a@example.org");
    }

    @Test
    void testNullIsUnchanged() {
        assertThat(EgressMasking.mask(EgressKind.HTTP, null)).isNull();
    }
}
