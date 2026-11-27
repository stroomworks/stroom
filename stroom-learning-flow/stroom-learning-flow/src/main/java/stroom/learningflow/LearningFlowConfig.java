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

/// Node configuration for Learning Flow as a whole, as opposed to the settings on each Learning Flow
/// document: the database holding its runtime state, the caches that keep stream processing off those
/// tables, how many sessions one pass of the deferred learning job resumes, how long session records are
/// kept, the session lease, and the cluster-wide spend ceilings.
public class LearningFlowConfig extends AbstractConfig implements IsStroomConfig, HasDbConfig {

    // A backstop against runaway spending, not a budget: high enough that normal use never reaches it,
    // low enough that a loop cannot spend without limit. Learning one feed from scratch cost about 550,000
    // tokens over thirteen calls on the development instance, so this allows roughly eighteen complete
    // learnings an hour across every document on the cluster.
    private static final long DEFAULT_SPEND_TOKENS_PER_HOUR = 10_000_000L;
    private static final int DEFAULT_SPEND_CALLS_PER_HOUR = 1_000;

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
        // Read for every stream of every learner and written only when something is learned, so entries
        // are invalidated by the code that writes them rather than by time; the expiry is a backstop.
        //
        // `expireAfterWrite` is set as well as `expireAfterAccess`, because access expiry alone never
        // expires a **busy** learner, whose entry every stream touches. A node that missed the
        // invalidation event would then go on serving an old binding, or no binding, for as long as the
        // feed kept arriving. The write expiry limits how long a missed event can leave an entry wrong.
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
        spendTokensPerHour = DEFAULT_SPEND_TOKENS_PER_HOUR;
        spendCallsPerHour = DEFAULT_SPEND_CALLS_PER_HOUR;
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
                ? DEFAULT_SPEND_TOKENS_PER_HOUR
                : spendTokensPerHour;
        this.spendCallsPerHour = spendCallsPerHour == null
                ? DEFAULT_SPEND_CALLS_PER_HOUR
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
