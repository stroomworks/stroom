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

import stroom.gwt.workbench.client.app.editors.DocEditors;
import stroom.gwt.workbench.client.app.rest.RestFixtures;
import stroom.gwt.workbench.framework.client.play.Play;
import stroom.gwt.workbench.framework.client.story.StoryContext;
import stroom.gwt.workbench.framework.client.story.StoryLayout;
import stroom.gwt.workbench.framework.client.story.StoryRegistry;

import com.google.gwt.user.client.ui.Widget;

import java.util.HashMap;
import java.util.Map;

/// The `App/Editors/LearningFlowStopReports` stories show the AI Translation tab of Stroom's real pipeline
/// editor ([AiPipelines]) with the firewall's learning run stopped, one story for each way a run can stop.
/// Each shows the run's report (error, details and suggestions) as the server's `StopReports` words it.
/// The stories are mainly for looking at, so each play only checks that the headline and the suggestions
/// are shown and that the page has no problems.
///
/// The reports were produced by `StopReports` itself from realistic answers and failures, so that what is
/// shown is what a person would read. They are kept here as JSON, as the Supervisor serves them.
public final class LearningFlowStopReportStories {

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
                "{\"cause\": \"NO_MODEL\", \"error\": \"The Learning Flow names no model to ask.\", \"details\":"
                + " [], \"suggestions\": [{\"text\": \"Choose a model on the Setup tab, or switch learning of"
                + "f there.\", \"action\": \"CHANGE_MODEL\"}]}");
        REPORTS.put("no-elements",
                "{\"cause\": \"SETUP\", \"error\": \"None of the element types this Learning Flow allows can be "
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
                "{\"cause\": \"OTHER\", \"error\": \"The learning run failed: The folder 'Learning Flow/F"
                + "irewall' could not be made.\", \"details\": [\"It failed with IllegalStateException.\"], "
                + "\"suggestions\": [{\"text\": \"Learning tries again from the feed's next stream.\"}]}");
    }

    private LearningFlowStopReportStories() {
        // Static utility
    }

    /// Adds the stories to the registry.
    ///
    /// @param registry The registry to add to.
    public static void addTo(final StoryRegistry registry) {
        registry.component("App/Editors/LearningFlowStopReports", LearningFlowStopReportStories.class)
                .layout(StoryLayout.FULLSCREEN)
                // A step ran out of tries. The report says how each answer fell short, gives the closest
                // score, and quotes the AI's last, rejected reply.
                .story("StepOutOfTries", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "step-spent", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't find what one record is: all 5 answers fell short."))
                // Every answer fell short in the same way, so more tries are not suggested.
                .story("EveryAnswerFellShortTheSameWay", context -> render(context, LearningFlowFixtures.DOC,
                        "Parked", "step-spent-same-way", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't write the transform: all 3 answers fell short."))
                // The parser ran out of tries, which stops learning, so the report suggests learning again
                // rather than answering the step.
                .story("ParserGivesUp", context -> render(context, LearningFlowFixtures.DOC, "Abandoned",
                        "parser-gives-up", true))
                .withPlay(play -> expectReport(play,
                        "Stopped learning: couldn't write the parser: all 3 answers fell short."))
                // A step's own rule paused the run.
                .story("StepRule", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "step-rule", false))
                .withPlay(play -> expectReport(play,
                        "Paused: stopped at \"Write the transform\", as its rule says to when the answer doesn't "
                        + "compile."))
                // A step's element failed on its input, so there was nothing to ask the model.
                .story("StepFailed", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "step-failed", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't find what one record is: JSONParser cannot read the sample as JSON."))
                // Finish with Unknowns was chosen, but the flow has no fallback.
                .story("FallbackMissing", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "fallback-missing", false))
                .withPlay(play -> expectReport(play,
                        "Paused: couldn't finish with Unknowns: \"Write the transform\" has no rule to use the "
                        + "fallback."))
                // The run took longer than the time allowed for each run.
                .story("OutOfTime", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "out-of-time", false))
                .withPlay(play -> expectReport(play,
                        "Paused: ran out of time at \"Write the parser\" (question 4)."))
                // The run spent more tokens than it is allowed.
                .story("OutOfTokens", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "out-of-tokens", false))
                .withPlay(play -> expectReport(play,
                        "Paused: ran out of tokens at \"Write the transform\" (question 9)."))
                // The model didn't reply in time.
                .story("ModelTimeout", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "model-timeout", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" didn't reply in time."))
                // The model refused the request because it couldn't sign in.
                .story("ModelSignIn", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "model-sign-in", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" refused the request: it couldn't sign in."))
                // The model wasn't found by its server.
                .story("ModelNotFound", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "model-not-found", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" wasn't found by its server."))
                // The model rejected the request because of too many requests.
                .story("ModelRateLimit", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "model-rate-limit", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" turned the request away: too many requests."))
                // The model couldn't be reached.
                .story("ModelUnreachable", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "model-unreachable", false))
                .withPlay(play -> expectReport(play,
                        "Failed: couldn't reach the model \"GPT-4o\"."))
                // The model's server returned an error.
                .story("ModelHttpError", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "model-http-error", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the model \"GPT-4o\" returned an error (HTTP 502)."))
                // The document names no model.
                .story("NoModel", context -> render(context, LearningFlowFixtures.NEW_DOC, "Error",
                        "no-model", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the Learning Flow names no model to ask."))
                // None of the allowed element types can be learned.
                .story("NoElementTypes", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "no-elements", false))
                .withPlay(play -> expectReport(play,
                        "Paused: none of the element types this Learning Flow allows can be learned with."))
                // The learning steps have a problem, so learning can't start.
                .story("InvalidSteps", context -> render(context, LearningFlowFixtures.DOC, "Abandoned",
                        "invalid-steps", true))
                .withPlay(play -> expectReport(play,
                        "Stopped learning: the learning steps have a problem, so learning can't start."))
                // The learning steps went round in a loop.
                .story("StepsGoRoundALoop", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "loop", false))
                .withPlay(play -> expectReport(play,
                        "Paused: the learning steps went round in a loop."))
                // The learning steps ended without writing an element.
                .story("ElementNeverWritten", context -> render(context, LearningFlowFixtures.DOC, "Parked",
                        "not-written", false))
                .withPlay(play -> expectReport(play,
                        "Paused: the learning steps ended without writing XSLTFilter."))
                // Any other failure.
                .story("OtherFailure", context -> render(context, LearningFlowFixtures.DOC, "Error",
                        "other-failure", false))
                .withPlay(play -> expectReport(play,
                        "Failed: the learning run failed: The folder 'Learning Flow/Firewall' could not be "
                        + "made."));
    }

    // Checks that the report's error is shown as the headline, that the suggestions are listed, and that the
    // page has no problems.
    private static void expectReport(final Play play, final String headline) {
        play.expect(play.findByText(headline)).toBeInTheDocument();
        play.expect(play.getByRole("list", "Suggestions")).toBeInTheDocument();
        DocEditors.expectNoProblems(play);
    }

    // Renders the firewall pipeline at its AI Translation tab, with its run stopped as the report says, and
    // its learner given up if `givenUp` is set.
    private static Widget render(final StoryContext context,
                                 final String doc,
                                 final String status,
                                 final String report,
                                 final boolean givenUp) {
        final String json = REPORTS.get(report);
        final String error = error(json);
        final String run = LearningFlowFixtures.firewallRun(status, error, json);
        final String learners = givenUp
                ? LearningFlowFixtures.firewallGivenUp(error)
                : "[" + LearningFlowFixtures.FIREWALL_LEARNER + "]";
        return AiPipelines.render(context, false, AiPipelines.ONE_ELEMENT,
                LearningFlowFixtures.pipelineRoutes(RestFixtures.builder(), doc, run, learners),
                AiPipelines.ELEMENT);
    }

    // Reads a report's error line from its JSON, unescaping it.
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
