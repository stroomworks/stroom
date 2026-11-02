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

import stroom.data.shared.StreamTypeNames;
import stroom.docref.DocRef;
import stroom.feed.shared.FeedDoc;
import stroom.meta.shared.MetaFields;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelineData;
import stroom.pipeline.shared.data.PipelineDataBuilder;
import stroom.pipeline.shared.data.PipelineDataUtil;
import stroom.pipeline.shared.data.PipelineElement;
import stroom.processor.shared.ProcessorFilter;
import stroom.processor.shared.ProcessorType;
import stroom.processor.shared.QueryData;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm.Condition;
import stroom.shapeshifter.ai.learning.Templates;
import stroom.shapeshifter.ai.scenario.Scenarios;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.json.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// The demo content pack: everything a person needs in front of them before they can watch this feature
/// learn a feed, as a zip Stroom will import.
///
/// Without it, seeing the feature work means building a Shapeshifter AI document, a model reference,
/// four feeds, a pipeline and a processor filter for each by hand, and getting every reference between them
/// right. That is an afternoon before anything happens, and an afternoon in which a demo can fail for
/// reasons that have nothing to do with what is being demonstrated.
///
/// The settings come from [DemoDocument], which is also what design 02 §5 scenario 51 is run with, so
/// the pack ships a configuration that has been seen to learn four unrelated formats rather than a
/// plausible one. What the pack adds is the wiring the scenario has no need of: the model the stage
/// calls, the feeds to point it at, and for each feed the pipeline the stage sits in and the filter
/// that processes it: one pipeline per feed, as the Pipeline Factory makes them (A103, A126), since a
/// learner learns one translation for whatever its pipeline is given.
///
/// **The written form** is the import/export format Stroom has always read: one `.node` file naming a
/// document's uuid, type, name and explorer path, beside the document's own assets under the same
/// prefix — `.meta` for every document's JSON, and `.json` for a pipeline's elements. It is written here
/// rather than exported from a running Stroom so that building the pack needs no database.
///
/// **No secret is in it.** The model document names a stored secret; the person running the demo creates
/// that secret with their own API key.
public final class DemoContentPack {

    public static final String FOLDER = "Shapeshifter AI Demo";
    public static final String DOCUMENT_NAME = "Any feed";
    public static final String MODEL_NAME = "Learning model";

    /// The name of a stored secret holding the API key. The pack carries the name; the key is the
    /// person's own and is never in source control.
    public static final String API_KEY_NAME = "shapeshifter-ai";

    /// Four feeds of four unrelated formats, and the document is told nothing about any of them.
    public static final List<String> FEEDS =
            List.of("DOOR-ACCESS", "APP-EVENTS", "FIREWALL", "MAINFRAME");

    /// Fixed so that importing the pack twice updates the same documents rather than making a second set.
    public static final String DOCUMENT_UUID = "bbc4cf72-ad52-463c-b6e8-e5cfd37f77a0";
    public static final String MODEL_UUID = "871334dc-5286-4409-8179-a5c27e4e2044";
    /// Each feed's pipeline, in the order of [#FEEDS].
    private static final List<String> PIPELINE_UUIDS = List.of(
            "898e3818-672a-4c2f-afd5-a72ef811c414",
            "11a98fbc-add1-4f91-b9ae-2ef1f2387bd4",
            "0a8aa433-33f0-44ec-8bb0-9e61a35ddb09",
            "46a38abf-4eda-4ae1-b787-53325cbdf858");
    /// Each feed's processor filter, in the order of [#FEEDS].
    private static final List<String> FILTER_UUIDS = List.of(
            "2cf19cba-6d01-44d2-90d7-a53514fbab43",
            "7a391b6b-8132-4c3b-a364-43e6dda83e72",
            "c83eb45c-c8ee-4063-9ae2-4e7f0fa4e78e",
            "433c53ed-6f3e-4d1d-b62c-9cb88cd1654a");
    /// The processor each feed's filter runs under, in the order of [#FEEDS]. An exported filter names
    /// its processor, and the importer makes the processor from that name where the installation has
    /// none; without it the import of the filter fails on a null uuid.
    private static final List<String> PROCESSOR_UUIDS = List.of(
            "0f1b9f8c-6d61-4f0c-9d0e-3a57ec5e3e9d",
            "9bfcb071-5082-4dd0-84d2-286d2c3885ff",
            "866168e8-ede7-457b-82d4-5b4a707cf9cd",
            "7189ecf5-6e9f-4745-915e-f4167afd64ed");
    private static final List<String> FEED_UUIDS = List.of(
            "757e9412-9c2a-4cc4-b46c-ecc16893cdec",
            "52ce424f-0d18-433e-9808-aa771f7d5171",
            "aebaa96e-fd68-4e46-9ed1-0a036609d5b4",
            "0d0a2c36-2a2a-4f07-9d06-1f4f1c1a2f65");

    /// The element id the document is attached to, which is also the name a person sees in the pipeline
    /// tree and in the Supervisor's error messages.
    public static final String STAGE_ELEMENT = "Shapeshifter AI";

    /// The version every document in the pack is at. A document in Stroom always has one — it is what a
    /// save checks before it writes — and the column it lands in does not take null, so a pack whose
    /// documents had none imported as an explorer entry with nothing behind it. Fixed rather than
    /// random, so that writing the pack twice writes the same bytes.
    private static final String VERSION = "5b3a2c74-1f3e-4a26-9c55-7bd9f0b6d0a1";

    /// The base name the entries of a feed's data zip share: Stroom pairs a `.meta` with its `.dat` by
    /// it, and one stream per zip needs only one pair.
    private static final String DATA_BASE = "001";

    /// The time every entry in the zip is stamped with; see [#writeZip].
    private static final long WRITTEN = 1_767_225_600_000L;

    private static final String CONTENT_ROOT = "stroomContent";
    private static final String NODE = ".node";
    private static final String META = ".meta";
    private static final String JSON = ".json";

    /// One file per feed, in the order of [#FEEDS]: the data design 02 §5 scenario 51 learns from. It is
    /// not content and the importer ignores it; it is there so that a demo has something to post.
    /// Written out one by one rather than from a `Map.of`, whose iteration order is different in every
    /// JVM: the pack is committed, and a file order that changed from one writing to the next would put
    /// a diff in front of a person who had changed nothing.
    private static final Map<String, Supplier<String>> SAMPLES = samples();

    /// Which sample each feed's data zip carries, named rather than taken by position, so that adding a
    /// feed to [#FEEDS] without a sample fails where it is written and not where it is posted.
    private static final Map<String, String> FEED_SAMPLES = feedSamples();

    private DemoContentPack() {
    }

    private static Map<String, String> feedSamples() {
        final Map<String, String> samples = new LinkedHashMap<>();
        samples.put("DOOR-ACCESS", "door-access.csv");
        samples.put("APP-EVENTS", "app-events.jsonl");
        samples.put("FIREWALL", "firewall.log");
        samples.put("MAINFRAME", "mainframe.log");
        return samples;
    }

    private static Map<String, Supplier<String>> samples() {
        final Map<String, Supplier<String>> samples = new LinkedHashMap<>();
        samples.put("door-access.csv", () -> Scenarios.corpus("001_csv_with_header").input());
        samples.put("app-events.jsonl", () -> Scenarios.resource("records.jsonl"));
        samples.put("firewall.log", () -> Scenarios.resource("syslog.log"));
        samples.put("mainframe.log", () -> Scenarios.resource("fixed-width.log"));
        return samples;
    }

    /// The Shapeshifter AI document the pack ships: scenario 51's settings, an identity, and the model.
    ///
    /// @return The document.
    public static ShapeshifterAiDoc document() {
        final ShapeshifterAiDoc configured = DemoDocument.configure(ShapeshifterAiDoc.builder())
                .uuid(DOCUMENT_UUID)
                .version(VERSION)
                .name(DOCUMENT_NAME)
                .description("""
                        One supervised stage's settings, used by a pipeline for each of four feeds of \
                        four unrelated formats and told nothing about any of them. Every parser it knows \
                        how to configure is allowed, so \
                        the first question it asks the model is which of them this feed needs.""")
                .model(modelRef())
                .build();
        // The store stamps a saved document with the built-in question text it was saved against, and a
        // document that arrived without the stamp would say its templates were of no known version until
        // someone saved it. The pack is written as a saved document, so it carries the stamp.
        return configured.copy()
                .flow(configured.getFlow().withBuiltInVersion(Templates.VERSION))
                .build();
    }

    /// @return A reference to the Shapeshifter AI document the pack ships.
    public static DocRef documentRef() {
        return new DocRef(ShapeshifterAiDoc.TYPE, DOCUMENT_UUID, DOCUMENT_NAME);
    }

    /// @return A reference to the model document the stage calls.
    public static DocRef modelRef() {
        return new DocRef(OpenAIModelDoc.TYPE, MODEL_UUID, MODEL_NAME);
    }

    /// A filter has no name of its own; Stroom's exporter gives it one made of the pipeline it runs and
    /// the head of its uuid, and this is that name, so the pack is written as an export of it would be.
    ///
    /// @param feed One of [#FEEDS].
    /// @return A reference to the feed's processor filter.
    public static DocRef filterRef(final String feed) {
        final String uuid = FILTER_UUIDS.get(index(feed));
        return new DocRef(ProcessorFilter.ENTITY_TYPE, uuid,
                pipelineName(feed) + " " + ProcessorFilter.ENTITY_TYPE + " " + uuid.substring(0, 7));
    }

    /// @param feed One of [#FEEDS].
    /// @return A reference to the pipeline the feed's stage sits in.
    public static DocRef pipelineRef(final String feed) {
        return new DocRef(PipelineDoc.TYPE, PIPELINE_UUIDS.get(index(feed)), pipelineName(feed));
    }

    /// @param feed A feed.
    /// @return The name of the pipeline that serves it.
    public static String pipelineName(final String feed) {
        return feed + " supervised";
    }

    /// @param feed One of [#FEEDS].
    /// @return The uuid of the processor the feed's filter runs under.
    public static String processorUuid(final String feed) {
        return PROCESSOR_UUIDS.get(index(feed));
    }

    private static int index(final String feed) {
        final int index = FEEDS.indexOf(feed);
        if (index < 0) {
            throw new IllegalArgumentException("The pack has no feed " + feed + "; it has " + FEEDS);
        }
        return index;
    }

    /// The model reference. The base URL and model id are a starting point to be edited for whatever
    /// OpenAI-compatible endpoint is to hand; the key is named rather than carried.
    ///
    /// @return The model document.
    public static OpenAIModelDoc model() {
        return OpenAIModelDoc.builder()
                .uuid(MODEL_UUID)
                .version(VERSION)
                .name(MODEL_NAME)
                .description("""
                        The model the Shapeshifter AI stage asks. Set the base URL and model id for the \
                        OpenAI-compatible endpoint you are using, and create a stored secret named \
                        '""" + API_KEY_NAME + "' holding its API key.")
                .baseUrl("https://api.openai.com/v1")
                .modelId("gpt-4o")
                .apiKey(API_KEY_NAME)
                .build();
    }

    /// @return The four feeds, in the order of [#FEEDS].
    public static List<FeedDoc> feeds() {
        return FEEDS.stream()
                .map(name -> FeedDoc.builder()
                        .uuid(FEED_UUIDS.get(FEEDS.indexOf(name)))
                        .version(VERSION)
                        .name(name)
                        .description("A demo feed. Post anything you like to it; the stage has not been "
                                     + "told what this format is.")
                        .streamType(StreamTypeNames.RAW_EVENTS)
                        .build())
                .toList();
    }

    /// `Source → ShapeshifterAi → SchemaFilter → RecordOutputFilter → RecordCountFilter → XMLWriter →
    /// StreamAppender`: the supervised stage standing where a parser and its translation would be, and
    /// the ordinary tail of an event pipeline after it.
    ///
    /// The appender names no feed, so a feed's events are written back to the feed they came from. Each
    /// feed has a pipeline of these, as the Pipeline Factory would make from a template.
    ///
    /// @param document The Shapeshifter AI document the stage is configured by.
    /// @return The pipeline's elements, links and properties.
    public static PipelineData pipelineData(final DocRef document) {
        return new PipelineDataBuilder()
                .addElement(new PipelineElement("Source", "Source"))
                .addElement(new PipelineElement(STAGE_ELEMENT, "ShapeshifterAi"))
                .addElement(new PipelineElement("Schema filter", "SchemaFilter"))
                .addElement(new PipelineElement("Record output filter", "RecordOutputFilter"))
                .addElement(new PipelineElement("Record count filter", "RecordCountFilter"))
                .addElement(new PipelineElement("XML writer", "XMLWriter"))
                .addElement(new PipelineElement("Stream appender", "StreamAppender"))
                .addLink("Source", STAGE_ELEMENT)
                .addLink(STAGE_ELEMENT, "Schema filter")
                .addLink("Schema filter", "Record output filter")
                .addLink("Record output filter", "Record count filter")
                .addLink("Record count filter", "XML writer")
                .addLink("XML writer", "Stream appender")
                .addProperty(PipelineDataUtil.createProperty(STAGE_ELEMENT, "shapeshifterAi", document))
                .addProperty(PipelineDataUtil.createProperty("Schema filter", "schemaGroup", "EVENTS"))
                .addProperty(PipelineDataUtil.createProperty("Record count filter", "countRead", false))
                .addProperty(PipelineDataUtil.createProperty("XML writer", "indentOutput", true))
                .addProperty(PipelineDataUtil.createProperty(
                        "Stream appender", "streamType", StreamTypeNames.EVENTS))
                .addProperty(PipelineDataUtil.createProperty("Stream appender", "segmentOutput", true))
                .build();
    }

    /// @param feed One of [#FEEDS].
    /// @return The feed's pipeline document, without its elements, which travel in their own asset.
    public static PipelineDoc pipeline(final String feed) {
        return PipelineDoc.builder()
                .uuid(PIPELINE_UUIDS.get(index(feed)))
                .version(VERSION)
                .name(pipelineName(feed))
                .description("The supervised stage where " + feed + "'s parser and translation would be.")
                .build();
    }

    /// A filter over one feed's raw events, so that posting data is the only thing left to do.
    ///
    /// It carries no id and no tracker: those belong to the installation that imports it, and a filter
    /// that carried them would be refused on import. It names its processor, which the importer makes.
    ///
    /// @param feed One of [#FEEDS].
    /// @return The feed's processor filter.
    public static ProcessorFilter filter(final String feed) {
        final ExpressionOperator expression = ExpressionOperator.builder()
                .addTextTerm(MetaFields.FEED, Condition.EQUALS, feed)
                .addTextTerm(MetaFields.TYPE, Condition.EQUALS, StreamTypeNames.RAW_EVENTS)
                .build();
        return ProcessorFilter.builder()
                .uuid(FILTER_UUIDS.get(index(feed)))
                .processorType(ProcessorType.PIPELINE)
                .processorUuid(processorUuid(feed))
                .pipelineUuid(PIPELINE_UUIDS.get(index(feed)))
                .pipelineName(pipelineName(feed))
                .queryData(QueryData.builder()
                        .dataSource(MetaFields.STREAM_STORE_DOC_REF)
                        .expression(expression)
                        .build())
                .priority(10)
                .maxProcessingTasks(0)
                .build();
    }

    /// Every file in the pack, keyed by its path inside the zip.
    ///
    /// @return The pack's files, in the order they are written.
    public static Map<String, byte[]> files() {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("README.md", utf8(readme()));
        SAMPLES.forEach((name, resource) -> files.put("data/" + name, utf8(resource.get())));
        add(files, documentRef(), Map.of(META, json(document())));
        add(files, modelRef(), Map.of(META, json(model())));
        FEEDS.forEach(feed -> {
            final Map<String, byte[]> pipelineAssets = new LinkedHashMap<>();
            pipelineAssets.put(META, json(pipeline(feed)));
            pipelineAssets.put(JSON, json(pipelineData(documentRef())));
            add(files, pipelineRef(feed), pipelineAssets);
        });
        feeds().forEach(feed -> add(files,
                new DocRef(FeedDoc.TYPE, feed.getUuid(), feed.getName()),
                Map.of(META, json(feed))));
        FEEDS.forEach(feed -> add(files, filterRef(feed), Map.of(META, json(filter(feed)))));
        return files;
    }

    /// One feed's sample as a zip Stroom will take: the `.meta` that names the feed and the type, and
    /// the data beside it under the same base name.
    ///
    /// The `.meta` is what lets the zip be posted to the data feed endpoint without anything else being
    /// said — the feed is in the file rather than in the request. Uploaded through **Data -> Upload**
    /// instead, the feed chosen in the dialog overrides it (`DataUploadTaskHandler` builds an attribute
    /// map that overrides embedded meta), so choose the feed this zip is named after.
    ///
    /// @param feed One of [#FEEDS].
    /// @return The zip's files, the `.meta` before the data as Stroom requires when a feed is named.
    public static Map<String, byte[]> dataFiles(final String feed) {
        final String sample = FEED_SAMPLES.get(feed);
        if (sample == null) {
            throw new IllegalArgumentException("The pack has no sample for feed " + feed + "; it has "
                                               + FEED_SAMPLES.keySet());
        }
        final Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(DATA_BASE + ".meta", utf8("Feed:" + feed + "\nType:" + StreamTypeNames.RAW_EVENTS + "\n"));
        files.put(DATA_BASE + ".dat", utf8(SAMPLES.get(sample).get()));
        return files;
    }

    /// @return The name of the zip a feed's data is written to.
    public static String dataZipName(final String feed) {
        return safe(feed) + "_data.zip";
    }

    /// Writes the pack as a zip, replacing whatever was there.
    ///
    /// @param zip Where to write it.
    public static void writeZip(final Path zip) {
        write(zip, files(), "the demo content pack");
    }

    /// Writes one zip per feed, each holding that feed's sample and a `.meta` naming the feed.
    ///
    /// One per feed rather than one for all four, because a zip carrying several feeds can only be
    /// *posted*: the upload dialog overrides what the entries say with the feed chosen in it.
    ///
    /// @param directory Where to write them.
    public static void writeDataZips(final Path directory) {
        FEEDS.forEach(feed -> write(directory.resolve(dataZipName(feed)), dataFiles(feed),
                "the demo data for " + feed));
    }

    private static void write(final Path zip, final Map<String, byte[]> files, final String what) {
        try {
            if (zip.getParent() != null) {
                Files.createDirectories(zip.getParent());
            }
            try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                for (final Map.Entry<String, byte[]> file : files.entrySet()) {
                    final ZipEntry entry = new ZipEntry(file.getKey());
                    // These are committed, so writing one again when nothing has changed should leave
                    // nothing to commit: a timestamp of the moment it was written would be a diff of
                    // every entry every time.
                    entry.setTime(WRITTEN);
                    out.putNextEntry(entry);
                    out.write(file.getValue());
                    out.closeEntry();
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to write " + what + " to " + zip, e);
        }
    }

    /// The `.node` file that tells the importer what a document is and where it goes, and the document's
    /// own assets beside it under the same prefix.
    private static void add(final Map<String, byte[]> files,
                            final DocRef docRef,
                            final Map<String, byte[]> assets) {
        final String prefix = CONTENT_ROOT + "/" + safe(FOLDER) + "/" + filePrefix(docRef);
        files.put(prefix + NODE, utf8(node(docRef)));
        assets.forEach((extension, bytes) -> files.put(prefix + extension, bytes));
    }

    /// The name Stroom's exporter would give this document's files: its name with anything that is not a
    /// letter or a digit replaced, then its type and uuid.
    private static String filePrefix(final DocRef docRef) {
        return (docRef.getName() != null
                ? safe(docRef.getName()) + "."
                : "") + docRef.getType() + "." + docRef.getUuid();
    }

    private static String safe(final String name) {
        return name.replaceAll("[^A-Za-z0-9]", "_");
    }

    /// Java properties, in the order the exporter writes them. A document with no name — a processor
    /// filter — has no `name` line, which the importer allows for exactly that case.
    private static String node(final DocRef docRef) {
        final StringBuilder sb = new StringBuilder();
        if (docRef.getName() != null) {
            sb.append("name=").append(docRef.getName()).append('\n');
        }
        return sb.append("path=").append(FOLDER).append('\n')
                .append("type=").append(docRef.getType()).append('\n')
                .append("uuid=").append(docRef.getUuid()).append('\n')
                .toString();
    }

    private static byte[] json(final Object object) {
        return utf8(JsonUtil.writeValueAsString(object, true));
    }

    private static byte[] utf8(final String string) {
        return string.getBytes(StandardCharsets.UTF_8);
    }

    private static String readme() {
        return """
                # Shapeshifter AI demo

                One Shapeshifter AI document, four feeds of four unrelated formats, a pipeline for each,
                and nothing telling any of them what its format is. Import this pack, give it a model and
                post some data.

                Import it from **Tools -> Import**, or drop the zip into the `content_pack_import`
                directory of an instance with `stroom.contentPackImport.enabled` set.

                ## What is in it

                - **`%s`** — the supervised stage's configuration: what it may learn with, how
                  candidates are scored, and when one is promoted.
                - **`%s`** — the model the stage asks. Edit it before running anything.
                - **`<feed> supervised`** — a pipeline for each feed, `Source -> Shapeshifter AI -> Schema
                  filter -> ... -> Stream appender`, each learning its own feed (one pipeline per feed, as
                  the Pipeline Factory makes them).
                - **`%s`** — four feeds. The pack tells the stages nothing about their formats.

                A processor filter over each feed comes with its pipeline. Importing from
                **Tools -> Import** leaves it switched off unless you tick **Enable Processor Filters**
                on the confirmation screen; a pack dropped in `content_pack_import` arrives with it on.
                Set the model up before posting anything, or the first stream will fail for want of an
                API key.

                ## Before you run it

                1. Open `%s` and set the base URL and model id for an OpenAI-compatible endpoint.
                2. Create a stored secret named `%s` holding that endpoint's API key. The pack carries
                   the name of the secret and never the key itself.
                3. Make sure the event-logging XML schemas are installed, since the stage gates every
                   candidate on validating against the `EVENTS` schema group.

                ## Running it

                Post a file to any of the four feeds as `Raw Events`. There is one under `data/` for
                each of them, and nothing stops you posting your own:

                | Feed | Sample | What it is |
                |---|---|---|
                | `DOOR-ACCESS` | `data/door-access.csv` | Delimited text with a header row. |
                | `APP-EVENTS` | `data/app-events.jsonl` | JSON, one object per line. |
                | `FIREWALL` | `data/firewall.log` | Syslog, in two forms from two senders. |
                | `MAINFRAME` | `data/mainframe.log` | Fixed-width columns with nothing to split on. |

                The feed's pipeline will find nothing learned yet, ask the model which parser the format
                needs, learn a configuration for it, score what comes back and — if it clears the floor —
                bind it and translate the stream with what it just learned. The feed's next stream is
                served by that binding without the model being asked again.

                Watch it happen in the **Supervisor**: the attempts, what each question was and what came
                back, the scores each candidate got and why a candidate was refused, and what is serving
                each pipeline.

                ## What the settings mean

                The interesting ones are on the document's own tabs, and all of them can be changed:

                - **Allowed elements** is every parser the stage knows how to configure. Narrowing it is
                  how you would tell the stage what a feed is; the demo deliberately does not.
                - **The flow** is the escalating one: ask directly first, and only propose a target event
                  when the transform falls short. It is a graph of steps and you can edit it.
                - **The scorers** gate on compiling, on the output validating against the schema, and on
                  the extraction being worth having — the last of these is what refuses a transform that
                  validates while saying nothing.
                - **Promotion floor** is the score a candidate must reach before its binding goes live.

                ## The formats it was proved on

                Design 02 §5 scenario 51 runs exactly these settings over delimited text with a header,
                JSON lines, syslog in two forms, and fixed-width columns with nothing to split on. The
                sample data under `data/` in this pack is that scenario's, one file per feed.
                """.formatted(DOCUMENT_NAME, MODEL_NAME, String.join("`, `", FEEDS), MODEL_NAME,
                API_KEY_NAME);
    }
}
