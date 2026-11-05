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

import stroom.egress.api.EgressDecision;
import stroom.egress.api.EgressDecision.Outcome;
import stroom.egress.api.EgressDecisionRecorder;
import stroom.egress.api.EgressGuard;
import stroom.egress.api.EgressRequest;
import stroom.egress.api.EgressRule;
import stroom.egress.api.EgressRuleSource;
import stroom.egress.api.EgressSettings;
import stroom.egress.impl.EgressMatcher.CompiledRule;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMode;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The guard. It reads the mode and kill switch of the kind, normalises the destination, and looks for a rule.
///
/// Fail-closed behaviour depends on the mode. In enforce mode an exception, an unknown kind or an unavailable
/// registry denies. In report-only mode the same conditions let the request proceed and are recorded, so a fault in
/// the registry cannot change the behaviour of an existing pipeline. The kill switch overrides report-only.
@Singleton
public class EgressGuardImpl implements EgressGuard {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(EgressGuardImpl.class);

    private final EgressSettings settings;
    private final EgressRuleSource ruleSource;
    private final EgressDecisionRecorder recorder;
    private final Map<EgressKind, CachedRules> cache = new EnumMap<>(EgressKind.class);

    /// Creates the guard.
    ///
    /// @param settings   Mode and kill switch per kind.
    /// @param ruleSource The rules.
    /// @param recorder   Where blocked and would-be-blocked attempts go.
    @Inject
    public EgressGuardImpl(final EgressSettings settings,
                           final EgressRuleSource ruleSource,
                           final EgressDecisionRecorder recorder) {
        this.settings = settings;
        this.ruleSource = ruleSource;
        this.recorder = recorder;
    }

    @Override
    public EgressDecision check(final EgressRequest request) {
        EgressMode mode = EgressMode.ENFORCE;
        EgressDecision decision;
        try {
            mode = settings.getMode(request.kind());
            decision = decide(request, mode);
        } catch (final RuntimeException e) {
            LOGGER.error(() -> "Egress guard failed for " + request.sink() + ": " + e.getMessage(), e);
            decision = mode == EgressMode.ENFORCE
                    ? new EgressDecision(Outcome.DENIED, "guard error, failing closed", null, null)
                    : new EgressDecision(Outcome.WOULD_DENY, "registry unavailable", null, null);
        }
        record(request, decision);
        return decision;
    }

    @Override
    public EgressMode getMode(final EgressKind kind) {
        try {
            return settings.getMode(kind);
        } catch (final RuntimeException e) {
            // Unknown means enforce, the safer reading.
            return EgressMode.ENFORCE;
        }
    }

    @Override
    public EgressDecision denyOutright(final EgressRequest request) {
        final EgressDecision decision = new EgressDecision(
                Outcome.DENIED, "this route is denied outright and no rule can permit it", null, null);
        record(request, decision);
        return decision;
    }

    private EgressDecision decide(final EgressRequest request, final EgressMode mode) {
        if (settings.isKilled(request.kind())) {
            return new EgressDecision(Outcome.DENIED, "kill switch is on for " + request.kind(), null, null);
        }
        final Optional<NormalisedDestination> normalised =
                EgressNormalisers.normalise(request.kind(), request.destination());
        if (normalised.isEmpty()) {
            return notPermitted(mode, "destination could not be normalised", null);
        }
        final NormalisedDestination destination = normalised.get();
        final Optional<EgressRule> rule =
                EgressMatcher.match(request.kind(), destination, compiledRules(request.kind()));
        if (rule.isPresent()) {
            return new EgressDecision(Outcome.ALLOWED, "permitted by a rule", destination.text(), rule.get().id());
        }
        return notPermitted(mode, "no rule permits this destination", destination.text());
    }

    private static EgressDecision notPermitted(final EgressMode mode, final String reason, final String normalised) {
        return new EgressDecision(
                mode == EgressMode.ENFORCE
                        ? Outcome.DENIED
                        : Outcome.WOULD_DENY,
                reason, normalised, null);
    }

    // Rules are compiled once per change. The source returns the same list instance until the rules change.
    private List<CompiledRule> compiledRules(final EgressKind kind) {
        final List<EgressRule> rules = ruleSource.getEnabledRules(kind);
        synchronized (cache) {
            final CachedRules cached = cache.get(kind);
            if (cached != null && cached.source() == rules) {
                return cached.compiled();
            }
            final List<CompiledRule> compiled = new ArrayList<>();
            for (final EgressRule rule : rules) {
                // A rule whose destination cannot be normalised can never match, so it is left out.
                EgressNormalisers.normalise(kind, rule.destination())
                        .ifPresent(destination -> compiled.add(new CompiledRule(rule, destination)));
            }
            final List<CompiledRule> immutable = List.copyOf(compiled);
            cache.put(kind, new CachedRules(rules, immutable));
            return immutable;
        }
    }

    // Recording must never break a request.
    private void record(final EgressRequest request, final EgressDecision decision) {
        try {
            if (decision.outcome() == Outcome.ALLOWED) {
                recorder.recordUse(request, decision);
            } else {
                recorder.record(request, decision);
            }
        } catch (final RuntimeException e) {
            LOGGER.error(() -> "Could not record an egress decision: " + e.getMessage(), e);
        }
    }

    private record CachedRules(List<EgressRule> source, List<CompiledRule> compiled) {

    }
}
