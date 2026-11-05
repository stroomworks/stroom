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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TestEmailNormaliser {

    private final EmailNormaliser normaliser = new EmailNormaliser();

    @Test
    void testDomainIsLowerCasedAndLocalPartIsNot() {
        assertThat(normaliser.normalise("  Alerts@EXAMPLE.Org "))
                .hasValueSatisfying(n -> assertThat(n.text()).isEqualTo("Alerts@example.org"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Name <a@example.org>",
            "a@example.org, b@example.org",
            "a@example.org;b@example.org",
            "a@b@example.org",
            "@example.org",
            "a@",
            "a@example",
            "a@.example.org",
            "a b@example.org",
            ""
    })
    void testRejects(final String raw) {
        assertThat(normaliser.normalise(raw)).isEmpty();
    }
}
