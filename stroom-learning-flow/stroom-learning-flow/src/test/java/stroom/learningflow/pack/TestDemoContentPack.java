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

package stroom.learningflow.pack;

import stroom.docref.DocRef;
import stroom.feed.shared.FeedDoc;
import stroom.learningflow.learning.Templates;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.processor.api.ProcessorFilterUtil;
import stroom.processor.shared.ProcessorFilter;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Checks every document in the demo content pack, and that the committed pack and data are up to date.
///
/// The pack is written by [GenerateDemoContentPack], not here. These tests check the documents and how they
/// refer to each other. Whether the arrangement works is tested by `TestScenario51AnyFormatFromAnyFeedInAPipeline`,
/// which runs the pack's own document, with a pipeline and filter per feed, over four formats in real pipelines.
///
/// Every file is read back with the same classes Stroom's importer uses, so a property name that does not exist,
/// a document that will not deserialise, an asset with no `.node` file beside it, or a reference to something
/// not in the pack is caught here. The importer's own behaviour is not tested, because it needs a database; the
/// pack is written to match an export file for file.
class TestDemoContentPack {

    /// The committed pack's path, relative to the root of the repository.
    private static final String PACK = "smart-pipelines-demo-v1.0.zip";
    /// The directory, beside the pack, where the demo data to post is committed.
    private static final String DATA = "demo-data";

    @Test
    void theDocumentIsTheOneScenario51Proves() {
        final LearningFlowDoc document = read(LearningFlowDoc.TYPE, LearningFlowDoc.class);

        // Field for field, the settings that TestScenario51AnyFormatFromAnyFeedInAPipeline runs with. If this
        // fails, the pack has drifted from the only test that shows it works.
        final LearningFlowDoc scenario = DemoDocument
                .configure(LearningFlowDoc.builder())
                .uuid(document.getUuid())
                .version(document.getVersion())
                .name(document.getName())
                .description(document.getDescription())
                .model(document.getModel())
                // The time budget depends on the environment, as the model does. The scenario's model is
                // scripted and its clock fixed, so the budget is irrelevant to what it tests.
                .attemptBudgetMs(document.getAttemptBudgetMs())
                // The built-in question text version is normally stamped by the store on save. The pack
                // carries it because the pack is written as a saved document would be.
                .flow(document.getFlow().withBuiltInVersion(Templates.VERSION))
                .build();
        assertThat(document).isEqualTo(scenario);
        assertThat(document.getAttemptBudgetMs())
                .describedAs("twenty minutes a run, since a real model is far slower than a scripted one")
                .isEqualTo(Duration.ofMinutes(20).toMillis());
        assertThat(document.getFlow().getBuiltInVersion())
                .describedAs("and a document that arrived without the stamp would say its templates "
                             + "were of no known version until someone saved it")
                .isEqualTo(Templates.VERSION);
        assertThat(document.getModel())
                .describedAs("and it names the model the pack ships, by the uuid the pack gives it")
                .isEqualTo(DemoContentPack.modelRef());
    }

    @Test
    void everyDocumentInThePackReadsBack() {
        assertThat(read(OpenAIModelDoc.TYPE, OpenAIModelDoc.class))
                .satisfies(model -> {
                    assertThat(model.getUuid()).isEqualTo(DemoContentPack.MODEL_UUID);
                    assertThat(model.getApiKeyName())
                            .describedAs("the key is named, never carried")
                            .isEqualTo(DemoContentPack.API_KEY_NAME);
                    assertThat(model.getBaseUrl()).isNotBlank();
                    assertThat(model.getModelId()).isNotBlank();
                });
        assertThat(readAll(PipelineDoc.TYPE, PipelineDoc.class))
                .describedAs("a pipeline per feed, as a Content Template makes them (A126)")
                .extracting(PipelineDoc::getUuid, PipelineDoc::getName)
                .containsExactlyInAnyOrderElementsOf(DemoContentPack.FEEDS.stream()
                        .map(DemoContentPack::pipelineRef)
                        .map(ref -> tuple(ref.getUuid(), ref.getName()))
                        .toList());

        final Map<String, byte[]> files = DemoContentPack.files();
        final List<String> feedFiles = files.keySet().stream()
                .filter(name -> name.contains("." + FeedDoc.TYPE + ".") && name.endsWith(".meta"))
                .toList();
        assertThat(feedFiles).hasSize(DemoContentPack.FEEDS.size());
        assertThat(feedFiles).allSatisfy(name -> assertThat(
                        JsonUtil.readValue(files.get(name), FeedDoc.class).getName())
                .isIn(DemoContentPack.FEEDS));
    }

    /// A pipeline's elements are in their own asset file. A Smart Pipeline element attached to no Learning
    /// Flow document would make the demo do nothing at all.
    @Test
    void everyStageIsAttachedToTheDocument() {
        final List<String> elements = pathsFor(PipelineDoc.TYPE, ".json");
        assertThat(elements).hasSize(DemoContentPack.FEEDS.size());
        elements.forEach(path -> assertTheStageIsAttached(
                JsonUtil.readValue(DemoContentPack.files().get(path), PipelineData.class)));
    }

    private static void assertTheStageIsAttached(final PipelineData data) {
        assertThat(data.getAddedElements())
                .extracting(element -> element.getType())
                .containsExactly("Source", "LearningFlow", "SchemaFilter", "RecordOutputFilter",
                        "RecordCountFilter", "XMLWriter", "StreamAppender");
        final PipelineProperty attachment = data.getAddedProperties().stream()
                .filter(property -> DemoContentPack.STAGE_ELEMENT.equals(property.getElement())
                                    && "learningFlow".equals(property.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the stage is attached to no document"));
        assertThat(attachment.getValue().getEntity())
                .isEqualTo(new DocRef(LearningFlowDoc.TYPE, DemoContentPack.DOCUMENT_UUID,
                        DemoContentPack.DOCUMENT_NAME));
        assertThat(data.getAddedProperties())
                .describedAs("the appender names no feed, so each feed's events go back to that feed")
                .noneMatch(property -> "feed".equals(property.getName()));
    }

    /// The document's translations are judged against one set of schemas and the pipeline's Schema filter
    /// validates against another, so the two must agree. The demo document names no target schema, so it works
    /// on any installation whatever version of event-logging is installed: it is asked for event-logging 3.0.0,
    /// which its content writes, and is judged against the `EVENTS` group, which each pipeline's Schema filter
    /// also validates against.
    @Test
    void thePipelinesValidateAgainstTheSchemasTheDocumentIsJudgedAgainst() {
        assertThat(DemoContentPack.document().getTargetSchema())
                .describedAs("a target would name an XMLSchema document by a uuid one installation has")
                .isNull();
        final List<String> elements = pathsFor(PipelineDoc.TYPE, ".json");
        elements.forEach(path -> {
            final PipelineData data = JsonUtil.readValue(DemoContentPack.files().get(path), PipelineData.class);
            assertThat(data.getAddedProperties())
                    .filteredOn(property -> "schemaGroup".equals(property.getName()))
                    .extracting(property -> property.getValue().getString())
                    .containsExactly("EVENTS");
        });
    }

    /// A filter carrying an id, a processor or a tracker is refused on import, and a person would then have to
    /// build each filter by hand, which is most of what the pack exists to save.
    @Test
    void everyFilterIsOneThatCanBeImported() {
        final List<ProcessorFilter> filters = readAll(ProcessorFilter.ENTITY_TYPE, ProcessorFilter.class);
        assertThat(filters).extracting(ProcessorFilter::getPipelineUuid)
                .describedAs("a filter per feed's pipeline")
                .containsExactlyInAnyOrderElementsOf(DemoContentPack.FEEDS.stream()
                        .map(feed -> DemoContentPack.pipelineRef(feed).getUuid())
                        .toList());
        DemoContentPack.FEEDS.forEach(feed -> assertImportable(filters.stream()
                .filter(filter -> DemoContentPack.pipelineRef(feed).getUuid().equals(filter.getPipelineUuid()))
                .findFirst()
                .orElseThrow(), feed));
    }

    private static void assertImportable(final ProcessorFilter filter, final String feed) {
        assertThat(ProcessorFilterUtil.shouldImport(filter)).isTrue();
        assertThat(filter.getId()).isNull();
        assertThat(filter.getProcessor()).isNull();
        assertThat(filter.getProcessorFilterTracker()).isNull();
        assertThat(filter.getPipelineUuid())
                .describedAs("the importer finds the filter's pipeline by this")
                .isEqualTo(DemoContentPack.pipelineRef(feed).getUuid());
        assertThat(filter.getProcessorUuid())
                .describedAs("and it makes the processor from this; a filter naming none fails on a "
                             + "null uuid while the rest of the pack lands")
                .isEqualTo(DemoContentPack.processorUuid(feed));
        assertThat(filter.getQueryData().getExpression().toString())
                .describedAs("and it takes its own feed's streams, and no other's")
                .contains(feed)
                .doesNotContain(DemoContentPack.FEEDS.stream().filter(other -> !other.equals(feed)).findFirst()
                        .orElseThrow());
    }

    /// A document with no version cannot be stored, because its database column does not accept null. The
    /// import records that as an error against the document but creates the explorer entry anyway, so a person
    /// would see a pack that imported and a document that will not open.
    @Test
    void everyDocumentCarriesTheVersionASavedDocumentHas() {
        final Map<String, byte[]> files = DemoContentPack.files();
        final List<String> documents = files.keySet().stream()
                .filter(name -> name.endsWith(".meta"))
                // A processor filter is not a stored document, and its version is of a different kind.
                .filter(name -> !name.contains("." + ProcessorFilter.ENTITY_TYPE + "."))
                .toList();
        assertThat(documents).describedAs("the document, the model, and a pipeline and a feed per feed")
                .hasSize(2 + 2 * DemoContentPack.FEEDS.size());
        assertThat(documents).allSatisfy(name -> assertThat(
                        JsonUtil.readValue(files.get(name), Map.class).get("version"))
                .describedAs(name)
                .isNotNull());
    }

    /// Every asset has a `.node` file beside it that tells the importer what it is, and every `.node` file
    /// agrees with the document's file name.
    @Test
    void everyAssetIsAnnouncedByANodeFile() {
        final Map<String, byte[]> files = DemoContentPack.files();
        final List<String> nodes = files.keySet().stream().filter(name -> name.endsWith(".node")).toList();
        assertThat(nodes).describedAs("and a filter per feed besides")
                .hasSize(2 + 3 * DemoContentPack.FEEDS.size());

        for (final String node : nodes) {
            final String prefix = node.substring(0, node.length() - ".node".length());
            assertThat(files).containsKey(prefix + ".meta");

            final Properties properties = properties(files.get(node));
            assertThat(properties.getProperty("path")).isEqualTo(DemoContentPack.FOLDER);
            final String uuid = properties.getProperty("uuid");
            final String type = properties.getProperty("type");
            assertThat(uuid).isNotBlank();
            assertThat(prefix)
                    .describedAs("the importer finds a document's assets by its name, type and uuid")
                    .endsWith(type + "." + uuid);
            assertThat(properties.getProperty("version"))
                    .describedAs("no version key, so the importer reads this as the format it is")
                    .isNull();
        }
        assertThat(files.keySet())
                .filteredOn(name -> name.startsWith("stroomContent/"))
                .allSatisfy(name -> assertThat(name).startsWith("stroomContent/Smart_Pipelines_Demo/"));
    }

    /// The pack includes one data file per feed, in unrelated formats, so a demo has something to post.
    @Test
    void thereIsSomethingToPost() {
        final Map<String, byte[]> files = DemoContentPack.files();
        assertThat(files.keySet()).filteredOn(name -> name.startsWith("data/"))
                .hasSize(DemoContentPack.FEEDS.size());
        assertThat(files).containsKey("README.md");
        assertThat(files.keySet()).filteredOn(name -> name.startsWith("data/"))
                .allSatisfy(name -> assertThat(files.get(name)).isNotEmpty());
    }

    /// The pack is committed to the repository, so it can fall behind the code that writes it, and a demo run
    /// from a stale zip demonstrates something nobody has tested. Regenerate it by running
    /// [GenerateDemoContentPack] with the path this test reads.
    @Test
    void theCommittedPackIsTheOneThisCodeWrites() throws IOException {
        final Path committed = repositoryRoot().resolve(PACK);
        assertThat(committed)
                .describedAs("the committed pack is missing; write it with GenerateDemoContentPack")
                .exists();

        assertThatZipHolds(committed, DemoContentPack.files());
    }

    /// There is one data zip per feed, holding that feed's demo sample and a `.meta` file naming the feed, so
    /// posting it needs no headers in the request. The zips are committed beside the pack and so are checked
    /// against what the code writes, as the pack is.
    @Test
    void everyFeedHasDataToPostAndItIsWhatThisCodeWrites() throws IOException {
        for (final String feed : DemoContentPack.FEEDS) {
            final Path committed = repositoryRoot().resolve(DATA).resolve(DemoContentPack.dataZipName(feed));
            assertThat(committed)
                    .describedAs("no data for " + feed + "; write it with GenerateDemoContentPack")
                    .exists();

            final Map<String, byte[]> expected = DemoContentPack.dataFiles(feed);
            assertThat(expected.keySet())
                    .describedAs("the meta comes before the data, which is what Stroom requires of a "
                                 + "zip that names its own feed")
                    .containsExactly("001.meta", "001.dat");
            assertThat(new String(expected.get("001.meta"), StandardCharsets.UTF_8))
                    .describedAs("and the meta names the feed and the type, colon-delimited as "
                                 + "AttributeMapUtil reads them")
                    .isEqualTo("Feed:" + feed + "\nType:Raw Events\n");
            assertThatZipHolds(committed, expected);
        }
    }

    private static void assertThatZipHolds(final Path zip, final Map<String, byte[]> expected)
            throws IOException {
        try (final ZipFile file = new ZipFile(zip.toFile())) {
            assertThat(file.stream().map(ZipEntry::getName))
                    .describedAs(zip + " holds exactly the files this code writes")
                    .containsExactlyInAnyOrderElementsOf(expected.keySet());
            for (final Map.Entry<String, byte[]> entry : expected.entrySet()) {
                try (final InputStream in = file.getInputStream(file.getEntry(entry.getKey()))) {
                    assertThat(in.readAllBytes())
                            .describedAs(entry.getKey() + " in " + zip + " has changed since it was written")
                            .isEqualTo(entry.getValue());
                }
            }
        }
    }

    /// Finds the repository root by climbing until `settings.gradle` appears, rather than by counting `..`
    /// from wherever the test happens to be run.
    private static Path repositoryRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        assertThat(directory).describedAs("no settings.gradle above " + Paths.get("").toAbsolutePath())
                .isNotNull();
        return directory;
    }

    private static <T> T read(final String type, final Class<T> clazz) {
        return JsonUtil.readValue(DemoContentPack.files().get(pathFor(type, ".meta")), clazz);
    }

    private static <T> List<T> readAll(final String type, final Class<T> clazz) {
        return pathsFor(type, ".meta").stream()
                .map(path -> JsonUtil.readValue(DemoContentPack.files().get(path), clazz))
                .toList();
    }

    private static List<String> pathsFor(final String type, final String extension) {
        return DemoContentPack.files().keySet().stream()
                .filter(name -> name.contains("." + type + ".") && name.endsWith(extension))
                .toList();
    }

    private static String pathFor(final String type, final String extension) {
        return DemoContentPack.files().keySet().stream()
                .filter(name -> name.contains("." + type + ".") && name.endsWith(extension))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + extension + " in the pack"));
    }

    private static Properties properties(final byte[] bytes) {
        final Properties properties = new Properties();
        try {
            properties.load(new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8)));
        } catch (final IOException e) {
            throw new AssertionError(e);
        }
        return properties;
    }
}
