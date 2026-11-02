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
import stroom.docref.DocRef;
import stroom.meta.api.MetaService;
import stroom.meta.shared.FindMetaCriteria;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.api.ProcessorResult;
import stroom.processor.impl.ProcessorConfig;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.cache.CachedLearnerBindings;
import stroom.shapeshifter.ai.cache.Rows;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.impl.db.DeferralsDao;
import stroom.shapeshifter.ai.impl.db.LearnerBindingsDao;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.shared.LearningMode;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;

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

import static org.assertj.core.api.Assertions.assertThat;

/// Design 02 §5, scenario 20, in tier 2 against a real database (design 03, phase C): a stream whose
/// learner has no binding produces no output and waits, on a row naming the input — the row being a row
/// now, in `shapeshifter_deferral` (A26), and not a map in one node's heap. Nothing is held: the stream
/// stays in the store, and the task the row names is what a promotion wakes.
class TestScenario20SentinelInAPipeline extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-UNBOUND";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

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
    private LearnerBindings learnerBindings;
    @Inject
    private Deferrals deferrals;
    @Inject
    @Rows
    private LearnerBindings bindingRows;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;

    @BeforeEach
    void setUp() {
        // A filter over a supervised pipeline needs the node to claim its own tasks (design 04 §7).
        processorConfigProvider.get().setClaimTasksOnWorker(true);
    }

    @Test
    void scenario20AStreamNothingServesWaitsOnARow() {
        // This harness is the node's own wiring, so the seams are the tables of A26 and not one node's
        // heap — the bindings behind the cache a node reads them through (design 01 §12 item 8).
        assertThat(learnerBindings).isInstanceOf(CachedLearnerBindings.class);
        assertThat(bindingRows).isInstanceOf(LearnerBindingsDao.class);
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
        // Learning is off for this document, so nothing can bind and nothing is asked: the stream waits,
        // and the row that says so is what this scenario is about.
        final Script silent = Script.of();
        advisor.set(silent);
        final Meta raw = rawStream();

        final ProcessorResult result = processOne();

        assertThat(silent.asked()).describedAs("the model was not asked").isEmpty();
        assertThat(result.getMarkerCount(Severity.ERROR, Severity.FATAL_ERROR))
                .describedAs("no error: the stream waits for a person to turn learning on (design 04 §3)")
                .isZero();
        assertThat(result.getWritten()).isZero();
        assertThat(metaService.find(FindMetaCriteria.createWithType(StreamTypeNames.EVENTS)).getValues())
                .describedAs("no output stream: the stage bound nothing")
                .isEmpty();
        assertThat(learnerBindings.forLearner(learner)).describedAs("and wrote no binding").isEmpty();

        // The deferral names the input, in a row, under the learner whose pipeline a promotion wakes (A129).
        assertThat(deferrals).isInstanceOf(DeferralsDao.class);
        assertThat(deferrals.of(new Learner(pipeline.getUuid(), "shapeshifterAi"), raw.getId()))
                .describedAs("the deferral row is a row in the database, not a map in one node's heap")
                .isPresent()
                .get()
                .satisfies(row -> {
                    assertThat(row.pipeline()).isEqualTo(pipeline.getUuid());
                    assertThat(row.failedAgainst()).isNull();
                    assertThat(row.reason()).contains("disabled");
                });
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-unbound");
        shapeshifterAiStore.writeDocument(shapeshifterAiStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.DISABLED)
                .build());
        return docRef;
    }

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "recordOutputFilter")
                .addLink("recordOutputFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "shapeshifterAi");
        return pipelineRef;
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
        assertThat(raw).hasSize(1);
        return raw.get(0);
    }

    private ProcessorResult processOne() {
        final List<ProcessorResult> results = commonTranslationTestHelper.processAll();
        assertThat(results).hasSize(1);
        return results.get(0);
    }
}
