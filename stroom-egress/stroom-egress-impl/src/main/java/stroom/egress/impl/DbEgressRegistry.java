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
import stroom.egress.api.EgressRuleSource;
import stroom.egress.api.EgressSettings;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMode;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/// Keeps the rules, modes and kill switches in the database and a copy in memory, which is all that a check reads.
///
/// A change is written to the database and then loaded back, so that this node sees it at once. Every node also
/// reloads every [#RELOAD_INTERVAL_SECONDS] seconds, which is how a change made on another node arrives: the
/// plan accepts that delay (the kill switch is the control for the cases where it matters, and it is the same
/// delay).
///
/// If a reload fails the last copy is kept and the failure is logged. If the first load fails the guard has
/// nothing to read, and it treats that as a registry failure: it refuses in enforce mode and records in
/// report-only mode.
@Singleton
public class DbEgressRegistry implements EgressRuleSource, EgressSettings, EgressStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(DbEgressRegistry.class);

    /// How often each node reloads the policy from the database.
    public static final long RELOAD_INTERVAL_SECONDS = 5;

    private final EgressDao dao;
    private final Provider<SecurityContext> securityContextProvider;

    private volatile Snapshot snapshot;
    private ScheduledExecutorService scheduler;

    @Inject
    DbEgressRegistry(final EgressDao dao,
                     final Provider<SecurityContext> securityContextProvider) {
        this.dao = dao;
        this.securityContextProvider = securityContextProvider;
    }

    /// Loads the policy and starts reloading it. Called once at start-up. A failed first load is logged and
    /// retried on the next tick.
    public synchronized void start() {
        reloadQuietly();
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "egress-policy-reload");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(
                    this::reloadQuietly, RELOAD_INTERVAL_SECONDS, RELOAD_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /// Stops the periodic reload.
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /// Reads the policy from the database and replaces the copy in memory. A kind whose rules have not changed
    /// keeps the same list instance, so the guard does not recompile them.
    public synchronized void reload() {
        final List<EgressRule> rules = dao.fetchRules();
        final Map<EgressKind, EgressMode> modes = dao.fetchModes();
        final Map<EgressKind, Boolean> killed = dao.fetchKillSwitches();
        snapshot = Snapshot.of(rules, modes, killed, snapshot);
    }

    @Override
    public List<EgressRule> getEnabledRules(final EgressKind kind) {
        return loaded().enabled.get(kind);
    }

    @Override
    public EgressMode getMode(final EgressKind kind) {
        return loaded().modes.get(kind);
    }

    @Override
    public boolean isKilled(final EgressKind kind) {
        return loaded().killed.get(kind);
    }

    @Override
    public List<EgressRule> listRules() {
        return loaded().all;
    }

    @Override
    public EgressRule saveRule(final EgressRule rule) {
        final EgressRule stored = dao.saveRule(rule, user());
        reload();
        return stored;
    }

    @Override
    public boolean deleteRule(final long id) {
        final boolean deleted = dao.deleteRule(id);
        reload();
        return deleted;
    }

    @Override
    public Map<EgressKind, EgressMode> getModes() {
        return loaded().modes;
    }

    @Override
    public Map<EgressKind, Boolean> getKillSwitches() {
        return loaded().killed;
    }

    @Override
    public void setMode(final EgressKind kind, final EgressMode mode) {
        dao.saveMode(kind, mode, user());
        reload();
    }

    @Override
    public void setKilled(final EgressKind kind, final boolean killed) {
        dao.saveKilled(kind, killed, user());
        reload();
    }

    private Snapshot loaded() {
        final Snapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("The egress policy has not been loaded from the database");
        }
        return current;
    }

    private String user() {
        return securityContextProvider.get().getUserIdentityForAudit();
    }

    private void reloadQuietly() {
        try {
            reload();
        } catch (final RuntimeException e) {
            LOGGER.error("Could not reload the egress policy from the database"
                         + (snapshot == null
                    ? ", and there is no earlier copy"
                    : ", keeping the earlier copy")
                         + ": " + e.getMessage(), e);
        }
    }


    // --------------------------------------------------------------------------------


    private record Snapshot(List<EgressRule> all,
                            Map<EgressKind, List<EgressRule>> enabled,
                            Map<EgressKind, EgressMode> modes,
                            Map<EgressKind, Boolean> killed) {

        private static Snapshot of(final List<EgressRule> rules,
                                   final Map<EgressKind, EgressMode> storedModes,
                                   final Map<EgressKind, Boolean> storedKills,
                                   final Snapshot previous) {
            final Map<EgressKind, List<EgressRule>> enabled = new EnumMap<>(EgressKind.class);
            final Map<EgressKind, EgressMode> modes = new EnumMap<>(EgressKind.class);
            final Map<EgressKind, Boolean> killed = new EnumMap<>(EgressKind.class);
            for (final EgressKind kind : EgressKind.values()) {
                final List<EgressRule> fresh = new ArrayList<>();
                for (final EgressRule rule : rules) {
                    if (rule.kind() == kind && rule.enabled()) {
                        fresh.add(rule);
                    }
                }
                // Reuse the earlier list if nothing changed, because the guard caches compiled rules by it.
                final List<EgressRule> earlier = previous == null
                        ? null
                        : previous.enabled.get(kind);
                enabled.put(kind, earlier != null && earlier.equals(fresh)
                        ? earlier
                        : List.copyOf(fresh));
                modes.put(kind, storedModes.getOrDefault(kind, EgressMode.REPORT_ONLY));
                killed.put(kind, storedKills.getOrDefault(kind, false));
            }
            return new Snapshot(
                    List.copyOf(rules),
                    Map.copyOf(enabled),
                    Map.copyOf(modes),
                    Map.copyOf(killed));
        }
    }
}
