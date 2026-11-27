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
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.element.AttemptRetention;
import stroom.learningflow.element.LearningFlowParser;
import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.scenario.AdvisorHolder;
import stroom.learningflow.scenario.Scenarios;
import stroom.learningflow.scenario.Script;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Learner;
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
import stroom.processor.impl.ProcessorConfig;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests, in a running node, that learning stops when its pipeline is deleted.
///
/// Deleting a pipeline through its store raises an event that marks the pipeline's learning as deleted and
/// drops the deferral of any stream waiting on it, because no task of that pipeline will run the stream
/// again. A pipeline deleted while no node received the event is found later by the nightly retention job.
class TestADeletedPipelinesLearningInANode extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-DELETED";
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

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
    private Deferrals deferrals;
    @Inject
    private DeletedPipelines deletedPipelines;
    @Inject
    private AttemptRetention attemptRetention;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;

    @BeforeEach
    void setUp() {
    }

    @Test
    void aPipelineDeletedThroughItsStoreIsHeardOf() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef pipeline = pipeline(feed, document());
        final Learner learner = new Learner(pipeline.getUuid(), "learningFlow");
        filter(pipeline);
        // Learning is disabled, so the stream is deferred and the model is never asked.
        advisor.set(Script.of());
        final Meta raw = rawStream();
        commonTranslationTestHelper.processAll();
        assertThat(deferrals.of(learner, raw.getId())).describedAs("the stream waits").isPresent();
        assertThat(deletedPipelines.isDeleted(pipeline.getUuid())).isFalse();

        pipelineStore.deleteDocument(pipeline);

        assertThat(deletedPipelines.isDeleted(pipeline.getUuid()))
                .describedAs("the delete event marks its learning deleted")
                .isTrue();
        assertThat(deferrals.of(learner, raw.getId()))
                .describedAs("and the stream is let go: no task of the pipeline will run it again")
                .isEmpty();
    }

    @Test
    void aPipelineGoneWithoutBeingHeardIsFoundAtMidnight() {
        // A deferral for a pipeline that does not exist, as if it was deleted while no node was running.
        final String gone = UUID.randomUUID().toString();
        deferrals.deferred(new Learner(gone, "learningFlow"), "doc-gone", 1L, FEED, StreamTypeNames.RAW_EVENTS,
                null, "Waiting.", 0);

        attemptRetention.exec();

        assertThat(deletedPipelines.isDeleted(gone)).isTrue();
        assertThat(deferrals.of(new Learner(gone, "learningFlow"), 1L)).isEmpty();
    }

    private DocRef document() {
        final DocRef docRef = learningFlowStore.createDocument("door-access-deleted");
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
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private void filter(final DocRef pipeline) {
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
}
