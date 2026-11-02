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
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.StepOutcome;
import stroom.shapeshifter.shared.TurnScore;

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

/**
 * The document's scorer set applied to a step (design §8.4): every setting whose scorer has a signal on
 * the step is scored, judged against its threshold, and weighted into a total. A setting whose scorer is
 * not installed, or whose parameters its scorer refuses, is a configuration fault and is refused when
 * the scorecard is built, not silently skipped or failed stream by stream at scoring time.
 */
public final class Scorecard {

    private static final Set<ScorerType> MEANING = EnumSet.of(
            ScorerType.SCHEMA_CONFORMANCE, ScorerType.EXTRACTION_QUALITY, ScorerType.BUSINESS_RULES,
            ScorerType.EVENT_CLASSIFICATION);

    private final List<ScorerSetting> settings;
    private final Map<ScorerType, Scorer> scorers;

    public Scorecard(final List<ScorerSetting> settings, final List<Scorer> scorers) {
        this.settings = List.copyOf(settings);
        this.scorers = scorers.stream().collect(Collectors.toUnmodifiableMap(Scorer::type, Function.identity()));
        for (final ScorerSetting setting : settings) {
            if (!this.scorers.containsKey(setting.getType())) {
                throw new IllegalArgumentException("No scorer is installed for " + setting.getType());
            }
            this.scorers.get(setting.getType()).validate(setting.getParameters());
        }
    }

    /**
     * This scorecard with only the scorers of meaning — those that judge what the output says, not how
     * much of the input it took or how many records it made — for judging one event on its own.
     */
    public Scorecard meaning() {
        return new Scorecard(settings.stream().filter(setting -> MEANING.contains(setting.getType())).toList(),
                List.copyOf(scorers.values()));
    }

    /// This scorecard with only the scorers a flow step names as its checks (A37), and the compile gate,
    /// which is not a check but the floor every step stands on.
    public Scorecard only(final Collection<Check> checks) {
        return new Scorecard(settings.stream()
                .filter(setting -> setting.getType() == ScorerType.COMPILE
                                   || checks.contains(Check.of(setting.getType())))
                .toList(), List.copyOf(scorers.values()));
    }

    /// The outcome of a verdict (A37): passed, or the shortfall that decided it — a failed gate before any
    /// other scorer that did not meet its threshold, in the document's order within each; the compile
    /// gate's as {@code COMPILE_FAILED}.
    public static StepOutcome outcome(final Verdict verdict) {
        final Judgement decisive = shortfall(verdict).orElse(null);
        if (decisive == null) {
            return StepOutcome.PASSED;
        }
        final Check check = Check.of(decisive.setting().getType());
        return check == null
                ? StepOutcome.COMPILE_FAILED
                : check.shortfall();
    }

    /// The score of the check that decided a verdict (design 06 A144): on a shortfall the one [#outcome]
    /// names; on a pass the gate that passed by the least, or where no gate applied, the check that did.
    /// The compile gate is the floor every step stands on rather than a check, and its outcome says all
    /// its score would, so it decides no score.
    ///
    /// @param verdict The verdict.
    /// @return The deciding check's score, or empty where no check decided it.
    public static Optional<TurnScore> deciding(final Verdict verdict) {
        final Optional<Judgement> shortfall = shortfall(verdict);
        final Optional<Judgement> decided;
        if (shortfall.isPresent()) {
            decided = shortfall.filter(Scorecard::isCheck);
        } else {
            final Comparator<Judgement> byMargin = Comparator.comparingDouble(
                    judgement -> judgement.score().value() - judgement.setting().getThreshold());
            final List<Judgement> checks = verdict.judgements().stream().filter(Scorecard::isCheck).toList();
            decided = checks.stream()
                    .filter(judgement -> judgement.setting().isGate())
                    .min(byMargin)
                    .or(() -> checks.stream().min(byMargin));
        }
        return decided.map(judgement -> new TurnScore(judgement.setting().getType(), judgement.score().value(),
                judgement.setting().getThreshold()));
    }

    /// The judgement that decided a shortfall: a failed gate before any other scorer that did not meet its
    /// threshold, in the document's order within each.
    private static Optional<Judgement> shortfall(final Verdict verdict) {
        return verdict.judgements().stream()
                .filter(Judgement::failedGate)
                .findFirst()
                .or(() -> verdict.judgements().stream().filter(judgement -> !judgement.metThreshold()).findFirst());
    }

    private static boolean isCheck(final Judgement judgement) {
        return Check.of(judgement.setting().getType()) != null;
    }

    public Verdict judge(final Attempted step) {
        final List<Judgement> judgements = new ArrayList<>();
        double weightedSum = 0.0;
        double weights = 0.0;
        for (final ScorerSetting setting : settings) {
            final Optional<Score> score = scorers.get(setting.getType()).score(setting.getParameters(), step);
            if (score.isPresent()) {
                judgements.add(new Judgement(setting, score.get()));
                weightedSum += setting.getWeight() * score.get().value();
                weights += setting.getWeight();
            }
        }
        return new Verdict(List.copyOf(judgements), weights == 0.0
                ? 1.0
                : weightedSum / weights);
    }
}
