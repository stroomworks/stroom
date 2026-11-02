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

import stroom.data.store.mock.MockStore;
import stroom.docref.DocRef;
import stroom.meta.mock.MockMetaService;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.xml.converter.ds3.DS3ParserFactory;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.QueryData;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.DeferredLearning;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.element.StoredRegressionSet;
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
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Decision.Kept;
import stroom.shapeshifter.ai.stage.Decision.Rebound;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.RegressionSet;
import stroom.shapeshifter.ai.stage.RegressionSet.Accepted;
import stroom.shapeshifter.ai.stage.RegressionSet.Kind;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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

/// Design 02 §5, scenario 53 at Tier 2 — design 04 §7, phase 3's exit: **the corpus is durable.**
///
/// A binding is learned in a processor task on a real feed, and what it was accepted on is written where a
/// node keeps it: a stream on the source feed, of the corpus's own type, one entry per segment, with a
/// row per entry in the database naming the stream and the segment. Nothing of it is held in memory by
/// anything — the stored set reads the rows and the stream every time it is asked — so a stage built
/// afresh, as the Supervisor builds one for every request, finds the corpus as the task left it. That is
/// what a restart between the session that filled it and the one it refuses comes to in a test that
/// cannot restart a node: no object survives from the one to the other but the rows and the stream.
///
/// The later sessions are improvements (A46), as scenario 53's are at Tier 1: a candidate that turns an
/// admission into a translation is accepted and the corpus grows by a second stream; one that would turn
/// a translated record back into an Unknown is refused, and the refusal names the entry (A66).
class TestScenario53TheCorpusSurvivesARestart extends AbstractProcessIntegrationTest {

    private static final String FEED = DemoContentPack.FEEDS.get(0);
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");
    private static final String MIXED = Scenarios.resource("csv-mixed.csv");
    private static final String TRANSLATES_BOTH = Scenarios.resource("csv-logon.xsl");
    private static final String ADMITS_LOGOFF = Scenarios.resource("csv-logon-unknown.xsl");

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

        // Learned in a task: the stylesheet admits a logoff rather than translating it.
        advisor.set(script(CSV.configuration(), ADMITS_LOGOFF));
        rawStream(MIXED);
        processOne();
        deferredLearning.exec();
        final ProcessorResult result = processOne();
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR)).isZero();
        final Binding binding = learnerBindings.forLearner(learner).get(0);

        // Where a node keeps it: one stream of the corpus's type on the source feed, a line per entry.
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

        // A session after a restart: a stage built afresh, as the Supervisor builds one, learning from the
        // corpus put back together. The candidate translates what was admitted, and is accepted.
        final Decision better = improve(document, binding, TRANSLATES_BOTH);
        assertThat(better).describedAs(better.toString()).isInstanceOf(Rebound.class);
        assertThat(corpusStreams()).describedAs("a second promotion, a second stream").hasSize(2);
        assertThat(corpusStreams()).extracting(Meta::getFeedName)
                .describedAs("and on the source feed too, though no stream of it was in hand: a learner does "
                             + "not know its feed (A126), but its records do")
                .containsOnly(FEED);
        final List<Accepted> grown = regressionSet.accepted(binding.getUuid());
        assertThat(grown).hasSize(12);
        assertThat(grown.subList(6, 12)).extracting(Accepted::kind).containsOnly(Kind.TRANSLATION);

        // And one more, that would turn a record that now translates back into an admission: refused by
        // the corpus, naming the entry, and the binding serves on as it was.
        final Binding rebound = learnerBindings.forLearner(learner).get(0);
        final Decision worse = improve(document, rebound, ADMITS_LOGOFF);
        assertThat(worse).describedAs(worse.toString()).isInstanceOf(Kept.class);
        assertThat(((Kept) worse).reason())
                .contains("turns a record that translated into an Unknown")
                .contains("record 9 of 12 in the corpus");
        assertThat(learnerBindings.forLearner(learner).get(0).getPipeline()).isEqualTo(rebound.getPipeline());
        assertThat(corpusStreams()).hasSize(2);
    }

    private Decision improve(final DocRef document, final Binding binding, final String stylesheet) {
        final Script script = script(CSV.configuration(), stylesheet);
        advisor.set(script);
        final ShapeshifterAiDoc doc = shapeshifterAiStore.readDocument(document);
        final Decision decision = pipelineScopes.get().scopeResult(() ->
                stageFactories.get().create().improve(doc, binding.getUuid(), null, "jo").decision());
        script.verifyExhausted();
        return decision;
    }

    private List<Meta> corpusStreams() {
        return metaService.getMetaMap().values().stream()
                .filter(meta -> StoredRegressionSet.CORPUS_TYPE.equals(meta.getTypeName()))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    /// The demo document, allowing Unknown in EventDetail so that an admission clears the bar, and holding
    /// nothing out so that the sample is the whole six-record stream. The model is left off: the script
    /// answers.
    private DocRef document() {
        final ShapeshifterAiDoc shipped = DemoContentPack.document();
        final DocRef docRef = shapeshifterAiStore.createDocument(shipped.getName());
        final ShapeshifterAiDoc configured = DemoDocument
                .configure(shapeshifterAiStore.readDocument(docRef).copy())
                .heldOutFraction(0.0)
                .build();
        shapeshifterAiStore.writeDocument(configured.copy()
                .scorers(configured.getScorers().stream()
                        .map(setting -> setting.getType() == ScorerType.EXTRACTION_QUALITY
                                ? new ScorerSetting(setting.getType(), setting.getWeight(), setting.getThreshold(),
                                        setting.isGate(), new ExtractionQualityParameters(true, List.of()))
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
        return List.of(new DataSplitterStep(compiler), new JsonStep(), new XmlFragmentStep(), new XsltStep());
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
