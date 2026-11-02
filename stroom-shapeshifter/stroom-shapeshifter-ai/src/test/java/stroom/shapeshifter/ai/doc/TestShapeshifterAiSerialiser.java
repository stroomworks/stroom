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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.shared.DocDataType;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportDocument;
import stroom.pipeline.shared.PipelineDoc;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.Template;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestShapeshifterAiSerialiser {

    private final ShapeshifterAiSerialiser serialiser = new ShapeshifterAiSerialiser(new Serialiser2FactoryImpl());

    @Test
    void aDocumentSavedWhenTheFlowWasTheConversationReadsAsItWasWritten() throws IOException {
        final LearningFlow flow = LearningFlow.of(FlowExample.TARGET_FIRST)
                .withTemplates(Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}"));
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").name("door").flow(flow).build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"flow\"");

        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replace("\"flow\"", "\"dialogue\"").getBytes(StandardCharsets.UTF_8)));

        final ShapeshifterAiDoc read = serialiser.read(written);
        assertThat(read.getFlow()).isEqualTo(flow);
        assertThat(serialiser.read(serialiser.write(doc)).getFlow()).isEqualTo(flow);
    }

    @Test
    void aDocumentSavedWhenTheFlowWasThePlanReadsAsItWasWritten() throws IOException {
        // A81: "plan" is a name in stored data, read as the flow and never written again.
        final LearningFlow flow = LearningFlow.of(FlowExample.ESCALATING);
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").name("door").flow(flow).build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replace("\"flow\"", "\"plan\"").getBytes(StandardCharsets.UTF_8)));

        final ShapeshifterAiDoc read = serialiser.read(written);
        assertThat(read.getFlow()).isEqualTo(flow);
        assertThat(new String(serialiser.write(read).getExtAssetData("meta"), StandardCharsets.UTF_8))
                .contains("\"flow\"").doesNotContain("\"plan\"");
    }

    @Test
    void aDocumentSavedWithItsOwnAllowedElementsPutsThemOnItsChainStep() throws IOException {
        // A95: the field is read onto the Chain step, and the written document carries it there alone.
        final ShapeshifterAiDoc doc = ShapeshifterAiDoc.builder().uuid("d").name("door").build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("\"allowedElements\"");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replaceFirst("\\{", "{\"allowedElements\":[\"XMLParser\",\"XSLTFilter\"],")
                        .getBytes(StandardCharsets.UTF_8)));

        final ShapeshifterAiDoc read = serialiser.read(written);
        assertThat(read.getAllowedElements()).containsExactly("XMLParser", "XSLTFilter");
        assertThat(read.getFlow().getSteps().get(0).format()).isEqualTo("CHAIN elements XMLParser,XSLTFilter");
    }

    @Test
    void aDocumentWithoutItsMetaReadsAsTheDelegateReadsIt() throws IOException {
        final ImportExportDocument written = serialiser.write(
                ShapeshifterAiDoc.builder().uuid("d").name("door").build());
        written.removeExtAsset("meta");
        assertThat(serialiser.read(written)).isNull();
    }

    @Test
    void aDocumentSavedWithAPresetReadsAsThatExamplesSteps() throws IOException {
        // Before A34 the section carried a preset in place of steps.
        final ImportExportDocument written = serialiser.write(
                ShapeshifterAiDoc.builder().uuid("d").name("door").build());
        final ObjectNode json = (ObjectNode) JsonUtil.getMapper().readTree(written.getExtAssetData("meta"));
        json.remove("flow");
        final ObjectNode legacy = json.putObject("dialogue");
        legacy.put("preset", "TARGET_FIRST");
        legacy.putObject("templates").put("CHAIN", "Pick");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                JsonUtil.getMapper().writeValueAsBytes(json)));

        final LearningFlow read = serialiser.read(written).getFlow();
        assertThat(read.getSteps()).isEqualTo(FlowExample.TARGET_FIRST.steps());
        assertThat(read.getTemplates()).containsEntry(Template.CHAIN, "Pick");
    }

    /// A document that learned before its bindings were rows (A41) carried them in its JSON. They chose
    /// their streams in a way that no longer means anything and named no learner, so they have nowhere to go
    /// (design 06 phase J slice 2b): the document reads without them, rather than failing to open.
    @Test
    void aDocumentThatCarriedItsBindingsReadsWithoutThem() throws IOException {
        final ImportExportDocument written = serialiser.write(
                ShapeshifterAiDoc.builder().uuid("d").name("door").build());
        final ObjectNode json = (ObjectNode) JsonUtil.getMapper().readTree(written.getExtAssetData("meta"));
        final ArrayNode table = json.putArray("routingTable");
        table.add(JsonUtil.getMapper().valueToTree(Binding.builder()
                .uuid("rule-1")
                .pipeline(new DocRef(PipelineDoc.TYPE, "fragment-1", "door-v1"))
                .score(0.97)
                .build()));
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                JsonUtil.getMapper().writeValueAsBytes(json)));

        final ShapeshifterAiDoc read = serialiser.read(written);

        assertThat(read.getUuid()).isEqualTo("d");
        assertThat(read.getName()).isEqualTo("door");
    }

    @Test
    void aPresetThisBuildDoesNotKnowLeavesTheDocumentOpenable() throws IOException {
        // A renamed example or a hand-edited document must not make a document unopenable, and so
        // unrepairable; its steps are the default flow's until it is saved again.
        final ImportExportDocument written = serialiser.write(
                ShapeshifterAiDoc.builder().uuid("d").name("door").build());
        final ObjectNode json = (ObjectNode) JsonUtil.getMapper().readTree(written.getExtAssetData("meta"));
        json.remove("flow");
        json.putObject("dialogue").put("preset", "SOME_EXAMPLE_THAT_WENT_AWAY");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                JsonUtil.getMapper().writeValueAsBytes(json)));

        final ShapeshifterAiDoc read = serialiser.read(written);

        assertThat(read.getFlow().getSteps()).isEqualTo(FlowExample.DIRECT.steps());
    }
}
