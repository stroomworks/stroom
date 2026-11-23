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
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.element.LearningFlowParser;
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
import stroom.test.StoreCreationTool;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// Track 1 task 5 (simplification option H): **a supervised pipeline runs with either way of getting
/// tasks to workers.**
///
/// It used to be refused unless `stroom.processor.claimTasksOnWorker` was on, because "a wake writes rows
/// the worker reads, which a master-fed task queue would not see". That reason does not hold: deferring,
/// waking and the retry sweep are plain database updates, the same in both modes, and the master queue
/// re-reads `CREATED` tasks from the database every time it fills — a woken task is exactly what that
/// query finds. The requirement is gone, and this is what replaces the test of it.
///
/// Run with the setting both off and on, because off is the default and was the refused case.
class TestASupervisedPipelineRunsInEitherMode extends AbstractCoreIntegrationTest {

    private static final String FEED = "DOOR-ACCESS-CLAIMING";

    private boolean claiming;

    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private ProcessorFilterService processorFilterService;
    @Inject
    private LearningFlowStore learningFlowStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;

    @BeforeEach
    void setUp() {
        claiming = processorConfigProvider.get().isClaimTasksOnWorker();
    }

    @AfterEach
    void tearDown() {
        processorConfigProvider.get().setClaimTasksOnWorker(claiming);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFilterOverASupervisedPipelineIsMadeWhicheverWayTasksReachWorkers(final boolean claimTasksOnWorker) {
        processorConfigProvider.get().setClaimTasksOnWorker(claimTasksOnWorker);
        final DocRef feed = storeCreationTool.getOrCreateFeedDoc(FEED);
        final DocRef doc = learningFlowStore.createDocument("door-access-claiming");

        assertThat(processorFilterService.create(request(supervised(feed, doc))))
                .describedAs("a pipeline holding a Learning Flow stage")
                .isNotNull();
        assertThat(processorFilterService.create(request(inheriting(feed, doc))))
                .describedAs("and one that only inherits the stage from a parent")
                .isNotNull();
        assertThat(processorFilterService.create(request(plain())))
                .describedAs("and one with no such stage, as it always was")
                .isNotNull();
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
                .addElement(new PipelineElement("learningFlow", LearningFlowParser.TYPE))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addElement(new PipelineElement("streamAppender", "StreamAppender"))
                .addLink("Source", "learningFlow")
                .addLink("learningFlow", "xmlWriter")
                .addLink("xmlWriter", "streamAppender")
                .addProperty(PipelineDataUtil.createProperty("learningFlow", "learningFlow", doc))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "feed", feed))
                .addProperty(PipelineDataUtil.createProperty("streamAppender", "streamType", StreamTypeNames.EVENTS))
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " supervised " + System.nanoTime());
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }

    /// A pipeline that holds no Learning Flow element of its own and inherits one from a parent: the
    /// shape the check answered "not supervised" for whenever it could not read the parent, and so the
    /// shape most worth proving runs in both modes.
    ///
    /// @param feed The feed its appender writes to.
    /// @param doc  The Learning Flow document the parent's stage runs under.
    /// @return The pipeline.
    private DocRef inheriting(final DocRef feed, final DocRef doc) {
        final DocRef parent = supervised(feed, doc);
        final DocRef child = pipelineStore.createDocument(FEED + " inheriting " + System.nanoTime());
        pipelineStore.writeDocument(pipelineStore.readDocument(child).copy()
                .parentPipeline(parent)
                .build());
        return child;
    }

    private DocRef plain() {
        final PipelineData data = new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("xmlParser", "XMLParser"))
                .addElement(new PipelineElement("xmlWriter", "XMLWriter"))
                .addLink("Source", "xmlParser")
                .addLink("xmlParser", "xmlWriter")
                .build();
        final DocRef pipelineRef = pipelineStore.createDocument(FEED + " plain " + System.nanoTime());
        pipelineStore.writeDocument(pipelineStore.readDocument(pipelineRef).copy().pipelineData(data).build());
        return pipelineRef;
    }
}
