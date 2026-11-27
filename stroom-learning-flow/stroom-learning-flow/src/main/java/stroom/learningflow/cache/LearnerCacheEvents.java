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

import stroom.cache.api.StroomCache;
import stroom.docref.DocRef;
import stroom.learningflow.stage.Learner;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;

/// Helpers that keep the learner-keyed caches ([CachedLearnerBindings], [CachedLearnerStates]) consistent
/// across the cluster.
///
/// A write invalidates the learner's entry on this node and fires an event naming the learner's pipeline;
/// the event then clears that pipeline's learners on every node. The event uses the learner's own event type
/// rather than the pipeline type, because a pipeline event would also make every node discard its cached
/// merged pipeline data each time a learner changed.
final class LearnerCacheEvents {

    private LearnerCacheEvents() {
    }

    /// Handles a learner change event by clearing the entries it names. An event naming a pipeline clears
    /// every learner of that pipeline (a pipeline has few learners, so this costs little); an event naming
    /// no pipeline clears the whole cache.
    ///
    /// @param cache The cache to clear entries from.
    /// @param event The event received, whose doc ref holds the pipeline UUID, if any.
    /// @param <V>   The type of value the cache holds for a learner.
    static <V> void onChange(final StroomCache<Learner, V> cache, final EntityEvent event) {
        final String pipelineUuid = event.getDocRef() == null
                ? null
                : event.getDocRef().getUuid();
        if (pipelineUuid == null) {
            cache.clear();
        } else {
            cache.invalidateEntries((learner, value) -> learner.pipelineUuid().equals(pipelineUuid));
        }
    }

    /// Called after a learner's rows have been written. This node's entry is invalidated at once, and an
    /// event is fired so that every other node invalidates its entries when the event reaches it.
    ///
    /// @param cache          The cache to invalidate the learner in.
    /// @param entityEventBus The bus the cluster-wide clear-cache event is fired on.
    /// @param learner        The learner whose rows were written.
    /// @param <V>            The type of value the cache holds for a learner.
    static <V> void changed(final StroomCache<Learner, V> cache,
                            final EntityEventBus entityEventBus,
                            final Learner learner) {
        cache.invalidate(learner);
        entityEventBus.fire(new EntityEvent(
                new DocRef(Learner.EVENT_TYPE, learner.pipelineUuid()), EntityAction.CLEAR_CACHE));
    }
}
