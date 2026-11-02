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

import stroom.docref.DocRef;
import stroom.feed.api.FeedStore;
import stroom.importexport.impl.ImportExportService;
import stroom.importexport.shared.ImportSettings;
import stroom.importexport.shared.ImportSettings.ImportMode;
import stroom.importexport.shared.ImportState;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.shared.PipelineDoc;
import stroom.processor.api.ProcessorFilterService;
import stroom.processor.shared.ProcessorFilter;
import stroom.shapeshifter.ai.doc.ShapeshifterAiStore;
import stroom.shapeshifter.ai.pack.DemoContentPack;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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

/// The pack through the importer, which is the one thing `TestDemoContentPack` cannot reach: it reads
/// the pack's files with the classes the importer reads them with, but reading a file is not importing
/// it, and a demo is only a demo if what lands can be opened.
class TestDemoContentPackImports extends AbstractCoreIntegrationTest {

    @Inject
    private ImportExportService importExportService;
    @Inject
    private ShapeshifterAiStore shapeshifterAiStore;
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

        final ShapeshifterAiDoc document = shapeshifterAiStore.readDocument(DemoContentPack.documentRef());
        assertThat(document).isNotNull();
        assertThat(document.getName()).isEqualTo(DemoContentPack.DOCUMENT_NAME);
        // The stage calls the model that came with it, not one that happens to be named the same.
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

    /// The filters are what make posting data the only thing left to do. Each arrives over its feed's
    /// pipeline and names its feed — but switched off, because an import enables filters only when it is
    /// told to, which on the import screen is the **Enable Processor Filters** tick. The pack's README says
    /// so, and this says what the README has to say.
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

    /// The document the demo is pointed at must be the one each pipeline's stage names, or a person who
    /// posts data gets a stage configured by nothing.
    @Test
    void theStageInEachPipelineNamesTheDocumentThatWasImported() {
        importThePack();

        DemoContentPack.FEEDS.forEach(feed -> assertTheStageNamesTheDocument(feed));
    }

    private void assertTheStageNamesTheDocument(final String feed) {
        final PipelineDoc pipeline = pipelineStore.readDocument(DemoContentPack.pipelineRef(feed));
        final DocRef named = pipeline.getPipelineData().getProperties().getAdd().stream()
                .filter(property -> DemoContentPack.STAGE_ELEMENT.equals(property.getElement()))
                .filter(property -> "shapeshifterAi".equals(property.getName()))
                .map(property -> property.getValue().getEntity())
                .findFirst()
                .orElse(null);

        assertThat(named).isNotNull();
        assertThat(shapeshifterAiStore.readDocument(named)).isNotNull();
    }

    private List<ImportState> importThePack() {
        return importThePack(false);
    }

    private List<ImportState> importThePack(final boolean enableFilters) {
        final Path zip = tempDir.resolve("shapeshifter-ai-demo.zip");
        DemoContentPack.writeZip(zip);

        // As the import screen does it: a pass that says what would change, every change agreed to, and
        // then the import itself.
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

    /// Everything the importer said went wrong, named with the document it went wrong for: an import
    /// records what it could not do rather than throwing, so a pack that half-arrived looks like a pack
    /// that arrived.
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
