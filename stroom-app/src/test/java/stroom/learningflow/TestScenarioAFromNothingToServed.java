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

package stroom.learningflow;

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.api.Source;
import stroom.data.store.api.SourceUtil;
import stroom.data.store.api.Store;
import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.element.DeferredLearning;
import stroom.learningflow.extraction.DataSplitterCompiler;
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.extraction.JsonStep;
import stroom.learningflow.extraction.XmlFragmentStep;
import stroom.learningflow.learning.StepRunner;
import stroom.learningflow.pack.DemoContentPack;
import stroom.learningflow.pack.DemoDocument;
import stroom.learningflow.scenario.AdvisorHolder;
import stroom.learningflow.scenario.QuestionMatcher;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.scenario.Structure;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.MetaRow;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.api.ProcessorTaskService;
import stroom.processor.impl.ProcessorConfig;
import stroom.processor.impl.ProcessorTaskAvailability;
import stroom.processor.impl.ProcessorTaskTestHelper;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.ProcessorTask;
import stroom.processor.shared.QueryData;
import stroom.processor.shared.TaskStatus;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.test.common.util.test.FileSystemTestUtil;
import stroom.util.shared.Severity;
import stroom.util.time.StroomDuration;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the whole path for a new feed, from nothing learned to streams served, against real processor
/// tasks and a real database.
///
/// Two streams arrive on a feed nothing has been learned for. Both tasks end `DEFERRED`, one session is
/// opened rather than two, and neither stream gets an output or error stream. The worker runs the session
/// with the scripted model, and a fragment is written for the feed and type and promoted. The promotion
/// wakes both tasks, which run again and complete, each writing six events. At no point is any output
/// written just to make a stream wait.
///
/// The feed is mixed CSV with logons and logoffs, and the script's stylesheet translates both, so that this
/// test is about the flow of tasks and sessions and not about what the transform can do. The case where a
/// later stream brings a kind of record the transform has not met is tested by
/// `TestScenarioAStepsFourToSix`.
class TestScenarioAFromNothingToServed extends AbstractCoreIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final Pattern EVENT = Pattern.compile("<Event>");

    /// The learner for the test pipeline, identified by the pipeline and its Learning Flow element.
    private Learner learner;

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private ProcessorTaskTestHelper processorTaskTestHelper;
    @Inject
    private ProcessorTaskService processorTaskService;
    @Inject
    private ProcessorTaskAvailability processorTaskAvailability;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private XmlSchemaStore xmlSchemaStore;
    @Inject
    private MetaService metaService;
    @Inject
    private Store store;
    @Inject
    private LearningFlowStore learningFlowStore;
    @Inject
    private LearnerBindings learnerBindings;
    @Inject
    private Attempts attempts;
    @Inject
    private Deferrals deferrals;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;
    @Inject
    private DeferredLearning deferredLearning;

    private String feed;
    private DocRef document;
    private ProcessorFilter filter;

    @BeforeEach
    void setUp() {
        processorConfigProvider.get().setSkipEmptyFilterFetchDuration(StroomDuration.ZERO);
        feed = FileSystemTestUtil.getUniqueTestString();
        storeCreationTool.getOrCreateFeedDoc(feed);
        document = document();
        filter = filterOver(pipeline(document));
    }

    /// Runs with `claimTasksOnWorker` both off and on, as a pipeline with a Learning Flow element must work
    /// with either way of handing tasks to workers. Off is the default. With the master node's queue, this
    /// covers a learning feed end to end: its streams are deferred, learning promotes, the wake returns the
    /// tasks to `CREATED`, the master hands them out, and they complete.
    @ParameterizedTest(name = "claimTasksOnWorker={0}")
    @ValueSource(booleans = {false, true})
    void twoStreamsOfAnUnseenFeedWaitOnOneSessionAndAreServedWhenItPromotes(
            final boolean claimTasksOnWorker) {
        processorConfigProvider.get().setClaimTasksOnWorker(claimTasksOnWorker);
        // Step 1: both tasks end deferred, one session is open, and nothing is written for either stream.
        final Script silent = Script.of();
        advisor.set(silent);
        final Meta first = rawStream();
        final Meta second = rawStream();
        for (final ProcessorTask task : tasks(2)) {
            final ProcessorResult result = commonTranslationTestHelper.process(task);
            assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR))
                    .describedAs("nothing is logged to the error receiver for a stream that is merely early")
                    .isZero();
            assertThat(result.getWritten()).isZero();
        }
        assertThat(silent.asked()).describedAs("no model call inside a processing task").isEmpty();
        assertThat(statusByInput()).containsEntry(first.getId(), TaskStatus.DEFERRED)
                .containsEntry(second.getId(), TaskStatus.DEFERRED);
        assertThat(childrenOf(first)).describedAs("no output stream and no error stream (§8.6)").isEmpty();
        assertThat(childrenOf(second)).isEmpty();
        final List<Recorded> open = attempts.forDocument(document.getUuid(), 10);
        assertThat(open).describedAs("one session, not two").hasSize(1);
        assertThat(open.get(0).status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(open.get(0).attempt().inputId()).isEqualTo(first.getId());
        final List<Deferral> waiting = deferrals.forDocument(document.getUuid());
        assertThat(waiting).extracting(Deferral::inputId).containsExactly(first.getId(), second.getId());
        assertThat(waiting).allSatisfy(row -> {
            assertThat(row.failedAgainst()).describedAs("no binding to have failed against").isNull();
        });
        assertThat(learnerBindings.forLearner(learner)).isEmpty();

        // Step 2: the worker runs the session on the stream that opened it. The promotion writes the
        // fragment and wakes both tasks, which return to CREATED before the retry delay has passed.
        final Script script = script(CSV.configuration(), TRANSLATES_BOTH);
        advisor.set(script);
        deferredLearning.exec();
        script.verifyExhausted();
        assertThat(attempts.byId(open.get(0).id()).orElseThrow().status()).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);
        assertThat(statusByInput()).containsEntry(first.getId(), TaskStatus.CREATED)
                .containsEntry(second.getId(), TaskStatus.CREATED);

        // Step 3: both tasks run again and complete, each stream's output has six events, and the
        // deferral rows are removed.
        advisor.set(silent);
        for (final ProcessorTask task : tasks(2)) {
            final ProcessorResult result = commonTranslationTestHelper.process(task);
            assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(statusByInput()).containsEntry(first.getId(), TaskStatus.COMPLETE)
                .containsEntry(second.getId(), TaskStatus.COMPLETE);
        for (final Meta input : List.of(first, second)) {
            // One events stream each. The element also logs its decision at INFO to the pipeline's error
            // receiver, which Stroom keeps as a separate stream. That stream holds no errors, as the marker
            // counts above show, so only the events streams are checked here.
            final List<Meta> outputs = childrenOf(input).stream()
                    .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                    .toList();
            assertThat(outputs).hasSize(1);
            assertThat(EVENT.matcher(data(outputs.get(0))).results().count())
                    .describedAs("six events for six records, the logoffs among them")
                    .isEqualTo(6);
            assertThat(data(outputs.get(0))).contains("<Action>Logoff</Action>");
        }
        assertThat(deferrals.forDocument(document.getUuid())).describedAs("nothing waits").isEmpty();
    }

    /// Creates and queues any tasks due, assigns them, and checks that exactly `count` were assigned.
    private List<ProcessorTask> tasks(final int count) {
        processorTaskAvailability.clear();
        processorTaskTestHelper.createAndQueueTasks();
        final List<ProcessorTask> tasks = processorTaskTestHelper.assignTasks(10);
        assertThat(tasks).hasSize(count);
        return tasks;
    }

    private Map<Long, TaskStatus> statusByInput() {
        return processorTaskService.find(new ExpressionCriteria()).getValues().stream()
                .collect(Collectors.toMap(ProcessorTask::getMetaId, ProcessorTask::getStatus, (a, b) -> b));
    }

    private List<Meta> childrenOf(final Meta input) {
        return metaService.findRelatedData(input.getId(), true).stream()
                .map(MetaRow::getMeta)
                .filter(meta -> Long.valueOf(input.getId()).equals(meta.getParentMetaId()))
                .toList();
    }

    private String data(final Meta meta) {
        try (final Source source = store.openSource(meta.getId())) {
            return SourceUtil.readString(source);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Creates a document configured like the demo content pack's, but with no model, because the
    /// scripted advisor supplies the answers.
    private DocRef document() {
        final LearningFlowDoc shipped = DemoContentPack.document();
        final DocRef docRef = learningFlowStore.createDocument(shipped.getName() + " " + feed);
        learningFlowStore.writeDocument(DemoDocument
                .configure(learningFlowStore.readDocument(docRef).copy())
                // A new document targets the latest installed event-logging schema (4.1.0 on a test
                // node), but this test's canned answers write 3.0.0, so it targets that version.
                .targetSchema(TargetSchemaFixture.eventLogging300(xmlSchemaStore))
                .build());
        return docRef;
    }

    private DocRef pipeline(final DocRef document) {
        final DocRef pipelineRef = pipelineStore.createDocument(DemoContentPack.pipelineName(feed));
        final PipelineDoc pipeline = pipelineStore.readDocument(pipelineRef);
        pipelineStore.writeDocument(pipeline.copy()
                .pipelineData(DemoContentPack.pipelineData(document))
                .build());
        learner = new Learner(pipelineRef.getUuid(), DemoContentPack.STAGE_ELEMENT);
        return pipelineRef;
    }

    private ProcessorFilter filterOver(final DocRef pipeline) {
        return processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, Condition.EQUALS, feed)
                                .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                                .build())
                        .build())
                .priority(1)
                .build());
    }

    private List<StepRunner> runners() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, ErrorReceiverProxy::new);
        return List.of(new DataSplitterStep(compiler), new JsonStep(), new XmlFragmentStep(), new StandInXsltStep());
    }

    private Script script(final String splitter, final String stylesheet) {
        return Script.of().structure(new Structure(runners(), splitter, stylesheet))
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(splitter))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(stylesheet));
    }

    private Meta rawStream() {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "mixed", ".txt");
            Files.writeString(file, MIXED, StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(feed, file, null);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.RAW_EVENTS))
                .getValues()
                .stream()
                .max((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElseThrow();
    }
}
