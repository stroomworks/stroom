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

package stroom.shapeshifter.ai.cache;

import stroom.cache.api.CacheManager;
import stroom.cache.api.LoadingStroomCache;
import stroom.docref.DocRef;
import stroom.shapeshifter.ai.ShapeshifterAiConfig;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerStates;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.OptionalDouble;

/// What a learner has settled into, in front of the rows (design 01 §12 item 8): given up, marked to
/// learn again, or awaiting a person's review. Every stream asks all three before it is served or waits,
/// and they change only when a learner settles — three queries per stream per node, otherwise, for an
/// answer that is almost always "none of them".
///
/// The rolling scores are deliberately *not* cached. They are a count across the cluster (A44's
/// reasoning applied to A23's scores): every served stream adds to them, so a node's own copy would be
/// wrong the moment another node served a stream, and caching a counter that every stream writes saves
/// nothing.
///
/// Held until what writes them says otherwise: every write goes to the rows and then fires an event for
/// the learner's pipeline, which clears that pipeline's learners on every node.
@Singleton
@EntityEventHandler(type = Learner.EVENT_TYPE)
public class CachedLearnerStates implements LearnerStates, Clearable, EntityEvent.Handler {

    private static final String CACHE_NAME = "Shapeshifter AI Learner State";

    private final LearnerStates rows;
    private final EntityEventBus entityEventBus;
    private final LoadingStroomCache<Learner, Settled> cache;

    @Inject
    public CachedLearnerStates(@Rows final LearnerStates rows,
                               final CacheManager cacheManager,
                               final EntityEventBus entityEventBus,
                               final Provider<ShapeshifterAiConfig> configProvider) {
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

    /// Straight to the rows: a count every served stream adds to, which no node may hold its own copy
    /// of, and which changes none of what is cached.
    @Override
    public OptionalDouble scored(final Learner learner, final String docUuid, final double score,
                                 final int records, final int memory) {
        return rows.scored(learner, docUuid, score, records, memory);
    }

    /// Straight to the rows, for the same reason [#scored] is: a count every served stream adds to.
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
        // A pipeline's worth at a time: the event names the pipeline, and a pipeline's learners are few.
        final String pipelineUuid = event.getDocRef() == null
                ? null
                : event.getDocRef().getUuid();
        if (pipelineUuid == null) {
            clear();
        } else {
            cache.invalidateEntries((learner, settled) -> learner.pipelineUuid().equals(pipelineUuid));
        }
    }

    /// An event of the learner's own type, naming its pipeline: a learner's state is a row of the
    /// feature's own, and an event on the pipeline would have every node drop its merged pipeline data
    /// each time a learner was marked.
    private void changed(final Learner learner) {
        cache.invalidate(learner);
        entityEventBus.fire(new EntityEvent(
                new DocRef(Learner.EVENT_TYPE, learner.pipelineUuid()), EntityAction.CLEAR_CACHE));
    }


    // --------------------------------------------------------------------------------


    /// What a learner has settled into: three answers read together, since every stream asks for all
    /// three.
    private record Settled(Optional<String> givenUp, Optional<String> relearn, Optional<String> draft) {

    }
}
