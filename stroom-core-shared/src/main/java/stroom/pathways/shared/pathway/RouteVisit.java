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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * One node a trace reached, and the steps it took there.
 *
 * <p>Both are positions rather than names. {@code node} indexes the node list on {@link Routes},
 * and {@code steps} indexes that node's {@code stepsUse}. Both lists are only ever appended to, so
 * a position names the same thing for the life of the pathway.
 */
@JsonInclude(Include.NON_NULL)
public class RouteVisit {

    @JsonProperty
    private final int node;
    @JsonProperty
    private final int steps;

    @JsonCreator
    public RouteVisit(@JsonProperty("node") final int node,
                      @JsonProperty("steps") final int steps) {
        this.node = node;
        this.steps = steps;
    }

    public int getNode() {
        return node;
    }

    public int getSteps() {
        return steps;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RouteVisit that = (RouteVisit) o;
        return node == that.node && steps == that.steps;
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, steps);
    }

    @Override
    public String toString() {
        return node + ":" + steps;
    }
}
