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
import stroom.egress.impl.db.EgressDbModule;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;
import stroom.test.common.util.db.DbTestModule;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Runs against a real MySQL, as the other database tests do, so it needs the same database as they do.
class TestEgressDaoImpl {

    @Inject
    private EgressDao dao;

    @BeforeEach
    void setUp() {
        Guice.createInjector(new TestModule()).injectMembers(this);
        dao.fetchRules().forEach(rule -> dao.deleteRule(rule.id()));
    }

    @Test
    void testInsertGivesAnIdAndReadsBack() {
        final EgressRule stored = dao.saveRule(
                new EgressRule(null, EgressKind.HTTP, "https://example.com/api", EgressMatch.PREFIX, "reason", true),
                "admin");

        assertThat(stored.id()).isNotNull();
        assertThat(dao.fetchRules()).containsExactly(stored);
    }

    @Test
    void testUpdateReplacesTheRule() {
        final EgressRule stored = dao.saveRule(
                new EgressRule(null, EgressKind.HTTP, "https://example.com/api", EgressMatch.PREFIX, "reason", true),
                "admin");

        dao.saveRule(
                new EgressRule(stored.id(), EgressKind.HTTP, "https://example.org/api", EgressMatch.EXACT,
                        "changed", false),
                "other");

        assertThat(dao.fetchRules()).containsExactly(
                new EgressRule(stored.id(), EgressKind.HTTP, "https://example.org/api", EgressMatch.EXACT,
                        "changed", false));
    }

    @Test
    void testUpdateOfAMissingRuleFails() {
        assertThatThrownBy(() -> dao.saveRule(
                new EgressRule(123456L, EgressKind.HTTP, "https://example.com/api", EgressMatch.EXACT, "x", true),
                "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testDelete() {
        final EgressRule stored = dao.saveRule(
                new EgressRule(null, EgressKind.FILE, "/data/out", EgressMatch.PREFIX, "reason", true), "admin");

        assertThat(dao.deleteRule(stored.id())).isTrue();
        assertThat(dao.deleteRule(stored.id())).isFalse();
        assertThat(dao.fetchRules()).isEmpty();
    }

    @Test
    void testModeAndKillSwitchAreIndependent() {
        dao.saveMode(EgressKind.HTTP, EgressMode.ENFORCE, "admin");
        dao.saveKilled(EgressKind.HTTP, true, "admin");
        dao.saveKilled(EgressKind.FILE, true, "admin");
        dao.saveMode(EgressKind.HTTP, EgressMode.REPORT_ONLY, "admin");

        assertThat(dao.fetchModes().get(EgressKind.HTTP)).isEqualTo(EgressMode.REPORT_ONLY);
        assertThat(dao.fetchModes().get(EgressKind.FILE)).isEqualTo(EgressMode.REPORT_ONLY);
        assertThat(dao.fetchKillSwitches().get(EgressKind.HTTP)).isTrue();
        assertThat(dao.fetchKillSwitches().get(EgressKind.FILE)).isTrue();
    }


    // --------------------------------------------------------------------------------


    private static class TestModule extends AbstractModule {

        @Override
        protected void configure() {
            install(new DbTestModule());
            install(new EgressDbModule());
            bind(EgressDao.class).to(EgressDaoImpl.class);
        }
    }
}
