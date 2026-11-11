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
import stroom.dependency.shared.GraphTruncation;
import stroom.dependency.shared.NodeRole;
import stroom.docref.DocRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/// The layout's test corpus: graphs in the shape the server sends, ported from the fixture scenarios in
/// the reference harness (`docs/references/folder-data-flow/stroom-structure-layout-harness.html`).
///
/// Node ids, names, uuids (`u0`, `u1`, ... in creation order) and the order of nodes and edges are the
/// same as the harness's, because the layout's output depends on them. Adding a pathological shape here is
/// the cheapest way to find out whether the layout survives it; add one here rather than a special case
/// in the layout.
public final class GraphScenarios {

    /// Every scenario, in the harness's order.
    public static final List<Scenario> ALL = List.of(
            new Scenario("events", "Event sources — uneven chains", GraphScenarios::eventSources),
            new Scenario("deep", "One chain, 7 stages deep", GraphScenarios::deepChain),
            new Scenario("extract", "Search extraction on the read path", GraphScenarios::extraction),
            new Scenario("dict", "Dictionary written and read", GraphScenarios::dictionary),
            new Scenario("fanin", "8 chains converging on 1 index", GraphScenarios::fanIn),
            new Scenario("wide12", "12 lanes × 2 refs", () -> wide(12, 2)),
            new Scenario("wide40", "40 lanes × 3 refs (~330 nodes)", () -> wide(40, 3)),
            new Scenario("refheavy", "3 lanes × 12 refs", () -> wide(3, 12)),
            new Scenario("library", "Pipeline library — no flow row", GraphScenarios::library),
            new Scenario("cycle", "Reprocessing cycle", GraphScenarios::cycle),
            new Scenario("single", "One document", GraphScenarios::single),
            new Scenario("empty", "Empty folder", GraphScenarios::empty));

    // Types drawn on the flow row. Everything else except an endpoint is a referenced document.
    private static final Set<String> FLOW_TYPES = Set.of(
            "Feed", "Pipeline", "Index", "PlanB", "Dashboard", "Endpoint", "Dictionary");

    private GraphScenarios() {
        // Static methods only.
    }

    /// @param id A scenario id, e.g. `events`.
    /// @return the scenario.
    public static Scenario get(final String id) {
        for (final Scenario scenario : ALL) {
            if (scenario.id().equals(id)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("No scenario " + id);
    }

    // A realistic event-source folder, uneven on purpose.
    private static DependencyGraph eventSources() {
        final Builder r = new Builder();
        final String idx = r.node("idx", "Index", "Network Events Index");
        final String store = r.node("store", "PlanB", "Event Store");
        final String sch = r.node("sch", "XMLSchema", "event-logging 3.5.2");
        final String common = r.node("common", "XSLT", "Common Event Norm");
        final String tmpl = r.node("tmpl", "Pipeline", "Event Parser Template");
        final String map = r.node("map", "XSLT", "Event Index Mapping");
        final String idxPipe = r.pipeline("pidx", "Event Indexing", 5);

        final List<Source> sources = List.of(
                new Source("fw", "FW-LOGS", true, "https://enrich.corp.internal/v1/asset", false, false),
                new Source("ids", "IDS-ALERTS", false, null, false, false),
                new Source("dns", "DNS-QUERY", false, null, true, false),
                new Source("prx", "PROXY-WEB", false, null, false, true));
        for (final Source s : sources) {
            final String shortName = s.name().split("-")[0];
            final String raw = r.feed(s.key() + "_raw", s.name() + ":RAW_EVENTS", "RAW_EVENTS");
            final String par = r.pipeline(s.key() + "_par", shortName + " Events Parser", s.split()
                    ? 7
                    : 6);
            final String ev = r.feed(s.key() + "_ev", s.name() + ":EVENTS", "EVENTS");
            r.edge(raw, par, EdgeKind.FLOW);
            r.edge(par, ev, EdgeKind.FLOW);
            final String xs = r.node(s.key() + "_x", "XSLT", shortName + " Translation");
            r.edge(par, xs, EdgeKind.REFERENCE, null, "translationFilter");
            r.edge(xs, common, EdgeKind.REFERENCE, EdgeCertainty.INFERRED, null);
            r.edge(par, sch, EdgeKind.REFERENCE, null, "schemaFilter");
            r.edge(tmpl, par, EdgeKind.INHERITANCE);
            if (s.split()) {
                final String sp = r.node(s.key() + "_sp", "XSLT", shortName + " Splitter");
                r.edge(par, sp, EdgeKind.REFERENCE, null, "splitFilter");
            }
            if (s.http() != null) {
                final String ep = r.node(s.key() + "_ep", "Endpoint", s.http().replaceFirst("^https?://", ""));
                r.edge(xs, ep, EdgeKind.HTTP, EdgeCertainty.INFERRED, null);
            }
            // One chain is a stage longer than the others.
            String tail = ev;
            if (s.extraStage()) {
                final String norm = r.pipeline(s.key() + "_np", "Proxy Normaliser", 4);
                final String nf = r.feed(s.key() + "_nf", s.name() + ":NORMALISED", "EVENTS");
                r.edge(ev, norm, EdgeKind.FLOW);
                r.edge(norm, nf, EdgeKind.FLOW);
                final String nx = r.node(s.key() + "_nx", "XSLT", "Proxy Normalisation");
                r.edge(norm, nx, EdgeKind.REFERENCE, null, "translationFilter");
                tail = nf;
            }
            if (!s.noIndex()) {
                final String ip = r.pipeline(s.key() + "_ip", "Event Indexing", 5);
                r.edge(tail, ip, EdgeKind.FLOW);
                r.edge(ip, idx, EdgeKind.FLOW);
                r.edge(ip, map, EdgeKind.REFERENCE, null, "indexTranslation");
            }
        }
        // Branch off one event feed into the store.
        final String sp = r.pipeline("sp", "Event Store Loader", 3);
        r.edge("fw_ev", sp, EdgeKind.FLOW);
        r.edge(sp, store, EdgeKind.FLOW);
        // Consumers.
        final List<String> dashboards = List.of("Firewall Overview", "Threat Hunting", "Network Summary");
        for (int i = 0; i < dashboards.size(); i++) {
            final String d = r.node("d" + i, "Dashboard", dashboards.get(i));
            r.edge(i == 2
                    ? store
                    : idx, d, EdgeKind.QUERY);
        }
        r.truncation(new GraphTruncation(idx, EdgeKind.QUERY, "Dashboard", 9));
        // The shared indexing pipeline document, referenced but not on any chain.
        r.edge(idxPipe, map, EdgeKind.REFERENCE);
        return r.build();
    }

    private static DependencyGraph wide(final int lanes, final int perLane) {
        final Builder r = new Builder();
        final String idx = r.node("idx", "Index", "Central Index");
        final String sch = r.node("sch", "XMLSchema", "event-logging 3.5.2");
        for (int i = 0; i < lanes; i++) {
            final String source = "SOURCE-" + pad2(i + 1);
            final String raw = r.node("r" + i, "Feed", source + ":RAW_EVENTS");
            final String par = r.pipeline("p" + i, "Parser " + (i + 1), 6);
            final String ev = r.node("e" + i, "Feed", source + ":EVENTS");
            r.edge(raw, par, EdgeKind.FLOW);
            r.edge(par, ev, EdgeKind.FLOW);
            for (int j = 0; j < perLane; j++) {
                final String x = r.node("x" + i + "_" + j, "XSLT", "Translation " + (i + 1) + "." + (j + 1));
                r.edge(par, x, EdgeKind.REFERENCE);
            }
            r.edge(par, sch, EdgeKind.REFERENCE);
            final String ip = r.pipeline("i" + i, "Indexing", 5);
            r.edge(ev, ip, EdgeKind.FLOW);
            r.edge(ip, idx, EdgeKind.FLOW);
        }
        final String d = r.node("d", "Dashboard", "Everything");
        r.edge(idx, d, EdgeKind.QUERY);
        return r.build();
    }

    private static DependencyGraph library() {
        final Builder r = new Builder();
        final String base = r.pipeline("b", "Event Parser Template", 6);
        for (int i = 0; i < 5; i++) {
            final String c = r.pipeline("c" + i, "Child Parser " + (i + 1), 7);
            r.edge(base, c, EdgeKind.INHERITANCE);
            final String x = r.node("cx" + i, "XSLT", "Shared Fragment " + (i + 1));
            r.edge(c, x, EdgeKind.REFERENCE);
        }
        return r.build();
    }

    private static DependencyGraph cycle() {
        final Builder r = new Builder();
        final String a = r.node("a", "Feed", "LOOP:RAW_EVENTS");
        final String p = r.pipeline("p", "Reprocessor", 5);
        final String b = r.node("b", "Feed", "LOOP:EVENTS");
        final String p2 = r.pipeline("p2", "Re-reprocessor", 4);
        r.edge(a, p, EdgeKind.FLOW);
        r.edge(p, b, EdgeKind.FLOW);
        r.edge(b, p2, EdgeKind.FLOW);
        // The back edge.
        r.edge(p2, a, EdgeKind.FLOW);
        final String x = r.node("x", "XSLT", "Loop Translation");
        r.edge(p, x, EdgeKind.REFERENCE);
        return r.build();
    }

    private static DependencyGraph single() {
        final Builder r = new Builder();
        r.node("only", "XSLT", "Orphan Translation");
        return r.build();
    }

    private static DependencyGraph empty() {
        return new Builder().build();
    }

    private static DependencyGraph deepChain() {
        final Builder r = new Builder();
        String prev = r.node("f0", "Feed", "STAGE-00:RAW_EVENTS");
        for (int i = 1; i <= 7; i++) {
            final String p = r.pipeline("p" + i, "Stage " + i, 4 + (i % 3));
            final String f = r.node("f" + i, "Feed", "STAGE-" + pad2(i) + ":EVENTS");
            r.edge(prev, p, EdgeKind.FLOW);
            r.edge(p, f, EdgeKind.FLOW);
            final String x = r.node("x" + i, "XSLT", "Stage " + i + " Translation");
            r.edge(p, x, EdgeKind.REFERENCE);
            prev = f;
        }
        final String idx = r.node("idx", "Index", "Deep Index");
        r.edge(prev, idx, EdgeKind.FLOW);
        return r.build();
    }

    // Search extraction, the read path. A dashboard queries an index, then the original events are re-read
    // and run through an extraction pipeline. Configured as dashboard to index and dashboard to pipeline,
    // but drawn the way the data goes.
    private static DependencyGraph extraction() {
        final Builder r = new Builder();
        final String idx = r.node("idx", "Index", "Network Events Index");
        final String sch = r.node("sch", "XMLSchema", "event-logging 3.5.2");
        final String ext = r.pipeline("ext", "Search Extraction", 4);
        final String extx = r.node("extx", "XSLT", "Extraction Decoration");
        r.edge(ext, extx, EdgeKind.REFERENCE, null, "extractionFilter");
        for (final String nm : List.of("FW-LOGS", "IDS-ALERTS")) {
            final String shortName = nm.split("-")[0];
            final String raw = r.node(nm + "_raw", "Feed", nm + ":RAW_EVENTS");
            final String par = r.pipeline(nm + "_par", shortName + " Parser", 6);
            final String ev = r.node(nm + "_ev", "Feed", nm + ":EVENTS");
            r.edge(raw, par, EdgeKind.FLOW);
            r.edge(par, ev, EdgeKind.FLOW);
            final String x = r.node(nm + "_x", "XSLT", shortName + " Translation");
            r.edge(par, x, EdgeKind.REFERENCE);
            r.edge(par, sch, EdgeKind.REFERENCE);
            final String ip = r.pipeline(nm + "_ip", "Event Indexing", 5);
            r.edge(ev, ip, EdgeKind.FLOW);
            r.edge(ip, idx, EdgeKind.FLOW);
        }
        // Index to extraction to dashboard: the extraction pipeline sits on the read path.
        r.edge(idx, ext, EdgeKind.QUERY);
        final List<String> dashboards = List.of("Threat Hunting", "Analyst Workbench");
        for (int i = 0; i < dashboards.size(); i++) {
            final String d = r.node("d" + i, "Dashboard", dashboards.get(i));
            r.edge(ext, d, EdgeKind.QUERY);
        }
        // A dashboard with no extraction pipeline reads the index directly.
        final String plain = r.node("dp", "Dashboard", "Volume Summary");
        r.edge(idx, plain, EdgeKind.QUERY);
        return r.build();
    }

    // Dictionaries go both ways: a pipeline writes one, an expression term reads one by reference, and an
    // XSLT reads one by name.
    private static DependencyGraph dictionary() {
        final Builder r = new Builder();
        final String raw = r.node("raw", "Feed", "WATCHLIST:RAW_EVENTS");
        final String build = r.pipeline("build", "Watchlist Builder", 5);
        final String dict = r.node("dict", "Dictionary", "Known Bad Hosts");
        r.edge(raw, build, EdgeKind.FLOW);
        // Written.
        r.edge(build, dict, EdgeKind.FLOW, null, "dictionaryAppender");
        final String parent = r.node("pdict", "Dictionary", "Corporate Allowlist");
        // A dictionary import.
        r.edge(parent, dict, EdgeKind.REFERENCE);
        final String ev = r.node("ev", "Feed", "NET:RAW_EVENTS");
        final String par = r.pipeline("par", "Net Parser", 6);
        final String out = r.node("out", "Feed", "NET:EVENTS");
        r.edge(ev, par, EdgeKind.FLOW);
        r.edge(par, out, EdgeKind.FLOW);
        final String x = r.node("x", "XSLT", "Net Translation");
        r.edge(par, x, EdgeKind.REFERENCE);
        // Read by name in an XSLT.
        r.edge(dict, x, EdgeKind.LOOKUP, EdgeCertainty.INFERRED, null);
        final String idx = r.node("idx", "Index", "Net Index");
        final String ip = r.pipeline("ip", "Indexing", 4);
        r.edge(out, ip, EdgeKind.FLOW);
        r.edge(ip, idx, EdgeKind.FLOW);
        // Read by an expression term.
        r.edge(dict, ip, EdgeKind.REFERENCE);
        final String d = r.node("d", "Dashboard", "Watchlist Hits");
        r.edge(idx, d, EdgeKind.QUERY);
        return r.build();
    }

    private static DependencyGraph fanIn() {
        final Builder r = new Builder();
        final String idx = r.node("idx", "Index", "Convergence Index");
        for (int i = 0; i < 8; i++) {
            final String f = r.node("f" + i, "Feed", "SRC-" + i + ":EVENTS");
            final String p = r.pipeline("p" + i, "Indexer " + i, 4);
            r.edge(f, p, EdgeKind.FLOW);
            r.edge(p, idx, EdgeKind.FLOW);
        }
        for (int i = 0; i < 6; i++) {
            final String d = r.node("d" + i, "Dashboard", "View " + i);
            r.edge(idx, d, EdgeKind.QUERY);
        }
        return r.build();
    }

    private static String pad2(final int value) {
        return value < 10
                ? "0" + value
                : String.valueOf(value);
    }

    /// A named scenario.
    ///
    /// @param id    A short id, e.g. `events`, which names its reference layouts.
    /// @param name  What it shows.
    /// @param graph Builds a fresh copy of the graph, with uuids starting again from `u0`.
    public record Scenario(String id, String name, Supplier<DependencyGraph> graph) {

        public DependencyGraph build() {
            return graph.get();
        }

        @Override
        public String toString() {
            return id;
        }
    }

    // One source chain of the event-sources scenario.
    private record Source(String key,
                          String name,
                          boolean split,
                          String http,
                          boolean noIndex,
                          boolean extraStage) {

    }

    // Builds a graph as the harness's mkResp(), node() and edge() do.
    private static final class Builder {

        private final List<GraphNode> nodes = new ArrayList<>();
        private final List<GraphEdge> edges = new ArrayList<>();
        private final List<GraphTruncation> truncations = new ArrayList<>();
        private int uid = 0;

        String node(final String id, final String type, final String name) {
            return add(id, type, name, null, null);
        }

        String feed(final String id, final String name, final String streamType) {
            return add(id, "Feed", name, streamType, null);
        }

        String pipeline(final String id, final String name, final int elementCount) {
            return add(id, "Pipeline", name, null, elementCount);
        }

        void edge(final String from, final String to, final EdgeKind kind) {
            edge(from, to, kind, null, null);
        }

        void edge(final String from,
                  final String to,
                  final EdgeKind kind,
                  final EdgeCertainty certainty,
                  final String element) {
            final EdgeCertainty actualCertainty = certainty == null
                    ? EdgeCertainty.CONFIGURED
                    : certainty;
            edges.add(new GraphEdge(from, to, kind, actualCertainty, element, null));
        }

        void truncation(final GraphTruncation truncation) {
            truncations.add(truncation);
        }

        public DependencyGraph build() {
            return new DependencyGraph(null, nodes, edges, truncations, null, null, null);
        }

        private String add(final String id,
                           final String type,
                           final String name,
                           final String streamType,
                           final Integer elementCount) {
            final NodeRole role;
            if ("Endpoint".equals(type)) {
                role = NodeRole.EXTERNAL;
            } else if (FLOW_TYPES.contains(type)) {
                role = NodeRole.FLOW;
            } else {
                role = NodeRole.REFERENCE;
            }
            final DocRef docRef = new DocRef(type, "u" + uid++, name);
            nodes.add(new GraphNode(id, docRef, role, streamType, true, null, elementCount, null));
            return id;
        }
    }
}
