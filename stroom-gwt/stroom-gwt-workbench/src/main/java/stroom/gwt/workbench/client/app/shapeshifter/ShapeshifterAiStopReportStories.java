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

import stroom.gwt.workbench.client.app.editors.DocEditors;
import stroom.gwt.workbench.client.app.editors.DocEditors.DocResource;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;
import stroom.shapeshifter.client.component.ShapeshifterAiEditor;
import stroom.shapeshifter.shared.ShapeshifterAiResource;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Widget;

import java.util.HashMap;
import java.util.Map;

/// The `App/Editors/ShapeshifterAiStopReports` stories: the Feeds tab of Stroom's real
/// [ShapeshifterAiEditor] with the firewall's learning run stopped in each of the ways a run can stop, a
/// story each, with its report (error, details, suggestions) as the engine's `StopReports` words it. They
/// are there to be seen: each says the headline is shown, and that the page has no problems.
///
/// The reports were written by `StopReports` itself, from realistic answers and failures, so that what is
/// shown is what a person would read; they are kept here as JSON, as the Supervisor serves them.
public final class ShapeshifterAiStopReportStories {

    private static final Map<String, String> REPORTS = new HashMap<>();

    static {
        REPORTS.put("step-spent",
                "{\"cause\": \"STEP_SPENT\", \"step\": \"split\", \"error\": \"Couldn't find what one recor"
                + "d is: all 5 answers fell short.\", \"details\": [\"4 fell short because the records are cu"
                + "t into pieces.\", \"1 fell short because the answer isn't in the reply format.\", \"The cl"
                + "osest scored 41%; 90% was needed.\", \"The AI's last answer: \\\"No single element name fi"
                + "ts. The records are the mixed children of batch: transfer, authentication and export eleme"
                + "nts, each one record.\\\"\"], \"suggestions\": [{\"text\": \"Leave the AI a note saying wh"
                + "at one record is.\", \"action\": \"LEAVE_NOTE\"}, {\"text\": \"Answer this step yourself."
                + "\", \"action\": \"ANSWER\"}, {\"text\": \"Give the step more tries, under \\\"Fine-tune th"
                + "is step\\\" on the Setup tab.\", \"action\": \"CHANGE_STEPS\"}]}");
        REPORTS.put("step-spent-same-way",
                "{\"cause\": \"STEP_SPENT\", \"step\": \"transform\", \"error\": \"Couldn't write the trans"
                + "form: all 3 answers fell short.\", \"details\": [\"It was writing XSLTFilter.\", \"3 fell "
                + "short because the events aren't valid against the event schema.\", \"The closest scored 75"
                + "%; 90% was needed.\", \"The last answer fell short on: Record 2: Schema conformance scored"
                + " 0.0 against a threshold of 1.0.\"], \"suggestions\": [{\"text\": \"Every answer fell shor"
                + "t the same way, so more tries may not help. Leave the AI a note about what its answers get"
                + " wrong.\", \"action\": \"LEAVE_NOTE\"}, {\"text\": \"Answer this step yourself.\", \"actio"
                + "n\": \"ANSWER\"}]}");
        REPORTS.put("parser-gives-up",
                "{\"cause\": \"STEP_SPENT\", \"step\": \"parser\", \"error\": \"Couldn't write the parser: "
                + "all 3 answers fell short.\", \"details\": [\"It was writing DSParser.\", \"1 fell short be"
                + "cause the answer doesn't compile.\", \"2 fell short because the records don't read all the"
                + " input.\", \"The closest scored 61%; 90% was needed.\", \"The last answer fell short on: L"
                + "ine 1 matched no expression of the data splitter.\"], \"suggestions\": [{\"text\": \"Leave"
                + " the AI a note about what its answers get wrong.\", \"action\": \"LEAVE_NOTE\"}, {\"text\""
                + ": \"Then learn the feed again from its next stream.\", \"action\": \"LEARN_AGAIN\"}, {\"te"
                + "xt\": \"Give the step more tries, under \\\"Fine-tune this step\\\" on the Setup tab.\", "
                + "\"action\": \"CHANGE_STEPS\"}]}");
        REPORTS.put("step-rule",
                "{\"cause\": \"STEP_RULE\", \"step\": \"transform\", \"error\": \"Stopped at \\\"Write the "
                + "transform\\\", as its rule says to when the answer doesn't compile.\", \"details\": [\"The"
                + " rule says to pause for a person when the answer doesn't compile.\", \"The last answer fel"
                + "l short on: Error at line 3: unexpected end of stylesheet.\"], \"suggestions\": [{\"text\""
                + ": \"Answer this step yourself.\", \"action\": \"ANSWER\"}, {\"text\": \"Change the step's "
                + "rules on the Setup tab.\", \"action\": \"CHANGE_STEPS\"}]}");
        REPORTS.put("step-failed",
                "{\"cause\": \"STEP_FAILED\", \"step\": \"split\", \"error\": \"Couldn't find what one reco"
                + "rd is: JSONParser cannot read the sample as JSON.\", \"details\": [\"Unexpected character "
                + "'<' at line 1, column 1.\"], \"suggestions\": [{\"text\": \"Check the element types the AI"
                + " may use, on the Setup tab.\", \"action\": \"CHANGE_STEPS\"}, {\"text\": \"Answer this ste"
                + "p yourself.\", \"action\": \"ANSWER\"}]}");
        REPORTS.put("fallback-missing",
                "{\"cause\": \"FALLBACK_MISSING\", \"step\": \"transform\", \"error\": \"Couldn't finish wi"
                + "th Unknowns: \\\"Write the transform\\\" has no rule to use the fallback.\", \"details\": "
                + "[\"Finish with Unknowns needs the step to use the fallback when it runs out of tries.\"], "
                + "\"suggestions\": [{\"text\": \"Choose \\\"Serve what it can\\\" under \\\"If the transform"
                + " can't be finished\\\" on the Setup tab.\", \"action\": \"CHANGE_STEPS\"}, {\"text\": \"Or"
                + " answer this step yourself.\", \"action\": \"ANSWER\"}]}");
        REPORTS.put("out-of-time",
                "{\"cause\": \"TIME_BUDGET\", \"step\": \"parser\", \"error\": \"Ran out of time at \\\"Wri"
                + "te the parser\\\" (question 4).\", \"details\": [\"Each run may take 5 minutes; this one h"
                + "ad taken 5 minutes 2 seconds.\"], \"suggestions\": [{\"text\": \"Give it more time.\", \"a"
                + "ction\": \"GIVE_MORE\"}, {\"text\": \"Raise \\\"Time for each run\\\" on the Setup tab.\","
                + " \"action\": \"CHANGE_LIMITS\"}]}");
        REPORTS.put("out-of-tokens",
                "{\"cause\": \"TOKEN_BUDGET\", \"step\": \"transform\", \"error\": \"Ran out of tokens at "
                + "\\\"Write the transform\\\" (question 9).\", \"details\": [\"A run may spend 100,000 token"
                + "s; this one had spent 101,234.\"], \"suggestions\": [{\"text\": \"Give it more tokens.\", "
                + "\"action\": \"GIVE_MORE\"}, {\"text\": \"Raise \\\"Tokens for a whole run\\\" on the Setup"
                + " tab.\", \"action\": \"CHANGE_LIMITS\"}]}");
        REPORTS.put("model-timeout",
                "{\"cause\": \"MODEL_TIMEOUT\", \"error\": \"The model \\\"GPT-4o\\\" didn't reply in time."
                + "\", \"details\": [\"The call to the model timed out before it answered.\"], \"suggestions"
                + "\": [{\"text\": \"Raise the timeout in the model document's HTTP Client Configuration.\", "
                + "\"action\": \"OPEN_MODEL\"}, {\"text\": \"Or choose a faster model on the Setup tab.\", \""
                + "action\": \"CHANGE_MODEL\"}]}");
        REPORTS.put("model-sign-in",
                "{\"cause\": \"MODEL_REFUSED\", \"error\": \"The model \\\"GPT-4o\\\" refused the request: "
                + "it couldn't sign in.\", \"details\": [\"The server said: Incorrect API key provided: sk-…a"
                + "1b2\"], \"suggestions\": [{\"text\": \"Check the API key in the model's document.\", \"act"
                + "ion\": \"OPEN_MODEL\"}]}");
        REPORTS.put("model-not-found",
                "{\"cause\": \"MODEL_REFUSED\", \"error\": \"The model \\\"GPT-4o\\\" wasn't found by its s"
                + "erver.\", \"details\": [\"The server said: The model 'gpt-4o-latest' does not exist or you"
                + " do not have access to it.\"], \"suggestions\": [{\"text\": \"Check the model's name in it"
                + "s document.\", \"action\": \"OPEN_MODEL\"}]}");
        REPORTS.put("model-rate-limit",
                "{\"cause\": \"MODEL_REFUSED\", \"error\": \"The model \\\"GPT-4o\\\" turned the request aw"
                + "ay: too many requests.\", \"details\": [\"The server said: Rate limit reached for requests"
                + "\"], \"suggestions\": [{\"text\": \"Learning tries again from the feed's next stream. If i"
                + "t keeps happening, check the limits of the model's account.\"}]}");
        REPORTS.put("model-unreachable",
                "{\"cause\": \"MODEL_UNREACHABLE\", \"error\": \"Couldn't reach the model \\\"GPT-4o\\\".\""
                + ", \"details\": [\"The server said: java.net.ConnectException: Connection refused: llm.exam"
                + "ple.org/10.0.0.9:443\"], \"suggestions\": [{\"text\": \"Check the model's address in its d"
                + "ocument, and that its server is running.\", \"action\": \"OPEN_MODEL\"}]}");
        REPORTS.put("model-http-error",
                "{\"cause\": \"MODEL_REFUSED\", \"error\": \"The model \\\"GPT-4o\\\" returned an error (HT"
                + "TP 502).\", \"details\": [\"The server said: Bad gateway\"], \"suggestions\": [{\"text\": "
                + "\"Open the model's document.\", \"action\": \"OPEN_MODEL\"}]}");
        REPORTS.put("no-model",
                "{\"cause\": \"NO_MODEL\", \"error\": \"The document names no model to ask.\", \"details\":"
                + " [], \"suggestions\": [{\"text\": \"Choose a model on the Setup tab, or switch learning of"
                + "f there.\", \"action\": \"CHANGE_MODEL\"}]}");
        REPORTS.put("no-elements",
                "{\"cause\": \"SETUP\", \"error\": \"None of the element types this document allows can be "
                + "learned with.\", \"details\": [\"It allows: XMLParser, CombinedParser.\"], \"suggestions"
                + "\": [{\"text\": \"Tick the parsers and filters the AI may choose from, under \\\"Fine-tune"
                + " this step\\\" on the first learning step on the Setup tab.\", \"action\": \"CHANGE_STEPS\"}]}");
        REPORTS.put("invalid-steps",
                "{\"cause\": \"SETUP\", \"error\": \"The learning steps have a problem, so learning can't s"
                + "tart.\", \"details\": [\"The first step must be 'Choose the elements'.\", \"The flow needs"
                + " a step that writes the translation.\"], \"suggestions\": [{\"text\": \"Put the learning s"
                + "teps right on the Setup tab.\", \"action\": \"CHANGE_STEPS\"}]}");
        REPORTS.put("loop",
                "{\"cause\": \"SETUP\", \"error\": \"The learning steps went round in a loop.\", \"details"
                + "\": [\"The rule \\\"if the records lose what the targets need, go back to step 3 (Write th"
                + "e parser)\\\" on step 4 (Write the transform) would be followed a second time, and each ru"
                + "le is followed only once in a learning run.\"], \"suggestions\": [{\"text\": \"Change the "
                + "rule on the Setup tab, so that the steps don't go back round.\", \"action\": \"CHANGE_STEP"
                + "S\"}]}");
        REPORTS.put("not-written",
                "{\"cause\": \"SETUP\", \"error\": \"The learning steps ended without writing XSLTFilter.\""
                + ", \"details\": [\"No step writes XSLTFilter.\"], \"suggestions\": [{\"text\": \"Add a step"
                + " that writes it, or end with \\\"Write every element\\\", on the Setup tab.\", \"action\":"
                + " \"CHANGE_STEPS\"}]}");
        REPORTS.put("other-failure",
                "{\"cause\": \"OTHER\", \"error\": \"The learning run failed: The folder 'Shapeshifter AI/F"
                + "irewall' could not be made.\", \"details\": [\"It failed with IllegalStateException.\"], "
                + "\"suggestions\": [{\"text\": \"Learning tries again from the feed's next stream.\"}]}");
    }

    private ShapeshifterAiStopReportStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/ShapeshifterAiStopReports", ShapeshifterAiStopReportStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // A step ran out of tries: how each answer fell short, the closest score, and the AI's refused
                // reply
                .story("StepOutOfTries", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "step-spent", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't find what one record is: all 5 answers fell short."))
                // Every answer fell short the same way, so more tries are not suggested
                .story("EveryAnswerFellShortTheSameWay", context -> render(context, ShapeshifterAiFixtures.DOC,
                        "Parked", "step-spent-same-way", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't write the transform: all 3 answers fell short."))
                // The parser ran out of tries, which stops learning: learn again, not answer
                .story("ParserGivesUp", context -> render(context, ShapeshifterAiFixtures.DOC, "Abandoned",
                        "parser-gives-up", true))
                .withPlay(play -> expectReport(play,
                        "Stopped learning: couldn't write the parser: all 3 answers fell short."))
                // A step's own rule paused the run
                .story("StepRule", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "step-rule", false))
                .withPlay(play -> expectReport(play,
                        "Paused: stopped at \"Write the transform\", as its rule says to when the answer doesn't "
                        + "compile."))
                // A step's element failed on its input, with nothing to ask the model
                .story("StepFailed", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "step-failed", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't find what one record is: JSONParser cannot read the sample as JSON."))
                // Finish with Unknowns was chosen, but the flow has no fallback
                .story("FallbackMissing", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "fallback-missing", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't finish with Unknowns: \"Write the transform\" has no rule to use the "
                        + "fallback."))
                // The run took longer than its time for each run
                .story("OutOfTime", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "out-of-time", false))
                .withPlay(play -> expectReport(play,
                        "Paused: ran out of time at \"Write the parser\" (question 4)."))
                // The run spent more tokens than it may
                .story("OutOfTokens", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "out-of-tokens", false))
                .withPlay(play -> expectReport(play,
                        "Paused: ran out of tokens at \"Write the transform\" (question 9)."))
                // The model didn't reply in time
                .story("ModelTimeout", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "model-timeout", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" didn't reply in time."))
                // The model refused the request: it couldn't sign in
                .story("ModelSignIn", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "model-sign-in", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" refused the request: it couldn't sign in."))
                // The model wasn't found by its server
                .story("ModelNotFound", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "model-not-found", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" wasn't found by its server."))
                // The model turned the request away: too many requests
                .story("ModelRateLimit", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "model-rate-limit", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" turned the request away: too many requests."))
                // The model couldn't be reached
                .story("ModelUnreachable", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "model-unreachable", false))
                .withPlay(play -> expectReport(play,
                        "Failed: couldn't reach the model \"GPT-4o\"."))
                // The model's server returned an error
                .story("ModelHttpError", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "model-http-error", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" returned an error (HTTP 502)."))
                // The document names no model
                .story("NoModel", context -> render(context, ShapeshifterAiFixtures.NEW_DOC, "Error",
                        "no-model", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the document names no model to ask."))
                // None of the allowed element types can be learned with
                .story("NoElementTypes", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "no-elements", false))
                .withPlay(play -> expectReport(play,
                        "Paused: none of the element types this document allows can be learned with."))
                // The learning steps have a problem, so learning can't start
                .story("InvalidSteps", context -> render(context, ShapeshifterAiFixtures.DOC, "Abandoned",
                        "invalid-steps", true))
                .withPlay(play -> expectReport(play,
                        "Stopped learning: the learning steps have a problem, so learning can't start."))
                // The learning steps went round in a loop
                .story("StepsGoRoundALoop", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "loop", false))
                .withPlay(play -> expectReport(play,
                        "Paused: the learning steps went round in a loop."))
                // The learning steps ended without writing an element
                .story("ElementNeverWritten", context -> render(context, ShapeshifterAiFixtures.DOC, "Parked",
                        "not-written", false))
                .withPlay(play -> expectReport(play,
                        "Paused: the learning steps ended without writing XSLTFilter."))
                // Anything else that failed
                .story("OtherFailure", context -> render(context, ShapeshifterAiFixtures.DOC, "Error",
                        "other-failure", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the learning run failed: The folder 'Shapeshifter AI/Firewall' could not be "
                        + "made."));
    }

    // The feed's headline is the report's error, and the page has no problems
    private static void expectReport(final Play play, final String headline) {
        play.expect(play.findByText(headline)).toBeInTheDocument();
        play.expect(play.getByRole("list", "Suggestions")).toBeInTheDocument();
        DocEditors.expectNoProblems(play);
    }

    // The editor with the firewall's run stopped as the report says, its learner given up where it is
    private static Widget render(final StoryContext context,
                                 final String doc,
                                 final String status,
                                 final String report,
                                 final boolean givenUp) {
        final String json = REPORTS.get(report);
        final String error = error(json);
        final String run = ShapeshifterAiFixtures.firewallRun(status, error, json);
        final String learners = givenUp
                ? ShapeshifterAiFixtures.learnersGivenUp(error)
                : ShapeshifterAiFixtures.LEARNERS;
        final RestFixtures fixtures = ShapeshifterAiFixtures.editorRoutes(
                        DocEditors.docSelectionRoutes(DocEditors.permissionRoutes(RestFixtures.builder())), doc, run,
                        learners)
                .build();
        final ShapeshifterAiResource resource = GWT.create(ShapeshifterAiResource.class);
        final String uuid = ShapeshifterAiFixtures.DOC_REF.getUuid();
        return DocEditors.render(context, fixtures, false, (harness, injector) -> DocEditors.open(harness,
                ShapeshifterAiFixtures.DOC_REF,
                injector.getShapeshifterAiEditor(),
                DocResource.of(
                        restFactory -> restFactory.create(resource).method(res -> res.fetch(uuid)),
                        (restFactory, doc2) -> restFactory.create(resource)
                                .method(res -> res.update(doc2.getUuid(), doc2)))));
    }

    // A report's error line, read from its JSON, where it comes after the cause and the step
    private static String error(final String report) {
        final String key = "\"error\": \"";
        final int start = report.indexOf(key) + key.length();
        final StringBuilder error = new StringBuilder();
        for (int i = start; i < report.length() && report.charAt(i) != '"'; i++) {
            final char c = report.charAt(i);
            if (c == '\\') {
                i++;
                error.append(report.charAt(i));
            } else {
                error.append(c);
            }
        }
        return error.toString();
    }
}
