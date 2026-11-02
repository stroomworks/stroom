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
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.PermissionInheritance;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipelinefactory.impl.Combination;
import stroom.pipelinefactory.impl.Combinations;
import stroom.pipelinefactory.impl.MetCombination;
import stroom.pipelinefactory.impl.MetCombination.State;
import stroom.pipelinefactory.impl.PipelineFactoryRunner;
import stroom.pipelinefactory.impl.PipelineFactoryStore;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.security.api.SecurityContext;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.test.CommonTestScenarioCreator;
import stroom.test.StoreCreationTool;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// A Pipeline Factory run over real streams (design 06, phase I slice 3): it meets each combination
/// once, makes its pipeline, leaves a pipeline a person deleted alone, and makes it afresh once forgotten.
class TestPipelineFactoryRunsOverTheStreams extends AbstractCoreIntegrationTest {

    @Inject
    private PipelineFactoryRunner runner;
    @Inject
    private PipelineFactoryStore factoryStore;
    @Inject
    private Combinations combinations;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private ExplorerService explorerService;
    @Inject
    private StoreCreationTool storeCreationTool;
    @Inject
    private CommonTestScenarioCreator streams;
    @Inject
    private SecurityContext securityContext;

    private String syslog;
    private String audit;
    private String firewall;

    @BeforeEach
    void setUp() {
        final String suffix = String.valueOf(System.nanoTime());
        syslog = "SYSLOG_" + suffix;
        audit = "AUDIT_" + suffix;
        firewall = "FIREWALL_" + suffix;
        List.of(syslog, audit, firewall).forEach(storeCreationTool::getOrCreateFeedDoc);
    }

    @Test
    void eachCombinationGetsOnePipelineAndOnlyTheFactorysStreamsAreLookedAt() {
        final PipelineFactoryDoc factory = factory();
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);
        streams.createSample2LineRawFile(audit, StreamTypeNames.RAW_EVENTS);
        // Not raw, so outside the factory's filter.
        streams.createSample2LineRawFile(firewall, StreamTypeNames.EVENTS);

        runner.run(factory);

        assertThat(met(factory)).extracting(MetCombination::combination, MetCombination::state)
                .containsExactly(
                        tuple(raw(syslog), State.MADE),
                        tuple(raw(audit), State.MADE));
        assertThat(pipelineNamed(syslog + " Raw Events")).hasSize(1);
        assertThat(pipelineNamed(audit + " Raw Events")).hasSize(1);

        // More of what it has met, and something new.
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);
        streams.createSample2LineRawFile(firewall, StreamTypeNames.RAW_EVENTS);
        runner.run(factory);

        assertThat(met(factory)).extracting(MetCombination::combination)
                .containsExactly(raw(syslog), raw(audit), raw(firewall));
        assertThat(pipelineNamed(syslog + " Raw Events")).hasSize(1);
    }

    @Test
    void aPipelineAPersonDeletedIsNotMadeAgainUntilItsCombinationIsForgotten() {
        final PipelineFactoryDoc factory = factory();
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);
        runner.run(factory);
        final DocRef made = met(factory).getFirst().pipeline();

        explorerService.delete(List.of(explorerService.getFromDocRef(made).orElseThrow()));
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);
        runner.run(factory);

        assertThat(pipelineNamed(syslog + " Raw Events")).isEmpty();
        assertThat(met(factory)).extracting(MetCombination::state).containsExactly(State.MADE);

        // Forgotten, the combination's next stream makes it afresh.
        assertThat(combinations.forget(factory.getUuid(), raw(syslog))).isTrue();
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);
        runner.run(factory);

        assertThat(pipelineNamed(syslog + " Raw Events")).hasSize(1);
        assertThat(met(factory).getFirst().pipeline()).isNotEqualTo(made);
    }

    @Test
    void theJobRunsEachEnabledFactoryThatIsSaved() {
        final DocRef saved = explorerService.create(PipelineFactoryDoc.TYPE, "raw events " + syslog,
                explorerService.ensureFolderPath(DocPath.fromParts("Factories"), PermissionInheritance.DESTINATION),
                PermissionInheritance.DESTINATION).getDocRef();
        final PipelineFactoryDoc factory = factoryStore.writeDocument(factory().copy()
                .uuid(saved.getUuid())
                .name(saved.getName())
                .version(factoryStore.readDocument(saved).getVersion())
                .build());
        streams.createSample2LineRawFile(syslog, StreamTypeNames.RAW_EVENTS);

        runner.exec();

        assertThat(met(factory)).extracting(MetCombination::combination).containsExactly(raw(syslog));

        // Deleted where a person deletes it, its record goes too; the pipeline it made stays.
        explorerService.delete(List.of(explorerService.getFromDocRef(saved).orElseThrow()));
        assertThat(met(factory)).isEmpty();
        assertThat(pipelineNamed(syslog + " Raw Events")).hasSize(1);
    }

    private PipelineFactoryDoc factory() {
        final DocRef template = pipelineStore.createDocument("template " + syslog);
        pipelineStore.writeDocument(pipelineStore.readDocument(template).copy()
                .pipelineData(new PipelineDataBuilder()
                        .addElement(new PipelineElement("Source", "Source"))
                        .addElement(new PipelineElement("translationFilter", "XSLTFilter"))
                        .addLink("Source", "translationFilter")
                        .build())
                .build());
        final ExpressionOperator.Builder feeds = ExpressionOperator.builder().op(Op.OR);
        List.of(syslog, audit, firewall).forEach(feed -> feeds.addTextTerm(MetaFields.FEED, Condition.EQUALS, feed));
        return PipelineFactoryDoc.builder()
                .uuid("factory-" + syslog)
                .name("raw events " + syslog)
                .enabled(true)
                .streamFilter(ExpressionOperator.builder()
                        .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                        .addOperator(feeds.build())
                        .build())
                .folderPattern("Made/${feed}")
                .template(template)
                .runAsUser(securityContext.getUserRef())
                .build();
    }

    private List<MetCombination> met(final PipelineFactoryDoc factory) {
        return combinations.list(factory.getUuid());
    }

    private static Combination raw(final String feed) {
        return Combination.ofFeedAndType(feed, StreamTypeNames.RAW_EVENTS);
    }

    private List<DocRef> pipelineNamed(final String name) {
        return pipelineStore.list().stream().filter(pipeline -> name.equals(pipeline.getName())).toList();
    }
}
