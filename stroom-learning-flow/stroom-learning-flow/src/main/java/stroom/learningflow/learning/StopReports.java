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

package stroom.learningflow.learning;

import stroom.learningflow.shared.FlowExit;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopCause;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Suggestion;
import stroom.learningflow.shared.SuggestionAction;
import stroom.learningflow.shared.TurnScore;
import stroom.learningflow.stage.Input;
import stroom.util.shared.StoredError;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.UnresolvedModelServerException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/// Builds the [StopReport] that explains to a person why a learning run stopped: an error line, the
/// details and the suggestions, in the same terms the screens use ([FlowWords]). Every reason a run can stop
/// is worded here and only here, so that the Feeds tab, the Monitoring Supervisor and the error streams all
/// say the same thing, and the wording can be tested.
///
/// What a person reads is kept short: one line for the error, a few for the details, and suggestions that
/// each say one thing to do. A reply from the model is quoted, cut to [#QUOTE] characters.
public final class StopReports {

    /// The maximum number of characters of a reply or a message quoted in a detail.
    static final int QUOTE = 200;

    private StopReports() {
        // Static utility
    }

    // ---- A step ----

    /// Reports a step that stopped the run: because it ran out of candidates, because one of its rules sent
    /// the run to an exit, or because its element failed.
    ///
    /// @param cause      Which of those it was: [StopCause#STEP_SPENT], [StopCause#STEP_RULE],
    ///                   [StopCause#STEP_FAILED] or [StopCause#FALLBACK_MISSING].
    /// @param steps      The flow's steps.
    /// @param index      The index of the step it stopped at.
    /// @param about      More to say about the step, as a sentence, e.g. "It was writing XSLTFilter.", or
    ///                   what failed for a step whose element failed; null for nothing.
    /// @param feedback   What the last answer fell short on.
    /// @param transcript The run's turns so far.
    /// @param exit       Where the run exits the flow.
    /// @return The report.
    public static StopReport ofStep(final StopCause cause,
                                    final List<FlowStep> steps,
                                    final int index,
                                    final String about,
                                    final List<StoredError> feedback,
                                    final List<Exchange> transcript,
                                    final FlowExit exit) {
        return ofStep(cause, steps, index, about, feedback, transcript, exit, null);
    }

    /// The same, adding how much of the stream the model was shown, where it was not shown all of it.
    ///
    /// @param cause      Why it stopped.
    /// @param steps      The flow's steps.
    /// @param index      The index of the step it stopped at.
    /// @param about      More to say about the step; null for nothing.
    /// @param feedback   What the last answer fell short on.
    /// @param transcript The run's turns so far.
    /// @param exit       Where the run exits the flow.
    /// @param sample     The sample the step judged, or null where there was none. A person told
    ///                   "couldn't write the parser" needs to know if the model saw, say, eight kilobytes
    ///                   of a two-megabyte stream.
    /// @return The report.
    public static StopReport ofStep(final StopCause cause,
                                    final List<FlowStep> steps,
                                    final int index,
                                    final String about,
                                    final List<StoredError> feedback,
                                    final List<Exchange> transcript,
                                    final FlowExit exit,
                                    final Sample sample) {
        final FlowStep step = steps.get(index);
        final String name = FlowWords.stepName(steps, index);
        final List<Exchange> answers = answers(step, transcript);
        return switch (cause) {
            // A rule firing is the flow doing what it was told, and a missing fallback is a fault in the
            // flow. Neither depends on what the model was shown, so neither gets the line about the sample.
            case STEP_RULE -> byRule(step, name, answers, feedback, exit);
            case FALLBACK_MISSING -> fallbackMissing(step, name);
            case STEP_FAILED -> withSample(failed(step, name, about, feedback, exit), sample);
            default -> withSample(spent(step, name, about, answers, feedback, exit), sample);
        };
    }

    // Adds a line to the report about what the model was shown, where it was shown less than was read.
    private static StopReport withSample(final StopReport report, final Sample sample) {
        if (sample == null || sample.notShown() == 0) {
            return report;
        }
        final List<String> details = new ArrayList<>(report.getDetails());
        details.add("The AI was shown " + Input.bytes(sample.text()) + " bytes of the "
                    + sample.cutFrom() + " read from this stream — the sample size limit on the Learning "
                    + "Flow. What it wrote was judged on all of them.");
        return new StopReport(report.getCause(), report.getStep(), report.getError(), details,
                report.getSuggestions());
    }

    private static StopReport spent(final FlowStep step,
                                    final String name,
                                    final String about,
                                    final List<Exchange> answers,
                                    final List<StoredError> feedback,
                                    final FlowExit exit) {
        final String error = "Couldn't " + doing(step, name);
        final List<String> details = new ArrayList<>();
        if (about != null) {
            details.add(about);
        }
        final Map<StepOutcome, Integer> tally = tally(answers);
        tally.forEach((outcome, count) -> details.add(
                count + " fell short because " + FlowWords.condition(outcome) + "."));
        best(answers).ifPresent(details::add);
        final Exchange last = answers.isEmpty()
                ? null
                : answers.get(answers.size() - 1);
        if (last != null && last.outcome() == StepOutcome.REFUSED && !blank(last.reply())) {
            details.add("The AI's last answer: \"" + quote(last.reply()) + "\"");
        } else {
            firstMessage(feedback).ifPresent(message -> details.add("The last answer fell short on: " + message));
            // A reply refused for its form often says in words what is wrong ("no single element name fits"),
            // so the latest such reply is quoted even when the model tried again after it.
            lastRefusal(answers).ifPresent(reply -> details.add(
                    "An earlier answer was refused, as not in the reply format: \"" + quote(reply) + "\""));
        }

        final boolean sameWay = answers.size() > 1 && tally.size() == 1;
        final List<Suggestion> suggestions = new ArrayList<>();
        suggestions.add(new Suggestion((sameWay
                ? "Every answer fell short the same way, so more tries may not help. "
                : "") + "Leave the AI a note " + noteAbout(step) + ".", SuggestionAction.LEAVE_NOTE));
        suggestions.add(exit == FlowExit.GIVE_UP
                ? new Suggestion("Then learn the feed again from its next stream.", SuggestionAction.LEARN_AGAIN)
                : new Suggestion("Answer this step yourself.", SuggestionAction.ANSWER));
        if (!sameWay) {
            suggestions.add(new Suggestion("Give the step more tries, under \"Fine-tune this step\" on the Setup "
                                           + "tab.", SuggestionAction.CHANGE_STEPS));
        }
        return new StopReport(StopCause.STEP_SPENT, step.effectiveId(),
                error + ": " + (answers.isEmpty()
                        ? "it ran out of tries."
                        : allFellShort(answers.size())),
                details, suggestions);
    }

    private static StopReport byRule(final FlowStep step,
                                     final String name,
                                     final List<Exchange> answers,
                                     final List<StoredError> feedback,
                                     final FlowExit exit) {
        final StepOutcome outcome = answers.isEmpty()
                ? null
                : answers.get(answers.size() - 1).outcome();
        final List<String> details = new ArrayList<>();
        details.add("The rule says to " + exitWords(exit) + " when " + FlowWords.condition(outcome) + ".");
        firstMessage(feedback).ifPresent(message -> details.add("The last answer fell short on: " + message));
        final List<Suggestion> suggestions = new ArrayList<>();
        suggestions.add(exit == FlowExit.GIVE_UP
                ? new Suggestion("Learn the feed again from its next stream.", SuggestionAction.LEARN_AGAIN)
                : new Suggestion("Answer this step yourself.", SuggestionAction.ANSWER));
        suggestions.add(new Suggestion("Change the step's rules on the Setup tab.", SuggestionAction.CHANGE_STEPS));
        return new StopReport(StopCause.STEP_RULE, step.effectiveId(),
                "Stopped at \"" + name + "\", as its rule says to when " + FlowWords.condition(outcome) + ".",
                details, suggestions);
    }

    private static StopReport failed(final FlowStep step,
                                     final String name,
                                     final String about,
                                     final List<StoredError> feedback,
                                     final FlowExit exit) {
        final List<String> details = new ArrayList<>();
        firstMessage(feedback).ifPresent(details::add);
        final List<Suggestion> suggestions = new ArrayList<>();
        suggestions.add(new Suggestion("Check the element types the AI may use, on the Setup tab.",
                SuggestionAction.CHANGE_STEPS));
        if (exit != FlowExit.GIVE_UP) {
            suggestions.add(new Suggestion("Answer this step yourself.", SuggestionAction.ANSWER));
        }
        return new StopReport(StopCause.STEP_FAILED, step.effectiveId(),
                "Couldn't " + doing(step, name) + (about == null
                        ? "."
                        : ": " + lowerFirst(sentence(about))),
                details, suggestions);
    }

    private static StopReport fallbackMissing(final FlowStep step, final String name) {
        return new StopReport(StopCause.FALLBACK_MISSING, step.effectiveId(),
                "Couldn't finish with Unknowns: \"" + name + "\" has no rule to use the fallback.",
                List.of("Finish with Unknowns needs the step to use the fallback when it runs out of tries."),
                List.of(new Suggestion("Choose \"Serve what it can\" under \"If the transform can't be finished\" "
                                       + "on the Setup tab.", SuggestionAction.CHANGE_STEPS),
                        new Suggestion("Or answer this step yourself.", SuggestionAction.ANSWER)));
    }

    /// Reports a run that reached the fallback (exhaustion) step for a target schema that isn't
    /// event-logging. The fallback writes event-logging's marked `Unknown` events, which no other schema has,
    /// so it doesn't apply, and the run stops for a person, as it does where the fallback is off.
    ///
    /// @param step     The fallback step.
    /// @param target   The target schema, not event-logging.
    /// @param feedback What the transform's last answer fell short on, which the step was reached with.
    /// @return The report.
    public static StopReport noFallbackFor(final FlowStep step,
                                           final TargetSchema target,
                                           final List<StoredError> feedback) {
        final List<String> details = new ArrayList<>();
        details.add("Marked Unknown events are event-logging's, and the target schema, " + target.name()
                    + ", has no Unknown to mark a record with, so the fallback doesn't apply to it.");
        firstMessage(feedback).ifPresent(message -> details.add("The last answer fell short on: " + message));
        return new StopReport(StopCause.STEP_SPENT, step.effectiveId(),
                "Couldn't write the transform, and there is no fallback for " + target.name() + ".",
                details,
                List.of(new Suggestion("Leave the AI a note about the records it couldn't translate.",
                                SuggestionAction.LEAVE_NOTE),
                        new Suggestion("Or answer the transform yourself.", SuggestionAction.ANSWER)));
    }

    // ---- The run's limits ----

    /// Reports a run that took longer than its time budget for each run.
    ///
    /// @param step      The step it was at.
    /// @param question  The number of the question it was at.
    /// @param allowedMs How long it may take, including any grant from a person.
    /// @param elapsedMs How long it had taken.
    /// @return The report.
    public static StopReport outOfTime(final FlowStep step,
                                       final int question,
                                       final long allowedMs,
                                       final long elapsedMs) {
        return new StopReport(StopCause.TIME_BUDGET, step.effectiveId(),
                "Ran out of time at \"" + FlowWords.stepName(step.getKind(), step.getRole()) + "\" (question "
                + question + ").",
                List.of("Each run may take " + duration(allowedMs) + "; this one had taken " + duration(elapsedMs)
                        + "."),
                List.of(new Suggestion("Give it more time.", SuggestionAction.GIVE_MORE),
                        new Suggestion("Raise \"Time for each run\" on the Setup tab.",
                                SuggestionAction.CHANGE_LIMITS)));
    }

    /// Reports a run that spent more tokens than its budget allows.
    ///
    /// @param step     The step it was at.
    /// @param question The number of the question it was at.
    /// @param allowed  How many it may spend, including any grant from a person.
    /// @param spent    How many it had spent.
    /// @return The report.
    public static StopReport outOfTokens(final FlowStep step,
                                         final int question,
                                         final long allowed,
                                         final long spent) {
        return new StopReport(StopCause.TOKEN_BUDGET, step.effectiveId(),
                "Ran out of tokens at \"" + FlowWords.stepName(step.getKind(), step.getRole()) + "\" (question "
                + question + ").",
                List.of("A run may spend " + number(allowed) + " tokens; this one had spent " + number(spent)
                        + "."),
                List.of(new Suggestion("Give it more tokens.", SuggestionAction.GIVE_MORE),
                        new Suggestion("Raise \"Tokens for a whole run\" on the Setup tab.",
                                SuggestionAction.CHANGE_LIMITS)));
    }

    // ---- The document's setup ----

    /// Reports a document that allows no element type this node can learn with.
    ///
    /// @param allowed The element types it allows.
    /// @return The report.
    public static StopReport noElements(final List<String> allowed) {
        return setup("None of the element types this Learning Flow allows can be learned with.",
                List.of("It allows: " + String.join(", ", allowed) + "."),
                "Tick the parsers and filters the AI may choose from, under \"Fine-tune this step\" on the "
                + "first learning step on the Setup tab.");
    }

    /// Reports a document whose learning steps can't be followed.
    ///
    /// @param problems What is wrong with them, a sentence each.
    /// @return The report.
    public static StopReport invalidSteps(final List<String> problems) {
        final List<String> details = new ArrayList<>();
        for (final String problem : problems) {
            details.add(sentence(problem));
        }
        return setup("The learning steps have a problem, so learning can't start.", details,
                "Put the learning steps right on the Setup tab.");
    }

    /// Reports a translation refused at promotion because it uses a function or instruction that is not on
    /// the Smart Pipelines allow-list. Stylesheets are checked when a candidate is judged and again at
    /// promotion, so this is a translation that reached promotion by a route that did not judge its
    /// stylesheet, or one whose pipeline was edited by hand since.
    ///
    /// @param refusals What it uses that it may not, one sentence each.
    /// @return The report.
    public static StopReport refusedByTheList(final List<String> refusals) {
        final List<String> details = new ArrayList<>();
        for (final String refusal : refusals) {
            details.add(sentence(refusal));
        }
        return setup("The translation uses something a learned translation may not, so it wasn't put in use.",
                details,
                "A learned translation may use only what is on the Smart Pipelines list. Where the pipeline "
                + "was edited by hand, take out what the list doesn't allow; otherwise let it learn again.");
    }

    /// Reports a stream that is a single XML or JSON document larger than the prefix read as text.
    ///
    /// Part of a document is not a document: cut at 64 KiB it does not parse, so there is nothing to find a
    /// record boundary in and no sample to show the model. Reading a whole stream of any size into memory is
    /// what the prefix exists to prevent, so instead the report says what is wrong and what would make the
    /// stream learnable, rather than letting the split step pass with no boundary and everything after it
    /// be judged on nothing.
    ///
    /// @param kind  What the stream is, for the message: "XML" or "JSON".
    /// @param bytes How much of it was read.
    /// @return The report.
    public static StopReport oneDocumentTooLarge(final String kind, final int bytes) {
        return new StopReport(StopCause.STEP_FAILED, null,
                "This stream is a single " + kind + " document larger than the "
                + bytes + " bytes that are read to learn from",
                List.of("A part of a document is not a document, so there is nothing here that can be "
                        + "parsed to settle what one record is.",
                        "Reading a whole stream of any size into memory to learn from is what that limit "
                        + "prevents, so a larger limit is not the answer."),
                List.of(new Suggestion("Send this feed as a stream of records — one document per record, "
                                       + "or records separated by lines — rather than as one document.",
                        SuggestionAction.LEAVE_NOTE),
                        new Suggestion("Or split the stream before it reaches this stage, with a pipeline "
                                       + "element in front of the Smart Pipelines stage.",
                                SuggestionAction.LEAVE_NOTE)));
    }

    /// Reports a run that went round a loop of rules.
    ///
    /// @param rule The rule, as [FlowWords#rule] says it.
    /// @param step The step it is on, as [FlowWords#step] says it.
    /// @return The report.
    public static StopReport loop(final String rule, final String step) {
        return setup("The learning steps went round in a loop.",
                List.of("The rule \"" + rule + "\" on " + step + " would be followed a second time, and each rule "
                        + "is followed only once in a learning run."),
                "Change the rule on the Setup tab, so that the steps don't go back round.");
    }

    /// Reports a run whose steps ended without writing one of its elements.
    ///
    /// @param element The element type nothing wrote.
    /// @return The report.
    public static StopReport notWritten(final String element) {
        return setup("The learning steps ended without writing " + element + ".",
                List.of("No step writes " + element + "."),
                "Add a step that writes it, or end with \"Write every element\", on the Setup tab.");
    }

    private static StopReport setup(final String error, final List<String> details, final String suggestion) {
        return new StopReport(StopCause.SETUP, null, error, details,
                List.of(new Suggestion(suggestion, SuggestionAction.CHANGE_STEPS)));
    }

    // ---- A failure ----

    /// Reports a run that failed: the model couldn't be asked or didn't answer, or something else went
    /// wrong. The kind of failure is worked out from the types of the exception and its causes, never from
    /// their messages.
    ///
    /// @param failure What was thrown.
    /// @param model   The name of the model the document asks, or null.
    /// @return The report.
    public static StopReport failure(final Throwable failure, final String model) {
        final String named = model == null
                ? "The model"
                : "The model \"" + model + "\"";
        final Suggestion openModel = new Suggestion("Open the model's document.", SuggestionAction.OPEN_MODEL);
        if (find(failure, ModelNotChosenException.class) != null) {
            return new StopReport(StopCause.NO_MODEL, null, "The Learning Flow names no model to ask.", List.of(),
                    List.of(new Suggestion("Choose a model on the Setup tab, or switch learning off there.",
                            SuggestionAction.CHANGE_MODEL)));
        }
        if (find(failure, dev.langchain4j.exception.TimeoutException.class) != null
            || find(failure, SocketTimeoutException.class) != null
            || find(failure, HttpTimeoutException.class) != null) {
            return new StopReport(StopCause.MODEL_TIMEOUT, null, named + " didn't reply in time.",
                    List.of("The call to the model timed out before it answered."),
                    List.of(new Suggestion("Raise the timeout in the model document's HTTP Client Configuration.",
                                    SuggestionAction.OPEN_MODEL),
                            new Suggestion("Or choose a faster model on the Setup tab.",
                                    SuggestionAction.CHANGE_MODEL)));
        }
        if (find(failure, AuthenticationException.class) != null) {
            return new StopReport(StopCause.MODEL_REFUSED, null, named + " refused the request: it couldn't sign in.",
                    messages(failure),
                    List.of(new Suggestion("Check the API key in the model's document.",
                            SuggestionAction.OPEN_MODEL)));
        }
        if (find(failure, ModelNotFoundException.class) != null) {
            return new StopReport(StopCause.MODEL_REFUSED, null, named + " wasn't found by its server.",
                    messages(failure),
                    List.of(new Suggestion("Check the model's name in its document.", SuggestionAction.OPEN_MODEL)));
        }
        if (find(failure, RateLimitException.class) != null) {
            return new StopReport(StopCause.MODEL_REFUSED, null, named + " turned the request away: too many "
                                                                 + "requests.",
                    messages(failure),
                    List.of(new Suggestion("Learning tries again from the feed's next stream. If it keeps "
                                           + "happening, check the limits of the model's account.", null)));
        }
        if (find(failure, ConnectException.class) != null
            || find(failure, UnknownHostException.class) != null
            || find(failure, UnresolvedModelServerException.class) != null) {
            return new StopReport(StopCause.MODEL_UNREACHABLE, null,
                    "Couldn't reach " + lowerFirst(named) + ".",
                    messages(failure),
                    List.of(new Suggestion("Check the model's address in its document, and that its server is "
                                           + "running.", SuggestionAction.OPEN_MODEL)));
        }
        final HttpException http = find(failure, HttpException.class);
        if (http != null) {
            return new StopReport(StopCause.MODEL_REFUSED, null,
                    named + " returned an error (HTTP " + http.statusCode() + ").",
                    messages(failure),
                    List.of(openModel));
        }
        return new StopReport(StopCause.OTHER, null, "The learning run failed: " + sentence(firstLine(failure)),
                List.of("It failed with " + failure.getClass().getSimpleName() + "."),
                List.of(new Suggestion("Learning tries again from the feed's next stream.", null)));
    }

    // ---- Helpers ----

    /// Returns the answers to a step since it was last entered: the turns at the end of the transcript that
    /// it asked and that were judged.
    ///
    /// @param step       The step.
    /// @param transcript The run's turns so far.
    /// @return The judged answers, oldest first.
    static List<Exchange> answers(final FlowStep step, final List<Exchange> transcript) {
        final List<Exchange> answers = new ArrayList<>();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            final Exchange exchange = transcript.get(i);
            if (!step.effectiveId().equals(exchange.step())) {
                break;
            }
            if (exchange.outcome() != null) {
                answers.add(0, exchange);
            }
        }
        return answers;
    }

    // Counts how many answers fell short in each way, in the order each way first came up.
    private static Map<StepOutcome, Integer> tally(final List<Exchange> answers) {
        final Map<StepOutcome, Integer> tally = new LinkedHashMap<>();
        for (final Exchange answer : answers) {
            if (answer.outcome() != StepOutcome.PASSED) {
                tally.merge(answer.outcome(), 1, Integer::sum);
            }
        }
        return tally;
    }

    // Says how close the best failing answer came, by the check that decided it, e.g. "The closest scored
    // 41%; 90% was needed."
    private static Optional<String> best(final List<Exchange> answers) {
        TurnScore best = null;
        for (final Exchange answer : answers) {
            final TurnScore score = answer.score();
            if (score != null && answer.outcome() != StepOutcome.PASSED
                && (best == null || score.getScore() > best.getScore())) {
                best = score;
            }
        }
        return best == null
                ? Optional.empty()
                : Optional.of("The closest scored " + percent(best.getScore()) + "; "
                                        + percent(best.getThreshold()) + " was needed.");
    }

    // Returns the reply of the latest answer refused for its form that says something; empty where there is
    // none
    private static Optional<String> lastRefusal(final List<Exchange> answers) {
        for (int i = answers.size() - 1; i >= 0; i--) {
            final Exchange answer = answers.get(i);
            if (answer.outcome() == StepOutcome.REFUSED && !blank(answer.reply())) {
                return Optional.of(answer.reply());
            }
        }
        return Optional.empty();
    }

    // Says what a step does, to follow "Couldn't", e.g. "find what one record is"
    private static String doing(final FlowStep step, final String name) {
        return step.getKind() == QuestionKind.ADMIT
                ? "translate what's left as marked Unknowns"
                : lowerFirst(name);
    }

    private static String allFellShort(final int answers) {
        return answers == 1
                ? "its 1 answer fell short."
                : "all " + answers + " answers fell short.";
    }

    private static String noteAbout(final FlowStep step) {
        return switch (step.getKind()) {
            case CHAIN -> "saying which elements the feed needs";
            case SPLIT -> "saying what one record is";
            case TARGET -> "saying what each kind of record should become";
            default -> "about what its answers get wrong";
        };
    }

    private static String exitWords(final FlowExit exit) {
        return exit == FlowExit.GIVE_UP
                ? "stop learning"
                : "pause for a person";
    }

    private static Optional<String> firstMessage(final List<StoredError> feedback) {
        if (feedback == null) {
            return Optional.empty();
        }
        for (final StoredError error : feedback) {
            if (error != null && !blank(error.getMessage())) {
                return Optional.of(sentence(quote(error.getMessage())));
            }
        }
        return Optional.empty();
    }

    private static List<String> messages(final Throwable failure) {
        final String line = firstLine(failure);
        return line.isEmpty()
                ? List.of()
                : List.of("The server said: " + line);
    }

    private static String firstLine(final Throwable failure) {
        final String message = failure.getMessage();
        return blank(message)
                ? failure.getClass().getSimpleName()
                : quote(message);
    }

    private static <T extends Throwable> T find(final Throwable failure, final Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /// Words as one line, cut to [#QUOTE] characters with an ellipsis where they were longer.
    static String quote(final String text) {
        final String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= QUOTE
                ? line
                : line.substring(0, QUOTE).stripTrailing() + "…";
    }

    private static String sentence(final String text) {
        final String trimmed = text.strip();
        return trimmed.endsWith(".") || trimmed.endsWith("…") || trimmed.endsWith("\"")
                ? trimmed
                : trimmed + ".";
    }

    /// Words with their first letter in lower case, for the middle of a sentence; a name that begins with
    /// capitals, such as "XMLParser", is left as it is.
    private static String lowerFirst(final String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.length() > 1 && Character.isUpperCase(text.charAt(1))
                ? text
                : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static boolean blank(final String text) {
        return text == null || text.isBlank();
    }

    private static String percent(final double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /// A whole number with its thousands grouped, e.g. "12,000".
    static String number(final long value) {
        return String.format(Locale.ENGLISH, "%,d", value);
    }

    /// A duration in minutes and seconds, e.g. "5 minutes", "5 minutes 2 seconds", "45 seconds".
    static String duration(final long ms) {
        final long seconds = Math.max(0L, ms / 1_000L);
        final long minutes = seconds / 60L;
        final long rest = seconds % 60L;
        if (minutes == 0L) {
            return count(rest, "second");
        }
        return rest == 0L
                ? count(minutes, "minute")
                : count(minutes, "minute") + " " + count(rest, "second");
    }

    private static String count(final long count, final String singular) {
        return count + " " + (count == 1L
                ? singular
                : singular + "s");
    }
}
