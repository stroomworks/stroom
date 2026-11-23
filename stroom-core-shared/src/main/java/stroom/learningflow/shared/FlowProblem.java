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

package stroom.learningflow.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// One thing wrong with a learning flow (A88): what, and which step it is about where it is about one,
/// so that an editor can paint the step rather than list the message beneath the whole.
///
/// A problem stops the flow being held; a warning is only said, so that a flow the user is still working on
/// can be saved.
///
/// Only [LearningFlow#check(List)] raises warnings, and only the Setup tab calls it, so the warnings are
/// the editor's. [LearningFlow#problems()], which the store refuses a save with, asks [LearningFlow#check()],
/// which raises none; it filters on severity so that it still refuses only problems if a warning ever
/// reaches it.
public class FlowProblem {

    /// The index of the step the problem is about, or null where it is about the flow as a whole.
    private final Integer step;
    private final String message;
    private final Severity severity;

    /// Makes a problem, which stops the flow being held.
    ///
    /// @param step    The index of the step it is about, or null for the flow as a whole.
    /// @param message What is wrong.
    public FlowProblem(final Integer step, final String message) {
        this(step, message, Severity.PROBLEM);
    }

    /// Makes a problem or a warning.
    ///
    /// @param step     The index of the step it is about, or null for the flow as a whole.
    /// @param message  What is wrong.
    /// @param severity Whether it stops the flow being held.
    public FlowProblem(final Integer step, final String message, final Severity severity) {
        this.step = step;
        this.message = Objects.requireNonNull(message);
        this.severity = Objects.requireNonNull(severity);
    }

    /// @return The index of the step this is about, or null where it is about the whole flow.
    public Integer getStep() {
        return step;
    }

    public String getMessage() {
        return message;
    }

    /// @return Whether this stops the flow being held, or is only said.
    public Severity getSeverity() {
        return severity;
    }

    /// @return Whether this stops the flow being held.
    public boolean isProblem() {
        return severity == Severity.PROBLEM;
    }

    /// The messages of one severity alone, in order. A warning doesn't stop a flow being held, so what the
    /// store refuses a save with is the problems'.
    ///
    /// @param problems The problems.
    /// @param severity The severity wanted.
    /// @return Their messages.
    public static List<String> messages(final List<FlowProblem> problems, final Severity severity) {
        final List<String> messages = new ArrayList<>();
        for (final FlowProblem problem : problems) {
            if (problem.severity == severity) {
                messages.add(problem.message);
            }
        }
        return messages;
    }

    @Override
    public String toString() {
        return message;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FlowProblem that = (FlowProblem) o;
        return Objects.equals(step, that.step) && message.equals(that.message) && severity == that.severity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, message, severity);
    }


    // --------------------------------------------------------------------------------


    /// Whether something wrong with a flow stops it being held, or is only said.
    public enum Severity {
        /// The flow cannot be held until it is put right.
        PROBLEM,
        /// The flow can be held; the user is told so that they can put it right.
        WARNING
    }
}
