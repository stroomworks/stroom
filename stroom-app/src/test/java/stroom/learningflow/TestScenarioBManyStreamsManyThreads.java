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
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.MetaRow;
import stroom.meta.shared.Status;
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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests many streams processed by many threads: the same feed and fixtures as
/// `TestScenarioAFromNothingToServed`, but with enough streams arriving at once that races really happen.
/// Several task threads on one node process them while the learning job runs beside them.
///
/// The things that must be safe across nodes (the task claim, the attempt lease, the deferral rows and the
/// wake) are contended here through the one database, exactly as a second node would contend for them.
/// After the backlog has drained, the test asserts that there was one session per learning event, every
/// stream was processed exactly once, there was no partial output and no unexpected error, and no task,
/// attempt or deferral row was left behind. It also samples the invariants while it runs, because a
/// partial output that is later completed, or an output that is later deleted, is a failure the final
/// state would hide.
class TestScenarioBManyStreamsManyThreads extends AbstractCoreIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String LOGONS = "dt,who,where,what\n"
                                         + "2020-06-17T08:00:00.000Z,jim,warehouse,logon\n"
                                         + "2020-06-17T08:10:00.000Z,bob,office,logon\n"
                                         + "2020-06-17T08:12:00.000Z,dave,office,logon\n"
                                         + "2020-06-17T08:14:00.000Z,fred,office,logon\n"
                                         + "2020-06-17T08:15:00.000Z,ann,office,logon\n";
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final Pattern EVENT = Pattern.compile("<Event>");
    private static final int FIRST_WAVE = 12;
    private static final int SECOND_WAVE = 8;
    private static final int THREADS = 4;
    private static final Duration PATIENCE = Duration.ofMinutes(4);

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

    @Test
    void manyStreamsOnManyThreadsAreEachServedOnceWithOneSessionPerLearningEvent() throws Exception {
        // The scripted model answers two learning events. The first wave teaches a transform for logons
        // only. The second wave contains logoffs, so it is deferred and the learner relearns for them.
        // Any other question would fail the script.
        final Script script = script(CSV.configuration(), LOGON_ONLY)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(TRANSLATES_BOTH));
        advisor.set(script);
        final List<Meta> first = new ArrayList<>();
        for (int i = 0; i < FIRST_WAVE; i++) {
            first.add(rawStream(LOGONS));
        }

        final Violations violations = new Violations();
        final AtomicBoolean stop = new AtomicBoolean();
        final ExecutorService threads = Executors.newFixedThreadPool(THREADS + 2);
        final List<Future<?>> running = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            running.add(threads.submit(() -> processUntil(stop, violations)));
        }
        running.add(threads.submit(() -> learnUntil(stop, violations)));
        running.add(threads.submit(() -> sampleUntil(stop, violations)));
        try {
            // The second wave arrives while the first is still draining, as soon as the promotion has
            // served any of it. Every stream in it holds a kind of record the transform has not met.
            awaitAnyComplete(first);
            final List<Meta> second = new ArrayList<>();
            for (int i = 0; i < SECOND_WAVE; i++) {
                second.add(rawStream(MIXED));
            }
            awaitComplete(first);
            awaitComplete(second);
        } finally {
            stop.set(true);
            threads.shutdown();
            assertThat(threads.awaitTermination(1, TimeUnit.MINUTES)).isTrue();
        }
        for (final Future<?> future : running) {
            future.get();
        }
        script.verifyExhausted();
        assertThat(violations.found).describedAs("invariants sampled while it ran").isEmpty();
        assertThat(violations.deferred.size())
                .describedAs("the race was real: streams met the gap together and waited")
                .isGreaterThanOrEqualTo(FIRST_WAVE / 2);

        // One session per learning event.
        final List<Recorded> sessions = attempts.forDocument(document.getUuid(), 100);
        assertThat(sessions).describedAs(String.valueOf(sessions)).hasSize(2);
        assertThat(sessions).allSatisfy(session -> assertThat(session.status()).isEqualTo(AttemptStatus.PROMOTED));
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);

        // Every stream processed exactly once, in full, and nothing left behind.
        final Map<Long, List<ProcessorTask>> tasksByInput = processorTaskService.find(new ExpressionCriteria())
                .getValues()
                .stream()
                .collect(Collectors.groupingBy(ProcessorTask::getMetaId));
        final List<Meta> all = new ArrayList<>(first);
        all.addAll(metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.RAW_EVENTS)).getValues()
                .stream()
                .filter(meta -> !first.contains(meta))
                .toList());
        assertThat(all).hasSize(FIRST_WAVE + SECOND_WAVE);
        for (final Meta input : all) {
            final List<ProcessorTask> tasks = tasksByInput.getOrDefault(input.getId(), List.of());
            assertThat(tasks).describedAs("one task for stream " + input.getId()).hasSize(1);
            assertThat(tasks.get(0).getStatus()).isEqualTo(TaskStatus.COMPLETE);
            final List<Meta> outputs = visibleEventsOf(input);
            assertThat(outputs).describedAs("one output for stream " + input.getId()).hasSize(1);
            final int expected = first.contains(input)
                    ? 5
                    : 6;
            assertThat(EVENT.matcher(data(outputs.get(0))).results().count())
                    .describedAs("the whole stream's events for stream " + input.getId())
                    .isEqualTo(expected);
        }
        assertThat(deferrals.forDocument(document.getUuid())).describedAs("no row unresolved").isEmpty();
        assertThat(attempts.awaiting(10)).isEmpty();
    }

    /// Runs as a task thread until told to stop. Tasks are created and claimed while holding a lock,
    /// because the test helper is not thread-safe, and processed outside the lock.
    private void processUntil(final AtomicBoolean stop, final Violations violations) {
        while (!stop.get()) {
            final List<ProcessorTask> claimed;
            synchronized (processorTaskTestHelper) {
                processorTaskAvailability.clear();
                processorTaskTestHelper.createAndQueueTasks();
                claimed = processorTaskTestHelper.assignTasks(1);
            }
            if (claimed.isEmpty()) {
                pause();
                continue;
            }
            for (final ProcessorTask task : claimed) {
                try {
                    final ProcessorResult result = commonTranslationTestHelper.process(task);
                    if (result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR) > 0) {
                        violations.add("Task for stream " + task.getMetaId() + " logged an error");
                    }
                } catch (final RuntimeException e) {
                    violations.add("Task for stream " + task.getMetaId() + " threw " + e);
                }
            }
        }
    }

    /// Runs the deferred learning job as a node schedules it, starting a new pass whenever the last has
    /// finished, until told to stop.
    private void learnUntil(final AtomicBoolean stop, final Violations violations) {
        while (!stop.get()) {
            try {
                deferredLearning.exec();
            } catch (final RuntimeException e) {
                violations.add("The learning job threw " + e);
            }
            pause();
        }
    }

    /// Samples the invariants while the test runs: a stream whose task is deferred must have no visible
    /// output.
    private void sampleUntil(final AtomicBoolean stop, final Violations violations) {
        while (!stop.get()) {
            try {
                for (final ProcessorTask task : processorTaskService.find(new ExpressionCriteria()).getValues()) {
                    if (task.getStatus() == TaskStatus.DEFERRED) {
                        violations.deferred.add(task.getMetaId());
                        final List<Meta> visible = visibleEventsOf(metaService.getMeta(task.getMetaId()));
                        if (!visible.isEmpty()) {
                            violations.add("Stream " + task.getMetaId() + " is deferred with output visible: "
                                           + visible);
                        }
                    }
                }
            } catch (final RuntimeException e) {
                // A row can change while it is being read. The next sample will read it once settled.
            }
            pause();
        }
    }

    private void awaitComplete(final List<Meta> inputs) throws InterruptedException {
        await(inputs, true);
    }

    private void awaitAnyComplete(final List<Meta> inputs) throws InterruptedException {
        await(inputs, false);
    }

    private void await(final List<Meta> inputs, final boolean all) throws InterruptedException {
        final Instant deadline = Instant.now().plus(PATIENCE);
        while (true) {
            final Map<Long, TaskStatus> status = statusByInput();
            final boolean done = all
                    ? inputs.stream().allMatch(meta -> status.get(meta.getId()) == TaskStatus.COMPLETE)
                    : inputs.stream().anyMatch(meta -> status.get(meta.getId()) == TaskStatus.COMPLETE);
            if (done) {
                return;
            }
            assertThat(Instant.now()).describedAs("the backlog drained in time: " + status).isBefore(deadline);
            Thread.sleep(200);
        }
    }

    private List<Meta> visibleEventsOf(final Meta input) {
        return childrenOf(input).stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName())
                                && !Status.DELETED.equals(meta.getStatus()))
                .toList();
    }

    private static void pause() {
        try {
            Thread.sleep(100);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The violations found by any thread, and the streams seen deferred.
    private static final class Violations {

        private final List<String> found = new CopyOnWriteArrayList<>();
        /// The streams seen deferred, which shows that the race really happened.
        private final Set<Long> deferred = ConcurrentHashMap.newKeySet();

        void add(final String violation) {
            found.add(violation);
        }
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
        // The pack's settings, but with the same flow as `TestScenarioAStepsFourToSix`: the transform step
        // has one candidate before the exhaustion (Admit) step. Here every scripted candidate passes, so
        // the Admit step is not reached.
        learningFlowStore.writeDocument(DemoDocument
                .configure(learningFlowStore.readDocument(docRef).copy())
                // A new document targets the latest installed event-logging schema (4.1.0 on a test
                // node), but this test's canned answers write 3.0.0, so it targets that version.
                .targetSchema(TargetSchemaFixture.eventLogging300(xmlSchemaStore))
                .flow(new LearningFlow(List.of(
                        FlowStep.of(QuestionKind.CHAIN),
                        FlowStep.builder(QuestionKind.CONFIGURE).role(ConfigureRole.PARSER).build(),
                        FlowStep.builder(QuestionKind.CONFIGURE)
                                .role(ConfigureRole.TRANSFORM)
                                .candidates(1)
                                .whenSpentGoTo("admit")
                                .build(),
                        FlowStep.builder(QuestionKind.ADMIT).candidates(1).build()), null, null))
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

    private Meta rawStream(final String data) {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "mixed", ".txt");
            Files.writeString(file, data, StandardCharsets.UTF_8);
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
