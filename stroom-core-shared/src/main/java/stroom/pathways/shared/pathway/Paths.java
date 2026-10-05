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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Every distinct path taken through a pathway, and the node list the paths index into.
 *
 * <p>{@code nodes} holds a node uuid per position and is only ever appended to, so a position names
 * the same node for the life of the pathway. Keeping the uuid here once rather than in every visit
 * is what makes a path a list of small integers.
 *
 * <p>{@code steps} holds every distinct shape a path takes at any point, each once, and is appended
 * to the same way. A subtree that many paths share is one entry however many reach it, so what a
 * path stores is the position of its outermost shape.
 */
@JsonInclude(Include.NON_NULL)
public class Paths {

    @JsonProperty
    private final List<String> nodes;
    @JsonProperty
    private final List<PathStep> steps;
    @JsonProperty
    private final List<PathUse> paths;

    @JsonCreator
    public Paths(@JsonProperty("nodes") final List<String> nodes,
                  @JsonProperty("steps") final List<PathStep> steps,
                  @JsonProperty("paths") final List<PathUse> paths) {
        this.nodes = nodes == null
                ? Collections.emptyList()
                : new ArrayList<>(nodes);
        this.steps = steps == null
                ? Collections.emptyList()
                : new ArrayList<>(steps);
        this.paths = paths == null
                ? Collections.emptyList()
                : new ArrayList<>(paths);
    }

    public static Paths empty() {
        return new Paths(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Node uuids by position. A visit's {@code node} indexes this.
     */
    public List<String> getNodes() {
        return nodes;
    }

    /**
     * Every distinct shape by position. A path's {@code root}, and every step within a shape, indexes
     * this.
     */
    public List<PathStep> getSteps() {
        return steps;
    }

    /**
     * The distinct paths, in the order they were first taken.
     */
    public List<PathUse> getPaths() {
        return paths;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Paths that = (Paths) o;
        return Objects.equals(nodes, that.nodes)
               && Objects.equals(steps, that.steps)
               && Objects.equals(paths, that.paths);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, steps, paths);
    }

    @Override
    public String toString() {
        return "Paths{" + paths.size() + " paths over " + nodes.size() + " nodes, "
               + steps.size() + " shapes}";
    }
}
