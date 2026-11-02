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
import stroom.processor.impl.ProcessorConfig;
import stroom.processor.shared.CreateProcessFilterRequest;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.AttemptRetention;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.scenario.AdvisorHolder;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.ai.scenario.Script;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.shared.LearningMode;
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

/// Design 06 A120, A134, A135, in a node: a pipeline deleted through its store is heard of by its
/// learning, which is marked deleted and lets go of the stream waiting on it; and a pipeline whose
/// deletion no node heard is found by the retention job.
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
    private ShapeshifterAiStore shapeshifterAiStore;
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
        // A filter over a supervised pipeline needs the node to claim its own tasks (design 04 §7).
        processorConfigProvider.get().setClaimTasksOnWorker(true);
    }

    @Test
    void aPipelineDeletedThroughItsStoreIsHeardOf() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef pipeline = pipeline(feed, document());
        final Learner learner = new Learner(pipeline.getUuid(), "shapeshifterAi");
        filter(pipeline);
        // Learning is off, so the stream waits on a row and nothing is asked.
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
        // Learning kept for a pipeline the node has no record of: deleted while no node was listening.
        final String gone = UUID.randomUUID().toString();
        deferrals.deferred(new Learner(gone, "shapeshifterAi"), "doc-gone", 1L, FEED, StreamTypeNames.RAW_EVENTS,
                null, "Waiting.", 0);

        attemptRetention.exec();

        assertThat(deletedPipelines.isDeleted(gone)).isTrue();
        assertThat(deferrals.of(new Learner(gone, "shapeshifterAi"), 1L)).isEmpty();
    }

    private DocRef document() {
        final DocRef docRef = shapeshifterAiStore.createDocument("door-access-deleted");
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
