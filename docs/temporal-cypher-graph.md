# A Temporal Cypher Graph on Stroom

**Status:** Design proposal / feasibility study
**Audience:** Stroom engineering team
**Scope:** Architecture and effort estimate for a property-graph database, queried with Cypher, with native point-in-time (temporal) querying, built on Stroom's existing data stores.
**Companion build plan:** [`temporal-cypher-graph-implementation-plan.md`](temporal-cypher-graph-implementation-plan.md) — the agent-ready, task-by-task implementation plan (verified repo facts, module layout, fully-specified P0 spikes, a file/signature-level PoC, and contract-level outlines for P1–P8). This document is the *design* (what & why); that one is the *build plan* (which files, what signatures, how to prove done).

---

## 1. Executive summary

We can build a **temporal property-graph database** on top of Stroom without inventing a new storage engine. The core idea most graph databases share — *"a graph is an adjacency index over a key/value store"* — maps almost directly onto **Plan B**, Stroom's LMDB-backed state store. Plan B already provides the three primitives a graph engine needs from its KV layer:

1. **Ordered keys with range/prefix scans** — the basis of adjacency lists (`source → its neighbours`).
2. **Native point-in-time ("as-of") lookups** — Plan B's temporal state already does a reverse-cursor "floor" lookup to find a value effective *at or before* a timestamp. This is exactly what "query the graph as it was at time *T*" requires, and it is the single biggest reason this project is tractable.
3. **Identifier interning** — a bidirectional string↔compact-UID store, used to keep adjacency keys small and traversal fast.

The **recommended stack**:

| Concern | Recommendation |
|---|---|
| Packaging | **A single `GraphDbDoc` document type** that *encapsulates and owns* every physical store the graph needs (see §5.1). The user creates one "Graph" document; the internal Plan B temporal stores, interning, and anchor index are **hidden implementation detail**, never separately created or wired. This deliberately shrinks the user's configuration surface to the genuine choices, so the graph is hard to misconfigure. |
| Storage substrate | **Plan B (LMDB)**, owned internally by the `GraphDbDoc`, as the primary store; an internal anchor index (Plan B `STATE` sub-store or a wrapped **Lucene** index) for "find the anchor node". Both are internal to the doc. |
| Query language | **Cypher**, parsed from the open-source **openCypher ANTLR grammar**, sitting alongside `StroomQL.g4` in the shared grammar module — not a bespoke hand-written parser. |
| Query engine | **Build on the planned query core** — grammar + ANTLR parser + relational logical plan + planner + joins (see the companion [query-optimiser-plan.md](query-optimiser-plan.md)). Cypher compiles to the **same logical IR**; graph traversal is an **index-nested-loop join** (`expand`) over adjacency; a **Graph datasource + `SearchProvider`** executes the plan and streams rows into the existing coprocessor / result-store / dashboard / REST plumbing. |
| Temporal model | **Single-axis valid time** first (`AS OF t`, `AROUND t ± d`, `BETWEEN t1 AND t2`), matching Plan B's temporal state exactly. Bitemporal deferred. |
| Ingest | Reuse **pipelines + XSLT + a new `GraphFilter`** modelled on the existing `PlanBFilter`. Raw events remain the rebuildable source of truth. |

**Effort (independently estimated and cross-checked against the codebase):**

- **Stage 1 is the reusable query core** — grammar + ANTLR parser + relational logical plan + planner + joins — delivered by the companion [query-optimiser-plan.md](query-optimiser-plan.md). It is a *separately-scoped project with its own estimate*; the graph inherits it, and StroomQL benefits too. The figures below are the **incremental graph work on top of that core**.
- **Graph PoC on the core** (single shard, Cypher → shared IR, `expand` over Plan B adjacency, `MATCH/WHERE/RETURN` single-hop + `AS OF`, Graph datasource, tabular output): **≈ 15–30 person-weeks (~2–3 months)**.
- **Graph feature v1** (ingest, both traversal directions, variable-length paths, temporal ranges, permissions, basic UI): loaded incremental figure **≈ 75–170 person-weeks**, **≈ 8–14 months calendar** with a **3-engineer** team.
- With the relational core in place, the biggest remaining risks are the **variable-length-path / fixpoint operator** and **cross-shard traversal** — the latter now modelled cleanly as a **distributed join / exchange** rather than bespoke scatter/gather. Storage still enjoys a large reuse discount.

---

## 2. Problem & goals

### Goals

- A **labelled property graph** (nodes and edges, each with labels/types and arbitrary properties).
- A **Cypher** query front end (the de-facto graph query language; the target of the openCypher standard).
- **Native temporal support**: ask for the state of the graph *at* a point in time, or *around* / *between* points in time — not just "latest".
- **Maximum reuse of existing Stroom features** — storage, ingest, query result plumbing, security, UI patterns.

### Non-goals (for v1)

- Not a general-purpose, high-write-throughput OLTP graph database. The graph is a **materialized, rebuildable projection** of event data that already lives in Stroom's stream store.
- Not full openCypher coverage on day one — a defined subset (see §8).
- Not bitemporal (separate transaction-time and valid-time axes) initially — single-axis valid time first.
- Not an interactive time-scrubbed graph visualisation for MVP — tabular results first, rich visualisation as a stretch.
- Domain types are **advisory** — the graph *uses* them (§5.6) but never *requires* one; a graph whose fields carry no domain type is built and queried identically.

---

## 3. Primer: a graph on a key/value store

For readers new to graph-database internals, the standard recipe is:

- **Nodes and edges become KV entries.** A node is `nodeId → {labels, properties}`. An edge is stored so that *finding a node's neighbours is a range scan*.
- **Adjacency via ordered keys.** If edge keys are laid out as `(sourceId, edgeType, targetId)` in a store that keeps keys in sorted order, then *all out-edges of a node* are a contiguous key range — a single **prefix scan**. Traversing a hop is "seek + scan", which is cheap. Multi-hop traversal is nested scans.
- **Interning.** Real identifiers (UUID strings, labels, property names) are long. Graph engines **intern** them into small integers so adjacency keys stay compact and comparisons are fast. This needs a bidirectional `string ↔ uid` map.
- **Secondary indexes.** To *start* a query (`MATCH (p:Person {email: "..."})`) you need to find anchor nodes by property value — a secondary index (either another KV index or a full-text index).
- **Temporal ("as-of").** A temporal graph stamps every node/edge version with a validity time. "As of *T*" means: for each entity, find the **most recent version at or before *T*** — a *floor* lookup. In an ordered KV store this is a reverse cursor from key `(entity, T)`.

Every one of these primitives already exists in Stroom. That is the whole thesis of this document.

---

## 4. What Stroom already gives us

All paths are relative to the repo root. These are the load-bearing reuse points; each was verified against the source.

| Graph-engine need | Provided by | Where |
|---|---|---|
| Ordered keys + range/prefix scans | Plan B / LMDB byte-ordered keys, cursor iteration | [`TemporalStateDb.java`](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/temporalstate/TemporalStateDb.java) (`iterate(...)`, `LmdbKeyRange`) |
| **Point-in-time "as-of" floor lookup** | Plan B temporal state — reverse cursor from `(key, time)` | `TemporalStateDb.getState(...)` → `LmdbKeyRange.builder().start(key).reverse().build()` |
| State-type palette (temporal + ranged) | 8 state types incl. `TEMPORAL_STATE`, `TEMPORAL_RANGED_STATE`, `TRACE` | [`StateType.java`](../stroom-core-shared/src/main/java/stroom/planb/shared/StateType.java) |
| Identifier interning (string ↔ compact UID) | Bidirectional `keyToUid` / `uidToKey` DBs; plus a hash-dedup DB | [`UidLookupDb.java`](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/dao/UidLookupDb.java), `HashLookupDb.java` |
| Ingest into the KV store from a pipeline | SAX filter that parses `reference-data:2` XML and writes KV entries | [`PlanBFilter.java`](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/pipeline/PlanBFilter.java) |
| A **graph-shaped precedent** on Plan B | `TRACE` state type stores OpenTelemetry spans (parent/child + `SpanLink`) with its own `search()` | `TraceDb.java` (`stroom-planb/.../dao/trace/`); [`SpanLink.java`](../stroom-core-shared/src/main/java/stroom/pathways/shared/otel/trace/SpanLink.java) |
| Queryable data-source contract | `SearchProvider` (executable) / `Searchable` (simplest) | [`SearchProvider.java`](../stroom-query/stroom-query-common/src/main/java/stroom/query/common/v2/SearchProvider.java), [`Searchable.java`](../stroom-search/stroom-searchable-api/src/main/java/stroom/searchable/api/Searchable.java) |
| A copyable provider (~300 LOC) | `StateSearchProvider` — the cleanest full provider template | [`StateSearchProvider.java`](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/StateSearchProvider.java) |
| One-line registration + doc-type wiring | Guice multibinder | [`PlanBModule.java`](../stroom-planb/stroom-planb-impl/src/main/java/stroom/planb/impl/PlanBModule.java) |
| Language-agnostic query interchange | Everything below `SearchRequest` is query-language-neutral | [`SearchRequest.java`](../stroom-query/stroom-query-api/src/main/java/stroom/query/api/SearchRequest.java) |
| Result plumbing (aggregation, paging, incremental) | Coprocessors → result store → `SearchResponse` → dashboards/REST | `stroom-query/stroom-query-common/.../v2/` |
| Anchor lookup by property / full text | Lucene index (term + range queries) | `stroom-index`, `stroom-search` |
| Security, doc-refs, explorer, retention | Document permissions; `condense` / `deleteOldData` retention | `stroom-security`, Plan B DAOs |

**The takeaway:** every hard KV primitive already exists. The genuinely new work is a graph *schema* over those primitives, a Cypher *engine*, and the glue to expose a "Graph" document type. The storage and plumbing layers are largely reuse; the Cypher engine is greenfield.

---

## 5. Architecture

**The `GraphDbDoc` wrapper.** A graph is a single explorer document — a `GraphDbDoc` — that *owns and encapsulates*
every physical store it needs. Rather than asking a user to create a Plan B store, an anchor index, and wire them
together (each an opportunity to get it wrong), the user creates one **Graph** document and the system provisions
and manages the internal stores as a hidden unit:

- **Owned internally (hidden):** the Plan B temporal sub-stores (§5.1 — node, out/in adjacency, interning) *and*
  the anchor index (a Plan B `STATE` sub-store or a wrapped Lucene index). None of these appear in the explorer or
  are separately configurable; they are created, opened, rebuilt (on reprocess), retained/condensed, and deleted
  **together, as part of the `GraphDbDoc`'s lifecycle**.
- **User-configurable (the genuine choices only):** name/description (standard doc metadata); a retention /
  temporal-precision policy; and the node/edge **schema mapping** — which fields become which nodes/edges/labels
  (this can be derived from the domain-type catalogue, §5.6, or set on the ingest pipeline, so even this is often
  zero-config). Everything physical — byte layouts, interning scheme, in/out-edge stores, anchor-index technology,
  sharding, snapshot/merge — is an internal default, not a knob.

The result: the configuration a user *can* do is limited to decisions they *must* make, so a graph is hard to
misconfigure. The sub-stores below are the encapsulated internals of that one document, not things a user assembles.

### 5.1 Physical graph model (internal to the `GraphDbDoc`)

The graph is composed of several LMDB sub-stores, exactly as Plan B already composes `UidLookupDb` + data DBs today.
**All of these are owned by the `GraphDbDoc` and hidden from the user** — they are the doc's implementation, opened
and managed as a unit under its lifecycle.

- **Interning DBs** (`UidLookupDb`): four UID namespaces — node external id, label, edge-type, property-key — mapping strings ↔ compact UIDs.
- **Node store** (temporal state): `nodeUid → {labels, properties}`, time-stamped so node property *history* is an as-of lookup.
- **Out-edge adjacency** (temporal): key `(srcUid, edgeTypeUid, validFrom, dstUid) → edgeProps`. A **prefix scan on `srcUid`** (optionally `+ edgeTypeUid`) yields all out-neighbours; the time component + reverse cursor gives the set **as of T**.
- **In-edge adjacency** (mirror): key `(dstUid, edgeTypeUid, validFrom, srcUid) → edgeProps`, for reverse / incoming / undirected traversal.
- **Property-value index**: `(labelUid, propKeyUid, value) → nodeUid` — a Plan B `STATE` sub-store (the P0.1 spike chose this over a Lucene index, so it stays under the doc's single LMDB env / lifecycle) used to find anchor nodes.

Key layout — **frozen by the P0.1 spike** (byte-level widths in the [implementation plan](temporal-cypher-graph-implementation-plan.md)'s Frozen-model box, §3 Task 0.1):

```
Node:      [nodeUid][validFrom]                         -> {labelsBitset, propsBlob | tombstone}
Out-edge:  [srcUid][edgeTypeUid][dstUid][validFrom]     -> edgeProps | tombstone
In-edge:   [dstUid][edgeTypeUid][srcUid][validFrom]     -> edgeProps | tombstone
PropIndex: [labelUid][propKeyUid][valueBytes]           -> nodeUid
```

Two things the spike fixed that the conceptual sketch left open. **(1) Fixed-width UIDs** (via `UidLookupDb` + a static unsigned-bytes factory): `UidLookupDb`'s default *variable* width would silently break the composite-key prefix scans once a namespace grew past a width boundary. **(2) `validFrom` last, with `dst` before it** (refining the earlier `…[validFrom][dst]` sketch): putting `dst` ahead of `validFrom` keeps each edge's version history a contiguous, floor-lookable run — so an edge's state *as of T* is the very same single reverse-cursor lookup Plan B's `TemporalStateDb.getState` already performs, and a version's validity-interval end is just the adjacent key. Numeric components (UIDs; `validFrom` via 6-byte `MillisecondTimeSerde`) are **big-endian / order-preserving**, so LMDB's byte order matches logical order — the technique Plan B's temporal key serdes already use. `TraceDb` / `SpanKeySerde` is the working fixed-layout composite-key precedent.

### 5.2 Ingest path

Reuse the pipeline framework end-to-end:

```
raw events → feed/stream → XSLT → graph-mutation XML → GraphFilter → Plan B graph store
                                                                          ↑
                        raw streams remain the rebuildable source of truth
```

1. Events land in feeds/streams (unchanged).
2. An **XSLT** transforms events into **graph-mutation XML** — a small new schema of node/edge upserts carrying a `validFrom` time (or a convention layered on `reference-data:2`).
3. A new **`GraphFilter`** (a SAX filter modelled on `PlanBFilter`) consumes that XML and writes node, out-edge, in-edge, interning, and property-index entries — each stamped with effective time.

Because the graph is a **materialized projection**, Stroom's existing reprocessing lets us rebuild it by re-running the pipeline over stored streams — important for schema changes and backfills.

### 5.3 Query integration

Stroom's *current* single-datasource query model (`Query` carries one `DocRef`; `Query.expression` is a boolean `ExpressionOperator` tree; results are tabular) cannot express Cypher's node–edge–node paths. Rather than work around that with an opaque provider, the graph builds on the **relational query core** introduced by the companion [query-optimiser-plan.md](query-optimiser-plan.md), which adds a grammar-driven parser, a **logical plan IR with a `Join` operator**, a planner, and **equi-joins across datasources**.

The graph integration is then a clean fit:

- **Cypher is a second front-end** — a `Cypher.g4` grammar alongside `StroomQL.g4` in the shared `stroom-query-grammar` module, compiled to the **same logical IR**.
- **A hop is a join.** `MATCH (a)-[r]->(b)` lowers to a join of the edge (adjacency) relation to the node relation; physically it runs as the core's **index-nested-loop / broadcast-lookup** join, with the adjacency prefix-scan as the access path. "Native traversal" and "join execution" are the same plan.
- **The `GraphDbDoc` is a new datasource** — the *document* is what you query (`Query.dataSource` is the `GraphDbDoc`'s `DocRef`), exposing node/edge/adjacency relations (with field metadata + cost signals). Its `GraphSearchProvider` resolves the doc and traverses its **internal** stores; the wrapped Plan B/anchor-index stores are never addressed directly by a query. Registration is one Guice multibinder line, as `PlanBModule` does today.

Everything downstream is reused unchanged: coprocessors, result stores, incremental/paged results, dashboards, query REST, and document-level security. A **"Graph" document type** makes a graph queryable like any other source; provider registration is one Guice multibinder line (as `PlanBModule` does today).

### 5.4 Temporal semantics

Cypher has no standard as-of syntax, so we add a small, resolved-before-execution extension:

- `AS OF <timestamp>` — one snapshot instant applied to every Plan B floor lookup and adjacency filter (`MATCH ... AS OF ...`).
- `AROUND <timestamp> ± <duration>` / `BETWEEN <t1> AND <t2>` — a **bounded window scan** returning versions/edges whose validity interval intersects the window (P0.3 fixes the exact intersection rule below). This is the *"at or around a point in time"* requirement.
- No clause → latest.

**Semantics frozen by the P0.3 spike** (worked examples in the [implementation plan](temporal-cypher-graph-implementation-plan.md)'s §3 Task 0.3 outcome). A version has a half-open validity interval `[validFrom, nextValidFrom)`. `AS OF t` is applied **per-edge** — every hop is floor-looked-up at the *same* instant *t*, so the result is "the graph as it existed at *t*", and a path is returned iff every edge and node on it was valid at *t* (per-path as-of is rejected as ambiguous for v1). For windows, bounds are **inclusive** and a version intersects `[w1, w2]` iff `validFrom ≤ w2 AND nextValidFrom > w1`; a path is returned iff every edge has a version intersecting the window (the versions need not be simultaneous). Tombstone versions (from a supersede/delete) mean "absent" and are never emitted.

Model choice: **single-axis valid time** first (each node/edge version carries `validFrom`; a supersede/delete writes a new version). This matches Plan B's temporal state 1:1, and Plan B's existing `condense` / `deleteOldData` handle retention of old versions. Bitemporal (separate transaction time) is a later extension, not a v1 requirement.

### 5.5 Execution engine

Execution reuses the query core's Volcano-style operator pipeline (scan / filter / **join** / aggregate / project); the graph adds three graph-specific operators over Plan B cursors:

1. Resolve the temporal context (`AS OF T`) once.
2. **Anchor scan** via the property/Lucene index (a normal `Scan` access path).
3. **`expand`** — the index-nested-loop join for a hop: **prefix-scan** the adjacency DB by `srcUid` (+ edge type), floor/window-filter by *T*, dereference neighbour nodes, apply predicates. (New adjacency *access path*; the join operator itself is the core's.)
4. **Variable-length paths** (`-[:REL*1..3]->`): a **fixpoint / bounded-transitive-closure** operator (BFS/DFS with a visited-set cycle guard). This is the one operator the equi-join core does **not** already provide, and the main net-new execution piece.
5. **As-of join** for temporal: a point-in-time lookup join that reuses the core's broadcast-lookup-to-State path and Plan B's floor lookup (via `GetState`/`StateProvider`). *Distinct from* the windowed stream-correlation joins the core explicitly defers.
6. Project `RETURN` columns into `Val[]` rows → coprocessors; aggregation reuses the core's `Aggregate` + `Val` functions.

### 5.6 Domain-type integration (semantic layer)

Stroom already carries a **semantic type** on fields and columns — `domainType`, a `class.attribute` string such as `Host.ipaddress` or `User.id`, with single-segment `*` wildcards and a matcher `DomainType.canAccept` ([`DomainType.java`](../stroom-core-shared/src/main/java/stroom/domaintype/shared/DomainType.java); catalogue `DomainTypeDoc`). It says what a value *means*, above the physical `FieldType`. Today it is **inert for execution** (only the client "Jump to…" navigation and `DashboardStoreImpl.findByType` consume it); the query optimiser is its first execution-side consumer — see that plan's [Domain types](query-optimiser-plan.md#domain-types-semantic-layer) section — and the graph is a natural second.

A graph is a graph *of entities*, and a domain type is exactly an entity identity, so the fit is unusually direct. As in the optimiser, every use is **advisory and must degrade gracefully**: a graph whose fields carry no domain type is built and queried identically.

- **Catalogue-driven node/edge mapping (ingest).** The `class` part → a node **label**; the `attribute` part → the identifying **property**. A field tagged `User.id` becomes a `:User {id}` node, `Host.ipaddress` a `:Host {ipaddress}` node, and their co-occurrence in an event an edge. The `GraphFilter`/XSLT mapping can be *derived from* the `DomainTypeDoc` catalogue rather than hand-written per feed.
- **Entity resolution across sources.** Two feeds carrying the same domain type — even under differently-named physical fields (`src_ip: Host.ipaddress` and `ipAddress: *.ipaddress`) — denote the **same node**. `canAccept` wildcard matching unifies them into one node identity, so heterogeneous events form one *connected* graph instead of islands. This reuses the optimiser's **enrichment-source routing** index (the generalisation of `DashboardDoc.domainTypes` → `findByType`) to discover which store answers for a typed key.
- **Node-key canonicalisation.** The value-domain problem the optimiser solves for join keys (`192.168.0.1` vs `3232235521`; MACs with/without colons) is **node identity** for a graph: where a domain type owns a canonical form, the ingest/lookup path normalises the key so the same entity resolves to one node. A correctness enabler, not just tidiness — reuse the optimiser's canonicalising rewrite.
- **Typed Cypher anchors + navigation.** An anchor `MATCH (u:User {id: …})` resolves to `User.id`, letting the planner pick the right node store / property index — the graph analogue of the optimiser's push-down eligibility. The existing "Jump to…" navigation extends naturally to "jump from a dashboard cell into the graph view of this entity."

**Gap — relationships aren't in the catalogue.** `DomainType` models entities + attributes, not edges; node/property mapping is well-supported but **edge semantics need a design choice** (see Open questions). Matching stays deliberately blunt (two segments, one `*`, no subtyping) — strong enough to *confirm* an entity mapping, so inference always **confirms** rather than silently rewrites.

---

## 6. Worked example

**Question:** *"Which accounts did device `d-42` talk to within an hour of 2026-07-01T09:00Z, and who owned those accounts as of that instant?"*

Cypher (with the temporal extension):

```cypher
MATCH (d:Device {id: 'd-42'})-[c:CONNECTED_TO]->(a:Account)<-[:OWNS]-(o:Owner)
AROUND datetime('2026-07-01T09:00:00Z') ± duration('PT1H')
RETURN a.id, o.name, c.startTime
ORDER BY c.startTime
```

End-to-end trace:

1. **Parse** — openCypher grammar → AST; the `AROUND … ± …` clause is captured as a temporal window `[08:00Z, 10:00Z]` with snapshot instant `09:00Z`.
2. **Plan** — logical ops: anchor-scan `Device{id}`, expand `CONNECTED_TO` (window), expand-reverse `OWNS` (as-of), project.
3. **Anchor seek** — property index lookup `(Device, id, 'd-42') → nodeUid`.
4. **Expand `CONNECTED_TO`** — prefix-scan out-edge adjacency `[d42Uid][CONNECTED_TO…]`, keep edges whose validity intersects `[08:00Z,10:00Z]`.
5. **Expand `OWNS` (reverse, as-of 09:00Z)** — for each account, prefix-scan in-edge adjacency `[accountUid][OWNS…]`, floor-lookup the owner edge/version **at or before 09:00Z**.
6. **Project & sort** — emit `Val[]` rows `(a.id, o.name, c.startTime)` into a `TableCoprocessor`; the result store sorts and pages.
7. **Return** — the rows surface in a Stroom dashboard/query result exactly like any StroomQL query — same UI, same REST, same permissions.

This shows both temporal modes in one query: a **window** (`CONNECTED_TO … AROUND ± 1h`) and an **as-of floor lookup** (`OWNS` ownership *at* the instant).

---

## 7. What's reused vs. built new

**Reused (the bulk):** Plan B storage, temporal floor lookups, interning, sharding, snapshot/retention; pipelines, XSLT, processor filters, reprocessing; the entire query result stack (coprocessors, result stores, incremental results); dashboards and query REST; explorer, doc-refs, permissions; Lucene for anchor lookups.

**Built new (focused surface):**

1. A **`GraphDbDoc` document type that owns and encapsulates its internal stores** (§5.1) — provisioning, opening,
   rebuilding, retaining, and deleting them as a hidden unit; the user-facing surface is only the genuine choices.
2. Graph physical schema + order-preserving serdes on Plan B (node, out/in adjacency, property index) — the doc's
   hidden internals.
3. `GraphFilter` ingest element + a small graph-mutation XML schema, writing into the owning `GraphDbDoc`'s stores.
4. A `Cypher.g4` grammar + AST→IR visitor, alongside `StroomQL.g4` in the shared grammar module (the parser *framework*, planner, and join executor come from the query core).
5. Graph-specific operators on the core's executor: **`expand`** (adjacency access path for the index-nested-loop join), a **variable-length-path / fixpoint** operator, and an **as-of join** for temporal — plus a **graph datasource** (the `GraphDbDoc`) with cost signals.
6. `GraphSearchProvider` (resolves a `GraphDbDoc`, traverses its internal stores) + result mapping.
7. (Optional) graph-aware UI / visualisation.

---

## 8. Risks & hard problems

| Risk | Why it matters | Mitigation |
|---|---|---|
| **Model mismatch** (paths vs boolean/tabular) | Cypher paths can't be expressed in the *current* single-source `ExpressionOperator` model | **Resolved by building on the relational query core**: Cypher → shared logical IR; a hop is a join; results are tabular-native — no opaque-provider workaround needed |
| **Cross-shard traversal** | Plan B shards by key; a multi-hop path crossing shards is distributed work — still the largest scaling risk | Model it as a **distributed join / exchange** operator extending `FederatedSearchExecutor` (not bespoke scatter/gather); P0 partitioning spike must produce a *benchmarked* decision; ship single-shard first |
| **Cypher coverage creep** | openCypher is large; every extra clause compounds through parser → planner → executor → tests | Hard, written subset spec (`MATCH/WHERE/RETURN/WITH/ORDER/SKIP/LIMIT`, single + bounded var-length paths, basic aggregation); out-of-subset = explicit parse error from day one |
| **Traversal executor correctness/perf** | Var-length paths, cycle guards, predicate push-down interacting with LMDB cursor lifetimes/byte-buffer reuse are subtle | Prototype anchor-seek→expand→filter against real Plan B cursors in P0 |
| **Temporal semantics across multi-hop** | "As of" per-edge vs per-path, and interval window scans, are easy to get subtly wrong | Dedicated temporal spike with worked examples reviewed by a domain owner before coding |
| **Write amplification & storage growth** | In-edge mirror + property index + temporal versions multiply writes/disk | Measure amplification on synthetic data during the PoC; rely on `condense`/`deleteOldData` retention |
| **New ANTLR toolchain** | No ANTLR precedent today (StroomQL is hand-written) — new dependency + team skill | **Owned by the query-core project**, which introduces `stroom-query-grammar` + the ANTLR build integration; the graph reuses it and adds only `Cypher.g4` |
| **Graph visualisation scope** | Interactive time-scrubbed graph rendering is genuinely hard | Tabular/edge-list results for MVP; rich vis is an explicit stretch |

---

## 9. Effort estimate

Independently estimated and cross-checked against the codebase. Effort is in **person-weeks (pw)**, low–high, assuming **experienced Java engineers new to** LMDB/Plan B internals, ANTLR/Cypher, and GWT.

> **Baseline:** these figures assume the **query core** (grammar + ANTLR parser + relational logical plan + planner + joins) is delivered by the companion [query-optimiser-plan.md](query-optimiser-plan.md). That project is estimated separately; the WBS below is the **incremental graph work on top of it**. Where the core already provides a capability (parser framework, planner, join executor, expression eval, aggregation, result projection) the graph inherits it, and the corresponding line is reduced or removed versus a from-scratch graph engine.

### 9.1 Work-breakdown structure

| Phase / workstream | Scope | pw | Key skills |
|---|---|---|---|
| **P0 — Design & de-risking spikes** | | **6–13** | |
| 0.1 Physical model + partitioning spike | Freeze key/serde layout; confirm sharding scheme (scatter/gather risk) | 3–6 | LMDB/serde, Plan B internals |
| 0.2 Cypher scope + grammar spike | Import openCypher ANTLR grammar, generate parser, lock supported subset | 2–4 | ANTLR/parsers |
| 0.3 Temporal semantics spike | Map `AS OF` / `AROUND±d` / `BETWEEN` onto floor lookups + window scans | 1–3 | Temporal modelling |
| **P1 — Physical graph model on Plan B** | | **11–23** | |
| 1.1 Node store | `nodeUid→{labels,props}` on temporal-state pattern + serde | 2–4 | LMDB/serde |
| 1.2 Out-edge adjacency | Composite key serde; prefix + reverse cursor ops | 3–5 | LMDB/serde |
| 1.3 In-edge mirror | Reverse-direction mirror of 1.2 | 1–2 | LMDB/serde |
| 1.4 Interning integration | 4 UID namespaces via `UidLookupDb` + recorders | 2–4 | Plan B internals |
| 1.5 Property-value index | Anchor lookups (Plan B State **or** Lucene) | 2–5 | LMDB **or** Lucene |
| 1.6 Retention / condense / snapshot | Extend `deleteOldData`/`condense` to graph DBs | 1–3 | Plan B internals |
| **P2 — Ingest (GraphFilter)** | | **7–13** | |
| 2.1 Mutation XML schema + example XSLT | Node/edge upserts with valid-time | 1–2 | Stroom XSLT/schemas |
| 2.2 `GraphFilter` pipeline element | SAX filter modelled on `PlanBFilter` | 3–6 | Stroom pipeline internals |
| 2.3 Ingest wiring | Element module, `ShardWriters`, doc type | 1–2 | Stroom pipeline/Guice |
| 2.4 Ingest round-trip tests | XML→graph→read fixtures | 2–3 | Stroom test harness |
| **P3 — Graph query extensions (on the query core)** | | **9–18** | |
| 3.1 `Cypher.g4` grammar + AST→IR | Cypher grammar alongside `StroomQL.g4`; visitor to the shared logical IR | 2–4 | ANTLR/parsers |
| 3.2 Graph operators + planner rules | `expand` (adjacency access path for the core's join); anchor-selectivity / start-node rules | 2–4 | Query compilers + LMDB |
| 3.3 Variable-length path / fixpoint operator | Bounded transitive-closure BFS/DFS + cycle guards (net-new; not in the equi-join core) | 3–6 | Query execution |
| 3.4 Plan B graph datasource | Field metadata + cost signals (row/key counts) + adjacency access-path costing | 2–4 | Stroom query API + LMDB |
| *(inherited from core: parser framework, logical/physical planner, join executor, expression eval, aggregation, result projection)* | | *0* | — |
| **P4 — Temporal Cypher extension** | | **5–10** | |
| 4.1 Temporal syntax | `AS OF` / `AROUND±d` / `BETWEEN` grammar + AST | 1–2 | ANTLR/parsers |
| 4.2 As-of / window execution | As-of join reusing the core's State-lookup path + Plan B floor lookup; window scans (interval intersection over adjacency version runs, per P0.3) | 2–4 | LMDB/serde + engine |
| 4.3 Temporal correctness tests | As-of across multi-hop, interval edge cases | 2–4 | Test design |
| **P5 — Query integration** | | **5–10** | |
| 5.1 `GraphSearchProvider` | Copy `StateSearchProvider`; drive engine → coprocessors | 2–4 | Stroom search stack |
| 5.2 Registration + datasource fields | Multibinder; field/schema surfacing | 1–3 | Guice, query API |
| 5.3 "Graph" document type | Docstore/cache/resource + explorer handler | 2–3 | Docstore/explorer |
| **P6 — UI** | | **7–16** | |
| 6.1 Graph doc editor | GWT Presenter/View mirroring `planb/client` | 2–4 | GWT/GIN |
| 6.2 Cypher query editor | Dashboard pane accepting Cypher | 2–4 | GWT/UI |
| 6.3 Graph visualisation | Force-directed/temporal render (new JS vis); table fallback cheap | 3–8 | JS/D3 + GWT bridge |
| **P7 — Security / permissions** | | **3–6** | |
| 7.1 Document-level permissions | Inherit via explorer/`SecurityContext` (largely free) | 1–2 | Stroom security |
| 7.2 Query guardrails + label security | Traversal cost caps/timeouts/row limits; optional label filtering | 2–4 | Stroom security + engine |
| **P8 — Scale-out & hardening** | | **10–20** | |
| 8.1 Cross-shard scatter/gather | Distributed multi-hop traversal across shards | 5–10 | Distributed systems |
| 8.2 Write-amplification / growth mitigation | Batch writes, tune in-edge/prop-index cost | 2–4 | LMDB/serde |
| 8.3 Perf benchmarking + tuning | Cursor/buffer reuse, caching, benchmarking | 3–6 | Perf engineering |
| **Cross-cutting** | | **5–9** | |
| X.1 E2E tests + dataset generation | Ingest→query→temporal E2E; synthetic graph generator | 3–5 | Test engineering |
| X.2 Documentation | Cypher-subset reference, temporal syntax, ops guide | 2–4 | Tech writing |

### 9.2 Totals

- **Stage 1 — query core:** a separately-scoped project (see [query-optimiser-plan.md](query-optimiser-plan.md)); **not re-estimated here**. The graph inherits it (as does StroomQL).
- **Graph increment — raw sum of WBS:** ≈ **65–140 person-weeks** (down from ~80–160 for a from-scratch graph engine, reflecting the parser/planner/join reuse).
- **Graph increment — loaded** (+15–25% for integration friction, review, rework): ≈ **75–170 person-weeks**.
- **Calendar (3 engineers):** ≈ **8–14 months** for the full graph feature *on top of* the core (temporal + UI + scale-out).
- **Graph PoC on the core** (single shard, minimal storage + ingest, `MATCH/WHERE/RETURN` single-hop, `AS OF`, provider, table output): ≈ **15–30 pw / ~2–3 months**.

**Assumptions:** the query core (grammar + parser + planner + joins) is delivered first or in parallel and the figures above are *incremental* on it; 3 experienced Java engineers new to Plan B/LMDB, ANTLR/Cypher and GWT; single-axis valid time (bitemporal deferred); Cypher scoped to the subset above; single-shard for PoC with cross-shard (distributed join) as a later phase; property-index technology chosen in the P0 spike.

### 9.3 Team & critical path

- **Roles:** a storage/LMDB-serde engineer (heaviest in P0–P1); a query-engine/compiler engineer (shared with — or handing off from — the query-core project; owns the graph operators + temporal execution); a Stroom platform generalist (GraphFilter, provider, doc type, wiring — parallelisable); a 0.5 GWT/JS-viz engineer (P5); a fractional tech-lead/architect (owns the P0 spikes and reviews the operators + temporal semantics).
- **Dependency:** the query core (Stage 1) is a prerequisite for graph execution — build it first, or run it in parallel and integrate against its logical IR once the `Join` operator and single-source execution land.
- **Parallelisable:** ingest (once the model is frozen), UI (against a stub provider), docs and test tooling, and scale-out (a follow-on after the single-shard PoC).
- **Critical path:** P0 model + partitioning spike → storage DBs (node + adjacency + interning) → **graph operators (`expand` + variable-length) on the core** → temporal execution. Everything hinges on **freezing the physical model and partitioning scheme early**, and on the query core's logical IR being available.

---

## 10. Phased roadmap

0. **Design spikes (P0):** freeze the physical model + partitioning; align Cypher with the query-core grammar; define temporal semantics. *Gate: no storage code until the model is frozen.*
1. **Reusable query core** *(companion [query-optimiser-plan.md](query-optimiser-plan.md))*: grammar + ANTLR parser + logical IR (incl. `Join`) + rule/cost planner + equi-joins + EXPLAIN/estimate. The graph consumes this and StroomQL benefits too. *This is the recommended first engineering stage — it is graph-agnostic, independently valuable, and turns the graph work into a set of extensions.*
2. **Graph PoC on the core:** Cypher → shared IR; `expand` over Plan B adjacency; single shard; `MATCH/WHERE/RETURN` single-hop + `AS OF`; Graph datasource + provider; tabular output. *Prove a 2–3 hop temporal query end-to-end through a dashboard.*
3. **Ingest + traversal:** `GraphFilter`, both adjacency directions, the variable-length-path / fixpoint operator, richer predicates.
4. **Temporal ranges:** `AROUND`/`BETWEEN` via as-of join + window scans, retention/condense policy.
5. **UI + scale-out:** Graph document type UX, visualisation, cross-shard traversal as a **distributed join / exchange**; optional bitemporal.

---

## 11. Open questions / de-risking spikes

- **Plan B shard partitioning** — ***resolved in P0.1:*** v1 partitions **by graph id** (a `GraphDbDoc`'s stores fully co-located → zero cross-shard hops); scale-out hash-partitions the out-edge store by `srcUid` (in-edge by `dstUid`) so a node's out-adjacency is local to the expanding cursor. *The cross-shard-hop **benchmark number** is a scale-out (P8) input, measured then — not a v1 gate (v1 is single-shard).*
- **Property-index technology** — ***resolved in P0.1 (D3):*** a Plan B `STATE` sub-store (kept under the `GraphDbDoc`'s single LMDB env / lifecycle; equality/prefix anchors cover the v1 subset). A wrapped Lucene index is the documented escalation path if free-text / range anchoring is needed.
- **openCypher grammar** — ***resolved in P0.2 (D4):*** the openCypher project's reference ANTLR grammar (Apache-2.0), trimmed to the v1 subset, in the shared `stroom-query-grammar` module. *Pin the exact upstream commit and re-confirm the licence at import.*
- **Reference-data store** — confirm its effective-time semantics as an alternative/secondary temporal store (not on the critical path).
- **Relationship (edge) semantics in the catalogue** — `DomainType` models entities + attributes, not relationships; decide whether edges come from a convention (event co-occurrence) or a lightweight relationship-type extension to `DomainTypeDoc`. *Pairs with the domain-type integration (§5.6).*

  *Sketch — a relationship-type extension.* `DomainTypeDoc` today holds just a `List<DomainType>` (each a `class.attribute` entity type). Add a parallel, optional `List<RelationshipType>` — a tiny record `{ type, from, to, directed }` where `type` is the edge label (`OWNS`, `CONNECTED_TO`) and `from`/`to` are `DomainType`s naming the endpoint entity classes (wildcards allowed, matched with the existing `canAccept`). That is the whole extension: one additive JSON field following the existing `domainTypes` pattern — no new document, no new matcher. Concretely:
    - **Schema, not inference.** The relationship type declares which classes a label may connect; edges are still created by the ingest mapping (which fields play `from`/`to`). The catalogue *confirms* the edge is legal (mirroring the optimiser's "auto-inference confirms, never silently rewrites"); optionally it suggests edges from co-occurrence — the convention option, made explicit.
    - **Cypher planning.** The label in `MATCH (h:Host)-[:CONNECTED_TO]->(x)` resolves to a `RelationshipType`; the planner validates it (is `CONNECTED_TO` defined for `Host`?), can infer the omitted endpoint label, and picks the adjacency store — the graph analogue of semantic join-key validation.
    - **Direction & navigation.** `directed` selects out-only vs symmetric in/out adjacency (both already maintained); enumerating a class's relationship types drives "expand"/autocomplete and typed "Jump to…". Types are timeless; each edge *instance* still carries `validFrom`, so this is orthogonal to the temporal model.

  Deliberately blunt — a plain triple + direction, reusing `DomainType` matching; no rich relationship schema, no subtyping, and (like domain types) never required.

---

## 12. Glossary

- **Node / edge** — a graph vertex / a directed relationship between two nodes; both carry labels/types and properties.
- **Adjacency (list)** — the set of a node's edges, stored so neighbours are a contiguous key range (prefix scan).
- **Interning** — mapping long identifiers/strings to small integer UIDs (and back) to keep keys compact.
- **Anchor node** — the starting node(s) of a `MATCH`, found via a secondary index.
- **Valid time vs transaction time** — *valid time* is when a fact is true in the modelled world; *transaction time* is when the system recorded it. Bitemporal = both axes; we do valid time first.
- **As-of / floor lookup** — the most recent version at or before a timestamp; in an ordered KV store, a reverse cursor from `(entity, T)`.
- **Volcano model** — a query-execution style where each operator is an iterator pulling rows from its child.

---

*This document is a design proposal. The effort figures are estimates for planning, not commitments; the P0 spikes exist specifically to tighten them before the main build.*
