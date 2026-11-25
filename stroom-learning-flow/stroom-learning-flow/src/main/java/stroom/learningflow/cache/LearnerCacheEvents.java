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

/// How a cache keyed by learner is kept true across the cluster: a write invalidates the learner here and
/// fires an event of the learner's own type naming its pipeline, and the event clears that pipeline's
/// learners on every node.
///
/// The learner's own type rather than the pipeline's: a learner's rows are the feature's own, and an event
/// on the pipeline would have every node drop its merged pipeline data each time a learner changed.
final class LearnerCacheEvents {

    private LearnerCacheEvents() {
    }

    /// Clears what an event names: a pipeline's learners, a pipeline at a time since a pipeline's learners
    /// are few, or everything where it names no pipeline.
    ///
    /// @param cache The cache.
    /// @param event The event.
    /// @param <V>   What the cache holds for a learner.
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

    /// What changed here must be known everywhere: this node's entry goes at once, and the others' go
    /// when the event reaches them.
    ///
    /// @param cache          The cache.
    /// @param entityEventBus Where the event is fired.
    /// @param learner        The learner whose rows were written.
    /// @param <V>            What the cache holds for a learner.
    static <V> void changed(final StroomCache<Learner, V> cache,
                            final EntityEventBus entityEventBus,
                            final Learner learner) {
        cache.invalidate(learner);
        entityEventBus.fire(new EntityEvent(
                new DocRef(Learner.EVENT_TYPE, learner.pipelineUuid()), EntityAction.CLEAR_CACHE));
    }
}
