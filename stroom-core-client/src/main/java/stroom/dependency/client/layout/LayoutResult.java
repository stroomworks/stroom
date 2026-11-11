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

package stroom.dependency.client.layout;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// What [GraphLayout#layout] produces: every instance with its position, and the edges between them.
public final class LayoutResult {

    private final List<Instance> instances;
    private final List<LayoutEdge> edges;
    private final Map<Integer, LaneBox> laneBoxes;
    private final List<Integer> lanes;
    private final List<Ghost> ghosts;
    private final List<String> backEdges;
    private final DetailLevel detail;
    private final Map<String, Boolean> pins;
    private final Set<String> expanded;
    private final LayoutStats stats;

    LayoutResult(final List<Instance> instances,
                 final List<LayoutEdge> edges,
                 final Map<Integer, LaneBox> laneBoxes,
                 final List<Integer> lanes,
                 final List<Ghost> ghosts,
                 final List<String> backEdges,
                 final DetailLevel detail,
                 final Map<String, Boolean> pins,
                 final Set<String> expanded,
                 final LayoutStats stats) {
        this.instances = Collections.unmodifiableList(instances);
        this.edges = Collections.unmodifiableList(edges);
        this.laneBoxes = Collections.unmodifiableMap(laneBoxes);
        this.lanes = Collections.unmodifiableList(lanes);
        this.ghosts = Collections.unmodifiableList(ghosts);
        this.backEdges = Collections.unmodifiableList(backEdges);
        this.detail = detail;
        this.pins = Collections.unmodifiableMap(pins);
        this.expanded = Collections.unmodifiableSet(expanded);
        this.stats = stats;
    }

    /// @return every instance laid out, in drawing order.
    public List<Instance> getInstances() {
        return instances;
    }

    /// @return the edges between instances.
    public List<LayoutEdge> getEdges() {
        return edges;
    }

    /// @return each lane's background extent, by lane number.
    public Map<Integer, LaneBox> getLaneBoxes() {
        return laneBoxes;
    }

    /// @return the lane numbers in use, in ascending order.
    public List<Integer> getLanes() {
        return lanes;
    }

    /// @return the create slots for an empty folder; empty unless there is nothing to draw.
    public List<Ghost> getGhosts() {
        return ghosts;
    }

    /// @return the flow edges removed to break cycles, each as `from>to` node ids.
    public List<String> getBackEdges() {
        return backEdges;
    }

    /// @return the detail level the graph was laid out at.
    public DetailLevel getDetail() {
        return detail;
    }

    /// @return the pins the graph was laid out with.
    public Map<String, Boolean> getPins() {
        return pins;
    }

    /// @return the ids of the pipelines drawn expanded.
    public Set<String> getExpanded() {
        return expanded;
    }

    /// @return counts describing the layout.
    public LayoutStats getStats() {
        return stats;
    }

    /// @param instance An instance from this layout.
    /// @return the size it is drawn at.
    public Dims dims(final Instance instance) {
        return GraphLayout.dims(instance, expanded);
    }
}
