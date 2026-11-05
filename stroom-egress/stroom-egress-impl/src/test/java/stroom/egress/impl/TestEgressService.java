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

import stroom.egress.api.EgressGuard;
import stroom.egress.api.EgressRequest;
import stroom.egress.shared.EgressAttemptDto;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;
import stroom.egress.shared.EgressRuleDto;
import stroom.egress.shared.EgressSettingsDto;
import stroom.egress.shared.EgressTestRequest;
import stroom.egress.shared.EgressTestResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestEgressService {

    private InMemoryEgressRegistry registry;
    private AggregatingDecisionRecorder recorder;
    private EgressService service;
    private EgressGuard guard;

    @BeforeEach
    void setUp() {
        registry = new InMemoryEgressRegistry();
        recorder = new AggregatingDecisionRecorder();
        service = new EgressService(registry, registry, recorder);
        guard = new EgressGuardImpl(registry, registry, recorder);
    }

    private static EgressRuleDto rule(final Long id,
                                      final EgressKind kind,
                                      final String destination,
                                      final EgressMatch match,
                                      final String comment) {
        return new EgressRuleDto(id, kind, destination, match, comment, true);
    }

    @Test
    void testSaveNormalisesAndNeverChangesSilently() {
        final EgressRuleDto saved = service.saveRule(rule(
                null, EgressKind.HTTP, "HTTPS://SIEM.Example.org:443/api/?token=x", EgressMatch.PREFIX, "SIEM"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getDestination()).isEqualTo("https://siem.example.org/api");
        assertThatThrownBy(() -> service.saveRule(rule(
                null, EgressKind.HTTP, "https://user:pw@siem.example.org/", EgressMatch.EXACT, "bad")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid");
        assertThat(service.listRules()).hasSize(1);
    }

    @Test
    void testCommentIsRequired() {
        assertThatThrownBy(() -> service.saveRule(rule(
                null, EgressKind.HTTP, "https://siem.example.org/", EgressMatch.EXACT, "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment");
    }

    @Test
    void testEmailCannotBePrefix() {
        assertThatThrownBy(() -> service.saveRule(rule(
                null, EgressKind.EMAIL, "a@example.org", EgressMatch.PREFIX, "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly");
    }

    @Test
    void testUpdateKeepsTheIdAndDeleteRemovesTheRule() {
        final EgressRuleDto saved = service.saveRule(rule(
                null, EgressKind.FILE, "/stroom/output", EgressMatch.PREFIX, "out"));
        final EgressRuleDto updated = service.saveRule(rule(
                saved.getId(), EgressKind.FILE, "/stroom/output", EgressMatch.EXACT, "narrowed"));
        assertThat(updated.getId()).isEqualTo(saved.getId());
        assertThat(service.listRules()).singleElement()
                .satisfies(r -> assertThat(r.getMatch()).isEqualTo(EgressMatch.EXACT));
        assertThat(service.deleteRule(saved.getId())).isTrue();
        assertThat(service.deleteRule(saved.getId())).isFalse();
        assertThat(service.listRules()).isEmpty();
    }

    @Test
    void testRuleChangesReachTheGuard() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        final EgressRequest request = new EgressRequest(EgressKind.HTTP, "https://siem.example.org/api/x", "t");
        assertThat(guard.check(request).isAllowed()).isFalse();
        final EgressRuleDto saved = service.saveRule(rule(
                null, EgressKind.HTTP, "https://siem.example.org/api", EgressMatch.PREFIX, "SIEM"));
        assertThat(guard.check(request).isAllowed()).isTrue();
        service.deleteRule(saved.getId());
        assertThat(guard.check(request).isAllowed()).isFalse();
    }

    @Test
    void testSettingsRoundTripAndTakeEffect() {
        service.saveSettings(new EgressSettingsDto(
                Map.of(EgressKind.HTTP, EgressMode.ENFORCE),
                Map.of(EgressKind.EMAIL, true)));
        final EgressSettingsDto settings = service.getSettings();
        assertThat(settings.getModes().get(EgressKind.HTTP)).isEqualTo(EgressMode.ENFORCE);
        assertThat(settings.getModes().get(EgressKind.FILE)).isEqualTo(EgressMode.REPORT_ONLY);
        assertThat(settings.getKillSwitches().get(EgressKind.EMAIL)).isTrue();
        assertThat(guard.check(new EgressRequest(EgressKind.EMAIL, "a@example.org", "t")).isAllowed()).isFalse();
    }

    @Test
    void testTheTestBoxSaysWhichRuleMatches() {
        service.saveSettings(new EgressSettingsDto(Map.of(EgressKind.HTTP, EgressMode.ENFORCE), Map.of()));
        final EgressRuleDto saved = service.saveRule(rule(
                null, EgressKind.HTTP, "https://siem.example.org:8443/api/", EgressMatch.PREFIX, "SIEM"));
        final EgressTestResponse allowed = service.test(new EgressTestRequest(
                EgressKind.HTTP, "https://SIEM.example.org:8443/api/v1"));
        assertThat(allowed.getOutcome()).isEqualTo("ALLOWED");
        assertThat(allowed.getRuleId()).isEqualTo(saved.getId());
        final EgressTestResponse denied = service.test(new EgressTestRequest(
                EgressKind.HTTP, "https://other.example.org/x"));
        assertThat(denied.getOutcome()).isEqualTo("DENIED");
        assertThat(denied.getRuleId()).isNull();
        assertThat(service.test(new EgressTestRequest(EgressKind.HTTP, "ftp://x/")).getNormalised()).isNull();
    }

    @Test
    void testAttemptsAreMaskedRevealableAndClearable() {
        guard.check(new EgressRequest(EgressKind.HTTP, "https://hooks.example.org/T000/secret", "http-call"));
        final List<EgressAttemptDto> attempts = service.listAttempts();
        assertThat(attempts).singleElement().satisfies(attempt -> {
            assertThat(attempt.getDestination()).isEqualTo("https://hooks.example.org/T000/****");
            assertThat(attempt.getOutcome()).isEqualTo("WOULD_DENY");
            assertThat(service.revealAttempt(attempt.getId()))
                    .isEqualTo("https://hooks.example.org/T000/secret");
        });
        assertThat(service.revealAttempt("nope")).isNull();
        assertThat(service.clearAttempts(0L)).isZero();
        assertThat(service.clearAttempts(null)).isEqualTo(1);
        assertThat(service.listAttempts()).isEmpty();
    }
}
