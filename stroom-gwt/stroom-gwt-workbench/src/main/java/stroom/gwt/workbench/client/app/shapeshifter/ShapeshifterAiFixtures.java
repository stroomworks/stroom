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
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

/// The replies the Shapeshifter AI stories share: a document, the built-in texts its node serves, and what
/// the Supervisor says about its feeds.
final class ShapeshifterAiFixtures {

    static final DocRef DOC_REF = new DocRef(ShapeshifterAiDoc.TYPE, "ss-1", "Firewall logs");

    /// `GET /shapeshifterAi/v1/{uuid}`: learning on, a model, two checks (a compile gate, and business
    /// rules with one assertion), and a flow of two steps, the second going back to the first when it
    /// doesn't read all the input.
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

    /// A document as it is made: learning off, no model, no checks, the direct flow. It won't learn
    /// anything, and its Pipelines tab says so.
    static final String NEW_DOC = """
            {"type": "ShapeshifterAi", "uuid": "ss-1", "name": "Firewall logs",
              "promotionMode": "AUTOMATIC", "promotionFloor": 0.9, "heldOutFraction": 0.2,
              "minRecordsToLearn": 10, "regressionCap": 100, "relearnThreshold": 0.8,
              "scorers": [], "learningMode": "DISABLED", "maxAttempts": 5,
              "attemptBudgetMs": 60000, "sampleSizeLimit": 8192, "maxDeferralAgeMs": 86400000,
              "flow": {"steps": [
                {"kind": "CHAIN"},
                {"kind": "CONFIGURE", "role": "PARSER"},
                {"kind": "CONFIGURE", "role": "TRANSFORM", "transitions": [{"goTo": "admit"}]},
                {"kind": "ADMIT"}]}}""";

    /// [#DOC] with its first step naming the element types to choose from itself, one of which, XMLParser,
    /// the AI can't learn.
    static final String DOC_UNLEARNABLE = DOC.replace("{\"kind\": \"CHAIN\", \"id\": \"chain\"}",
            "{\"kind\": \"CHAIN\", \"id\": \"chain\", \"elements\": [\"XMLParser\", \"XSLTFilter\"]}");

    /// `GET /shapeshifterAi/v1/templates`: the built-in texts.
    static final String TEMPLATES = """
            {"version": 1, "templates": {
              "SYSTEM": "You write Stroom pipelines.",
              "CHAIN": "Choose the elements of the chain.",
              "CONFIGURATION": "Configure the parser."}}""";

    private static final String DOC_JSON = "{\"type\": \"ShapeshifterAi\", \"uuid\": \"ss-1\", "
                                           + "\"name\": \"Firewall logs\"}";

    /// The firewall's learner: its pipeline waiting for a person, with two streams waiting. A learner's state
    /// and a run's status are sent as their display values.
    static final String FIREWALL_LEARNER = """
            {"learner": {"pipelineUuid": "p-1", "elementId": "shapeshifterAi"}, "doc": DOC,
              "pipeline": {"type": "Pipeline", "uuid": "p-1", "name": "Firewall pipeline"},
              "state": "Waiting for a person", "waiting": 2, "records": 0, "guidance": 1, "raised": false,
              "belowThreshold": false, "provisional": false, "pipelineDeleted": false,
              "lastSessionMs": 1761800600000, "oldestWaitMs": 1761700000000}""".replace("DOC", DOC_JSON);

    /// The firewall's learner translating with what it learned: its pipeline's learned translation, as the
    /// Learning Status tab links to it.
    static final String FIREWALL_TRANSLATING = FIREWALL_LEARNER.replace("\"state\": \"Waiting for a person\",",
            "\"state\": \"Serving\", \"bindingUuid\": \"b-2\", \"fragment\": {\"type\": \"Pipeline\", "
            + "\"uuid\": \"f-2\", \"name\": \"Firewall pipeline learned\"}, \"promotedScore\": 0.97,");

    /// The proxy's learner: its pipeline translating.
    static final String PROXY_LEARNER = """
            {"learner": {"pipelineUuid": "p-3", "elementId": "shapeshifterAi"}, "doc": DOC,
              "pipeline": {"type": "Pipeline", "uuid": "p-3", "name": "Proxy pipeline"},
              "state": "Serving", "bindingUuid": "b-1",
              "fragment": {"type": "Pipeline", "uuid": "f-1", "name": "Proxy translation"},
              "promotedScore": 0.95, "rollingScore": 0.98, "records": 1204,
              "waiting": 0, "guidance": 0, "raised": false, "belowThreshold": false, "provisional": false,
              "pipelineDeleted": false, "lastSessionMs": 1761600000000}""".replace("DOC", DOC_JSON);

    /// `POST /shapeshifterAiSupervisor/v1/learners` for the document: the firewall's pipeline and the
    /// proxy's.
    static final String LEARNERS = "{\"values\": [" + FIREWALL_LEARNER + ", " + PROXY_LEARNER + "], "
                                   + "\"pageResponse\": {\"offset\": 0, \"length\": 2, \"total\": 2, "
                                   + "\"exact\": true}}";

    /// The question the paused run stopped at, as a turn records it.
    static final String TURN_4 = "Configure: dsParser TextConverter, after 1 shortfall(s)";

    // The paused run's parser answer, a configuration of several lines, as JSON carries it
    private static final String PARSER = String.join("\\n",
            "<dataSplitter xmlns='data-splitter:3' version='3.0'>",
            "  <split delimiter='\\\\n'>",
            "    <group>",
            "      <split delimiter=','>",
            "        <var id='field' />",
            "      </split>",
            "    </group>",
            "  </split>",
            "</dataSplitter>");

    // The firewall's run: the chain, a parser that didn't read all the input and went back to the chain,
    // the chain again, and the parser asked again, where it ran out of time
    private static final String TURNS = """
            [{"number": 1, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
               "question": "Chain: choose from [dsParser, xmlFragmentParser, xslt]",
               "answer": "dsParser\\nxslt", "outcome": "PASSED", "answeredBy": "model"},
             {"number": 2, "stepId": "parse", "kind": "CONFIGURE", "candidate": 1,
               "question": "Configure: dsParser TextConverter",
               "answer": "PARSER",
               "outcome": "COVERAGE_SHORT", "answeredBy": "model",
               "score": {"scorer": "INPUT_COVERAGE", "score": 0.42, "threshold": 0.9}},
             {"number": 3, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
               "question": "Chain: choose from [dsParser, xmlFragmentParser, xslt], after 1 shortfall(s)",
               "answer": "dsParser\\nxslt", "outcome": "PASSED", "answeredBy": "model"},
             {"number": 4, "stepId": "parse", "kind": "CONFIGURE", "candidate": 2,
               "question": "TURN_4"}]"""
            .replace("PARSER", PARSER)
            .replace("TURN_4", TURN_4);

    /// `GET /shapeshifterAiSupervisor/v1/7`: the firewall's run, paused out of time, with its report as the
    /// engine writes it: the error, the details and the suggestions.
    static final String PAUSED = """
            {"id": 7, "learner": {"pipelineUuid": "p-1", "elementId": "shapeshifterAi"}, "doc": DOC,
              "status": "Parked", "feed": "FIREWALL", "type": "Raw Events", "node": "node1a",
              "createTimeMs": 1761800000000, "updateTimeMs": 1761800600000, "score": 0.42,
              "tokensSpent": 12000, "grantedTokens": 0, "grantedMs": 0, "exhaustNext": false,
              "pipelineDeleted": false, "promotionMode": "AUTOMATIC", "inputId": 101,
              "decision": "Ran out of time at \\"Write the parser\\" (question 4).",
              "report": {"cause": "TIME_BUDGET", "step": "parse",
                "error": "Ran out of time at \\"Write the parser\\" (question 4).",
                "details": ["Each run may take 5 minutes; this one had taken 5 minutes 2 seconds."],
                "suggestions": [{"text": "Give it more time.", "action": "GIVE_MORE"},
                  {"text": "Raise \\"Time for each run\\" on the Setup tab.", "action": "CHANGE_LIMITS"}]},
              "turns": TURNS}""".replace("DOC", DOC_JSON).replace("TURNS", TURNS);

    /// The firewall's run as it would be had the model not replied in time: failed, its learner as it was.
    static final String FAILED = """
            {"id": 7, "learner": {"pipelineUuid": "p-1", "elementId": "shapeshifterAi"}, "doc": DOC,
              "status": "Error", "feed": "FIREWALL", "type": "Raw Events", "node": "node1a",
              "createTimeMs": 1761800000000, "updateTimeMs": 1761800600000,
              "tokensSpent": 9000, "grantedTokens": 0, "grantedMs": 0, "exhaustNext": false,
              "pipelineDeleted": false, "promotionMode": "AUTOMATIC", "inputId": 101,
              "decision": "The model \\"GPT-4o\\" didn't reply in time.",
              "report": {"cause": "MODEL_TIMEOUT",
                "error": "The model \\"GPT-4o\\" didn't reply in time.",
                "details": ["The call to the model timed out before it answered."],
                "suggestions": [
                  {"text": "Raise the timeout in the model document's HTTP Client Configuration.",
                    "action": "OPEN_MODEL"},
                  {"text": "Or choose a faster model on the Setup tab.", "action": "CHANGE_MODEL"}]},
              "turns": TURNS}""".replace("DOC", DOC_JSON).replace("TURNS", TURNS);

    /// The firewall's run stopped with a report, for a story of each kind of stop: its status, its decision
    /// (the report's error line), the report as the engine writes it, and the turns of [#PAUSED].
    ///
    /// @param status The run's status as JSON names it, e.g. "Parked", "Error" or "Abandoned".
    /// @param error  The report's error line.
    /// @param report The report, as JSON.
    /// @return The run, as JSON.
    static String firewallRun(final String status, final String error, final String report) {
        return """
                {"id": 7, "learner": {"pipelineUuid": "p-1", "elementId": "shapeshifterAi"}, "doc": DOC,
                  "status": "STATUS", "feed": "FIREWALL", "type": "Raw Events", "node": "node1a",
                  "createTimeMs": 1761800000000, "updateTimeMs": 1761800600000,
                  "tokensSpent": 12000, "grantedTokens": 0, "grantedMs": 0, "exhaustNext": false,
                  "pipelineDeleted": false, "promotionMode": "AUTOMATIC", "inputId": 101,
                  "decision": "DECISION", "report": REPORT, "turns": TURNS}"""
                .replace("DOC", DOC_JSON)
                .replace("TURNS", TURNS)
                .replace("STATUS", status)
                .replace("DECISION", json(error))
                .replace("REPORT", report);
    }

    /// The firewall's pipeline's learners ([#FIREWALL_LEARNER]) with its learner given up, for the reason
    /// given, as `GET /shapeshifterAiSupervisor/v1/pipelines/p-1/learners` lists them.
    ///
    /// @param reason Why it gave up: its run's error line.
    /// @return The learners, as JSON.
    static String firewallGivenUp(final String reason) {
        return "[" + FIREWALL_LEARNER.replace("\"state\": \"Waiting for a person\",",
                "\"state\": \"Given up\", \"givenUpReason\": \"" + json(reason) + "\",") + "]";
    }

    // Text as it goes between a JSON string's quotes
    private static String json(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /// `GET /shapeshifterAiSupervisor/v1/9`: the proxy's run, which put its translation to use.
    static final String PROMOTED = """
            {"id": 9, "learner": {"pipelineUuid": "p-3", "elementId": "shapeshifterAi"}, "doc": DOC,
              "status": "Promoted", "feed": "PROXY", "type": "Raw Events", "node": "node1a",
              "createTimeMs": 1761600000000, "updateTimeMs": 1761600600000, "score": 0.95,
              "tokensSpent": 30500, "grantedTokens": 0, "grantedMs": 0, "exhaustNext": false,
              "pipelineDeleted": false, "promotionMode": "AUTOMATIC", "inputId": 88, "bindingUuid": "b-1",
              "decision": "Promoted", "turns": [
                {"number": 1, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
                  "question": "Chain: choose from [dsParser, xslt]", "answer": "dsParser\\nxslt",
                  "outcome": "PASSED", "answeredBy": "model"},
                {"number": 2, "stepId": "parse", "kind": "CONFIGURE", "candidate": 1,
                  "question": "Configure: dsParser TextConverter", "answer": "<dataSplitter/>",
                  "outcome": "PASSED", "answeredBy": "model"}]}""".replace("DOC", DOC_JSON);

    /// The firewall's run that learned the translation it runs, promoted: [#PROMOTED] as the firewall's.
    static final String FIREWALL_PROMOTED = PROMOTED
            .replace("\"id\": 9", "\"id\": 7")
            .replace("\"pipelineUuid\": \"p-3\"", "\"pipelineUuid\": \"p-1\"");

    private static final String GUIDANCE = """
            [{"id": 1, "message": "Timestamps are in UTC.", "author": "admin", "timeMs": 1761700000000}]""";
    private static final String MORE_GUIDANCE = """
            [{"id": 1, "message": "Timestamps are in UTC.", "author": "admin", "timeMs": 1761700000000},
             {"id": 2, "message": "Ports are numbers.", "author": "admin", "timeMs": 1761800000000}]""";

    private static final String WAITING = """
            {"values": [{"inputId": 101, "doc": DOC, "feed": "FIREWALL", "type": "Raw Events",
                "firstDeferredMs": 1761700000000, "tries": 1, "failingRecords": 12, "raised": false,
                "reason": "Coverage short"}],
              "pageResponse": {"offset": 0, "length": 1, "total": 1, "exact": true}}""".replace("DOC", DOC_JSON);

    private ShapeshifterAiFixtures() {
        // Static utility
    }

    /// Adds the routes a Shapeshifter AI document's editor asks for, beyond its Permissions tab and
    /// document pickers: the document (fetched, and echoed when saved), the built-in texts, and what the
    /// Supervisor says about the document's feeds, and does when asked.
    ///
    /// @param builder The story's fixtures.
    /// @param doc     The document, as JSON.
    /// @return The builder.
    static RestFixtures.Builder editorRoutes(final RestFixtures.Builder builder, final String doc) {
        return editorRoutes(builder, doc, PAUSED);
    }

    /// As [#editorRoutes(RestFixtures.Builder, String)], with the firewall's run given.
    ///
    /// @param builder  The story's fixtures.
    /// @param doc      The document, as JSON.
    /// @param firewall The firewall's learning run, as JSON, e.g. [#PAUSED] or [#FAILED].
    /// @return The builder.
    static RestFixtures.Builder editorRoutes(final RestFixtures.Builder builder,
                                             final String doc,
                                             final String firewall) {
        return editorRoutes(builder, doc, firewall, LEARNERS);
    }

    /// As [#editorRoutes(RestFixtures.Builder, String, String)], with the learners given.
    ///
    /// @param builder  The story's fixtures.
    /// @param doc      The document, as JSON.
    /// @param firewall The firewall's learning run, as JSON.
    /// @param learners The document's learners, as JSON, e.g. [#LEARNERS].
    /// @return The builder.
    static RestFixtures.Builder editorRoutes(final RestFixtures.Builder builder,
                                             final String doc,
                                             final String firewall,
                                             final String learners) {
        return builder
                .get("/shapeshifterAi/v1/templates", RestReply.json(TEMPLATES))
                .get("/shapeshifterAi/v1/ss-1", RestReply.json(doc))
                .put("/shapeshifterAi/v1/ss-1", request -> RestReply.json(request.getBody()))
                .post("/shapeshifterAiSupervisor/v1/learners", RestReply.json(learners))
                .route(RequestMatcher.post("/shapeshifterAiSupervisor/v1/find")
                                .withJsonBodyContaining("{\"learner\": {\"pipelineUuid\": \"p-3\"}}"),
                        RestReply.json(page(PROMOTED)))
                .post("/shapeshifterAiSupervisor/v1/find", RestReply.json(page(firewall)))
                .get("/shapeshifterAiSupervisor/v1/7", RestReply.json(firewall))
                .get("/shapeshifterAiSupervisor/v1/9", RestReply.json(PROMOTED))
                .post("/shapeshifterAiSupervisor/v1/7/grant", RestReply.json(PAUSED))
                .post("/shapeshifterAiSupervisor/v1/7/turns/4", RestReply.json(PAUSED))
                .post("/shapeshifterAiSupervisor/v1/learners/ss-1/giveUp", RestReply.json("true"))
                .post("/shapeshifterAiSupervisor/v1/learners/ss-1/waiting", RestReply.json(WAITING))
                .get("/shapeshifterAiSupervisor/v1/guidance/ss-1", RestReply.json(GUIDANCE))
                .post("/shapeshifterAiSupervisor/v1/guidance/ss-1", RestReply.json(MORE_GUIDANCE));
    }

    /// Adds the routes a pipeline's AI Translation tab asks for: what the Supervisor says about the firewall's
    /// and the proxy's pipelines' learners (the firewall's as given), their runs, and does when asked; and the
    /// settings of the document they learn under.
    ///
    /// @param builder          The story's fixtures.
    /// @param doc              The document they learn under, as JSON, e.g. [#DOC].
    /// @param firewall         The firewall's learning run, as JSON, e.g. [#PAUSED] or [#FAILED].
    /// @param firewallLearners The firewall's pipeline's learners, as JSON, e.g. `[FIREWALL_LEARNER]`.
    /// @return The builder.
    static RestFixtures.Builder pipelineRoutes(final RestFixtures.Builder builder,
                                               final String doc,
                                               final String firewall,
                                               final String firewallLearners) {
        return editorRoutes(builder, doc, firewall)
                .get("/shapeshifterAiSupervisor/v1/pipelines/p-1/learners", RestReply.json(firewallLearners))
                .get("/shapeshifterAiSupervisor/v1/pipelines/p-3/learners",
                        RestReply.json("[" + PROXY_LEARNER + "]"))
                .get("/shapeshifterAiSupervisor/v1/learners/ss-1/settings", RestReply.json(doc))
                // Nothing said by QA of any pipeline, which Learning Status asks to flag open issues; a story of
                // the Quality Assurance tab gives its own replies first
                .route(RequestMatcher.get("/shapeshifterAiQuality/v1/*"), RestReply.json("[]"));
    }

    private static String page(final String run) {
        return "{\"values\": [" + run + "], "
               + "\"pageResponse\": {\"offset\": 0, \"length\": 1, \"total\": 1, \"exact\": true}}";
    }
}
