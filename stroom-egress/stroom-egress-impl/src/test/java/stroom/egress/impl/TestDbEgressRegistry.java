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
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;
import stroom.security.api.SecurityContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDbEgressRegistry {

    private FakeDao dao;
    private DbEgressRegistry registry;

    @BeforeEach
    void setUp() {
        dao = new FakeDao();
        final SecurityContext securityContext = Mockito.mock(SecurityContext.class);
        Mockito.when(securityContext.getUserIdentityForAudit()).thenReturn("admin");
        registry = new DbEgressRegistry(dao, () -> securityContext);
    }

    @Test
    void testNothingReadableBeforeTheFirstLoad() {
        assertThatThrownBy(() -> registry.getMode(EgressKind.HTTP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been loaded");
        assertThatThrownBy(() -> registry.getEnabledRules(EgressKind.HTTP))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testDefaultsAreReportOnlyAndNotKilled() {
        registry.reload();

        for (final EgressKind kind : EgressKind.values()) {
            assertThat(registry.getMode(kind)).isEqualTo(EgressMode.REPORT_ONLY);
            assertThat(registry.isKilled(kind)).isFalse();
            assertThat(registry.getEnabledRules(kind)).isEmpty();
        }
    }

    @Test
    void testSavedRuleIsReadBackAndWrittenWithTheUser() {
        registry.reload();

        final EgressRule stored = registry.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true));

        assertThat(stored.id()).isNotNull();
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).containsExactly(stored);
        assertThat(registry.listRules()).containsExactly(stored);
        assertThat(dao.lastUser).isEqualTo("admin");
    }

    @Test
    void testDisabledRuleIsListedButNotEnforced() {
        registry.reload();

        final EgressRule stored = registry.saveRule(rule(EgressKind.HTTP, "https://example.com/api", false));

        assertThat(registry.listRules()).containsExactly(stored);
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).isEmpty();
    }

    @Test
    void testRulesOfOtherKindsAreKeptApart() {
        registry.reload();

        registry.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true));
        final EgressRule file = registry.saveRule(
                new EgressRule(null, EgressKind.FILE, "/data/out", EgressMatch.PREFIX, "out", true));

        assertThat(registry.getEnabledRules(EgressKind.FILE)).containsExactly(file);
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).hasSize(1);
    }

    @Test
    void testDeleteRemovesTheRule() {
        registry.reload();
        final EgressRule stored = registry.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true));

        assertThat(registry.deleteRule(stored.id())).isTrue();
        assertThat(registry.deleteRule(stored.id())).isFalse();
        assertThat(registry.listRules()).isEmpty();
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).isEmpty();
    }

    @Test
    void testModeAndKillSwitchAreStoredPerKind() {
        registry.reload();

        registry.setMode(EgressKind.HTTP, EgressMode.ENFORCE);
        registry.setKilled(EgressKind.FILE, true);

        assertThat(registry.getMode(EgressKind.HTTP)).isEqualTo(EgressMode.ENFORCE);
        assertThat(registry.getMode(EgressKind.FILE)).isEqualTo(EgressMode.REPORT_ONLY);
        assertThat(registry.isKilled(EgressKind.FILE)).isTrue();
        assertThat(registry.isKilled(EgressKind.HTTP)).isFalse();
        assertThat(registry.getModes().get(EgressKind.HTTP)).isEqualTo(EgressMode.ENFORCE);
        assertThat(registry.getKillSwitches().get(EgressKind.FILE)).isTrue();
    }

    @Test
    void testChangeMadeByAnotherNodeArrivesOnReload() {
        registry.reload();
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).isEmpty();

        // Written straight to the store, as another node would.
        dao.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true), "someone");
        dao.saveMode(EgressKind.HTTP, EgressMode.ENFORCE, "someone");
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).isEmpty();

        registry.reload();

        assertThat(registry.getEnabledRules(EgressKind.HTTP)).hasSize(1);
        assertThat(registry.getMode(EgressKind.HTTP)).isEqualTo(EgressMode.ENFORCE);
    }

    @Test
    void testUnchangedKindKeepsTheSameListInstance() {
        registry.reload();
        registry.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true));
        final List<EgressRule> before = registry.getEnabledRules(EgressKind.HTTP);

        // A change to a different kind must not make the guard recompile the HTTP rules.
        registry.saveRule(new EgressRule(null, EgressKind.FILE, "/data/out", EgressMatch.PREFIX, "out", true));
        registry.reload();

        assertThat(registry.getEnabledRules(EgressKind.HTTP)).isSameAs(before);
    }

    @Test
    void testFailedReloadKeepsTheEarlierCopy() {
        registry.reload();
        registry.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true));

        dao.failing = true;

        assertThatThrownBy(() -> registry.reload()).isInstanceOf(IllegalStateException.class);
        assertThat(registry.getEnabledRules(EgressKind.HTTP)).hasSize(1);
    }

    @Test
    void testStartLoadsAndStopIsSafeToRepeat() {
        dao.saveRule(rule(EgressKind.HTTP, "https://example.com/api", true), "someone");

        registry.start();
        try {
            assertThat(registry.getEnabledRules(EgressKind.HTTP)).hasSize(1);
        } finally {
            registry.stop();
            registry.stop();
        }
    }

    @Test
    void testStartSurvivesAFailedFirstLoad() {
        dao.failing = true;

        registry.start();
        try {
            assertThatThrownBy(() -> registry.getMode(EgressKind.HTTP))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            registry.stop();
        }
    }

    private static EgressRule rule(final EgressKind kind, final String destination, final boolean enabled) {
        return new EgressRule(null, kind, destination, EgressMatch.EXACT, "a reason", enabled);
    }


    // --------------------------------------------------------------------------------


    private static class FakeDao implements EgressDao {

        private final List<EgressRule> rules = new ArrayList<>();
        private final Map<EgressKind, EgressMode> modes = new EnumMap<>(EgressKind.class);
        private final Map<EgressKind, Boolean> killed = new EnumMap<>(EgressKind.class);
        private final AtomicLong nextId = new AtomicLong(1);
        private String lastUser;
        private boolean failing;

        @Override
        public List<EgressRule> fetchRules() {
            check();
            return List.copyOf(rules);
        }

        @Override
        public EgressRule saveRule(final EgressRule rule, final String user) {
            lastUser = user;
            final long id = rule.id() == null
                    ? nextId.getAndIncrement()
                    : rule.id();
            final EgressRule stored = new EgressRule(
                    id, rule.kind(), rule.destination(), rule.match(), rule.comment(), rule.enabled());
            rules.removeIf(existing -> existing.id() == id);
            rules.add(stored);
            return stored;
        }

        @Override
        public boolean deleteRule(final long id) {
            return rules.removeIf(existing -> existing.id() == id);
        }

        @Override
        public Map<EgressKind, EgressMode> fetchModes() {
            check();
            return new EnumMap<>(modes.isEmpty()
                    ? new EnumMap<>(EgressKind.class)
                    : modes);
        }

        @Override
        public Map<EgressKind, Boolean> fetchKillSwitches() {
            check();
            return new EnumMap<>(killed.isEmpty()
                    ? new EnumMap<>(EgressKind.class)
                    : killed);
        }

        @Override
        public void saveMode(final EgressKind kind, final EgressMode mode, final String user) {
            lastUser = user;
            modes.put(kind, mode);
        }

        @Override
        public void saveKilled(final EgressKind kind, final boolean killed, final String user) {
            lastUser = user;
            this.killed.put(kind, killed);
        }

        private void check() {
            if (failing) {
                throw new IllegalStateException("The database is down");
            }
        }
    }
}
