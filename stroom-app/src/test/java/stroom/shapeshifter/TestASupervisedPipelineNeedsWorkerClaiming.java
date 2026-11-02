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
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.StoreCreationTool;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Design 04 §7, phase 6: **a supervised pipeline needs the node to claim its own tasks.** A deferred task
/// is woken by a row the worker writes, which a master-fed queue never sees, so a filter over a pipeline
/// with a Shapeshifter AI stage is refused while `stroom.processor.claimTasksOnWorker` is off, and says
/// which setting to change. A filter over a pipeline with no such stage is made as it always was, and so
/// is one over the supervised pipeline once the node claims its own.
class TestASupervisedPipelineNeedsWorkerClaiming extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-CLAIMING";

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;

    @BeforeEach
    void setUp() {
        processorConfigProvider.get().setClaimTasksOnWorker(false);
    }

    @Test
    void aFilterOverASupervisedPipelineIsRefusedUntilTheNodeClaimsItsOwnTasks() {
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = shapeshifterAiStore.createDocument("door-access-claiming");
        final DocRef supervised = supervised(feed, doc);
        final DocRef plain = plain();

        assertThatThrownBy(() -> processorFilterService.create(request(supervised)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supervised stage")
                .hasMessageContaining("stroom.processor.claimTasksOnWorker");
        assertThat(processorFilterService.create(request(plain)))
                .describedAs("a pipeline with no supervised stage is as it always was").isNotNull();

        processorConfigProvider.get().setClaimTasksOnWorker(true);
        assertThat(processorFilterService.create(request(supervised))).isNotNull();
    }

    private static CreateProcessFilterRequest request(final DocRef pipeline) {
        return CreateProcessFilterRequest.builder()
                .pipeline(pipeline)
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(ExpressionOperator.builder()
                                .addTextTerm(MetaFields.FEED, Condition.EQUALS, FEED)
                                .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                                .build())
                        .build())
                .priority(1)
                .build();
    }

    private DocRef supervised(final DocRef feed, final DocRef doc) {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "shapeshifterAi")
                .addLink("shapeshifterAi", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    private DocRef plain() {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("xmlParser", "XMLParser"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addLink("Source", "xmlParser")
                .addLink("xmlParser", "xmlWriter")
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " plain");
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }
}
