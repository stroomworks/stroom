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

package stroom.learningflow.doc;

import stroom.docstore.api.DocumentSerialiser2;
import stroom.docstore.api.Serialiser2;
import stroom.docstore.api.Serialiser2Factory;
import stroom.importexport.api.ImportExportDocument;
import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QuestionKind;
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

/// Serialises Learning Flow documents, and upgrades the JSON of documents saved by earlier versions of the
/// feature as they are read.
public class LearningFlowSerialiser implements DocumentSerialiser2<LearningFlowDoc> {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(LearningFlowSerialiser.class);

    /// The name of the asset the delegate keeps the document's JSON in.
    private static final String META = "meta";
    private static final String FLOW = "flow";
    /// Earlier names of the `flow` property, newest first: documents saved by earlier versions have the flow
    /// under `plan` or, earlier still, `dialogue`. These are names in **stored data**, so they must keep
    /// being recognised even though the property has been renamed. The shared document class cannot carry
    /// them, because its JSON codec is also generated for the client, so they are handled here, on read
    /// only.
    private static final List<String> LEGACY_FLOW = List.of("plan", "dialogue");
    /// The document-level property that held the chain question's elements in documents saved before the
    /// elements were moved onto the Chain step.
    private static final String LEGACY_ELEMENTS = "allowedElements";
    private static final String LEGACY_PRESET = "preset";

    private final Serialiser2<LearningFlowDoc> delegate;

    @Inject
    LearningFlowSerialiser(final Serialiser2Factory serialiser2Factory) {
        this.delegate = serialiser2Factory.createSerialiser(LearningFlowDoc.class);
    }

    /// Reads a document, upgrading any properties saved under earlier names. Nothing is written back.
    ///
    /// Documents saved by early versions of the feature may hold bindings. Those are ignored: they named no
    /// learner and selected their streams by a mechanism that no longer exists, so there is nowhere to put
    /// them.
    @Override
    public LearningFlowDoc read(final ImportExportDocument importExportDocument) throws IOException {
        final LearningFlowDoc read = delegate.read(importExportDocument);
        final LearningFlowDoc document = legacyElements(read, importExportDocument);
        // A document that saved its flow under an earlier property name has the flow read from that
        // property. The JSON is only parsed again if an old name occurs in it at all.
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
        // Early documents named a preset flow instead of holding steps; such a document gets the preset's steps.
        final JsonNode preset = legacy.get(LEGACY_PRESET);
        if (preset != null && !preset.isNull() && (legacy.get("steps") == null || legacy.get("steps").isNull())) {
            // A preset name this build does not recognise (a renamed example, or a hand-edited document) gets
            // the default steps, rather than making the document impossible to open and so to repair.
            flow = flow.withSteps(FlowExamples.steps(example(preset.asText())));
        }
        return document.copy().flow(flow).build();
    }

    /// Documents saved by an earlier version held the chain question's elements in a document-level
    /// property. Those elements are moved onto the Chain step, unless a Chain step already names elements.
    private static LearningFlowDoc legacyElements(final LearningFlowDoc document,
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
    public ImportExportDocument write(final LearningFlowDoc document) throws IOException {
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
