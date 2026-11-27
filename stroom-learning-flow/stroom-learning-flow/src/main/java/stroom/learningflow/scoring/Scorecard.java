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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckSettings;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.StepOutcome;
import stroom.learningflow.shared.TurnScore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/// Applies a Learning Flow's checks to a step. Every check that is not off, and whose scorer has
/// something to measure on the step, is scored and judged against its threshold. A gate check refuses a
/// candidate below its threshold; an advice check does so only where the flow step has a transition for
/// its shortfall. A check whose scorer is not installed, or whose parameters its scorer rejects, is a
/// configuration fault and is refused when the scorecard is built, rather than silently skipped or failed
/// stream by stream at scoring time. The checks with no scorer (wholeness, preservation, fidelity, the
/// regression corpus) are judged by the learning conversation and by promotion, which ask this scorecard
/// whether each is on and whether it decides the outcome ([#decides]).
public final class Scorecard {

    private static final Set<Check> MEANING = EnumSet.of(
            Check.CONFORMANCE, Check.QUALITY, Check.RULES);

    private final List<CheckSetting> settings;
    private final Map<Check, Scorer> scorers;
    /// The schema the document's translations must conform to, or null where it names none.
    private final TargetSchema target;

    private Scorecard(final List<CheckSetting> settings,
                      final List<Scorer> scorers,
                      final TargetSchema target,
                      final boolean validate) {
        this.settings = settings.stream().filter(CheckSetting::isOn).toList();
        this.scorers = scorers.stream().collect(Collectors.toUnmodifiableMap(Scorer::type, Function.identity()));
        this.target = target;
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

    /// Builds a scorecard that judges against no particular schema.
    ///
    /// @param settings A Learning Flow's checks; those that are off are not run.
    /// @param scorers  The installed scorers.
    /// @return The scorecard.
    /// @throws IllegalArgumentException If a check that is on has no scorer or invalid parameters.
    public static Scorecard of(final List<CheckSetting> settings, final List<Scorer> scorers) {
        return of(settings, scorers, null);
    }

    /// Builds a scorecard.
    ///
    /// @param settings A Learning Flow's checks; those that are off are not run.
    /// @param scorers  The installed scorers.
    /// @param target   The schema the document's translations must conform to, or null where it names
    ///                 none.
    /// @return The scorecard.
    /// @throws IllegalArgumentException If a check that is on has no scorer or invalid parameters.
    public static Scorecard of(final List<CheckSetting> settings,
                               final List<Scorer> scorers,
                               final TargetSchema target) {
        return new Scorecard(settings, scorers, target, true);
    }

    /// This scorecard narrowed to the checks of meaning (those that judge what the output says, not how
    /// much of the input it consumed or how many records it produced), for judging one event on its own.
    ///
    /// @return The narrowed scorecard.
    public Scorecard meaning() {
        return narrowed(settings.stream().filter(setting -> MEANING.contains(setting.getCheck())).toList());
    }

    /// This scorecard narrowed to the checks a flow step names, plus the compile and permitted checks,
    /// which apply to every step.
    ///
    /// @param checks The checks the step names.
    /// @return The narrowed scorecard.
    public Scorecard only(final Collection<Check> checks) {
        return narrowed(settings.stream()
                .filter(setting -> setting.getCheck() == Check.COMPILE
                                   || setting.getCheck() == Check.PERMITTED
                                   || checks.contains(setting.getCheck()))
                .toList());
    }

    private Scorecard narrowed(final List<CheckSetting> kept) {
        return new Scorecard(kept, List.copyOf(scorers.values()), target, false);
    }

    /// @return The schema the document's translations must conform to, or null where it names none.
    public TargetSchema target() {
        return target;
    }

    /// @param check A check.
    /// @return Its setting, where it is on.
    public Optional<CheckSetting> setting(final Check check) {
        return settings.stream().filter(setting -> setting.getCheck() == check).findFirst();
    }

    /// Whether a check that falls short on a step decides the step's outcome. A gate always does; advice
    /// does only where the step has a transition for its shortfall; a check that is off, or not in this
    /// scorecard, never does.
    ///
    /// @param check A check.
    /// @param step  The step, or null for none, which has no transitions.
    /// @return Whether its shortfall decides the outcome.
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

    /// The outcome of a verdict on a step that has no transitions: passed, or the shortfall of the failed
    /// gate that decided it.
    ///
    /// @param verdict The verdict.
    /// @return The outcome.
    public static StepOutcome outcome(final Verdict verdict) {
        return outcome(verdict, null);
    }

    /// The outcome of a verdict on a step: passed, or the shortfall that decided it. A failed gate takes
    /// precedence over any advice that fell short and that the step has a transition for, and within each
    /// the first check in order wins. A compile failure gives `COMPILE_FAILED`.
    ///
    /// @param verdict The verdict.
    /// @param step    The step judged, whose transitions say which advice shortfalls decide the outcome;
    ///                null for one with no transitions.
    /// @return The outcome.
    public static StepOutcome outcome(final Verdict verdict, final FlowStep step) {
        return shortfall(verdict, step)
                .map(judgement -> judgement.setting().getCheck().shortfall())
                .orElse(StepOutcome.PASSED);
    }

    /// The score of the check that decided a verdict, which is what a turn is scored by. On a shortfall it
    /// is the check [#outcome] names; on a pass it is the gate that passed by the smallest margin or,
    /// where no gate applied, the check that did. The compile check is a basic requirement of every step
    /// rather than a measure, and its outcome already says everything its score would, so it is never the
    /// deciding check.
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
    /// @return Its display name.
    static String name(final Check check) {
        return check.getName();
    }

    // Finds the judgement that decided a shortfall: a failed gate, or failing that, advice the step has a
    // transition for.
    private static Optional<Judgement> shortfall(final Verdict verdict, final FlowStep step) {
        return verdict.judgements().stream()
                .filter(Judgement::failedGate)
                .findFirst()
                .or(() -> verdict.judgements().stream()
                        .filter(judgement -> !judgement.metThreshold()
                                             && ruled(step, judgement.setting().getCheck()))
                        .findFirst());
    }

    // Whether the step has a transition for the check's shortfall.
    private static boolean ruled(final FlowStep step, final Check check) {
        final StepOutcome shortfall = check.shortfall();
        return step != null && shortfall != null && step.transitionOn(shortfall) != null;
    }

    private static boolean isCheck(final Judgement judgement) {
        return judgement.setting().getCheck() != Check.COMPILE;
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
