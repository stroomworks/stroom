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

package stroom.learningflow.client.component;

import stroom.learningflow.shared.BuiltInFlow;
import stroom.learningflow.shared.CheckPreset;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowStep;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.QuestionKind;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Works out the text the Setup tab's route map shows under each section ([SetupSection]): a one-line summary
/// of the section's settings, e.g. "Standard · 7 gates", and the problem with it, if there is one.
///
/// The problems are those found by the same readiness checks ([Readiness]) that the Pipelines tab
/// (`FeedsTab`) shows. Each is shown against the section where it can be fixed.
public final class SetupSummary {

    private final Map<SetupSection, String> summaries;
    private final Map<SetupSection, String> problems;

    private SetupSummary(final Map<SetupSection, String> summaries, final Map<SetupSection, String> problems) {
        this.summaries = summaries;
        this.problems = problems;
    }

    /// Summarises a document's setup, section by section.
    ///
    /// @param doc      The document, including any unsaved changes made on the Setup tab.
    /// @param examples The built-in learning flows, as fetched from the server.
    /// @return The summary and any problem for each section.
    public static SetupSummary of(final LearningFlowDoc doc, final List<BuiltInFlow> examples) {
        final Map<SetupSection, String> summaries = new EnumMap<>(SetupSection.class);
        summaries.put(SetupSection.LEARNING, learning(doc));
        summaries.put(SetupSection.APPROACH, approach(examples, doc.getFlow().getSteps()));
        summaries.put(SetupSection.QUALITY, quality(doc.getChecks()));
        summaries.put(SetupSection.GOING_LIVE, doc.getPromotionMode() == PromotionMode.REVIEW
                ? "Approved first"
                : "Used once it passes");
        summaries.put(SetupSection.IN_SERVICE, "Flagged below " + PlainWords.percent(doc.getRelearnThreshold()));

        final Map<SetupSection, String> problems = new EnumMap<>(SetupSection.class);
        for (final Readiness.Item item : Readiness.of(doc, examples).getItems()) {
            if (!item.isOk() && item.getFix() != null) {
                problems.putIfAbsent(item.getFix().getSection(), item.getText());
            }
        }
        return new SetupSummary(summaries, problems);
    }

    /// @param section A section.
    /// @return A summary of the section's settings, e.g. "On · GPT-4o".
    public String summary(final SetupSection section) {
        return summaries.get(section);
    }

    /// @param section A section.
    /// @return The first problem found in the section, or null if there is none.
    public String problem(final SetupSection section) {
        return problems.get(section);
    }

    private static String learning(final LearningFlowDoc doc) {
        final String mode = doc.getLearningMode() == LearningMode.DISABLED
                ? "Off"
                : "On";
        final String model = doc.getModel() == null
                ? "no model"
                : Objects.requireNonNullElse(doc.getModel().getName(), "a model");
        return mode + " · " + model;
    }

    private static String approach(final List<BuiltInFlow> examples, final List<FlowStep> steps) {
        final FlowExample example = ApproachFlow.example(examples, steps);
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
