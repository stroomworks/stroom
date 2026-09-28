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

package stroom.pathways.shared.pathway;

import stroom.pathways.shared.otel.trace.NanoTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * How often a node ran one particular set of steps, and when it last did.
 *
 * <p>A node's steps are the children it ran, named in the sequence they ran in. Two sets of steps
 * differ where the children differ as well as where the sequence does, so a run that pings first is
 * a different set of steps from one that does not, rather than the same ones rearranged.
 *
 * <p>Which steps are allowed is the node's childSteps constraint. This says which of them traces
 * actually take, so a reader can tell steps the model relies on from steps it saw once and kept. It
 * is held on the node rather than in the constraint because it moves on every trace, and a
 * constraint that moved on every trace would make every trace look like a change to the model.
 *
 * <p>A node holds these in the sequence they were first seen, and the list only ever grows, so a
 * position in it names the same steps for the life of the pathway.
 */
@JsonInclude(Include.NON_NULL)
public class StepsUse {

    /**
     * What separates one child's name from the next in a set of steps. Both the writing of a set and
     * the reading back of which children ran depend on it, and they are in different modules.
     */
    public static final String SEPARATOR = " > ";

    @JsonProperty
    private final String steps;
    @JsonProperty
    private final long timesUsed;
    @JsonProperty
    private final NanoTime lastUsedTime;

    @JsonCreator
    public StepsUse(@JsonProperty("steps") final String steps,
                    @JsonProperty("timesUsed") final long timesUsed,
                    @JsonProperty("lastUsedTime") final NanoTime lastUsedTime) {
        this.steps = steps;
        this.timesUsed = timesUsed;
        this.lastUsedTime = lastUsedTime;
    }

    public String getSteps() {
        return steps;
    }

    public long getTimesUsed() {
        return timesUsed;
    }

    public NanoTime getLastUsedTime() {
        return lastUsedTime;
    }

    /**
     * The same steps, taken once more at the given time.
     */
    public StepsUse used(final NanoTime time) {
        return new StepsUse(steps, timesUsed + 1, time);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final StepsUse that = (StepsUse) o;
        return timesUsed == that.timesUsed
               && Objects.equals(steps, that.steps)
               && Objects.equals(lastUsedTime, that.lastUsedTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(steps, timesUsed, lastUsedTime);
    }

    @Override
    public String toString() {
        return "StepsUse{" + steps + " x" + timesUsed + ", last " + lastUsedTime + '}';
    }
}
