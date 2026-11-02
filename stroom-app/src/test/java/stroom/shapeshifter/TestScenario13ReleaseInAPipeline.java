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
import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.api.ProcessorTaskService;
import stroom.processor.impl.ProcessorConfig;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorTask;
import stroom.processor.shared.QueryData;
import stroom.processor.shared.TaskStatus;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.DeferredLearning;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.shapeshifter.ai.extraction.DataSplitterCompiler;
import stroom.shapeshifter.ai.extraction.DataSplitterStep;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.QuestionMatcher;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.scenario.Structure;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;
import stroom.util.time.StroomDuration;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Design 02 §5, scenario 13, in tier 2 against a real database: **promotion wakes what waited, and what
/// is woken is actually processed**.
///
/// It is what "nothing is held" (design 01 §5.2) means in practice: a stream that arrives before its
/// learner has learned is not parked anywhere — its task is deferred — and the promotion that settles the
/// learner puts the deferred tasks back in the queue, to run through what now serves (A129).
class TestScenario13ReleaseInAPipeline extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-RELEASED";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    /// The learner the test's pipeline is: its Shapeshifter AI element (design 06 phase J slice 2b).
    private Learner learner;

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private MetaService metaService;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;
    @Inject
    private ProcessorTaskService processorTaskService;
    @Inject
    private Deferrals deferrals;
    @Inject
    private DeferredLearning deferredLearning;
    @Inject
    private LearnerBindings learnerBindings;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;

    @Test
    void scenario13ThePromotionReleasesTheStreamThatWaitedAndItIsProcessed() {
        // A filter over a supervised pipeline needs the node to claim its own tasks (design 04 §7).
        processorConfigProvider.get().setClaimTasksOnWorker(true);
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, Condition.EQUALS, FEED)
                                .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                                .build())
                        .build())
                .priority(1)
                .build());

        // Learning is off, so the first stream cannot bind: it waits (design 04 §3), with a deferral row
        // naming the task a wake will move, and nothing is sentinelled or written to make it wait.
        // A node that found a filter empty waits before asking it again; a wake must not wait on that.
        processorConfigProvider.get().setSkipEmptyFilterFetchDuration(StroomDuration.ZERO);
        advisor.set(Script.of());
        final Meta early = rawStream();
        final ProcessorResult deferred = processOne();
        assertThat(deferred.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(deferred.getWritten()).isZero();
        assertThat(events()).describedAs("nothing was produced from it").isEmpty();

        // Learning on. The next stream of the same learner defers too, with a session opened on it; the
        // worker learns and promotes, and the promotion wakes every task waiting on the learner.
        allowLearning(doc);
        final Script script = script();
        advisor.set(script);
        final Meta learned = rawStream();
        processOne();
        deferredLearning.exec();
        script.verifyExhausted();
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);

        // And the wake is real: both tasks are back in the queue ahead of the retry delay, and run again
        // through the binding the learner settled on — bound, not learned again.
        final List<ProcessorTask> tasks = processorTaskService.find(new ExpressionCriteria()).getValues();
        assertThat(tasks).describedAs(tasks.toString())
                .extracting(ProcessorTask::getMetaId, ProcessorTask::getStatus)
                .containsExactlyInAnyOrder(tuple(early.getId(), TaskStatus.CREATED),
                        tuple(learned.getId(), TaskStatus.CREATED));
        assertThat(deferrals.forDocument(doc.getUuid()))
                .describedAs("both rows stand until each stream is served")
                .extracting(Deferral::inputId)
                .containsExactly(early.getId(), learned.getId());
        final Script silent = Script.of();
        advisor.set(silent);
        final List<ProcessorResult> released = commonTranslationTestHelper.processAll();
        assertThat(released).describedAs("two tasks, woken by the promotion").hasSize(2);
        assertThat(released).allSatisfy(result -> {
            assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
            assertThat(result.getWritten()).isEqualTo(6);
        });

        assertThat(events())
                .describedAs("both streams have output now: the one the learner learned on and the one "
                             + "that arrived before it was known")
                .extracting(Meta::getParentMetaId)
                .containsExactlyInAnyOrder(learned.getId(), early.getId());
        assertThat(silent.asked())
                .describedAs("released as-current (A12): the binding the learner settled on served it, and "
                             + "the model was not asked again")
                .isEmpty();
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);
    }


    /**
     * The split and target questions are answered from the configurations the script will give, as the
     * module's scenarios do; the script states only what the scenario is about.
     */
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return Script.of()
                .structure(new Structure(
                        List.of(new DataSplitterStep(compiler), new XsltStep()), CSV.configuration(), XSLT))
                .expect(QuestionMatcher.chain()
                        .withKey("Feed", FEED)
                        .withKey("Type", StreamTypeNames.RAW_EVENTS))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    /// The document as the operator first has it: nothing may be learned, so nothing can bind.
    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-released");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.DISABLED)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build());
        return docRef;
    }

    private void allowLearning(final DocRef docRef) {
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                .build());
    }

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "shapeshifterAi");
        return pipelineRef;
    }

    private List<Meta> events() {
        return metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.EVENTS)).getValues();
    }

    private Meta rawStream() {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "door-access", ".csv");
            Files.writeString(file, CSV.input(), StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(FEED, file, null);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        final List<Meta> raw = metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.RAW_EVENTS))
                .getValues();
        return raw.stream().max((a, b) -> Long.compare(a.getId(), b.getId())).orElseThrow();
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }
}
