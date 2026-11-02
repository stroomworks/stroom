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

package stroom.pipelinefactory.impl;

import stroom.cluster.lock.api.ClusterLockService;
import stroom.docstore.api.DocumentStoreBinder;
import stroom.job.api.ScheduledJobsBinder;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.util.RunnableWrapper;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;

/// Binds the Pipeline Factory document's store, its handlers and its resource (design 06, phase I).
public class PipelineFactoryModule extends AbstractModule {

    @Override
    protected void configure() {
        DocumentStoreBinder.create(binder())
                .bind(PipelineFactoryDoc.TYPE, PipelineFactoryStore.class, PipelineFactoryStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(PipelineFactoryResourceImpl.class);

        ScheduledJobsBinder.create(binder())
                .bindJobTo(PipelineFactoryJob.class, builder -> builder
                        .name("Pipeline Factory")
                        .description("Look through the streams that have arrived for each enabled Pipeline "
                                     + "Factory, and make a pipeline and processor filter for each new "
                                     + "combination of its key's values")
                        .frequencySchedule("1m"));
    }


    // --------------------------------------------------------------------------------


    /// The job runs on one node at a time: two nodes reading the same streams would race to make the same
    /// pipelines, and the record's unique key would leave one of them with a pipeline it never recorded.
    private static class PipelineFactoryJob extends RunnableWrapper {

        private static final String LOCK = "pipeline-factory";

        @Inject
        PipelineFactoryJob(final ClusterLockService clusterLockService, final PipelineFactoryRunner runner) {
            super(() -> clusterLockService.tryLock(LOCK, runner::exec));
        }
    }
}
