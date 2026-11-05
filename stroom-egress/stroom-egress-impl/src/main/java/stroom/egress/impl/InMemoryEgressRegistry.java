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

import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/// Holds the rules, modes and kill switches in memory. It is the store used until the database store is in place,
/// and the one the tests use. A change replaces the list of rules for the kind, so the guard recompiles it.
///
/// The default mode is report-only, as on first install.
@Singleton
public class InMemoryEgressRegistry implements EgressRuleSource, EgressSettings, EgressStore {

    private final Map<EgressKind, List<EgressRule>> enabled = new EnumMap<>(EgressKind.class);
    private final List<EgressRule> all = new CopyOnWriteArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<EgressKind, EgressMode> modes = new EnumMap<>(EgressKind.class);
    private final Map<EgressKind, Boolean> killed = new EnumMap<>(EgressKind.class);

    @Override
    public synchronized List<EgressRule> getEnabledRules(final EgressKind kind) {
        return enabled.computeIfAbsent(kind, k -> List.of());
    }

    @Override
    public synchronized EgressMode getMode(final EgressKind kind) {
        return modes.getOrDefault(kind, EgressMode.REPORT_ONLY);
    }

    @Override
    public synchronized boolean isKilled(final EgressKind kind) {
        return killed.getOrDefault(kind, false);
    }

    /// Adds a rule.
    ///
    /// @param rule The rule.
    public synchronized void addRule(final EgressRule rule) {
        saveRule(rule);
    }

    @Override
    public synchronized List<EgressRule> listRules() {
        return List.copyOf(all);
    }

    @Override
    public synchronized EgressRule saveRule(final EgressRule rule) {
        final long id = rule.id() != null
                ? rule.id()
                : nextId.getAndIncrement();
        final EgressRule stored = new EgressRule(
                id, rule.kind(), rule.destination(), rule.match(), rule.comment(), rule.enabled());
        final EgressKind previousKind = all.stream()
                .filter(existing -> existing.id() != null && existing.id() == id)
                .map(EgressRule::kind)
                .findFirst()
                .orElse(null);
        all.removeIf(existing -> existing.id() != null && existing.id() == id);
        all.add(stored);
        refresh(stored.kind());
        if (previousKind != null && previousKind != stored.kind()) {
            refresh(previousKind);
        }
        return stored;
    }

    @Override
    public synchronized boolean deleteRule(final long id) {
        final EgressKind kind = all.stream()
                .filter(existing -> existing.id() != null && existing.id() == id)
                .map(EgressRule::kind)
                .findFirst()
                .orElse(null);
        if (kind == null) {
            return false;
        }
        all.removeIf(existing -> existing.id() != null && existing.id() == id);
        refresh(kind);
        return true;
    }

    @Override
    public synchronized Map<EgressKind, EgressMode> getModes() {
        final Map<EgressKind, EgressMode> result = new EnumMap<>(EgressKind.class);
        for (final EgressKind kind : EgressKind.values()) {
            result.put(kind, getMode(kind));
        }
        return result;
    }

    @Override
    public synchronized Map<EgressKind, Boolean> getKillSwitches() {
        final Map<EgressKind, Boolean> result = new EnumMap<>(EgressKind.class);
        for (final EgressKind kind : EgressKind.values()) {
            result.put(kind, isKilled(kind));
        }
        return result;
    }

    /// Removes every rule.
    public synchronized void clearRules() {
        all.clear();
        for (final EgressKind kind : EgressKind.values()) {
            refresh(kind);
        }
    }

    @Override
    public synchronized void setMode(final EgressKind kind, final EgressMode mode) {
        modes.put(kind, mode);
    }

    @Override
    public synchronized void setKilled(final EgressKind kind, final boolean killed) {
        this.killed.put(kind, killed);
    }

    private void refresh(final EgressKind kind) {
        final List<EgressRule> rules = new ArrayList<>();
        for (final EgressRule rule : all) {
            if (rule.kind() == kind && rule.enabled()) {
                rules.add(rule);
            }
        }
        enabled.put(kind, List.copyOf(rules));
    }
}
