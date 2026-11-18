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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// What the Setup tab's route map says under each section ([SetupSection]): a line of what the section
/// is set to, e.g. "Standard · 4 checks", and what is wrong in it, if anything. What is wrong is what the
/// Feeds tab's readiness check says ([Readiness]), shown against the section its fix is made in.
public final class SetupSummary {

    private final Map<SetupSection, String> summaries;
    private final Map<SetupSection, String> problems;

    private SetupSummary(final Map<SetupSection, String> summaries, final Map<SetupSection, String> problems) {
        this.summaries = summaries;
        this.problems = problems;
    }

    /// Sums up a document's setup, section by section.
    ///
    /// @param doc The document, as the Setup tab has it now.
    /// @return What each section says.
    public static SetupSummary of(final ShapeshifterAiDoc doc) {
        final Map<SetupSection, String> summaries = new EnumMap<>(SetupSection.class);
        summaries.put(SetupSection.LEARNING, learning(doc));
        summaries.put(SetupSection.APPROACH, approach(doc.getFlow().getSteps()));
        summaries.put(SetupSection.QUALITY, quality(doc.getScorers()));
        summaries.put(SetupSection.GOING_LIVE, "Pass mark " + ScoreValues.toPercent(doc.getPromotionFloor()) + "% · "
                                               + (doc.getPromotionMode() == PromotionMode.REVIEW
                ? "approved first"
                : "used once it passes"));
        summaries.put(SetupSection.IN_SERVICE, "Flagged below " + ScoreValues.toPercent(doc.getRelearnThreshold())
                                               + "%");

        final Map<SetupSection, String> problems = new EnumMap<>(SetupSection.class);
        for (final Readiness.Item item : Readiness.of(doc).getItems()) {
            if (!item.isOk() && item.getFix() != null) {
                problems.putIfAbsent(item.getFix().getSection(), item.getText());
            }
        }
        return new SetupSummary(summaries, problems);
    }

    /// @param section A section.
    /// @return What it is set to, e.g. "On · GPT-4o".
    public String summary(final SetupSection section) {
        return summaries.get(section);
    }

    /// @param section A section.
    /// @return What is wrong in it, or null for nothing.
    public String problem(final SetupSection section) {
        return problems.get(section);
    }

    private static String learning(final ShapeshifterAiDoc doc) {
        final String mode = doc.getLearningMode() == LearningMode.DISABLED
                ? "Off"
                : "On";
        final String model = doc.getModel() == null
                ? "no model"
                : Objects.requireNonNullElse(doc.getModel().getName(), "a model");
        return mode + " · " + model;
    }

    private static String approach(final List<FlowStep> steps) {
        final FlowExample example = ApproachFlow.example(steps);
        final long cards = steps.stream().filter(step -> step.getKind() != QuestionKind.ADMIT).count();
        return (example == null
                ? "Its own approach"
                : PlainWords.example(example)) + " · " + PlainWords.count(cards, "step");
    }

    private static String quality(final List<ScorerSetting> scorers) {
        if (scorers == null || scorers.isEmpty()) {
            return "No checks";
        }
        final QualityPreset preset = QualityPreset.of(scorers);
        return (preset == null
                ? "Own checks"
                : preset.getDisplayValue()) + " · " + PlainWords.count(scorers.size(), "check");
    }
}
