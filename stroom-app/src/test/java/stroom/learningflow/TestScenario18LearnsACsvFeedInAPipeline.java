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

package stroom.learningflow;

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.mock.MockStore;
import stroom.docref.DocRef;
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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.Produced;
import stroom.learningflow.transformation.StandInXsltStep;
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

/// Tests learning a CSV feed in a running node: the same case as the module's
/// `TestScenario01LearnsACsvFeed`, but with the document, feed and streams as real content and the Learning
/// Flow element in a real pipeline run by a processor task.
///
/// The module's own tests cannot check what this one does: that the learned fragment runs as a pipeline,
/// that the element serves the stream and passes on its events, that the fragment is a document in the
/// store, and that the output stream's attributes record which document, binding and fragment produced
/// it.
class TestScenario18LearnsACsvFeedInAPipeline extends AbstractProcessIntegrationTest {

    private static final String FEED = "DOOR-ACCESS";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final String EXPECTED_EVENTS = Scenarios.resource("csv-logon.events.xml");

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
    void learnsInAPipelineThenBindsTheNextStream() {
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

        // For the first stream the model is asked, and the stream is then processed by what was learned.
        final Script script = script()
                .expect(QuestionMatcher.chain()
                        .withKey("Feed", FEED)
                        .withKey("Type", StreamTypeNames.RAW_EVENTS))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        advisor.set(script);
        rawStream();
        // The task defers the stream and opens a session, the worker learns, and the woken task serves it.
        final ProcessorResult deferred = processOne();
        assertThat(deferred.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(deferred.getWritten()).isZero();
        assertThat(script.asked()).describedAs("nothing asked in the task").isEmpty();
        deferredLearning.exec();
        script.verifyExhausted();
        final ProcessorResult first = processOne();

        assertThat(first.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(first.getWritten()).isEqualTo(6);
        // Bindings are stored as database rows, not in the document, which holds only what a person
        // authors. Learning writes one binding and leaves the document unchanged.
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);
        final Binding binding = learnerBindings.forLearner(learner).get(0);
        assertThat(learningFlowStore.readDocument(doc))
                .describedAs("the document the operator authored is untouched by learning")
                .isEqualTo(learningFlowStore.readDocument(doc));
        assertThat(binding.isDraft()).isFalse();
        final PipelineDoc fragment = pipelineStore.readDocument(binding.getPipeline());
        assertThat(fragment).describedAs("the fragment is a real pipeline document").isNotNull();
        assertThat(fragment.getName())
                .describedAs("named for the pipeline it was learned for, the element naming nothing (A113)")
                .isEqualTo("DOOR-ACCESS supervised learned");
        assertThat(fragment.getPipelineData().getAddedElements())
                .extracting(element -> element.getType())
                .containsExactly("Source", "DSParser", "SplitFilter", "XSLTFilter");

        final Meta output = outputs().get(0);
        assertThat(canonical(data(output))).isEqualTo(canonical(EXPECTED_EVENTS));
        final Map<String, String> attributes = streamStore.getAttributes(output.getId());
        assertThat(attributes)
                .describedAs("design 01 §7.3 rule 3: the output carries what produced it")
                .containsEntry(Produced.DOC_ATTRIBUTE, doc.getUuid())
                .containsEntry(Produced.BINDING_ATTRIBUTE, binding.getUuid())
                .containsEntry(Produced.FRAGMENT_ATTRIBUTE, binding.getPipeline().getUuid());

        // The learner's second stream is served by the binding without asking the model.
        final Script silent = Script.of();
        advisor.set(silent);
        rawStream();
        final ProcessorResult second = processOne();

        assertThat(silent.asked()).isEmpty();
        assertThat(second.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        assertThat(second.getWritten()).isEqualTo(6);
        assertThat(outputs()).hasSize(2);
        assertThat(canonical(data(outputs().get(1)))).isEqualTo(canonical(EXPECTED_EVENTS));
        assertThat(learnerBindings.forLearner(learner)).hasSize(1);
    }

    /// Starts the script of model answers. As in the module's scenarios, the split and target questions are
    /// answered from the configurations given to [Structure], so the script only needs the chain and
    /// configuration answers this test is about. The Data Splitter compiler uses the node's parser factory,
    /// as the element does.
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return Script.of().structure(new Structure(
                List.of(new DataSplitterStep(compiler), new StandInXsltStep()), CSV.configuration(), XSLT));
    }

    private DocRef document() {
        final DocRef docRef = learningFlowStore.createDocument("door-access");
        learningFlowStore.writeDocument(learningFlowStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.AUTOMATIC)
                // Review is the default promotion mode. This test is about how learning works, not about who
                // approves what it learns, so it promotes automatically.
                .promotionMode(PromotionMode.AUTOMATIC)
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

    /// Creates the pipeline `Source → LearningFlow → SchemaFilter → RecordOutputFilter → RecordCountFilter →
    /// XMLWriter → StreamAppender`. The Learning Flow element takes the place of a parser and its
    /// translation, followed by the usual elements at the end of an event pipeline.
    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("learningFlow", LearningFlowParser.TYPE))
                .addElement(new PipelineElement("schemaFilter", "SchemaFilter"))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("writeRecordCountFilter", "RecordCountFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "learningFlow")
                .addLink("learningFlow", "schemaFilter")
                .addLink("schemaFilter", "recordOutputFilter")
                .addLink("recordOutputFilter", "writeRecordCountFilter")
                .addLink("writeRecordCountFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("learningFlow", "learningFlow", doc))
                .addProperty(PipelineDataUtil.createProperty("schemaFilter", "schemaGroup", "EVENTS"))
                .addProperty(PipelineDataUtil.createProperty("writeRecordCountFilter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("xmlWriter", "indentOutput", true))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument("DOOR-ACCESS supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "learningFlow");
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

    /// Normalises XML for comparison, because the two serialisers indent differently and only one writes an
    /// XML declaration.
    private static String canonical(final String xml) {
        return xml.replaceAll("<\\?xml[^>]*\\?>", "").replaceAll(">\\s+<", "><").strip();
    }
}
