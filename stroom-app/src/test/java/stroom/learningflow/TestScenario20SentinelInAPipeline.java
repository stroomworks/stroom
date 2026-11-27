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
import stroom.docref.DocRef;
import stroom.learningflow.cache.CachedLearnerBindings;
import stroom.learningflow.cache.Rows;
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.element.LearningFlowParser;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.impl.db.DeferralsDao;
import stroom.learningflow.impl.db.LearnerBindingsDao;
import stroom.learningflow.scenario.AdvisorHolder;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
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

/// Tests, in a running node with a real database, that a stream whose learner has no binding produces no
/// output and is deferred.
///
/// The deferral is recorded as a row in the `learning_flow_deferral` table, which names the input stream,
/// so that every node sees it. The stream itself stays in the store, and a later promotion wakes the
/// pipeline's deferred tasks.
class TestScenario20SentinelInAPipeline extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-UNBOUND";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

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
    }

    @Test
    void scenario20AStreamNothingServesWaitsOnARow() {
        // This test uses the node's real wiring, so bindings are read from the database tables through
        // the node's cache, rather than from in-memory stand-ins.
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
        // Learning is disabled for this document, so nothing can serve the stream and the model is not
        // asked. The stream is deferred, and the deferral row is what this test checks.
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

        // The deferral row names the input stream and the learner's pipeline, whose tasks a promotion wakes.
        assertThat(deferrals).isInstanceOf(DeferralsDao.class);
        assertThat(deferrals.of(new Learner(pipeline.getUuid(), "learningFlow"), raw.getId()))
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
        final DocRef docRef = learningFlowStore.createDocument("door-access-unbound");
        learningFlowStore.writeDocument(learningFlowStore.readDocument(docRef)
                .copy()
                .learningMode(LearningMode.DISABLED)
                .build());
        return docRef;
    }

    private DocRef pipeline(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("learningFlow", LearningFlowParser.TYPE))
                .addElement(new PipelineElement("recordOutputFilter", "RecordOutputFilter"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "learningFlow")
                .addLink("learningFlow", "recordOutputFilter")
                .addLink("recordOutputFilter", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("learningFlow", "learningFlow", doc))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "segmentOutput", true))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        learner = new Learner(pipelineRef.getUuid(), "learningFlow");
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
