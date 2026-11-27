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
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.FlowProblem;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// Works out whether a Learning Flow document is set up well enough to learn anything, from the document
/// alone. It is shown on the Pipelines tab (`FeedsTab`) and summarised on the Setup tab.
///
/// It checks that learning is switched on, that a model is chosen, that a target schema is chosen (D1: there is
/// none where no event-logging schema was installed when the document was made, and a run refuses to start
/// without one), that at least one quality check other than Compile and Permitted is a gate (without one, any
/// answer that runs would pass), and that the learning flow has no problems. Each failed check names the fix
/// the Pipelines tab offers for it.
///
/// It does not check whether any pipeline uses the document. That would need other documents to be read, and
/// a pipeline may legitimately be built by hand rather than by a Pipeline Factory.
public final class Readiness {

    private final List<Item> items;

    private Readiness(final List<Item> items) {
        this.items = Collections.unmodifiableList(items);
    }

    /// Checks a document.
    ///
    /// @param doc      The document, including any unsaved changes made on its tabs.
    /// @param examples The built-in learning flows, as fetched from the server, used to name the document's
    ///                 approach if it matches one. If empty (e.g. not fetched yet), the approach is described
    ///                 as the document's own.
    /// @return Every check, passed or failed, in the order the Pipelines tab lists them.
    public static Readiness of(final LearningFlowDoc doc, final List<BuiltInFlow> examples) {
        final List<Item> items = new ArrayList<>();
        if (doc.getLearningMode() == LearningMode.DISABLED) {
            items.add(new Item(false, "Learning is switched off, so streams of new feeds wait.", Fix.SWITCH_ON));
        } else {
            items.add(new Item(true, "Learning is on", null));
        }
        if (doc.getModel() == null) {
            items.add(new Item(false, "No model is chosen.", Fix.CHOOSE_MODEL));
        } else {
            items.add(new Item(true, "Model: " + Objects.requireNonNullElse(doc.getModel().getName(),
                    doc.getModel().getUuid()), null));
        }
        if (doc.getTargetSchema() == null) {
            items.add(new Item(false, "No target schema is chosen, so the AI has no schema to write to.",
                    Fix.CHOOSE_TARGET_SCHEMA));
        } else {
            items.add(new Item(true, "Target schema: " + Objects.requireNonNullElse(
                    doc.getTargetSchema().getName(), doc.getTargetSchema().getUuid()), null));
        }
        // Compile and Permitted are always gates, but neither says anything about the quality of the
        // output. Compile only says the answer runs, and Permitted only that it uses allowed functions.
        final long gates = doc.getChecks().stream()
                .filter(setting -> setting.isGate()
                                   && setting.getCheck() != Check.COMPILE
                                   && setting.getCheck() != Check.PERMITTED)
                .count();
        if (gates == 0) {
            items.add(new Item(false, "No quality check is a gate, so any answer that runs would pass.",
                    Fix.USE_STANDARD_CHECKS));
        } else {
            items.add(new Item(true, PlainWords.count(gates, "quality gate"), null));
        }
        final List<FlowProblem> problems = doc.getFlow().check();
        if (!problems.isEmpty()) {
            items.add(new Item(false, "The approach has a problem: " + problems.get(0).getMessage() + ".",
                    Fix.OPEN_APPROACH));
        } else {
            final FlowExample example = ApproachFlow.example(examples, doc.getFlow().getSteps());
            items.add(new Item(true, "Approach: " + (example == null
                    ? "your own"
                    : PlainWords.example(example)), null));
        }
        return new Readiness(items);
    }

    /// @return Every check, passed or failed.
    public List<Item> getItems() {
        return items;
    }

    /// @return Whether every check passed.
    public boolean isReady() {
        return items.stream().allMatch(Item::isOk);
    }


    // --------------------------------------------------------------------------------


    /// A fix the Pipelines tab offers for a failed check, and the Setup tab section where the fix is made.
    public enum Fix {
        /// Switches learning on.
        SWITCH_ON("Switch on", SetupSection.LEARNING),
        /// Shows the setting where the model is chosen.
        CHOOSE_MODEL("Choose a model", SetupSection.LEARNING),
        /// Shows the setting where the target schema is chosen.
        CHOOSE_TARGET_SCHEMA("Choose a target schema", SetupSection.LEARNING),
        /// Replaces the document's quality checks with the Standard preset.
        USE_STANDARD_CHECKS("Use the Standard checks", SetupSection.QUALITY),
        /// Shows the learning flow (approach), where the problem is.
        OPEN_APPROACH("Show the approach", SetupSection.APPROACH);

        private final String label;
        private final SetupSection section;

        Fix(final String label, final SetupSection section) {
            this.label = label;
            this.section = section;
        }

        /// @return The text of the fix's button.
        public String getLabel() {
            return label;
        }

        /// @return The Setup tab section where the fix is made.
        public SetupSection getSection() {
            return section;
        }
    }


    // --------------------------------------------------------------------------------


    /// The result of one readiness check.
    public static final class Item {

        private final boolean ok;
        private final String text;
        private final Fix fix;

        Item(final boolean ok, final String text, final Fix fix) {
            this.ok = ok;
            this.text = text;
            this.fix = fix;
        }

        /// @return Whether the check passed.
        public boolean isOk() {
            return ok;
        }

        /// @return The text describing the result, for the user.
        public String getText() {
            return text;
        }

        /// @return The fix for a failed check, or null if the check passed.
        public Fix getFix() {
            return fix;
        }

        @Override
        public String toString() {
            return (ok
                    ? "ok: "
                    : "not: ") + text;
        }
    }
}
