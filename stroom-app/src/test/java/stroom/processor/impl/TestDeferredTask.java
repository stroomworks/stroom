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

package stroom.processor.impl;

import stroom.data.shared.StreamTypeNames;
import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.MetaRow;
import stroom.meta.shared.Status;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.api.ProcessorTaskService;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.ProcessorTask;
import stroom.processor.shared.QueryData;
import stroom.processor.shared.TaskStatus;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTestScenarioCreator;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.test.common.util.test.FileSystemTestUtil;
import stroom.util.shared.Severity;
import stroom.util.time.StroomDuration;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import name.falgout.jeffrey.testing.junit.guice.IncludeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 04 §5, phase 1: a task has a third outcome. An element that throws `DeferTaskException` ends its
/// task `DEFERRED` — no output stream, no error stream, no marker on the input — and the task comes back
/// to `CREATED` by the reaper's sweep after the retry delay, or at once by a wake, and then completes. In
/// both task selection modes, since the handler and the reaper serve both.
///
/// The element is [DeferringParser], which stands where Shapeshifter AI's will: it defers each stream
/// the first time and emits a small document the second.
@IncludeModule(DeferringParserModule.class)
class TestDeferredTask extends AbstractCoreIntegrationTest {

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTestScenarioCreator commonTestScenarioCreator;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private ProcessorTaskTestHelper processorTaskTestHelper;
    @Inject
    private ProcessorTaskService processorTaskService;
    @Inject
    private ProcessorTaskDao processorTaskDao;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private MetaService metaService;
    @Inject
    private ProcessorTaskReaper processorTaskReaper;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;
    @Inject
    private ProcessorTaskAvailability processorTaskAvailability;

    private String feed;
    private Meta input;
    private DocRef pipeline;
    private ProcessorFilter filter;

    @BeforeEach
    void setUp() {
        DeferringParser.reset();
        processorConfigProvider.get().setSkipEmptyFilterFetchDuration(StroomDuration.ZERO);
        feed = FileSystemTestUtil.getUniqueTestString();
        storeCreationTool.getOrCreateFeedDoc(feed);
        input = commonTestScenarioCreator.createSample2LineRawFile(feed, StreamTypeNames.RAW_EVENTS);
        pipeline = pipeline();
        filter = filterOver(pipeline);
    }

    @ParameterizedTest(name = "claimTasksOnWorker={0}")
    @ValueSource(booleans = {false, true})
    void aDeferredTaskLeavesNothingBehindAndComesBackAfterTheDelay(final boolean claimTasksOnWorker) {
        processorTaskTestHelper.setClaimTasksOnWorker(claimTasksOnWorker);

        final ProcessorTask task = theOnlyTask();
        final ProcessorResult result = commonTranslationTestHelper.process(task);

        // Deferred, owned by nobody, and nothing written anywhere for the stream.
        assertThat(result.getMarkerCount(Severity.SEVERITIES)).isZero();
        assertThat(taskStatus()).isEqualTo(TaskStatus.DEFERRED);
        assertThat(theTask().getNodeName()).isNull();
        assertThat(childrenOfInput()).isEmpty();
        assertThat(metaService.getMeta(input.getId()).getStatus()).isEqualTo(Status.UNLOCKED);

        // Not claimable while deferred: a fetch finds nothing, in either mode.
        processorTaskAvailability.clear();
        processorTaskTestHelper.createAndQueueTasks();
        assertThat(processorTaskTestHelper.assignTasks(10)).isEmpty();
        assertThat(taskStatus()).isEqualTo(TaskStatus.DEFERRED);

        // Not yet due: the reaper leaves it. Then due: the reaper returns it.
        final StroomDuration delay = processorConfigProvider.get().getDeferredTaskRetryDelay();
        processorTaskReaper.reap(Instant.now());
        assertThat(taskStatus()).isEqualTo(TaskStatus.DEFERRED);
        processorTaskReaper.reap(Instant.now().plus(delay).plusSeconds(1));
        assertThat(taskStatus()).isEqualTo(TaskStatus.CREATED);

        // And the second time through, it completes with its output.
        final ProcessorResult second = commonTranslationTestHelper.process(theOnlyTask());
        assertThat(second.getMarkerCount(Severity.SEVERITIES)).isZero();
        assertThat(taskStatus()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(childrenOfInput())
                .describedAs(everyStream())
                .hasSize(1)
                .allSatisfy(child -> assertThat(child.getTypeName()).isEqualTo(StreamTypeNames.EVENTS));
    }

    @ParameterizedTest(name = "claimTasksOnWorker={0}")
    @ValueSource(booleans = {false, true})
    void aWakeBringsADeferredTaskBackBeforeTheDelay(final boolean claimTasksOnWorker) {
        processorTaskTestHelper.setClaimTasksOnWorker(claimTasksOnWorker);
        commonTranslationTestHelper.process(theOnlyTask());
        assertThat(taskStatus()).isEqualTo(TaskStatus.DEFERRED);

        // A wake names the pipeline; another pipeline's wake leaves the task deferred.
        assertThat(processorTaskDao.wakeDeferredTasks("another-pipeline")).isZero();
        assertThat(taskStatus()).isEqualTo(TaskStatus.DEFERRED);
        assertThat(processorTaskDao.wakeDeferredTasks(pipeline.getUuid())).isEqualTo(1);
        assertThat(taskStatus()).isEqualTo(TaskStatus.CREATED);

        commonTranslationTestHelper.process(theOnlyTask());
        assertThat(taskStatus()).isEqualTo(TaskStatus.COMPLETE);
        assertThat(childrenOfInput()).describedAs(everyStream()).hasSize(1);
    }

    /// A run that defers has replaced nothing, so the output a reprocess would have superseded must stand
    /// until it does. The executor deletes a stream's earlier output from the same pipeline when a task
    /// ends; it must not when the task deferred.
    @ParameterizedTest(name = "claimTasksOnWorker={0}")
    @ValueSource(booleans = {false, true})
    void aReprocessThatDefersLeavesTheEarlierOutputStanding(final boolean claimTasksOnWorker) {
        processorTaskTestHelper.setClaimTasksOnWorker(claimTasksOnWorker);
        commonTranslationTestHelper.process(theOnlyTask());
        processorTaskDao.wakeDeferredTasks(pipeline.getUuid());
        commonTranslationTestHelper.process(theOnlyTask());
        assertThat(standingChildrenOfInput()).hasSize(1);
        final long earlier = standingChildrenOfInput().get(0).getId();

        // A second filter over the same stream and pipeline: its task is a reprocess, and the parser has
        // forgotten the stream, so that task defers.
        final ProcessorFilter again = filterOver(pipeline);
        DeferringParser.reset();
        final ProcessorTask reprocess = theOnlyClaimableTask();
        commonTranslationTestHelper.process(reprocess);
        assertThat(reprocess.getProcessorFilter().getId()).isEqualTo(again.getId());
        assertThat(standingChildrenOfInput())
                .describedAs(everyStream())
                .extracting(Meta::getId)
                .containsExactly(earlier);

        // And when it finally runs, the earlier output is superseded as a reprocess supersedes it.
        processorTaskDao.wakeDeferredTasks(pipeline.getUuid());
        commonTranslationTestHelper.process(theOnlyClaimableTask());
        assertThat(standingChildrenOfInput())
                .describedAs(everyStream())
                .hasSize(1)
                .extracting(Meta::getId)
                .doesNotContain(earlier);
    }

    /// `Source -> TestDeferringParser -> XMLWriter -> StreamAppender`: the smallest pipeline that would write
    /// an output stream if the parser let anything through.
    private DocRef pipeline() {
        final DocRef pipelineRef = pipelineStore.createDocument("Deferring " + feed);
        final PipelineDoc doc = pipelineStore.readDocument(pipelineRef);
        pipelineStore.writeDocument(doc.copy()
                .pipelineData(new PipelineDataBuilder()
                        .addElement(new PipelineElement("Source", "Source"))
                        .addElement(new PipelineElement("Parser", DeferringParser.TYPE))
                        .addElement(new PipelineElement("XML writer", "XMLWriter"))
                        .addElement(new PipelineElement("Stream appender", "StreamAppender"))
                        .addLink("Source", "Parser")
                        .addLink("Parser", "XML writer")
                        .addLink("XML writer", "Stream appender")
                        .addProperty(PipelineDataUtil.createProperty(
                                "Stream appender", "streamType", StreamTypeNames.EVENTS))
                        .build())
                .build());
        return pipelineRef;
    }

    private ProcessorFilter filterOver(final DocRef pipeline) {
        final QueryData queryData = QueryData.builder()
                .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                .expression(ExpressionOperator.builder()
                        .addTextTerm(MetaFields.FEED, Condition.EQUALS, feed)
                        .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                        .build())
                .build();
        return processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(queryData)
                .priority(1)
                .build());
    }

    /// Create and hand out the one task there is, by whichever route the mode uses.
    private ProcessorTask theOnlyTask() {
        // A claiming node caches which filters have work; a test that has just changed that cannot wait
        // for the cache to expire.
        processorTaskAvailability.clear();
        processorTaskTestHelper.createAndQueueTasks();
        final List<ProcessorTask> tasks = processorTaskTestHelper.assignTasks(10);
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    /// Where more than one task exists, the one that can be handed out now.
    private ProcessorTask theOnlyClaimableTask() {
        processorTaskAvailability.clear();
        processorTaskTestHelper.createAndQueueTasks();
        final List<ProcessorTask> tasks = processorTaskTestHelper.assignTasks(10);
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private List<Meta> standingChildrenOfInput() {
        return childrenOfInput().stream()
                .filter(meta -> !Status.DELETED.equals(meta.getStatus()))
                .toList();
    }

    private ProcessorTask theTask() {
        final List<ProcessorTask> tasks = processorTaskService.find(new ExpressionCriteria()).getValues();
        assertThat(tasks).hasSize(1);
        return tasks.get(0);
    }

    private TaskStatus taskStatus() {
        return theTask().getStatus();
    }

    /// Everything written as a child of the input, whatever its status — an output stream, an error
    /// stream, anything.
    private List<Meta> childrenOfInput() {
        return metaService.findRelatedData(input.getId(), true).stream()
                .map(MetaRow::getMeta)
                .filter(meta -> Long.valueOf(input.getId()).equals(meta.getParentMetaId()))
                .toList();
    }

    private String everyStream() {
        return metaService.find(new FindMetaCriteria()).getValues().stream()
                .map(meta -> meta.getId() + ":" + meta.getTypeName() + ":parent=" + meta.getParentMetaId()
                             + ":" + meta.getStatus())
                .toList()
                .toString();
    }
}
