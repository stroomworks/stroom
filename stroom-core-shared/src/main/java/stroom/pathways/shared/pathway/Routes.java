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
 * Every distinct route taken through a pathway, and the node list the routes index into.
 *
 * <p>{@code nodes} holds a node uuid per position and is only ever appended to, so a position names
 * the same node for the life of the pathway. Keeping the uuid here once rather than in every visit
 * is what makes a route a list of small integers.
 *
 * <p>{@code steps} holds every distinct shape a route takes at any point, each once, and is appended
 * to the same way. A subtree that many routes share is one entry however many reach it, so what a
 * route stores is the position of its outermost shape.
 */
@JsonInclude(Include.NON_NULL)
public class Routes {

    @JsonProperty
    private final List<String> nodes;
    @JsonProperty
    private final List<RouteStep> steps;
    @JsonProperty
    private final List<RouteUse> routes;

    @JsonCreator
    public Routes(@JsonProperty("nodes") final List<String> nodes,
                  @JsonProperty("steps") final List<RouteStep> steps,
                  @JsonProperty("routes") final List<RouteUse> routes) {
        this.nodes = nodes == null
                ? Collections.emptyList()
                : new ArrayList<>(nodes);
        this.steps = steps == null
                ? Collections.emptyList()
                : new ArrayList<>(steps);
        this.routes = routes == null
                ? Collections.emptyList()
                : new ArrayList<>(routes);
    }

    public static Routes empty() {
        return new Routes(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Node uuids by position. A visit's {@code node} indexes this.
     */
    public List<String> getNodes() {
        return nodes;
    }

    /**
     * Every distinct shape by position. A route's {@code root}, and every step within a shape, indexes
     * this.
     */
    public List<RouteStep> getSteps() {
        return steps;
    }

    /**
     * The distinct routes, in the order they were first taken.
     */
    public List<RouteUse> getRoutes() {
        return routes;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Routes that = (Routes) o;
        return Objects.equals(nodes, that.nodes)
               && Objects.equals(steps, that.steps)
               && Objects.equals(routes, that.routes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, steps, routes);
    }

    @Override
    public String toString() {
        return "Routes{" + routes.size() + " routes over " + nodes.size() + " nodes, "
               + steps.size() + " shapes}";
    }
}
