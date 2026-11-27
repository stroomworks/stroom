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
import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.element.DeferredLearning;
import stroom.learningflow.element.LearningFlowParser;
import stroom.learningflow.extraction.DataSplitterCompiler;
import stroom.learningflow.extraction.DataSplitterStep;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.scenario.AdvisorHolder;
import stroom.learningflow.scenario.QuestionMatcher;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.scenario.Structure;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.transformation.StandInXsltStep;
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

/// Tests, in a running node with a real database, that a promotion wakes the deferred tasks of streams
/// that were waiting, and that the woken tasks are then processed.
///
/// A stream that arrives before its learner has learned anything is not held anywhere apart from the
/// processor's task queue: its task is deferred. The promotion that gives the learner a binding moves the
/// deferred tasks back to `CREATED`, so they run again and are served by the new binding.
class TestScenario13ReleaseInAPipeline extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-RELEASED";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");

    /// The learner for the test pipeline, identified by the pipeline and its Learning Flow element.
    private Learner learner;

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private LearningFlowStore learningFlowStore;
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

        // Learning is disabled, so nothing can serve the first stream and it is deferred. A deferral row
        // records the task for a later wake to move, and no output is written to make the stream wait.
        // A node that finds a filter empty normally waits before fetching from it again. The skip duration
        // is set to zero so that the test does not have to wait for woken tasks to be fetched.
        processorConfigProvider.get().setSkipEmptyFilterFetchDuration(StroomDuration.ZERO);
        advisor.set(Script.of());
        final Meta early = rawStream();
        final ProcessorResult deferred = processOne();
        assertThat(deferred.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(deferred.getWritten()).isZero();
        assertThat(events()).describedAs("nothing was produced from it").isEmpty();

        // With learning enabled, the next stream for the same learner is also deferred, and a session is
        // opened on it. The worker learns and promotes, and the promotion wakes every task waiting on the
        // learner.
        allowLearning(doc);
        final Script script = script();
        advisor.set(script);
        final Meta learned = rawStream();
        processOne();
        deferredLearning.exec();
        script.verifyExhausted();
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);

        // Both tasks are back in the queue before the retry delay has passed, and run again through the
        // learner's new binding without learning again.
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


    /// Builds the scripted model answers. As in the module's scenarios, the split and target questions are
    /// answered from the configurations given to [Structure], so the script only lists the chain and
    /// configuration answers this test is about.
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, ErrorReceiverProxy::new);
        return Script.of()
                .structure(new Structure(
                        List.of(new DataSplitterStep(compiler), new StandInXsltStep()), CSV.configuration(), XSLT))
                .expect(QuestionMatcher.chain()
                        .withKey("Feed", FEED)
                        .withKey("Type", StreamTypeNames.RAW_EVENTS))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    /// Creates the Learning Flow document with learning disabled, so that nothing can be learned or bound
    /// until [#allowLearning] enables it.
    private DocRef document() {
        final DocRef docRef = learningFlowStore.createDocument("door-access-released");
        learningFlowStore.writeDocument(learningFlowStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.DISABLED)
                .flow(FlowExamples.flow(FlowExample.TARGET_FIRST))
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        new CheckSetting(Check.COVERAGE, CheckState.ADVICE, 0.8, null),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build());
        return docRef;
    }

    private void allowLearning(final DocRef docRef) {
        learningFlowStore.writeDocument(learningFlowStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                // Review is the default promotion mode. This test is about how learning works, not about who
                // approves what it learns, so it promotes automatically.
                .promotionMode(PromotionMode.AUTOMATIC)
                .build());
    }

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("learningFlow", LearningFlowParser.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "learningFlow")
                .addLink("learningFlow", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("learningFlow", "learningFlow", doc))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "learningFlow");
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
