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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;

/// A per-node cache of each learner's bindings, sitting in front of the database rows.
///
/// Every stream a learner processes is served by one of its bindings, and bindings change only when
/// something is learned, approved or retracted. Reading them from the database on the processing path would
/// cost a query per stream on every node, which at hundreds of processing threads per node would be the
/// first thing to become a bottleneck.
///
/// Entries are kept until a write invalidates them. Every write goes to the rows and then fires an event for
/// the learner's pipeline, which clears that pipeline's entries on *every* node, because the node that
/// learns a binding is rarely the only node serving with it. The expiry set in the configuration is only a
/// backstop.
@Singleton
@EntityEventHandler(type = Learner.EVENT_TYPE)
public class CachedLearnerBindings implements LearnerBindings, Clearable, EntityEvent.Handler {

    private static final String CACHE_NAME = "Learning Flow Learner Bindings";

    private final LearnerBindings rows;
    private final EntityEventBus entityEventBus;
    private final LoadingStroomCache<Learner, List<Binding>> cache;

    @Inject
    public CachedLearnerBindings(@Rows final LearnerBindings rows,
                       final CacheManager cacheManager,
                       final EntityEventBus entityEventBus,
                       final Provider<LearningFlowConfig> configProvider) {
        this.rows = rows;
        this.entityEventBus = entityEventBus;
        this.cache = cacheManager.createLoadingCache(
                CACHE_NAME,
                () -> configProvider.get().getBindingCache(),
                // Copied into an immutable list because the cached list is shared by every serving thread
                // on the node until it is invalidated. A caller that sorted or added to a mutable list would
                // corrupt the bindings every other stream is served with.
                learner -> List.copyOf(rows.forLearner(learner)));
    }

    @Override
    public List<Binding> forLearner(final Learner learner) {
        return cache.get(learner);
    }

    /// Reads straight from the rows, uncached. A lookup by binding UUID comes from a person acting in the
    /// Supervisor, not from stream processing, so it is too rare to be worth caching.
    @Override
    public Optional<Held> byUuid(final String bindingUuid) {
        return rows.byUuid(bindingUuid);
    }

    @Override
    public Binding append(final Learner learner, final String docUuid, final Binding binding) {
        final Binding appended = rows.append(learner, docUuid, binding);
        changed(learner);
        return appended;
    }

    @Override
    public boolean replace(final Learner learner, final String docUuid, final Binding binding) {
        final boolean written = rows.replace(learner, docUuid, binding);
        // Invalidated even if the write was refused. A refused write means another node changed the row
        // first, so this node's cached copy is stale and must be thrown away.
        changed(learner);
        return written;
    }

    @Override
    public void remove(final Learner learner, final String bindingUuid) {
        rows.remove(learner, bindingUuid);
        changed(learner);
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
}
