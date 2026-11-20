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

import stroom.shapeshifter.shared.CheckPreset;
import stroom.shapeshifter.shared.CheckSetting;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.FlowStep;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.PromotionMode;
import stroom.shapeshifter.shared.QuestionKind;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// What the Setup tab's route map says under each section ([SetupSection]): a line of what the section
/// is set to, e.g. "Standard · 7 gates", and what is wrong in it, if anything. What is wrong is what the
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
        summaries.put(SetupSection.QUALITY, quality(doc.getChecks()));
        summaries.put(SetupSection.GOING_LIVE, doc.getPromotionMode() == PromotionMode.REVIEW
                ? "Approved first"
                : "Used once it passes");
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
    /// @return What it is set to, e.g. "On · GPT-4o · event-logging v3.0.0".
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
        return mode + " · " + model + " · " + targetSchema(doc);
    }

    /// @param doc A document.
    /// @return What its translations are written to, e.g. "event-logging v4.1.0", or "no target schema".
    static String targetSchema(final ShapeshifterAiDoc doc) {
        return doc.getTargetSchema() == null
                ? "no target schema"
                : Objects.requireNonNullElse(doc.getTargetSchema().getName(), "a schema");
    }

    private static String approach(final List<FlowStep> steps) {
        final FlowExample example = ApproachFlow.example(steps);
        final long cards = steps.stream().filter(step -> step.getKind() != QuestionKind.ADMIT).count();
        return (example == null
                ? "Its own approach"
                : PlainWords.example(example)) + " · " + PlainWords.count(cards, "step");
    }

    private static String quality(final List<CheckSetting> checks) {
        int gates = 0;
        int advice = 0;
        for (final CheckSetting setting : checks) {
            if (setting.isGate()) {
                gates++;
            } else if (setting.isOn()) {
                advice++;
            }
        }
        final CheckPreset preset = CheckPreset.of(checks);
        return (preset == null
                ? "Own checks"
                : preset.getDisplayValue()) + " · " + PlainWords.count(gates, "gate") + (advice == 0
                ? ""
                : ", " + PlainWords.count(advice, "advice check"));
    }
}
