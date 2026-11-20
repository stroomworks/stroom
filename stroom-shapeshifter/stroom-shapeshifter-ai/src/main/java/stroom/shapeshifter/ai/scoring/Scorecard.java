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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.shared.Check;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.CheckSettings;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.TurnScore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A Learning Flow's checks applied to a step (the one model of checks, UM-2 and UM-3): every check that
 * isn't off and whose scorer has a signal on the step is scored and judged against its threshold. A check
 * that is a gate refuses a candidate below its threshold; one that is advice does so only where the step
 * has a rule for its shortfall (D1). A check whose scorer is not installed, or whose parameters its scorer
 * refuses, is a configuration fault and is refused when the scorecard is built, not silently skipped or
 * failed stream by stream at scoring time. The checks with no scorer (wholeness, preservation, fidelity,
 * the regression corpus) are judged by the conversation and by promotion, which ask this scorecard whether
 * each is on and whether it decides ([#decides]).
 */
public final class Scorecard {

    private static final Set<Check> MEANING = EnumSet.of(
            Check.CONFORMANCE, Check.QUALITY, Check.RULES);

    private final List<CheckSetting> settings;
    private final Map<Check, Scorer> scorers;
    private final TargetSchema target;

    private Scorecard(final List<CheckSetting> settings,
                      final List<Scorer> scorers,
                      final TargetSchema target,
                      final boolean validate) {
        this.target = Objects.requireNonNull(target, "target");
        this.settings = settings.stream().filter(CheckSetting::isOn).toList();
        this.scorers = scorers.stream().collect(Collectors.toUnmodifiableMap(Scorer::type, Function.identity()));
        if (validate) {
            for (final CheckSetting setting : this.settings) {
                final Check check = setting.getCheck();
                if (check.isScored()) {
                    if (!this.scorers.containsKey(check)) {
                        throw new IllegalArgumentException("No scorer is installed for " + check);
                    }
                    this.scorers.get(check).validate(setting.getParameters());
                }
            }
        }
    }

    /// A scorecard judging against the default target schema, event-logging v3.0.0: for a test or a harness
    /// that has no Learning Flow's target to hand.
    ///
    /// @param settings A Learning Flow's checks; those that are off are not run.
    /// @param scorers  The installed scorers.
    /// @return The scorecard.
    public static Scorecard of(final List<CheckSetting> settings, final List<Scorer> scorers) {
        return of(settings, scorers, TargetSchema.DEFAULT);
    }

    /// @param settings A Learning Flow's checks; those that are off are not run.
    /// @param scorers  The installed scorers.
    /// @param target   The schema the Learning Flow's translations must conform to (D1).
    /// @return The scorecard.
    public static Scorecard of(final List<CheckSetting> settings,
                               final List<Scorer> scorers,
                               final TargetSchema target) {
        return new Scorecard(settings, scorers, target, true);
    }

    /**
     * This scorecard with only the checks of meaning — those that judge what the output says, not how
     * much of the input it took or how many records it made — for judging one event on its own.
     */
    public Scorecard meaning() {
        return narrowed(settings.stream().filter(setting -> MEANING.contains(setting.getCheck())).toList());
    }

    /// This scorecard with only the checks a flow step names (A37), and the compile gate, which every step
    /// stands on.
    public Scorecard only(final Collection<Check> checks) {
        return narrowed(settings.stream()
                .filter(setting -> setting.getCheck() == Check.COMPILE || checks.contains(setting.getCheck()))
                .toList());
    }

    private Scorecard narrowed(final List<CheckSetting> kept) {
        return new Scorecard(kept, List.copyOf(scorers.values()), target, false);
    }

    /// @param check A check.
    /// @return Its setting, where it is on.
    public Optional<CheckSetting> setting(final Check check) {
        return settings.stream().filter(setting -> setting.getCheck() == check).findFirst();
    }

    /// Whether a check that falls short on a step decides the step's outcome (D1): a gate always does; advice
    /// does only where the step has a rule for its shortfall; a check that is off, or not in this scorecard,
    /// never does.
    ///
    /// @param check A check.
    /// @param step  The step, or null for none, which has no rules.
    /// @return Whether its shortfall decides.
    public boolean decides(final Check check, final FlowStep step) {
        return setting(check).map(setting -> setting.isGate() || ruled(step, check)).orElse(false);
    }

    /// @param check A check with a threshold, e.g. wholeness.
    /// @return Its threshold here, or its default where it is not on.
    public double threshold(final Check check) {
        return setting(check).map(CheckSetting::getPassingValue)
                .orElse(check.getDefaultThreshold() == null
                        ? 1.0
                        : check.getDefaultThreshold());
    }

    /// The outcome of a verdict on a step that has no rules: passed, or the failed gate that decided it.
    public static StepOutcome outcome(final Verdict verdict) {
        return outcome(verdict, null);
    }

    /// The outcome of a verdict on a step (A37, D1): passed, or the shortfall that decided it — a failed gate
    /// before any advice that fell short and that the step has a rule for, in the order of the checks within
    /// each; the compile gate's as `COMPILE_FAILED`.
    ///
    /// @param verdict The verdict.
    /// @param step    The step judged, whose rules say which advice routes; null for one with no rules.
    /// @return The outcome.
    public static StepOutcome outcome(final Verdict verdict, final FlowStep step) {
        return shortfall(verdict, step)
                .map(judgement -> judgement.setting().getCheck().shortfall())
                .orElse(StepOutcome.PASSED);
    }

    /// The score of the check that decided a verdict (design 06 A144): on a shortfall the one [#outcome]
    /// names; on a pass the gate that passed by the least, or where no gate applied, the check that did.
    /// The compile gate is the floor every step stands on rather than a check, and its outcome says all
    /// its score would, so it decides no score.
    ///
    /// @param verdict The verdict.
    /// @param step    The step judged; null for one with no rules.
    /// @return The deciding check's score, or empty where no check decided it.
    public static Optional<TurnScore> deciding(final Verdict verdict, final FlowStep step) {
        final Optional<Judgement> shortfall = shortfall(verdict, step);
        final Optional<Judgement> decided;
        if (shortfall.isPresent()) {
            decided = shortfall.filter(Scorecard::isCheck);
        } else {
            final Comparator<Judgement> byMargin = Comparator.comparingDouble(
                    judgement -> judgement.score().value() - judgement.setting().getPassingValue());
            final List<Judgement> checks = verdict.judgements().stream().filter(Scorecard::isCheck).toList();
            decided = checks.stream()
                    .filter(judgement -> judgement.setting().isGate())
                    .min(byMargin)
                    .or(() -> checks.stream().min(byMargin));
        }
        return decided.map(judgement -> new TurnScore(judgement.setting().getCheck(),
                judgement.score().value(), judgement.setting().getPassingValue()));
    }

    /// @param check A check.
    /// @return Its name as people read it.
    static String name(final Check check) {
        return check.getName();
    }

    // The judgement that decided a shortfall: a failed gate before any advice the step routes on
    private static Optional<Judgement> shortfall(final Verdict verdict, final FlowStep step) {
        return verdict.judgements().stream()
                .filter(Judgement::failedGate)
                .findFirst()
                .or(() -> verdict.judgements().stream()
                        .filter(judgement -> !judgement.metThreshold()
                                             && ruled(step, judgement.setting().getCheck()))
                        .findFirst());
    }

    // Whether the step has a rule for the check's shortfall
    private static boolean ruled(final FlowStep step, final Check check) {
        final StepOutcome shortfall = check.shortfall();
        return step != null && shortfall != null && step.transitionOn(shortfall) != null;
    }

    private static boolean isCheck(final Judgement judgement) {
        return judgement.setting().getCheck() != Check.COMPILE;
    }

    /// @return The schema this scorecard judges conformance to, and a proposed target is wrapped for.
    public TargetSchema target() {
        return target;
    }

    public Verdict judge(final Attempted step) {
        final List<Judgement> judgements = new ArrayList<>();
        for (final CheckSetting setting : settings) {
            final Check check = setting.getCheck();
            if (check.isScored()) {
                scorers.get(check).score(setting.getParameters(), step, target)
                        .ifPresent(score -> judgements.add(new Judgement(setting, score)));
            }
        }
        return Verdict.of(judgements);
    }
}
