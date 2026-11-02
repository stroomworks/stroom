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

package stroom.test;

import stroom.processor.api.ProcessorFilterService;
import stroom.processor.impl.MockProcessorTaskDao;
import stroom.processor.mock.MockProcessorTaskService;
import stroom.processor.shared.ProcessorTask;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/// The mock task service with one thing real: a wake, over the mock task DAO. The mock harness runs
/// tasks it creates itself, and a promotion in Shapeshifter AI wakes its pipeline's deferred tasks
/// through the task service (design 06 A129), so a harness whose wake did nothing would leave every
/// deferred stream asleep.
public class WakingMockProcessorTaskService extends MockProcessorTaskService {

    private final MockProcessorTaskDao processorTaskDao;
    private final ProcessorFilterService processorFilterService;

    @Inject
    public WakingMockProcessorTaskService(final MockProcessorTaskDao processorTaskDao,
                                          final ProcessorFilterService processorFilterService) {
        this.processorTaskDao = processorTaskDao;
        this.processorFilterService = processorFilterService;
    }

    @Override
    public int wakeDeferredTasks(final String pipelineUuid) {
        // The mock queue offers each stream once, from the filter's tracker onward, whatever the task
        // rows say: a wake here is each filter's tracker wound back to the oldest stream woken on it, so
        // that the next assignment offers it again.
        final List<ProcessorTask> deferred = processorTaskDao.deferred(pipelineUuid);
        final int woken = processorTaskDao.wakeDeferredTasks(pipelineUuid);
        final Map<Integer, Long> oldestByFilter = deferred.stream()
                .collect(Collectors.toMap(task -> task.getProcessorFilter().getId(), ProcessorTask::getMetaId,
                        Math::min));
        oldestByFilter.forEach((filterId, oldest) -> processorFilterService.fetch(filterId)
                .ifPresent(filter -> filter.getProcessorFilterTracker().setMinMetaId(
                        Math.min(filter.getProcessorFilterTracker().getMinMetaId(), oldest))));
        return woken;
    }
}
