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
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearnedUnknown;
import stroom.shapeshifter.shared.LearningFlow;
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

/// Design 04 §8.1, **test A, steps 4 to 6** — phase 5, end to end against real processor tasks and a real
/// database, on the feed steps 1 to 3 learned.
///
/// A stream arrives carrying a kind of record the transform has not met — one it can translate, the
/// logoff, and one nothing can, the badge fault — beside the kinds already known. Its task defers with
/// nothing emitted, and one session opens. The model translates the logoff and, its candidate spent on
/// the badge fault, is asked by the exhaustion step for the admitted form and gives it: the same pipeline
/// is improved in place under a new snapshot, held to the corpus, which now holds the badge faults as
/// admissions beside the translations. The task wakes and completes: logons and logoffs as their events,
/// the badge faults as marked Unknown events. A stream of every kind is then served at once, with no
/// session opened.
class TestScenarioAStepsFourToSix extends AbstractCoreIntegrationTest {

    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String LOGONS = "dt,who,where,what\n"
                                         + "2020-06-17T08:00:00.000Z,jim,warehouse,logon\n"
                                         + "2020-06-17T08:10:00.000Z,bob,office,logon\n"
                                         + "2020-06-17T08:12:00.000Z,dave,office,logon\n"
                                         + "2020-06-17T08:14:00.000Z,fred,office,logon\n"
                                         + "2020-06-17T08:15:00.000Z,ann,office,logon\n";
    private static final String EVERY_KIND = "dt,who,where,what\n"
                                             + "2020-06-17T09:00:00.000Z,jim,warehouse,logon\n"
                                             + "2020-06-17T09:10:00.000Z,bob,office,logoff\n"
                                             + "2020-06-17T09:11:00.000Z,pete,shop,badge-fault\n"
                                             + "2020-06-17T09:12:00.000Z,dave,office,logon\n"
                                             + "2020-06-17T09:13:00.000Z,sam,shop,badge-fault\n"
                                             + "2020-06-17T09:14:00.000Z,fred,office,logoff\n";
    private static final String LOGON_ONLY = Scenarios.resource("csv-logon-only.xsl");
    private static final String STRICT = Scenarios.resource("csv-logon-logoff.xsl");
    private static final String ADMITS = Scenarios.resource("csv-logon-logoff-admits.xsl");
    private static final Pattern EVENT = Pattern.compile("<Event>");
    private static final Pattern LOGOFF = Pattern.compile("<Action>Logoff</Action>");
    private static final Pattern ADMITTED = Pattern.compile("Name=\"" + LearnedUnknown.MARKER
                                                            + "\" Value=\"badge-fault\"");

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
    @Inject
    private RegressionSet regressionSet;

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
    void anUnseenKindIsLearnedWhereItCanBeAndAdmittedWhereItCannot() {
        // Steps 1 to 3, compressed: a stream of logons learns the feed, logon-only.
        final Script silent = Script.of();
        advisor.set(silent);
        final Meta first = rawStream(LOGONS);
        commonTranslationTestHelper.process(tasks(1).get(0));
        final Script learning = script(CSV.configuration(), LOGON_ONLY);
        advisor.set(learning);
        deferredLearning.exec();
        learning.verifyExhausted();
        advisor.set(silent);
        commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(statusByInput()).containsEntry(first.getId(), TaskStatus.COMPLETE);
        final Binding learned = learnerBindings.forLearner(learner).get(0);

        // Step 4: a stream with two kinds the transform has not met defers, and one session opens.
        final Meta mixed = rawStream(EVERY_KIND);
        final ProcessorResult held = commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(held.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(statusByInput()).containsEntry(mixed.getId(), TaskStatus.DEFERRED);
        assertThat(childrenOf(mixed).stream().filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName())
                                                             && !meta.getStatus().equals(Status.DELETED)))
                .describedAs("nothing is emitted, not even for the records that would have translated").isEmpty();
        final List<Recorded> open = attempts.forDocument(document.getUuid(), 10).stream()
                .filter(attempt -> attempt.status() == AttemptStatus.AWAITING_MODEL)
                .toList();
        assertThat(open).hasSize(1);

        // Step 5: the model translates the logoff and falls short on the badge fault — which the sample
        // already holds, so the schema's feedback is what spends the one candidate — then admits it at the
        // exhaustion step. In place, a new snapshot, held to the corpus.
        final Script relearning = script(CSV.configuration(), STRICT)
                .expect(QuestionMatcher.admission("XSLTFilter").withFeedbackMentioning("Schema conformance"))
                .reply(Scenarios.fenced(ADMITS));
        advisor.set(relearning);
        deferredLearning.exec();
        relearning.verifyExhausted();
        final Recorded session = attempts.byId(open.get(0).id()).orElseThrow();
        assertThat(session.status()).describedAs(String.valueOf(session.decision())).isEqualTo(AttemptStatus.PROMOTED);
        final List<Binding> now = learnerBindings.forLearner(learner);
        assertThat(now).hasSize(1);
        assertThat(now.get(0).getUuid()).isEqualTo(learned.getUuid());
        assertThat(now.get(0).getPipeline()).describedAs("the same pipeline, improved in place (A58)")
                .isEqualTo(learned.getPipeline());
        assertThat(now.get(0).getSnapshot())
                .describedAs("under a new snapshot (A61)").isNotEqualTo(learned.getSnapshot());
        final List<Accepted> corpus = regressionSet.accepted(learned.getUuid());
        assertThat(corpus).filteredOn(entry -> entry.kind() == Kind.TRANSLATION)
                .describedAs("the records learned for before are still held, as translations").isNotEmpty();
        assertThat(corpus).filteredOn(entry -> entry.kind() == Kind.UNKNOWN)
                .describedAs("and the badge faults are held as admissions (A75)").hasSize(2);
        assertThat(statusByInput()).containsEntry(mixed.getId(), TaskStatus.CREATED);

        // Step 6: the task wakes and completes; a stream of every kind is served at once.
        advisor.set(silent);
        commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(statusByInput()).containsEntry(mixed.getId(), TaskStatus.COMPLETE);
        // The one visible output stream; what the deferred run wrote was discarded (§8 scenario 6).
        final String served = data(childrenOf(mixed).stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName())
                                && !Status.DELETED.equals(meta.getStatus()))
                .findFirst()
                .orElseThrow());
        assertThat(EVENT.matcher(served).results().count()).isEqualTo(6);
        assertThat(LOGOFF.matcher(served).results().count()).isEqualTo(2);
        assertThat(ADMITTED.matcher(served).results().count()).isEqualTo(2);
        final Meta again = rawStream(EVERY_KIND);
        commonTranslationTestHelper.process(tasks(1).get(0));
        assertThat(statusByInput()).containsEntry(again.getId(), TaskStatus.COMPLETE);
        assertThat(silent.asked()).isEmpty();
        assertThat(attempts.forDocument(document.getUuid(), 10)).describedAs("no session opened for it").hasSize(2);
        assertThat(deferrals.forDocument(document.getUuid())).isEmpty();
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
        // The pack's settings under a flow whose transform has one candidate before the exhaustion step
        // (design 04 §4), so that the scripted model's one refusal of the unseen kind is what sends the
        // session there.
        shapeshifterAiStore.writeDocument(DemoDocument
                .configure(shapeshifterAiStore.readDocument(docRef).copy())
                .flow(new LearningFlow(List.of(
                        FlowStep.parse("CHAIN"),
                        FlowStep.parse("CONFIGURE parser"),
                        FlowStep.parse("CONFIGURE transform candidates 1 on spent goto admit"),
                        FlowStep.parse("ADMIT candidates 1")), null, null))
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
