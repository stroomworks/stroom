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

package stroom.shapeshifter.ai.pack;

import stroom.docref.DocRef;
import stroom.feed.shared.FeedDoc;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineProperty;
import stroom.processor.api.ProcessorFilterUtil;
import stroom.processor.shared.ProcessorFilter;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
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

/// Checks every document the demo content pack is made of.
///
/// The pack itself is written by [GenerateDemoContentPack], not here: what this is for is the documents
/// and how they name each other. What the *arrangement* does is
/// `TestScenario51AnyFormatFromAnyFeedInAPipeline`, which runs the pack's own document, and a pipeline and
/// filter per feed, over four formats in real pipelines.
///
/// **What this can prove and what it cannot.** Every file is read back with the very classes Stroom's
/// importer reads it with, so a property name that does not exist, a document that will not
/// deserialise, an asset with no `.node` beside it or a reference that names nothing in the pack are all
/// caught here. What is not caught is the importer's own behaviour, which needs a database; the format
/// is the one Stroom has always read and the pack is written to match an export of it file for file.
class TestDemoContentPack {

    /// Where the pack is committed, relative to the root of the repository.
    private static final String PACK = "shapeshifter-ai-demo-v1.0.zip";
    /// Where the data to post is committed, beside the pack.
    private static final String DATA = "demo-data";

    @Test
    void theDocumentIsTheOneScenario51Proves() {
        final ShapeshifterAiDoc document = read(ShapeshifterAiDoc.TYPE, ShapeshifterAiDoc.class);

        // Field for field the settings of design 02 §5 scenario 51. Not "much the same as": if this
        // fails, the pack has drifted from the only run that says it works.
        final ShapeshifterAiDoc scenario = DemoDocument
                .configure(ShapeshifterAiDoc.builder())
                .uuid(document.getUuid())
                .version(document.getVersion())
                .name(document.getName())
                .description(document.getDescription())
                .model(document.getModel())
                // The time budget is the environment's, as the model is: scenario 51's model is scripted
                // and its clock fixed, so the budget says nothing about what it proves.
                .attemptBudgetMs(document.getAttemptBudgetMs())
                // The stamp of the built-in question text is the store's to write, and the pack carries
                // it because the pack is written as a saved document would be.
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
                .describedAs("a pipeline per feed, as the Pipeline Factory makes them (A126)")
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

    /// A pipeline's elements travel in their own asset, and the one thing that would make the demo do
    /// nothing at all is a stage being attached to no document.
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
                .containsExactly("Source", "ShapeshifterAi", "SchemaFilter", "RecordOutputFilter",
                        "RecordCountFilter", "XMLWriter", "StreamAppender");
        final PipelineProperty attachment = data.getAddedProperties().stream()
                .filter(property -> DemoContentPack.STAGE_ELEMENT.equals(property.getElement())
                                    && "shapeshifterAi".equals(property.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the stage is attached to no document"));
        assertThat(attachment.getValue().getEntity())
                .isEqualTo(new DocRef(ShapeshifterAiDoc.TYPE, DemoContentPack.DOCUMENT_UUID,
                        DemoContentPack.DOCUMENT_NAME));
        assertThat(data.getAddedProperties())
                .describedAs("the appender names no feed, so each feed's events go back to that feed")
                .noneMatch(property -> "feed".equals(property.getName()));
    }

    /// A filter carrying an id, a processor or a tracker is refused on import, and the pack would then
    /// need a filter building by hand — which is most of what it exists to save.
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

    /// A document with no version cannot be written at all — the column it lands in does not take null —
    /// and the import records that against the document while the explorer entry is made anyway. What a
    /// person then sees is a pack that imported and a document that will not open.
    @Test
    void everyDocumentCarriesTheVersionASavedDocumentHas() {
        final Map<String, byte[]> files = DemoContentPack.files();
        final List<String> documents = files.keySet().stream()
                .filter(name -> name.endsWith(".meta"))
                // A processor filter is not a document in the store and has a version of its own kind.
                .filter(name -> !name.contains("." + ProcessorFilter.ENTITY_TYPE + "."))
                .toList();
        assertThat(documents).describedAs("the document, the model, and a pipeline and a feed per feed")
                .hasSize(2 + 2 * DemoContentPack.FEEDS.size());
        assertThat(documents).allSatisfy(name -> assertThat(
                        JsonUtil.readValue(files.get(name), Map.class).get("version"))
                .describedAs(name)
                .isNotNull());
    }

    /// Every asset has the `.node` beside it that tells the importer what it is, and every `.node` says
    /// the same thing about its document as the document's file name does.
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
                .allSatisfy(name -> assertThat(name).startsWith("stroomContent/Shapeshifter_AI_Demo/"));
    }

    /// Four files of four unrelated formats, so that a demo has something to post.
    @Test
    void thereIsSomethingToPost() {
        final Map<String, byte[]> files = DemoContentPack.files();
        assertThat(files.keySet()).filteredOn(name -> name.startsWith("data/"))
                .hasSize(DemoContentPack.FEEDS.size());
        assertThat(files).containsKey("README.md");
        assertThat(files.keySet()).filteredOn(name -> name.startsWith("data/"))
                .allSatisfy(name -> assertThat(files.get(name)).isNotEmpty());
    }

    /// The pack is committed to the repository, so it can fall behind the code that writes it — and a
    /// demo run from a stale zip is a demo of something nobody has tested. Regenerate it by running
    /// [GenerateDemoContentPack] with the path this looks in.
    @Test
    void theCommittedPackIsTheOneThisCodeWrites() throws IOException {
        final Path committed = repositoryRoot().resolve(PACK);
        assertThat(committed)
                .describedAs("the committed pack is missing; write it with GenerateDemoContentPack")
                .exists();

        assertThatZipHolds(committed, DemoContentPack.files());
    }

    /// One zip per feed, each holding the sample that feed is demonstrated with and a `.meta` naming the
    /// feed, so that posting it needs nothing said in the request. Committed beside the pack, and so
    /// checked against what the code writes, as the pack is.
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

    /// The directory the build is rooted in, found by climbing until the settings file appears rather
    /// than by counting `..` from wherever a test happens to be run.
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
