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

import stroom.egress.api.EgressContext;
import stroom.egress.api.EgressDecision;
import stroom.egress.api.EgressDecision.Outcome;
import stroom.egress.api.EgressDeniedException;
import stroom.egress.api.EgressRequest;
import stroom.egress.api.EgressRule;
import stroom.egress.api.EgressRuleSource;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestEgressGuardImpl {

    private InMemoryEgressRegistry registry;
    private AggregatingDecisionRecorder recorder;
    private EgressGuardImpl guard;

    @BeforeEach
    void setUp() {
        registry = new InMemoryEgressRegistry();
        recorder = new AggregatingDecisionRecorder();
        guard = new EgressGuardImpl(registry, registry, recorder);
        registry.addRule(new EgressRule(
                7L, EgressKind.HTTP, "https://siem.example.org:8443/api/", EgressMatch.PREFIX, "SIEM", true));
    }

    private static EgressRequest http(final String destination) {
        return new EgressRequest(EgressKind.HTTP, destination, "http-call");
    }

    @Test
    void testPermittedByRule() {
        final EgressDecision decision = guard.check(http("https://siem.example.org:8443/api/v1"));
        assertThat(decision.outcome()).isEqualTo(Outcome.ALLOWED);
        assertThat(decision.ruleId()).isEqualTo(7L);
        assertThat(recorder.getUse(7L)).isPresent();
        assertThat(recorder.getAttempts()).isEmpty();
    }

    @Test
    void testNoRuleIsDeniedInEnforce() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        final EgressDecision decision = guard.check(http("https://other.example.org/x"));
        assertThat(decision.outcome()).isEqualTo(Outcome.DENIED);
        assertThat(decision.isAllowed()).isFalse();
    }

    @Test
    void testNoRuleIsWouldDenyInReportOnlyAndProceeds() {
        final EgressDecision decision = guard.check(http("https://other.example.org/x"));
        assertThat(decision.outcome()).isEqualTo(Outcome.WOULD_DENY);
        assertThat(decision.isAllowed()).isTrue();
        assertThat(recorder.getAttempts()).hasSize(1);
    }

    @Test
    void testUnnormalisableIsDeniedInEnforceAndReportedInReportOnly() {
        assertThat(guard.check(http("https://user:pw@siem.example.org:8443/api/")).outcome())
                .isEqualTo(Outcome.WOULD_DENY);
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThat(guard.check(http("https://user:pw@siem.example.org:8443/api/")).outcome())
                .isEqualTo(Outcome.DENIED);
    }

    @Test
    void testKillSwitchOverridesRuleAndReportOnly() {
        registry.setKilled(EgressKind.HTTP, true);
        assertThat(guard.check(http("https://siem.example.org:8443/api/v1")).outcome()).isEqualTo(Outcome.DENIED);
        registry.setKilled(EgressKind.HTTP, false);
        assertThat(guard.check(http("https://siem.example.org:8443/api/v1")).outcome()).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void testModeIsPerKind() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThat(guard.check(http("https://other.example.org/")).outcome()).isEqualTo(Outcome.DENIED);
        assertThat(guard.check(new EgressRequest(EgressKind.EMAIL, "a@example.org", "send-email")).outcome())
                .isEqualTo(Outcome.WOULD_DENY);
    }

    @Test
    void testRegistryFailureDeniesInEnforceAndProceedsInReportOnly() {
        final EgressRuleSource broken = kind -> {
            throw new IllegalStateException("database down");
        };
        final EgressGuardImpl brokenGuard = new EgressGuardImpl(registry, broken, recorder);
        assertThat(brokenGuard.check(http("https://siem.example.org:8443/api/")).outcome())
                .isEqualTo(Outcome.WOULD_DENY);
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThat(brokenGuard.check(http("https://siem.example.org:8443/api/")).outcome())
                .isEqualTo(Outcome.DENIED);
    }

    @Test
    void testRecorderFailureNeverBreaksARequest() {
        final EgressGuardImpl failing = new EgressGuardImpl(registry, registry,
                new stroom.egress.api.EgressDecisionRecorder() {
                    @Override
                    public void record(final EgressRequest request, final EgressDecision decision) {
                        throw new IllegalStateException("cannot record");
                    }
                });
        assertThat(failing.check(http("https://other.example.org/")).outcome()).isEqualTo(Outcome.WOULD_DENY);
    }

    @Test
    void testDenyOutrightIgnoresModeAndRules() {
        final EgressDecision decision = guard.denyOutright(
                new EgressRequest(EgressKind.FILE, "/etc/passwd", "unparsed-text"));
        assertThat(decision.outcome()).isEqualTo(Outcome.DENIED);
        assertThat(recorder.getAttempts()).hasSize(1);
    }

    @Test
    void testRequireThrowsWhenDenied() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThatThrownBy(() -> guard.require(http("https://other.example.org/")))
                .isInstanceOf(EgressDeniedException.class);
    }

    @Test
    void testRuleChangeTakesEffect() {
        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        assertThat(guard.check(http("https://new.example.org/x")).outcome()).isEqualTo(Outcome.DENIED);
        registry.addRule(new EgressRule(
                8L, EgressKind.HTTP, "https://new.example.org/", EgressMatch.PREFIX, "new", true));
        assertThat(guard.check(http("https://new.example.org/x")).outcome()).isEqualTo(Outcome.ALLOWED);
    }

    @Test
    void testAttemptsCarryContextAndAggregate() {
        EgressContext.set(new EgressContext("alerts", "FEED_A"));
        try {
            guard.check(http("https://other.example.org/x?secret=1"));
            guard.check(http("https://other.example.org/x"));
        } finally {
            EgressContext.clear();
        }
        assertThat(recorder.getAttempts()).singleElement().satisfies(attempt -> {
            assertThat(attempt.count()).isEqualTo(2);
            assertThat(attempt.key().pipeline()).isEqualTo("alerts");
            assertThat(attempt.key().feed()).isEqualTo("FEED_A");
            assertThat(attempt.key().destination()).doesNotContain("secret");
        });
        assertThat(recorder.clear()).isEqualTo(1);
        assertThat(recorder.getAttempts()).isEmpty();
    }

    @Test
    void testRuleListIsCompiledOnceUntilItChanges() {
        final int[] calls = {0};
        final List<EgressRule> rules = List.of(new EgressRule(
                1L, EgressKind.HTTP, "https://a.example.org/", EgressMatch.PREFIX, "a", true));
        final EgressRuleSource counting = kind -> {
            calls[0]++;
            return rules;
        };
        final EgressGuardImpl counted = new EgressGuardImpl(registry, counting, recorder);
        counted.check(http("https://a.example.org/x"));
        counted.check(http("https://a.example.org/y"));
        assertThat(calls[0]).isEqualTo(2);
        assertThat(counted.check(http("https://a.example.org/z")).outcome()).isEqualTo(Outcome.ALLOWED);
    }
}
