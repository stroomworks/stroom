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
import stroom.shapeshifter.ai.stage.Input;
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
import stroom.util.shared.StoredError;
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

/// Design 02 §5, scenario 57 at Tier 2 — **the session runs the held stream in full** (A64, A73) against
/// real processor tasks, a real database and the fragment run as a pipeline.
///
/// A stream larger than the prefix a task reads as text: the logons fill the first sixty-four kibibytes
/// and the logoffs lie beyond them, where neither the sample the model is shown
/// nor the judgement of the first candidate reaches. The first candidate translates logons alone and is
/// promoted on the prefix; the worker's full pass over the stream — the fragment run through the pipeline
/// with the parser tapped — finds the logoffs, as the parser cut them, and the next question carries
/// them; the second candidate translates both; the session ends with one attempt and the stream is woken
/// and served in full, logoffs among its events.
class TestScenario57TheSessionRunsTheHeldStreamInFullInAPipeline extends AbstractCoreIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final Pattern EVENT = Pattern.compile("<Event>");
    private static final Pattern LOGOFF = Pattern.compile("<Action>Logoff</Action>");
    private static final int LOGONS = 1_800;
    private static final int LOGOFFS = 4;
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
    void theFullPassFindsTheRecordsBeyondThePrefixAndTheSessionLearnsForThem() {
        final String stream = stream();
        assertThat(stream.indexOf("logoff")).describedAs("the logoffs lie beyond the prefix a task reads")
                .isGreaterThan(Input.PREFIX_BYTES);
        final Script silent = Script.of();
        advisor.set(silent);
        final Meta input = rawStream(stream);
        for (final ProcessorTask task : tasks(1)) {
            final ProcessorResult result = commonTranslationTestHelper.process(task);
            assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        }
        assertThat(statusByInput()).containsEntry(input.getId(), TaskStatus.DEFERRED);
        final List<Recorded> open = attempts.forDocument(document.getUuid(), 10);
        assertThat(open).hasSize(1);

        // One pass of the worker: round one from the sample, the full pass, round two with the logoffs in
        // front of the model.
        final Script script = script(CSV.configuration(), LOGON_ONLY)
                .expect(QuestionMatcher.chain()
                        .withFeedbackMentioning("of the held stream fell short of the bar")
                        .withFeedbackMentioning("The record as the parser cut it")
                        .withFeedbackMentioning("logoff"))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(TRANSLATES_BOTH));
        advisor.set(script);
        deferredLearning.exec();
        script.verifyExhausted();
        // The evidence is the record that fell short, as the parser cut it — not its neighbour: the tap
        // is read while the parser is still inside the record whose output is being judged.
        final List<String> evidence = script.asked().stream()
                .flatMap(question -> question.feedback().stream())
                .map(StoredError::getMessage)
                .filter(message -> message.contains("The record as the parser cut it"))
                .distinct()
                .toList();
        assertThat(evidence).describedAs("the shortfalls kept, each with its parsed record").hasSize(LOGOFFS);
        assertThat(evidence.get(0))
                .describedAs(evidence.get(0))
                .contains("Record " + (LOGONS + 1) + " of the held stream")
                .contains("value=\"user" + LOGONS + "\"")
                .contains("value=\"logoff\"")
                .doesNotContain("user" + (LOGONS - 1));
        final Recorded carried = attempts.byId(open.get(0).id()).orElseThrow();
        assertThat(carried.status()).describedAs(String.valueOf(carried.decision())).isEqualTo(AttemptStatus.PROMOTED);
        assertThat(attempts.forDocument(document.getUuid(), 10)).describedAs("one session to its end").hasSize(1);
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);
        assertThat(statusByInput()).containsEntry(input.getId(), TaskStatus.CREATED);

        // The woken task serves the stream in full.
        advisor.set(silent);
        for (final ProcessorTask task : tasks(1)) {
            final ProcessorResult result = commonTranslationTestHelper.process(task);
            assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        }
        assertThat(silent.asked()).isEmpty();
        assertThat(statusByInput()).containsEntry(input.getId(), TaskStatus.COMPLETE);
        final List<Meta> outputs = childrenOf(input).stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .toList();
        assertThat(outputs).hasSize(1);
        final String written = data(outputs.get(0));
        assertThat(EVENT.matcher(written).results().count()).isEqualTo(LOGONS + LOGOFFS);
        assertThat(LOGOFF.matcher(written).results().count()).isEqualTo(LOGOFFS);
        assertThat(deferrals.forDocument(document.getUuid())).isEmpty();
    }

    /// The header, the logons, then the logoffs: more than the prefix of them before the first logoff.
    private static String stream() {
        final StringBuilder text = new StringBuilder("dt,who,where,what\n");
        for (int i = 0; i < LOGONS + LOGOFFS; i++) {
            text.append("2020-06-17T").append(String.format("%02d:%02d:%02d", i / 3600, i / 60 % 60, i % 60))
                    .append(".000Z,user").append(i).append(",office,").append(i < LOGONS
                            ? "logon"
                            : "logoff").append('\n');
        }
        return text.toString();
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
            final Path file = Files.createTempFile(getCurrentTestDir(), "beyond", ".txt");
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
