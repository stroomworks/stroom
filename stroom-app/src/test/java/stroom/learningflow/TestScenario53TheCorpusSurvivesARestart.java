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
import stroom.learningflow.element.StageFactory;
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
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.RegressionSet;
import stroom.learningflow.stage.RegressionSet.Accepted;
import stroom.learningflow.stage.RegressionSet.Kind;
import stroom.learningflow.transformation.StandInXsltStep;
import stroom.meta.mock.MockMetaService;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.pipeline.xmlschema.XmlSchemaStore;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.QueryData;
import stroom.test.AbstractProcessIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
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

/// Tests that a learner's corpus is durable, so that it still holds a later session to its standard after
/// a restart.
///
/// A binding is learned in a processor task on a real feed. The records it was accepted on are written
/// where a node keeps them: a stream of the corpus type on the source feed, with one entry per segment,
/// and a database row per entry naming the stream and segment. Nothing is held in memory, because the
/// stored corpus reads the rows and the stream each time it is asked. A Learning Flow stage created
/// afresh, as the Supervisor creates one for every request, therefore finds the corpus as the task left
/// it. A test cannot restart a node, so this is how it simulates one: nothing but the rows and the stream
/// survives from one session to the next.
///
/// The later sessions are improvements requested by a person, as in the module's
/// `TestScenario53TheCorpusHoldsACandidateToNoWorse`. A candidate that translates a record that was
/// previously admitted as a marked Unknown is accepted, and the corpus grows by a second stream. A candidate
/// that would turn a translated record back into an Unknown is rejected, and the rejection names the corpus
/// entry.
class TestScenario53TheCorpusSurvivesARestart extends AbstractProcessIntegrationTest {

    private static final String FEED = DemoContentPack.FEEDS.get(0);
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String ADMITS_LOGOFF = Scenarios.resource("csv-logon-unknown.xsl");

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
    private XmlSchemaStore xmlSchemaStore;
    @Inject
    private LearnerBindings learnerBindings;
    @Inject
    private RegressionSet regressionSet;
    @Inject
    private MockMetaService metaService;
    @Inject
    private MockStore streamStore;
    @Inject
    private AdvisorHolder advisor;
    @Inject
    private DeferredLearning deferredLearning;
    @Inject
    private Attempts attempts;
    @Inject
    private Provider<DS3ParserFactory> parserFactories;
    @Inject
    private Provider<PipelineScopeRunnable> pipelineScopes;
    @Inject
    private Provider<StageFactory> stageFactories;

    @Test
    void whatATaskAcceptedABindingOnIsThereForTheNextSessionAndHoldsIt() {
        storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef document = document();
        filter(pipeline(document));

        // Learn in a task, with a stylesheet that admits a logoff as a marked Unknown rather than
        // translating it.
        advisor.set(script(CSV.configuration(), ADMITS_LOGOFF));
        rawStream(MIXED);
        processOne();
        deferredLearning.exec();
        final ProcessorResult result = processOne();
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        final Binding binding = learnerBindings.forLearner(learner).get(0);

        // The corpus is one stream of the corpus type on the source feed, with one line per entry.
        final List<Meta> corpusStreams = corpusStreams();
        assertThat(corpusStreams).hasSize(1);
        assertThat(corpusStreams.get(0).getFeedName()).isEqualTo(FEED);
        assertThat(data(corpusStreams.get(0)).lines()).hasSize(6);
        final List<Accepted> accepted = regressionSet.accepted(binding.getUuid());
        assertThat(accepted).hasSize(6);
        assertThat(regressionSet.feedOf(binding.getUuid()))
                .describedAs("what an improvement, with no stream of its own, keeps its records on")
                .contains(FEED);
        assertThat(accepted).extracting(Accepted::kind).containsExactly(
                Kind.TRANSLATION, Kind.TRANSLATION, Kind.UNKNOWN, Kind.TRANSLATION, Kind.UNKNOWN, Kind.TRANSLATION);
        assertThat(accepted).allSatisfy(entry -> {
            assertThat(entry.text()).isNotBlank();
            assertThat(entry.preamble()).isEqualTo("dt,who,where,what\n");
            assertThat(entry.attemptId()).describedAs("the attempt that accepted it").isNotNull();
        });

        // A session after a simulated restart, from a stage created afresh as the Supervisor creates one,
        // using the corpus read back from storage. The candidate translates what was admitted as Unknown,
        // and is accepted.
        final String better = improve(document, binding, TRANSLATES_BOTH);
        assertThat(better).describedAs(better).startsWith("Rebound");
        assertThat(corpusStreams()).describedAs("a second promotion, a second stream").hasSize(2);
        assertThat(corpusStreams()).extracting(Meta::getFeedName)
                .describedAs("and on the source feed too, though no stream of it was in hand: a learner does "
                             + "not know its feed (A126), but its records do")
                .containsOnly(FEED);
        final List<Accepted> grown = regressionSet.accepted(binding.getUuid());
        assertThat(grown).hasSize(12);
        assertThat(grown.subList(6, 12)).extracting(Accepted::kind).containsOnly(Kind.TRANSLATION);

        // One more candidate would turn a record that now translates back into an Unknown. The corpus
        // rejects it, naming the entry, and the existing binding continues to serve.
        final Binding rebound = learnerBindings.forLearner(learner).get(0);
        final String worse = improve(document, rebound, ADMITS_LOGOFF);
        assertThat(worse).describedAs(worse)
                .startsWith("Kept the incumbent")
                .contains("turns a record that translated into an Unknown")
                .contains("record 9 of 12 in the corpus");
        assertThat(learnerBindings.forLearner(learner).get(0).getPipeline()).isEqualTo(rebound.getPipeline());
        assertThat(corpusStreams()).hasSize(2);
    }

    /// Runs a person's *Improve* request and returns the decision it reached.
    ///
    /// The request opens an attempt that waits at its first question, and the Deferred Learning job asks
    /// the model. The outcome is therefore read back from the attempt's recorded decision text, rather
    /// than returned by the call that made the request.
    private String improve(final DocRef document, final Binding binding, final String stylesheet) {
        final Script script = script(CSV.configuration(), stylesheet);
        advisor.set(script);
        final LearningFlowDoc doc = learningFlowStore.readDocument(document);
        pipelineScopes.get().scopeRunnable(() ->
                stageFactories.get().create().improve(doc, binding.getUuid(), null, "jo"));
        deferredLearning.exec();
        script.verifyExhausted();
        return attempts.forDocument(doc.getUuid(), 1).get(0).decision();
    }

    private List<Meta> corpusStreams() {
        return metaService.getMetaMap().values().stream()
                .filter(meta -> StreamTypeNames.LEARNING_FLOW_CORPUS.equals(meta.getTypeName()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    /// Creates a document configured as the demo document is, with two changes. The extraction quality
    /// check allows Unknown in `EventDetail`, so that an admitted record clears the bar, and nothing is held
    /// out, so that the sample is the whole six-record stream. There is no model, because the scripted
    /// advisor supplies the answers.
    private DocRef document() {
        final LearningFlowDoc shipped = DemoContentPack.document();
        final DocRef docRef = learningFlowStore.createDocument(shipped.getName());
        final LearningFlowDoc configured = DemoDocument
                .configure(learningFlowStore.readDocument(docRef).copy())
                // A new document targets the latest installed event-logging schema (4.1.0 on a test
                // node), but this test's canned answers write 3.0.0, so it targets that version.
                .targetSchema(TargetSchemaFixture.eventLogging300(xmlSchemaStore))
                .heldOutFraction(0.0)
                .build();
        learningFlowStore.writeDocument(configured.copy()
                .checks(configured.getChecks().stream()
                        .map(setting -> setting.getCheck() == Check.QUALITY
                                ? new CheckSetting(setting.getCheck(), setting.getState(), setting.getThreshold(),
                                        new ExtractionQualityParameters(true, List.of()))
                                : setting)
                        .toList())
                .build());
        return docRef;
    }

    private DocRef pipeline(final DocRef document) {
        final DocRef pipelineRef = pipelineStore.createDocument(DemoContentPack.pipelineName(FEED));
        final PipelineDoc pipeline = pipelineStore.readDocument(pipelineRef);
        pipelineStore.writeDocument(pipeline.copy()
                .pipelineData(DemoContentPack.pipelineData(document))
                .build());
        learner = new Learner(pipelineRef.getUuid(), DemoContentPack.STAGE_ELEMENT);
        return pipelineRef;
    }

    private void filter(final DocRef pipeline) {
        final ProcessorFilter shipped = DemoContentPack.filter(FEED);
        final QueryData queryData = shipped.getQueryData().copy()
                .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                .build();
        processorFilterService.create(CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(queryData)
                .priority(shipped.getPriority())
                .build());
    }

    private List<StepRunner> runners() {
        final DataSplitterCompiler compiler = new DataSplitterCompiler(parserFactories, new ErrorReceiverProxy());
        return List.of(new DataSplitterStep(compiler), new JsonStep(), new XmlFragmentStep(), new StandInXsltStep());
    }

    private Script script(final String splitter, final String stylesheet) {
        return Script.of().structure(new Structure(runners(), splitter, stylesheet))
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(splitter))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(stylesheet));
    }

    private void rawStream(final String data) {
        try {
            final Path file = Files.createTempFile(getCurrentTestDir(), "corpus", ".txt");
            Files.writeString(file, data, StandardCharsets.UTF_8);
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

    private String data(final Meta meta) {
        final Map<String, byte[]> files = streamStore.getFileData().get(meta.getId());
        return new String(files.get(meta.getTypeName()), StandardCharsets.UTF_8);
    }
}
