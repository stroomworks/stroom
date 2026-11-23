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

/// The runtime state of A26 as tables: the seams the stage has used against memory since slice 8, bound
/// here to rows so that what a node learns survives its restart and reaches every other node (A41, A42).
///
/// Separate from [LearningFlowDbModule] because these bindings need the application — a cache manager
/// and the entity event bus — and the module that opens the data source is installed long before there is
/// one, in the injector that runs the migrations.
public class LearningFlowDaoModule extends AbstractModule {

    @Override
    protected void configure() {
        super.configure();

        // Said out loud so that an injector without them names what is missing rather than the
        // constructor of a cache several links down.
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
        // One table of marks, and the retention job's view of every table a learner keeps rows in.
        bind(DeletedPipelines.class).to(DeletedPipelinesDao.class);
        bind(KeptLearning.class).to(DeletedPipelinesDao.class);
        // The two the hot path reads for every stream are bound as the rows behind a cache, and the cache
        // is what everything else asks for (design 01 §12 item 8): a learner's bindings read from the database
        // once per stream per node is the first thing to give at volume. Nothing but the cache asks the
        // database, and what writes through it tells every node to let go of its copy.
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
