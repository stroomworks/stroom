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
import stroom.learningflow.shared.LearningFlowStepDetails;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.meta.mock.MockMetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.SharedElementData;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.stepping.NestedElementData;
import stroom.pipeline.shared.stepping.PipelineStepRequest;
import stroom.pipeline.shared.stepping.StepType;
import stroom.pipeline.shared.stepping.SteppingResult;
import stroom.pipeline.stepping.SteppingService;
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
import static org.assertj.core.api.Assertions.tuple;

/// Tests stepping into the fragment that a Learning Flow element runs, in a running node.
///
/// From outside, a Learning Flow element looks like a single element that takes a stream and emits events.
/// The records the learned parser produced, and what the learned transform made of each, happen inside it
/// and would be invisible to a person debugging the pipeline. The stepper therefore shows the fragment's
/// own elements beneath the Learning Flow element, each with its input and output for the record at the
/// cursor, and they step like any other element.
///
/// The chain shown is the fragment as it was learned. The runner adds two elements to make a fragment
/// runnable: a parser in front of a chain that has none, and a filter at the end to collect the events.
/// These are not part of what was learned, so they are not shown.
class TestScenario33SteppingIntoTheFragment extends AbstractProcessIntegrationTest {

    private static final String FEED = "DOOR-ACCESS";
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
    @Inject
    private SteppingService steppingService;

    /// Learns a feed, then steps the pipeline that learned it, and checks that the element's step details
    /// hold the fragment's own chain, with each element's real input and output for the record.
    @Test
    void theFragmentsElementsStepBeneathTheStage() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        learn(pipeline, doc);

        final SteppingResult stepped = step(pipeline, StepType.FIRST);

        assertThat(stepped.isFoundRecord()).describedAs("the stream has records to step").isTrue();
        final SharedElementData stage = stepped.getStepData().getElementData("learningFlow");
        assertThat(stage).describedAs("the supervised stage is in the step data").isNotNull();
        final LearningFlowStepDetails details = (LearningFlowStepDetails) stage.getDetails();
        assertThat(details).describedAs("with its stage pane (A30)").isNotNull();
        assertThat(details.isDryRun())
                .describedAs("stepping is a dry run: the stage serves and learns nothing")
                .isTrue();

        final List<NestedElementData> nested = details.getNested();
        assertThat(nested)
                .extracting(NestedElementData::getName, NestedElementData::getType)
                .describedAs("the chain as it was learned, in the order it ran — the split that cuts the "
                             + "records included, and nothing the runner added to make it runnable")
                .containsExactly(tuple("dsParser", "DSParser"), tuple("splitFilter", "SplitFilter"),
                        tuple("xsltFilter", "XSLTFilter"));
        assertThat(nested)
                .extracting(NestedElementData::getId)
                .describedAs("named by the stage that ran them, because a fragment may hold an XSLTFilter "
                             + "and so may the pipeline it is running inside")
                .containsExactly("learningFlow/dsParser", "learningFlow/splitFilter", "learningFlow/xsltFilter");

        final NestedElementData parser = nested.get(0);
        final NestedElementData split = nested.get(1);
        final NestedElementData transform = nested.get(2);
        assertThat(parser.getInput())
                .describedAs("the learned parser was given the stream's own text")
                .isNotBlank();
        assertThat(parser.getOutput())
                .describedAs("and cut it into records")
                .contains("<record>");
        assertThat(split.getType()).describedAs("the split is shown where it ran").isEqualTo("SplitFilter");
        assertThat(transform.getInput())
                .describedAs("what the parser wrote for this record is what the transform was given, one "
                             + "record at a time: the chain, opened up")
                .isEqualTo(parser.getOutput())
                .contains("<record>");
        assertThat(transform.getOutput())
                .describedAs("and the transform wrote the event the stage emitted")
                .contains("<Event");
    }

    /// Checks that a chain with a split steps record by record.
    ///
    /// The Learning Flow element sits where a parser would, so it is given the whole stream, and the learned
    /// parser reads all of it once. The split then divides the parser's output into records and the
    /// transform is given one record at a time. The stepper shows the same: for the record at the cursor,
    /// the transform's input is that record and its output is that record's event, and stepping forward
    /// moves on to the next record.
    @Test
    void aChainThatSplitsStepsRecordByRecord() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        learn(pipeline, doc);

        final SteppingResult first = step(pipeline, StepType.FIRST);
        final SteppingResult second = steppingService.step(request(pipeline)
                .copy()
                .stepType(StepType.FORWARD)
                .stepLocation(first.getFoundLocation())
                .sessionUuid(first.getSessionUuid())
                .build());

        assertThat(second.isFoundRecord()).describedAs("the stream has a second record").isTrue();
        assertThat(second.getFoundLocation().getRecordIndex())
                .describedAs("and the stage has stepped on to it")
                .isEqualTo(first.getFoundLocation().getRecordIndex() + 1);
        final List<NestedElementData> one = nested(first);
        final List<NestedElementData> two = nested(second);
        assertThat(one).noneMatch(NestedElementData::isWholeStream)
                .describedAs("no row is one run over the whole stream: the chain cut the records itself");
        assertThat(one.get(2).getOutput())
                .describedAs("the first record's event")
                .contains("2020-06-17T08:00:00.000Z")
                .doesNotContain("2020-06-17T08:14:00.000Z");
        assertThat(two.get(2).getOutput())
                .describedAs("and the second record's, which is another")
                .isNotEqualTo(one.get(2).getOutput())
                .doesNotContain("2020-06-17T08:00:00.000Z");
    }

    /// Checks that a feed nothing has been learned for can still be stepped, which is the main case the
    /// element's stepping pane exists for.
    ///
    /// The stepper stops at records, where a record is an `endDocument` reaching the record detector below
    /// the element. An element with no binding emits nothing, so without special handling the stepper
    /// would find nothing to stop at, and the pane that says the element *would learn*, with the learner
    /// and the reason, could never be shown. When stepping, the element therefore emits one empty document
    /// to carry the explanation. This is only done when stepping, which is a dry run. In a processing task
    /// the same document would produce an empty output stream, which would look like a feed with no data.
    @Test
    void aFeedNobodyHasTaughtCanStillBeStepped() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = document();
        final DocRef pipeline = pipeline(feed, doc);
        rawStream();
        // Nothing has processed the stream, so nothing is bound, and stepping must not ask the model.
        advisor.set(Script.of());

        final SteppingResult stepped = step(pipeline, StepType.FIRST);

        assertThat(stepped.isFoundRecord())
                .describedAs("there is a record to stop at, though the stage bound nothing and emitted "
                             + "nothing of its own")
                .isTrue();
        final SharedElementData stage = stepped.getStepData().getElementData("learningFlow");
        final LearningFlowStepDetails details = (LearningFlowStepDetails) stage.getDetails();
        assertThat(details).describedAs("and the stage pane is there to say what would happen").isNotNull();
        assertThat(details.isDryRun()).isTrue();
        assertThat(details.getDecision())
                .describedAs("*would* learn: nothing has been learned by looking")
                .isEqualTo("Would");
        assertThat(details.getStream()).containsEntry("Feed", FEED).containsEntry("Type", StreamTypeNames.RAW_EVENTS);
        assertThat(details.getNested())
                .describedAs("with no chain beneath it, because no chain ran")
                .isEmpty();
        assertThat(learnerBindings.forLearner(learner))
                .describedAs("and nothing was bound by opening the stepper on it")
                .isEmpty();
    }

    private static List<NestedElementData> nested(final SteppingResult result) {
        final SharedElementData stage = result.getStepData().getElementData("learningFlow");
        return ((LearningFlowStepDetails) stage.getDetails()).getNested();
    }

    private SteppingResult step(final DocRef pipeline, final StepType stepType) {
        return steppingService.step(request(pipeline).copy().stepType(stepType).build());
    }

    private PipelineStepRequest request(final DocRef pipeline) {
        return PipelineStepRequest.builder()
                .pipelineDoc(pipelineStore.readDocument(pipeline))
                .criteria(new FindMetaCriteria(ExpressionOperator.builder()
                        .addTextTerm(MetaFields.FEED, Condition.EQUALS, FEED)
                        .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                        .build()))
                .timeout(Long.MAX_VALUE)
                .build();
    }

    /// Processes one stream through the pipeline so that the learner learns and is bound, giving stepping
    /// a fragment to open.
    private void learn(final DocRef pipeline, final DocRef doc) {
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
                .expect(QuestionMatcher.chain()
                        .withKey("Feed", FEED)
                        .withKey("Type", StreamTypeNames.RAW_EVENTS))
                .reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
        advisor.set(script);
        rawStream();
        processOne();
        deferredLearning.exec();
        script.verifyExhausted();
        final ProcessorResult result = processOne();
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        final List<Binding> table = learnerBindings.forLearner(learner);
        assertThat(table).hasSize(1);
        final PipelineDoc fragment = pipelineStore.readDocument(table.get(0).getPipeline());
        assertThat(fragment.getPipelineData().getAddedElements())
                .extracting(PipelineElement::getType)
                .containsExactly("Source", "DSParser", "SplitFilter", "XSLTFilter");
        // A step is a dry run and must not call the model, so the script is emptied to catch any call.
        advisor.set(Script.of());
    }

    /// Starts the script of model answers. As in the module's scenarios, the split and target questions are
    /// answered from the configurations given to [Structure], so the script only needs the chain and
    /// configuration answers this test is about. The Data Splitter compiler uses the node's parser factory,
    /// as the element does.
    private Script script() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, ErrorReceiverProxy::new);
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
