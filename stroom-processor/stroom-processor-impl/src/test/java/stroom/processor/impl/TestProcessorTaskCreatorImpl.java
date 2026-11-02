/*
 * Copyright 2025 Crown Copyright
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

import stroom.cluster.lock.api.ClusterLockService;
import stroom.meta.api.MetaService;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.Status;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.shared.Processor;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.ProcessorFilterTracker;
import stroom.processor.shared.ProcessorFilterTrackerStatus;
import stroom.processor.shared.QueryData;
import stroom.processor.shared.TaskStatus;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.common.v2.EventSearch;
import stroom.query.common.v2.ExpressionValidationException;
import stroom.security.api.SecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.SimpleTaskContextFactory;
import stroom.util.shared.UserRef;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestProcessorTaskCreatorImpl {

    private static final String PROFILE = "profile1";
    private static final String OTHER_PROFILE = "profile2";

    @Mock
    private ProcessorFilterService processorFilterService;
    @Mock
    private ProcessorFilterTrackerDao processorFilterTrackerDao;
    @Mock
    private ProcessorTaskDao processorTaskDao;
    @Mock
    private ExecutorProvider executorProvider;
    @Mock
    private MetaService metaService;
    @Mock
    private EventSearch eventSearch;
    @Mock
    private SecurityContext securityContext;
    @Mock
    private ClusterLockService clusterLockService;
    @Mock
    private PrioritisedFilters prioritisedFilters;
    @Mock
    private ProcessorProfileCache processorProfileCache;

    private final ProcessorConfig processorConfig = new ProcessorConfig();

    private ProcessorTaskCreatorImpl taskCreator;

    @BeforeEach
    void setUp() {
        // Run the locked work, the executor and the run-as user inline so the test is deterministic.
        Mockito.doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(clusterLockService).tryLock(any(), any(Runnable.class));
        when(executorProvider.get(any())).thenReturn(Runnable::run);
        Mockito.doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(securityContext).asUser(any(UserRef.class), any(Runnable.class));
        // Every filter we load still exists and is unchanged.
        when(processorFilterService.fetch(anyInt())).thenAnswer(invocation ->
                Optional.ofNullable(filtersById.get(invocation.getArgument(0, Integer.class))));

        taskCreator = new ProcessorTaskCreatorImpl(
                processorFilterService,
                processorFilterTrackerDao,
                processorTaskDao,
                executorProvider,
                new SimpleTaskContextFactory(),
                () -> processorConfig,
                metaService,
                eventSearch,
                securityContext,
                clusterLockService,
                prioritisedFilters,
                processorProfileCache,
                new FilterFetchBackoff());
    }

    // --------------------------------------------------------------------------------
    // Which filters a creation run looks at
    // --------------------------------------------------------------------------------

    @Test
    void busyProfileDoesNotStopTasksBeingCreatedForAnotherProfile() {
        // The first filter alone uses up a whole budget, but its tasks are no use to the nodes of the second
        // filter's profile, so the second filter must still be considered.
        final ProcessorFilter busy = givenFilter(1, PROFILE);
        final ProcessorFilter other = givenFilter(2, OTHER_PROFILE);
        givenFilters(busy, other);
        givenExistingCreatedTasks(busy, processorConfig.getTasksToCreate());

        taskCreator.exec();

        verify(processorFilterService).fetch(other.getId());
    }

    /**
     * Note that the run stopping altogether once every profile has spent its budget is not observable here, as
     * a filter of a spent profile is passed over either way. {@link TestTaskCreationBudgets} covers when that
     * happens.
     */
    @Test
    void furtherFiltersOfAProfileAreNotLoadedOnceItsBudgetIsSpent() {
        final ProcessorFilter first = givenFilter(1, PROFILE);
        final ProcessorFilter second = givenFilter(2, OTHER_PROFILE);
        final ProcessorFilter third = givenFilter(3, PROFILE);
        givenFilters(first, second, third);
        givenExistingCreatedTasks(first, processorConfig.getTasksToCreate());
        givenExistingCreatedTasks(second, processorConfig.getTasksToCreate());

        taskCreator.exec();

        verify(processorFilterService, never()).fetch(third.getId());
    }

    /// Design 06 A109: a deferred task counts against its own filter's allowance, but it is no node's to
    /// claim, so it spends nothing of the run's budget its profile shares: a learner's backlog must not
    /// stop the profile's other feeds being given tasks.
    @Test
    void deferredTasksDoNotSpendTheBudgetTheProfileShares() {
        final ProcessorFilter supervised = givenFilter(1, PROFILE);
        final ProcessorFilter other = givenFilter(2, PROFILE);
        givenFilters(supervised, other);
        givenTasks(supervised, 0, processorConfig.getTasksToCreate() * 2);

        taskCreator.exec();

        verify(processorFilterService).fetch(other.getId());
    }

    /// A filter whose allowance went on deferred tasks has spent it, rather than going on making tasks
    /// that will only defer: here the run's budget, which bounds a filter by default.
    @Test
    void aFilterWhoseTasksAreDeferredMakesNoMore() {
        final ProcessorFilter supervised = givenStreamFilter(givenFilter(1, null));
        givenFilters(supervised);
        givenTasks(supervised, 1, processorConfig.getTasksToCreate() - 1);

        taskCreator.exec();

        verify(metaService, never()).getMaxId();
    }

    /// A filter that holds fewer tasks than its allowance, none of them deferred, goes on as before.
    @Test
    void aFilterWithNoneDeferredCreatesAsBefore() {
        final ProcessorFilter plain = givenStreamFilter(givenFilter(1, null));
        givenFilters(plain);
        givenTasks(plain, processorConfig.getTasksToCreate() - 1, 0);

        taskCreator.exec();

        verify(metaService).getMaxId();
    }

    /**
     * A profile with nothing to do never uses up its budget, so the run never stops early because of it.
     * Filters that are backing off from polls that created nothing must therefore be skipped without being
     * loaded, or every run would load every filter.
     */
    @Test
    void backedOffFilterIsSkippedWithoutBeingLoaded() {
        final ProcessorFilter backedOff = givenFilter(1, null);
        givenNonProducingPoll(backedOff);
        givenFilters(backedOff);

        taskCreator.exec();

        verify(processorFilterService, never()).fetch(backedOff.getId());
    }

    @Test
    void filterThatIsDueAPollIsLoaded() {
        final ProcessorFilter due = givenFilter(1, null);
        givenFilters(due);

        taskCreator.exec();

        verify(processorFilterService).fetch(due.getId());
    }

    @Test
    void testSanitise_removeTerms() {
        final ExpressionOperator operator = ExpressionOperator.builder()
                .enabled(true)
                .addTerm(ExpressionTerm.builder()
                        .field(MetaFields.FEED)
                        .enabled(true)
                        .condition(Condition.EQUALS)
                        .value("FEED1")
                        .build())
                .addTextTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                .addTerm(ExpressionTerm.builder()
                        .enabled(true)
                        .field(MetaFields.TYPE)
                        .condition(Condition.NOT_EQUALS)
                        .value("Events")
                        .build())
                .addOperator(ExpressionOperator.builder()
                        .op(Op.OR)
                        .enabled(true)
                        .addTerm(ExpressionTerm.builder()
                                .field(MetaFields.PIPELINE_NAME)
                                .enabled(true)
                                .condition(Condition.EQUALS)
                                .value("foo")
                                .build())
                        .build())
                .addOperator(ExpressionOperator.builder()
                        .op(Op.OR)
                        .enabled(true)
                        .addTextTerm(MetaFields.STATUS, Condition.EQUALS, Status.UNLOCKED.getDisplayValue())
                        .addTextTerm(MetaFields.STATUS, Condition.EQUALS, Status.LOCKED.getDisplayValue())
                        .build())
                .build();
        final ExpressionOperator operator2 = ProcessorTaskCreatorImpl.sanitiseAndValidateExpression(operator);
        assertThat(operator2)
                .isNotEqualTo(operator);

        assertThat(operator.containsField(
                MetaFields.FEED.getFldName(),
                MetaFields.TYPE.getFldName(),
                MetaFields.PIPELINE_NAME.getFldName(),
                MetaFields.STATUS.getFldName()))
                .isTrue();
        assertThat(operator2.containsField(MetaFields.STATUS.getFldName()))
                .isFalse();
        assertThat(operator.containsField(
                MetaFields.FEED.getFldName(),
                MetaFields.TYPE.getFldName(),
                MetaFields.PIPELINE_NAME.getFldName()))
                .isTrue();

        assertThat(operator2)
                .isEqualTo(ExpressionOperator.builder()
                        .enabled(true)
                        .addTerm(ExpressionTerm.builder()
                                .field(MetaFields.FEED)
                                .enabled(true)
                                .condition(Condition.EQUALS)
                                .value("FEED1")
                                .build())
                        .addTerm(ExpressionTerm.builder()
                                .enabled(true)
                                .field(MetaFields.TYPE)
                                .condition(Condition.NOT_EQUALS)
                                .value("Events")
                                .build())
                        .addOperator(ExpressionOperator.builder()
                                .op(Op.OR)
                                .enabled(true)
                                .addTerm(ExpressionTerm.builder()
                                        .field(MetaFields.PIPELINE_NAME)
                                        .enabled(true)
                                        .condition(Condition.EQUALS)
                                        .value("foo")
                                        .build())
                                .build())
                        .addOperator(ExpressionOperator.builder()
                                .op(Op.OR)
                                .enabled(true)
                                .build())
                        .build());
    }

    @Test
    void testSanitise_noChange() {
        final ExpressionOperator operator = ExpressionOperator.builder()
                .enabled(true)
                .addTerm(ExpressionTerm.builder()
                        .field(MetaFields.FEED)
                        .enabled(true)
                        .condition(Condition.EQUALS)
                        .value("FEED1")
                        .build())
                .addTerm(ExpressionTerm.builder()
                        .enabled(true)
                        .field(MetaFields.TYPE)
                        .condition(Condition.NOT_EQUALS)
                        .value("Events")
                        .build())
                .addOperator(ExpressionOperator.builder()
                        .op(Op.OR)
                        .enabled(true)
                        .addTerm(ExpressionTerm.builder()
                                .field(MetaFields.PIPELINE_NAME)
                                .enabled(true)
                                .condition(Condition.EQUALS)
                                .value("foo")
                                .build())
                        .build())
                .build();
        final ExpressionOperator operator2 = ProcessorTaskCreatorImpl.sanitiseAndValidateExpression(operator);
        assertThat(operator2)
                .isSameAs(operator);
    }

    @Test
    void testSanitise_unknownField() {
        final ExpressionOperator operator = ExpressionOperator.builder()
                .enabled(true)
                .addTerm(ExpressionTerm.builder()
                        .field("foo")
                        .enabled(true)
                        .condition(Condition.EQUALS)
                        .value("bar")
                        .build())
                .build();

        Assertions.assertThatThrownBy(() ->
                        ProcessorTaskCreatorImpl.sanitiseAndValidateExpression(operator))
                .isInstanceOf(ExpressionValidationException.class)
                .hasMessageContaining("field")
                .hasMessageContaining("foo");
    }

    // --------------------------------------------------------------------------------

    private final java.util.Map<Integer, ProcessorFilter> filtersById = new java.util.HashMap<>();

    private ProcessorFilter givenFilter(final int id, final String profileName) {
        final ProcessorFilter filter = ProcessorFilter.builder()
                .id(id)
                .version(1)
                .priority(10 - id)
                .enabled(true)
                .deleted(false)
                .profileName(profileName)
                .uuid(UUID.randomUUID().toString())
                .runAsUser(UserRef.builder().uuid(UUID.randomUUID().toString()).build())
                .processorFilterTracker(ProcessorFilterTracker.builder()
                        .status(ProcessorFilterTrackerStatus.CREATED)
                        .build())
                .processor(Processor.builder()
                        .id(id)
                        .enabled(true)
                        .deleted(false)
                        .build())
                .build();
        filtersById.put(id, filter);
        return filter;
    }

    private void givenFilters(final ProcessorFilter... filters) {
        when(prioritisedFilters.get()).thenReturn(List.of(filters));
    }

    /**
     * Give the filter enough tasks already sitting in the database to use up its profile's budget.
     */
    private void givenExistingCreatedTasks(final ProcessorFilter filter, final int count) {
        givenTasks(filter, count, 0);
    }

    /// The tasks a filter holds, waiting and deferred, as the one count the creator asks for (design 06
    /// A109).
    private void givenTasks(final ProcessorFilter filter, final int waiting, final int deferred) {
        when(processorTaskDao.countTasksForFilter(eq(filter.getId()),
                eq(Set.of(TaskStatus.CREATED, TaskStatus.DEFERRED))))
                .thenReturn(Map.of(TaskStatus.CREATED, waiting, TaskStatus.DEFERRED, deferred));
    }

    /// The filter over the stream store: one that goes on to make tasks asks the store how far it reaches.
    private ProcessorFilter givenStreamFilter(final ProcessorFilter filter) {
        final ProcessorFilter streamFilter = filter.copy()
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, Condition.EQUALS, "DOOR-ACCESS")
                                .build())
                        .build())
                .build();
        filtersById.put(streamFilter.getId(), streamFilter);
        return streamFilter;
    }

    /**
     * Make the filter look like it has just been polled without creating anything, so it is backing off.
     */
    private void givenNonProducingPoll(final ProcessorFilter filter) {
        final long nowMs = System.currentTimeMillis();
        filter.getProcessorFilterTracker().setLastPollMs(nowMs);
        filter.getProcessorFilterTracker().setLastPollTaskCount(0);
        filter.getProcessorFilterTracker().setNextPollMs(
                nowMs + processorConfig.getSkipNonProducingFiltersMaxDuration().toMillis());
    }
}
