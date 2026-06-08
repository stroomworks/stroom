/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.floormap.impl;

import stroom.analytics.impl.ExecuteNow;
import stroom.analytics.impl.ExecuteNowProviderBinder;
import stroom.analytics.impl.ScheduledExecutorService;
import stroom.analytics.shared.ExecutionSchedule;
import stroom.docstore.api.ContentIndexable;
import stroom.docstore.api.DocumentActionHandlerBinder;
import stroom.event.logging.api.ObjectInfoProviderBinder;
import stroom.explorer.api.ExplorerActionHandler;
import stroom.floormap.shared.FloorMapDoc;
import stroom.importexport.api.ImportExportActionHandler;
import stroom.job.api.ScheduledJobsBinder;
import stroom.util.RunnableWrapper;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;

public class FloorMapModule extends AbstractModule {

    @Override
    protected void configure() {
        ScheduledJobsBinder.create(binder())
                .bindJobTo(ScheduledFloorMapExecutorRunnable.class, builder -> builder
                        .name("Data Generator")
                        .description("Generate data to be fed into the selected feed.")
                        .frequencySchedule("10m")
                        .enabled(false)
                        .enabledOnBootstrap(false)
                        .advanced(true));

        bind(FloorMapStore.class).to(FloorMapStoreImpl.class);

        GuiceUtil.buildMultiBinder(binder(), ExplorerActionHandler.class)
                .addBinding(FloorMapStoreImpl.class);
        GuiceUtil.buildMultiBinder(binder(), ImportExportActionHandler.class)
                .addBinding(FloorMapStoreImpl.class);
        GuiceUtil.buildMultiBinder(binder(), ContentIndexable.class)
                .addBinding(FloorMapStoreImpl.class);

        DocumentActionHandlerBinder.create(binder())
                .bind(FloorMapDoc.TYPE, FloorMapStoreImpl.class);

        // Provide object info to the logging service.
        ObjectInfoProviderBinder.create(binder())
                .bind(FloorMapDoc.class, FloorMapDocObjectInfoProvider.class);

        ExecuteNowProviderBinder.create(binder())
                .bind(FloorMapDoc.TYPE, FloorMapExecuteNow.class);

        RestResourcesBinder.create(binder())
                .bind(FloorMapResourceImpl.class);
    }

    private static class ScheduledFloorMapExecutorRunnable extends RunnableWrapper {

        @Inject
        ScheduledFloorMapExecutorRunnable(final ScheduledExecutorService<FloorMapDoc> scheduledExecutorService,
                                         final ScheduledFloorMapExecutable scheduledFloorMapExecutor) {
            super(() -> scheduledExecutorService.exec(scheduledFloorMapExecutor));
        }
    }

    private static class FloorMapExecuteNow implements ExecuteNow {

        private final ScheduledExecutorService<FloorMapDoc> scheduledExecutorService;
        private final ScheduledFloorMapExecutable scheduledFloorMapExecutor;

        @Inject
        FloorMapExecuteNow(final ScheduledExecutorService<FloorMapDoc> scheduledExecutorService,
                          final ScheduledFloorMapExecutable scheduledFloorMapExecutor) {
            this.scheduledExecutorService = scheduledExecutorService;
            this.scheduledFloorMapExecutor = scheduledFloorMapExecutor;
        }

        @Override
        public void execute(final ExecutionSchedule executionSchedule) {
            scheduledExecutorService.executeNow(executionSchedule, scheduledFloorMapExecutor);
        }
    }
}
