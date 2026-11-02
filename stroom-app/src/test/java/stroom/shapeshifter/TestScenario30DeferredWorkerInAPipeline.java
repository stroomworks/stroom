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
import stroom.data.store.mock.MockStore;
import stroom.docref.DocRef;
import stroom.meta.mock.MockMetaService;
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
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
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
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.AttemptStatus;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;

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

/**
 * Design 02 §5, scenario 30 (Tier 2; A5, A28): deferred learning in a real pipeline. The processing task
 * asks the model nothing — it parks an attempt and writes an error stream — and the node's job carries
 * the attempt on afterwards, with no task in front of it, reading the stream back out of the store by
 * the id the attempt kept. What Tier 1 cannot claim is claimed here: the worker's seams are the node's
 * stores, the binding it writes is a row, and the fragment it wrote is a pipeline document.
 */
class TestScenario30DeferredWorkerInAPipeline extends AbstractProcessIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-DEFERRED";
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
    private LearnerBindings learnerBindings;
    @Inject
    private Attempts attempts;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private MockMetaService metaService;
    @Inject
    private MockStore streamStore;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private DeferredLearning deferredLearning;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;

    @Test
    void scenario30TheTaskParksAndTheJobLearns() {
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
        // The script is set, and the task must not touch it: that is what deferred means.
        final Script script = script()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        advisor.set(script);
        final Meta raw = rawStream();

        final ProcessorResult task = processOne();

        assertThat(script.asked()).describedAs("no model call inside the processing task").isEmpty();
        assertThat(task.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR))
                .describedAs("the stream waits, and nothing is sentinelled to make it (design 04 §8.7)").isZero();
        assertThat(task.getWritten()).isZero();
        assertThat(outputs()).isEmpty();
        assertThat(learnerBindings.forLearner(learner)).isEmpty();
        final Recorded parked = attempts.forDocument(doc.getUuid(), 10).get(0);
        assertThat(parked.status()).isEqualTo(AttemptStatus.AWAITING_MODEL);
        assertThat(parked.turns()).describedAs("the question it stopped at, asked of nobody").hasSize(1);
        assertThat(parked.turns().get(0).answer()).isNull();
        assertThat(parked.attempt().inputId())
                .describedAs("naming the stream it was raised on, which is how the job finds it again")
                .isEqualTo(raw.getId());

        // A second stream of the same learner while the attempt waits: deferred behind it — scenario 13's
        // two streams, which the promotion must wake together.
        final Meta second = rawStream();
        processOne();
        assertThat(attempts.forDocument(doc.getUuid(), 10)).describedAs("one attempt for one learner")
                .hasSize(1);
        assertThat(second.getId()).isNotEqualTo(raw.getId());

        // The job, with no task and no pipeline around it: it reads the document and the stream back out
        // of the node's own stores and carries the attempt on.
        deferredLearning.exec();

        script.verifyExhausted();
        final Recorded finished = attempts.byId(parked.id()).orElseThrow();
        assertThat(finished.status()).describedAs(String.valueOf(finished.decision()))
                .isEqualTo(AttemptStatus.PROMOTED);
        assertThat(learnerBindings.forLearner(learner)).describedAs("one binding, written by the job").hasSize(1);
        final Binding binding = learnerBindings.forLearner(learner).get(0);
        assertThat(pipelineStore.readDocument(binding.getPipeline()))
                .describedAs("and the fragment it learned is a pipeline document").isNotNull();
        // Scenario 13's substance in a node: *both* streams waited on this learner and one promotion
        // woke them together, as the pipeline's deferred tasks (design 06 A129).

        // The next stream of the learner is bound in the task, with no model call and no worker.
        final Script silent = Script.of();
        advisor.set(silent);
        rawStream();
        // The two that waited, woken by the promotion, and the new one: all three bound in the task.
        final List<ProcessorResult> served = commonTranslationTestHelper.processAll();

        assertThat(silent.asked()).isEmpty();
        assertThat(served).hasSize(3).allSatisfy(result -> {
            assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
            assertThat(result.getWritten()).isEqualTo(6);
        });
        assertThat(outputs()).hasSize(3);
        assertThat(attempts.forDocument(doc.getUuid(), 10))
                .describedAs("and opened no second attempt: the learner is known now").hasSize(1);
    }

    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return Script.of().structure(new Structure(
                List.of(new DataSplitterStep(compiler), new XsltStep()), CSV.configuration(), XSLT));
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-deferred");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                // What this scenario is about: nothing is asked in the task (A5).
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

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("schemaFilter", "SchemaFilter"))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "schemaFilter")
                .addLink("schemaFilter", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("schemaFilter", "schemaGroup", "EVENTS"))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("xmlWriter", "indentOutput", true))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "shapeshifterAi");
        return pipelineRef;
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private Meta rawStream() {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "door-access", ".csv");
            Files.writeString(file, CSV.input(), StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(FEED, file, null);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.RAW_EVENTS))
                .getValues()
                .stream()
                .max((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElseThrow();
    }

    private List<Meta> outputs() {
        return metaService.getMetaMap().values().stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }
}
