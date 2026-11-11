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

package stroom.explorer.impl.graph;

import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.EdgeCertainty;
import stroom.dependency.shared.EdgeKind;
import stroom.dependency.shared.GraphEdge;
import stroom.dependency.shared.GraphNode;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;
import stroom.docstore.api.DependencyEdge;
import stroom.explorer.shared.ExplorerConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Builds a folder's [DependencyGraph] from the recorded dependencies of what is in it.
///
/// It holds no state between calls and reads nothing itself: everything comes from a [GraphSource], so
/// the rules are tested without a database. In order:
///
/// 1. **Discovery.** The folder's documents the user can view, then up to `contextDepth` hops of
///    context: what they point at and what points at them. Every document passes the same view check,
///    which fails closed. A document the user cannot view is absent: not a node, not an edge's end, not
///    counted.
/// 2. **Classification.** `doc_dependency` records only that A points at B, so each edge's kind is
///    worked out from the pair of types, and for a pipeline from its configuration ([PipelineFacts]).
///    A processor filter's feeds and pipeline become feed-to-pipeline flow; dashboards' edges are
///    reversed, to point the way the data goes.
/// 3. **Pipeline instances.** A pipeline is drawn once per source-to-destination pair, with the id
///    `inst:<pipeline>:<source>:<destination>`, so each chain through a shared pipeline gets its own copy.
/// 4. **The ceiling.** At most `maxNodes` nodes, the folder's own first.
public final class DependencyGraphAssembler {

    /// The prefix of a pipeline instance's id.
    public static final String INSTANCE_PREFIX = "inst:";

    // Types drawn on the flow row: where data comes from, goes through and ends up.
    static final Set<String> FLOW_TYPES = Set.of(
            "Feed", "Pipeline", "Index", "ElasticIndex", "SolrIndex", "PlanB", "StatisticStore",
            "StroomStatsStore", "Dashboard", "Dictionary", "AnalyticRule");

    private static final String PROCESSOR_FILTER_TYPE = "ProcessorFilter";
    private static final String PIPELINE_TYPE = "Pipeline";
    private static final String FEED_TYPE = "Feed";
    private static final String DASHBOARD_TYPE = "Dashboard";
    private static final String ANALYTIC_RULE_TYPE = "AnalyticRule";

    private final GraphSource source;
    private final int contextDepth;
    private final int maxNodes;

    // Every document in the graph, by uuid, in the order found: the folder's own first, then context.
    private final Map<String, Found> docs = new LinkedHashMap<>();
    // The recorded edges between documents, by "from>to".
    private final Map<String, DependencyEdge> rawEdges = new LinkedHashMap<>();
    // Each processor filter's recorded edges, by the filter's uuid.
    private final Map<String, List<DependencyEdge>> filterEdges = new LinkedHashMap<>();
    // How many of each document's references point at nothing.
    private final Map<String, Integer> unresolved = new HashMap<>();
    private final Map<String, PipelineFacts> facts = new HashMap<>();
    private boolean nodeLimitReached;

    private DependencyGraphAssembler(final GraphSource source, final int contextDepth, final int maxNodes) {
        this.source = source;
        this.contextDepth = contextDepth;
        this.maxNodes = maxNodes;
    }

    /// Builds a folder's graph.
    ///
    /// @param root         The folder.
    /// @param contextDepth How many hops of context beyond the folder to include, 0 or more.
    /// @param maxNodes     The most nodes to return, at least 1.
    /// @param source       Where the folder's contents, the recorded edges, the permission check and the
    ///                     documents' configuration come from.
    /// @return the graph.
    public static DependencyGraph assemble(final DocRef root,
                                           final int contextDepth,
                                           final int maxNodes,
                                           final GraphSource source) {
        return new DependencyGraphAssembler(source, contextDepth, maxNodes).build(root);
    }

    private DependencyGraph build(final DocRef root) {
        discover(root);
        final List<ClassifiedEdge> edges = classify();
        return expand(root, edges);
    }

    // --------------------------------------------------------------------------------
    // 1. Discovery
    // --------------------------------------------------------------------------------

    private void discover(final DocRef root) {
        for (final DocRef docRef : source.contents(root)) {
            if (docRef != null
                && !ExplorerConstants.isFolderOrSystem(docRef)
                && !docs.containsKey(docRef.getUuid())
                && source.canView(docRef)) {
                addDoc(docRef, 0);
            }
        }

        Set<String> frontier = new LinkedHashSet<>(docs.keySet());
        for (int hop = 0; !frontier.isEmpty(); hop++) {
            final List<DependencyEdge> found = readEdges(frontier);
            if (hop >= contextDepth) {
                break;
            }
            final Set<String> next = new LinkedHashSet<>();
            for (final DependencyEdge edge : found) {
                addContext(edge.from(), hop + 1, next);
                if (edge.targetExists()) {
                    addContext(edge.to(), hop + 1, next);
                }
            }
            frontier = next;
        }
    }

    // Reads the edges from and to the frontier, and the edges of any processor filter among them, so a
    // filter's feeds and pipeline are both known.
    private List<DependencyEdge> readEdges(final Set<String> frontier) {
        final List<DependencyEdge> found = new ArrayList<>();
        found.addAll(source.edgesFrom(frontier));
        found.addAll(source.edgesTo(frontier));

        final Set<String> filters = new LinkedHashSet<>();
        final List<DependencyEdge> documentEdges = new ArrayList<>();
        for (final DependencyEdge edge : found) {
            if (PROCESSOR_FILTER_TYPE.equals(edge.from().getType())) {
                if (!filterEdges.containsKey(edge.from().getUuid())) {
                    filters.add(edge.from().getUuid());
                }
            } else {
                documentEdges.add(edge);
                rawEdges.putIfAbsent(key(edge.from().getUuid(), edge.to().getUuid()), edge);
            }
        }
        for (final DependencyEdge edge : source.edgesFrom(filters)) {
            filterEdges.computeIfAbsent(edge.from().getUuid(), k -> new ArrayList<>()).add(edge);
            // A filter links what it names to each other, so they are a hop from the folder too.
            if (edge.targetExists()) {
                documentEdges.add(edge);
            }
        }
        return documentEdges;
    }

    private void addContext(final DocRef docRef, final int hop, final Set<String> next) {
        if (docRef == null
            || PROCESSOR_FILTER_TYPE.equals(docRef.getType())
            || docs.containsKey(docRef.getUuid())
            || ExplorerConstants.isFolderOrSystem(docRef)
            || !source.isDocumentType(docRef.getType())
            || !source.canView(docRef)) {
            return;
        }
        addDoc(docRef, hop);
        next.add(docRef.getUuid());
    }

    private void addDoc(final DocRef docRef, final int hop) {
        docs.put(docRef.getUuid(), new Found(docRef, hop));
    }

    // --------------------------------------------------------------------------------
    // 2. Classification
    // --------------------------------------------------------------------------------

    private List<ClassifiedEdge> classify() {
        final Map<String, ClassifiedEdge> edges = new LinkedHashMap<>();
        for (final DependencyEdge edge : rawEdges.values()) {
            final String from = edge.from().getUuid();
            final String to = edge.to().getUuid();
            if (!docs.containsKey(from)) {
                continue;
            }
            if (!edge.targetExists()) {
                // A reference to nothing. Never to a document the user cannot see: that exists, so is
                // simply absent.
                if (source.isDocumentType(edge.to().getType())) {
                    unresolved.merge(from, 1, Integer::sum);
                }
                continue;
            }
            if (docs.containsKey(to)) {
                add(edges, classify(edge));
            }
        }

        // A processor filter feeds each feed it names to each pipeline (or analytic rule) it runs.
        for (final List<DependencyEdge> filter : filterEdges.values()) {
            final List<String> feeds = new ArrayList<>();
            final List<String> consumers = new ArrayList<>();
            for (final DependencyEdge edge : filter) {
                final String to = edge.to().getUuid();
                if (!edge.targetExists() || !docs.containsKey(to)) {
                    continue;
                }
                final String type = edge.to().getType();
                if (FEED_TYPE.equals(type)) {
                    feeds.add(to);
                } else if (PIPELINE_TYPE.equals(type) || ANALYTIC_RULE_TYPE.equals(type)) {
                    consumers.add(to);
                }
            }
            for (final String feed : feeds) {
                for (final String consumer : consumers) {
                    add(edges, new ClassifiedEdge(feed, consumer, EdgeKind.FLOW, EdgeCertainty.CONFIGURED, false));
                }
            }
        }

        // What a pipeline writes to through its parent's configuration is not recorded against it.
        for (final Found found : docs.values()) {
            final DocRef docRef = found.docRef();
            if (PIPELINE_TYPE.equals(docRef.getType())) {
                final PipelineFacts pipeline = facts(docRef);
                if (pipeline != null) {
                    for (final String output : pipeline.outputs()) {
                        if (docs.containsKey(output)) {
                            add(edges, new ClassifiedEdge(docRef.getUuid(),
                                    output,
                                    EdgeKind.FLOW,
                                    EdgeCertainty.CONFIGURED,
                                    false));
                        }
                    }
                }
            }
        }
        return new ArrayList<>(edges.values());
    }

    // The kind of a recorded edge between two documents in the graph.
    private ClassifiedEdge classify(final DependencyEdge edge) {
        final DocRef from = edge.from();
        final DocRef to = edge.to();
        final boolean ambiguous = edge.ambiguous();

        if (PIPELINE_TYPE.equals(from.getType())) {
            final PipelineFacts pipeline = facts(from);
            if (PIPELINE_TYPE.equals(to.getType())) {
                // Either its parent, drawn parent to child, or a reference loader's pipeline.
                if (pipeline != null && pipeline.parent() != null && to.getUuid().equals(pipeline.parent().getUuid())) {
                    return new ClassifiedEdge(to.getUuid(), from.getUuid(), EdgeKind.INHERITANCE,
                            EdgeCertainty.STATIC, ambiguous);
                }
                // A reference loader's pipeline; inferred if the pipeline could not be read.
                return new ClassifiedEdge(from.getUuid(), to.getUuid(), EdgeKind.REFERENCE,
                        pipeline == null
                                ? EdgeCertainty.INFERRED
                                : EdgeCertainty.STATIC,
                        ambiguous);
            }
            if (pipeline != null && pipeline.outputs().contains(to.getUuid())) {
                return new ClassifiedEdge(from.getUuid(), to.getUuid(), EdgeKind.FLOW,
                        EdgeCertainty.CONFIGURED, ambiguous);
            }
            if (FEED_TYPE.equals(to.getType())) {
                // Not an output, so a reference loader's feed; inferred if the pipeline could not be read.
                return new ClassifiedEdge(from.getUuid(), to.getUuid(), EdgeKind.REFERENCE,
                        pipeline == null
                                ? EdgeCertainty.INFERRED
                                : EdgeCertainty.CONFIGURED,
                        ambiguous);
            }
            if (PipelineFacts.STORE_TYPES.contains(to.getType())) {
                return new ClassifiedEdge(from.getUuid(), to.getUuid(), EdgeKind.FLOW,
                        EdgeCertainty.INFERRED, ambiguous);
            }
        } else if (DASHBOARD_TYPE.equals(from.getType())
                   && (PipelineFacts.STORE_TYPES.contains(to.getType()) || PIPELINE_TYPE.equals(to.getType()))) {
            // A dashboard is configured to read its index and extraction pipeline; drawn the way the data
            // goes.
            return new ClassifiedEdge(to.getUuid(), from.getUuid(), EdgeKind.QUERY, EdgeCertainty.STATIC, ambiguous);
        }
        return new ClassifiedEdge(from.getUuid(), to.getUuid(), EdgeKind.REFERENCE, EdgeCertainty.STATIC, ambiguous);
    }

    private PipelineFacts facts(final DocRef pipeline) {
        return facts.computeIfAbsent(pipeline.getUuid(), k -> source.pipelineFacts(pipeline));
    }

    private static void add(final Map<String, ClassifiedEdge> edges, final ClassifiedEdge edge) {
        edges.putIfAbsent(edge.from() + ">" + edge.to() + ">" + edge.kind(), edge);
    }

    // --------------------------------------------------------------------------------
    // 3. Pipeline instances, and 4. the ceiling
    // --------------------------------------------------------------------------------

    private DependencyGraph expand(final DocRef root, final List<ClassifiedEdge> edges) {
        // Each pipeline's flow in and out, in the order found.
        final Map<String, Set<String>> inputs = new HashMap<>();
        final Map<String, Set<String>> outputs = new HashMap<>();
        for (final ClassifiedEdge edge : edges) {
            if (edge.kind() == EdgeKind.FLOW) {
                if (isPipeline(edge.to())) {
                    inputs.computeIfAbsent(edge.to(), k -> new LinkedHashSet<>()).add(edge.from());
                }
                if (isPipeline(edge.from())) {
                    outputs.computeIfAbsent(edge.from(), k -> new LinkedHashSet<>()).add(edge.to());
                }
            }
        }

        // The nodes: a pipeline with flow becomes one instance per source-to-destination pair.
        final Map<String, List<Instance>> instances = new LinkedHashMap<>();
        final List<Placed> placed = new ArrayList<>();
        for (final Found found : docs.values()) {
            final DocRef docRef = found.docRef();
            final String uuid = docRef.getUuid();
            final List<Instance> list = new ArrayList<>();
            if (inputs.containsKey(uuid) || outputs.containsKey(uuid)) {
                for (final String from : orNone(inputs.get(uuid))) {
                    for (final String to : orNone(outputs.get(uuid))) {
                        list.add(new Instance(INSTANCE_PREFIX + uuid + ":" + Objects.toString(from, "")
                                              + ":" + Objects.toString(to, ""), from, to));
                    }
                }
            } else {
                list.add(new Instance(uuid, null, null));
            }
            instances.put(uuid, list);
            for (final Instance instance : list) {
                placed.add(new Placed(instance.id(), found));
            }
        }

        // The ceiling: the folder's own documents were found first, so they are kept first. Nodes are
        // built only for what is kept, as building one may read its document.
        nodeLimitReached = placed.size() > maxNodes;
        final List<GraphNode> kept = new ArrayList<>();
        final Set<String> keptIds = new LinkedHashSet<>();
        for (final Placed node : placed.subList(0, Math.min(maxNodes, placed.size()))) {
            kept.add(node(node.id(), node.found()));
            keptIds.add(node.id());
        }

        final List<GraphEdge> graphEdges = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (final ClassifiedEdge edge : edges) {
            for (final String[] ends : instanceEnds(edge, instances)) {
                if (keptIds.contains(ends[0]) && keptIds.contains(ends[1])
                    && seen.add(ends[0] + ">" + ends[1] + ">" + edge.kind())) {
                    graphEdges.add(new GraphEdge(ends[0], ends[1], edge.kind(), edge.certainty(), null,
                            edge.ambiguous()));
                }
            }
        }

        return new DependencyGraph(root,
                kept,
                graphEdges,
                List.of(),
                nodeLimitReached,
                topologyHash(kept, graphEdges),
                System.currentTimeMillis());
    }

    // The instance-to-instance edges a document edge becomes. Flow into or out of a pipeline goes to the
    // instances for that source or destination; anything else joins every instance of each end.
    private static List<String[]> instanceEnds(final ClassifiedEdge edge, final Map<String, List<Instance>> instances) {
        final List<String[]> ends = new ArrayList<>();
        final List<Instance> fromInstances = instances.getOrDefault(edge.from(), List.of());
        final List<Instance> toInstances = instances.getOrDefault(edge.to(), List.of());
        if (edge.kind() == EdgeKind.FLOW && isExpanded(toInstances)) {
            for (final Instance to : toInstances) {
                if (edge.from().equals(to.from())) {
                    ends.add(new String[]{edge.from(), to.id()});
                }
            }
        } else if (edge.kind() == EdgeKind.FLOW && isExpanded(fromInstances)) {
            for (final Instance from : fromInstances) {
                if (edge.to().equals(from.to())) {
                    ends.add(new String[]{from.id(), edge.to()});
                }
            }
        } else {
            for (final Instance from : fromInstances) {
                for (final Instance to : toInstances) {
                    ends.add(new String[]{from.id(), to.id()});
                }
            }
        }
        return ends;
    }

    private GraphNode node(final String id, final Found found) {
        final DocRef docRef = found.docRef();
        final String uuid = docRef.getUuid();
        final int hop = found.hop();
        final PipelineFacts pipeline = PIPELINE_TYPE.equals(docRef.getType())
                ? facts(docRef)
                : null;
        final Integer count = unresolved.get(uuid);
        return new GraphNode(id,
                docRef,
                FLOW_TYPES.contains(docRef.getType())
                        ? NodeRole.FLOW
                        : NodeRole.REFERENCE,
                FEED_TYPE.equals(docRef.getType())
                        ? source.streamType(docRef)
                        : null,
                hop == 0,
                hop == 0
                        ? null
                        : hop,
                pipeline == null
                        ? null
                        : pipeline.elementCount(),
                count);
    }

    private boolean isPipeline(final String uuid) {
        final Found found = docs.get(uuid);
        return found != null && PIPELINE_TYPE.equals(found.docRef().getType());
    }

    private static boolean isExpanded(final List<Instance> instances) {
        return !instances.isEmpty() && instances.getFirst().id().startsWith(INSTANCE_PREFIX);
    }

    // One element, null, standing for "none", so a pipeline with no input or no output still has
    // instances.
    private static Collection<String> orNone(final Set<String> set) {
        return set == null || set.isEmpty()
                ? Collections.singletonList(null)
                : set;
    }

    private static String topologyHash(final List<GraphNode> nodes, final List<GraphEdge> edges) {
        final StringBuilder sb = new StringBuilder();
        for (final GraphNode node : nodes) {
            sb.append(node.getId()).append('\n');
        }
        for (final GraphEdge edge : edges) {
            sb.append(edge.getFrom()).append('>').append(edge.getTo()).append('>').append(edge.getKind()).append('\n');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static String key(final String from, final String to) {
        return from + ">" + to;
    }

    /// Where the assembler's inputs come from.
    public interface GraphSource {

        /// @param folder A folder.
        /// @return everything under it, at any depth, sub-folders included; no permission check needed.
        List<DocRef> contents(DocRef folder);

        /// @param docRef A document.
        /// @return true if the user may view it. Must fail closed: false if it cannot be told.
        boolean canView(DocRef docRef);

        /// @param type A type.
        /// @return true if documents of the type are Stroom documents, as opposed to e.g. a data source
        /// that lives outside the document store.
        boolean isDocumentType(String type);

        /// @param uuids Sources' UUIDs.
        /// @return the recorded edges from them, unfiltered.
        List<DependencyEdge> edgesFrom(Set<String> uuids);

        /// @param uuids Targets' UUIDs.
        /// @return the recorded edges to them, unfiltered.
        List<DependencyEdge> edgesTo(Set<String> uuids);

        /// @param pipeline A pipeline the user may view.
        /// @return what its configuration says, or null if it cannot be read.
        PipelineFacts pipelineFacts(DocRef pipeline);

        /// @param feed A feed the user may view.
        /// @return its stream type, or null if it cannot be read.
        String streamType(DocRef feed);
    }

    // An edge between two documents, once its kind is known.
    private record ClassifiedEdge(String from,
                                  String to,
                                  EdgeKind kind,
                                  EdgeCertainty certainty,
                                  boolean ambiguous) {

    }

    // A document in the graph, and how many hops from the folder it is: 0 for the folder's own.
    private record Found(DocRef docRef, int hop) {

    }

    // A node to be built: its id, and the document it draws.
    private record Placed(String id, Found found) {

    }

    // One node a document is drawn as: for a pipeline instance, the source and destination it is drawn
    // between (either may be null).
    private record Instance(String id, String from, String to) {

    }
}
