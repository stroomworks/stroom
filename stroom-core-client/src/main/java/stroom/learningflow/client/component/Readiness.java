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

/// Whether a Learning Flow document will learn anything, worked out from the document alone (the
/// "document checks" of design 07): learning is switched on, a model is chosen, there is at least one
/// quality check (a document with none passes every answer, ISSUES item 22), and the approach has no
/// problems. Each check that fails names the fix the Feeds tab offers for it.
///
/// Whether pipelines use the document is not asked here: it needs other documents, and a pipeline built by
/// hand is as good as one a factory made.
public final class Readiness {

    private final List<Item> items;

    private Readiness(final List<Item> items) {
        this.items = Collections.unmodifiableList(items);
    }

    /// Checks a document.
    ///
    /// @param doc      The document, as its tabs have it now.
    /// @param examples The built-in setups, as fetched (Track 2 step 1c): what the approach is named
    ///                 against. Empty where they are not in hand, which reads as the document's own.
    /// @return What is right and what is wrong with it, in the order the Feeds tab lists them.
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
        // Compile and permitted are always gates, and neither says anything about the quality of what
        // was learned: compile only says the answer runs, and permitted only that it uses what it may
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

    /// @return Every check, right or wrong.
    public List<Item> getItems() {
        return items;
    }

    /// @return Whether every check is right.
    public boolean isReady() {
        return items.stream().allMatch(Item::isOk);
    }


    // --------------------------------------------------------------------------------


    /// What the Feeds tab offers to put a check right, and the Setup tab's section it is made in.
    public enum Fix {
        /// Switches learning on.
        SWITCH_ON("Switch on", SetupSection.LEARNING),
        /// Shows where the model is chosen.
        CHOOSE_MODEL("Choose a model", SetupSection.LEARNING),
        /// Puts the Standard quality checks in place.
        USE_STANDARD_CHECKS("Use the Standard checks", SetupSection.QUALITY),
        /// Shows the approach, where the problem is.
        OPEN_APPROACH("Show the approach", SetupSection.APPROACH);

        private final String label;
        private final SetupSection section;

        Fix(final String label, final SetupSection section) {
            this.label = label;
            this.section = section;
        }

        /// @return What its button says.
        public String getLabel() {
            return label;
        }

        /// @return The Setup tab's section the fix is made in.
        public SetupSection getSection() {
            return section;
        }
    }


    // --------------------------------------------------------------------------------


    /// One check.
    public static final class Item {

        private final boolean ok;
        private final String text;
        private final Fix fix;

        Item(final boolean ok, final String text, final Fix fix) {
            this.ok = ok;
            this.text = text;
            this.fix = fix;
        }

        /// @return Whether it is right.
        public boolean isOk() {
            return ok;
        }

        /// @return What it says.
        public String getText() {
            return text;
        }

        /// @return What puts it right, or null where it is right.
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
