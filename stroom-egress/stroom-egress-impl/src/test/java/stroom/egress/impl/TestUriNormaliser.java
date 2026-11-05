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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestUriNormaliser {

    private final UriNormaliser normaliser = new UriNormaliser(Set.of("http", "https"));

    @ParameterizedTest
    @CsvSource({
            "https://SIEM.Example.ORG/api/,                https://siem.example.org/api",
            "https://siem.example.org./api,                https://siem.example.org/api",
            "https://siem.example.org:443/api,             https://siem.example.org/api",
            "http://siem.example.org:80/api,               http://siem.example.org/api",
            "https://siem.example.org:8443/api,            https://siem.example.org:8443/api",
            "HTTPS://siem.example.org,                     https://siem.example.org/",
            "https://siem.example.org/a/./b/../c,          https://siem.example.org/a/c",
            "https://siem.example.org//a///b,              https://siem.example.org/a/b",
            "https://siem.example.org/a%20b,               https://siem.example.org/a%20b",
            "https://siem.example.org/%61pi,               https://siem.example.org/api",
            "https://siem.example.org/api?token=secret,    https://siem.example.org/api",
            "https://siem.example.org/api#frag,            https://siem.example.org/api",
            "https://bücher.example/api,                   https://xn--bcher-kva.example/api"
    })
    void testNormalises(final String raw, final String expected) {
        assertThat(normaliser.normalise(raw.trim()))
                .hasValueSatisfying(n -> assertThat(n.text()).isEqualTo(expected.trim()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:pw@siem.example.org/api",
            "https://siem.example.org\\api",
            "https://siem.example.org/a;jsessionid=1",
            "https://siem.example.org/a%2Fb",
            "https://siem.example.org/a%2fb",
            "https://siem.example.org/a%5Cb",
            "https://siem.example.org/a%00b",
            "https://siem.example.org/a%252Fb",
            "https://siem.example.org/a%2",
            "https://siem.example.org/../etc",
            "https://siem.example.org:0/api",
            "https://siem.example.org:99999/api",
            "https://siem.example.org:abc/api",
            "https:///api",
            "ftp://siem.example.org/api",
            "siem.example.org/api",
            "https://siem example.org/api",
            "",
            "   "
    })
    void testRejects(final String raw) {
        assertThat(normaliser.normalise(raw)).isEmpty();
    }

    @Test
    void testNull() {
        assertThat(normaliser.normalise(null)).isEmpty();
    }

    @Test
    void testHttpsOnPort80IsNotTheDefault() {
        assertThat(normaliser.normalise("https://siem.example.org:80/api"))
                .hasValueSatisfying(n -> assertThat(n.text()).isEqualTo("https://siem.example.org:80/api"));
        assertThat(normaliser.normalise("http://siem.example.org:443/api"))
                .hasValueSatisfying(n -> assertThat(n.text()).isEqualTo("http://siem.example.org:443/api"));
    }

    @Test
    void testComponentsAreSchemeAuthorityThenSegments() {
        assertThat(normaliser.normalise("https://siem.example.org:8443/api/v1").orElseThrow().components())
                .containsExactly("https://siem.example.org:8443", "api", "v1");
    }

    @Test
    void testIpv6() {
        assertThat(normaliser.normalise("https://[::1]:8443/x"))
                .hasValueSatisfying(n -> assertThat(n.text()).isEqualTo("https://[::1]:8443/x"));
    }

    @Test
    void testQueryIsNeverKept() {
        assertThat(normaliser.normalise("https://hooks.example.org/T000/B000?token=abc").orElseThrow().text())
                .doesNotContain("token")
                .doesNotContain("?");
    }
}
