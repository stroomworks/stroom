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

package stroom.shapeshifter.ai.learning;

import stroom.shapeshifter.ai.extraction.PerRecord;
import stroom.shapeshifter.ai.extraction.RecordSplit;
import stroom.shapeshifter.ai.learning.Outcome.Abandoned;
import stroom.shapeshifter.ai.learning.Outcome.Learned;
import stroom.shapeshifter.ai.learning.Question.Chain;
import stroom.shapeshifter.ai.learning.Question.Configuration;
import stroom.shapeshifter.ai.learning.Question.Split;
import stroom.shapeshifter.ai.learning.Question.TargetFor;
import stroom.shapeshifter.ai.scoring.Admission;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.OutputRecords;
import stroom.shapeshifter.ai.scoring.Scorecard;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.stage.Guidance.Given;
import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.ConfigureRole;
import stroom.shapeshifter.shared.FlowExit;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearnedUnknown;
import stroom.shapeshifter.shared.LearningFlow;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.StepGuard;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.Transition;
import stroom.shapeshifter.shared.TurnScore;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

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

/**
 * One attempt, following the document's learning flow (A21, A31, A33, A37; design 01 §10.2). The flow is
 * a graph of typed questions: settle the chain; where the flow asks, settle what one record is
 * (<i>Split</i>) and propose what each kind of record should become (<i>Target</i>), validated at once;
 * and ask for each element's configuration, running the chain as far as it has been built so that the
 * next question carries real input, the parser held to preserving what the targets need and the
 * transform to reproducing them.
 * <p>
 * This class holds the question kinds with their reply grammars and judges, and the outcomes judging can
 * end in; the flow says which questions are asked, in what order, judged by which checks, and where to go
 * on each outcome. A step re-asks itself with the feedback of what fell short until its candidates are
 * spent; a transition the step declares on an outcome leaves at once, and one on {@code spent} says where
 * to go when the candidates are gone, abandoning unless told. A pass goes to the next line unless the step
 * says {@code on passed goto}; {@code end} names the end of the flow. Each transition is taken at most once
 * per attempt, so every flow ends. The interpreter knows nothing of which flow it is walking.
 * <p>
 * The chain is chosen from the document's allowed elements that this conversation can actually run: offering
 * the model an element there is no runner for would be a trap. A document that allows nothing runnable,
 * or whose flow cannot be held, is abandoned before the model is asked anything, naming what is wrong.
 */
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
    /// When this run's time budget began to run, where it is not this conversation's start: a round that
    /// follows another in one run is under the one budget (A5, design 06 A145).
    private Long begunAtMs;
    /// What a person granted the attempt beyond the document's budgets (A77).
    private long grantedTokens;
    private long grantedMs;
    /// Whether a person sent the attempt to the exhaustion step (A65): the next transform question that
    /// would be put to the model is not put, and the step's spent transition is taken instead.
    private boolean exhaustFirst;
    private Supplier<List<Given>> guidance = List::of;
    /// What [#guidance] answered for this walk, read when the walk begins.
    private List<Given> standing = List.of();
    /// The records of the held stream that fell short under the transform in force (design 04 §4), as the
    /// parser cut them: what every candidate for the chain's last element is held to, beyond the sample.
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

    /**
     * @param heartbeat Run before every question, for a caller holding something that expires while the
     *                  conversation runs — the attempt's claim on its learner (A45), which one slow model call
     *                  would otherwise outlive.
     */
    public Conversation(final Advisor advisor,
                    final List<StepRunner> runners,
                    final Scorecard scorecard,
                    final Clock clock,
                    final Runnable heartbeat) {
        this(advisor, runners, scorecard, clock, heartbeat, (number, exchange) -> {
        });
    }

    /**
     * @param onTurn Given every turn as it is answered and again as it is judged, by its number in the
     *               transcript: for a caller recording the attempt as it happens (A28), so that one still
     *               running — or one whose node died — shows what it had got to.
     */
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

    /// What this attempt spent before it was parked (A5, A45): a resumed attempt's token budget counts
    /// the whole of the attempt and not its last leg, or an attempt that stops and starts could spend its
    /// budget over again each time. The wall-clock half of the budget is this leg's, since an attempt may
    /// wait days for a person and the waiting is not the attempt taking too long.
    ///
    /// @return This conversation, to be run.
    public Conversation alreadySpent(final long tokens) {
        this.alreadySpent = tokens;
        return this;
    }

    /// Count this run's time budget from this moment rather than from the conversation's start (A5, design
    /// 06 A145): a round that follows another in one run is under the one budget.
    ///
    /// @return This conversation, to be run.
    public Conversation begunAt(final long ms) {
        this.begunAtMs = ms;
        return this;
    }

    /// What a person granted this attempt beyond the document's budgets (A77): added to them for this
    /// attempt alone.
    ///
    /// @return This conversation, to be run.
    /// Send this attempt to the exhaustion step (A65): the first transform question that would be put to
    /// the model — once the recorded turns, if any, have been replayed — is not put, and the step's spent
    /// transition is taken instead, as though its candidates were gone. A flow whose transform step has no
    /// spent transition abandons there, and the stage parks the session again with that reason.
    ///
    /// @return This conversation, to be run.
    public Conversation exhaustFirst(final boolean exhaust) {
        this.exhaustFirst = exhaust;
        return this;
    }

    public Conversation granted(final long tokens, final long ms) {
        this.grantedTokens = tokens;
        this.grantedMs = ms;
        return this;
    }

    /// What a supervisor has said about the learner being learned (A46), carried into every question.
    ///
    /// A supplier rather than a list, because guidance belongs to the learner and not to this object: an
    /// attempt parked awaiting the model and resumed a day later must carry what was said in between,
    /// and that is the whole reason a hint needs no timing. Each turn records which rows it carried, so
    /// a re-walk (A45) replays what was used rather than what has since been added.
    ///
    /// Read once per walk rather than once per question. A deferred attempt re-walks from the start on
    /// every pass of the worker, so a read per question would cost a select per turn per pass — and
    /// within one walk, which is seconds, a hint arriving between two questions is a distinction nobody
    /// can act on. The pass that follows reads it afresh.
    ///
    /// @return This conversation, to be run.
    /// Hold every candidate for the chain's last element to these records as well as to the sample: the
    /// records of the held stream that fell short under the transform in force (design 04 §4), each as
    /// the parser cut it. A session's second question is about them; without this its candidates would
    /// be judged on a sample they are not in.
    ///
    /// @param records Each record as one document of its own, as the transform is given it.
    public Conversation heldTo(final List<String> records) {
        held = List.copyOf(records);
        return this;
    }

    public Conversation guidedBy(final Supplier<List<Given>> guidance) {
        this.guidance = guidance;
        return this;
    }

    public Outcome run(final ShapeshifterAiDoc policy, final Sample sample) {
        return run(policy, sample, List.of());
    }

    /**
     * @param opening What the model is told before its first answer — a relearn's account of how the
     *                incumbent fell short (A29) — carried as the feedback of every question's first asking.
     */
    public Outcome run(final ShapeshifterAiDoc policy, final Sample sample, final List<StoredError> opening) {
        final Walk walk = new Walk(policy, sample, opening);
        // What a supervisor has said, read as this walk begins (A46). A deferred attempt is re-walked
        // from the start on every pass, so each pass reads it afresh and a hint given between passes is
        // carried by the one that follows.
        standing = List.copyOf(guidance.get());
        budget = new Budget(policy, begunAtMs != null
                ? begunAtMs
                : clock.millis(), advisor.tokensUsed() - alreadySpent, grantedTokens, grantedMs);
        try {
            return follow(walk);
        } catch (final BudgetExhausted e) {
            // A5: an attempt has a wall-clock and token budget for the whole conversation, whichever mode; one that
            // runs out is abandoned, not left to block a task or spend without end.
            return abandoned(e.getMessage(), List.of(), walk.transcript);
        }
    }

    private Outcome follow(final Walk walk) {
        final ShapeshifterAiDoc policy = walk.policy;
        final List<String> allowed = policy.getAllowedElements().stream()
                .filter(runners::containsKey)
                .toList();
        if (allowed.isEmpty()) {
            return abandoned("The document allows no element this conversation can run: "
                             + policy.getAllowedElements(), List.of(), walk.transcript);
        }
        final LearningFlow flow = policy.getFlow();
        final List<String> problems = new ArrayList<>(flow.problems());
        problems.addAll(Templates.problems(flow));
        if (!problems.isEmpty()) {
            return abandoned("The document's flow cannot be held (see its Learning tab): "
                             + String.join("; ", problems), List.of(), walk.transcript);
        }
        final List<FlowStep> steps = flow.getSteps();

        int at = 0;
        // Whether the step at `at` was reached by a transition rather than by the step before it ending:
        // the exhaustion step is entered only that way (design 04 §4), since it is reached when another
        // step's candidates are spent and never because the step above it passed.
        boolean jumped = false;
        while (at < steps.size()) {
            final FlowStep step = steps.get(at);
            final Visit visit = step.getKind() == QuestionKind.ADMIT && !jumped
                    ? Visit.next()
                    : enter(step, allowed, walk);
            jumped = false;
            switch (visit.kind()) {
                // What a transition carried is spent by the next question asked, so a step the guard
                // skips passes it on rather than dropping it.
                case NEXT -> at++;
                case ABANDON -> {
                    return new Abandoned(visit.reason(), List.copyOf(visit.feedback()), List.copyOf(walk.transcript),
                            visit.exit() == null
                                    ? FlowExit.PARK
                                    : visit.exit());
                }
                case GOTO -> {
                    final String edge = step.effectiveId() + " " + visit.transition().format();
                    if (!walk.taken.add(edge)) {
                        return abandoned("The flow would take '" + visit.transition().format() + "' from step '"
                                         + step.effectiveId() + "' a second time; a transition is taken once "
                                         + "per attempt", visit.feedback(), walk.transcript);
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
                return abandoned(walk.chain.get(i) + " was never configured; the flow ended without a CONFIGURE "
                                 + "step for it", List.of(), walk.transcript);
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
        // problems() has said every goto names a step.
        throw new IllegalStateException("No step named " + id);
    }

    /**
     * One step of the flow: asked and judged by its kind, under its guard and its checks.
     */
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
            case ADMIT -> configure(step, walk, true);
        };
    }

    /**
     * Asks a step's question for one unit of its work — the chain, the split, one kind of record, one
     * element — candidate by candidate until one passes, a transition fires, or the candidates are spent.
     *
     * @param unit  Asks and judges one candidate, given the feedback of the last.
     * @param spent The reason to abandon when the candidates are gone and the step says nothing else.
     */
    private Visit candidates(final FlowStep step, final Walk walk, final Unit unit, final String spent) {
        return candidates(step, walk, unit, spent, false);
    }

    /// @param exhaustible Whether a person's sending to the exhaustion step (A65) cuts these candidates
    ///                    short: before each one that would be put to the model — the record's turns
    ///                    replayed first — the step goes where it would go with its candidates spent.
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
                // Sent to the exhaustion step by a person (A65): this candidate is not asked for, and the
                // step goes where it would have gone with its candidates spent. Taken once.
                exhaustFirst = false;
                return spent(step, feedback, "Sent to the exhaustion step by a person, and step '"
                                             + step.effectiveId()
                                             + "' has no 'on spent' transition to take it there");
            }
            final Judged judged = unit.attempt(candidate, feedback);
            judge(walk.transcript, step, candidate, judged.outcome(), judged.score());
            if (judged.outcome() == StepOutcome.PASSED) {
                return Visit.next();
            }
            final Transition at = step.transitionOn(judged.outcome());
            if (at != null) {
                return at.exits()
                        ? Visit.abandon("Step '" + step.effectiveId() + "' leaves the flow on "
                                        + judged.outcome().getDisplayValue() + ", as the flow says: " + at.format(),
                                judged.feedback(), at.namedExit())
                        : Visit.take(at, judged.feedback());
            }
            feedback = judged.feedback();
        }
        return spent(step, feedback, spent);
    }

    /// Where a step goes with its candidates spent: the transition on spent, else the attempt is
    /// abandoned with the reason.
    private static Visit spent(final FlowStep step, final List<StoredError> feedback, final String reason) {
        final Transition when = step.transitionOnSpent();
        if (when != null && !when.exits()) {
            return Visit.take(when, feedback);
        }
        return Visit.abandon(reason, feedback, when == null
                ? null
                : when.namedExit());
    }

    /**
     * Where a step goes when a run-only element — one asked nothing, so with one candidate, its run — has
     * failed: the transition on its outcome, else the one on spent, since its candidates are gone; else the
     * attempt is abandoned with the reason.
     */
    private static Visit runOnly(final FlowStep step, final Judged judged, final String reason) {
        final Transition at = step.transitionOn(judged.outcome());
        if (at != null && !at.exits()) {
            return Visit.take(at, judged.feedback());
        }
        final Transition when = step.transitionOnSpent();
        if (at == null && when != null && !when.exits()) {
            return Visit.take(when, judged.feedback());
        }
        // The exit the step names on this outcome, else on spent, else its default.
        final Transition exiting = at != null
                ? at
                : when;
        return Visit.abandon(reason, judged.feedback(), exiting == null
                ? null
                : exiting.namedExit());
    }

    /**
     * Where a step goes once every unit of its work has passed: the next line, unless it says
     * {@code on passed}. A step of several units — a CONFIGURE over the whole chain, a TARGET over several
     * kinds — passes as a whole, not at its first unit.
     */
    private static Visit passed(final FlowStep step, final Visit last) {
        if (last.kind() != Visit.Kind.NEXT) {
            return last;
        }
        final Transition at = step.transitionOn(StepOutcome.PASSED);
        if (at == null) {
            return Visit.next();
        }
        return at.exits()
                ? Visit.abandon("The flow leaves on a pass of step '" + step.effectiveId() + "': " + at.format(),
                        List.of(), at.namedExit())
                : Visit.take(at, List.of());
    }

    /**
     * With one element there is nothing to choose, so the question is not asked (A21).
     */
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
        }, "No usable chain after " + candidates(step, walk.policy) + " candidates"));
    }

    /**
     * What one record is. For raw text the parser's runner is asked for a configuration that cuts and
     * emits each record whole, judged by the checks the step names — coverage and yield, since nothing
     * about meaning is in play yet, and whether the records emitted carry the input whole, which coverage
     * alone cannot tell. For an input that is already XML — the first element is not a parser — the model
     * is asked which element is one record (A35), judged by whether those elements exist, are not the
     * root, and together hold the document whole.
     */
    private Visit split(final FlowStep step, final Walk walk) {
        final StepRunner first = walk.first(runners);
        final Set<Check> checks = checks(step, SPLIT_CHECKS);
        final String spent = "No record boundary after " + candidates(step, walk.policy) + " candidates";
        if (walk.kind == InputKind.JSON) {
            return passed(step, splitJson(step, walk, first, checks, spent));
        }
        if (walk.kind == InputKind.XML) {
            // Where a parser stands in front, the split is settled over what it writes rather than over
            // the stream: a run of markup fragments is not a document until the parser has wrapped them
            // (§12 item 26), and the elements to choose between are in the wrapped document.
            final Optional<OutputRecords> document = OutputRecords.parse(markup(first, walk));
            if (document.isEmpty()) {
                walk.cut(null, List.of(walk.sample.text()));
                return passed(step, Visit.next());
            }
            return passed(step, candidates(step, walk, (candidate, feedback) -> {
                final String reply = ask(walk, step, candidate,
                        new Split(walk.sample, first.elementType(), null, InputKind.XML, feedback))
                        .strip().replaceAll("^<|>$", "");
                if (!reply.matches("[A-Za-z_][\\w.-]*")) {
                    return Judged.refused("The reply was not an element name");
                }
                final List<String> records = TargetChecks.elementsNamed(document.get(), reply);
                if (records.isEmpty()) {
                    return Judged.refused("No element named " + reply + " occurs in the input");
                }
                if (checks.contains(Check.WHOLENESS)) {
                    final Optional<StoredError> shortfall = TargetChecks.recordElement(document.get(), reply);
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
            if (!verdict.passed()) {
                return Judged.fellShort(verdict, verdict.feedback());
            }
            final List<String> records = recordTexts(result.output());
            if (records.isEmpty()) {
                return Judged.refused("The split produced no records; each record should be emitted whole, as one "
                                      + "data value");
            }
            if (checks.contains(Check.WHOLENESS)) {
                final Optional<StoredError> partial = TargetChecks.wholeness(walk.sample.text(), records);
                if (partial.isPresent()) {
                    return new Judged(StepOutcome.WHOLENESS_SHORT, List.of(partial.get()), null);
                }
            }
            walk.cut(Boundary.ofConfiguration(configuration.get()), records);
            return Judged.passed(verdict);
        }, spent));
    }

    /**
     * The split question for JSON (A31): the parser turns the sample into XML of the XSL/json vocabulary, and
     * the model names the array whose items are the records by its key, or {@code root} where each top-level
     * value is one — judged by whether such an array occurs and whether its items hold the document whole.
     */
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
            return runOnly(step, Judged.failed(parsed, false),
                    first.elementType() + " cannot read the sample as JSON");
        }
        return candidates(step, walk, (candidate, feedback) -> {
            final String reply = ask(walk, step, candidate,
                    new Split(walk.sample, first.elementType(), null, InputKind.JSON, feedback)).strip()
                    .replaceAll("^[\"']|[\"']$", "");
            // A key is a name, not an XPath fragment: what the model sends goes into a predicate, so
            // anything that could close one is refused here rather than thrown by Saxon later.
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
            if (checks.contains(Check.WHOLENESS)) {
                final Optional<StoredError> partial = TargetChecks.wholeness(document.get().root().getStringValue(),
                        records.stream().map(TargetChecks::textOf).toList());
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

    /**
     * What one kind of record should become: proposed by the model, judged at once by the scorers of
     * meaning over the wrapped event, re-asked with the shortfall of the document it wrote; or
     * {@code none}, for a kind that yields no event. A header or a comment is seen once in a sample; a kind
     * seen more than once is what the feed exists to report, so its first {@code none} is questioned and
     * only its second taken (design 02 §6.3). Questioning a none is not a candidate spent.
     */
    private Visit target(final FlowStep step, final Walk walk) {
        final List<String> over = walk.records == null
                ? recordsOf(walk, runners)
                : walk.records;
        final int kinds = step.getKinds() == null
                ? FlowStep.DEFAULT_KINDS
                : step.getKinds();
        final Scorecard meaning = step.getChecks().isEmpty()
                ? scorecard.meaning()
                : scorecard.meaning().only(step.getChecks());
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
                final String document = TargetChecks.asDocument(event.get());
                final Optional<StoredError> refusal = TargetChecks.refusal(document);
                if (refusal.isPresent()) {
                    return new Judged(StepOutcome.REFUSED, List.of(refusal.get()), null);
                }
                final Verdict verdict = TargetChecks.verdict(meaning, record, document);
                if (!verdict.passed()) {
                    return Judged.fellShort(verdict, verdict.feedback());
                }
                proposed.add(Target.of(record, event.get()));
                return Judged.passed(verdict);
            }, "No acceptable target for record kind " + kind + " after " + candidates(step, walk.policy)
                + " candidates");
            if (visit.kind() != Visit.Kind.NEXT) {
                return visit;
            }
        }
        walk.targets = List.copyOf(proposed);
        return passed(step, Visit.next());
    }

    /**
     * Each element the step's role names, in chain order: asked for its configuration where it takes one,
     * run over the real output of the element before it, and judged by the step's checks — the scorecard,
     * and against the targets (A31): a parser must preserve what they need, the chain's last element must
     * reproduce them. A role the chain does not have is skipped.
     */
    private Visit configure(final FlowStep step, final Walk walk, final boolean admitting) {
        final Set<Check> checks = checks(step, EnumSet.allOf(Check.class));
        final Scorecard over = scorecard.only(checks);
        // The exhaustion step (design 04 §4) is the transform's configuration asked for once more, with
        // the admitted form in it: the transform alone, whatever the flow wrote for its role.
        final ConfigureRole role = admitting
                ? ConfigureRole.TRANSFORM
                : step.getRole();
        for (final int index : walk.elements(role, runners)) {
            final StepRunner runner = runners.get(walk.chain.get(index));
            if (index > 0 && walk.learned[index - 1] == null) {
                return Visit.abandon(runner.elementType() + " cannot be configured before "
                                     + walk.chain.get(index - 1) + " has been", List.of());
            }
            final String input = index == 0
                    ? walk.sample.text()
                    : walk.learned[index - 1].result().output();
            final boolean last = index == walk.chain.size() - 1;
            // What the fragment's SplitFilter will give this element (§12 item 25): from the first
            // element that is not a parser, one record at a time. It is shown one record because that is
            // what it will be given — a person writing a Stroom stylesheet is shown one — and it is run
            // over every record because that is how it will run.
            // The first element after the parser is given the parser's records; every element after that
            // is given what the one before it wrote for each record, which is one record deep. A fragment
            // carries one SplitFilter, not one per element.
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
            // A47: one record of each kind, where the flow has not settled its targets yet. Where it has,
            // the targets show a record of each kind beside the event it must become, and showing them
            // here as well is a budget (A44) spent saying the same thing twice.
            final List<String> otherKinds = oneAtATime && walk.targets.isEmpty()
                    ? TargetChecks.representatives(perRecord).stream().skip(1).toList()
                    : List.of();
            final Optional<StepRunner.Configured> configured = runner.configured();
            final Optional<String> fixed = runner.fixedConfiguration();
            final Visit visit;
            if (configured.isPresent() && fixed.isPresent()) {
                // An element whose configuration is its own business (§12 item 26): run with it, judge
                // it, learn it so that the fragment is written with the document a pipeline needs, and
                // spend no candidate asking for something a model has no say in.
                final StepResult ran = oneAtATime
                        ? PerRecord.run(runner, fixed.get(), input, perRecord)
                        : runner.run(fixed.get(), input);
                final Judged judged = judge(over, checks, runner, fixed.get(), input, ran, walk, last, admitting);
                walk.learn(index, judged.learned());
                visit = judged.outcome() == StepOutcome.PASSED
                        ? Visit.next()
                        : runOnly(step, judged, runner.elementType() + " failed on its input");
            } else if (configured.isEmpty()) {
                // Nothing is asked for a run-only element, so it is run once and judged; what a transition
                // carried here is left for the next question asked.
                // Per record too where the split has settled: a run-only element after the filter is
                // given one record at a time exactly as a configured one is, and the gate runs it so.
                final StepResult ran = oneAtATime
                        ? PerRecord.run(runner, null, input, perRecord)
                        : runner.run(null, input);
                final Judged judged = judge(over, checks, runner, null, input, ran, walk, last, admitting);
                walk.learn(index, judged.learned());
                visit = judged.outcome() == StepOutcome.PASSED
                        ? Visit.next()
                        : runOnly(step, judged, runner.elementType() + " failed on its input");
            } else {
                // A parser is held to the split's records; a transform over XML input is told the record element.
                final boolean carriesSplit = runner.parser()
                                             || (walk.split != null && walk.split.configuration() == null);
                final Boundary split = carriesSplit
                        ? walk.split
                        : null;
                // A transform's candidates are what a person's sending to the exhaustion step cuts short
                // (A65): a parser's are not, since the exhaustion step admits what is parsed and cannot be
                // translated, and the exhaustion step's own are not, since it is where the sending goes.
                final boolean exhaustible = !admitting && !runner.parser();
                final Visit asked = candidates(step, walk, (candidate, feedback) -> {
                    final String reply = ask(walk, step, candidate, new Configuration(runner.elementType(),
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
                    final Judged judged = judge(over, checks, runner, configuration.get(), input, result,
                            walk, last, admitting);
                    walk.learn(index, judged.learned());
                    return judged;
                }, (admitting
                        ? "No admission for "
                        : "No passing configuration for ") + runner.elementType() + " after "
                    + candidates(step, walk.policy) + " attempts", exhaustible);
                // Where the flow names no exit, the step's own default (A83): a parser whose candidates
                // are spent is the one failure nothing can serve (A52, A54), so its learner is given up;
                // any other step parks for a person (A65).
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
        // A step that leaves on its pass by `abandon` takes its own default exit too (A83): give-up where
        // the step is the parser's, park otherwise.
        return passed(step, Visit.next()).withDefaultExit(step.getRole() == ConfigureRole.PARSER
                ? FlowExit.GIVE_UP
                : FlowExit.PARK);
    }

    /// A candidate run over each record the held stream fell short on, judged one at a time: the outcome
    /// of the first that falls short, with the record named in the feedback, or null where all clear.
    private Judged heldTo(final Scorecard over,
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
                // The held records are the ones a model would most like to admit, and the sample does
                // not show them: the refusal of the admitted form (A53) is made here as well.
                if (!admitting && admitted(result.output())) {
                    return refusedAdmission();
                }
                final Verdict verdict = over.judge(Attempted.of(runner, record, result, boundary));
                if (!verdict.passed()) {
                    final List<StoredError> feedback = new ArrayList<>();
                    feedback.add(new StoredError(Severity.ERROR, null, new ElementId(runner.elementId()),
                            "On a record of the held stream that fell short (" + (i + 1) + " of " + held.size()
                            + "), as the parser cut it:\n" + record));
                    feedback.addAll(verdict.feedback());
                    return Judged.fellShort(verdict, feedback);
                }
            }
        }
        return null;
    }

    /// The admitted form offered at any step but the exhaustion step (A53).
    private static Judged refusedAdmission() {
        return Judged.refused("The admitted form — an Unknown marked " + LearnedUnknown.MARKER
                              + " — is the exhaustion step's to ask for: translate the record, or leave it "
                              + "for that step");
    }

    /// Whether a transform's output carries a learned admission (A62).
    private static boolean admitted(final String output) {
        return OutputRecords.parse(output).map(Admission::admitsAny).orElse(false);
    }

    /**
     * The gate of §8.1 first — a step that did not run has nothing to score — then the scorecard, then
     * the targets (A31): a parser must preserve what they need, a transform must reproduce them. A
     * transform that cannot reproduce a target because its input lacks the value is the parser's
     * shortfall, not its own, and is reported as such (§10.1 rule 6) for the flow to route.
     */
    private Judged judge(final Scorecard over,
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
        // Only the exhaustion step may ask for the admitted form (A53): offered anywhere else it is
        // refused, so that a model cannot take the short way out of a record it could translate.
        if (!admitting && !runner.parser() && admitted(result.output())) {
            return refusedAdmission();
        }
        final Verdict verdict = over.judge(Attempted.of(runner, input, result, boundary));
        if (!verdict.passed()) {
            return Judged.fellShort(verdict, verdict.feedback());
        }
        // Held to the records of the held stream that fell short (design 04 §4): a candidate for the
        // chain's last element is run over each as the pipeline would give it, and judged on it alone, so
        // that a transform the sample does not exercise cannot pass by ignoring what the stream wants.
        if (last && !runner.parser() && configuration != null && !held.isEmpty()) {
            final Judged short_ = heldTo(over, runner, configuration, boundary, admitting);
            if (short_ != null) {
                return short_;
            }
        }
        if (!walk.targets.isEmpty()) {
            if (runner.parser() && checks.contains(Check.PRESERVATION)) {
                final List<StoredError> lacking = TargetChecks.preservation(result.output(), walk.targets);
                if (!lacking.isEmpty()) {
                    return new Judged(StepOutcome.PRESERVATION_SHORT, lacking, null);
                }
            } else if (last && checks.contains(Check.FIDELITY)) {
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
                Scorecard.deciding(verdict).orElse(null));
    }

    /**
     * The records a target is proposed over where no split has been asked: the sample's top-level
     * children where it is already records, or once the parser has made it records, its non-blank lines
     * where it is raw text.
     */
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

    private static int candidates(final FlowStep step, final ShapeshifterAiDoc policy) {
        return step.getCandidates() == null
                ? policy.getMaxAttempts()
                : step.getCandidates();
    }

    /**
     * The checks that judge a step: those it names, or the kind's own.
     */
    private static Set<Check> checks(final FlowStep step, final Set<Check> defaults) {
        return step.getChecks().isEmpty()
                ? defaults
                : EnumSet.copyOf(step.getChecks());
    }

    /**
     * Each record as its text: the one data value the split was asked to emit, or every data value of the
     * record joined, where it emitted more.
     */
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

    private static Abandoned abandoned(final String reason,
                                       final List<StoredError> diagnostics,
                                       final List<Exchange> transcript) {
        return new Abandoned(reason, List.copyOf(diagnostics), List.copyOf(transcript));
    }

    /// How many records the stream holds and how many kinds they are of, for an element that is shown
    /// one of them (§12 item 25): one record is one record's worth of evidence, and the configuration
    /// written for it is run over every record of the stream, so what it is not being shown is said
    /// rather than left for it to discover.
    private static Question.Records carried(final List<String> perRecord) {
        if (perRecord.isEmpty()) {
            return Question.Records.UNKNOWN;
        }
        // Counted over what this element will actually be given, which is what the representatives shown
        // beside the count are drawn from: for an element after the first, that is what the one before
        // it wrote, and not the stream's own records.
        return new Question.Records(perRecord.size(),
                TargetChecks.representatives(perRecord, perRecord.size()).size());
    }

    /// The markup the split question is settled over: the stream itself where the chain starts with a
    /// transform, and what the parser makes of the stream where one stands in front of it. A parser that
    /// cannot read the stream leaves the question with nothing, which the caller reads as no split.
    private static String markup(final StepRunner first, final Walk walk) {
        if (!first.parser()) {
            return walk.sample.text();
        }
        final StepResult parsed = first.run(first.fixedConfiguration().orElse(null), walk.sample.text());
        return parsed.output() == null
                ? walk.sample.text()
                : parsed.output();
    }

    /// Where the first element that is not a parser sits in the chain: the one the fragment's filter
    /// stands in front of.
    private int firstTransform(final Walk walk) {
        for (int i = 0; i < walk.chain.size(); i++) {
            final StepRunner runner = runners.get(walk.chain.get(i));
            if (runner != null && !runner.parser()) {
                return i;
            }
        }
        return walk.chain.size();
    }

    /// The depth a settled boundary carries, where it has one.
    private static OptionalInt depth(final Boundary split) {
        return split.depth() == null
                ? OptionalInt.empty()
                : OptionalInt.of(split.depth());
    }

    /// How deep in the document the transform will receive one record sits (§12 item 25): what the
    /// fragment's `SplitFilter` is set to split at, read from the document the split was settled against
    /// rather than assumed, since the items of an array under a key sit deeper than the children of a
    /// root. Null where the boundary names nothing here, which the writer reads as the usual depth.
    private static Integer depthOf(final OutputRecords document, final RecordBoundary boundary) {
        final OptionalInt depth = document.depthOf(boundary);
        return depth.isPresent()
                ? depth.getAsInt()
                : null;
    }

    private String ask(final Walk walk, final FlowStep step, final int candidate, final Question question) {
        budget.check(clock.millis(), advisor.tokensUsed());
        heartbeat.run();
        final String reply;
        try {
            reply = advisor.ask(List.copyOf(walk.transcript), question, standing);
        } catch (final RecordedAdvisor.AwaitingAnswer awaiting) {
            // Where the walk stopped is the conversation's to say: the advisor knows only the question, and a
            // turn recorded unanswered (A28) needs the step, the candidate and the number it would be.
            throw new RecordedAdvisor.AwaitingAnswer(question, step.effectiveId(), candidate,
                    walk.transcript.size() + 1);
        }
        // What it was actually asked with, which is the advisor's to say: a turn replayed from the
        // record was asked under what stood then, not under what stands now (A45).
        final Exchange exchange = new Exchange(question, reply, step.effectiveId(), candidate, null,
                advisor.carried(standing));
        walk.transcript.add(exchange);
        onTurn.accept(walk.transcript.size(), exchange);
        budget.check(clock.millis(), advisor.tokensUsed());
        return reply;
    }

    /**
     * Records how a candidate ended on every turn it took (A28): the turns of this step and candidate
     * not yet judged.
     */
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

    /**
     * One candidate of a step's unit of work, asked and judged.
     */
    @FunctionalInterface
    private interface Unit {

        Judged attempt(int candidate, List<StoredError> feedback);
    }

    /**
     * How one candidate ended, with what to tell the model if it did not pass and what was learned if it did.
     */
    /// @param score The score of the check that decided it (design 06 A144), or null where no scorer did.
    private record Judged(StepOutcome outcome, List<StoredError> feedback, LearnedStep learned, TurnScore score) {

        Judged(final StepOutcome outcome, final List<StoredError> feedback, final LearnedStep learned) {
            this(outcome, feedback, learned, null);
        }

        static Judged passed() {
            return new Judged(StepOutcome.PASSED, List.of(), null);
        }

        /// A shortfall the scorecard decided, with the check that decided it (A144).
        static Judged fellShort(final Verdict verdict, final List<StoredError> feedback) {
            return new Judged(Scorecard.outcome(verdict), feedback, null, Scorecard.deciding(verdict).orElse(null));
        }

        /// A pass the scorecard decided, with the check that passed by the least.
        static Judged passed(final Verdict verdict) {
            return new Judged(StepOutcome.PASSED, List.of(), null, Scorecard.deciding(verdict).orElse(null));
        }

        static Judged refused(final String message) {
            return new Judged(StepOutcome.REFUSED, refusal(message), null);
        }

        /**
         * A step that did not pass the gate of §8.1: a configuration that did not compile where the element
         * takes one and left no output; a run that failed otherwise — a run-only element has nothing to
         * compile.
         */
        static Judged failed(final StepResult result, final boolean configured) {
            return new Judged(result.output() == null && configured
                    ? StepOutcome.COMPILE_FAILED
                    : StepOutcome.RUN_FAILED, result.diagnostics(), null);
        }
    }

    /**
     * Where a step's visit leaves the walk: at the next step, at the step a transition names, or abandoned.
     */
    /// @param parse Whether an abandonment is the parser's candidates spent (A54).
    /// @param exit Where an abandonment leaves to (A83), or null where the step's default is still to apply.
    private record Visit(Kind kind, Transition transition, String reason, List<StoredError> feedback,
                         FlowExit exit) {

        Visit(final Kind kind, final Transition transition, final String reason, final List<StoredError> feedback) {
            this(kind, transition, reason, feedback, null);
        }

        /// This abandonment with the step's default exit, where the flow named none; any other visit as
        /// it is.
        Visit withDefaultExit(final FlowExit fallback) {
            return kind == Kind.ABANDON && exit == null
                    ? new Visit(kind, transition, reason, feedback, fallback)
                    : this;
        }

        static Visit next() {
            return new Visit(Kind.NEXT, null, null, List.of());
        }

        static Visit take(final Transition transition, final List<StoredError> feedback) {
            return new Visit(Kind.GOTO, transition, null, feedback);
        }

        static Visit abandon(final String reason, final List<StoredError> feedback) {
            return new Visit(Kind.ABANDON, null, reason, feedback);
        }

        static Visit abandon(final String reason, final List<StoredError> feedback, final FlowExit exit) {
            return new Visit(Kind.ABANDON, null, reason, feedback, exit);
        }

        enum Kind {
            NEXT,
            GOTO,
            ABANDON
        }
    }

    /**
     * The state of one attempt as the flow is walked: what has been settled, learned and carried so far.
     */
    private static final class Walk {

        private final ShapeshifterAiDoc policy;
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

        private Walk(final ShapeshifterAiDoc policy, final Sample sample, final List<StoredError> opening) {
            this.policy = policy;
            this.sample = sample;
            this.opening = opening;
        }

        /**
         * The chain settled, or settled again: what was cut, aimed at and learned was over the old chain
         * and is forgotten with it. The first element says what the input is — each parser declares what
         * it consumes ({@link StepRunner#consumes()}) and a chain with no parser is given records — and
         * the guards read accordingly.
         */
        private void chosen(final List<String> chain, final Map<String, StepRunner> runners) {
            this.chain = List.copyOf(chain);
            final StepRunner first = runners.get(chain.get(0));
            // What the first element is given says what the input is, and the element is what knows:
            // raw text for a splitter, JSON for the JSON parser, markup for a fragment parser or for a
            // chain that starts with a transform because the stream is already records (§12 item 26).
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

        /**
         * An element learned, or learned again: what the elements after it produced was over its old output
         * and is forgotten, so that a flow which stops re-configuring them is caught at the end.
         */
        private void learn(final int index, final LearnedStep step) {
            learned[index] = step;
            if (step != null) {
                Arrays.fill(learned, index + 1, learned.length, null);
            }
        }

        /**
         * The chain positions a CONFIGURE step's role names: the parser is the first element where it parses;
         * the transform is every element after it; no role is every element.
         */
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

    /// The session's budget (A5): how many tokens it may spend over its life, and how long this run of it
    /// may take (design 06 A145) — a session carried on again has the whole time budget again.
    ///
    /// @param grantedTokens What a person granted beyond the document's token budget (A77).
    /// @param grantedMs     Likewise beyond its time budget.
    private record Budget(ShapeshifterAiDoc policy,
                          long startedMs,
                          long tokensAtStart,
                          long grantedTokens,
                          long grantedMs) {

        void check(final long nowMs, final long tokensNow) {
            final long elapsed = nowMs - startedMs;
            final long allowedMs = policy.getAttemptBudgetMs() + grantedMs;
            if (elapsed > allowedMs) {
                throw new BudgetExhausted("This run's time budget of " + allowedMs
                                          + " ms is spent after " + elapsed + " ms; each run of the session "
                                          + "has the whole of it");
            }
            final long tokens = tokensNow - tokensAtStart;
            if (policy.getTokenBudget() != null) {
                final long allowed = policy.getTokenBudget() + grantedTokens;
                if (tokens > allowed) {
                    throw new BudgetExhausted("The session's budget of " + allowed
                                              + " tokens is spent after " + tokens + " tokens");
                }
            }
        }
    }

    private static final class BudgetExhausted extends RuntimeException {

        private BudgetExhausted(final String message) {
            super(message);
        }
    }
}
