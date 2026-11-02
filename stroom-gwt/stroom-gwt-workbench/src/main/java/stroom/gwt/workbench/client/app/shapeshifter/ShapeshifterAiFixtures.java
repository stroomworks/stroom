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

package stroom.gwt.workbench.client.app.shapeshifter;

import stroom.docref.DocRef;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

/// The replies the Shapeshifter AI stories share: a document, the built-in texts its node serves,
/// and what the Supervisor says about it.
final class ShapeshifterAiFixtures {

    static final DocRef DOC_REF = new DocRef(ShapeshifterAiDoc.TYPE, "ss-1", "Firewall logs");

    /// `GET /shapeshifterAi/v1/{uuid}`: two scorers (a compile gate, and business rules with one
    /// assertion), and a flow of two steps, the second going back to the first when its coverage
    /// falls short.
    static final String DOC = """
            {"type": "ShapeshifterAi", "uuid": "ss-1", "name": "Firewall logs",
              "description": "# Firewall logs",
              "promotionMode": "AUTOMATIC", "promotionFloor": 0.8, "heldOutFraction": 0.2,
              "minRecordsToLearn": 20, "regressionCap": 50, "regressionRetentionDays": 30,
              "relearnThreshold": 0.6,
              "scorers": [
                {"type": "COMPILE", "weight": 1.0, "threshold": 1.0, "gate": true},
                {"type": "BUSINESS_RULES", "weight": 2.0, "threshold": 0.9, "gate": false,
                  "parameters": {"type": "businessRules", "includeTransformMessages": false,
                    "assertions": [{"name": "users are named",
                      "xpath": "EventDetail/Authenticate/User/Id != ''"}]}}],
              "learningMode": "AUTOMATIC",
              "model": {"type": "OpenAIModel", "uuid": "model-1", "name": "GPT-4o"},
              "instructions": "Prefer the standard schema.", "maxAttempts": 3,
              "attemptBudgetMs": 300000, "tokenBudget": 0, "sampleSizeLimit": 65536,
              "maxDeferralAgeMs": 86400000,
              "flow": {"builtInVersion": 1, "templates": {},
                "steps": [
                  {"kind": "CHAIN", "id": "chain"},
                  {"kind": "CONFIGURE", "role": "PARSER", "id": "parse",
                    "transitions": [{"on": "COVERAGE_SHORT", "goTo": "chain"}]}]}}""";

    /// `GET /shapeshifterAi/v1/templates`: the built-in texts.
    static final String TEMPLATES = """
            {"version": 1, "templates": {
              "SYSTEM": "You write Stroom pipelines.",
              "CHAIN": "Choose the elements of the chain.",
              "CONFIGURATION": "Configure the parser."}}""";

    /// The Supervisor's figures for the document's process header: three streams waiting, and one
    /// session parked.
    private static final String WAITING_PAGE = """
            {"values": [], "pageResponse": {"offset": 0, "length": 0, "total": 3, "exact": true}}""";
    private static final String PARKED_PAGE = """
            {"values": [], "pageResponse": {"offset": 0, "length": 0, "total": 1, "exact": true}}""";

    private ShapeshifterAiFixtures() {
        // Static utility
    }

    /// Adds the routes a Shapeshifter AI document's editor asks for, beyond its Permissions tab and
    /// document pickers: the document (fetched, and echoed when saved), the built-in texts and the
    /// Supervisor's figures.
    ///
    /// @param builder The story's fixtures.
    /// @return The builder.
    static RestFixtures.Builder editorRoutes(final RestFixtures.Builder builder) {
        return builder
                .get("/shapeshifterAi/v1/templates", RestReply.json(TEMPLATES))
                .get("/shapeshifterAi/v1/ss-1", RestReply.json(DOC))
                .put("/shapeshifterAi/v1/ss-1", request -> RestReply.json(request.getBody()))
                .post("/shapeshifterAiSupervisor/v1/backlog", RestReply.json(WAITING_PAGE))
                .post("/shapeshifterAiSupervisor/v1/find", RestReply.json(PARKED_PAGE));
    }
}
