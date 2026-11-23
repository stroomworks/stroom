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

package stroom.learningflow;

import stroom.config.common.HasDbConfig;
import stroom.util.cache.CacheConfig;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;
import stroom.util.time.StroomDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Min;

/// What a node is told about Learning Flow as a whole, as against what one document says about itself:
/// where its runtime state (A26) is kept, the caches that keep the hot path off those tables, how much
/// deferred learning one pass of the worker does (A5), and how long an attempt's record is kept (A28).
public class LearningFlowConfig extends AbstractConfig implements IsStroomConfig, HasDbConfig {

    private final LearningFlowDbConfig dbConfig;
    private final CacheConfig bindingCache;
    private final CacheConfig learnerStateCache;
    private final int deferredLearningBatchSize;
    private final StroomDuration attemptRetention;
    private final StroomDuration sessionLease;
    private final long spendTokensPerHour;
    private final int spendCallsPerHour;

    public LearningFlowConfig() {
        dbConfig = new LearningFlowDbConfig();
        // Read for every stream of every learner and written only when something is learned, so it is
        // invalidated by what writes it rather than by time; the expiry is a backstop.
        //
        // `expireAfterWrite` as well as `expireAfterAccess` (audit finding W7-1). Access alone never
        // expires a **busy** learner — every stream touches it — so a node that missed the invalidation
        // event goes on serving an old binding, or "no binding", for as long as the feed keeps arriving,
        // which is exactly when it matters. The write expiry is the bound on how long a lost event can
        // be wrong for.
        bindingCache = CacheConfig.builder()
                .maximumSize(1000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .expireAfterWrite(StroomDuration.ofMinutes(10))
                .build();
        learnerStateCache = CacheConfig.builder()
                .maximumSize(10_000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .expireAfterWrite(StroomDuration.ofMinutes(10))
                .build();
        deferredLearningBatchSize = 10;
        attemptRetention = StroomDuration.ofDays(90);
        sessionLease = StroomDuration.ofMinutes(10);
        // A backstop against a runaway, not a budget: high enough that no honest cluster meets it, low
        // enough that a loop cannot spend without limit. One feed learned from scratch cost about 550,000
        // tokens over thirteen calls on the development instance, so this is roughly eighteen whole
        // learnings an hour across every document on the cluster.
        spendTokensPerHour = 10_000_000L;
        spendCallsPerHour = 1_000;
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public LearningFlowConfig(@JsonProperty("db") final LearningFlowDbConfig dbConfig,
                                @JsonProperty("bindingCache") final CacheConfig bindingCache,
                                @JsonProperty("learnerStateCache") final CacheConfig learnerStateCache,
                                @JsonProperty("deferredLearningBatchSize") final int deferredLearningBatchSize,
                                @JsonProperty("attemptRetention") final StroomDuration attemptRetention,
                                @JsonProperty("sessionLease") final StroomDuration sessionLease,
                                @JsonProperty("spendTokensPerHour") final Long spendTokensPerHour,
                                @JsonProperty("spendCallsPerHour") final Integer spendCallsPerHour) {
        this.dbConfig = dbConfig;
        this.bindingCache = bindingCache;
        this.learnerStateCache = learnerStateCache;
        this.deferredLearningBatchSize = deferredLearningBatchSize;
        this.attemptRetention = attemptRetention;
        this.sessionLease = sessionLease;
        this.spendTokensPerHour = spendTokensPerHour == null
                ? 10_000_000L
                : spendTokensPerHour;
        this.spendCallsPerHour = spendCallsPerHour == null
                ? 1_000
                : spendCallsPerHour;
    }

    @Override
    @JsonProperty("db")
    @JsonPropertyDescription("The database holding the runtime state of A26: what serves each learner and "
                             + "its state, its learning sessions, the streams waiting on it, and the "
                             + "cluster's spend.")
    public LearningFlowDbConfig getDbConfig() {
        return dbConfig;
    }

    @JsonProperty("bindingCache")
    @JsonPropertyDescription("What serves each Learning Flow learner, cached per node. Every stream of "
                             + "every learner reads it, and it changes only when something is learned or a "
                             + "person acts on it, which clears the entry on every node. The write expiry "
                             + "is the backstop for a clearing event a node misses: without it a busy "
                             + "learner, whose entry is touched by every stream, would never expire and "
                             + "the node would serve the old binding for as long as the feed kept "
                             + "arriving.")
    public CacheConfig getBindingCache() {
        return bindingCache;
    }

    @JsonProperty("learnerStateCache")
    @JsonPropertyDescription("What each learner has settled into — given up, marked to learn again, "
                             + "awaiting review — cached per node. The rolling scores are not cached: they "
                             + "are counted across the cluster.")
    public CacheConfig getLearnerStateCache() {
        return learnerStateCache;
    }

    @Min(1)
    @JsonProperty("deferredLearningBatchSize")
    @JsonPropertyDescription("How many attempts awaiting the model one pass of the Learning Flow Deferred "
                             + "Learning job carries on. One attempt is one conversation, which may be minutes "
                             + "of model time, so a pass is bounded and the job's schedule sets the rate. "
                             + "At least one: a batch of none would leave every waiting stream unlearned, "
                             + "silently.")
    public int getDeferredLearningBatchSize() {
        return deferredLearningBatchSize;
    }

    @JsonProperty("attemptRetention")
    @JsonPropertyDescription("How long the record of a finished attempt is kept before the prune job "
                             + "removes it and its turns, and how long what each binding produced is kept, "
                             + "which is what an as-processed reprocess reads. An attempt "
                             + "that is still learning, or waiting for a person, is never pruned however "
                             + "old it is. Zero keeps everything for ever, which on a busy cluster is a "
                             + "row per stream that nothing will ever remove.")
    public StroomDuration getAttemptRetention() {
        return attemptRetention;
    }

    @JsonProperty("sessionLease")
    @JsonPropertyDescription("How long a node's claim on a learning session lasts from its last turn. The "
                             + "node renews it before and after every question it puts to the model, so a "
                             + "live session never lapses; one whose node has died lapses within this of "
                             + "its last turn, and the next pass of the Deferred Learning job on any node "
                             + "carries it on from the turns already recorded. Longer than the slowest "
                             + "model call expected, or a slow answer is taken for a dead node and two nodes "
                             + "walk one session.")
    public StroomDuration getSessionLease() {
        return sessionLease;
    }

    @Min(0)
    @JsonProperty("spendTokensPerHour")
    @JsonPropertyDescription("The most tokens every Smart Pipeline on the cluster may spend between them "
                             + "in an hour (ruling A24). Past it no new learning session opens and the "
                             + "streams that would have opened one wait, saying so, until the hour rolls; "
                             + "a session already running is left to finish, since it has budgets of its "
                             + "own. This is a backstop against a runaway rather than a budget: one feed "
                             + "learned from scratch costs of the order of half a million tokens, so the "
                             + "default is roughly eighteen whole learnings an hour across the whole "
                             + "cluster. Zero turns it off, which leaves unattended learning with no upper "
                             + "bound on what it can spend.")
    public long getSpendTokensPerHour() {
        return spendTokensPerHour;
    }

    @Min(0)
    @JsonProperty("spendCallsPerHour")
    @JsonPropertyDescription("The most questions every Smart Pipeline on the cluster may put to a model "
                             + "between them in an hour: the rate limit beside the spend ceiling, for a "
                             + "model priced or throttled by the call rather than by the token. It is read "
                             + "and acted on exactly as the token ceiling is. Zero turns it off.")
    public int getSpendCallsPerHour() {
        return spendCallsPerHour;
    }
}
