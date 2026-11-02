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
import stroom.docref.DocRef;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.pipelinefactory.impl.Combination;
import stroom.pipelinefactory.impl.Made;
import stroom.pipelinefactory.impl.PipelineMaker;
import stroom.pipelinefactory.impl.PipelineMakerException;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryFilterSettings;
import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.processor.impl.ProcessorConfig;
import stroom.processor.shared.ProcessorFilter;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.query.api.datasource.QueryField;
import stroom.security.api.SecurityContext;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.element.ShapeshifterAiParser;
import stroom.test.AbstractCoreIntegrationTest;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/// A Pipeline Factory making the pipeline and filter for one combination, against the application's own
/// explorer, pipeline store and processor (design 06, phase I slice 2).
class TestPipelineFactoryMakesPipelines extends AbstractCoreIntegrationTest {

    private static final ExpressionOperator RAW_EVENTS = ExpressionOperator.builder()
            .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
            .build();
    private static final Combination SYSLOG = Combination.ofFeedAndType("SYSLOG", StreamTypeNames.RAW_EVENTS);

    @Inject
    private PipelineMaker maker;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
    @Inject
    private SecurityContext securityContext;
    @Inject
    private Provider<ProcessorConfig> processorConfigProvider;

    private boolean claiming;

    @BeforeEach
    void setUp() {
        claiming = processorConfigProvider.get().isClaimTasksOnWorker();
    }

    @AfterEach
    void tearDown() {
        processorConfigProvider.get().setClaimTasksOnWorker(claiming);
    }

    @Test
    void aCombinationGetsAPipelineInheritingTheTemplateAndAnEnabledFilter() {
        final DocRef template = xsltTemplate();
        final PipelineFactoryDoc factory = factory(template)
                .properties(List.of(new PipelineFactoryProperty("translationFilter", "xsltNamePattern",
                        "${feed}-${type}")))
                .filterSettings(PipelineFactoryFilterSettings.builder().priority(7).maxProcessingTasks(3).build())
                .build();

        final Made made = maker.make(factory, SYSLOG);

        assertThat(made.pipeline().getName()).isEqualTo("SYSLOG Raw Events");
        final PipelineDoc pipeline = pipelineStore.readDocument(made.pipeline());
        assertThat(pipeline.getParentPipeline()).isEqualTo(template);
        assertThat(pipeline.getPipelineData().getAddedProperties())
                .extracting(PipelineProperty::getElement, PipelineProperty::getName,
                        property -> property.getValue().getString())
                .containsExactly(tuple(
                        "translationFilter", "xsltNamePattern", "SYSLOG-Raw Events"));

        final ProcessorFilter filter = made.filter();
        assertThat(filter.getPipelineUuid()).isEqualTo(made.pipeline().getUuid());
        assertThat(filter.isEnabled()).isTrue();
        assertThat(filter.isReprocess()).isFalse();
        assertThat(filter.getPriority()).isEqualTo(7);
        assertThat(filter.getMaxProcessingTasks()).isEqualTo(3);
        assertThat(filter.getRunAsUser()).isEqualTo(factory.getRunAsUser());
        assertThat(filter.getQueryData().getExpression().getChildren()).containsExactly(
                RAW_EVENTS,
                text(MetaFields.FEED, "SYSLOG"),
                text(MetaFields.TYPE, StreamTypeNames.RAW_EVENTS));
    }

    @Test
    void aPipelineKeyNamesWhatItMakesAfterThePipelineThatWroteTheStreams() {
        final DocRef writer = pipelineStore.createDocument("Writer");
        final PipelineFactoryDoc factory = factory(xsltTemplate())
                .key(List.of("Pipeline"))
                .folderPattern("By writer")
                .namePattern("${pipeline} indexed")
                .build();

        final Made made = maker.make(factory, new Combination(Map.of("Pipeline", writer.getUuid())));

        assertThat(made.pipeline().getName()).isEqualTo("Writer indexed");
    }

    @Test
    void aPropertyOnAnElementTheTemplateLacksMakesNothing() {
        final PipelineFactoryDoc factory = factory(xsltTemplate())
                .namePattern("${feed} missing element")
                .properties(List.of(new PipelineFactoryProperty("nowhere", "xsltNamePattern", "${feed}")))
                .build();

        assertThatThrownBy(() -> maker.make(factory, SYSLOG))
                .isInstanceOf(PipelineMakerException.class)
                .hasMessageContaining("has no element 'nowhere'");
        assertThat(pipelineNamed("SYSLOG missing element")).isEmpty();
    }

    @Test
    void aTemplateThatIsNotThereMakesNothing() {
        // Without a template a made pipeline inherits nothing and its filter errors on every stream. A
        // factory that writes no properties must still find its template first.
        final DocRef gone = PipelineDoc.buildDocRef().uuid("no-such-template").name("Gone").build();
        final PipelineFactoryDoc factory = factory(gone).namePattern("${feed} orphan").build();

        assertThatThrownBy(() -> maker.make(factory, SYSLOG))
                .isInstanceOf(PipelineMakerException.class)
                .hasMessage("The template Gone does not exist");
        assertThat(pipelineNamed("SYSLOG orphan")).isEmpty();
    }

    @Test
    void aPropertyThatIsNotTextMakesNothing() {
        final PipelineFactoryDoc factory = factory(xsltTemplate())
                .namePattern("${feed} not text")
                .properties(List.of(new PipelineFactoryProperty("translationFilter", "usePool", "${feed}")))
                .build();

        assertThatThrownBy(() -> maker.make(factory, SYSLOG))
                .isInstanceOf(PipelineMakerException.class)
                .hasMessageContaining("a factory writes only text");
        assertThat(pipelineNamed("SYSLOG not text")).isEmpty();
    }

    @Test
    void aPipelineWhoseFilterIsRefusedIsDeletedAgain() {
        // An AI template while this node does not claim its own tasks: the processor refuses the filter,
        // and a pipeline left without one would be made a second time when the combination is tried again.
        processorConfigProvider.get().setClaimTasksOnWorker(false);
        final PipelineFactoryDoc factory = factory(aiTemplate()).namePattern("${feed} supervised").build();

        assertThatThrownBy(() -> maker.make(factory, SYSLOG))
                .isInstanceOf(PipelineMakerException.class)
                .hasMessageContaining("claimTasksOnWorker");
        assertThat(pipelineNamed("SYSLOG supervised")).isEmpty();

        processorConfigProvider.get().setClaimTasksOnWorker(true);
        assertThat(maker.make(factory, SYSLOG).pipeline().getName()).isEqualTo("SYSLOG supervised");
    }

    private PipelineFactoryDoc.Builder factory(final DocRef template) {
        return PipelineFactoryDoc.builder()
                .uuid("factory")
                .name("raw events")
                .enabled(true)
                .streamFilter(RAW_EVENTS)
                .template(template)
                .runAsUser(securityContext.getUserRef());
    }

    private DocRef xsltTemplate() {
        return template("XSLT template", new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("translationFilter", "XSLTFilter"))
                .addLink("Source", "translationFilter")
                .build());
    }

    private DocRef aiTemplate() {
        final DocRef doc = shapeshifterAiStore.createDocument("factory-made AI");
        return template("AI template", new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement("shapeshifterAi", ShapeshifterAiParser.TYPE))
                .addLink("Source", "shapeshifterAi")
                .addProperty(PipelineDataUtil.createProperty("shapeshifterAi", "shapeshifterAi", doc))
                .build());
    }

    private DocRef template(final String name, final PipelineData data) {
        final DocRef template = pipelineStore.createDocument(name);
        pipelineStore.writeDocument(pipelineStore.readDocument(template).copy().pipelineData(data).build());
        return template;
    }

    private List<DocRef> pipelineNamed(final String name) {
        return pipelineStore.list().stream().filter(pipeline -> name.equals(pipeline.getName())).toList();
    }

    private static ExpressionItem text(final QueryField field, final String value) {
        return ExpressionOperator.builder()
                .addTextTerm(field, Condition.EQUALS, value)
                .build()
                .getChildren()
                .getFirst();
    }
}
