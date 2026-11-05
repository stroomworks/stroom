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

import stroom.egress.api.EgressRule;
import stroom.egress.impl.EgressMatcher.CompiledRule;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestEgressMatcher {

    private static CompiledRule rule(final EgressKind kind,
                                     final String destination,
                                     final EgressMatch match,
                                     final boolean enabled) {
        final EgressRule rule = new EgressRule(1L, kind, destination, match, "test", enabled);
        return new CompiledRule(rule, EgressNormalisers.normalise(kind, destination).orElseThrow());
    }

    private static boolean matches(final EgressKind kind, final String destination, final CompiledRule rule) {
        return EgressMatcher.match(kind, EgressNormalisers.normalise(kind, destination).orElseThrow(),
                List.of(rule)).isPresent();
    }

    @Test
    void testExactMatchesOnlyEqual() {
        final CompiledRule rule = rule(EgressKind.HTTP, "https://hooks.example.org/stroom", EgressMatch.EXACT, true);
        assertThat(matches(EgressKind.HTTP, "https://HOOKS.example.org:443/stroom/", rule)).isTrue();
        assertThat(matches(EgressKind.HTTP, "https://hooks.example.org/stroom/more", rule)).isFalse();
    }

    @Test
    void testPrefixMatchesWholeComponents() {
        final CompiledRule rule = rule(EgressKind.HTTP, "https://siem.example.org:8443/api/", EgressMatch.PREFIX, true);
        assertThat(matches(EgressKind.HTTP, "https://siem.example.org:8443/api/v1/events", rule)).isTrue();
        assertThat(matches(EgressKind.HTTP, "https://siem.example.org:8443/api", rule)).isTrue();
        assertThat(matches(EgressKind.HTTP, "https://siem.example.org:8443/apiary", rule)).isFalse();
        assertThat(matches(EgressKind.HTTP, "https://siem.example.org:9000/api", rule)).isFalse();
        assertThat(matches(EgressKind.HTTP, "http://siem.example.org:8443/api", rule)).isFalse();
    }

    @Test
    void testLookAlikeHostIsNotAPrefix() {
        final CompiledRule rule = rule(EgressKind.HTTP, "https://siem.example.org/", EgressMatch.PREFIX, true);
        assertThat(matches(EgressKind.HTTP, "https://siem.example.org.evil.example/x", rule)).isFalse();
    }

    @Test
    void testLookAlikeDirectoryIsNotAPrefix() {
        final CompiledRule rule = rule(EgressKind.FILE, "/stroom/output", EgressMatch.PREFIX, true);
        assertThat(matches(EgressKind.FILE, "/stroom/output/2026/a.dat", rule)).isTrue();
        assertThat(matches(EgressKind.FILE, "/stroom/output-old/a.dat", rule)).isFalse();
    }

    @Test
    void testDisabledRuleNeverMatches() {
        final CompiledRule rule = rule(EgressKind.HTTP, "https://siem.example.org/", EgressMatch.PREFIX, false);
        assertThat(matches(EgressKind.HTTP, "https://siem.example.org/x", rule)).isFalse();
    }

    @Test
    void testEmailRuleCannotBePrefix() {
        assertThatThrownBy(() -> new EgressRule(
                1L, EgressKind.EMAIL, "a@example.org", EgressMatch.PREFIX, "x", true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
