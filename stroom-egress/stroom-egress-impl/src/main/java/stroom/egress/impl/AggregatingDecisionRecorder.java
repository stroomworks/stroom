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
import stroom.egress.api.EgressDecisionRecorder;
import stroom.egress.api.EgressRequest;
import stroom.egress.shared.EgressKind;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Aggregates blocked and would-be-blocked attempts by destination, sink, pipeline and feed, so a flood of identical
/// attempts becomes one row with a count, and counts the use of each rule.
///
/// The table is not capped (a project decision). It can be cleared, in full or of rows older than a date. The first
/// time a new attempt is seen it is also written to the log.
@Singleton
public class AggregatingDecisionRecorder implements EgressDecisionRecorder {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(AggregatingDecisionRecorder.class);

    private final Map<AttemptKey, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<Long, RuleUse> uses = new ConcurrentHashMap<>();

    @Override
    public void record(final EgressRequest request, final EgressDecision decision) {
        final Optional<EgressContext> context = EgressContext.current();
        final AttemptKey key = new AttemptKey(
                request.kind(),
                decision.normalised() != null
                        ? decision.normalised()
                        : String.valueOf(request.destination()),
                request.sink(),
                context.map(EgressContext::pipeline).orElse(null),
                context.map(EgressContext::feed).orElse(null),
                decision.outcome());
        final Instant now = Instant.now();
        final boolean[] first = {false};
        attempts.compute(key, (k, existing) -> {
            if (existing == null) {
                first[0] = true;
                return new Attempt(k, 1, now, now, decision.reason());
            }
            return new Attempt(k, existing.count() + 1, existing.firstSeen(), now, decision.reason());
        });
        if (first[0]) {
            LOGGER.warn(() -> "Egress " + decision.outcome() + ": " + key.kind() + " " + key.destination()
                              + " by " + key.sink() + " (" + decision.reason() + ")");
        }
    }

    @Override
    public void recordUse(final EgressRequest request, final EgressDecision decision) {
        if (decision.ruleId() != null) {
            final Instant now = Instant.now();
            uses.computeIfAbsent(decision.ruleId(), id -> new RuleUse()).hit(now);
        }
    }

    /// The aggregated attempts, most recent first.
    ///
    /// @return A snapshot.
    public List<Attempt> getAttempts() {
        return attempts.values().stream()
                .sorted(Comparator.comparing(Attempt::lastSeen).reversed())
                .toList();
    }

    /// When and how often a rule has been used.
    ///
    /// @param ruleId The rule.
    /// @return The use, if the rule has been used since start-up.
    public Optional<RuleUse> getUse(final long ruleId) {
        return Optional.ofNullable(uses.get(ruleId));
    }

    /// Removes every aggregated attempt.
    ///
    /// @return How many rows were removed.
    public int clear() {
        final int count = attempts.size();
        attempts.clear();
        return count;
    }

    /// Removes the attempts last seen before a time.
    ///
    /// @param before The cut-off.
    /// @return How many rows were removed.
    public int clearOlderThan(final Instant before) {
        final int sizeBefore = attempts.size();
        attempts.values().removeIf(attempt -> attempt.lastSeen().isBefore(before));
        return sizeBefore - attempts.size();
    }

    /// The identity of an aggregated row.
    ///
    /// @param kind        The kind.
    /// @param destination The normalised destination, or the raw one if it could not be normalised.
    /// @param sink        The sink.
    /// @param pipeline    The pipeline, or null.
    /// @param feed        The feed, or null.
    /// @param outcome     DENIED or WOULD_DENY.
    public record AttemptKey(EgressKind kind,
                             String destination,
                             String sink,
                             String pipeline,
                             String feed,
                             Outcome outcome) {

    }

    /// One aggregated row.
    ///
    /// @param key       What it is for.
    /// @param count     How many times it was seen.
    /// @param firstSeen When it was first seen.
    /// @param lastSeen  When it was last seen.
    /// @param reason    The latest reason given.
    public record Attempt(AttemptKey key, long count, Instant firstSeen, Instant lastSeen, String reason) {

    }

    /// How often a rule has been used.
    public static final class RuleUse {

        private final AtomicLong count = new AtomicLong();
        private volatile Instant lastUsed;

        private void hit(final Instant now) {
            count.incrementAndGet();
            lastUsed = now;
        }

        /// The number of uses since start-up.
        ///
        /// @return The count.
        public long getCount() {
            return count.get();
        }

        /// When it was last used.
        ///
        /// @return The time, or null.
        public Instant getLastUsed() {
            return lastUsed;
        }
    }
}
