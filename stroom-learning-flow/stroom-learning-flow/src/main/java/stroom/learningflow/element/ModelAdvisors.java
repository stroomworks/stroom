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

package stroom.learningflow.element;

import stroom.ai.api.AiService;
import stroom.docstore.shared.DocRefUtil;
import stroom.event.logging.api.DocumentEventLog;
import stroom.learningflow.learning.Advisor;
import stroom.learningflow.learning.Advisors;
import stroom.learningflow.learning.AskedWith;
import stroom.learningflow.learning.QuestionText;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.openai.shared.OpenAIModelDoc;

import jakarta.inject.Inject;

/// The default [Advisors] binding on a node. It creates a [ModelAdvisor] for the model a Learning Flow
/// document names, through `stroom-ai`, which resolves the API key through the credentials service,
/// guards the base URL against SSRF and applies the HTTP client configuration. For a document that names
/// no model, it returns a [NoModelAdvisor], which fails the attempt with a clear reason.
public class ModelAdvisors implements Advisors {

    private final AiService aiService;
    private final DocumentEventLog eventLog;
    private final TargetSchemas targetSchemas;

    @Inject
    public ModelAdvisors(final AiService aiService,
                         final DocumentEventLog eventLog,
                         final TargetSchemas targetSchemas) {
        this.aiService = aiService;
        this.eventLog = eventLog;
        this.targetSchemas = targetSchemas;
    }

    @Override
    public Advisor of(final LearningFlowDoc doc) {
        return of(doc, AskedWith.UNKNOWN);
    }

    @Override
    public Advisor of(final LearningFlowDoc doc, final AskedWith askedWith) {
        if (doc.getModel() == null) {
            return new NoModelAdvisor();
        }
        final OpenAIModelDoc modelDoc = aiService.getOpenAIModelDoc(doc.getModel());
        if (modelDoc == null) {
            throw new IllegalStateException("Learning Flow " + doc.getName() + " names model "
                                            + doc.getModel() + ", which cannot be read");
        }
        return new ModelAdvisor(aiService.getChatModel(modelDoc), modelDoc.getName(), DocRefUtil.create(doc),
                // The questions name the schema this document's translations must conform to.
                QuestionText.of(doc, askedWith, targetSchemas.of(doc).orElse(null)), eventLog);
    }
}
