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

package stroom.gwt.workbench.client.app.learningflow;

import stroom.docref.DocRef;
import stroom.gwt.workbench.client.app.rest.RequestMatcher;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.client.app.rest.RestReply;
import stroom.learningflow.shared.LearningFlowDoc;

/// The fake REST replies shared by the Learning Flow stories: a document, the built-in texts the node
/// serves, and the Supervisor's replies about the document's feeds and learning runs.
final class LearningFlowFixtures {

    static final DocRef DOC_REF = new DocRef(LearningFlowDoc.TYPE, "ss-1", "Firewall logs");

    /// The reply to `GET /learningFlow/v1/{uuid}`: a document with learning on, a model, a target schema, its
    /// own checks (the Standard ones, but with business rules as advice with one assertion), and a flow of
    /// two steps, where the second goes back to the first when the parser doesn't read all the input.
    static final String DOC = """
            {"type": "LearningFlow", "uuid": "ss-1", "name": "Firewall logs",
              "description": "# Firewall logs",
              "promotionMode": "AUTOMATIC", "heldOutFraction": 0.2,
              "minRecordsToLearn": 20, "regressionCap": 50,
              "relearnThreshold": 0.6,
              "checks": [
                {"check": "COMPILE", "state": "GATE"},
                {"check": "RULES", "state": "ADVICE", "threshold": 0.9,
                  "parameters": {"type": "businessRules", "includeTransformMessages": false,
                    "assertions": [{"name": "users are named",
                      "xpath": "EventDetail/Authenticate/User/Id != ''"}]}}],
              "learningMode": "AUTOMATIC",
              "model": {"type": "OpenAIModel", "uuid": "model-1", "name": "GPT-4o"},
              "targetSchema": {"type": "XMLSchema", "uuid": "schema-1", "name": "event-logging v4.1.0"},
              "instructions": "Prefer the standard schema.", "maxAttempts": 3,
              "attemptBudgetMs": 300000, "tokenBudget": 0, "sampleSizeLimit": 65536,
              "maxDeferralAgeMs": 86400000,
              "flow": {"builtInVersion": 1, "templates": {},
                "steps": [
                  {"kind": "CHAIN", "id": "chain"},
                  {"kind": "CONFIGURE", "role": "PARSER", "id": "parse",
                    "transitions": [{"on": "COVERAGE_SHORT", "goTo": "chain"}]}]}}""";

    /// [#DOC] with a target schema that isn't event-logging, so it has no marked Unknown for the fallback
    /// to write.
    static final String OTHER_SCHEMA_DOC = DOC.replace(
            "{\"type\": \"XMLSchema\", \"uuid\": \"schema-1\", \"name\": \"event-logging v4.1.0\"}",
            "{\"type\": \"XMLSchema\", \"uuid\": \"schema-2\", \"name\": \"sightings v2.4\"}");

    /// [#DOC] with a target schema whose root may hold a header beside its records, so the header would be
    /// read as a record too.
    static final String HEADED_SCHEMA_DOC = DOC.replace(
            "{\"type\": \"XMLSchema\", \"uuid\": \"schema-1\", \"name\": \"event-logging v4.1.0\"}",
            "{\"type\": \"XMLSchema\", \"uuid\": \"schema-3\", \"name\": \"batches v1\"}");

    /// The reply to `GET /learningFlow/v1/targetSchema/schema-1`: event-logging, the target of [#DOC].
    private static final String EVENT_LOGGING_SCHEMA = """
            {"name": "event-logging v4.1.0", "eventLogging": true, "rootElement": "Events",
              "rootChildren": ["Event"]}""";
    /// The reply to `GET /learningFlow/v1/targetSchema/schema-2`: a schema that isn't event-logging, the
    /// target of [#OTHER_SCHEMA_DOC].
    private static final String SIGHTINGS_SCHEMA = """
            {"name": "sightings v2.4", "eventLogging": false, "rootElement": "Sightings",
              "rootChildren": ["Sighting"]}""";
    /// The reply to `GET /learningFlow/v1/targetSchema/schema-3`: a schema whose root may hold a header
    /// beside its records, the target of [#HEADED_SCHEMA_DOC].
    private static final String BATCHES_SCHEMA = """
            {"name": "batches v1", "eventLogging": false, "rootElement": "Batch",
              "rootChildren": ["Header", "Record"]}""";

    /// A newly created document where no event-logging schema is installed: learning off, no model, no target
    /// schema, the Standard checks and the Direct flow. It won't learn anything, and its Pipelines tab says so.
    static final String NEW_DOC = """
            {"type": "LearningFlow", "uuid": "ss-1", "name": "Firewall logs",
              "promotionMode": "AUTOMATIC", "heldOutFraction": 0.2,
              "minRecordsToLearn": 10, "regressionCap": 100, "relearnThreshold": 0.8,
              "learningMode": "DISABLED", "maxAttempts": 5,
              "attemptBudgetMs": 60000, "sampleSizeLimit": 8192, "maxDeferralAgeMs": 86400000,
              "flow": {"steps": [
                {"kind": "CHAIN"},
                {"kind": "CONFIGURE", "role": "PARSER"},
                {"kind": "CONFIGURE", "role": "TRANSFORM", "transitions": [{"goTo": "admit"}]},
                {"kind": "ADMIT"}]}}""";

    /// A document with every check off except compile, though it has a target schema. Its Pipelines tab says
    /// nearly any answer would pass, and offers the Standard checks.
    static final String NO_GATES_DOC = """
            {"type": "LearningFlow", "uuid": "ss-1", "name": "Firewall logs",
              "promotionMode": "AUTOMATIC", "heldOutFraction": 0.2,
              "minRecordsToLearn": 10, "regressionCap": 100, "relearnThreshold": 0.8,
              "learningMode": "AUTOMATIC",
              "model": {"type": "OpenAIModel", "uuid": "model-1", "name": "GPT-4o"},
              "targetSchema": {"type": "XMLSchema", "uuid": "schema-1", "name": "event-logging v4.1.0"},
              "checks": [{"check": "COMPILE", "state": "GATE"}, {"check": "COVERAGE", "state": "OFF"},
                {"check": "COMPLETENESS", "state": "OFF"},
                {"check": "WHOLENESS", "state": "OFF"}, {"check": "CONFORMANCE", "state": "OFF"},
                {"check": "PRESERVATION", "state": "OFF"}, {"check": "FIDELITY", "state": "OFF"},
                {"check": "REGRESSION", "state": "OFF"}],
              "maxAttempts": 5, "attemptBudgetMs": 60000, "sampleSizeLimit": 8192, "maxDeferralAgeMs": 86400000,
              "flow": {"steps": [
                {"kind": "CHAIN"},
                {"kind": "CONFIGURE", "role": "PARSER"},
                {"kind": "CONFIGURE", "role": "TRANSFORM", "transitions": [{"goTo": "admit"}]},
                {"kind": "ADMIT"}]}}""";

    /// [#DOC] with the coverage check switched off, although its second step still has a rule that fires
    /// when the parser doesn't read all the input. The step's card warns that the rule never fires.
    static final String RULE_ON_OFF_CHECK_DOC = DOC.replace(
            "{\"check\": \"COMPILE\", \"state\": \"GATE\"},",
            "{\"check\": \"COMPILE\", \"state\": \"GATE\"}, {\"check\": \"COVERAGE\", \"state\": \"OFF\"},");

    /// [#DOC] with its first step listing its own element types to choose from, one of which, XMLParser,
    /// the AI can't learn.
    static final String DOC_UNLEARNABLE = DOC.replace("{\"kind\": \"CHAIN\", \"id\": \"chain\"}",
            "{\"kind\": \"CHAIN\", \"id\": \"chain\", \"elements\": [\"XMLParser\", \"XSLTFilter\"]}");

    /// [#DOC] with no target schema (D1): learning on and a model, but nothing for the AI to write to, so it
    /// won't learn anything.
    static final String DOC_UNTARGETED = DOC.replace(
            "\"targetSchema\": {\"type\": \"XMLSchema\", \"uuid\": \"schema-1\", "
            + "\"name\": \"event-logging v4.1.0\"},", "");

    /// The reply to `GET /learningFlow/v1/templates`: the built-in question texts.
    static final String TEMPLATES = """
            {"version": 1, "templates": {
              "SYSTEM": "You write Stroom pipelines.",
              "CHAIN": "Choose the elements of the chain.",
              "CONFIGURATION": "Configure the parser."}}""";

    /// The reply to `GET /learningFlow/v1/flowExamples`: the built-in default setups. The client compares a
    /// document's flow with these to name its approach, and loads a setup from them, so any story that shows
    /// the Setup tab needs them. `Direct` is given in full because [#NEW_DOC] uses it.
    static final String FLOW_EXAMPLES = """
            [{"example": "DIRECT", "displayValue": "Direct", "steps": [
                {"kind": "CHAIN", "when": "ALWAYS"},
                {"kind": "CONFIGURE", "role": "PARSER", "when": "ALWAYS"},
                {"kind": "CONFIGURE", "role": "TRANSFORM", "when": "ALWAYS",
                  "transitions": [{"goTo": "admit"}]},
                {"kind": "ADMIT", "when": "ALWAYS"}]},
             {"example": "TARGET_FIRST", "displayValue": "Target first", "steps": [
                {"kind": "CHAIN", "when": "ALWAYS"},
                {"kind": "SPLIT", "when": "ALWAYS"},
                {"kind": "TARGET", "when": "ALWAYS", "kinds": 3},
                {"kind": "CONFIGURE", "role": "PARSER", "when": "ALWAYS"},
                {"kind": "CONFIGURE", "role": "TRANSFORM", "when": "ALWAYS",
                  "transitions": [{"on": "PRESERVATION_SHORT", "goTo": "parser"}, {"goTo": "admit"}]},
                {"kind": "ADMIT", "when": "ALWAYS"}]},
             {"example": "ESCALATING", "displayValue": "Escalating", "steps": [
                {"kind": "CHAIN", "when": "ALWAYS"},
                {"kind": "SPLIT", "when": "ALWAYS"},
                {"kind": "CONFIGURE", "role": "PARSER", "when": "ALWAYS",
                  "transitions": [{"on": "YIELD_SHORT", "goTo": "split"}]},
                {"id": "first", "kind": "CONFIGURE", "role": "TRANSFORM", "when": "ALWAYS",
                  "candidates": 2,
                  "transitions": [{"on": "PASSED", "goTo": "end"}, {"goTo": "target"}]},
                {"kind": "TARGET", "when": "ALWAYS", "kinds": 3},
                {"id": "again", "kind": "CONFIGURE", "role": "PARSER", "when": "ALWAYS"},
                {"kind": "CONFIGURE", "role": "TRANSFORM", "when": "ALWAYS",
                  "transitions": [{"on": "PRESERVATION_SHORT", "goTo": "again"}, {"goTo": "admit"}]},
                {"kind": "ADMIT", "when": "ALWAYS"}]}]""";

    private static final String DOC_JSON = "{\"type\": \"LearningFlow\", \"uuid\": \"ss-1\", "
                                           + "\"name\": \"Firewall logs\"}";

    /// The firewall pipeline's learner, waiting for a person, with two streams waiting. A learner's state
    /// and a run's status are sent as their display values.
    static final String FIREWALL_LEARNER = """
            {"learner": {"pipelineUuid": "p-1", "elementId": "learningFlow"}, "doc": DOC,
              "pipeline": {"type": "Pipeline", "uuid": "p-1", "name": "Firewall pipeline"},
              "state": "Waiting for a person", "waiting": 2, "records": 0, "guidanceCount": 1, "raised": false,
              "belowThreshold": false, "pipelineDeleted": false,
              "lastSessionMs": 1761800600000, "oldestWaitMs": 1761700000000}""".replace("DOC", DOC_JSON);

    /// The firewall pipeline's learner, which would be learning but is paused because its Learning Flow has
    /// reached its hourly spending limit.
    static final String FIREWALL_SPEND_PAUSED = FIREWALL_LEARNER.replace("\"state\": \"Waiting for a person\",",
            "\"state\": \"Learning\", \"spendPaused\": \"This Learning Flow spent 1500 token(s) in the last "
            + "hour, which is its ceiling of 1000. No new learning starts until the hour rolls; what is already "
            + "learning finishes.\",");

    /// The firewall pipeline's learner, where the pipeline sets the time zone used to read timestamps.
    static final String FIREWALL_ZONED = FIREWALL_LEARNER.replace("\"belowThreshold\": false,",
            "\"belowThreshold\": false, \"timeZone\": \"Europe/London\", \"timeZoneSource\": \"PIPELINE\",");

    /// The firewall pipeline's learner serving with what it learned, including the learned translation that
    /// the Learning Status tab links to.
    static final String FIREWALL_TRANSLATING = FIREWALL_LEARNER.replace("\"state\": \"Waiting for a person\",",
            "\"state\": \"Serving\", \"bindingUuid\": \"b-2\", \"fragment\": {\"type\": \"Pipeline\", "
            + "\"uuid\": \"f-2\", \"name\": \"Firewall pipeline learned\"}, \"promotedScore\": 0.97,");

    /// The proxy pipeline's learner, serving.
    static final String PROXY_LEARNER = """
            {"learner": {"pipelineUuid": "p-3", "elementId": "learningFlow"}, "doc": DOC,
              "pipeline": {"type": "Pipeline", "uuid": "p-3", "name": "Proxy pipeline"},
              "state": "Serving", "bindingUuid": "b-1",
              "fragment": {"type": "Pipeline", "uuid": "f-1", "name": "Proxy translation"},
              "promotedScore": 0.95, "rollingScore": 0.98, "records": 1204,
              "waiting": 0, "guidanceCount": 0, "raised": false, "belowThreshold": false,
              "pipelineDeleted": false, "lastSessionMs": 1761600000000}""".replace("DOC", DOC_JSON);

    /// The reply to `POST /learningFlowSupervisor/v1/learners/find` for the document: the learners of the
    /// firewall and proxy pipelines.
    static final String LEARNERS = "{\"values\": [" + FIREWALL_LEARNER + ", " + PROXY_LEARNER + "], "
                                   + "\"pageResponse\": {\"offset\": 0, \"length\": 2, \"total\": 2, "
                                   + "\"exact\": true}}";

    /// The question the paused run stopped at, as its turn records it.
    static final String TURN_4 = "Configure: dsParser TextConverter, after 1 shortfall(s)";

    // The paused run's parser answer, a multi-line configuration, escaped as it appears in JSON.
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

    // The turns of the firewall's run: the chain, a parser that didn't read all the input and so went back
    // to the chain, the chain again, and the parser asked again, at which point the run ran out of time.
    private static final String TURNS = """
            [{"number": 1, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
               "question": "Chain: choose from [dsParser, xmlFragmentParser, xslt]",
               "answer": "dsParser\\nxslt", "outcome": "PASSED", "answeredBy": "model"},
             {"number": 2, "stepId": "parse", "kind": "CONFIGURE", "candidate": 1,
               "question": "Configure: dsParser TextConverter",
               "answer": "PARSER",
               "outcome": "COVERAGE_SHORT", "answeredBy": "model",
               "score": {"check": "COVERAGE", "score": 0.42, "threshold": 0.9}},
             {"number": 3, "stepId": "chain", "kind": "CHAIN", "candidate": 1,
               "question": "Chain: choose from [dsParser, xmlFragmentParser, xslt], after 1 shortfall(s)",
               "answer": "dsParser\\nxslt", "outcome": "PASSED", "answeredBy": "model"},
             {"number": 4, "stepId": "parse", "kind": "CONFIGURE", "candidate": 2,
               "question": "TURN_4"}]"""
            .replace("PARSER", PARSER)
            .replace("TURN_4", TURN_4);

    /// The reply to `GET /learningFlowSupervisor/v1/attempts/7`: the firewall's run, parked because it ran out
    /// of time, with its report as the server writes it (the error, the details and the suggestions).
    static final String PAUSED = """
            {"id": 7, "learner": {"pipelineUuid": "p-1", "elementId": "learningFlow"}, "doc": DOC,
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

    /// The firewall's run as it would be if the model had not replied in time: failed, with its learner
    /// unchanged.
    static final String FAILED = """
            {"id": 7, "learner": {"pipelineUuid": "p-1", "elementId": "learningFlow"}, "doc": DOC,
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

    /// Builds the firewall's run stopped with a report, for the stories of each kind of stop. It has the
    /// given status, its decision is the report's error line, its report is as the server writes it, and
    /// its turns are those of [#PAUSED].
    ///
    /// @param status The run's status as JSON names it, e.g. "Parked", "Error" or "Abandoned".
    /// @param error  The report's error line, also used as the run's decision.
    /// @param report The report, as JSON.
    /// @return The run, as JSON.
    static String firewallRun(final String status, final String error, final String report) {
        return """
                {"id": 7, "learner": {"pipelineUuid": "p-1", "elementId": "learningFlow"}, "doc": DOC,
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

    // How the firewall's new translation was judged: two gates passed, one advice check met and one advice
    // check fallen short of.
    private static final String EVIDENCE = """
            "evidence": [
              {"check": "COMPILE", "state": "GATE", "score": 1.0, "threshold": 1.0},
              {"check": "CONFORMANCE", "state": "GATE", "score": 1.0, "threshold": 1.0},
              {"check": "QUALITY", "state": "ADVICE", "score": 0.8, "threshold": 0.7},
              {"check": "TIMES", "state": "ADVICE", "score": 0.5, "threshold": 1.0}], \
            """;

    /// The reply to `GET /learningFlowSupervisor/v1/attempts/7`: the firewall's run, with its new translation
    /// waiting for a person to approve it, and how it was judged.
    static final String AWAITING_REVIEW = firewallRun("Awaiting review", "Awaiting review, scored 0.88", "null")
            .replace("\"turns\": ", "\"score\": 0.88, \"bindingUuid\": \"b-7\", " + EVIDENCE + "\"turns\": ");

    /// The same run after a person approved it, recording who approved it and when.
    static final String APPROVED = firewallRun("Promoted", "Approved", "null")
            .replace("\"turns\": ", "\"score\": 0.88, \"bindingUuid\": \"b-7\", " + EVIDENCE
                                    + "\"reviewedBy\": \"jo\", \"reviewedTimeMs\": 1761900000000, \"turns\": ");

    /// Builds the firewall pipeline's learners ([#FIREWALL_LEARNER]) with the learner given up for the given
    /// reason, as `GET /learningFlowSupervisor/v1/pipelines/p-1/learners` lists them.
    ///
    /// @param reason Why the learner gave up, i.e. its run's error line.
    /// @return The learners, as JSON.
    static String firewallGivenUp(final String reason) {
        return "[" + FIREWALL_LEARNER.replace("\"state\": \"Waiting for a person\",",
                "\"state\": \"Given up\", \"givenUpReason\": \"" + json(reason) + "\",") + "]";
    }

    // Escapes text for use between a JSON string's quotes.
    private static String json(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /// The reply to `GET /learningFlowSupervisor/v1/attempts/9`: the proxy's run, which promoted its translation.
    static final String PROMOTED = """
            {"id": 9, "learner": {"pipelineUuid": "p-3", "elementId": "learningFlow"}, "doc": DOC,
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

    /// The firewall's promoted run that learned the translation it serves: [#PROMOTED] with the firewall's
    /// run id and pipeline.
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
                "firstDeferredMs": 1761700000000, "timesDeferred": 1, "failingRecords": 12, "raised": false,
                "reason": "Coverage short"}],
              "pageResponse": {"offset": 0, "length": 1, "total": 1, "exact": true}}""".replace("DOC", DOC_JSON);

    private static final String NOTHING_WAITING = """
            {"values": [], "pageResponse": {"offset": 0, "length": 0, "total": 0, "exact": true}}""";

    // The firewall's and the proxy's learners, as the Supervisor addresses them
    private static final String FIREWALL = "/learningFlowSupervisor/v1/pipelines/p-1/elements/learningFlow";
    private static final String PROXY = "/learningFlowSupervisor/v1/pipelines/p-3/elements/learningFlow";

    private LearningFlowFixtures() {
        // Static utility
    }

    /// Adds the routes a Learning Flow document's editor calls, other than those of its Permissions tab and
    /// document pickers: the document (fetched, and echoed back when saved), the built-in texts, and the
    /// Supervisor's replies about the document's feeds and to the actions it is asked to take.
    ///
    /// @param builder The story's fixtures.
    /// @param doc     The document, as JSON.
    /// @return The builder.
    static RestFixtures.Builder editorRoutes(final RestFixtures.Builder builder, final String doc) {
        return editorRoutes(builder, doc, PAUSED);
    }

    /// As [#editorRoutes(RestFixtures.Builder, String)], but with the given firewall run.
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

    /// As [#editorRoutes(RestFixtures.Builder, String, String)], but with the given learners.
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
                .get("/learningFlow/v1/templates", RestReply.json(TEMPLATES))
                .get("/learningFlow/v1/flowExamples", RestReply.json(FLOW_EXAMPLES))
                .get("/learningFlow/v1/ss-1", RestReply.json(doc))
                .get("/learningFlow/v1/targetSchema/schema-1", RestReply.json(EVENT_LOGGING_SCHEMA))
                .get("/learningFlow/v1/targetSchema/schema-2", RestReply.json(SIGHTINGS_SCHEMA))
                .get("/learningFlow/v1/targetSchema/schema-3", RestReply.json(BATCHES_SCHEMA))
                .put("/learningFlow/v1/ss-1", request -> RestReply.json(request.getBody()))
                .post("/learningFlowSupervisor/v1/learners/find", RestReply.json(learners))
                .route(RequestMatcher.post("/learningFlowSupervisor/v1/attempts/find")
                                .withJsonBodyContaining("{\"learner\": {\"pipelineUuid\": \"p-3\"}}"),
                        RestReply.json(page(PROMOTED)))
                .post("/learningFlowSupervisor/v1/attempts/find", RestReply.json(page(firewall)))
                .get("/learningFlowSupervisor/v1/attempts/7", RestReply.json(firewall))
                .get("/learningFlowSupervisor/v1/attempts/9", RestReply.json(PROMOTED))
                .post("/learningFlowSupervisor/v1/attempts/7/grant", RestReply.json(PAUSED))
                .post("/learningFlowSupervisor/v1/attempts/7/turns/4", RestReply.json(PAUSED))
                .post(FIREWALL + "/giveUp", RestReply.json(FIREWALL_LEARNER))
                .post(FIREWALL + "/waiting", RestReply.json(WAITING))
                .get(FIREWALL + "/guidance", RestReply.json(GUIDANCE))
                .post(FIREWALL + "/guidance", RestReply.json(MORE_GUIDANCE))
                .post(PROXY + "/waiting", RestReply.json(NOTHING_WAITING))
                .get(PROXY + "/guidance", RestReply.json("[]"));
    }

    /// Adds the routes a pipeline's AI Translation tab calls: the Supervisor's replies about the learners of
    /// the firewall and proxy pipelines (the firewall's as given) and their runs, and to the actions it is
    /// asked to take, and the settings of the document they learn under.
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
                .get("/learningFlowSupervisor/v1/pipelines/p-1/learners", RestReply.json(firewallLearners))
                .get("/learningFlowSupervisor/v1/pipelines/p-3/learners",
                        RestReply.json("[" + PROXY_LEARNER + "]"))
                .get(FIREWALL + "/settings", RestReply.json(doc))
                .get(PROXY + "/settings", RestReply.json(doc))
                // Nothing from the quality resource. A story of the Quality Assurance tab adds its own replies
                // before these.
                .route(RequestMatcher.get("/learningFlowQuality/v1/*"), RestReply.json("[]"));
    }

    private static String page(final String run) {
        return "{\"values\": [" + run + "], "
               + "\"pageResponse\": {\"offset\": 0, \"length\": 1, \"total\": 1, \"exact\": true}}";
    }
}
