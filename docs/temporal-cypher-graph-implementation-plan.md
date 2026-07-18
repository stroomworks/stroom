# Implementation plan: temporal Cypher graph on Stroom

**Companion to** [`temporal-cypher-graph.md`](temporal-cypher-graph.md) (the *design* — what & why). This document is the
*build plan* — ordered, self-contained tasks for a coding agent to execute. Where the design doc explains a decision,
this doc says which files to touch, what signatures to write, and how to prove the task is done. It mirrors the
structure of [`query-optimiser-implementation-plan.md`](query-optimiser-implementation-plan.md), which delivered the
**query core this project builds on**.

Repo facts below were verified against the working tree on **2026-07-18** (branch `sw-graph-db`). Code drifts:
**before editing any file, re-read it and confirm the cited signature/line still holds** — line numbers are hints,
names are contracts.

---

## 0. How to use this document

- **Reader**: an autonomous coding agent (e.g. Sonnet). Each task is written to be picked up cold.
- **Task shape**: every task has *Goal · Depends on · Files · Contract · Done-when · Verify*. Do them in order within
  a phase; phases are gated.
- **Depth, and why it varies (read this).** This project has a hard structural constraint the optimiser did not:
  **the physical graph model is frozen by the P0 spikes, not by this document.** The design doc says so explicitly
  ("exact byte encoding is a P0 deliverable, not fixed here"; property-index technology and partitioning are P0
  spikes). Therefore:
  - **P0 (§3) is specified in full** — it is the one phase that can be, and everything else depends on its outputs.
  - **The PoC (§4) is specified to file/signature level** *as far as the design already fixes it*; wherever a task
    needs a P0 output (a byte layout, the chosen property-index tech, the openCypher grammar), that dependency is
    called out explicitly as **[P0-dep]** and the task says what to do once P0 delivers it. An agent runs P0 first,
    records its outputs in this doc (§3 has a "record the answer here" slot per spike), then the PoC tasks become
    fully concrete.
  - **P1–P8 (§6) are contract-level task outlines** — expand each into 0/1-style tasks *on arrival*, using the
    verified facts in §1 and the PoC as a worked template. Detailing them now would mean inventing the very
    decisions the P0 spikes exist to make.
- **Golden rules of this project:**
  1. **No storage code before the P0 model is frozen** (design doc §10 gate). Writing serdes against an unfrozen
     key layout guarantees rework.
  2. **The graph reuses the query core; it does not fork it.** The core (grammar framework, `LogicalPlan` IR with
     `Join`, `Binder`, `JoinExecutor`, planner, cost model, `SearchProvider`→coprocessor→`ResultStore` plumbing)
     is **already built** (see §1.2). Cypher is a *second front-end* onto it, and a graph is a *new datasource*
     on it. If a task seems to require re-implementing something the core provides, stop and reconsider.
  3. **Domain types are advisory** (design §5.6): a graph with no domain types must build and query identically.
  4. **Every task ships green**: `./gradlew` build + the task's own tests + checkstyle, before it's "done".

---

## 1. Repo orientation (verified facts)

All paths relative to repo root. These are the load-bearing reuse points; each signature below was read from source
on 2026-07-18.

### 1.1 Build / toolchain
- Java 25, Gradle. ANTLR is already wired into `stroom-query-grammar` (see §1.3) — the "no ANTLR precedent" risk
  the design doc lists is **retired**; the module, the `antlr` plugin, and the `generateGrammarSource` task all exist.
- Module tests: `testImplementation libs.bundles.common.test.implementation` + `testRuntimeOnly
  libs.bundles.common.test.runtime` (JUnit 5 + AssertJ + Mockito). A module with no test source yet must add these
  (the query-optimiser work hit this: `stroom-searchable-impl` needed both lines before its first test would run).

### 1.2 The query core this project builds on (already delivered — verify it's on your branch)
The design doc treats "the query core" as a separately-delivered prerequisite. **It is now built** (query-optimiser
Phases 0–6). The graph consumes these directly:

- **Grammar module** `stroom-query-grammar`: ANTLR plugin, `StroomQL.g4`, generated parser in package
  `stroom.query.grammar.antlr`, hand-written AST in `stroom.query.grammar.ast`, entry point
  `stroom.query.grammar.parse.StroomQlParser.parse(String) -> AstQuery`. **This is the exact template for Cypher**
  (§1.3, PoC.1).
- **Logical IR** `stroom-query-planner`, package `stroom.query.planner.logical`: sealed `LogicalPlan` permitting
  `Scan(alias, dataSourceName, position)`, `Filter(input, wherePredicate, filterPredicate, position)`,
  `Project(input, List<ProjectField>, position)`, `Join(left, right, JoinType, List<EquiKey>, position)`,
  `Aggregate`, `Having`, `Window`, `Sort`, `Limit`. `JoinType{LEFT, INNER}`, `EquiKey(QualifiedField left,
  QualifiedField right)`, `QualifiedField(@Nullable String alias, String field)`.
- **Binder** `stroom.query.planner.bind.Binder`: `new Binder(FieldInfoSource).bind(AstQuery) -> LogicalPlan`;
  throws `BindException`. Resolves alias-qualified fields via a `Scope`. (Cypher's AST→IR visitor is the analogue.)
- **Join execution** `stroom.query.planner.join.JoinExecutor`: pure `join(Side left, Side right, JoinType,
  JoinAlgorithm) -> List<Val[]>`; `Side(List<Val[]> rows, int[] keyPositions, int width)`. Implements `HASH_JOIN`
  and `NESTED_LOOP`; **`BROADCAST_LOOKUP` throws `UnsupportedOperationException`** — the per-probe-row lookup path
  a graph `expand` needs is *not* yet built here (design §5.5 item 4; this project's P3/PoC owns it).
- **Cost model** `stroom.query.planner.cost`: `CostModel`, sealed `AccessPath{FullScan, IndexScan, StateLookup}`,
  `CostEstimate(rows, bytes, durationMs, confidence, notes)`, `JoinCostModel`, `JoinAlgorithm{BROADCAST_LOOKUP,
  HASH_JOIN, NESTED_LOOP}`. Ports in `stroom.query.planner.port`: `FieldInfoSource`, `MetaStats`,
  `IndexShardStats`, `StateStoreStats` (the last two are interface-only, adapters deferred — a graph cost adapter
  would follow the same port/adapter split).
- **Cross-datasource join wire + execution** (query-optimiser Phase 6, the closest end-to-end template):
  - `stroom.query.api.JoinSpec` (GWT-safe wire type on `Query.getJoinSpec()`): two sub-`SearchRequest`s +
    `JoinType` + `List<JoinEquiKey>`.
  - `stroom.query.common.v2.JoinDataSourceType.TYPE = "StroomQLJoin"` — a **sentinel `DocRef` type** that routes
    a compiled query to a dedicated provider with **no change to `SearchProviderRegistry`** (it resolves purely by
    `DocRef.getType()`).
  - `stroom.searchable.impl.JoinSearchProvider` — reads the `JoinSpec`, realises each side via its own
    `SearchProvider` (`createResultStore` → `awaitCompletion` → `getData(TABLE_COMPONENT_ID).fetch(...)`), runs
    `JoinExecutor`, feeds combined rows into a fresh `CoprocessorsImpl` at the outer `FieldIndex` positions, applies
    a where-predicate via `ExpressionPredicateFactory`, returns a `ResultStore`. **This is the concrete template
    for `GraphSearchProvider`.**
- **The compilation seam** `stroom.query.language.QueryCompiler` (in `stroom-query-common`): `create(query, in,
  ExpressionContext) -> SearchRequest`. `OptimisingQueryCompiler` is the grammar-driven implementation and shows
  how to go AST → `Binder` → `RewritePipeline` → `SearchRequest`, including compiling a join into a `JoinSpec` +
  sentinel `DocRef`. **`CypherCompiler` is the analogue for Cypher** (PoC.3).
- **Result plumbing** `stroom-query-common` `v2`: `CoprocessorsFactory.create(SearchRequest, DataStoreSettings)`
  and `create(SearchRequestSource, DateTimeSettings, QueryKey, List<CoprocessorSettings>, List<Param>,
  DataStoreSettings)`; `DataStoreSettings.createBasicSearchResultStoreSettings()`; `ResultStoreFactory.create
  (SearchRequestSource, CoprocessorsImpl)`; `CoprocessorsImpl.accept(Val[])` / `.getFieldIndex()` /
  `.getCompletionState().signalComplete()`; `ResultStore.getData(componentId).fetch(columns, OffsetRange.UNBOUNDED,
  OpenGroups.ALL, timeFilter, IdentityItemMapper.INSTANCE, itemConsumer, countConsumer)`. `FieldIndex` keys on a
  field-reference's **exact expression string** and assigns positions in first-`create` order;
  `Ref.Gen.set` reads `values[pos]`. (All exercised by the query-optimiser join work — see that plan's Phase 6.)

### 1.3 Plan B storage primitives (the graph's substrate)
- **DAO base**: `stroom.planb.impl.dao.AbstractDb<K,V> implements Db<K,V>`
  (`stroom-planb/stroom-planb-impl/.../dao/AbstractDb.java`). Holds `PlanBEnv env`, `ByteBuffers byteBuffers`,
  `PlanBDoc doc`, `Dbi<ByteBuffer> dbi` (data, name `"db"`), `Dbi<ByteBuffer> infoDbi` (name `"info_db"`),
  `PutFlags[] putFlags`. `Db` declares `int MAX_KEY_LENGTH = 511` and the contract: `insert`, `get`, `search`,
  `merge`, `deleteOldData`, `condense`, `compact`, `createWriter`, `count`.
- **Temporal state DAO** `stroom.planb.impl.dao.temporalstate.TemporalStateDb extends AbstractDb<TemporalKey, Val>`.
  Built via static `create(Path, ByteBuffers, PlanBDoc, boolean readOnly)` (not injected). The **as-of / floor
  lookup** is the load-bearing primitive — `getState(TemporalStateRequest) -> TemporalState`:
  builds the full key buffer `[prefix][time]`, then
  `LmdbKeyRange.builder().start(keyByteBuffer).reverse().build()`, iterates **descending**, and returns the first
  entry still matching the key-prefix (`ByteBufferUtils.containsPrefix`). Write: `insert(LmdbWriter, KV<TemporalKey,
  Val>)` (stores value wrapped as `ValTime(val, Instant.now())`). Scan: `search(ExpressionCriteria, FieldIndex,
  DateTimeSettings, ExpressionPredicateFactory, ValuesConsumer)` → delegates to
  `stroom.planb.impl.dao.PlanBSearchHelper.search(...)`.
- **Composite-key + custom-search precedent** `stroom.planb.impl.dao.trace.TraceDb extends AbstractDb<SpanKey,
  SpanValue>` — keeps a **second DBI** (`"trace-roots"`) alongside `"db"` (the adjacency/secondary-index pattern),
  fixed-layout composite key (`SpanKeySerde`: `[traceId:16][parentSpanId:8][spanId:8]`), and a **forward prefix
  scan** `findSpans(Txn, byte[] traceId, Consumer<Span>)` via `LmdbKeyRange.builder().prefix(prefixBuffer).build()`.
- **Order-preserving encoding**: `stroom.planb.impl.serde.time.TimeSerde` impls (`MillisecondTimeSerde`, …) encode
  time big-endian unsigned (`UnsignedBytes`) so LMDB byte order == chronological order — the reason the reverse-scan
  floor lookup works. Composite temporal key = `[prefix bytes][fixed-width time bytes]` (see `UidLookupKeySerde`).
  `LmdbKeyRange` (`stroom-lmdb/.../stream/LmdbKeyRange.java`): `.builder().start(buf).reverse()` (floor),
  `.prefix(buf)` (adjacency), `.build()`.
- **UID interning** `stroom.planb.impl.dao.UidLookupDb` — get-or-create is `<R> R put(Txn, ByteBuffer keyBytes,
  Function<ByteBuffer, R> uidConsumer)`; read is `<R> R get(Txn, ByteBuffer, Function<Optional<ByteBuffer>, R>)` and
  `ByteBuffer getValue(Txn, ByteBuffer uid)` / `getValue(Txn, long uid)`. UID is a variable-length big-endian
  unsigned integer (a `long maxId` counter). Content-hash alternative: `HashLookupDb` (same shape, keyed by hash
  with clash handling). Constructed `new UidLookupDb(PlanBEnv, ByteBuffers, String name[, UnsignedBytesFactory])`,
  opening DBIs `name+"-keyToUid"`, `name+"-uidToKey"`, `name+"-info"`.
- **State-type palette** `stroom.planb.shared.StateType` (in `stroom-core-shared`): `STATE, TEMPORAL_STATE,
  RANGED_STATE, TEMPORAL_RANGED_STATE, SESSION, HISTOGRAM, METRIC, TRACE`. A graph adds one constant here (like
  `TRACE` did) *or* gets its own doc type — see D2.

### 1.4 Ingest (the `GraphFilter` template)
- `stroom.planb.impl.pipeline.PlanBFilter extends stroom.pipeline.filter.AbstractXMLFilter`, annotated
  `@ConfigurableElement(type="PlanBFilter", category=Category.FILTER, roles={ROLE_TARGET, ROLE_HAS_TARGETS}, …)`.
  Consumes `reference-data:2` XML via SAX (`startElement`/`endElement`/`characters`); on `endElement` dispatches
  per element (`temporal-state` → `add(StateType.TEMPORAL_STATE)` → `writer.addTemporalState(doc, new
  TemporalState(key, val))`). Injected deps: `ErrorReceiverProxy, LocationFactoryProxy, MetaHolder,
  ByteBufferFactory, ShardWriters`.
- Writer: `stroom.planb.impl.dao.ShardWriters` (`@Singleton`), `createWriter(Meta) -> ShardWriter`; `ShardWriter`
  exposes `Optional<PlanBDoc> getDoc(String mapName, Consumer<String> error)` and typed `add*` writes (each lazily
  opens a per-doc DBI and downcasts to the concrete DAO). On `close()` it zips the per-stream shard and ships it via
  `FileTransferClient` — **the filter writes a local shard that is merged later, not the live store**.
- Registration: `stroom.planb.impl.pipeline.PlanBElementModule extends PipelineElementModule`,
  `bindElement(PlanBFilter.class)`; installed by `PlanBModule` (`install(new PlanBElementModule())`).

### 1.5 Search provider + module wiring (the `GraphSearchProvider` template)
- `stroom.planb.impl.StateSearchProvider implements SearchProvider, IndexFieldProvider` (~300 LOC — the cleanest
  copyable provider). `@Inject` deps: `Executor, PlanBDocStore, PlanBDocCache, CoprocessorsFactory,
  ResultStoreFactory, TaskManager, TaskContextFactory, ShardManager, ExpressionPredicateFactory, SecurityContext,
  FieldInfoResultPageFactory, DocFinder`. `createResultStore(SearchRequest)`: replace params, resolve `PlanBDoc`
  from `docRef.getName()` under `securityContext.useAsReadResult`, build coprocessors
  (`createBasicSearchResultStoreSettings`), create `ResultStore`, then **async** (`CompletableFuture.runAsync`)
  run `shardManager.get(doc.getName(), reader -> reader.search(criteria, coprocessors.getFieldIndex(),
  dateTimeSettings, expressionPredicateFactory, coprocessors))` and `resultStore.signalComplete()`. `getDataSourceType()`
  → `PlanBDoc.TYPE`; `getFieldInfo` → `StateFieldUtil.getQueryableFields(doc)`.
- Registration `stroom.planb.impl.PlanBModule`: `StateSearchProvider` is triple-multibound —
  `GuiceUtil.buildMultiBinder(binder(), X.class).addBinding(StateSearchProvider.class)` for `X` ∈
  {`DataSourceProvider`, `SearchProvider`, `IndexFieldProvider`}. Doc type: `DocumentStoreBinder.create(binder())
  .bind(PlanBDoc.TYPE, PlanBDocStore.class, PlanBDocStoreImpl.class)`; REST via `RestResourcesBinder`; doc cache
  `bind(PlanBDocCache.class).to(PlanBDocCacheImpl.class)`. (Explorer/handler wiring for the doc type is **outside**
  `PlanBModule` — a new Graph doc type must wire that separately.)

### 1.6 Domain types (semantic layer, advisory)
`stroom.domaintype.shared.DomainType` — `class.attribute` string, single-segment `*` wildcard,
`canAccept(DomainType)` (segment-wise `*`-or-equals). Catalogue doc `DomainTypeDoc` holds `List<DomainType>
domainTypes` + `description`. Already consumed by the query optimiser's `Binder.validateDomainTypeCompatibility`
(join-key validation). The graph is the second execution-side consumer (design §5.6). **Relationship (edge) types
are not in the catalogue** — see D5.

---

## 2. Target module layout

Where new code lives, and why (verified against the `build.gradle` dependency graphs in §1):

| New artefact | Module / package | Rationale |
|---|---|---|
| `Cypher.g4` + generated parser | `stroom-query-grammar`, `src/main/antlr/stroom/query/grammar/antlr/` → gen package `stroom.query.grammar.antlr` | Same module + `generateGrammarSource` config as `StroomQL.g4`; this module has a deliberately minimal dep surface (no planner/language deps), so Cypher inherits that. |
| Cypher AST records + `parse(...)` entry | `stroom-query-grammar`, `stroom.query.grammar.ast.cypher` + `stroom.query.grammar.parse` | Mirrors StroomQL's `ast`/`parse` split. Separate `ast.cypher` sub-package avoids record-name collisions with StroomQL's AST. |
| Cypher → `LogicalPlan` compiler + graph logical nodes (`Expand`, `VarLengthPath`) | `stroom-query-planner`, `stroom.query.planner.cypher` + `stroom.query.planner.logical` | Planner already depends on the grammar module and holds `LogicalPlan`; new IR nodes join the sealed hierarchy here. **Clean module — no Plan B dependency** (see next row). |
| **`GraphDbDoc` document type** (docstore doc + store) that **owns and encapsulates** the internal stores | `stroom-planb-impl` (`stroom.planb.impl.graph.*`) + `stroom-core-shared` for the shared `GraphDbDoc` type | This is the *only* thing a user creates. It provisions, opens, rebuilds, retains, and deletes its internal stores as a hidden unit (§2.1 below). Lives with the Plan B internals it wraps. |
| Graph physical storage (node/adjacency/property-index DAOs + serdes) + the anchor index — **internal to the `GraphDbDoc`, hidden from the user**, plus `GraphFilter`, `GraphSearchProvider`, the adjacency **access path** (prefix-scan over Plan B) | `stroom-planb-impl` (`stroom.planb.impl.graph.*`) | The Plan B DAO infra (`AbstractDb`, `PlanBEnv`, `UidLookupDb`, `ShardWriters`, `ShardManager`) is package-internal to `stroom.planb.impl`. Like the optimiser's `IndexShardStats` adapter (which had to live in `stroom-index-impl`, not the clean planner module, to avoid a dependency cycle), the graph's *physical* pieces live here; only the *logical* plan/operators live in the clean planner module. |
| Graph cost adapter (implements a planner `port`) | `stroom-planb-impl` | Same port/adapter split — interface in `stroom-query-planner.port`, adapter in the impl module. |
| Graph doc UI + Cypher editor + visualisation | `stroom-core-client` (GWT), mirroring `stroom.planb.client` | Same place as the Plan B doc editor. |

### 2.1 The `GraphDbDoc` wrapper (encapsulation — the defining architectural decision)

**The user creates one `GraphDbDoc` and nothing else.** That document *owns* every physical store the graph needs
— the Plan B temporal sub-stores (node, out/in adjacency, interning) *and* the anchor index (a Plan B `STATE`
sub-store or a wrapped Lucene index, per D3) — and manages them as a single hidden unit. This is the answer to
"less possibility to misconfigure": there is no separate Plan B doc or Index doc to create or wire, so there is
nothing to wire wrongly.

- **Lifecycle-as-a-unit**: the `GraphDbDoc`'s create / open / reprocess-rebuild / retain-condense / delete each
  provision or act on *all* its internal stores together. Model this on how `PlanBDoc` owns its LMDB env + sub-DBIs
  today (a graph just owns more of them, plus optionally a Lucene directory). No internal store has its own
  explorer node, `DocRef`, permissions, or REST resource — they are addressed only through the owning doc.
- **User-configurable surface = genuine choices only** (see D8): standard name/description; a
  retention/temporal-precision policy; and the node/edge **schema mapping** (often derivable from the domain-type
  catalogue, §5.6, so frequently zero-config). Everything physical — byte layouts, interning scheme, in/out-edge
  stores, **anchor-index technology (D3)**, sharding, snapshot/merge — is an internal default, **not** exposed.
- **Implication for every task below**: wherever a PoC/P-phase task says "the graph store" or "the provider resolves
  the datasource", it means *the `GraphDbDoc` and its owned internals* — never a user-visible Plan B/Index doc. The
  `GraphSearchProvider` resolves a `GraphDbDoc` (by its `DocRef`) and opens that doc's internal stores; `GraphFilter`
  writes into the owning `GraphDbDoc`'s stores.

> **[Decision D1 — new module vs. `stroom-planb-impl`]** The table places graph code in `stroom-planb-impl`. An
> alternative is a new `stroom-graph-impl` module depending on `stroom-planb-impl`, if the Plan B internals it needs
> (`PlanBEnv`, `UidLookupDb`, `ShardWriters`) are made accessible. Start in `stroom-planb-impl` (least friction,
> everything is reachable); extract later if the graph grows large. Record the choice in the P1 PR. **Note**: the
> shared `GraphDbDoc` type (a serialisable doc, like `PlanBDoc` in `stroom-core-shared`) lives in `stroom-core-shared`
> regardless.

---

## 3. Phase 0 — Design & de-risking spikes (fully specified; gates everything)

**No storage, grammar-to-IR, or execution code is written until P0's three spikes have recorded their outputs
below.** Each spike's deliverable is a *written artefact + a throwaway prototype*, not production code. Estimated
6–13 pw (design §9.1).

### Task 0.1 — Physical model + partitioning spike **(blocks all of P1, PoC.4–PoC.6)**
- **Goal**: freeze the byte-level key/value layout for every graph sub-store, choose interning per namespace, and
  decide the partitioning scheme — proven against real Plan B cursors.
- **Questions this spike must answer** (record each answer in the "Frozen model" box below):
  1. Exact key layout for `Node`, `Out-edge`, `In-edge`, `PropIndex` (design §5.1 gives the conceptual shape
     `[nodeUid][validFrom]`, `[srcUid][edgeTypeUid][validFrom][dstUid]`, mirror, `[labelUid][propKeyUid][valueBytes]`).
     Fix each field's width and encoding, reusing `TimeSerde` (order-preserving time) and the `TraceDb`/`SpanKeySerde`
     fixed-layout composite-key precedent. Confirm total key ≤ `Db.MAX_KEY_LENGTH` (511).
  2. Interning per namespace (node-external-id, label, edge-type, property-key): `UidLookupDb` (sequential, compact)
     vs `HashLookupDb` (content-hash, stable, needs clash handling). Design §5.1 assumes `UidLookupDb` ×4 —
     confirm or override with a reason.
  3. Property-value index technology: a Plan B `STATE` sub-store vs a Lucene index (design §5.1, Open question).
     Decide on write-cost vs query-flexibility; **[resolves D3]**.
  4. Partitioning: shard by graph/tenant id (adjacency co-located, minimises cross-shard hops) vs by node id.
     **Benchmark** the cross-shard-hop cost of at least one alternative on synthetic data (design §11 — "blocks the
     scale-out estimate").
- **Deliverable**: a written key-layout spec (a section appended to the design doc or a new `graph-physical-model.md`)
  + a *throwaway* prototype (not committed to production packages) that: interns a few nodes/edges, writes them to a
  real `TemporalStateDb`-style DBI, and runs **anchor-seek → 1-hop expand (prefix scan) → floor filter** reading real
  LMDB entries — proving the cursor lifetimes / byte-buffer reuse work (design §8 "traversal executor correctness"
  risk).
- **Done-when**: the layout spec is reviewed; the prototype demonstrably reads back a 1-hop traversal with a correct
  as-of result; the partitioning decision is recorded with a benchmark number; D3 is decided.
- **Verify**: prototype runs green as a throwaway `main`/test; spec reviewed by the storage owner.

> **Frozen model (fill in when 0.1 completes — the rest of the plan references this):**
> - Node key: `________`  Node value: `________`
> - Out-edge key: `________`  value: `________`   In-edge key: `________`
> - PropIndex: `________`   (tech: Plan B STATE | Lucene = `____`)
> - Interning: node-id=`____` label=`____` edgeType=`____` propKey=`____`
> - Partitioning: `________`  (cross-shard-hop benchmark: `____`)

### Task 0.2 — Cypher scope + grammar spike **(blocks PoC.1)**
- **Goal**: import a maintained openCypher ANTLR grammar, generate a parser in `stroom-query-grammar`, and lock the
  supported subset — out-of-subset = explicit parse error from day one (design §8 "coverage creep").
- **Questions this spike must answer**:
  1. **Which** openCypher grammar to adopt (design §11). Record the source + licence + revision. **[resolves D4]**
  2. The exact v1 subset: `MATCH` (single + fixed-length paths), `WHERE`, `RETURN`, `WITH`, `ORDER BY`, `SKIP`,
     `LIMIT`, bounded variable-length paths `-[:T*1..k]->`, basic aggregation (`count`, `sum`, `avg`, `min`, `max`).
     Everything else rejected at parse.
  3. How the temporal extension clauses (`AS OF` / `AROUND ± d` / `BETWEEN`) attach to the grammar (a Stroom-specific
     production; design §5.4). Resolved *before* execution, so it's a grammar + AST concern here.
- **Deliverable**: `Cypher.g4` (trimmed to the subset) building in `stroom-query-grammar` with the same
  `generateGrammarSource` args as `StroomQL.g4` (`-visitor -no-listener -package stroom.query.grammar.antlr`); a
  written subset spec; a **golden corpus** of in-subset queries that must parse and out-of-subset queries that must
  error (the parity-test discipline the optimiser used, Task 1.6).
- **Done-when**: `./gradlew :stroom-query:stroom-query-grammar:build` is green with `Cypher.g4` present; the subset
  spec exists; a corpus test asserts in-subset parses / out-of-subset throws `SyntaxException`. **[resolves D4]**
- **Verify**: `./gradlew :stroom-query:stroom-query-grammar:test`.

### Task 0.3 — Temporal semantics spike **(blocks PoC.6, all of P4)**
- **Goal**: pin how `AS OF <t>`, `AROUND <t> ± <d>`, `BETWEEN <t1> AND <t2>` map onto Plan B floor lookups and
  window scans — with worked examples, reviewed by a domain owner *before* any temporal execution code (design §8).
- **Questions this spike must answer**:
  1. `AS OF t`: one snapshot instant → the `TemporalStateDb.getState`-style floor lookup applied to every node
     version and adjacency edge. Confirm the exact mapping (reverse cursor from `[entity][t]`).
  2. `AROUND t ± d` / `BETWEEN`: a bounded window scan over `TEMPORAL_RANGED_STATE` returning versions/edges whose
     validity interval **intersects** the window. Define "intersects" precisely (inclusive/exclusive bounds).
  3. **Multi-hop semantics**: is `AS OF` applied per-edge (each hop floor-looked-up at `t`) or per-path? Design §8
     flags this as "easy to get subtly wrong". Decide and document with the §6 worked example.
- **Deliverable**: a written temporal-semantics spec with ≥2 worked examples (the §6 device/account query is one),
  giving the exact floor-lookup/window-scan mapping and the multi-hop rule.
- **Done-when**: spec reviewed and signed off by a domain owner. **No code.**

**Phase 0 exit gate**: all three "Frozen model" / subset / temporal specs recorded and reviewed; the 0.1 prototype
demonstrates a real 1-hop as-of traversal. Only now does storage/grammar/execution coding begin.

---

## 4. Phase PoC — first end-to-end graph query on the core

**Goal (design §10 stage 2)**: a single-shard graph, `MATCH (a:L {p:v})-[:T]->(b:L2) [AS OF t] RETURN …` running
end-to-end through a dashboard — Cypher → the existing `LogicalPlan` IR → `expand` over Plan B adjacency →
`GraphSearchProvider` → coprocessors → `ResultStore` → REST/UI. Estimated ≈ 15–30 pw. Detailed to file/signature
level below; **[P0-dep]** marks a spot that consumes a P0 output.

### Task PoC.0 — `GraphDbDoc` document type + store-ownership scaffold
- **Goal**: the single user-facing document (§2.1) and the object that owns/opens its internal stores as a unit —
  the foundation every other PoC task plugs into.
- **Depends on**: 0.1 (needs to know which internal stores exist).
- **Files**:
  - `stroom-core-shared/src/main/java/stroom/graph/shared/GraphDbDoc.java` — a serialisable doc extending
    `AbstractDoc` (mirror `PlanBDoc`), `TYPE = "GraphDb"`, carrying **only the user-configurable fields** (D8):
    `description`, a retention/temporal-precision policy, and the node/edge schema mapping (nullable — zero-config
    when derived from the domain-type catalogue). **No physical-store config fields.**
  - `stroom-planb-impl/.../graph/GraphStores` — the object that, given a `GraphDbDoc` + its on-disk directory,
    provisions/opens the internal stores together (node DAO, out/in adjacency DAOs, the 4 `UidLookupDb` interning
    namespaces, the anchor index) and exposes them to the traversal engine + filter. Model the "owns an LMDB env +
    several DBIs" lifecycle on how `PlanBDoc`/`TemporalStateDb.create(...)` open their env; a graph just opens more
    sub-stores under one env (plus optionally a Lucene directory). Create / open / close / **rebuild** (drop +
    re-provision, for reprocess) / **delete** all act on the whole set.
  - Docstore + cache wiring: `GraphDbDocStore`/`GraphDbDocStoreImpl` + a `GraphDbDocCache`, mirroring
    `PlanBDocStore`/`PlanBDocCache`; bound in a module (PoC.6 / P5 does the `DocumentStoreBinder` +
    `RestResourcesBinder` + explorer-handler wiring).
- **Contract**: creating a `GraphDbDoc` provisions an empty, queryable graph with **no** other document created or
  referenced; deleting it removes all internal stores. No internal store has its own `DocRef`/explorer node.
- **Done-when**: a test creates a `GraphDbDoc`, `GraphStores` provisions + reopens its internal stores against a
  temp-dir env, and deleting the doc removes them; the doc round-trips through Jackson exposing only the
  user-configurable fields.
- **Verify**: `./gradlew :stroom-core-shared:test :stroom-planb:stroom-planb-impl:test`.

### Task PoC.1 — `Cypher.g4` → AST (mirror the StroomQL pipeline)
- **Depends on**: 0.2 (the subset + chosen grammar).
- **Files**:
  - `stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4` **[P0-dep: 0.2's trimmed grammar]**.
  - `stroom-query-grammar/src/main/java/stroom/query/grammar/parse/CypherQueryParser.java` — entry
    `public static AstCypherQuery parse(String cypher)`, mirroring `StroomQlParser.parse` exactly (lexer →
    `ThrowingSyntaxErrorListener.INSTANCE` → `CommonTokenStream` → generated parser → an `AstCypherBuilder`).
    (Named `CypherQueryParser`, not `CypherParser`, to avoid collision with the generated
    `stroom.query.grammar.antlr.CypherParser`.)
  - `stroom-query-grammar/src/main/java/stroom/query/grammar/ast/cypher/` — records mirroring
    `stroom.query.grammar.ast`: `AstCypherQuery`, `AstMatch`, `AstPathPattern` (a node/edge/node chain),
    `AstNodePattern(variable, labels, propMap)`, `AstEdgePattern(variable, type, direction, varLength?)`,
    `AstWhere`, `AstReturn`, `AstReturnItem`, `AstOrderBy`, `AstSkip`, `AstLimit`, `AstTemporal(mode, instant,
    duration?, from?, to?)`, plus `AstCypherBuilder` (hand-written, like `AstBuilder`).
- **Contract**: `parse(cypher)` returns a typed AST for every in-subset query and throws `SyntaxException`
  (verbatim reuse of the grammar module's existing error listener) for out-of-subset input.
- **Done-when**: 0.2's golden corpus parses to the expected AST shape; out-of-subset throws.
- **Verify**: `./gradlew :stroom-query:stroom-query-grammar:test`.

### Task PoC.2 — Graph logical IR nodes
- **Goal**: the `LogicalPlan` nodes a graph traversal needs that the relational core lacks.
- **Files** (`stroom-query-planner/src/main/java/stroom/query/planner/logical/`):
  - `NodeScan(String variable, List<String> labels, @Nullable ExpressionOperator propertyAnchor, AstPosition)` —
    an anchor scan (find start nodes by label + property predicate). *(Alternatively, reuse `Scan` with a graph
    datasource name + a `Filter` — decide in PoC.3; a dedicated node is clearer for graph-specific costing.)*
  - `Expand(LogicalPlan input, String edgeType, Direction direction, String targetVariable, AstPosition)` — one hop;
    `Direction{OUT, IN, BOTH}`. Add to the sealed `LogicalPlan` permits list.
  - `VarLengthExpand(LogicalPlan input, String edgeType, Direction, int minHops, int maxHops, String targetVariable,
    AstPosition)` — the bounded transitive closure (executed in P3; the IR node exists now so PoC plans type-check).
- **Contract**: pure record IR nodes, no execution. Extend the `LogicalPlan` sealed interface + every exhaustive
  `switch` over it (there are several in `stroom-query-planner`/`-common` — the compiler will list them; handle each,
  even if only to `throw` "not supported in this pass" for `VarLengthExpand` pre-P3).
- **Done-when**: `stroom-query-planner` compiles with the new permits; existing planner tests stay green.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test`.

### Task PoC.3 — Cypher → IR compiler
- **Depends on**: PoC.1, PoC.2.
- **Files**: `stroom-query-planner/src/main/java/stroom/query/planner/cypher/CypherToLogicalPlan.java` —
  `LogicalPlan compile(AstCypherQuery)`. Lower a single path pattern `(a:L{p:v})-[:T]->(b:L2)` to
  `Project(Expand(NodeScan(a, [L], p=v), T, OUT, b), returnItems)`; attach `WHERE` as a `Filter`; `RETURN`/`ORDER
  BY`/`SKIP`/`LIMIT`/aggregation reuse the core's `Project`/`Sort`/`Limit`/`Aggregate` unchanged. Capture the
  temporal clause as a resolved field on the plan (a small `TemporalContext(mode, instant, from, to)` threaded to
  execution — **[P0-dep: 0.3's mapping]**).
- **Contract**: produces a `LogicalPlan` whose leaves reference the graph datasource; a query outside the PoC shape
  (multi-hop chains beyond one edge, var-length) throws a clear "not in PoC subset" error (tightened in P3).
- **Done-when**: unit tests: the §6-style single-hop query compiles to the expected `Project/Expand/NodeScan` tree;
  a `WHERE` predicate lands in a `Filter`; `AS OF` is captured.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test`.

### Task PoC.4 — Graph physical stores (node + single-direction adjacency + property index)
- **Depends on**: 0.1 (**the frozen model — do not start before**), PoC.0 (these DAOs are the internal stores
  `GraphStores` provisions/opens; they are not addressable except through the owning `GraphDbDoc`).
- **Files** (`stroom-planb-impl/src/main/java/stroom/planb/impl/graph/`):
  - Serdes for the frozen key layouts (node, out-edge, property index) modelled on `UidLookupKeySerde` /
    `SpanKeySerde` (order-preserving, `TimeSerde` for the time suffix). **[P0-dep]**
  - `GraphNodeDb extends AbstractDb<…>` — node versions with an as-of `getNode(nodeUid, TemporalContext)` copying
    `TemporalStateDb.getState`'s reverse-`start` floor scan.
  - `GraphAdjacencyDb extends AbstractDb<…>` — `expandOut(srcUid, edgeTypeUid, TemporalContext, Consumer<neighbour>)`
    via `LmdbKeyRange.builder().prefix(...).build()` (the `TraceDb.findSpans` forward-scan pattern) + floor/window
    filter on the time component.
  - `GraphPropertyIndex` — `findAnchors(labelUid, propKeyUid, valueBytes) -> nodeUids` (Plan B STATE sub-store **or**
    Lucene per 0.1's D3 decision). **This is the "main index" the `GraphDbDoc` wraps** — internal, hidden.
  - `GraphUids` — the 4 `UidLookupDb` namespaces (`UidLookupDb.put/get`), wired as `TemporalStateDb` wires its own.
  - All of the above are opened/owned by `GraphStores` (PoC.0), not created standalone.
- **Contract**: pure storage; no query engine. In-edge mirror + retention are P1 (not PoC).
- **Done-when**: a round-trip test (intern → write node + out-edge → `getNode` as-of + `expandOut`) reads back the
  expected neighbours and the correct as-of node version, against a real temp-dir LMDB env (the
  `TestSearchResultCreation`/`LmdbDataStoreFactory` temp-dir pattern).
- **Verify**: `./gradlew :stroom-planb:stroom-planb-impl:test`.

### Task PoC.5 — Traversal executor (anchor → expand → project) over Plan B cursors
- **Depends on**: PoC.2, PoC.4.
- **Files** (`stroom-planb-impl/.../graph/`): `GraphTraversalEngine` — given a compiled `LogicalPlan` (anchor +
  single `Expand`) + a `TemporalContext`, executes: (1) resolve temporal context once; (2) `GraphPropertyIndex.
  findAnchors`; (3) for each anchor, `GraphAdjacencyDb.expandOut`, dereference neighbour via `GraphNodeDb.getNode`,
  apply the `Filter` predicate (reuse `ExpressionPredicateFactory` exactly as `JoinSearchProvider.whereRowPredicate`
  does — build a name→`ValueFunctionFactory` accessor over the row); (4) emit `Val[]` rows for the `RETURN`/`Project`
  columns.
- **Contract**: single-hop only (var-length is P3); single-shard only (cross-shard is P8). Streams `Val[]` — does
  **not** itself build coprocessors (PoC.6 does).
- **Done-when**: unit test over PoC.4's fixtures: a single-hop `MATCH…RETURN` yields the expected `Val[]` rows;
  an `AS OF` variant yields the point-in-time-correct rows.
- **Verify**: `./gradlew :stroom-planb:stroom-planb-impl:test`.

### Task PoC.6 — `GraphSearchProvider` + Graph datasource + `CypherCompiler` seam
- **Depends on**: PoC.3, PoC.5; templates: `StateSearchProvider` (§1.5), `JoinSearchProvider` (§1.2).
- **Files**:
  - `stroom-planb-impl/.../graph/GraphSearchProvider implements SearchProvider` — copy `StateSearchProvider`'s
    constructor deps + `createResultStore` skeleton. **Resolves the `GraphDbDoc` from `Query.dataSource` (by
    name/DocRef, under `securityContext.useAsReadResult`, via the `GraphDbDocCache`), then opens that doc's internal
    stores through `GraphStores` (PoC.0)** — it never addresses a Plan B/Index doc directly. Builds coprocessors via
    `CoprocessorsFactory`, `ResultStore` via `ResultStoreFactory`, runs `GraphTraversalEngine` over the doc's stores,
    feeds `coprocessors.accept(Val[])` at the `FieldIndex` positions the `RETURN` columns claimed (the exact
    `buildFieldMapping`/`assembleRow` + `whereRowPredicate` pattern from `JoinSearchProvider`), `signalComplete`,
    returns the store. `getDataSourceType()` → `GraphDbDoc.TYPE`.
  - `CypherCompiler` (seam analogous to `QueryCompiler`/`OptimisingQueryCompiler`): `SearchRequest create(String
    cypher, SearchRequest in, ExpressionContext)` → `CypherQueryParser.parse` → `CypherToLogicalPlan.compile` →
    a `SearchRequest` whose `Query.dataSource` is the Graph doc + a graph-plan payload (mirror `JoinSpec`: a
    GWT-safe `GraphSpec` on `Query` carrying the compiled traversal + `TemporalContext`, so the provider reads it
    back the way `JoinSearchProvider` reads `JoinSpec`). **[Decision D6 — reuse `Query.joinSpec`-style payload vs a
    new field; recommend a new `Query.graphSpec` field, purely additive like `joinSpec` was.]**
  - Registration: triple-multibind `GraphSearchProvider` in `PlanBModule` (`DataSourceProvider`, `SearchProvider`,
    `IndexFieldProvider`) — one line each, exactly as `StateSearchProvider`.
- **Contract**: a Cypher single-hop query submitted through the normal search REST path returns real rows; the
  `ModelChangeDetector` golden test (see the optimiser plan's Task 6.1a finding) will flag any `SearchRequest`-model
  change — treat a new `Query.graphSpec` field as an additive, minor-version change and update the portrait.
- **Done-when**: an in-module end-to-end test (real coprocessors, real `ResultStore`, PoC.4 graph fixtures) proves
  `MATCH (d:Device{id:'d-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id` returns the expected rows; an `AS OF`
  variant returns the point-in-time-correct rows.
- **Verify**: `./gradlew :stroom-planb:stroom-planb-impl:test`.

**PoC exit gate**: a 1-hop temporal Cypher query runs end-to-end and its rows surface through a dashboard/query REST
result exactly like any StroomQL query. Ingest is still fixture-driven (real `GraphFilter` is P2); traversal is
single-hop single-shard.

---

## 5. Test strategy (quick reference)
Mirror the optimiser project's altitudes:
- **Unit per component**: grammar (parse corpus, in/out-of-subset), Cypher→IR (`text→plan`), each graph DAO
  (round-trip write/read + as-of), traversal engine (`plan+fixtures→Val[]`), temporal correctness (as-of across
  hops, window intersection edge cases).
- **End-to-end in-module**: the PoC.6 real-coprocessor test — the graph analogue of the optimiser's
  `innerJoin_returnsRealJoinedRows_throughRealCoprocessors`.
- **Ingest round-trip** (P2): XML → `GraphFilter` → store → query.
- **Golden/temporal corpus**: hand-computed expected results (a graph has no legacy oracle), plus the invariant that
  traversal-order / algorithm choice never changes the result set.
- **A real cross-provider run against a live backend** (graph ⋈ index, cross-shard) is manual verification beyond
  in-module tests — call it out, don't claim it from unit tests (the same honesty the optimiser plan applied to
  `index ⋈ index`).

---

## 6. Phases P1–P8 — task outlines (re-plan in detail on arrival)

Shape-complete but intentionally lighter — expand each into 0/1-style tasks when you reach it, using §1's verified
facts and the PoC as the worked template. The design doc's §9.1 WBS gives the effort; the pw ranges below are from it.

### P1 — Physical graph model, completed (11–23 pw) — *after PoC proves the shape*
- **In-edge adjacency mirror** (`[dstUid][edgeTypeUid][validFrom][srcUid]`) for reverse/undirected traversal — a
  mirror of PoC.4's out-edge DAO.
- **Interning hardening**: all 4 UID namespaces + used-lookup recorders (the `getUsedLookupsRecorder` pattern that
  Plan B serdes already use for retention).
- **Retention / condense / snapshot**: extend Plan B's `deleteOldData`/`condense` to the graph DBs (design §5.4).
- **Property-index build-out** to whatever 0.1/D3 chose.
- *Gate*: reverse + undirected traversal correct; retention removes old versions without corrupting adjacency.

### P2 — Ingest `GraphFilter` (7–13 pw)
- **Graph-mutation XML schema** (node/edge upserts carrying `validFrom`) — a small new schema, or a convention on
  `reference-data:2` (design §5.2). Decide **[D7]**.
- **`GraphFilter extends AbstractXMLFilter`** modelled on `PlanBFilter` (§1.4): SAX-parse mutations, write
  node/out-edge/in-edge/interning/property-index entries via a new `ShardWriter.addGraph…` path.
- **Wiring**: `bindElement(GraphFilter.class)` in a `PipelineElementModule`; `ShardWriters` graph-DBI open path.
- **Round-trip tests**: XML → graph → query fixtures.
- *Gate*: a feed of events reprocesses into a queryable graph; rebuild-from-streams works.

### P3 — Variable-length paths / fixpoint (part of the 9–18 pw graph-query line)
- **The one operator the equi-join core does not provide** (design §5.5 item 4). Bounded transitive-closure BFS/DFS
  over adjacency with a visited-set cycle guard, executing `VarLengthExpand` (PoC.2). Predicate push-down into the
  expansion; interaction with LMDB cursor lifetimes is the subtle risk (design §8).
- **Multi-hop path patterns** beyond single-hop; anchor-selectivity / start-node planner rules.
- *Gate*: `-[:T*1..k]->` returns correct paths with cycle safety; hand-computed expected sets match.

### P4 — Temporal ranges (5–10 pw) — *after 0.3*
- **`AROUND ± d` / `BETWEEN`** window scans over `TEMPORAL_RANGED_STATE`; **as-of join** reusing the core's
  State-lookup path + Plan B floor lookup for multi-hop temporal (design §5.5 items 4–5).
- **Temporal correctness tests**: as-of across multi-hop, interval intersection edges.
- *Gate*: the §6 worked example (window + as-of in one query) returns the domain-owner-approved result.

### P5 — Query integration hardening (5–10 pw)
- **Graph datasource cost signals** (row/key counts, adjacency access-path costing) implementing a
  `stroom-query-planner.port` interface via an adapter in `stroom-planb-impl` (the port/adapter split, §2).
- **`GraphDbDoc` hardening** (PoC.0 built the doc + store-ownership scaffold): REST resource + **explorer handler**
  (the piece outside `PlanBModule`, §1.5); full owned-store lifecycle — **reprocess-rebuild** (drop + re-provision
  all internal stores, then re-run ingest over stored streams) and **retention/condense as-a-unit** across every
  internal store; permissions on the one doc cascade to all its internals.
- *Gate*: a graph is created, edited, reprocessed, permissioned, retained, and queried like any other datasource —
  with **no** internal store ever visible or separately configurable (the §2.1 encapsulation invariant).

### P6 — UI (7–16 pw)
- **Graph doc editor** — a **tabbed** GWT presenter/view mirroring the Lucene Index editor
  (`stroom.index.client.presenter.IndexPresenter`, whose tabs are `Settings / Fields / Shards / Data /
  Documentation / Permissions`). A graph needs at least a **Settings** tab (the small config surface, D8) and a
  **Data** tab.
- **The Data tab** — mirrors the Index editor's `Data` tab: on open it runs a **default, editable** Cypher query
  (e.g. `MATCH (n)-[r]->(m) RETURN labels(n), type(r), labels(m), n, m LIMIT 20`) so the user sees *that* the graph
  is populated and *what shape* the data has (labels, edge types, sample rows), then can edit/re-run in place. Seed
  the default query from the graph's schema-mapping / domain-type catalogue where available so it names the graph's
  actual labels/edge types; an empty store shows zero rows (a clear "nothing ingested yet", not an error). It is a
  pre-filled query pane scoped to this one graph, using the same execution path as a dashboard query (PoC.6). See
  design HTML §6.5.
- **Cypher query editor** pane (dashboard); **visualisation** (force-directed/temporal; table fallback is cheap).
  Autocomplete can use domain-type relationship info (D5) once present.
- *Gate*: author + run a Cypher query and see tabular results in a dashboard; the Data tab confirms a populated
  graph's contents on open; visualisation is a stretch.

### P7 — Security / permissions (3–6 pw)
- **Document-level permissions** inherited via explorer/`SecurityContext` (largely free, as Plan B gets today).
- **Query guardrails**: traversal cost caps / timeouts / row limits; optional label-level filtering.

### P8 — Scale-out & hardening (10–20 pw)
- **Cross-shard traversal as a distributed join / exchange** extending `FederatedSearchExecutor` (design §8 — the
  largest scaling risk; the P0.1 partitioning decision drives it). Ship single-shard first.
- **Write-amplification mitigation** (in-edge mirror + property index + versions multiply writes); **perf
  benchmarking** (cursor/buffer reuse, caching).

### Cross-cutting
- **E2E tests + synthetic graph generator**; **docs** (Cypher-subset reference, temporal syntax, ops guide) — a
  graph analogue of the optimiser's user guide.

---

## 7. Open decisions to resolve (record the choice in the PR)
- **D1 — module placement**: graph code in `stroom-planb-impl` (recommended, least friction) vs a new
  `stroom-graph-impl`. The shared `GraphDbDoc` type lives in `stroom-core-shared` regardless. Record in the PoC.0 PR.
- **D2 — packaging *(resolved: a `GraphDbDoc` that wraps everything)*.** The graph is a single `GraphDbDoc`
  document type that **owns and encapsulates** its internal stores (§2.1) — *not* a `StateType.GRAPH` under
  `PlanBDoc`, and *not* a user-assembled Plan B doc + Index doc. This is the defining decision (the user's explicit
  requirement: minimise the configurable surface so a graph is hard to misconfigure). Built in PoC.0.
- **D3 — property-index technology *(now an internal, hidden default — no longer user-facing)*.** Plan B `STATE`
  sub-store vs a wrapped Lucene index for the anchor lookup. Because the `GraphDbDoc` owns it (§2.1), whichever is
  chosen is an internal implementation default, **not** a user configuration knob. Plan B `STATE` is the simpler to
  wrap (all under the doc's one LMDB env, one lifecycle); a wrapped Lucene index is more powerful for
  text/range anchors but adds an internal Lucene directory to the doc's owned-store lifecycle. **Resolve in P0.1.**
- **D4 — which openCypher grammar** to adopt into `stroom-query-grammar`. **Resolve in P0.2** (record source +
  licence + revision).
- **D5 — relationship (edge) types in the catalogue**: `DomainType` models entities, not edges. Either derive edges
  from convention (event co-occurrence) or add an optional `List<RelationshipType>` (`{type, from, to, directed}`)
  to `DomainTypeDoc` — the additive, `canAccept`-reusing extension sketched in design §11. Decide when P2 ingest
  mapping and/or P6 autocomplete need it; not on the PoC critical path.
- **D6 — graph-plan wire payload**: a new additive `Query.graphSpec` field (recommended, mirrors how `Query.joinSpec`
  was added — additive, `@JsonInclude(NON_NULL)`, minor-version bump, update `ModelChangeDetector` portrait) vs
  overloading an existing field. Decided in PoC.6.
- **D7 — graph-mutation XML**: a new schema vs a `reference-data:2` convention. Decided in P2.
- **D8 — the `GraphDbDoc` user-configurable surface**: which fields the user *can* set (§2.1). Recommended minimum:
  name/description + a retention/temporal-precision policy + an optional node/edge schema mapping (zero-config when
  derived from the domain-type catalogue). Everything physical stays hidden. Keep this list as small as the genuine
  choices demand — every field added is a new way to misconfigure. Finalised in PoC.0 (extended if P2/P4 surface a
  genuine required choice).

---

## 8. Sequencing & critical path
- **Critical path** (design §9.3): **P0.1 model + partitioning → PoC.0 `GraphDbDoc` + store ownership → PoC.4
  storage → PoC.5 traversal → PoC.6 provider → P4 temporal**. Everything hinges on freezing the physical model
  early and on the query core (present).
- **Parallelisable**: P0.2/P0.3 alongside P0.1; ingest (P2) once the model is frozen; UI (P6) against a stub
  provider; docs and the synthetic-data generator; scale-out (P8) after the single-shard PoC.
- **PR plan**: one PR per PoC task (PoC.0…PoC.6), each green in isolation, mirroring how the optimiser landed one
  well-scoped commit per task. PoC.0 (the `GraphDbDoc` + store-ownership scaffold) lands first, since every other
  PoC task plugs into it. P0 produces reviewed *documents*, not merged code (except the throwaway prototype, which
  is not committed to production packages).

---

*This is a build plan derived from the design proposal [`temporal-cypher-graph.md`](temporal-cypher-graph.md). P0
exists specifically to replace every **[P0-dep]** marker and "Frozen model" blank above with a concrete, reviewed
answer before the dependent code is written.*
