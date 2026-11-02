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
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.shared.Binding;
import stroom.util.entityevent.EntityAction;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.entityevent.EntityEventHandler;
import stroom.util.shared.Clearable;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Optional;

/// A learner's bindings, in front of the rows (design 01 §12 item 8). Every stream a learner meets is
/// served by one of them, and they change only when something is learned, approved or taken back, so
/// reading them from the database on the hot path would be a query per stream per node — at hundreds of
/// threads a node, the first thing to give.
///
/// Held until what writes them says otherwise: every write goes to the rows and then fires an event for
/// the learner's pipeline, which clears that pipeline's entries on *every* node, since the node that
/// learns a binding is rarely the only node serving with it. The expiry in the configuration is a
/// backstop and not the mechanism.
@Singleton
@EntityEventHandler(type = Learner.EVENT_TYPE)
public class CachedLearnerBindings implements LearnerBindings, Clearable, EntityEvent.Handler {

    private static final String CACHE_NAME = "Shapeshifter AI Learner Bindings";

    private final LearnerBindings rows;
    private final EntityEventBus entityEventBus;
    private final LoadingStroomCache<Learner, List<Binding>> cache;

    @Inject
    public CachedLearnerBindings(@Rows final LearnerBindings rows,
                       final CacheManager cacheManager,
                       final EntityEventBus entityEventBus,
                       final Provider<ShapeshifterAiConfig> configProvider) {
        this.rows = rows;
        this.entityEventBus = entityEventBus;
        this.cache = cacheManager.createLoadingCache(
                CACHE_NAME,
                () -> configProvider.get().getBindingCache(),
                // Copied, because the rows hand back a list of their own making and this one is shared
                // by every serving thread on the node until something invalidates it: a caller that
                // sorted or added to what it was given would corrupt the serving of every stream.
                learner -> List.copyOf(rows.forLearner(learner)));
    }

    @Override
    public List<Binding> forLearner(final Learner learner) {
        return cache.get(learner);
    }

    /// From the rows: a binding looked up by its uuid is a person's act on the Supervisor, not a stream.
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
    public void replace(final Learner learner, final String docUuid, final Binding binding) {
        rows.replace(learner, docUuid, binding);
        changed(learner);
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
        final String pipelineUuid = event.getDocRef() == null
                ? null
                : event.getDocRef().getUuid();
        if (pipelineUuid == null) {
            clear();
        } else {
            cache.invalidateEntries((learner, bindings) -> learner.pipelineUuid().equals(pipelineUuid));
        }
    }

    /// What was learned here must be known everywhere: this node's entry goes at once, and the others'
    /// go when the event reaches them.
    private void changed(final Learner learner) {
        cache.invalidate(learner);
        entityEventBus.fire(new EntityEvent(
                new DocRef(Learner.EVENT_TYPE, learner.pipelineUuid()), EntityAction.CLEAR_CACHE));
    }
}
