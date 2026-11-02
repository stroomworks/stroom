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

package stroom.shapeshifter;

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.api.Source;
import stroom.data.store.api.SourceUtil;
import stroom.data.store.api.Store;
import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
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
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.DeferredLearning;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.extraction.JsonStep;
import stroom.shapeshifter.ai.extraction.XmlFragmentStep;
import stroom.shapeshifter.ai.learning.StepRunner;
import stroom.shapeshifter.ai.pack.DemoContentPack;
import stroom.shapeshifter.ai.pack.DemoDocument;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.QuestionMatcher;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.scenario.Structure;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 04 §8, scenario 16 (its first half) and §3, *streams of any size*: **a serving run writes as it
/// goes and leaves nothing when it defers.**
///
/// A binding is learned that translates logons and leaves anything else as an event the schema refuses.
/// A stream of six records, two of them logoffs, is then served as it is read: the fragment runs over
/// the stream, each record of its output is judged as it is written, and the two records before the
/// first logoff reach the pipeline's writer before the third stops the run. The task ends deferred, and
/// what the writer had made of those two records is discarded by the task — a stream that waits leaves
/// nothing anyone can see. The worker relearns on the held stream, the promotion wakes the task, and the
/// stream is served in full.
///
/// What this test cannot show is memory: that nothing larger than a record is held is the gate's
/// construction, read in `RecordGate`, not a thing a six-record stream can measure.
class TestScenario16AServingRunWritesAsItGoes extends AbstractCoreIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final Pattern EVENT = Pattern.compile("<Event>");

    /// The learner the test's pipeline is: its Shapeshifter AI element (design 06 phase J slice 2b).
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
    private MetaService metaService;
    @Inject
    private Store store;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
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
        // A filter over a supervised pipeline needs the node to claim its own tasks (design 04 §7).
        processorConfigProvider.get().setClaimTasksOnWorker(true);
        feed = FileSystemTestUtil.getUniqueTestString();
        storeCreationTool.getOrCreateFeedDoc(feed);
        document = document();
        filter = filterOver(pipeline(document));
    }

    @Test
    void aStreamWithARecordShortOfTheBarIsDiscardedAsItWaitsAndServedWhenTheTransformMoves() {
        // A binding that translates logons alone, learned on a stream of logons: task, worker, task.
        advisor.set(Script.of());
        final Meta logons = rawStream(CSV.input());
        commonTranslationTestHelper.process(tasks(1).get(0));
        final Script learning = script(CSV.configuration(), LOGON_ONLY);
        advisor.set(learning);
        deferredLearning.exec();
        learning.verifyExhausted();
        advisor.set(Script.of());
        commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(statusByInput()).containsEntry(logons.getId(), TaskStatus.COMPLETE);

        // The mixed stream: two records reach the writer, the third stops the run, the task defers.
        final Meta mixed = rawStream(MIXED);
        final ProcessorResult held = commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(held.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR))
                .describedAs(childrenOf(mixed).stream()
                        .map(meta -> meta.getTypeName() + ":" + meta.getStatus())
                        .toList()
                        .toString())
                .isZero();
        assertThat(statusByInput()).containsEntry(mixed.getId(), TaskStatus.DEFERRED);
        assertThat(visibleChildrenOf(mixed))
                .describedAs("what the writer made of the records before the short one is discarded (§8.6)")
                .isEmpty();
        final Learner learner = new Learner(filter.getPipelineUuid(), DemoContentPack.STAGE_ELEMENT);
        final Deferral row = deferrals.of(learner, mixed.getId()).orElseThrow();
        assertThat(row.reason()).startsWith("Record 3 fell short of the bar");
        assertThat(row.failedAgainst())
                .describedAs("stamped with the snapshot in force (A61)")
                .isEqualTo(learnerBindings.forLearner(learner).get(0).getSnapshot());
        final List<Recorded> open = attempts.forDocument(document.getUuid(), 10).stream()
                .filter(attempt -> attempt.status() == AttemptStatus.AWAITING_MODEL)
                .toList();
        assertThat(open).describedAs("a relearning session, parked for the worker").hasSize(1);

        // The worker relearns on the held stream with a stylesheet that translates both; the rebinding
        // wakes the task; the stream is served in full.
        final Script relearning = script(CSV.configuration(), TRANSLATES_BOTH);
        advisor.set(relearning);
        deferredLearning.exec();
        relearning.verifyExhausted();
        assertThat(statusByInput()).containsEntry(mixed.getId(), TaskStatus.CREATED);
        advisor.set(Script.of());
        final ProcessorResult served = commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(served.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(statusByInput()).containsEntry(mixed.getId(), TaskStatus.COMPLETE);
        final List<Meta> outputs = visibleChildrenOf(mixed).stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .toList();
        assertThat(outputs).hasSize(1);
        assertThat(EVENT.matcher(data(outputs.get(0))).results().count()).isEqualTo(6);
        assertThat(data(outputs.get(0))).contains("<Action>Logoff</Action>");
        assertThat(deferrals.forDocument(document.getUuid())).isEmpty();
    }

    private List<Meta> visibleChildrenOf(final Meta input) {
        return childrenOf(input).stream()
                .filter(meta -> !Status.DELETED.equals(meta.getStatus()))
                .toList();
    }

    /// Create what tasks there are to create and hand out `count` of them.
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

    /// The pack's document with the model left off: the script answers.
    private DocRef document() {
        final ShapeshifterAiDoc shipped = DemoContentPack.document();
        final DocRef docRef = shapeshifterAiStore.createDocument(shipped.getName() + " " + feed);
        shapeshifterAiStore.writeDocument(DemoDocument
                .configure(shapeshifterAiStore.readDocument(docRef).copy())
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
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return List.of(new DataSplitterStep(compiler), new JsonStep(), new XmlFragmentStep(), new XsltStep());
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
