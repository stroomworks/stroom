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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.impl.Serialiser2FactoryImpl;
import stroom.docstore.shared.DocDataType;
import stroom.importexport.api.ByteArrayImportExportAsset;
import stroom.importexport.api.ImportExportDocument;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.Template;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.json.JsonUtil;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestLearningFlowSerialiser {

    private final LearningFlowSerialiser serialiser = new LearningFlowSerialiser(new Serialiser2FactoryImpl());

    @Test
    void aDocumentSavedWhenTheFlowWasTheConversationReadsAsItWasWritten() throws IOException {
        final LearningFlow flow = FlowExamples.flow(FlowExample.TARGET_FIRST)
                .withTemplates(Map.of(Template.CHAIN, "Pick: ${elements}\n${sample}"));
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").name("door").flow(flow).build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        assertThat(json).contains("\"flow\"");

        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replace("\"flow\"", "\"dialogue\"").getBytes(StandardCharsets.UTF_8)));

        final LearningFlowDoc read = serialiser.read(written);
        assertThat(read.getFlow()).isEqualTo(flow);
        assertThat(serialiser.read(serialiser.write(doc)).getFlow()).isEqualTo(flow);
    }

    @Test
    void aDocumentSavedWhenTheFlowWasThePlanReadsAsItWasWritten() throws IOException {
        // "plan" is an earlier name for the flow, still found in stored documents. It is read as the flow
        // and never written again.
        final LearningFlow flow = FlowExamples.flow(FlowExample.ESCALATING);
        final LearningFlowDoc doc = LearningFlowDoc.builder().uuid("d").name("door").flow(flow).build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replace("\"flow\"", "\"plan\"").getBytes(StandardCharsets.UTF_8)));

        final LearningFlowDoc read = serialiser.read(written);
        assertThat(read.getFlow()).isEqualTo(flow);
        assertThat(new String(serialiser.write(read).getExtAssetData("meta"), StandardCharsets.UTF_8))
                .contains("\"flow\"").doesNotContain("\"plan\"");
    }

    @Test
    void aDocumentSavedWithItsOwnAllowedElementsPutsThemOnItsChainStep() throws IOException {
        // Documents saved by an earlier version held the allowed elements as a document field. That field is
        // read onto the Chain step, and the document is written with the elements on the step only. The
        // document is given a flow because a document built here has no steps, and the elements need a Chain
        // step to go onto.
        final LearningFlowDoc doc = LearningFlowDoc.builder()
                .uuid("d")
                .name("door")
                .flow(FlowExamples.flow(FlowExample.DIRECT))
                .build();
        final ImportExportDocument written = serialiser.write(doc);
        final String json = new String(written.getExtAssetData("meta"), StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("\"allowedElements\"");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                json.replaceFirst("\\{", "{\"allowedElements\":[\"XMLParser\",\"XSLTFilter\"],")
                        .getBytes(StandardCharsets.UTF_8)));

        final LearningFlowDoc read = serialiser.read(written);
        assertThat(read.getAllowedElements()).containsExactly("XMLParser", "XSLTFilter");
        assertThat(read.getFlow().getSteps().get(0).getKind()).isEqualTo(QuestionKind.CHAIN);
        assertThat(read.getFlow().getSteps().get(0).getElements()).containsExactly("XMLParser", "XSLTFilter");
    }

    @Test
    void aDocumentWithoutItsMetaReadsAsTheDelegateReadsIt() throws IOException {
        final ImportExportDocument written = serialiser.write(
                LearningFlowDoc.builder().uuid("d").name("door").build());
        written.removeExtAsset("meta");
        assertThat(serialiser.read(written)).isNull();
    }

    @Test
    void aDocumentSavedWithAPresetReadsAsThatExamplesSteps() throws IOException {
        // Documents saved by an earlier version named a preset example flow in place of holding steps.
        final ImportExportDocument written = serialiser.write(
                LearningFlowDoc.builder().uuid("d").name("door").build());
        final ObjectNode json = (ObjectNode) JsonUtil.getMapper().readTree(written.getExtAssetData("meta"));
        json.remove("flow");
        final ObjectNode legacy = json.putObject("dialogue");
        legacy.put("preset", "TARGET_FIRST");
        legacy.putObject("templates").put("CHAIN", "Pick");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                JsonUtil.getMapper().writeValueAsBytes(json)));

        final LearningFlow read = serialiser.read(written).getFlow();
        assertThat(read.getSteps()).isEqualTo(FlowExamples.steps(FlowExample.TARGET_FIRST));
        assertThat(read.getTemplates()).containsEntry(Template.CHAIN, "Pick");
    }

    /// Documents saved by an earlier version carried their bindings in the document JSON, as a routing table.
    /// Those bindings chose streams by routing rules that no longer exist and named no learner, so they cannot
    /// be migrated. The document is read without them rather than failing to open.
    @Test
    void aDocumentThatCarriedItsBindingsReadsWithoutThem() throws IOException {
        final ImportExportDocument written = serialiser.write(
                LearningFlowDoc.builder().uuid("d").name("door").build());
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

        final LearningFlowDoc read = serialiser.read(written);

        assertThat(read.getUuid()).isEqualTo("d");
        assertThat(read.getName()).isEqualTo("door");
    }

    @Test
    void aPresetThisBuildDoesNotKnowLeavesTheDocumentOpenable() throws IOException {
        // A renamed example or a hand-edited document must not make a document impossible to open, and so
        // impossible to repair. It is given the default (Direct) example's steps instead.
        final ImportExportDocument written = serialiser.write(
                LearningFlowDoc.builder().uuid("d").name("door").build());
        final ObjectNode json = (ObjectNode) JsonUtil.getMapper().readTree(written.getExtAssetData("meta"));
        json.remove("flow");
        json.putObject("dialogue").put("preset", "SOME_EXAMPLE_THAT_WENT_AWAY");
        written.removeExtAsset("meta");
        written.addExtAsset(new ByteArrayImportExportAsset("meta", DocDataType.JSON,
                JsonUtil.getMapper().writeValueAsBytes(json)));

        final LearningFlowDoc read = serialiser.read(written);

        assertThat(read.getFlow().getSteps()).isEqualTo(FlowExamples.steps(FlowExample.DIRECT));
    }
}
