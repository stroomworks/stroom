/*
 * Copyright 2016-2026 Crown Copyright
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
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.PipelineDoc;
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
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.Produced;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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
 * Design 02 §5, scenario 19 (Tier 2): scenario 5 in a real pipeline. The degenerate transform of design 01
 * §8.3 validates against the node's own schema store — every version of the event-logging schema the
 * content pack carries, resolved as the real {@code SchemaFilter} resolves it — and is refused by the
 * extraction-quality gate (A16); the second candidate is promoted and its events pass the pipeline's own
 * {@code SchemaFilter} downstream.
 */
class TestScenario19DegenerateTransformInAPipeline extends AbstractProcessIntegrationTest {

    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String DEGENERATE = Scenarios.resource("csv-degenerate.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

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
    void theDegenerateTransformIsRefusedAndTheRealOnePromoted() {
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

        final Script script = script()
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter").withoutFeedback())
                .reply(Scenarios.fenced(DEGENERATE))
                .expect(QuestionMatcher.configuration("XSLTFilter")
                        .withFeedbackMentioning("Extraction quality scored")
                        .withFeedbackMentioning("records name Unknown in EventDetail"))
                .reply(Scenarios.fenced(XSLT));
        advisor.set(script);
        rawStream();
        processOne();
        deferredLearning.exec();
        script.verifyExhausted();
        final ProcessorResult result = processOne();

        // The degenerate candidate cleared the schema gate against the node's schemas: its re-ask carries no
        // conformance feedback. The promoted transform's events pass the pipeline's own schema filter.
        assertThat(script.scripted().get(3).feedback())
                .noneMatch(error -> error.getMessage().startsWith("Schema conformance"));
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(result.getWritten()).isEqualTo(6);
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);
        final Binding binding = learnerBindings.forLearner(learner).get(0);
        final Meta output = outputs().get(0);
        assertThat(canonical(data(output))).isEqualTo(canonical(EXPECTED_EVENTS));
        assertThat(streamStore.getAttributes(output.getId())).containsEntry(Produced.BINDING_ATTRIBUTE,
                binding.getUuid());
    }

    /**
     * The split and target questions are answered from the configurations the script will give, as the
     * module's scenarios do; the script states only what the scenario is about. The structure's compiler
     * is built when a question is asked, inside the processing pipeline's scope, so it reaches the node's
     * parser factory the way the element does.
     */
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return Script.of().structure(new Structure(
                List.of(new DataSplitterStep(compiler), new XsltStep()), CSV.configuration(), XSLT));
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.TARGET_FIRST)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(5)
                .promotionFloor(0.85)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.INPUT_COVERAGE, 1.0, 0.8, false, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                                new SchemaConformanceParameters("EVENTS")),
                        new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                                new ExtractionQualityParameters(false, List.of("EventSource/User/Id")))))
                .build());
        return docRef;
    }

    /**
     * {@code Source → ShapeshifterAi → SchemaFilter → RecordOutputFilter → RecordCountFilter → XMLWriter →
     * StreamAppender}: the supervised stage where a parser and its translation would be, and the standard
     * tail of an event pipeline after it.
     */
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
        final DocRef pipelineRef = pipelineStore.createDocument("DOOR-ACCESS supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "shapeshifterAi");
        return pipelineRef;
    }

    private void rawStream() {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "door-access", ".csv");
            Files.writeString(file, CSV.input(), StandardCharsets.UTF_8);
            storeCreationTool.loadEventData(FEED, file, null);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }

    private List<Meta> outputs() {
        return metaService.getMetaMap().values().stream()
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    private String data(final Meta meta) {
        return new String(streamStore.getFileData().get(meta.getId()).get(meta.getTypeName()), StandardCharsets.UTF_8);
    }

    /**
     * The two serialisers indent differently and one writes a declaration; the events are the same.
     */
    private static String canonical(final String xml) {
        return xml.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }
}
