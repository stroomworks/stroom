/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.docstore.api.DocumentSerialiser2;
import stroom.docstore.api.Serialiser2;
import stroom.docstore.api.Serialiser2Factory;
import stroom.importexport.api.ImportExportDocument;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.json.JsonUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ShapeshifterAiSerialiser implements DocumentSerialiser2<ShapeshifterAiDoc> {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShapeshifterAiSerialiser.class);

    /// The asset the delegate keeps the document's JSON in.
    private static final String META = "meta";
    private static final String FLOW = "flow";
    /// The names the flow was saved under before it was named so, newest first: `plan` until A81, and
    /// `dialogue` before A37. These are names in **stored data** and so can never be renamed: a document
    /// written then has them in its JSON whatever this feature decides to call the thing today. The
    /// shared class cannot carry them, since its JSON codec is generated for the client too, so they
    /// are honoured here, on the way in only.
    private static final List<String> LEGACY_FLOW = List.of("plan", "dialogue");
    /// Where the chain question's elements were kept before A95 put them on the Chain step.
    private static final String LEGACY_ELEMENTS = "allowedElements";
    private static final String LEGACY_PRESET = "preset";

    private final Serialiser2<ShapeshifterAiDoc> delegate;

    @Inject
    ShapeshifterAiSerialiser(final Serialiser2Factory serialiser2Factory) {
        this.delegate = serialiser2Factory.createSerialiser(ShapeshifterAiDoc.class);
    }

    /// A read is a read: nothing is written here. A document's bindings from before A41 are not carried over
    /// (design 06 phase J slice 2b): they named no learner, and chose their streams in a way that no
    /// longer means anything, so they have nowhere to go; the document reads without them.
    @Override
    public ShapeshifterAiDoc read(final ImportExportDocument importExportDocument) throws IOException {
        final ShapeshifterAiDoc read = delegate.read(importExportDocument);
        final ShapeshifterAiDoc document = legacyElements(read, importExportDocument);
        // A document saved when the flow was the plan (before A81) or the conversation (before A37) reads
        // as it was written, and only where an old name occurs at all.
        final byte[] meta = importExportDocument.getExtAssetData(META);
        if (document == null || meta == null) {
            return document;
        }
        final String text = new String(meta, StandardCharsets.UTF_8);
        final String name = LEGACY_FLOW.stream()
                .filter(legacyName -> text.contains('"' + legacyName + '"'))
                .findFirst()
                .orElse(null);
        if (name == null) {
            return document;
        }
        final JsonNode json = JsonUtil.getMapper().readTree(meta);
        final JsonNode legacy = json.get(name);
        if (legacy == null || legacy.isNull() || json.get(FLOW) != null) {
            return document;
        }
        LearningFlow flow = JsonUtil.getMapper().treeToValue(legacy, LearningFlow.class);
        // Before A34 the section carried a preset in place of steps; a document that chose one gets its steps.
        final JsonNode preset = legacy.get(LEGACY_PRESET);
        if (preset != null && !preset.isNull() && (legacy.get("steps") == null || legacy.get("steps").isNull())) {
            // A preset this build does not know — a renamed example, a hand-edited document — leaves the
            // steps as the default rather than making the document unopenable, and so unrepairable.
            flow = flow.withSteps(example(preset.asText()).steps());
        }
        return document.copy().flow(flow).build();
    }

    /// A document saved before A95 carried the chain question's elements as its own field: they go on its
    /// Chain step, where the step names none of its own.
    private static ShapeshifterAiDoc legacyElements(final ShapeshifterAiDoc document,
                                                    final ImportExportDocument importExportDocument)
            throws IOException {
        final byte[] meta = importExportDocument.getExtAssetData(META);
        if (document == null || meta == null
            || !new String(meta, StandardCharsets.UTF_8).contains('"' + LEGACY_ELEMENTS + '"')) {
            return document;
        }
        final JsonNode elements = JsonUtil.getMapper().readTree(meta).get(LEGACY_ELEMENTS);
        if (elements == null || !elements.isArray() || elements.isEmpty()) {
            return document;
        }
        for (final FlowStep step : document.getFlow().getSteps()) {
            if (step.getKind() == QuestionKind.CHAIN && !step.getElements().isEmpty()) {
                return document;
            }
        }
        final List<String> named = new ArrayList<>();
        elements.forEach(element -> named.add(element.asText()));
        return document.copy().allowedElements(named).build();
    }

    @Override
    public ImportExportDocument write(final ShapeshifterAiDoc document) throws IOException {
        return delegate.write(document);
    }

    private static FlowExample example(final String name) {
        for (final FlowExample example : FlowExample.values()) {
            if (example.name().equalsIgnoreCase(name)) {
                return example;
            }
        }
        LOGGER.warn(() -> LogUtil.message("Flow example '{}' is not one this build knows: {}. The document's "
                                          + "steps are the default flow's until it is saved again.",
                name, Arrays.toString(FlowExample.values())));
        return FlowExample.DIRECT;
    }
}
