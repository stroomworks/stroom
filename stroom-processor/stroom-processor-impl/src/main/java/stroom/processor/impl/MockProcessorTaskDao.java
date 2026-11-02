/*
 * Copyright 2019 Crown Copyright
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

package stroom.processor.impl;

import stroom.entity.shared.ExpressionCriteria;
import stroom.meta.shared.Meta;
import stroom.processor.api.InclusiveRanges;
import stroom.processor.impl.ProgressMonitor.FilterProgressMonitor;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.ProcessorFilterTracker;
import stroom.processor.shared.ProcessorTask;
import stroom.processor.shared.ProcessorTaskFields;
import stroom.processor.shared.ProcessorTaskSummary;
import stroom.processor.shared.TaskStatus;
import stroom.query.api.ExpressionUtil;
import stroom.query.language.functions.FieldIndex;
import stroom.query.language.functions.ValuesConsumer;
import stroom.util.shared.Clearable;
import stroom.util.shared.NullSafe;
import stroom.util.shared.ResultPage;

import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MockProcessorTaskDao implements ProcessorTaskDao, Clearable {

    /// The deferred tasks the mock queue made, by filter and stream: see [#deferred].
    private final Map<String, ProcessorTask> deferredUnsaved = new ConcurrentHashMap<>();
    private final MockIntCrud<ProcessorTask> dao = new MockIntCrud<>(
            (processorTask, integer) -> processorTask.copy().id(integer).build(),
            processorTask -> (int) processorTask.getId());

    @Override
    public long releaseOwnedTasks(final String nodeName) {
        return releaseTasks(Set.of(nodeName), null, null);
    }

    @Override
    public long reapDeadTasks(final Instant statusOlderThan) {
        return 0;
    }

    @Override
    public int countDeadTasks(final Instant statusOlderThan) {
        return 0;
    }

    @Override
    public int renewTaskHeartbeats(final String nodeName,
                                   final Collection<Long> taskIds,
                                   final long nowMs) {
        return 0;
    }

    private long releaseTasks(final Set<String> releaseForNodes,
                              final Set<String> retainForNodes,
                              final Instant statusOlderThan) {
        final long now = System.currentTimeMillis();
        dao.getMap().values().forEach(task -> {
            if (TaskStatus.CREATED.equals(task.getStatus()) ||
                TaskStatus.PROCESSING.equals(task.getStatus())) {

                boolean release = false;
                if (releaseForNodes != null) {
                    for (final String node : releaseForNodes) {
                        if (node.equals(task.getNodeName())) {
                            release = true;
                        }
                    }

                } else if (retainForNodes != null) {
                    release = true;
                    for (final String node : retainForNodes) {
                        if (node.equals(task.getNodeName())) {
                            release = false;
                        }
                    }
                }

                if (release && statusOlderThan != null) {
                    if (statusOlderThan.toEpochMilli() < task.getStatusTimeMs()) {
                        release = false;
                    }
                }

                if (release) {
                    dao.update(task.copy().status(TaskStatus.CREATED).statusTimeMs(now).nodeName(null).build());
                }
            }
        });
        return 0L;
    }

    @Override
    public int createNewTasks(final ProcessorFilter filter,
                              final ProcessorFilterTracker tracker,
                              final FilterProgressMonitor filterProgressMonitor,
                              final long metaQueryTime,
                              final Map<Meta, InclusiveRanges> metaMap,
                              final Long maxMetaId,
                              final boolean reachedLimit) {
        final long now = System.currentTimeMillis();

        metaMap.forEach((meta, eventRanges) -> {
            final ProcessorTask.Builder builder = ProcessorTask.builder()
                    .version(1)
                    .createTimeMs(now)
                    .status(TaskStatus.CREATED)
                    .startTimeMs(now)
                    .metaId(meta.getId());

            String eventRangeData = null;
            if (eventRanges != null) {
                eventRangeData = eventRanges.rangesToString();
//                eventCount += eventRanges.count();
            }

            if (eventRangeData != null && !eventRangeData.isEmpty()) {
                builder.data(eventRangeData);
            }

            builder.processorFilter(filter);

            dao.create(builder.build());
        });

        return metaMap.size();
    }

    @Override
    public List<ProcessorTask> claimTasks(final int filterId, final String nodeName, final int limit) {
        final long now = System.currentTimeMillis();
        final List<ProcessorTask> claimed = dao.getMap().values()
                .stream()
                .filter(task -> TaskStatus.CREATED.equals(task.getStatus()))
                .filter(task -> Objects.equals(
                        NullSafe.get(task, ProcessorTask::getProcessorFilter, ProcessorFilter::getId),
                        filterId))
                .sorted(Comparator.comparing(ProcessorTask::getId))
                .limit(Math.max(0, limit))
                .toList();
        return claimed
                .stream()
                .map(task -> dao.update(task.copy()
                        .status(TaskStatus.PROCESSING)
                        .statusTimeMs(now)
                        .startTimeMs(now)
                        .nodeName(nodeName)
                        .build()))
                .toList();
    }

    @Override
    public long sweepQueuedTasks(final Instant statusOlderThan) {
        return 0;
    }

    @Override
    public int countTasks(final TaskStatus status) {
        return (int) dao.getMap().values().stream().filter(task -> status.equals(task.getStatus())).count();
    }

    @Override
    public long sweepDeferredTasks(final Instant statusOlderThan) {
        return wake(task -> task.getStatusTimeMs() < statusOlderThan.toEpochMilli());
    }

    @Override
    public synchronized int wakeDeferredTasks(final String pipelineUuid) {
        final List<String> unsaved = deferredUnsaved.entrySet().stream()
                .filter(entry -> isOn(entry.getValue(), pipelineUuid))
                .map(Map.Entry::getKey)
                .toList();
        unsaved.forEach(deferredUnsaved::remove);
        return (int) wake(task -> isOn(task, pipelineUuid)) + unsaved.size();
    }

    /// The deferred tasks of a pipeline's filters, including those the mock queue made without a row: it
    /// builds each task afresh from a filter's tracker, so a task it hands out is told it deferred and is
    /// found nowhere else.
    ///
    /// @param pipelineUuid The pipeline.
    /// @return Its deferred tasks.
    public synchronized List<ProcessorTask> deferred(final String pipelineUuid) {
        final List<ProcessorTask> deferred = new ArrayList<>();
        dao.getMap().values().stream()
                .filter(task -> TaskStatus.DEFERRED.equals(task.getStatus()) && isOn(task, pipelineUuid))
                .forEach(deferred::add);
        deferredUnsaved.values().stream()
                .filter(task -> isOn(task, pipelineUuid))
                .forEach(deferred::add);
        return deferred;
    }

    private static boolean isOn(final ProcessorTask task, final String pipelineUuid) {
        return task.getProcessorFilter() != null
               && Objects.equals(task.getProcessorFilter().getPipelineUuid(), pipelineUuid);
    }

    private static String key(final ProcessorTask task) {
        return NullSafe.get(task.getProcessorFilter(), ProcessorFilter::getId) + ":" + task.getMetaId();
    }

    private long wake(final java.util.function.Predicate<ProcessorTask> due) {
        final long now = System.currentTimeMillis();
        long count = 0;
        for (final ProcessorTask task : new ArrayList<>(dao.getMap().values())) {
            if (TaskStatus.DEFERRED.equals(task.getStatus()) && due.test(task)) {
                dao.update(task.copy().status(TaskStatus.CREATED).statusTimeMs(now).nodeName(null).build());
                count++;
            }
        }
        return count;
    }

    @Override
    public Map<Integer, Long> getTaskAvailability(final Collection<Integer> filterIds) {
        final Set<Integer> wanted = new HashSet<>(filterIds);
        final Map<Integer, Long> availability = new HashMap<>();
        dao.getMap().values().forEach(task -> {
            final Integer filterId = NullSafe.get(task,
                    ProcessorTask::getProcessorFilter,
                    ProcessorFilter::getId);
            if (filterId != null
                && wanted.contains(filterId)
                && TaskStatus.CREATED.equals(task.getStatus())) {
                availability.merge(filterId, task.getId(), Math::min);
            }
        });
        return availability;
    }

    @Override
    public int countTasksForFilter(final int filterId, final TaskStatus status) {
        return 0;
    }

    @Override
    public Map<TaskStatus, Integer> countTasksForFilter(final int filterId, final Set<TaskStatus> statuses) {
        final Map<TaskStatus, Integer> counts = new HashMap<>();
        statuses.forEach(status -> counts.put(status, 0));
        return counts;
    }

    @Override
    public FilterTaskCounts countTasksForFilter(final int filterId,
                                                final String nodeName,
                                                final TaskStatus status) {
        return new FilterTaskCounts(0, 0);
    }


    @Override
    public List<ProcessorTask> queueTasks(final Set<Long> idSet,
                                          final String thisNodeName) {
        return Collections.emptyList();
    }

    @Override
    public int releaseTasks(final Set<Long> idSet, final TaskStatus currentStatus) {
        return 0;
    }

    @Override
    public ResultPage<ProcessorTask> changeTaskStatus(final ExpressionCriteria criteria,
                                                      final String nodeName,
                                                      final TaskStatus status,
                                                      final Long startTime,
                                                      final Long endTime) {
        final ResultPage<ProcessorTask> tasks = find(criteria);
        List<ProcessorTask> values = tasks.getValues();
        values = values.stream().map(task ->
                changeTaskStatus(task, nodeName, status, startTime, endTime)).toList();
        return new ResultPage<>(values, tasks.getPageResponse());
    }

    @Override
    public ProcessorTask changeTaskStatus(final ProcessorTask processorTask,
                                          final String nodeName,
                                          final TaskStatus status,
                                          final Long startTime,
                                          final Long endTime) {
        final ProcessorTask changed = processorTask.copy()
                .nodeName(nodeName)
                .status(status)
                .statusTimeMs(System.currentTimeMillis())
                .startTimeMs(startTime)
                .endTimeMs(endTime)
                .build();
        // Kept, as the table keeps it: a wake finds the deferred tasks by their status. A task the mock queue
        // made has no row, so a deferred one is kept beside the rows until it is woken or runs again.
        if (dao.getMap().containsKey((int) changed.getId())) {
            dao.update(changed);
        } else if (TaskStatus.DEFERRED.equals(status)) {
            deferredUnsaved.put(key(changed), changed);
        } else {
            deferredUnsaved.remove(key(changed));
        }
        return changed;
    }

    @Override
    public ResultPage<ProcessorTask> find(final ExpressionCriteria criteria) {
        final List<ProcessorTask> list = dao
                .getMap()
                .values()
                .stream()
                .filter(task -> {
                    final List<String> pipelineUuids = ExpressionUtil.values(criteria.getExpression(),
                            ProcessorTaskFields.PIPELINE.getFldName());
                    if (pipelineUuids != null) {
                        if (!pipelineUuids.contains(task.getProcessorFilter().getProcessor().getPipelineUuid())) {
                            return false;
                        }
                    }
                    final List<String> taskStatus = ExpressionUtil.values(criteria.getExpression(),
                            ProcessorTaskFields.STATUS.getFldName());
                    if (taskStatus != null) {
                        return taskStatus.contains(task.getStatus().getDisplayValue());
                    }
                    return true;
                })
                .toList();

        return ResultPage.createCriterialBasedList(list, criteria);
    }

    @Override
    public ResultPage<ProcessorTaskSummary> findSummary(final ExpressionCriteria criteria) {
        return null;
    }

    @Override
    public void search(final ExpressionCriteria criteria,
                       final FieldIndex fieldIndex,
                       final ValuesConsumer consumer) {

    }

    @Override
    public void clear() {
        deferredUnsaved.clear();
        dao.clear();
    }

    @Override
    public int logicalDeleteByProcessorId(final int processorId) {
        return 0;
    }

    @Override
    public int logicalDeleteByProcessorFilterId(final int processorFilterId) {
        return 0;
    }

    @Override
    public int logicalDeleteForDeletedProcessorFilters(final Instant deleteThreshold) {
        return 0;
    }

    @Override
    public int physicallyDeleteOldTasks(final Instant deleteThreshold) {
        return 0;
    }

    @Override
    public List<ExistingCreatedTask> findExistingCreatedTasks(final long minTaskId,
                                                              final int filterId,
                                                              final int limit) {
        return null;
    }

}
