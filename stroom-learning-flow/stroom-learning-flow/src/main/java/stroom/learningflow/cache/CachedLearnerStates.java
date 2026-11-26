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

package stroom.learningflow.cache;

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.learningflow.LearningFlowConfig;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerStates;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.OptionalDouble;

/// A per-node cache, in front of the database rows, of the parts of a learner's state that every stream
/// checks: whether it has given up, whether it is marked to learn again, and whether a draft awaits review.
///
/// Every stream checks all three before it is served or deferred, and they change rarely. Without the cache
/// that would be three queries per stream on every node, for an answer that is almost always "none of them".
///
/// The rolling scores are deliberately *not* cached. They are a cluster-wide count that every served stream
/// adds to, so a node's own copy would be wrong as soon as another node served a stream, and caching a value
/// that every stream writes saves nothing.
///
/// Entries are kept until a write invalidates them. Every write goes to the rows and then fires an event for
/// the learner's pipeline, which clears that pipeline's learners on every node.
@Singleton
@EntityEventHandler(type = Learner.EVENT_TYPE)
public class CachedLearnerStates implements LearnerStates, Clearable, EntityEvent.Handler {

    private static final String CACHE_NAME = "Learning Flow Learner State";

    private final LearnerStates rows;
    private final EntityEventBus entityEventBus;
    private final LoadingStroomCache<Learner, Settled> cache;

    @Inject
    public CachedLearnerStates(@Rows final LearnerStates rows,
                               final CacheManager cacheManager,
                               final EntityEventBus entityEventBus,
                               final Provider<LearningFlowConfig> configProvider) {
        this.rows = rows;
        this.entityEventBus = entityEventBus;
        this.cache = cacheManager.createLoadingCache(
                CACHE_NAME,
                () -> configProvider.get().getLearnerStateCache(),
                learner -> new Settled(
                        rows.reasonGivenUp(learner),
                        rows.relearnReason(learner),
                        rows.draftAwaiting(learner)));
    }

    @Override
    public Optional<String> reasonGivenUp(final Learner learner) {
        return cache.get(learner).givenUp();
    }

    @Override
    public Optional<String> relearnReason(final Learner learner) {
        return cache.get(learner).relearn();
    }

    @Override
    public Optional<String> draftAwaiting(final Learner learner) {
        return cache.get(learner).draft();
    }

    @Override
    public void giveUp(final Learner learner, final String docUuid, final String reason) {
        rows.giveUp(learner, docUuid, reason);
        changed(learner);
    }

    @Override
    public void markForRelearning(final Learner learner, final String docUuid, final String reason) {
        rows.markForRelearning(learner, docUuid, reason);
        changed(learner);
    }

    @Override
    public void awaitReview(final Learner learner, final String docUuid, final String bindingUuid) {
        rows.awaitReview(learner, docUuid, bindingUuid);
        changed(learner);
    }

    @Override
    public void reset(final Learner learner) {
        rows.reset(learner);
        changed(learner);
    }

    /// Writes straight to the rows. The rolling score is a cluster-wide count that every served stream adds
    /// to, so it is not cached, and updating it does not affect any cached state.
    @Override
    public OptionalDouble scored(final Learner learner, final String docUuid, final double score,
                                 final int records, final int skipped, final int memory) {
        return rows.scored(learner, docUuid, score, records, skipped, memory);
    }

    /// Reads straight from the rows, for the same reason as [#scored]: the rolling score is a count that
    /// every served stream on any node adds to.
    @Override
    public Optional<Rolling> rolling(final Learner learner) {
        return rows.rolling(learner);
    }

    @Override
    public void clear() {
        cache.clear();
    }

    @Override
    public void onChange(final EntityEvent event) {
        LearnerCacheEvents.onChange(cache, event);
    }

    private void changed(final Learner learner) {
        LearnerCacheEvents.changed(cache, entityEventBus, learner);
    }


    // --------------------------------------------------------------------------------


    /// The cached state of one learner. The three values are loaded together because every stream asks for
    /// all three.
    private record Settled(Optional<String> givenUp, Optional<String> relearn, Optional<String> draft) {

    }
}
