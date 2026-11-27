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

package stroom.learningflow.impl.db;

import stroom.cache.api.CacheManager;
import stroom.learningflow.cache.CachedLearnerBindings;
import stroom.learningflow.cache.CachedLearnerStates;
import stroom.learningflow.cache.Rows;
import stroom.learningflow.element.KeptLearning;
import stroom.learningflow.quality.Volumes;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.CorpusIndex;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearnerStates;
import stroom.learningflow.stage.Learners;
import stroom.learningflow.stage.LearningPipelines;
import stroom.learningflow.stage.Outputs;
import stroom.learningflow.stage.Snapshots;
import stroom.learningflow.stage.Spend;
import stroom.util.entityevent.EntityEvent;
import stroom.util.entityevent.EntityEventBus;
import stroom.util.guice.GuiceUtil;
import stroom.util.shared.Clearable;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

/// The Guice module that binds the Learning Flow's state interfaces to their database implementations, so
/// that what a node learns survives a restart and is shared with every other node. Tests bind the same
/// interfaces to in-memory implementations instead.
///
/// It is separate from [LearningFlowDbModule] because these bindings need application services (a cache
/// manager and the entity event bus), while that module is installed earlier, in the injector that runs the
/// database migrations before the application exists.
public class LearningFlowDaoModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();

        // Declared explicitly so that an injector without them reports what is missing, rather than
        // failing in the constructor of a cache several dependencies away.
        requireBinding(CacheManager.class);
        requireBinding(EntityEventBus.class);

        bind(Attempts.class).to(AttemptsDao.class);
        bind(Guidance.class).to(GuidanceDao.class);
        bind(Spend.class).to(SpendDao.class);
        bind(Outputs.class).to(OutputsDao.class);
        bind(LearningPipelines.class).to(LearningPipelinesDao.class);
        bind(Deferrals.class).to(DeferralsDao.class);
        bind(Learners.class).to(LearnersDao.class);
        bind(CorpusIndex.class).to(CorpusIndexDao.class);
        bind(Snapshots.class).to(SnapshotsDao.class);
        bind(Volumes.class).to(VolumesDao.class);
        // One class implements both: the marks on deleted pipelines, and the retention job's access to
        // every table that holds a learner's rows.
        bind(DeletedPipelines.class).to(DeletedPipelinesDao.class);
        bind(KeptLearning.class).to(DeletedPipelinesDao.class);
        // Bindings and learner states are read for every stream, so they are cached. The DAOs are bound
        // with the @Rows annotation for the caches to use, and everything else gets the cached versions.
        // Reading a learner's bindings from the database for every stream on every node would not scale.
        // A write through a cache tells every node to evict its copy.
        bind(LearnerBindings.class).annotatedWith(Rows.class).to(LearnerBindingsDao.class);
        bind(LearnerStates.class).annotatedWith(Rows.class).to(LearnerStatesDao.class);
        bind(LearnerBindings.class).to(CachedLearnerBindings.class).in(Scopes.SINGLETON);
        bind(LearnerStates.class).to(CachedLearnerStates.class).in(Scopes.SINGLETON);
        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(CachedLearnerBindings.class)
                .addBinding(CachedLearnerStates.class);
        GuiceUtil.buildMultiBinder(binder(), Clearable.class)
                .addBinding(CachedLearnerBindings.class)
                .addBinding(CachedLearnerStates.class);
    }
}
