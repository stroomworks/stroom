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

package stroom.shapeshifter.ai.element;

import stroom.ai.api.AiService;
import stroom.docstore.shared.DocRefUtil;
import stroom.event.logging.api.DocumentEventLog;
import stroom.openai.shared.OpenAIModelDoc;
import stroom.shapeshifter.ai.learning.Advisor;
import stroom.shapeshifter.ai.learning.Advisors;
import stroom.shapeshifter.ai.learning.QuestionText;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import jakarta.inject.Inject;

/**
 * A node's advisors: the model the document names, through {@code stroom-ai} — which resolves the key
 * through the credentials service, guards the base URL against SSRF and applies the HTTP client
 * configuration — or, for a document that names none, an advisor that fails the stream loudly.
 */
public class ModelAdvisors implements Advisors {

    private final AiService aiService;
    private final DocumentEventLog eventLog;

    @Inject
    public ModelAdvisors(final AiService aiService, final DocumentEventLog eventLog) {
        this.aiService = aiService;
        this.eventLog = eventLog;
    }

    @Override
    public Advisor of(final ShapeshifterAiDoc doc) {
        if (doc.getModel() == null) {
            return new NoModelAdvisor();
        }
        final OpenAIModelDoc modelDoc = aiService.getOpenAIModelDoc(doc.getModel());
        if (modelDoc == null) {
            throw new IllegalStateException("Shapeshifter AI document " + doc.getName() + " names model "
                                            + doc.getModel() + ", which cannot be read");
        }
        return new ModelAdvisor(aiService.getChatModel(modelDoc), modelDoc.getName(), DocRefUtil.create(doc),
                QuestionText.of(doc), eventLog);
    }
}
