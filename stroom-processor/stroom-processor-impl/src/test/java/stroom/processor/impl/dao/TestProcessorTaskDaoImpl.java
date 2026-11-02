/*
 * Copyright 2022 Crown Copyright
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

package stroom.processor.impl.dao;

import stroom.entity.shared.ExpressionCriteria;
import stroom.processor.impl.ProgressMonitor;
import stroom.processor.impl.ProgressMonitor.FilterProgressMonitor;
import stroom.processor.shared.Processor;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.ProcessorFilterTracker;
import stroom.processor.shared.ProcessorTask;
import stroom.processor.shared.ProcessorTaskFields;
import stroom.processor.shared.TaskStatus;
import stroom.query.api.datasource.QueryField;
import stroom.query.language.functions.FieldIndex;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static stroom.processor.impl.db.jooq.tables.Processor.PROCESSOR;
import static stroom.processor.impl.db.jooq.tables.ProcessorFilter.PROCESSOR_FILTER;
import static stroom.processor.impl.db.jooq.tables.ProcessorTask.PROCESSOR_TASK;

class TestProcessorTaskDaoImpl extends AbstractProcessorTest {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestProcessorTaskDaoImpl.class);

    Processor processor1;
    Processor processor2;
    Processor processor3;
    ProcessorFilter processorFilter1a;
    ProcessorFilter processorFilter1b;
    ProcessorFilter processorFilter2;
    ProcessorFilter processorFilter3;

    @Test
    void testReleaseOwnedTasks() {
        assertThat(getProcessorCount(null)).isZero();
        assertThat(countTasks()).isZero();
        assertThat(countOwned(NODE1)).isZero();
        assertThat(countOwned(NODE2)).isZero();

        processor1 = createProcessor();

        assertThat(getProcessorCount(null)).isOne();

        processorFilter1a = createProcessorFilter(processor1);
        assertThat(getProcessorFilterCount(null)).isOne();

        createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED);

        assertThat(countTasks()).isEqualTo(3);
        assertThat(countOwned(NODE1)).isEqualTo(3);

        processorTaskDao.releaseOwnedTasks(NODE1);

        assertThat(countTasks()).isEqualTo(3);
        assertThat(countOwned(NODE1)).isZero();
        assertThat(countOwned(null)).isEqualTo(3);

        createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE2, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE2, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE2, FEED);

        assertThat(countTasks()).isEqualTo(6);
        assertThat(countOwned(NODE1)).isZero();
        assertThat(countOwned(NODE2)).isEqualTo(3);
        assertThat(countOwned(null)).isEqualTo(3);

        processorTaskDao.releaseOwnedTasks(NODE1);

        assertThat(countTasks()).isEqualTo(6);
        assertThat(countOwned(NODE1)).isZero();
        assertThat(countOwned(NODE2)).isEqualTo(3);
        assertThat(countOwned(null)).isEqualTo(3);
    }

    /**
     * Design 04 §5.2. A deferred task is one an element asked for back later. The sweep returns those
     * deferred longer ago than the threshold to CREATED with no node and a bumped version, and leaves
     * the rest — younger deferrals and every other status — exactly as they were.
     */
    @Test
    void testSweepDeferredTasks() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);
        final Instant now = Instant.now();
        final long due = createProcessorTask(
                processorFilter1a, TaskStatus.DEFERRED, null, FEED, now.minus(Duration.ofMinutes(6)));
        final long notYet = createProcessorTask(
                processorFilter1a, TaskStatus.DEFERRED, null, FEED, now.minus(Duration.ofMinutes(4)));
        final long processing = createProcessorTask(
                processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED, now.minus(Duration.ofMinutes(6)));
        final long created = createProcessorTask(
                processorFilter1a, TaskStatus.CREATED, null, FEED, now.minus(Duration.ofMinutes(6)));
        final int dueVersion = getTaskVersion(due);

        assertThat(processorTaskDao.countTasks(TaskStatus.DEFERRED)).isEqualTo(2);

        final long swept = processorTaskDao.sweepDeferredTasks(now.minus(Duration.ofMinutes(5)));

        assertThat(swept).isEqualTo(1);
        assertThat(processorTaskDao.countTasks(TaskStatus.DEFERRED)).isEqualTo(1);
        assertThat(getTaskStatus(due)).isEqualTo(TaskStatus.CREATED);
        assertThat(getTaskNodeId(due)).isNull();
        assertThat(getTaskVersion(due)).isEqualTo(dueVersion + 1);
        assertThat(getTaskStatusTimeMs(due)).isGreaterThanOrEqualTo(now.toEpochMilli());
        assertThat(getTaskStatus(notYet)).isEqualTo(TaskStatus.DEFERRED);
        assertThat(getTaskStatus(processing)).isEqualTo(TaskStatus.PROCESSING);
        assertThat(getTaskStatus(created)).isEqualTo(TaskStatus.CREATED);
    }

    /// Design 06 A109. The tasks a filter holds are counted in one query, a count for each status asked
    /// for: not those being processed, nor another filter's.
    @Test
    void testCountTasksForFilterInStatuses() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);
        processorFilter1b = createProcessorFilter(processor1);
        final Instant now = Instant.now();
        createProcessorTask(processorFilter1a, TaskStatus.CREATED, null, FEED, now);
        createProcessorTask(processorFilter1a, TaskStatus.DEFERRED, null, FEED, now);
        createProcessorTask(processorFilter1a, TaskStatus.DEFERRED, null, FEED, now);
        createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED, now);
        createProcessorTask(processorFilter1b, TaskStatus.DEFERRED, null, FEED, now);

        assertThat(processorTaskDao.countTasksForFilter(processorFilter1a.getId(),
                Set.of(TaskStatus.CREATED, TaskStatus.DEFERRED)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(TaskStatus.CREATED, 1, TaskStatus.DEFERRED, 2));
        assertThat(processorTaskDao.countTasksForFilter(processorFilter1b.getId(),
                Set.of(TaskStatus.CREATED, TaskStatus.DEFERRED)))
                .describedAs("a status with none is counted as none")
                .containsExactlyInAnyOrderEntriesOf(Map.of(TaskStatus.CREATED, 0, TaskStatus.DEFERRED, 1));
    }

    /**
     * Design 04 §5.3, design 06 §4.3. A wake returns the deferred tasks of every filter on a pipeline,
     * now, whatever their age — a reprocess filter's as well as the one that feeds it; a task of another
     * pipeline's filter, and a task that is not deferred, are left alone.
     */
    @Test
    void testWakeDeferredTasks() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);
        processorFilter1b = createProcessorFilter(processor1);
        final Processor processor2 = createProcessor();
        final ProcessorFilter otherPipelines = createProcessorFilter(processor2);
        final Instant now = Instant.now();
        final long deferred = createProcessorTask(processorFilter1a, TaskStatus.DEFERRED, null, FEED, now, 1L);
        final long alsoDeferred = createProcessorTask(processorFilter1a, TaskStatus.DEFERRED, null, FEED, now, 2L);
        final long secondFilter = createProcessorTask(processorFilter1b, TaskStatus.DEFERRED, null, FEED, now, 1L);
        final long otherPipeline = createProcessorTask(otherPipelines, TaskStatus.DEFERRED, null, FEED, now, 1L);
        final long notDeferred = createProcessorTask(processorFilter1a, TaskStatus.COMPLETE, NODE1, FEED, now, 3L);

        final int woken = processorTaskDao.wakeDeferredTasks(processor1.getPipelineUuid());

        assertThat(woken).isEqualTo(3);
        assertThat(getTaskStatus(deferred)).isEqualTo(TaskStatus.CREATED);
        assertThat(getTaskStatus(alsoDeferred)).isEqualTo(TaskStatus.CREATED);
        assertThat(getTaskStatus(secondFilter))
                .describedAs("every filter on the pipeline, not only the one that feeds it")
                .isEqualTo(TaskStatus.CREATED);
        assertThat(getTaskStatus(otherPipeline)).isEqualTo(TaskStatus.DEFERRED);
        assertThat(getTaskStatus(notDeferred)).isEqualTo(TaskStatus.COMPLETE);
        assertThat(processorTaskDao.wakeDeferredTasks("no-such-pipeline")).isZero();
    }

    /**
     * What the statements that already exist do with the new status, held to rather than assumed
     * (design 04 §5.2): the dead-task reap leaves a deferred task alone however old it is, and deleting
     * a filter takes its deferred tasks with it.
     */
    @Test
    void testDeferredTasksAreNotReapedButAreDeletedWithTheirFilter() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);
        final Instant now = Instant.now();
        final long deferred = createProcessorTask(
                processorFilter1a, TaskStatus.DEFERRED, null, FEED, now.minus(Duration.ofHours(1)));

        processorTaskDao.reapDeadTasks(now.minus(Duration.ofMinutes(10)));
        assertThat(getTaskStatus(deferred)).isEqualTo(TaskStatus.DEFERRED);

        processorTaskDao.logicalDeleteByProcessorFilterId(processorFilter1a.getId());
        assertThat(getTaskStatus(deferred)).isEqualTo(TaskStatus.DELETED);
    }

    /**
     * gh-5699. The reaper's condition: a PROCESSING task whose heartbeat has gone un-renewed past
     * the lease is dead and goes back to CREATED; anything else is left alone. The version bump is
     * the fence that stops the original owner writing over it afterwards.
     */
    @Test
    void testReapDeadTasks() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);

        final Instant now = Instant.now();
        final Instant stale = now.minus(20, ChronoUnit.MINUTES);
        final Instant fresh = now.minus(1, ChronoUnit.MINUTES);

        final long dead = createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED, stale);
        final long alive = createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED, fresh);
        final long queued = createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE1, FEED, stale);
        final long complete = createProcessorTask(processorFilter1a, TaskStatus.COMPLETE, NODE1, FEED, stale);
        final int deadVersionBefore = getTaskVersion(dead);

        assertThat(processorTaskDao.reapDeadTasks(now.minus(10, ChronoUnit.MINUTES))).isEqualTo(1);

        assertThat(getTaskStatus(dead)).isEqualTo(TaskStatus.CREATED);
        assertThat(getTaskNodeId(dead))
                .describedAs("a reaped task must be unowned so any node can claim it")
                .isNull();
        assertThat(getTaskVersion(dead))
                .describedAs("the version bump is what fences the original owner")
                .isEqualTo(deadVersionBefore + 1);

        assertThat(getTaskStatus(alive))
                .describedAs("a heartbeat within the lease means the node is alive")
                .isEqualTo(TaskStatus.PROCESSING);
        assertThat(getTaskNodeId(alive)).isNotNull();
        assertThat(getTaskStatus(queued))
                .describedAs("only PROCESSING rows are dead task candidates")
                .isEqualTo(TaskStatus.QUEUED);
        assertThat(getTaskStatus(complete)).isEqualTo(TaskStatus.COMPLETE);
    }

    /**
     * gh-5699. A failed version check means we lost the lease, so the write is abandoned rather
     * than forced - forcing it is exactly how a half dead node stamps COMPLETE over a task another
     * node now owns.
     */
    @Test
    void testChangeTaskStatusAbandonsOnLostLease() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);

        final Instant now = Instant.now();
        final long taskId = createProcessorTask(
                processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED, now.minus(20, ChronoUnit.MINUTES));

        final ProcessorTask taskAsOwned = processorTaskDao.find(new ExpressionCriteria())
                .getValues()
                .getFirst();
        assertThat(taskAsOwned.getId()).isEqualTo(taskId);

        // The reaper takes the task from us: version bumps, so our copy is now stale.
        assertThat(processorTaskDao.reapDeadTasks(now.minus(10, ChronoUnit.MINUTES))).isEqualTo(1);

        // Our belated attempt to complete it must be abandoned...
        final ProcessorTask result = processorTaskDao.changeTaskStatus(
                taskAsOwned, NODE1, TaskStatus.COMPLETE, now.toEpochMilli(), now.toEpochMilli());

        assertThat(result).isNull();
        // ...leaving the row as the reaper set it, not stamped COMPLETE.
        assertThat(getTaskStatus(taskId)).isEqualTo(TaskStatus.CREATED);
        assertThat(getTaskNodeId(taskId)).isNull();

        // A write with the current version still succeeds - abandonment is about lost leases,
        // not a general write freeze.
        final ProcessorTask current = processorTaskDao.find(new ExpressionCriteria())
                .getValues()
                .getFirst();
        final ProcessorTask updated = processorTaskDao.changeTaskStatus(
                current, NODE1, TaskStatus.PROCESSING, now.toEpochMilli(), null);
        assertThat(updated).isNotNull();
        assertThat(getTaskStatus(taskId)).isEqualTo(TaskStatus.PROCESSING);
    }

    @Test
    void testLogicalDeleteByProcessorId() {
        assertThat(getProcessorCount(null))
                .isEqualTo(0);

        processor1 = createProcessor();
        processor2 = createProcessor();

        processorFilter1a = createProcessorFilter(processor1);
        createTasksForFilter(processorFilter1a);

        processorFilter1b = createProcessorFilter(processor1);
        createTasksForFilter(processorFilter1b);

        processorFilter2 = createProcessorFilter(processor2);
        createTasksForFilter(processorFilter2);

        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(15);

        dumpProcessorTaskTable();

        final int count = processorTaskDao.logicalDeleteByProcessorId(processor1.getId());
        assertThat(count).isEqualTo(8);

        dumpProcessorTaskTable();

        // No change to row counts as they have been logically deleted
        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(15);

        assertThat(getProcessorCount(PROCESSOR.DELETED.eq(true)))
                .isEqualTo(0);
        assertThat(getProcessorFilterCount(PROCESSOR_FILTER.DELETED.eq(true)))
                .isEqualTo(0);
        // logically deletes the CREATED, QUEUED and ASSIGNED for each of two filters on one processor
        assertThat(getProcessorTaskCount(PROCESSOR_TASK.STATUS.eq(TaskStatus.DELETED.getPrimitiveValue())))
                .isEqualTo(8);
    }

    private void createTasksForFilter(final ProcessorFilter filter) {
        createProcessorTask(filter, TaskStatus.CREATED, null, FEED);
        createProcessorTask(filter, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(filter, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(filter, TaskStatus.PROCESSING, NODE1, FEED);
        createProcessorTask(filter, TaskStatus.COMPLETE, NODE1, FEED);
    }

    @Test
    void testLogicalDeleteByProcessorFilterId() {
        assertThat(getProcessorCount(null))
                .isEqualTo(0);

        processor1 = createProcessor();
        processor2 = createProcessor();

        processorFilter1a = createProcessorFilter(processor1);
        createTasksForFilter(processorFilter1a);

        processorFilter1b = createProcessorFilter(processor1);
        createTasksForFilter(processorFilter1b);

        processorFilter2 = createProcessorFilter(processor2);
        createTasksForFilter(processorFilter2);

        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(15);

        dumpProcessorTaskTable();

        final int count = processorTaskDao.logicalDeleteByProcessorFilterId(processorFilter1a.getId());
        assertThat(count).isEqualTo(4);

        dumpProcessorTaskTable();

        // No change to row counts as they have been logically deleted
        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(15);

        assertThat(getProcessorCount(PROCESSOR.DELETED.eq(true)))
                .isEqualTo(0);
        assertThat(getProcessorFilterCount(PROCESSOR_FILTER.DELETED.eq(true)))
                .isEqualTo(0);
        // logically deletes the CREATED, QUEUED and ASSIGNED for one processor
        assertThat(getProcessorTaskCount(PROCESSOR_TASK.STATUS.eq(TaskStatus.DELETED.getPrimitiveValue())))
                .isEqualTo(4);
    }

    @Test
    void testLogicalDeleteForDeletedProcessorFilters() {
        assertThat(getProcessorCount(null))
                .isEqualTo(0);

        processor1 = createProcessor();
        processor2 = createProcessor();

        processorFilter1a = createProcessorFilter(processor1);
        createProcessorTask(processorFilter1a, TaskStatus.CREATED, NODE1, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED);

        processorFilter1a = processorFilter1a.copy()
                .deleted(true)
                .updateTimeMs(Instant.now().toEpochMilli())
                .build();
        processorFilterDao.update(processorFilter1a);

        processorFilter1b = createProcessorFilter(processor1);
        createProcessorTask(processorFilter1b, TaskStatus.CREATED, NODE1, FEED);
        createProcessorTask(processorFilter1b, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter1b, TaskStatus.PROCESSING, NODE1, FEED);

        processorFilter2 = createProcessorFilter(processor2);
        createProcessorTask(processorFilter2, TaskStatus.CREATED, NODE1, FEED);
        createProcessorTask(processorFilter2, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter2, TaskStatus.PROCESSING, NODE1, FEED);

        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(9);

        assertThat(getProcessorFilterCount(PROCESSOR_FILTER.DELETED.eq(true)))
                .isEqualTo(1);

        dumpProcessorTaskTable();

        final Instant threshold = Instant.now().plus(1, ChronoUnit.DAYS);

        processorTaskDao.logicalDeleteForDeletedProcessorFilters(threshold);

        dumpProcessorTaskTable();

        // No change to row counts as they have been logically deleted
        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(9);

        assertThat(getProcessorCount(PROCESSOR.DELETED.eq(true)))
                .isEqualTo(0);
        assertThat(getProcessorFilterCount(PROCESSOR_FILTER.DELETED.eq(true)))
                .isEqualTo(1);
        assertThat(getProcessorTaskCount(PROCESSOR_TASK.STATUS.eq(TaskStatus.DELETED.getPrimitiveValue())))
                .isEqualTo(3);
    }

    void setup() {
        assertThat(getProcessorCount(null))
                .isEqualTo(0);

        processor1 = createProcessor();
        processor2 = createProcessor();

        processorFilter1a = createProcessorFilter(processor1);
        createProcessorTask(processorFilter1a, TaskStatus.CREATED, NODE1, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter1a, TaskStatus.PROCESSING, NODE1, FEED);

        processorFilter1b = createProcessorFilter(processor1);
        createProcessorTask(processorFilter1b, TaskStatus.CREATED, NODE1, FEED);
        createProcessorTask(processorFilter1b, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter1b, TaskStatus.PROCESSING, NODE1, FEED);
        createProcessorTask(processorFilter1b, TaskStatus.COMPLETE, NODE1, FEED);
        createProcessorTask(processorFilter1b, TaskStatus.DELETED, NODE1, FEED);
    }

    @Test
    void testPhysicallyDeleteOldTasks() {
        setup();

        // These two are older than the threshold time, the rest are not
        createProcessorTask(processorFilter1b,
                TaskStatus.COMPLETE,
                NODE1,
                FEED,
                Instant.now().minus(2, ChronoUnit.DAYS));
        createProcessorTask(
                processorFilter1b,
                TaskStatus.DELETED,
                NODE1,
                FEED,
                Instant.now().minus(2, ChronoUnit.DAYS));

        processorFilter2 = createProcessorFilter(processor2);
        createProcessorTask(processorFilter2, TaskStatus.CREATED, NODE1, FEED);
        createProcessorTask(processorFilter2, TaskStatus.QUEUED, NODE1, FEED);
        createProcessorTask(processorFilter2, TaskStatus.PROCESSING, NODE1, FEED);

        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(13);

        dumpProcessorTaskTable();

        final Instant threshold = Instant.now().minus(1, ChronoUnit.DAYS);

        processorTaskDao.physicallyDeleteOldTasks(threshold);

        dumpProcessorTaskTable();

        assertThat(getProcessorCount(null))
                .isEqualTo(2);
        assertThat(getProcessorFilterCount(null))
                .isEqualTo(3);
        assertThat(getProcessorTaskCount(null))
                .isEqualTo(13 - 2);

        assertThat(getProcessorCount(PROCESSOR.DELETED.eq(true)))
                .isEqualTo(0);
        assertThat(getProcessorFilterCount(PROCESSOR_FILTER.DELETED.eq(true)))
                .isEqualTo(0);
        assertThat(getProcessorTaskCount(PROCESSOR_TASK.STATUS.eq(TaskStatus.DELETED.getPrimitiveValue())))
                .isEqualTo(1);
    }

    @Test
    void testTrackerDoesNotMoveBackwards() {
        processor1 = createProcessor();
        processorFilter1a = createProcessorFilter(processor1);

        // A poll that creates no tasks moves the tracker on to just past the max meta id it was given.
        assertThat(pollWithNoTasks(processorFilter1a, 99L).getMinMetaId())
                .isEqualTo(100);

        // Simulate a filter that has part processed the events within a stream.
        final ProcessorFilterTracker tracker = fetchTracker(processorFilter1a);
        tracker.setMinEventId(5L);
        processorFilterTrackerDao.update(tracker);

        // A poll bounded by a lower max meta id must leave the tracker alone. Winding it back would
        // re-scan meta we have already created tasks for, and there is no unique constraint on
        // (filter, meta) to stop the duplicates.
        ProcessorFilterTracker result = pollWithNoTasks(processorFilter1a, 49L);
        assertThat(result.getMinMetaId()).isEqualTo(100);
        assertThat(result.getMinEventId()).isEqualTo(5);

        // The same max meta id is not greater either, so it must not reset the event position within
        // the stream we are part way through.
        result = pollWithNoTasks(processorFilter1a, 99L);
        assertThat(result.getMinMetaId()).isEqualTo(100);
        assertThat(result.getMinEventId()).isEqualTo(5);

        // A higher max meta id still moves the tracker on, and starts the new stream from its first event.
        result = pollWithNoTasks(processorFilter1a, 149L);
        assertThat(result.getMinMetaId()).isEqualTo(150);
        assertThat(result.getMinEventId()).isZero();
    }

    /**
     * Create tasks for a filter that has no meta to create tasks for, returning the persisted tracker.
     */
    private ProcessorFilterTracker pollWithNoTasks(final ProcessorFilter filter,
                                                   final long maxMetaId) {
        final FilterProgressMonitor filterProgressMonitor = new ProgressMonitor(1)
                .logFilter(filter, 0);
        // Re-fetch the tracker as each poll would, so that its version matches the DB.
        final int createdTasks = processorTaskDao.createNewTasks(
                filter,
                fetchTracker(filter),
                filterProgressMonitor,
                System.currentTimeMillis(),
                Map.of(),
                maxMetaId,
                false);
        assertThat(createdTasks).isZero();
        return fetchTracker(filter);
    }

    private ProcessorFilterTracker fetchTracker(final ProcessorFilter filter) {
        return processorFilterTrackerDao
                .fetch(filter.getProcessorFilterTracker().getId())
                .orElseThrow();
    }

    @Test
    void testSearch() {
        setup();

        final List<QueryField> fields = ProcessorTaskFields.getFields();
        assertThat(fields.size()).isEqualTo(18);

        for (final QueryField field : fields) {
            final FieldIndex fieldIndex = new FieldIndex();
            fieldIndex.create(field.getFldName());

            final AtomicInteger count = new AtomicInteger();
            processorTaskDao.search(new ExpressionCriteria(), fieldIndex, values -> {
                count.incrementAndGet();
                assertThat(values.length).isEqualTo(1);
            });
            assertThat(count.get()).isEqualTo(8);
        }
    }
}
