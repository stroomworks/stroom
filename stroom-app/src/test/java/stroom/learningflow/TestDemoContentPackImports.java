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

import stroom.docref.DocRef;
import stroom.feed.api.FeedStore;
import stroom.importexport.impl.ImportExportService;
import stroom.importexport.shared.ImportSettings;
import stroom.importexport.shared.ImportSettings.ImportMode;
import stroom.importexport.shared.ImportState;
import stroom.learningflow.doc.LearningFlowStore;
import stroom.learningflow.pack.DemoContentPack;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.shared.ProcessorFilter;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.util.shared.Message;
import stroom.util.shared.Severity;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests that the Learning Flow demo content pack imports through the real importer and that the imported
/// documents can be opened.
///
/// `TestDemoContentPack` reads the pack's files with the same classes the importer uses, but cannot check
/// that an actual import succeeds, which is what this test does.
class TestDemoContentPackImports extends AbstractCoreIntegrationTest {

    @Inject
    private ImportExportService importExportService;
    @Inject
    private LearningFlowStore learningFlowStore;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private FeedStore feedStore;
    @Inject
    private ProcessorFilterService processorFilterService;

    @TempDir
    private Path tempDir;

    @Test
    void everyDocumentInThePackIsThereToOpenAfterwards() {
        final List<ImportState> imported = importThePack();

        assertThat(problems(imported)).isEmpty();

        final LearningFlowDoc document = learningFlowStore.readDocument(DemoContentPack.documentRef());
        assertThat(document).isNotNull();
        assertThat(document.getName()).isEqualTo(DemoContentPack.DOCUMENT_NAME);
        // The document must refer to the model shipped in the pack by UUID, not to any model of the same name.
        assertThat(document.getModel()).isNotNull();
        assertThat(document.getModel().getUuid()).isEqualTo(DemoContentPack.MODEL_UUID);
        assertThat(document.getFlow().getSteps()).isNotEmpty();

        DemoContentPack.FEEDS.forEach(feed -> {
            final PipelineDoc pipeline = pipelineStore.readDocument(DemoContentPack.pipelineRef(feed));
            assertThat(pipeline).describedAs(feed + "'s pipeline").isNotNull();
            assertThat(pipeline.getPipelineData().getElements().getAdd())
                    .anySatisfy(element -> assertThat(element.getId()).isEqualTo(DemoContentPack.STAGE_ELEMENT));
        });

        assertThat(feedStore.list().stream().map(DocRef::getName).toList())
                .containsAll(DemoContentPack.FEEDS);
    }

    /// Checks that each processor filter is imported onto its feed's pipeline and selects that feed, so that
    /// posting data is all a person has to do. The filters arrive disabled, because an import only enables
    /// filters when asked to, which on the import screen is the **Enable Processor Filters** tick box. The
    /// pack's README must tell the reader this.
    @Test
    void eachFilterArrivesOverItsFeedsPipelineAndNamesItsFeed() {
        importThePack(false);

        DemoContentPack.FEEDS.forEach(feed -> {
            final ProcessorFilter filter = onlyFilter(feed);
            assertThat(filter.getUuid()).isEqualTo(DemoContentPack.filterRef(feed).getUuid());
            assertThat(filter.isEnabled()).isFalse();
            assertThat(filter.getQueryData().getExpression().toString()).contains(feed);
        });
    }

    @Test
    void andTheyAreMakingTasksWhenTheImportIsToldToEnableFilters() {
        importThePack(true);

        DemoContentPack.FEEDS.forEach(feed -> assertThat(onlyFilter(feed).isEnabled()).isTrue());
    }

    private ProcessorFilter onlyFilter(final String feed) {
        final List<ProcessorFilter> filters = processorFilterService
                .find(DemoContentPack.pipelineRef(feed))
                .getValues();
        assertThat(filters).describedAs(feed + "'s pipeline has its filter").hasSize(1);
        return filters.getFirst();
    }

    /// Checks that the Learning Flow element in each pipeline refers to the imported document. Otherwise data
    /// posted to the demo would reach an element with no settings to run under.
    @Test
    void theStageInEachPipelineNamesTheDocumentThatWasImported() {
        importThePack();

        DemoContentPack.FEEDS.forEach(feed -> assertTheStageNamesTheDocument(feed));
    }

    private void assertTheStageNamesTheDocument(final String feed) {
        final PipelineDoc pipeline = pipelineStore.readDocument(DemoContentPack.pipelineRef(feed));
        final DocRef named = pipeline.getPipelineData().getProperties().getAdd().stream()
                .filter(property -> DemoContentPack.STAGE_ELEMENT.equals(property.getElement()))
                .filter(property -> "learningFlow".equals(property.getName()))
                .map(property -> property.getValue().getEntity())
                .findFirst()
                .orElse(null);

        assertThat(named).isNotNull();
        assertThat(learningFlowStore.readDocument(named)).isNotNull();
    }

    private List<ImportState> importThePack() {
        return importThePack(false);
    }

    private List<ImportState> importThePack(final boolean enableFilters) {
        final Path zip = tempDir.resolve("learning-flow-demo.zip");
        DemoContentPack.writeZip(zip);

        // Import as the import screen does: a confirmation pass that lists what would change, then every
        // change accepted, then the import itself.
        final List<ImportState> confirmations = importExportService.importConfig(
                zip, ImportSettings.createConfirmation(), new ArrayList<>());
        assertThat(confirmations).isNotEmpty();
        confirmations.forEach(state -> state.setAction(true));

        return importExportService.importConfig(
                zip,
                ImportSettings.builder()
                        .importMode(ImportMode.ACTION_CONFIRMATION)
                        .enableFilters(enableFilters)
                        .build(),
                confirmations);
    }

    /// Collects every error the importer reported, prefixed with the document it concerns. An import
    /// records what it could not do instead of throwing, so without this check a partly imported pack would
    /// look like a successful one.
    private static List<String> problems(final List<ImportState> states) {
        return states.stream()
                .flatMap(state -> state.getMessageList().stream()
                        .filter(message -> Severity.ERROR.equals(message.getSeverity())
                                           || Severity.FATAL_ERROR.equals(message.getSeverity()))
                        .map(message -> state.getDocRef() + ": " + messageText(message)))
                .toList();
    }

    private static String messageText(final Message message) {
        return message.getMessage();
    }
}
