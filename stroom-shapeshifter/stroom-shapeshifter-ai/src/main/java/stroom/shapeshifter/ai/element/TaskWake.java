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

package stroom.shapeshifter.ai.element;

import stroom.processor.api.ProcessorTaskService;
import stroom.shapeshifter.ai.stage.Wake;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/// The wake in a node (design 04 §5.3, design 06 §4.3): the deferred tasks of every filter on the
/// pipeline back to `CREATED` through the processor task service.
@Singleton
public class TaskWake implements Wake {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TaskWake.class);

    private final ProcessorTaskService processorTaskService;

    @Inject
    public TaskWake(final ProcessorTaskService processorTaskService) {
        this.processorTaskService = processorTaskService;
    }

    @Override
    public int wake(final String pipelineUuid) {
        final int woken = processorTaskService.wakeDeferredTasks(pipelineUuid);
        LOGGER.debug(() -> LogUtil.message("Woke {} task(s) of pipeline {}", woken, pipelineUuid));
        return woken;
    }
}
