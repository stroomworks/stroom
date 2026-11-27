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

package stroom.learningflow.learning;

import stroom.learningflow.extraction.PerRecord;
import stroom.learningflow.extraction.RecordSplit;
import stroom.learningflow.learning.Outcome.Abandoned;
import stroom.learningflow.learning.Outcome.Learned;
import stroom.learningflow.learning.Question.Chain;
import stroom.learningflow.learning.Question.Configuration;
import stroom.learningflow.learning.Question.Split;
import stroom.learningflow.learning.Question.TargetFor;
import stroom.learningflow.scoring.Admission;
import stroom.learningflow.scoring.Attempted;
import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Scorecard;
import stroom.learningflow.scoring.Verdict;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ConfigureRole;
import stroom.learningflow.shared.FlowExit;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.FlowWords;
import stroom.learningflow.shared.LearnedUnknown;
import stroom.learningflow.shared.LearningFlow;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QuestionKind;
import stroom.learningflow.shared.RecordBoundary;
import stroom.learningflow.shared.StepGuard;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.StopCause;
import stroom.learningflow.shared.StopReport;
import stroom.learningflow.shared.Transition;
import stroom.learningflow.shared.TurnScore;
import stroom.learningflow.stage.Guidance.Given;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.RecordSkeleton;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.s9api.XdmNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/// Runs one learning attempt by walking the document's learning flow, asking the model each question and
/// judging each answer. The stage runs one for each attempt, and its [Outcome] is either a learned chain or
/// the reason the attempt stopped.
///
/// The flow is a graph of typed steps. The chain step settles which elements to use. Where the flow asks
/// for them, the split step settles what one record is, and the target step proposes what each kind of
/// record should become, validated at once. The configure steps ask for each element's configuration,
/// running the chain as far as it has been built so that each question carries real input. The parser is
/// checked for preserving what the targets need, and the transform for reproducing them.
///
/// This class holds the question kinds with their reply formats and judging, and the outcomes judging can
/// end in. The flow says which questions are asked, in what order, and where to go on each outcome. Which
/// checks judge a step is not up to the flow: every check that can measure a step judges it, and whether
/// a check is Off, Advice or Gate is set on the document. A step asks again, with feedback on what fell
/// short, until its candidates are spent. A transition the step declares on an outcome is taken at once,
/// and one on `spent` says where to go when the candidates are gone; without one the attempt is abandoned.
/// A pass goes to the next step unless the step says `on passed goto`, and `end` names the end of the
/// flow. Each transition is taken at most once per attempt, so every flow ends. This class knows nothing
/// about which flow it is walking.
///
/// The chain is chosen only from those of the document's allowed elements that have a [StepRunner],
/// because offering the model an element that cannot be run would lead it into a dead end. A document that
/// allows nothing runnable, or whose flow is invalid, is abandoned before the model is asked anything,
/// with the reason.
public final class Conversation {

    private static final ElementId CONVERSATION = new ElementId("Conversation");
    private static final Set<Check> SPLIT_CHECKS = EnumSet.of(Check.COVERAGE, Check.YIELD, Check.WHOLENESS);

    private final Advisor advisor;
    private final Map<String, StepRunner> runners;
    private final Scorecard scorecard;
    private final Clock clock;
    private final Runnable heartbeat;
    private final BiConsumer<Integer, Exchange> onTurn;
    private Budget budget;
    private long alreadySpent;
    /// When this run's time budget started, where that is not this conversation's start. A round that
    /// follows another in the same run shares the one time budget.
    private Long begunAtMs;
    /// What a person granted the attempt beyond the document's budgets, in tokens and milliseconds.
    private long grantedTokens;
    private long grantedMs;
    /// Whether a person sent the attempt to the exhaustion step. If so, the next transform question that
    /// would be put to the model is not asked, and the step's spent transition is taken instead.
    private boolean exhaustFirst;
    private Supplier<List<Given>> guidance = List::of;
    /// The guidance [#guidance] supplied for this walk, read when the walk begins.
    private List<Given> standing = List.of();
    /// The records of the held stream that fell short under the current transform, as the parser cut them.
    /// Every candidate for the chain's last element must pass on these as well as on the sample.
    private List<String> held = List.of();

    public Conversation(final Advisor advisor, final List<StepRunner> runners, final Scorecard scorecard) {
        this(advisor, runners, scorecard, Clock.systemUTC());
    }

    public Conversation(final Advisor advisor,
                    final List<StepRunner> runners,
                    final Scorecard scorecard,
                    final Clock clock) {
        this(advisor, runners, scorecard, clock, () -> {
        });
    }

    /// @param advisor   Asks the model each question.
    /// @param runners   A runner for each element type the chain may use.
    /// @param scorecard The document's checks, which judge each candidate.
    /// @param clock     The clock the time budget is measured by.
    /// @param heartbeat Run before every question, for a caller holding something that expires while the
    ///                  conversation runs, such as the attempt's claim on its learner, which one slow model
    ///                  call could otherwise outlast.
    public Conversation(final Advisor advisor,
                    final List<StepRunner> runners,
                    final Scorecard scorecard,
                    final Clock clock,
                    final Runnable heartbeat) {
        this(advisor, runners, scorecard, clock, heartbeat, (number, exchange) -> {
        });
    }

    /// @param advisor   Asks the model each question.
    /// @param runners   A runner for each element type the chain may use.
    /// @param scorecard The document's checks, which judge each candidate.
    /// @param clock     The clock the time budget is measured by.
    /// @param heartbeat Run before every question, to renew the attempt's claim on its learner.
    /// @param onTurn    Given every turn, with its number in the transcript, as it is answered and again as
    ///                  it is judged. This lets a caller record the attempt as it happens, so that one still
    ///                  running, or one whose node died, shows how far it got.
    public Conversation(final Advisor advisor,
                    final List<StepRunner> runners,
                    final Scorecard scorecard,
                    final Clock clock,
                    final Runnable heartbeat,
                    final BiConsumer<Integer, Exchange> onTurn) {
        this.advisor = advisor;
        this.scorecard = scorecard;
        this.clock = clock;
        this.heartbeat = heartbeat;
        this.onTurn = onTurn;
        this.runners = runners.stream()
                .collect(Collectors.toUnmodifiableMap(StepRunner::elementType, Function.identity()));
    }

    /// Sets the tokens this attempt spent before it was parked. A resumed attempt's token budget covers the
    /// whole attempt, not just its latest run, or an attempt that stops and starts could spend its budget
    /// again each time. The time budget is per run, because an attempt may wait days for a person, and that
    /// waiting is not the attempt taking too long.
    ///
    /// @param tokens The tokens already spent.
    /// @return This conversation, to be run.
    public Conversation alreadySpent(final long tokens) {
        this.alreadySpent = tokens;
        return this;
    }

    /// Counts this run's time budget from the given moment rather than from the conversation's start, so
    /// that a round that follows another in the same run shares the one time budget.
    ///
    /// @param ms When the run started, in epoch milliseconds.
    /// @return This conversation, to be run.
    public Conversation begunAt(final long ms) {
        this.begunAtMs = ms;
        return this;
    }

    /// Sends this attempt to the exhaustion step. The first transform question that would be put to the
    /// model (after any recorded turns have been replayed) is not asked, and the step's spent transition is
    /// taken instead, as though its candidates were gone. A flow whose transform step has no spent
    /// transition is abandoned there, and the stage parks the session again with that reason.
    ///
    /// @param exhaust Whether to go to the exhaustion step.
    /// @return This conversation, to be run.
    public Conversation exhaustFirst(final boolean exhaust) {
        this.exhaustFirst = exhaust;
        return this;
    }

    /// Sets what a person granted this attempt beyond the document's budgets. The grant is added to the
    /// budgets for this attempt alone.
    ///
    /// @param tokens The extra tokens granted.
    /// @param ms     The extra time granted, in milliseconds.
    /// @return This conversation, to be run.
    public Conversation granted(final long tokens, final long ms) {
        this.grantedTokens = tokens;
        this.grantedMs = ms;
        return this;
    }

    /// Makes every candidate for the chain's last element pass on these records as well as on the sample.
    /// They are the records of the held stream that fell short under the current transform, each as the
    /// parser cut it. Relearning is about these records, and without this the candidates would be judged
    /// only on a sample that may not contain them.
    ///
    /// @param records Each record as one document of its own, as the transform is given it.
    /// @return This conversation, to be run.
    public Conversation heldTo(final List<String> records) {
        held = List.copyOf(records);
        return this;
    }

    /// Sets where the guidance a person has left for the learner comes from. It is included in every
    /// question.
    ///
    /// It is a supplier rather than a list because guidance belongs to the learner, not to this object: an
    /// attempt parked awaiting the model and resumed a day later must include what was said in between.
    /// Each turn records which guidance it was asked with, so that a resumed walk replays what was used
    /// rather than what has been added since.
    ///
    /// It is read once per walk rather than once per question. A deferred attempt is walked again from the
    /// start on every pass of the worker, so reading per question would cost a database query per turn per
    /// pass, and within one walk, which takes seconds, a hint arriving between two questions makes no
    /// practical difference. The next pass reads it afresh.
    ///
    /// @param guidance Supplies the guidance in force.
    /// @return This conversation, to be run.
    public Conversation guidedBy(final Supplier<List<Given>> guidance) {
        this.guidance = guidance;
        return this;
    }

    public Outcome run(final LearningFlowDoc policy, final Sample sample) {
        return run(policy, sample, List.of());
    }

    /// Walks the flow for one attempt.
    ///
    /// @param policy  The Learning Flow document.
    /// @param sample  The sample to learn from.
    /// @param opening What the model is told before its first answer, such as a relearn's account of how
    ///                the current binding fell short. It is given as the feedback on the first asking of
    ///                every question.
    /// @return How the attempt ended.
    public Outcome run(final LearningFlowDoc policy, final Sample sample, final List<StoredError> opening) {
        final Walk walk = new Walk(policy, sample, opening);
        // Read the guidance as this walk begins. A deferred attempt is walked again from the start on every
        // pass, so each pass reads it afresh, and a hint given between passes reaches the next one.
        standing = List.copyOf(guidance.get());
        budget = new Budget(policy, begunAtMs != null
                ? begunAtMs
                : clock.millis(), advisor.tokensUsed() - alreadySpent, grantedTokens, grantedMs);
        try {
            return follow(walk);
        } catch (final BudgetExhausted e) {
            // An attempt has a time and token budget, and one that runs out is parked for a person rather
            // than left to spend without end.
            return abandoned(e.report(), walk.transcript);
        }
    }

    private Outcome follow(final Walk walk) {
        final LearningFlowDoc policy = walk.policy;
        final List<String> allowed = policy.getAllowedElements().stream()
                .filter(runners::containsKey)
                .toList();
        if (allowed.isEmpty()) {
            return abandoned(StopReports.noElements(policy.getAllowedElements()), walk.transcript);
        }
        final LearningFlow flow = policy.getFlow();
        final List<String> problems = new ArrayList<>(flow.problems());
        problems.addAll(Templates.problems(flow));
        if (!problems.isEmpty()) {
            return abandoned(StopReports.invalidSteps(problems), walk.transcript);
        }
        final List<FlowStep> steps = flow.getSteps();

        int at = 0;
        // Whether the step at `at` was reached by a transition rather than by the step before it ending.
        // The exhaustion step is entered only that way, because it is reached when another step's
        // candidates are spent, never because the step above it passed.
        boolean jumped = false;
        while (at < steps.size()) {
            final FlowStep step = steps.get(at);
            final Visit visit = step.getKind() == QuestionKind.ADMIT && !jumped
                    ? Visit.next()
                    : enter(step, allowed, walk);
            jumped = false;
            switch (visit.kind()) {
                // The feedback a transition carried is used by the next question asked, so a step its guard
                // skips passes it on rather than dropping it.
                case NEXT -> at++;
                case ABANDON -> {
                    final FlowExit exit = visit.exit() == null
                            ? FlowExit.PARK
                            : visit.exit();
                    final StopReport report = visit.report() != null
                            ? visit.report()
                            : StopReports.ofStep(visit.cause(), steps, at, visit.reason(),
                                    visit.feedback(), walk.transcript, exit, walk.sample);
                    return new Abandoned(report, List.copyOf(visit.feedback()), List.copyOf(walk.transcript), exit);
                }
                case GOTO -> {
                    // Record which transition of which step was taken, so that taking it twice is a loop.
                    final String edge = step.effectiveId() + " " + visit.transition().getOn()
                                        + " " + visit.transition().getGoTo();
                    if (!walk.taken.add(edge)) {
                        return new Abandoned(StopReports.loop(FlowWords.rule(steps, at, visit.transition()),
                                FlowWords.step(steps, at)), List.copyOf(visit.feedback()),
                                List.copyOf(walk.transcript), FlowExit.PARK);
                    }
                    walk.carried = visit.feedback();
                    jumped = true;
                    at = Transition.END.equals(visit.transition().getGoTo())
                            ? steps.size()
                            : indexOf(steps, visit.transition().getGoTo());
                }
            }
        }
        for (int i = 0; i < walk.chain.size(); i++) {
            if (walk.learned[i] == null) {
                return abandoned(StopReports.notWritten(walk.chain.get(i)), walk.transcript);
            }
        }
        return new Learned(List.of(walk.learned), walk.learned[walk.learned.length - 1].result().output(),
                walk.targets, List.copyOf(walk.transcript), walk.split == null
                ? null
                : walk.split.shared());
    }

    private static int indexOf(final List<FlowStep> steps, final String id) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).effectiveId().equals(id)) {
                return i;
            }
        }
        // Unreachable: problems() has already checked that every goto names a step.
        throw new IllegalStateException("No step named " + id);
    }

    // Runs one step of the flow, according to its kind, unless its guard says it does not apply to this
    // kind of input.
    private Visit enter(final FlowStep step, final List<String> allowed, final Walk walk) {
        if (walk.chain != null && step.getWhen() != StepGuard.ALWAYS
            && !walk.kind.name().equals(step.getWhen().name())) {
            return Visit.next();
        }
        return switch (step.getKind()) {
            case CHAIN -> chain(step, allowed, walk);
            case SPLIT -> split(step, walk);
            case TARGET -> target(step, walk);
            case CONFIGURE -> configure(step, walk, false);
            case ADMIT -> admit(step, walk);
        };
    }

    // Runs the fallback (exhaustion) step, which writes event-logging's marked Unknown events. A target
    // schema that is not event-logging has no Unknown, so there the run stops for a person instead, as it
    // does where the fallback is off.
    private Visit admit(final FlowStep step, final Walk walk) {
        final TargetSchema target = scorecard.target();
        if (TargetSchema.isEventLoggingOrNone(target)) {
            return configure(step, walk, true);
        }
        return Visit.abandonWith(StopReports.noFallbackFor(step, target, walk.carried), FlowExit.PARK);
    }

    // Asks a step's question for one unit of its work (the chain, the split, one kind of record, one
    // element), candidate by candidate, until one passes, a transition fires, or the candidates are spent.
    // `unit` asks and judges one candidate, given the feedback on the last; `spent` is the reason to give
    // when the candidates are gone and the step has no spent transition.
    private Visit candidates(final FlowStep step, final Walk walk, final Unit unit, final String spent) {
        return candidates(step, walk, unit, spent, false);
    }

    // `exhaustible` says whether a person sending the attempt to the exhaustion step cuts these candidates
    // short. If so, before the first candidate that would be put to the model (after any recorded turns
    // are replayed), the step goes where it would go with its candidates spent.
    private Visit candidates(final FlowStep step,
                             final Walk walk,
                             final Unit unit,
                             final String spent,
                             final boolean exhaustible) {
        return candidates(step, walk, unit, spent, candidates(step, walk.policy), exhaustible);
    }

    private Visit candidates(final FlowStep step,
                             final Walk walk,
                             final Unit unit,
                             final String spent,
                             final int limit,
                             final boolean exhaustible) {
        List<StoredError> feedback = walk.carried.isEmpty()
                ? walk.opening
                : walk.carried;
        walk.carried = List.of();
        for (int candidate = 1; candidate <= limit; candidate++) {
            if (exhaustible && exhaustFirst && !advisor.replaying()) {
                // A person sent the attempt to the exhaustion step: this candidate is not asked for, and the
                // step goes where it would have gone with its candidates spent. This happens only once.
                exhaustFirst = false;
                return spent(step, feedback, "A person chose Finish with Unknowns, but '"
                                             + FlowWords.stepName(step.getKind(), step.getRole())
                                             + "' has no rule to use the fallback when it runs out of tries",
                        StopCause.FALLBACK_MISSING);
            }
            final Judged judged = unit.attempt(candidate, feedback);
            judge(walk.transcript, step, candidate, judged.outcome(), judged.score());
            if (judged.outcome() == StepOutcome.PASSED) {
                return Visit.next();
            }
            final Transition at = step.transitionOn(judged.outcome());
            if (at != null) {
                return at.exits()
                        ? Visit.abandon(StopCause.STEP_RULE, leaves(step, at),
                                judged.feedback(), at.namedExit())
                        : Visit.take(at, judged.feedback());
            }
            feedback = judged.feedback();
        }
        return spent(step, feedback, spent, StopCause.STEP_SPENT);
    }

    // Decides where a step goes with its candidates spent: the transition on spent if there is one, or else
    // the attempt is abandoned for the given cause, with `about` saying more about the step (or null).
    private static Visit spent(final FlowStep step,
                               final List<StoredError> feedback,
                               final String about,
                               final StopCause cause) {
        final Transition when = step.transitionOnSpent();
        if (when != null && !when.exits()) {
            return Visit.take(when, feedback);
        }
        return Visit.abandon(cause, about, feedback, when == null
                ? null
                : when.namedExit());
    }

    // Decides where a step goes when a run-only element has failed. Such an element is asked nothing, so its
    // one run is its only candidate. The step takes the transition on the outcome, or else the one on spent,
    // since its candidates are gone, or else the attempt is abandoned with the reason.
    private static Visit runOnly(final FlowStep step, final Judged judged, final String reason) {
        final Transition at = step.transitionOn(judged.outcome());
        if (at != null && !at.exits()) {
            return Visit.take(at, judged.feedback());
        }
        final Transition when = step.transitionOnSpent();
        if (at == null && when != null && !when.exits()) {
            return Visit.take(when, judged.feedback());
        }
        // The exit the step names on this outcome, or else on spent, or else its default.
        final Transition exiting = at != null
                ? at
                : when;
        return Visit.abandon(at != null
                        ? StopCause.STEP_RULE
                        : StopCause.STEP_FAILED, reason, judged.feedback(), exiting == null
                ? null
                : exiting.namedExit());
    }

    // Decides where a step goes once every unit of its work has passed: the next step, unless it says
    // `on passed`. A step of several units (a CONFIGURE over the whole chain, a TARGET over several kinds)
    // passes as a whole, not at its first unit.
    private static Visit passed(final FlowStep step, final Visit last) {
        if (last.kind() != Visit.Kind.NEXT) {
            return last;
        }
        final Transition at = step.transitionOn(StepOutcome.PASSED);
        if (at == null) {
            return Visit.next();
        }
        return at.exits()
                ? Visit.abandon(StopCause.STEP_RULE, leaves(step, at),
                        List.of(), at.namedExit())
                : Visit.take(at, List.of());
    }

    // Says why a run left the flow by a step's rule, in the terms of the Setup tab, e.g. "At 'Write the
    // transform', the answer isn't in the reply format, and the step's rule says to pause for a person".
    private static String leaves(final FlowStep step, final Transition rule) {
        return "At '" + FlowWords.stepName(step.getKind(), step.getRole()) + "', "
               + FlowWords.condition(rule.getOn()) + ", and the step's rule says to " + FlowWords.exit(step, rule);
    }

    // Runs the chain step. With only one allowed element there is nothing to choose, so the question is not
    // asked.
    private Visit chain(final FlowStep step, final List<String> allowed, final Walk walk) {
        if (allowed.size() == 1) {
            walk.chosen(allowed, runners);
            return passed(step, Visit.next());
        }
        final Set<String> allowedSet = new HashSet<>(allowed);
        return passed(step, candidates(step, walk, (candidate, feedback) -> {
            final String reply = ask(walk, step, candidate, new Chain(walk.sample, allowed, feedback));
            final Optional<List<String>> chain = ChainReply.chain(reply, allowedSet);
            if (chain.isEmpty()) {
                return Judged.refused("The reply was not a chain of allowed element types: " + allowed);
            }
            walk.chosen(chain.get(), runners);
            return Judged.passed();
        }, null));
    }

    // Judges a split answered as the children of a container: every child element of the named one is a
    // record, whatever it is called. This is for feeds that mix several record types as siblings under one
    // container. `parent` is the container's local name, or [RecordBoundary#ROOT] for the document's root.
    private Judged childrenSplit(final FlowStep step,
                                 final Walk walk,
                                 final OutputRecords document,
                                 final String parent,
                                 final Set<Check> checks) {
        if (checks.contains(Check.WHOLENESS) && scorecard.decides(Check.WHOLENESS, step)) {
            final Optional<StoredError> shortfall = TargetChecks.recordChildren(document, parent,
                    scorecard.threshold(Check.WHOLENESS));
            if (shortfall.isPresent()) {
                return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(shortfall.get()), null);
            }
        }
        final RecordBoundary boundary = RecordBoundary.ofChildrenOf(parent);
        final List<XdmNode> found = document.recordsBy(boundary);
        if (found.isEmpty()) {
            return Judged.refused("No element named " + parent + " holds any element children here");
        }
        walk.cut(Boundary.ofChildrenOf(parent, depthOf(document, boundary)),
                found.stream().map(XdmNode::toString).toList());
        return Judged.passed();
    }

    // Runs the split step, which settles what one record is. For raw text the model is asked for a Data
    // Splitter configuration that cuts the text into records and emits each whole, judged on coverage and
    // yield (nothing about meaning is in play yet) and on wholeness, which coverage alone cannot tell. For XML
    // the model is asked which element is one record (or which element's children are), judged by whether
    // those elements exist, are not the root, and together hold nearly all of the document. For JSON it is
    // asked which array holds the records.
    private Visit split(final FlowStep step, final Walk walk) {
        final StepRunner first = walk.first(runners);
        final Set<Check> checks = SPLIT_CHECKS;
        // Nothing more to say about the step than its report says
        final String spent = null;
        if (walk.kind == InputKind.JSON) {
            return passed(step, splitJson(step, walk, first, checks, spent));
        }
        if (walk.kind == InputKind.XML) {
            // Where a parser comes first, the split is settled over its output rather than over the stream,
            // because a run of XML fragments is not a document until the parser has wrapped them, and the
            // elements to choose between are in the wrapped document.
            final Optional<OutputRecords> document = OutputRecords.parse(markup(first, walk));
            if (document.isEmpty()) {
                // A single document larger than the prefix read as text: part of a document is not a
                // document, so there is nothing to find a boundary in. Report that, rather than passing
                // the step with no boundary and judging everything after it on nothing.
                //
                // Only where the sample **looks** like XML. A chain whose first element is a transform
                // takes this branch for any input, so a CSV stream of a million lines reaches here too, and
                // its prefix is a perfectly good sample. Refusing that would refuse the feeds this
                // feature is for.
                if (walk.sample.beyondPrefix() && RecordSkeleton.isMarkup(walk.sample.text())) {
                    return Visit.abandonWith(StopReports.oneDocumentTooLarge("XML", Input.PREFIX_BYTES),
                            FlowExit.PARK);
                }
                walk.cut(null, List.of(walk.sample.text()));
                return passed(step, Visit.next());
            }
            return passed(step, candidates(step, walk, (candidate, feedback) -> {
                final String reply = ask(walk, step, candidate,
                        new Split(walk.sample, first.elementType(), null, InputKind.XML, feedback))
                        .strip().replaceAll("^<|>$", "");
                // `batch/*`, or `*` for the root's own children: every child of that element is one
                // record, whatever it is called. This is for a feed that mixes several record types as
                // siblings under one container, where no single name describes a record.
                if (reply.equals("*") || reply.matches("[A-Za-z_][\\w.-]*/\\*")) {
                    final String parent = reply.equals("*")
                            ? RecordBoundary.ROOT
                            : reply.substring(0, reply.length() - 2);
                    return childrenSplit(step, walk, document.get(), parent, checks);
                }
                if (!reply.matches("[A-Za-z_][\\w.-]*")) {
                    return Judged.refused("The reply was not an element name, nor a container's children "
                                          + "as name/*");
                }
                final List<String> records = TargetChecks.elementsNamed(document.get(), reply);
                if (records.isEmpty()) {
                    return Judged.refused("No element named " + reply + " occurs in the input");
                }
                if (checks.contains(Check.WHOLENESS) && scorecard.decides(Check.WHOLENESS, step)) {
                    final Optional<StoredError> shortfall = TargetChecks.recordElement(document.get(), reply,
                            scorecard.threshold(Check.WHOLENESS));
                    if (shortfall.isPresent()) {
                        return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(shortfall.get()), null);
                    }
                }
                walk.cut(Boundary.ofElement(reply, depthOf(document.get(), RecordBoundary.ofElement(reply))),
                        records);
                return Judged.passed();
            }, spent));
        }
        final StepRunner.Configured configured = first.configured().orElseThrow();
        final Scorecard over = scorecard.only(checks);
        return passed(step, candidates(step, walk, (candidate, feedback) -> {
            final String reply = ask(walk, step, candidate,
                    new Split(walk.sample, first.elementType(), configured.documentType(), InputKind.TEXT, feedback));
            final Optional<String> configuration = ConfigurationReply.configuration(reply);
            if (configuration.isEmpty()) {
                return Judged.refused("The reply was not a single " + configured.documentType() + " document");
            }
            final StepResult result = first.run(configuration.get(), walk.sample.text());
            if (!result.passed()) {
                return Judged.failed(result, true);
            }
            final Verdict verdict = over.judge(Attempted.of(first, walk.sample.text(), result));
            if (Scorecard.outcome(verdict, step) != StepOutcome.PASSED) {
                return Judged.fellShort(verdict, step, verdict.feedback());
            }
            final List<String> records = recordTexts(result.output());
            if (records.isEmpty()) {
                return Judged.refused("The split produced no records; each record should be emitted whole, as one "
                                      + "data value");
            }
            if (checks.contains(Check.WHOLENESS) && scorecard.decides(Check.WHOLENESS, step)) {
                final Optional<StoredError> partial = TargetChecks.wholeness(walk.sample.text(), records,
                        scorecard.threshold(Check.WHOLENESS));
                if (partial.isPresent()) {
                    return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(partial.get()), null);
                }
            }
            walk.cut(Boundary.ofConfiguration(configuration.get()), records);
            return Judged.passed(verdict, step);
        }, spent));
    }

    // Runs the split question for JSON. The parser turns the sample into XML in the XSL/json vocabulary,
    // and the model names the array whose items are the records by its key, or `root` where each top-level
    // value is one. The answer is judged by whether such an array exists and whether its items hold nearly
    // all of the document.
    private Visit splitJson(final FlowStep step,
                            final Walk walk,
                            final StepRunner first,
                            final Set<Check> checks,
                            final String spent) {
        final StepResult parsed = first.run(null, walk.sample.text());
        final Optional<OutputRecords> document = parsed.passed()
                ? OutputRecords.parse(parsed.output())
                : Optional.empty();
        if (document.isEmpty()) {
            if (walk.sample.beyondPrefix()) {
                return Visit.abandonWith(StopReports.oneDocumentTooLarge("JSON", Input.PREFIX_BYTES),
                        FlowExit.PARK);
            }
            return runOnly(step, Judged.failed(parsed, false),
                    first.elementType() + " cannot read the sample as JSON");
        }
        return candidates(step, walk, (candidate, feedback) -> {
            final String reply = ask(walk, step, candidate,
                    new Split(walk.sample, first.elementType(), null, InputKind.JSON, feedback)).strip()
                    .replaceAll("^[\"']|[\"']$", "");
            // A key must be a plain name, not an XPath fragment: the reply goes into a predicate, so anything
            // that could close one is refused here rather than failing in Saxon later.
            if (!reply.matches("[\\w.:-]+")) {
                return Judged.refused("The reply was not an array's key, nor the word " + Boundary.ROOT
                                      + ". A key is a word: letters, digits, dots, colons, hyphens or "
                                      + "underscores");
            }
            final List<String> records;
            if (Boundary.ROOT.equalsIgnoreCase(reply)) {
                records = TargetChecks.rootRecords(document.get());
            } else {
                records = TargetChecks.arrayItems(document.get(), reply);
                if (records.isEmpty()) {
                    return Judged.refused("No array with the key \"" + reply + "\" occurs in the input");
                }
            }
            if (checks.contains(Check.WHOLENESS) && scorecard.decides(Check.WHOLENESS, step)) {
                final Optional<StoredError> partial = TargetChecks.wholeness(document.get().root().getStringValue(),
                        records.stream().map(TargetChecks::textOf).toList(), scorecard.threshold(Check.WHOLENESS));
                if (partial.isPresent()) {
                    return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(partial.get()), null);
                }
            }
            final String key = Boundary.ROOT.equalsIgnoreCase(reply)
                    ? Boundary.ROOT
                    : reply;
            walk.cut(Boundary.ofArray(key, depthOf(document.get(), RecordBoundary.ofArray(key))), records);
            return Judged.passed();
        }, spent);
    }

    // Runs the target step: for each kind of record, the model proposes the event it should become, or
    // `none` for a kind that yields no event. Each event is judged at once by the scorers of meaning over
    // the wrapped event, and re-asked with the shortfalls of what the model wrote. A header or a comment
    // appears once in a sample, while a kind seen more than once is probably what the feed exists to
    // report, so its first `none` is questioned and only a second `none` is accepted. Questioning a `none`
    // does not use up a candidate.
    private Visit target(final FlowStep step, final Walk walk) {
        final List<String> over = walk.records == null
                ? recordsOf(walk, runners)
                : walk.records;
        final int kinds = step.getKinds() == null
                ? FlowStep.DEFAULT_KINDS
                : step.getKinds();
        final Scorecard meaning = scorecard.meaning();
        final List<String> representatives = TargetChecks.representatives(over, kinds);
        final List<Target> proposed = new ArrayList<>();
        for (int i = 0; i < representatives.size(); i++) {
            final String record = representatives.get(i);
            final int kind = i + 1;
            final int seen = TargetChecks.count(over, record);
            final Visit visit = candidates(step, walk, (candidate, feedback) -> {
                String reply = ask(walk, step, candidate,
                        new TargetFor(walk.sample, record, kind, representatives.size(), feedback));
                if (TargetChecks.NONE.equalsIgnoreCase(reply.strip()) && seen > 1) {
                    reply = ask(walk, step, candidate, new TargetFor(walk.sample, record, kind,
                            representatives.size(), List.of(new StoredError(Severity.WARNING, null, CONVERSATION,
                                    "This kind of record is seen " + seen + " times in the sample; a header or a "
                                    + "comment is seen once. If these records carry no event, reply none again; "
                                    + "otherwise write the event, with the fields the record has and without "
                                    + "those it lacks"))));
                }
                if (TargetChecks.NONE.equalsIgnoreCase(reply.strip())) {
                    proposed.add(Target.none(record));
                    return Judged.passed();
                }
                final Optional<String> event = ConfigurationReply.configuration(reply);
                if (event.isEmpty()) {
                    return Judged.refused("The reply was not a single event document, nor the word none");
                }
                // Wrap it in the root of the schema the document's translations must produce
                final String document = TargetChecks.asDocument(event.get(), scorecard.target());
                final Optional<StoredError> refusal = TargetChecks.refusal(document, scorecard.target());
                if (refusal.isPresent()) {
                    return new Judged(StepOutcome.REFUSED, List.of(refusal.get()), null);
                }
                final Verdict verdict = TargetChecks.verdict(meaning, record, document);
                if (Scorecard.outcome(verdict, step) != StepOutcome.PASSED) {
                    return Judged.fellShort(verdict, step, verdict.feedback());
                }
                proposed.add(Target.of(record, event.get()));
                return Judged.passed(verdict, step);
            }, "For record kind " + kind + ".");
            if (visit.kind() != Visit.Kind.NEXT) {
                return visit;
            }
        }
        walk.targets = List.copyOf(proposed);
        return passed(step, Visit.next());
    }

    // Runs a configure step over each element the step's role names, in chain order. Each element is asked
    // for its configuration where it takes one, run over the real output of the element before it, and
    // judged by the step's checks: the scorecard, and against the targets, which a parser must preserve
    // and the chain's last element must reproduce. A role the chain does not have is skipped.
    private Visit configure(final FlowStep step, final Walk walk, final boolean admitting) {
        final Set<Check> checks = EnumSet.allOf(Check.class);
        final Scorecard over = scorecard.only(checks);
        // The exhaustion step asks for the transform's configuration once more, with the marked Unknown form
        // added, so it always configures the transform, whatever role the flow gave it.
        final ConfigureRole role = admitting
                ? ConfigureRole.TRANSFORM
                : step.getRole();
        for (final int index : walk.elements(role, runners)) {
            final StepRunner runner = runners.get(walk.chain.get(index));
            if (index > 0 && walk.learned[index - 1] == null) {
                return Visit.abandon(StopCause.STEP_FAILED, runner.elementType() + " cannot be configured before "
                                     + walk.chain.get(index - 1) + " has been", List.of());
            }
            final String input = index == 0
                    ? walk.sample.text()
                    : walk.learned[index - 1].result().output();
            final boolean last = index == walk.chain.size() - 1;
            // From the first element that is not a parser, the fragment's SplitFilter gives each element
            // one record at a time. So the model is shown one record, as a person writing a Stroom
            // stylesheet would be, and the candidate is run over every record, as it will be in use.
            // The first element after the parser is given the parser's records; every element after that
            // is given what the one before it wrote for each record, which is one level deep. A fragment
            // has one SplitFilter, not one per element.
            final OptionalInt depth = runner.parser() || walk.split == null
                    ? OptionalInt.empty()
                    : depth(walk.split).stream()
                            .map(settled -> index == firstTransform(walk)
                                    ? settled
                                    : PerRecord.WRITTEN)
                            .findFirst();
            final List<String> perRecord = depth.isPresent()
                    ? RecordSplit.split(input, depth.getAsInt())
                    : List.of();
            final boolean oneAtATime = !perRecord.isEmpty();
            final String shown = oneAtATime
                    ? perRecord.get(0)
                    : input;
            // Show one record of each other kind, where the flow has not settled its targets yet. Where it
            // has, the targets already show a record of each kind beside the event it must become, and
            // showing them here as well would spend tokens saying the same thing twice.
            final List<String> otherKinds = oneAtATime && walk.targets.isEmpty()
                    ? TargetChecks.representatives(perRecord).stream().skip(1).toList()
                    : List.of();
            final Optional<StepRunner.Configured> configured = runner.configured();
            final Optional<String> fixed = runner.fixedConfiguration();
            final Visit visit;
            if (configured.isPresent() && fixed.isPresent()) {
                // An element with a fixed configuration: run it with that, judge it, and record it so that the
                // fragment is written with the document a pipeline needs, without spending a candidate
                // asking the model for something it has no say in.
                final StepResult ran = oneAtATime
                        ? PerRecord.run(runner, fixed.get(), input, perRecord)
                        : runner.run(fixed.get(), input);
                final Judged judged = judge(step, over, checks, runner, fixed.get(), input, ran, walk, last, admitting);
                walk.learn(index, judged.learned());
                visit = judged.outcome() == StepOutcome.PASSED
                        ? Visit.next()
                        : runOnly(step, judged, runner.elementType() + " failed on its input");
            } else if (configured.isEmpty()) {
                // Nothing is asked for a run-only element, so it is run once and judged; feedback a
                // transition carried here is left for the next question asked.
                // It too is run per record where the split has settled, because after the filter it is
                // given one record at a time, exactly as a configured element is.
                final StepResult ran = oneAtATime
                        ? PerRecord.run(runner, null, input, perRecord)
                        : runner.run(null, input);
                final Judged judged = judge(step, over, checks, runner, null, input, ran, walk, last, admitting);
                walk.learn(index, judged.learned());
                visit = judged.outcome() == StepOutcome.PASSED
                        ? Visit.next()
                        : runOnly(step, judged, runner.elementType() + " failed on its input");
            } else {
                // A parser must cut the split's records; a transform over XML input is told the record element
                final boolean carriesSplit = runner.parser()
                                             || (walk.split != null && walk.split.configuration() == null);
                final Boundary split = carriesSplit
                        ? walk.split
                        : null;
                // Sending the attempt to the exhaustion step cuts short a transform's candidates. It does not
                // cut short a parser's, because the exhaustion step deals with records that were parsed but
                // cannot be translated, nor the exhaustion step's own, because that is where it sends them.
                final boolean exhaustible = !admitting && !runner.parser();
                final Visit asked = candidates(step, walk, (candidate, feedback) -> {
                    final String reply = ask(walk, step, candidate, new Question.Configuration(runner.elementType(),
                            configured.get().documentType(), walk.sample, shown, walk.previous[index], split,
                            walk.targets, oneAtATime, carried(perRecord), otherKinds, admitting, feedback));
                    final Optional<String> configuration = ConfigurationReply.configuration(reply);
                    if (configuration.isEmpty()) {
                        return Judged.refused("The reply was not a single " + configured.get().documentType()
                                              + " document");
                    }
                    walk.previous[index] = configuration.get();
                    final StepResult result = oneAtATime
                            ? PerRecord.run(runner, configuration.get(), input, perRecord)
                            : runner.run(configuration.get(), input);
                    final Judged judged = judge(step, over, checks, runner, configuration.get(), input, result,
                            walk, last, admitting);
                    walk.learn(index, judged.learned());
                    return judged;
                }, (admitting
                        ? "It was serving "
                        : "It was writing ") + runner.elementType() + ".", exhaustible);
                // Where the flow names no exit, use the step's default. A parser whose candidates are spent
                // is treated as unlearnable, the one failure that gives the learner up; any other step parks
                // for a person.
                visit = asked.kind() == Visit.Kind.ABANDON
                        ? asked.withDefaultExit(runner.parser()
                                ? FlowExit.GIVE_UP
                                : FlowExit.PARK)
                        : asked;
            }
            if (visit.kind() != Visit.Kind.NEXT) {
                return visit;
            }
        }
        // A step whose pass transition exits the flow without naming an exit also takes its default: give
        // up where the step is the parser's, park otherwise.
        return passed(step, Visit.next()).withDefaultExit(step.getRole() == ConfigureRole.PARSER
                ? FlowExit.GIVE_UP
                : FlowExit.PARK);
    }

    // Runs a candidate over each record the held stream fell short on, judging each one alone. Returns the
    // outcome for the first record that falls short, with the record named in the feedback, or null where
    // every record passes.
    private Judged heldTo(final FlowStep step,
                          final Scorecard over,
                          final StepRunner runner,
                          final String configuration,
                          final RecordBoundary boundary,
                          final boolean admitting) {
        try (StepRunner.Prepared prepared = runner.prepare(configuration)) {
            for (int i = 0; i < held.size(); i++) {
                final String record = held.get(i);
                final StepResult result = prepared.run(record);
                if (!result.passed()) {
                    return Judged.failed(result, true);
                }
                // The held records are the ones a model would most like to mark as Unknown, and the sample
                // may not contain them, so marked Unknowns are refused here as well.
                if (!admitting && admitted(result.output())) {
                    return refusedAdmission();
                }
                final Verdict verdict = over.judge(Attempted.of(runner, record, result, boundary));
                if (Scorecard.outcome(verdict, step) != StepOutcome.PASSED) {
                    final List<StoredError> feedback = new ArrayList<>();
                    feedback.add(new StoredError(Severity.ERROR, null, new ElementId(runner.elementId()),
                            "On a record of the held stream that fell short (" + (i + 1) + " of " + held.size()
                            + "), as the parser cut it:\n" + record));
                    feedback.addAll(verdict.feedback());
                    return Judged.fellShort(verdict, step, feedback);
                }
            }
        }
        return null;
    }

    // Refuses a marked Unknown offered at any step but the exhaustion step.
    private static Judged refusedAdmission() {
        return Judged.refused("The admitted form — an Unknown marked " + LearnedUnknown.MARKER
                              + " — is the exhaustion step's to ask for: translate the record, or leave it "
                              + "for that step");
    }

    // Whether a transform's output contains a marked Unknown event.
    private static boolean admitted(final String output) {
        return OutputRecords.parse(output).map(Admission::admitsAny).orElse(false);
    }

    // Judges one element's result. First, a step that did not run cleanly has nothing to score. Then the
    // scorecard is applied, then the held records, then the targets: a parser must preserve what they
    // need, and a transform must reproduce them. A transform that cannot reproduce a target because its
    // input from a Data Splitter lacks the value is reported as the parser's shortfall, not its own, so that
    // the flow can send it back to the parser.
    private Judged judge(final FlowStep step,
                         final Scorecard over,
                         final Set<Check> checks,
                         final StepRunner runner,
                         final String configuration,
                         final String input,
                         final StepResult result,
                         final Walk walk,
                         final boolean last,
                         final boolean admitting) {
        if (!result.passed()) {
            return Judged.failed(result, runner.configured().isPresent());
        }
        final RecordBoundary boundary = walk.split == null
                ? null
                : walk.split.shared();
        // Only the exhaustion step may write marked Unknowns. Anywhere else they are refused, so that a model
        // cannot take the easy way out of a record it could translate.
        if (!admitting && !runner.parser() && admitted(result.output())) {
            return refusedAdmission();
        }
        final Verdict verdict = over.judge(
                Attempted.of(runner, input, result, boundary, configuration, walk.sample.facts()));
        if (Scorecard.outcome(verdict, step) != StepOutcome.PASSED) {
            return Judged.fellShort(verdict, step, verdict.feedback());
        }
        // A candidate for the chain's last element is also run over each record of the held stream that fell
        // short, as the pipeline would give it, and judged on it alone, so that a transform cannot pass by
        // handling the sample while ignoring the records the stream actually needs.
        if (last && !runner.parser() && configuration != null && !held.isEmpty()) {
            final Judged short_ = heldTo(step, over, runner, configuration, boundary, admitting);
            if (short_ != null) {
                return short_;
            }
        }
        if (!walk.targets.isEmpty()) {
            if (runner.parser() && checks.contains(Check.PRESERVATION) && scorecard.decides(Check.PRESERVATION, step)) {
                final List<StoredError> lacking = TargetChecks.preservation(result.output(), walk.targets);
                if (!lacking.isEmpty()) {
                    return new Judged(StepOutcome.PRESERVATION_SHORT, lacking, null);
                }
            } else if (last && checks.contains(Check.FIDELITY) && scorecard.decides(Check.FIDELITY, step)) {
                final List<StoredError> unlike = TargetChecks.fidelity(result.output(), walk.targets);
                if (!unlike.isEmpty()) {
                    final List<StoredError> lacking = walk.kind == InputKind.TEXT
                            ? TargetChecks.preservation(input, walk.targets)
                            : List.of();
                    return lacking.isEmpty()
                            ? new Judged(StepOutcome.FIDELITY_SHORT, unlike, null)
                            : new Judged(StepOutcome.PRESERVATION_SHORT, lacking, null);
                }
            }
        }
        return new Judged(StepOutcome.PASSED, List.of(), new LearnedStep(runner, configuration, result, verdict),
                Scorecard.deciding(verdict, step).orElse(null));
    }

    // Returns the records targets are proposed over where no split step has run: the non-blank lines of
    // raw text, the root's children for XML, or the root records of the parsed JSON.
    private static List<String> recordsOf(final Walk walk, final Map<String, StepRunner> runners) {
        final Sample sample = walk.sample;
        return switch (walk.kind) {
            case TEXT -> sample.text().lines().filter(line -> !line.isBlank()).toList();
            case XML -> OutputRecords.parse(sample.text())
                    .map(parsed -> parsed.records().stream().map(Object::toString).toList())
                    .orElse(List.of(sample.text()));
            case JSON -> {
                final StepResult parsed = walk.first(runners).run(null, sample.text());
                yield parsed.passed()
                        ? OutputRecords.parse(parsed.output())
                        .map(TargetChecks::rootRecords)
                        .orElse(List.of(sample.text()))
                        : List.of(sample.text());
            }
        };
    }

    private static int candidates(final FlowStep step, final LearningFlowDoc policy) {
        return step.getCandidates() == null
                ? policy.getMaxAttempts()
                : step.getCandidates();
    }

    // Returns each record of a Data Splitter's output as text: the one data value the split was asked to
    // emit, or every data value of the record joined with spaces, where it emitted more.
    private static List<String> recordTexts(final String recordsDocument) {
        return OutputRecords.parse(recordsDocument)
                .map(parsed -> parsed.records().stream()
                        .map(record -> parsed.evaluate(record,
                                        "string-join(descendant::*[local-name() = 'data']/@value, ' ')")
                                .itemAt(0).getStringValue())
                        .filter(text -> !text.isBlank())
                        .toList())
                .orElse(List.of());
    }

    private static Abandoned abandoned(final StopReport report, final List<Exchange> transcript) {
        return new Abandoned(report, List.of(), List.copyOf(transcript), FlowExit.PARK);
    }

    // Counts the records and their kinds, for an element that is shown only one record. The configuration
    // written for it is run over every record of the stream, so the model is told what it is not being
    // shown rather than left to discover it.
    private static Question.Records carried(final List<String> perRecord) {
        if (perRecord.isEmpty()) {
            return Question.Records.UNKNOWN;
        }
        // Count over what this element will actually be given, which is also where the example records shown
        // beside the count come from. For an element after the first, that is what the one before it wrote,
        // not the stream's own records.
        return new Question.Records(perRecord.size(),
                TargetChecks.representatives(perRecord, perRecord.size()).size());
    }

    // Returns the XML the split question is settled over: the stream itself where the chain starts with a
    // transform, or the parser's output where a parser comes first. If the parser produces no output, the
    // stream itself is returned.
    private static String markup(final StepRunner first, final Walk walk) {
        if (!first.parser()) {
            return walk.sample.text();
        }
        final StepResult parsed = first.run(first.fixedConfiguration().orElse(null), walk.sample.text());
        return parsed.output() == null
                ? walk.sample.text()
                : parsed.output();
    }

    // Returns the index of the first element in the chain that is not a parser, which is the one the
    // fragment's SplitFilter is placed in front of.
    private int firstTransform(final Walk walk) {
        for (int i = 0; i < walk.chain.size(); i++) {
            final StepRunner runner = runners.get(walk.chain.get(i));
            if (runner != null && !runner.parser()) {
                return i;
            }
        }
        return walk.chain.size();
    }

    // Returns the depth of a settled boundary, where it has one.
    private static OptionalInt depth(final Boundary split) {
        return split.depth() == null
                ? OptionalInt.empty()
                : OptionalInt.of(split.depth());
    }

    // Returns the element depth at which one record sits, which is what the fragment's `SplitFilter` is set
    // to split at. It is read from the document the split was settled against rather than assumed, because
    // the items of an array under a key sit deeper than the children of a root. Null where the boundary
    // matches nothing in the document, which the fragment writer treats as the usual depth.
    private static Integer depthOf(final OutputRecords document, final RecordBoundary boundary) {
        final OptionalInt depth = document.depthOf(boundary);
        return depth.isPresent()
                ? depth.getAsInt()
                : null;
    }

    private String ask(final Walk walk, final FlowStep step, final int candidate, final Question question) {
        budget.check(clock.millis(), advisor.tokensUsed(), step, walk.transcript.size() + 1);
        heartbeat.run();
        final String reply;
        try {
            reply = advisor.ask(List.copyOf(walk.transcript), question, standing);
        } catch (final RecordedAdvisor.AwaitingAnswer awaiting) {
            // Only the conversation knows where the walk stopped: the advisor knows only the question, and a
            // turn recorded unanswered needs the step, the candidate and the number it would have.
            throw new RecordedAdvisor.AwaitingAnswer(question, step.effectiveId(), candidate,
                    walk.transcript.size() + 1);
        }
        // Ask the advisor which guidance the answer was given with: a turn replayed from the record was
        // asked with the guidance in force then, not with what is in force now.
        final Exchange exchange = new Exchange(question, reply, step.effectiveId(), candidate, null,
                advisor.carried(standing));
        walk.transcript.add(exchange);
        onTurn.accept(walk.transcript.size(), exchange);
        budget.check(clock.millis(), advisor.tokensUsed(), step, walk.transcript.size());
        return reply;
    }

    // Records how a candidate ended on every turn it took: the turns of this step and candidate that are
    // not yet judged.
    private void judge(final List<Exchange> transcript,
                       final FlowStep step,
                       final int candidate,
                       final StepOutcome outcome,
                       final TurnScore score) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            final Exchange turn = transcript.get(i);
            if (turn.outcome() != null || !step.effectiveId().equals(turn.step()) || turn.candidate() != candidate) {
                return;
            }
            transcript.set(i, turn.judged(outcome, score));
            onTurn.accept(i + 1, transcript.get(i));
        }
    }

    private static List<StoredError> refusal(final String message) {
        return List.of(new StoredError(Severity.FATAL_ERROR, null, CONVERSATION, message));
    }

    // Asks and judges one candidate of a step's unit of work.
    @FunctionalInterface
    private interface Unit {

        Judged attempt(int candidate, List<StoredError> feedback);
    }

    // How one candidate ended, with what to tell the model if it did not pass and what was learned if it
    // did. `score` is the score of the check that decided it, or null where no scorer did.
    private record Judged(StepOutcome outcome, List<StoredError> feedback, LearnedStep learned, TurnScore score) {

        Judged(final StepOutcome outcome, final List<StoredError> feedback, final LearnedStep learned) {
            this(outcome, feedback, learned, null);
        }

        static Judged passed() {
            return new Judged(StepOutcome.PASSED, List.of(), null);
        }

        // A shortfall the scorecard decided, with the score of the check that decided it.
        static Judged fellShort(final Verdict verdict, final FlowStep step, final List<StoredError> feedback) {
            return new Judged(Scorecard.outcome(verdict, step), feedback, null,
                    Scorecard.deciding(verdict, step).orElse(null));
        }

        // A pass the scorecard decided, with the score of the check that passed by the least.
        static Judged passed(final Verdict verdict, final FlowStep step) {
            return new Judged(StepOutcome.PASSED, List.of(), null, Scorecard.deciding(verdict, step).orElse(null));
        }

        static Judged refused(final String message) {
            return new Judged(StepOutcome.REFUSED, refusal(message), null);
        }

        // A step that did not run cleanly. It counts as a compile failure where the element takes a
        // configuration and produced no output; otherwise it is a run failure, and a run-only element
        // always fails this way, having nothing to compile.
        static Judged failed(final StepResult result, final boolean configured) {
            return new Judged(result.output() == null && configured
                    ? StepOutcome.COMPILE_FAILED
                    : StepOutcome.RUN_FAILED, result.diagnostics(), null);
        }
    }

    // Where a step's visit leaves the walk: at the next step, at the step a transition names, or abandoned.
    // For an abandonment, `exit` is where the run exits the flow (null while the step's default is still to
    // apply), `cause` is why it stopped, for its report ([StopReports#ofStep]), and `report` is a report the
    // step wrote itself, for a stop whose reason is about the stream rather than the step's answers. Each
    // is null for other visits.
    private record Visit(Kind kind, Transition transition, String reason, List<StoredError> feedback,
                         FlowExit exit, StopCause cause, StopReport report) {

        Visit(final Kind kind, final Transition transition, final String reason, final List<StoredError> feedback) {
            this(kind, transition, reason, feedback, null, null, null);
        }

        // Returns this abandonment with the step's default exit, where the flow named none; any other visit
        // is returned as it is.
        Visit withDefaultExit(final FlowExit fallback) {
            return kind == Kind.ABANDON && exit == null
                    ? new Visit(kind, transition, reason, feedback, fallback, cause, report)
                    : this;
        }

        static Visit next() {
            return new Visit(Kind.NEXT, null, null, List.of());
        }

        static Visit take(final Transition transition, final List<StoredError> feedback) {
            return new Visit(Kind.GOTO, transition, null, feedback);
        }

        static Visit abandon(final StopCause cause, final String reason, final List<StoredError> feedback) {
            return new Visit(Kind.ABANDON, null, reason, feedback, null, cause, null);
        }

        static Visit abandon(final StopCause cause,
                             final String reason,
                             final List<StoredError> feedback,
                             final FlowExit exit) {
            return new Visit(Kind.ABANDON, null, reason, feedback, exit, cause, null);
        }

        // An abandonment whose report the step wrote itself: a stop about the stream rather than about the
        // step's answers, which has its own wording and suggestions.
        static Visit abandonWith(final StopReport report, final FlowExit exit) {
            return new Visit(Kind.ABANDON, null, report.getError(), List.of(), exit, StopCause.STEP_FAILED,
                    report);
        }

        enum Kind {
            NEXT,
            GOTO,
            ABANDON
        }
    }

    // The state of one attempt as the flow is walked: what has been settled, learned and carried so far.
    private static final class Walk {

        private final LearningFlowDoc policy;
        private final Sample sample;
        private final List<StoredError> opening;
        private final List<Exchange> transcript = new ArrayList<>();
        private final Set<String> taken = new HashSet<>();
        private List<StoredError> carried = List.of();
        private List<String> chain;
        private InputKind kind;
        private Boundary split;
        private List<String> records;
        private List<Target> targets = List.of();
        private LearnedStep[] learned = new LearnedStep[0];
        private String[] previous = new String[0];

        private Walk(final LearningFlowDoc policy, final Sample sample, final List<StoredError> opening) {
            this.policy = policy;
            this.sample = sample;
            this.opening = opening;
        }

        // Records the chain, when it is settled or settled again. The split, the targets and what was learned
        // all depended on the old chain, so they are cleared. The first element decides what kind of input
        // this is: each parser declares what it consumes ([StepRunner#consumes()]), and a chain with no
        // parser is given XML records. The step guards use this kind.
        private void chosen(final List<String> chain, final Map<String, StepRunner> runners) {
            this.chain = List.copyOf(chain);
            final StepRunner first = runners.get(chain.get(0));
            // The first element knows what it takes: raw text for a Data Splitter, JSON for the JSON parser,
            // XML for an XML fragment parser, or for a chain that starts with a transform because the stream
            // is already records.
            kind = first.parser()
                    ? first.consumes()
                    : InputKind.XML;
            split = null;
            records = null;
            targets = List.of();
            learned = new LearnedStep[chain.size()];
            previous = new String[chain.size()];
        }

        private StepRunner first(final Map<String, StepRunner> runners) {
            return runners.get(chain.get(0));
        }

        private void cut(final Boundary split, final List<String> records) {
            this.split = split;
            this.records = records;
        }

        // Records an element as learned, or learned again. What the elements after it learned was based on
        // its old output, so it is cleared, and a flow that does not configure them again is caught at the
        // end.
        private void learn(final int index, final LearnedStep step) {
            learned[index] = step;
            if (step != null) {
                Arrays.fill(learned, index + 1, learned.length, null);
            }
        }

        // Returns the chain positions a CONFIGURE step's role names: the parser is the first element, where it
        // is a parser; the transform is every other element; no role means every element.
        private List<Integer> elements(final ConfigureRole role, final Map<String, StepRunner> runners) {
            final boolean parserFirst = runners.get(chain.get(0)).parser();
            final List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++) {
                final boolean parser = i == 0 && parserFirst;
                if (role == null || (role == ConfigureRole.PARSER) == parser) {
                    indices.add(i);
                }
            }
            return indices;
        }
    }

    // The session's budget: how many tokens it may spend over its life, and how long this run of it may take.
    // A resumed session gets the whole time budget again. `grantedTokens` and `grantedMs` are what a person
    // granted beyond the document's budgets.
    private record Budget(LearningFlowDoc policy,
                          long startedMs,
                          long tokensAtStart,
                          long grantedTokens,
                          long grantedMs) {

        // Throws BudgetExhausted if the run has taken too long or spent too many tokens. `step` and
        // `question` say where the run is, for the report.
        void check(final long nowMs, final long tokensNow, final FlowStep step, final int question) {
            final long elapsed = nowMs - startedMs;
            final long allowedMs = policy.getAttemptBudgetMs() + grantedMs;
            if (elapsed > allowedMs) {
                throw new BudgetExhausted(StopReports.outOfTime(step, question, allowedMs, elapsed));
            }
            final long tokens = tokensNow - tokensAtStart;
            // A token budget of zero means unlimited, as the user interface shows it and as a person setting
            // the field means it. Otherwise a document written by import, REST or MCP with `tokenBudget: 0`
            // would park at its first answer.
            if (policy.getTokenBudget() != null && policy.getTokenBudget() > 0L) {
                final long allowed = policy.getTokenBudget() + grantedTokens;
                if (tokens > allowed) {
                    throw new BudgetExhausted(StopReports.outOfTokens(step, question, allowed, tokens));
                }
            }
        }
    }

    // Thrown when a run's budget is spent, with the report of where.
    private static final class BudgetExhausted extends RuntimeException {

        private final transient StopReport report;

        private BudgetExhausted(final StopReport report) {
            super(report.getError());
            this.report = report;
        }

        StopReport report() {
            return report;
        }
    }
}
