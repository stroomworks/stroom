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

import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.EdgeCertainty;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/// Turns a [DependencyGraph] into positions.
///
/// The server sends a graph, not a drawing: no coordinates, no lanes and no duplicated reference copies.
/// All three are decided here.
///
/// This is a line-by-line translation of `layout()` in the reference harness
/// (`docs/references/folder-data-flow/stroom-structure-layout-harness.html`), and is tested to give
/// identical positions for every fixture scenario. Its output depends on the order of its inputs, so every
/// map and set keeps insertion order, as JavaScript's do. Improve it only once a change is proven against
/// those tests.
///
/// The stages are:
///
/// 1. Break cycles, so layering terminates on a reprocessing loop.
/// 2. Use inheritance as a layering edge, but only where flow says nothing, so a pipeline library reads
///    parent to child.
/// 3. Columns, by longest path from a source.
/// 4. Lanes: one per source, branches fork, and convergences settle to the mean of their inputs.
/// 5. Referenced documents, one copy per lane that reaches them.
/// 6. Collapse, per lane and per reference row, with pins overriding the detail level.
/// 7. Positions, stacking each lane by the rows it actually contains.
/// 8. Edges, mapped onto instances and through any collapse.
public final class GraphLayout {

    // How deep a chain of references is followed from a flow node.
    private static final int MAX_REFERENCE_DEPTH = 6;
    // Passes to place convergence and cycle-only nodes, then to pull convergences to the barycentre.
    private static final int PLACEMENT_PASSES = 4;
    private static final int BARYCENTRE_PASSES = 3;

    private static final String LANE_PIN_PREFIX = "lane:";
    private static final String REFS_PIN_PREFIX = "refs:";

    private final DetailLevel detail;
    private final Map<String, Boolean> pins;
    private final Set<String> expanded;

    private final List<GraphNode> nodes;
    private final Map<String, GraphNode> byId = new LinkedHashMap<>();
    private final List<GraphEdge> flowEdges = new ArrayList<>();
    private final List<GraphEdge> refEdges = new ArrayList<>();
    private final List<GraphEdge> httpEdges = new ArrayList<>();
    // The reference edges by source and by target, each in the order the edges were sent
    private final Map<String, List<GraphEdge>> refEdgesFrom = new HashMap<>();
    private final Map<String, List<GraphEdge>> refEdgesTo = new HashMap<>();
    private final List<GraphNode> flowNodes = new ArrayList<>();

    private final Set<String> back = new LinkedHashSet<>();
    private final Map<String, List<String>> forwardOut = new LinkedHashMap<>();
    private final Map<String, List<String>> forwardIn = new LinkedHashMap<>();
    private final Map<String, Integer> col = new LinkedHashMap<>();
    private final Map<String, Integer> lane = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> lanesFor = new LinkedHashMap<>();
    private int nextLane = 0;

    private final List<Instance> allInstances = new ArrayList<>();
    private final Map<String, List<Instance>> instancesOf = new LinkedHashMap<>();
    private final Map<String, String> redirect = new LinkedHashMap<>();
    private final List<Instance> kept = new ArrayList<>();
    private final Map<String, Instance> keptById = new LinkedHashMap<>();

    private GraphLayout(final DependencyGraph graph,
                        final Set<String> expanded,
                        final DetailLevel detail,
                        final Map<String, Boolean> pins) {
        this.nodes = graph == null
                ? Collections.emptyList()
                : graph.getNodes();
        this.expanded = expanded == null
                ? Collections.emptySet()
                : expanded;
        this.detail = detail == null
                ? DetailLevel.EVERYTHING
                : detail;
        this.pins = pins == null
                ? Collections.emptyMap()
                : pins;
        readGraph(graph);
    }

    /// Lays out a graph.
    ///
    /// @param graph    What the server sent. Any positions in it are ignored. Null is treated as empty.
    /// @param expanded The ids of the pipelines to draw expanded, showing their elements. Null for none.
    /// @param detail   How much to draw, which a pin overrides lane by lane. Null for everything.
    /// @param pins     Explicit collapse states, keyed by [#lanePinKey] or [#refsPinKey], each true for
    ///                 collapsed. A pin survives a change of detail level, so a lane opened deliberately is
    ///                 not closed by zooming out. Null for none.
    /// @return every instance with its position, and the edges between them.
    public static LayoutResult layout(final DependencyGraph graph,
                                      final Set<String> expanded,
                                      final DetailLevel detail,
                                      final Map<String, Boolean> pins) {
        return new GraphLayout(graph, expanded, detail, pins).run();
    }

    /// @param lane A lane number.
    /// @return the pin key that collapses or opens the whole lane. Also the id of its summary.
    public static String lanePinKey(final int lane) {
        return LANE_PIN_PREFIX + lane;
    }

    /// @param lane A lane number.
    /// @return the pin key that collapses or opens the lane's reference row.
    public static String refsPinKey(final int lane) {
        return REFS_PIN_PREFIX + lane;
    }

    /// @param instance An instance.
    /// @param expanded The ids of the pipelines drawn expanded.
    /// @return the size the instance is drawn at.
    public static Dims dims(final Instance instance, final Set<String> expanded) {
        final InstanceKind kind = instance.getKind();
        if (kind == InstanceKind.FLOW
            && expanded.contains(instance.getId())
            && instance.isExpandable()) {
            final int elementCount = instance.getNode().getElementCount();
            final int chipStep = LayoutConstants.EH + LayoutConstants.EXPANDED_CHIP_GAP;
            return new Dims(LayoutConstants.EW + LayoutConstants.EXPANDED_MARGIN,
                    LayoutConstants.EXPANDED_HEADER + elementCount * chipStep + LayoutConstants.EXPANDED_FOOTER);
        } else if (kind == InstanceKind.REFERENCE || kind == InstanceKind.REFERENCE_SUMMARY) {
            return new Dims(LayoutConstants.RW, LayoutConstants.RH);
        } else if (kind == InstanceKind.ENDPOINT) {
            return new Dims(LayoutConstants.EPW, LayoutConstants.EPH);
        } else {
            return new Dims(LayoutConstants.W, LayoutConstants.H);
        }
    }

    /// Compares names as the reference layout's `localeCompare` does, as nearly as GWT allows:
    /// `java.text.Collator` is not emulated, so case is ignored first and only then decides.
    ///
    /// @param a A name, or null.
    /// @param b A name, or null.
    /// @return negative, zero or positive as `a` sorts before, with or after `b`.
    static int compareNames(final String a, final String b) {
        final String left = a == null
                ? ""
                : a;
        final String right = b == null
                ? ""
                : b;
        final int ignoringCase = String.CASE_INSENSITIVE_ORDER.compare(left, right);
        return ignoringCase != 0
                ? ignoringCase
                : left.compareTo(right);
    }

    private LayoutResult run() {
        breakCycles();
        addInheritance();
        assignColumns();
        assignLanes();
        seedReferences();
        createInstances();
        collapse();
        final int maxCol = placeX();
        final Map<Integer, LaneBox> laneBoxes = new LinkedHashMap<>();
        final List<Integer> lanes = placeY(laneBoxes);
        final List<LayoutEdge> edges = mapEdges();
        final List<Ghost> ghosts = createGhosts();

        final LayoutStats stats = new LayoutStats(
                kept.size(),
                nodes.size(),
                edges.size(),
                lanes.size(),
                maxCol + 1,
                kept.size() - nodes.size(),
                redirect.size());
        return new LayoutResult(kept,
                edges,
                laneBoxes,
                lanes,
                ghosts,
                new ArrayList<>(back),
                detail,
                new LinkedHashMap<>(pins),
                new LinkedHashSet<>(expanded),
                stats);
    }

    // Sorts the graph's edges by kind, dropping any whose ends were not sent.
    private void readGraph(final DependencyGraph graph) {
        for (final GraphNode node : nodes) {
            byId.put(node.getId(), node);
        }
        final List<GraphEdge> edges = graph == null
                ? Collections.emptyList()
                : graph.getEdges();
        for (final GraphEdge edge : edges) {
            if (!byId.containsKey(edge.getFrom()) || !byId.containsKey(edge.getTo())) {
                continue;
            }
            final EdgeKind kind = edge.getKind();
            if ((kind == EdgeKind.FLOW || kind == EdgeKind.QUERY)
                && isFlow(byId.get(edge.getFrom()))
                && isFlow(byId.get(edge.getTo()))) {
                flowEdges.add(edge);
            }
            if (kind == EdgeKind.REFERENCE || kind == EdgeKind.INHERITANCE) {
                refEdges.add(edge);
                refEdgesFrom.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge);
                refEdgesTo.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge);
            }
            if (kind == EdgeKind.HTTP) {
                httpEdges.add(edge);
            }
        }
        for (final GraphNode node : nodes) {
            if (isFlow(node)) {
                flowNodes.add(node);
            }
        }
    }

    // Stage 1: a depth-first search over flow edges, in node order. An edge to a node still on the stack
    // closes a cycle and is recorded as a back edge; the rest are the forward edges layering uses.
    private void breakCycles() {
        final Map<String, List<String>> out = new LinkedHashMap<>();
        for (final GraphNode node : flowNodes) {
            out.put(node.getId(), new ArrayList<>());
        }
        for (final GraphEdge edge : flowEdges) {
            out.get(edge.getFrom()).add(edge.getTo());
        }

        // 1 while on the stack, 2 once finished. The search is bounded by the server's node ceiling, so
        // recursion is acceptable.
        final Map<String, Integer> state = new HashMap<>();
        for (final GraphNode node : flowNodes) {
            if (state.getOrDefault(node.getId(), 0) == 0) {
                visit(node.getId(), out, state);
            }
        }

        for (final GraphNode node : flowNodes) {
            forwardOut.put(node.getId(), new ArrayList<>());
            forwardIn.put(node.getId(), new ArrayList<>());
        }
        for (final GraphEdge edge : flowEdges) {
            if (!back.contains(edgeKey(edge))) {
                forwardOut.get(edge.getFrom()).add(edge.getTo());
                forwardIn.get(edge.getTo()).add(edge.getFrom());
            }
        }
    }

    private void visit(final String id,
                       final Map<String, List<String>> out,
                       final Map<String, Integer> state) {
        state.put(id, 1);
        for (final String target : out.get(id)) {
            final int targetState = state.getOrDefault(target, 0);
            if (targetState == 1) {
                back.add(id + ">" + target);
            } else if (targetState == 0) {
                visit(target, out, state);
            }
        }
        state.put(id, 2);
    }

    // Stage 2: a pipeline library has no flow edges at all, so without this every pipeline would be a root
    // in column 0 and the hierarchy would be invisible. Applied only where flow says nothing, because
    // applying it everywhere would drag inherited templates into the flow columns.
    private void addInheritance() {
        final Set<String> flowConnected = new LinkedHashSet<>();
        for (final GraphEdge edge : flowEdges) {
            if (!back.contains(edgeKey(edge))) {
                flowConnected.add(edge.getFrom());
                flowConnected.add(edge.getTo());
            }
        }
        for (final GraphEdge edge : refEdges) {
            if (edge.getKind() == EdgeKind.INHERITANCE
                && isFlow(byId.get(edge.getFrom()))
                && isFlow(byId.get(edge.getTo()))
                && !flowConnected.contains(edge.getFrom())
                && !flowConnected.contains(edge.getTo())) {
                forwardOut.get(edge.getFrom()).add(edge.getTo());
                forwardIn.get(edge.getTo()).add(edge.getFrom());
            }
        }
    }

    // Stage 3: longest path from a source, by repeatedly taking nodes whose inputs are all placed. Nodes in
    // a cycle with no way in keep column 0.
    private void assignColumns() {
        final Map<String, Integer> inDegree = new HashMap<>();
        final Deque<String> queue = new ArrayDeque<>();
        for (final GraphNode node : flowNodes) {
            col.put(node.getId(), 0);
            inDegree.put(node.getId(), forwardIn.get(node.getId()).size());
        }
        for (final GraphNode node : flowNodes) {
            if (inDegree.get(node.getId()) == 0) {
                queue.add(node.getId());
            }
        }
        while (!queue.isEmpty()) {
            final String id = queue.poll();
            for (final String target : forwardOut.get(id)) {
                col.put(target, Math.max(col.get(target), col.get(id) + 1));
                final int remaining = inDegree.get(target) - 1;
                inDegree.put(target, remaining);
                if (remaining == 0) {
                    queue.add(target);
                }
            }
        }
    }

    // Stage 4: each source starts a lane, the first branch keeps it and later branches fork new ones.
    // A convergence is placed afterwards, at the mean of its inputs' lanes.
    private void assignLanes() {
        for (final GraphNode node : flowNodes) {
            final String id = node.getId();
            if (forwardIn.get(id).isEmpty() && !lane.containsKey(id)) {
                walk(id, nextLane++);
            }
        }

        // Convergence and cycle-only nodes take the mean of whichever inputs are placed. The passes update
        // in place, in node order, so a node placed early in a pass counts towards later ones.
        for (int pass = 0; pass < PLACEMENT_PASSES; pass++) {
            for (final GraphNode node : flowNodes) {
                if (lane.containsKey(node.getId())) {
                    continue;
                }
                int sum = 0;
                int count = 0;
                for (final String input : forwardIn.get(node.getId())) {
                    final Integer inputLane = lane.get(input);
                    if (inputLane != null) {
                        sum += inputLane;
                        count++;
                    }
                }
                if (count > 0) {
                    lane.put(node.getId(), roundMean(sum, count));
                }
            }
        }
        for (final GraphNode node : flowNodes) {
            if (!lane.containsKey(node.getId())) {
                lane.put(node.getId(), nextLane++);
            }
        }

        // Pull convergences to the barycentre of their inputs.
        for (int pass = 0; pass < BARYCENTRE_PASSES; pass++) {
            for (final GraphNode node : flowNodes) {
                final List<String> inputs = forwardIn.get(node.getId());
                if (inputs.size() > 1) {
                    int sum = 0;
                    for (final String input : inputs) {
                        sum += lane.get(input);
                    }
                    lane.put(node.getId(), roundMean(sum, inputs.size()));
                }
            }
        }
    }

    private void walk(final String id, final int currentLane) {
        if (lane.containsKey(id)) {
            return;
        }
        lane.put(id, currentLane);
        final List<String> successors = new ArrayList<>();
        for (final String target : forwardOut.get(id)) {
            if (!lane.containsKey(target)) {
                successors.add(target);
            }
        }
        for (int i = 0; i < successors.size(); i++) {
            final String target = successors.get(i);
            // A convergence is placed later, but still uses up its index, so the successor after it forks a
            // new lane. The scenarios were tuned against this.
            if (forwardIn.get(target).size() > 1) {
                continue;
            }
            // The lane is taken before the walk checks whether the target is already placed, so a fork to a
            // node placed meanwhile still uses up a lane number.
            final int targetLane = i == 0
                    ? currentLane
                    : nextLane++;
            walk(target, targetLane);
        }
    }

    // Stage 5: the server sends one node per document, and which lanes need a copy is a layout question.
    private void seedReferences() {
        for (final GraphNode node : flowNodes) {
            seedReferences(node.getId(), lane.get(node.getId()), 0, new LinkedHashSet<>());
        }
        // Inheritance parents point into flow pipelines, so catch that direction too.
        for (final GraphEdge edge : refEdges) {
            final GraphNode from = byId.get(edge.getFrom());
            final GraphNode to = byId.get(edge.getTo());
            if (!isFlow(from) && isFlow(to)) {
                lanesFor.computeIfAbsent(edge.getFrom(), k -> new LinkedHashSet<>()).add(lane.get(to.getId()));
            }
        }
        // A reference nobody reaches still has to appear somewhere.
        for (final GraphNode node : nodes) {
            if (node.getRole() == NodeRole.REFERENCE && !lanesFor.containsKey(node.getId())) {
                final Set<Integer> lanes = new LinkedHashSet<>();
                lanes.add(nextLane++);
                lanesFor.put(node.getId(), lanes);
            }
        }
    }

    private void seedReferences(final String fromId,
                                final int currentLane,
                                final int depth,
                                final Set<String> seen) {
        if (depth > MAX_REFERENCE_DEPTH) {
            return;
        }
        for (final GraphEdge edge : refEdgesFrom.getOrDefault(fromId, List.of())) {
            if (isFlow(byId.get(edge.getTo()))) {
                continue;
            }
            final String target = edge.getTo();
            if (!seen.add(target)) {
                continue;
            }
            lanesFor.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(currentLane);
            seedReferences(target, currentLane, depth + 1, seen);
        }
    }

    private void createInstances() {
        for (final GraphNode node : flowNodes) {
            final Instance instance = Instance.flow(node, lane.get(node.getId()), col.get(node.getId()));
            allInstances.add(instance);
            final List<Instance> list = new ArrayList<>();
            list.add(instance);
            instancesOf.put(node.getId(), list);
        }
        for (final Map.Entry<String, Set<Integer>> entry : lanesFor.entrySet()) {
            final GraphNode node = byId.get(entry.getKey());
            if (node == null) {
                continue;
            }
            final List<Instance> list = new ArrayList<>();
            for (final Integer referenceLane : entry.getValue()) {
                final Instance instance = Instance.reference(node, referenceLane, entry.getValue().size());
                allInstances.add(instance);
                list.add(instance);
            }
            instancesOf.put(entry.getKey(), list);
        }
        // Endpoints follow the instances that call them, or are called by them.
        for (final GraphNode node : nodes) {
            if (node.getRole() != NodeRole.EXTERNAL) {
                continue;
            }
            final List<String> connected = new ArrayList<>();
            for (final GraphEdge edge : httpEdges) {
                if (edge.getTo().equals(node.getId())) {
                    connected.add(edge.getFrom());
                }
            }
            for (final GraphEdge edge : httpEdges) {
                if (edge.getFrom().equals(node.getId())) {
                    connected.add(edge.getTo());
                }
            }
            final List<Instance> hosts = new ArrayList<>();
            for (final String id : connected) {
                hosts.addAll(instancesOf.getOrDefault(id, Collections.emptyList()));
            }
            final List<Instance> list = new ArrayList<>();
            if (hosts.isEmpty()) {
                // An endpoint with no host falls back to lane 0, as in the reference layout.
                final Instance instance = Instance.endpoint(node, 0, null);
                allInstances.add(instance);
                list.add(instance);
            } else {
                for (final Instance host : hosts) {
                    final Instance instance = Instance.endpoint(node, host.getLane(), host.getId());
                    allInstances.add(instance);
                    list.add(instance);
                }
            }
            instancesOf.put(node.getId(), list);
        }
    }

    // Stage 6: the detail level sets each lane's default and a pin overrides it.
    private void collapse() {
        for (final Map.Entry<Integer, List<Instance>> entry : byLane(allInstances).entrySet()) {
            final int currentLane = entry.getKey();
            final List<Instance> mine = entry.getValue();
            if (isLaneCollapsed(currentLane)) {
                final List<Instance> flow = new ArrayList<>();
                for (final Instance instance : mine) {
                    if (instance.getKind() == InstanceKind.FLOW) {
                        flow.add(instance);
                    }
                }
                flow.sort(Comparator.comparingInt(Instance::getCol));
                final Instance head = flow.isEmpty()
                        ? mine.get(0)
                        : flow.get(0);
                final Instance summary = Instance.laneSummary(currentLane,
                        head,
                        mine.size(),
                        pins.containsKey(lanePinKey(currentLane)));
                for (final Instance instance : mine) {
                    redirect.put(instance.getId(), summary.getId());
                }
                kept.add(summary);
                continue;
            }

            final List<Instance> rest = new ArrayList<>();
            for (final Instance instance : mine) {
                if (instance.getKind() == InstanceKind.FLOW) {
                    kept.add(instance);
                } else {
                    rest.add(instance);
                }
            }
            if (isRefsCollapsed(currentLane) && !rest.isEmpty()) {
                // Endpoints fold into the reference summary too, as in the reference layout.
                final Instance summary = Instance.referenceSummary(currentLane,
                        rest.size(),
                        pins.containsKey(refsPinKey(currentLane)));
                for (final Instance instance : rest) {
                    redirect.put(instance.getId(), summary.getId());
                }
                kept.add(summary);
            } else {
                kept.addAll(rest);
            }
        }
        for (final Instance instance : kept) {
            keptById.put(instance.getId(), instance);
        }
    }

    private boolean isLaneCollapsed(final int currentLane) {
        final String key = lanePinKey(currentLane);
        return pins.containsKey(key)
                ? Boolean.TRUE.equals(pins.get(key))
                : detail.getLevel() >= DetailLevel.LANES_COLLAPSED.getLevel();
    }

    private boolean isRefsCollapsed(final int currentLane) {
        final String key = refsPinKey(currentLane);
        return pins.containsKey(key)
                ? Boolean.TRUE.equals(pins.get(key))
                : detail.getLevel() >= DetailLevel.REFERENCES_COLLAPSED.getLevel() || isLaneCollapsed(currentLane);
    }

    // Stage 7a: flow nodes by column. Reference and endpoint rows pack left to right within their lane,
    // ordered by the column of whatever references them.
    private int placeX() {
        int maxCol = 0;
        for (final Instance instance : kept) {
            if (instance.getCol() != null) {
                maxCol = Math.max(maxCol, instance.getCol());
                instance.setX(LayoutConstants.PAD + instance.getCol() * LayoutConstants.COL_STEP);
            }
        }

        final Map<Instance, Integer> refCols = new HashMap<>();
        for (final Instance instance : kept) {
            if (isReferenceRow(instance)) {
                refCols.put(instance, referenceColumn(instance));
            }
        }
        final Comparator<Instance> referenceOrder = Comparator
                .comparingInt((Instance instance) -> refCols.get(instance))
                .thenComparing((a, b) -> compareNames(a.getName(), b.getName()));

        for (final List<Instance> mine : byLane(kept).values()) {
            final List<Instance> references = new ArrayList<>();
            final List<Instance> endpoints = new ArrayList<>();
            for (final Instance instance : mine) {
                if (isReferenceRow(instance)) {
                    references.add(instance);
                } else if (instance.getKind() == InstanceKind.ENDPOINT) {
                    endpoints.add(instance);
                }
            }
            references.sort(referenceOrder);
            for (int i = 0; i < references.size(); i++) {
                references.get(i).setX(LayoutConstants.PAD + i * LayoutConstants.REF_STEP);
            }
            for (int i = 0; i < endpoints.size(); i++) {
                endpoints.get(i).setX(LayoutConstants.PAD + i * LayoutConstants.EP_STEP);
            }
        }
        return maxCol;
    }

    // The leftmost column among the flow nodes a referenced document is connected to, or 0.
    private int referenceColumn(final Instance instance) {
        final List<String> users = new ArrayList<>();
        for (final GraphEdge edge : refEdgesTo.getOrDefault(instance.getSrc(), List.of())) {
            users.add(edge.getFrom());
        }
        for (final GraphEdge edge : refEdgesFrom.getOrDefault(instance.getSrc(), List.of())) {
            users.add(edge.getTo());
        }
        Integer min = null;
        for (final String user : users) {
            final Integer userCol = col.get(user);
            if (userCol != null && (min == null || userCol < min)) {
                min = userCol;
            }
        }
        return min == null
                ? 0
                : min;
    }

    // Stage 7b: stack lanes by the rows they actually contain, so a lane with no reference row costs
    // nothing.
    private List<Integer> placeY(final Map<Integer, LaneBox> laneBoxes) {
        final Set<Integer> laneSet = new TreeSet<>();
        for (final Instance instance : kept) {
            laneSet.add(instance.getLane());
        }
        final List<Integer> lanes = new ArrayList<>(laneSet);

        int y = LayoutConstants.PAD;
        for (final int currentLane : lanes) {
            final List<Instance> flow = new ArrayList<>();
            final List<Instance> references = new ArrayList<>();
            final List<Instance> endpoints = new ArrayList<>();
            for (final Instance instance : kept) {
                if (instance.getLane() != currentLane) {
                    continue;
                }
                final InstanceKind kind = instance.getKind();
                if (kind == InstanceKind.FLOW || kind == InstanceKind.LANE_SUMMARY) {
                    flow.add(instance);
                } else if (isReferenceRow(instance)) {
                    references.add(instance);
                } else if (kind == InstanceKind.ENDPOINT) {
                    endpoints.add(instance);
                }
            }

            final int top = y;
            // Two flow nodes can share a lane and a column, e.g. two dashboards reading the same index, so
            // they stack. Their actual heights are accumulated, because an expanded pipeline is taller.
            final Map<Integer, Integer> nextY = new HashMap<>();
            flow.sort(Comparator.comparingInt(Instance::getCol)
                    .thenComparing((a, b) -> compareNames(a.getId(), b.getId())));
            int flowBottom = flow.isEmpty()
                    ? top + LayoutConstants.H
                    : Integer.MIN_VALUE;
            for (final Instance instance : flow) {
                final int nodeTop = nextY.getOrDefault(instance.getCol(), top);
                final int height = dims(instance).h();
                instance.setY(nodeTop);
                nextY.put(instance.getCol(), nodeTop + height + LayoutConstants.STACK_GAP);
                flowBottom = Math.max(flowBottom, nodeTop + height);
            }

            int bottom = flowBottom;
            if (!references.isEmpty()) {
                final int referenceTop = flowBottom + LayoutConstants.REF_ROW_GAP;
                for (final Instance instance : references) {
                    instance.setY(referenceTop);
                }
                bottom = referenceTop + LayoutConstants.RH;
            }
            if (!endpoints.isEmpty()) {
                final int endpointTop = bottom + LayoutConstants.EP_GAP;
                for (final Instance instance : endpoints) {
                    instance.setY(endpointTop);
                }
                bottom = endpointTop + LayoutConstants.EPH;
            }
            laneBoxes.put(currentLane,
                    new LaneBox(top - LayoutConstants.LANE_BOX_TOP, bottom + LayoutConstants.LANE_BOX_BOTTOM));
            y = bottom + LayoutConstants.LANE_GAP;
        }
        return lanes;
    }

    // Stage 8: each edge is mapped onto instances, then through any collapse. An edge that ends up inside one
    // summary is dropped.
    private List<LayoutEdge> mapEdges() {
        final List<LayoutEdge> out = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (final GraphEdge edge : flowEdges) {
            addEdge(out,
                    seen,
                    firstInstanceOf(edge.getFrom()),
                    firstInstanceOf(edge.getTo()),
                    edge.getKind(),
                    back.contains(edgeKey(edge)),
                    edge.getCertainty(),
                    null,
                    edge.isAmbiguous());
        }
        // A reference is drawn within a lane: from each copy of its user to the copy in the same lane.
        for (final GraphEdge edge : refEdges) {
            for (final Instance from : instancesOf.getOrDefault(edge.getFrom(), Collections.emptyList())) {
                final Instance to = instanceInLane(edge.getTo(), from.getLane());
                if (to != null) {
                    addEdge(out,
                            seen,
                            from,
                            to,
                            edge.getKind(),
                            null,
                            edge.getCertainty(),
                            edge.getElement(),
                            edge.isAmbiguous());
                }
            }
        }
        for (final GraphEdge edge : httpEdges) {
            for (final Instance from : instancesOf.getOrDefault(edge.getFrom(), Collections.emptyList())) {
                final Instance to = instanceInLane(edge.getTo(), from.getLane());
                if (to != null) {
                    addEdge(out, seen, from, to, EdgeKind.HTTP, null, edge.getCertainty(), null, edge.isAmbiguous());
                }
            }
        }
        return out;
    }

    private void addEdge(final List<LayoutEdge> out,
                         final Set<String> seen,
                         final Instance from,
                         final Instance to,
                         final EdgeKind kind,
                         final Boolean isBack,
                         final EdgeCertainty certainty,
                         final String element,
                         final boolean ambiguous) {
        final Instance a = resolve(from);
        final Instance b = resolve(to);
        if (a == null || b == null || a == b) {
            return;
        }
        if (seen.add(a.getId() + ">" + b.getId() + ">" + kind)) {
            out.add(new LayoutEdge(a, b, kind, isBack, certainty, element, ambiguous));
        }
    }

    // The instance drawn in place of this one: its summary if it was folded into one, itself if it was kept,
    // or null.
    private Instance resolve(final Instance instance) {
        if (instance == null) {
            return null;
        }
        final String summaryId = redirect.get(instance.getId());
        if (summaryId != null) {
            return keptById.get(summaryId);
        }
        return keptById.containsKey(instance.getId())
                ? instance
                : null;
    }

    private Instance firstInstanceOf(final String id) {
        final List<Instance> list = instancesOf.get(id);
        return list == null || list.isEmpty()
                ? null
                : list.get(0);
    }

    private Instance instanceInLane(final String id, final int targetLane) {
        for (final Instance instance : instancesOf.getOrDefault(id, Collections.emptyList())) {
            if (instance.getLane() == targetLane) {
                return instance;
            }
        }
        return null;
    }

    // An empty folder gets create slots from the same column arithmetic, so they line up with where real
    // content would land.
    private List<Ghost> createGhosts() {
        final List<Ghost> ghosts = new ArrayList<>();
        if (kept.isEmpty()) {
            ghosts.add(flowGhost(0, "+ Feed"));
            ghosts.add(flowGhost(1, "+ Pipeline"));
            ghosts.add(flowGhost(2, "+ Feed"));
            ghosts.add(flowGhost(3, "+ Pipeline"));
            ghosts.add(flowGhost(4, "+ Index"));
            ghosts.add(flowGhost(5, "+ Dashboard"));
            ghosts.add(new Ghost("+ XSLT",
                    true,
                    LayoutConstants.PAD + LayoutConstants.REF_STEP,
                    LayoutConstants.PAD + LayoutConstants.H + LayoutConstants.REF_ROW_GAP,
                    LayoutConstants.RW,
                    LayoutConstants.RH));
        }
        return ghosts;
    }

    private static Ghost flowGhost(final int column, final String label) {
        return new Ghost(label,
                false,
                LayoutConstants.PAD + column * LayoutConstants.COL_STEP,
                LayoutConstants.PAD,
                LayoutConstants.W,
                LayoutConstants.H);
    }

    private Dims dims(final Instance instance) {
        return dims(instance, expanded);
    }

    // The instances of each lane, in lane order, each lane's in the order given.
    private static Map<Integer, List<Instance>> byLane(final List<Instance> instances) {
        final Map<Integer, List<Instance>> lanes = new TreeMap<>();
        for (final Instance instance : instances) {
            lanes.computeIfAbsent(instance.getLane(), k -> new ArrayList<>()).add(instance);
        }
        return lanes;
    }

    private static boolean isFlow(final GraphNode node) {
        return node != null && node.getRole() == NodeRole.FLOW;
    }

    private static boolean isReferenceRow(final Instance instance) {
        return instance.getKind() == InstanceKind.REFERENCE
               || instance.getKind() == InstanceKind.REFERENCE_SUMMARY;
    }

    private static String edgeKey(final GraphEdge edge) {
        return edge.getFrom() + ">" + edge.getTo();
    }

    // JavaScript's Math.round of the mean, which rounds a half up.
    private static int roundMean(final int sum, final int count) {
        return (int) Math.round((double) sum / count);
    }
}
