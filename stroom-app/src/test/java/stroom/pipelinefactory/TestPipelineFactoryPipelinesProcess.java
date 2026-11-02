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

package stroom.pipelinefactory;

import stroom.data.shared.StreamTypeNames;
import stroom.data.store.api.Store;
import stroom.data.store.api.Target;
import stroom.data.store.api.TargetUtil;
import stroom.docref.DocRef;
import stroom.meta.api.MetaProperties;
import stroom.meta.api.MetaService;
import stroom.meta.api.StandardHeaderArguments;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.MetaRow;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipelinefactory.impl.Combinations;
import stroom.pipelinefactory.impl.MetCombination;
import stroom.pipelinefactory.impl.PipelineFactoryRunner;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.processor.api.ProcessorResult;
import stroom.processor.impl.ProcessorTaskTestHelper;
import stroom.processor.shared.ProcessorTask;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.security.api.SecurityContext;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTranslationTestHelper;
import stroom.test.StoreCreationTool;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Phase I's exit (design 06 §7): a factory over raw events with an ordinary template makes a pipeline
/// and filter for each feed as its data arrives, and each filter processes its feed's streams, and only
/// its feed's.
class TestPipelineFactoryPipelinesProcess extends AbstractCoreIntegrationTest {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                                      + "<records><record>one</record></records>";

    @Inject
    private PipelineFactoryRunner runner;
    @Inject
    private Combinations combinations;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private Store streamStore;
    @Inject
    private MetaService metaService;
    @Inject
    private ProcessorTaskTestHelper processorTaskTestHelper;
    @Inject
    private CommonTranslationTestHelper commonTranslationTestHelper;
    @Inject
    private SecurityContext securityContext;

    @Test
    void eachFeedGetsAPipelineThatProcessesItsOwnStreams() {
        final String suffix = String.valueOf(System.nanoTime());
        final String syslog = "SYSLOG_" + suffix;
        final String audit = "AUDIT_" + suffix;
        List.of(syslog, audit).forEach(storeCreationTool::getOrCreateFeedDoc);
        final PipelineFactoryDoc factory = factory(suffix, List.of(syslog, audit));

        final Meta syslogStream = raw(syslog);
        final Meta auditStream = raw(audit);
        runner.run(factory);

        assertThat(combinations.list(factory.getUuid())).extracting(MetCombination::pipeline)
                .extracting(DocRef::getName)
                .containsExactly(syslog + " Raw Events", audit + " Raw Events");

        // The filters the factory made create the tasks, and each task runs its feed's pipeline.
        processorTaskTestHelper.createAndQueueTasks();
        final List<ProcessorTask> tasks = processorTaskTestHelper.assignTasks(10);
        assertThat(tasks).hasSize(2);
        for (final ProcessorTask task : tasks) {
            final ProcessorResult result = commonTranslationTestHelper.process(task);
            assertThat(result.getMarkerCount(Severity.SEVERITIES)).describedAs("errors processing " + task).isZero();
        }

        assertThat(processedBy(syslogStream)).containsExactly(syslog + " Raw Events");
        assertThat(processedBy(auditStream)).containsExactly(audit + " Raw Events");
    }

    private PipelineFactoryDoc factory(final String suffix, final List<String> feeds) {
        final DocRef template = pipelineStore.createDocument("XML template " + suffix);
        pipelineStore.writeDocument(pipelineStore.readDocument(template).copy()
                .pipelineData(new PipelineDataBuilder()
                        .addElement(new PipelineElement("Source", "Source"))
                        .addElement(new PipelineElement("Parser", "XMLParser"))
                        .addElement(new PipelineElement("XML writer", "XMLWriter"))
                        .addElement(new PipelineElement("Stream appender", "StreamAppender"))
                        .addLink("Source", "Parser")
                        .addLink("Parser", "XML writer")
                        .addLink("XML writer", "Stream appender")
                        .addProperty(PipelineDataUtil.createProperty(
                                "Stream appender", "streamType", StreamTypeNames.EVENTS))
                        .build())
                .build());
        final ExpressionOperator.Builder ours = ExpressionOperator.builder().op(Op.OR);
        feeds.forEach(feed -> ours.addTextTerm(MetaFields.FEED, Condition.EQUALS, feed));
        return PipelineFactoryDoc.builder()
                .uuid("factory-" + suffix)
                .name("raw events " + suffix)
                .enabled(true)
                .streamFilter(ExpressionOperator.builder()
                        .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                        .addOperator(ours.build())
                        .build())
                .folderPattern("Processed/${feed}")
                .template(template)
                .runAsUser(securityContext.getUserRef())
                .build();
    }

    private Meta raw(final String feed) {
        final MetaProperties properties = MetaProperties.builder()
                .feedName(feed)
                .typeName(StreamTypeNames.RAW_EVENTS)
                .build();
        try (final Target target = streamStore.openTarget(properties)) {
            TargetUtil.write(target, XML);
            target.getAttributes().put(StandardHeaderArguments.FEED, feed);
            return target.getMeta();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The names of the pipelines that wrote events from a stream.
    private Set<String> processedBy(final Meta input) {
        return metaService.findRelatedData(input.getId(), true).stream()
                .map(MetaRow::getMeta)
                .filter(meta -> Long.valueOf(input.getId()).equals(meta.getParentMetaId()))
                .filter(meta -> StreamTypeNames.EVENTS.equals(meta.getTypeName()))
                .map(meta -> pipelineStore.readDocument(
                        DocRef.builder().type("Pipeline").uuid(meta.getPipelineUuid()).build()).getName())
                .collect(Collectors.toSet());
    }
}
