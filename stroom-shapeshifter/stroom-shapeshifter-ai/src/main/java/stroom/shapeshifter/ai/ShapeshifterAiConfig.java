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

package stroom.shapeshifter.ai;

import stroom.config.common.HasDbConfig;
import stroom.util.cache.CacheConfig;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;
import stroom.util.time.StroomDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Min;

/// What a node is told about Shapeshifter AI as a whole, as against what one document says about itself:
/// where its runtime state (A26) is kept, the caches that keep the hot path off those tables, how much
/// deferred learning one pass of the worker does (A5), and how long an attempt's record is kept (A28).
public class ShapeshifterAiConfig extends AbstractConfig implements IsStroomConfig, HasDbConfig {

    private final ShapeshifterAiDbConfig dbConfig;
    private final CacheConfig bindingCache;
    private final CacheConfig learnerStateCache;
    private final int deferredLearningBatchSize;
    private final StroomDuration attemptRetention;
    private final StroomDuration sessionLease;

    public ShapeshifterAiConfig() {
        dbConfig = new ShapeshifterAiDbConfig();
        // Read for every stream of every learner and written only when something is learned, so it is
        // invalidated by what writes it rather than by time; the expiry is a backstop.
        bindingCache = CacheConfig.builder()
                .maximumSize(1000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .build();
        learnerStateCache = CacheConfig.builder()
                .maximumSize(10_000L)
                .expireAfterAccess(StroomDuration.ofMinutes(10))
                .build();
        deferredLearningBatchSize = 10;
        attemptRetention = StroomDuration.ofDays(90);
        sessionLease = StroomDuration.ofMinutes(10);
    }

    @SuppressWarnings("unused")
    @JsonCreator
    public ShapeshifterAiConfig(@JsonProperty("db") final ShapeshifterAiDbConfig dbConfig,
                                @JsonProperty("bindingCache") final CacheConfig bindingCache,
                                @JsonProperty("learnerStateCache") final CacheConfig learnerStateCache,
                                @JsonProperty("deferredLearningBatchSize") final int deferredLearningBatchSize,
                                @JsonProperty("attemptRetention") final StroomDuration attemptRetention,
                                @JsonProperty("sessionLease") final StroomDuration sessionLease) {
        this.dbConfig = dbConfig;
        this.bindingCache = bindingCache;
        this.learnerStateCache = learnerStateCache;
        this.deferredLearningBatchSize = deferredLearningBatchSize;
        this.attemptRetention = attemptRetention;
        this.sessionLease = sessionLease;
    }

    @Override
    @JsonProperty("db")
    @JsonPropertyDescription("The database holding the runtime state of A26: what serves each learner and "
                             + "its state, its learning sessions, the streams waiting on it, and the "
                             + "cluster's spend.")
    public ShapeshifterAiDbConfig getDbConfig() {
        return dbConfig;
    }

    @JsonProperty("bindingCache")
    @JsonPropertyDescription("What serves each Shapeshifter AI learner, cached per node. Every stream of "
                             + "every learner reads it, and it changes only when something is learned or a "
                             + "person acts on it, which clears the entry on every node.")
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
    @JsonPropertyDescription("How many attempts awaiting the model one pass of the Shapeshifter AI Deferred "
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
}
