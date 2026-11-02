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

package stroom.shapeshifter.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// One thing wrong with a learning flow (A88): what, and which step it is about where it is about one,
/// so that an editor can paint the step rather than list the message beneath the whole.
public class FlowProblem {

    /// The index of the step the problem is about, or null where it is about the flow as a whole.
    private final Integer step;
    private final String message;

    public FlowProblem(final Integer step, final String message) {
        this.step = step;
        this.message = Objects.requireNonNull(message);
    }

    /// @return The index of the step this is about, or null where it is about the whole flow.
    public Integer getStep() {
        return step;
    }

    public String getMessage() {
        return message;
    }

    /// The messages alone, in order: what the store says when it refuses a save.
    public static List<String> messages(final List<FlowProblem> problems) {
        final List<String> messages = new ArrayList<>(problems.size());
        for (final FlowProblem problem : problems) {
            messages.add(problem.message);
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
        return Objects.equals(step, that.step) && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(step, message);
    }
}
