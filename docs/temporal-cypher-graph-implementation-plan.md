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
  5. **Documented + runtime-checked contracts on every new type/method.** Each new public or protected type and method
     carries **Javadoc** stating its **preconditions** (constraints on arguments/state at entry — one per `@param` where
     it applies), its **postconditions** (`@return` guarantees, side effects, and `@throws` for each thrown type), and
     its **null status**.
     - **Null status is expressed with JSpecify** (`org.jspecify.annotations`, already a dependency — `libs.jspecify`
       1.0.0 — and already used across the query core + `stroom-core-shared`), *not* prose and *not* another
       `@Nullable` flavour (the codebase also has stray `jakarta.annotation`/`jetbrains` ones — do not add more). Every
       new package gets a `@NullMarked` `package-info.java` so all references are non-null by default; `@Nullable`
       marks the genuine exceptions (parameters, return types, fields, type arguments). The annotations are the source
       of truth; the Javadoc explains the *meaning* of a null where one is allowed.
     - **Preconditions and postconditions are verified at runtime in code**, not merely documented. Use the repo's
       idioms: `java.util.Objects.requireNonNull(x, "x")` for null-argument preconditions (the dominant convention,
       800+ files), and Guava `com.google.common.base.Preconditions.checkArgument(...)` / `checkState(...)` for
       value/state preconditions and for postcondition invariants (assert-or-throw before returning). A violated
       contract **throws a clear exception**, never proceeds. This matters most in the byte-level serde / cursor code,
       where a wrong UID width, buffer offset, or over-length key **silently corrupts an LMDB store** instead of failing
       loudly — Plan B's existing `KeyLength.check(buffer, Db.MAX_KEY_LENGTH)` is the precedent to follow. (`assert` is
       acceptable only for pure programming-error checks that may be disabled at runtime; load-bearing invariants — and
       everything guarding a write to a store — must throw.)

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
| **`GraphDbDoc` document type** (docstore doc + store) that **owns and encapsulates** the internal stores | `stroom-graphdb-impl` (`stroom.graphdb.impl.*`) + `stroom-core-shared` for the shared `GraphDbDoc` type | This is the *only* thing a user creates. It provisions, opens, rebuilds, retains, and deletes its internal stores as a hidden unit (§2.1 below). **[D1 resolved — a dedicated module; see the blockquote below.]** |
| Graph physical storage (node/adjacency/property-index DAOs + serdes) + the anchor index — **internal to the `GraphDbDoc`, hidden from the user**, plus `GraphFilter`, `GraphSearchProvider`, the adjacency **access path** (prefix-scan over Plan B) | `stroom-graphdb-impl` (`stroom.graphdb.impl.*`) | A dedicated module depending on `stroom-planb-impl` (storage substrate) + `stroom-query-planner`/`-common`/`-grammar` (IR, engine, Cypher parse) + `stroom-core-shared`. **Consequence of D1 (new module, not folded into `stroom-planb-impl`):** the Plan B internals the graph reuses (`AbstractDb`, `PlanBEnv`, `UidLookupDb`/`HashLookupDb`, the serde primitives, `ShardWriters`, `ShardManager`) — some package-internal to `stroom.planb.impl` today — must be made reachable across the module boundary (promote to `public` in exported packages, or front with a small Plan B graph SPI). No dependency cycle results (`stroom-planb-impl` does not depend back on the graph module). Do the access audit in PoC.0. |
| Graph cost adapter (implements a planner `port`) | `stroom-graphdb-impl` | Same port/adapter split — interface in `stroom-query-planner.port`, adapter in the graph module. |
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

> **[Decision D1 — RESOLVED: a dedicated `stroom-graphdb-impl` module.]** Graph code lives in its own module
> `stroom-graphdb-impl` (Java packages `stroom.graphdb.impl.*`), sitting under a `stroom-graphdb/` parent to mirror the
> `stroom-planb/stroom-planb-impl` convention — registered in `settings.gradle` as
> `include 'stroom-graphdb:stroom-graphdb-impl'`. It depends on `stroom-planb-impl` (storage substrate),
> `stroom-query-planner`/`-common`/`-grammar` (IR + engine + Cypher parse), and `stroom-core-shared` (the shared
> `GraphDbDoc` type — which lives in `stroom-core-shared` regardless, package `stroom.graphdb.shared`). Its own
> `GraphDbModule` (Guice) owns all wiring; `PlanBModule` is **not** edited.
>
> **Action this choice creates (do it in PoC.0):** because the graph is now a *separate* module, the Plan B internals
> it reuses (`AbstractDb`, `PlanBEnv`, `UidLookupDb`/`HashLookupDb`, the `serde/*` primitives, `ShardWriters`,
> `ShardManager`) — some of which are today package-internal to `stroom.planb.impl` — must be reachable across the
> module boundary: promote the required types to `public` in exported packages, or extract a small graph-facing SPI in
> `stroom-planb-impl`. This access is the price of the clean separation (it was "free" only while the code sat inside
> `stroom-planb-impl`). There is **no dependency cycle**: `stroom-planb-impl` never depends on the graph module.

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
- **Done-when**: the layout spec is reviewed (**signed off**); D3 is decided; the partitioning decision is recorded
  (its benchmark number is a **P8** input, not a v1 gate — see below). *The throwaway prototype is **waived** — the
  layout is grounded in the verified Plan B serdes, and PoC.4's own tests prove the round-trip + as-of behaviour when
  storage lands.*
- **Verify**: layout spec signed off by the storage owner (done); prototype waived.

> **Frozen model (recorded 2026-07-18 from the P0.1 spike — the rest of the plan references this):**
> All UIDs are FIXED-WIDTH (`UidLookupDb` + `StaticUnsignedBytesFactory(UnsignedBytesInstances.ofLength(W))`); the
> `UidLookupDb` default is *variable* width (grows 1→8 B with the id counter) and would silently break composite-key
> prefix scans once a namespace crosses a width boundary — so fixed width is mandatory. `validFrom` =
> `MillisecondTimeSerde` (**6 B**, unsigned BE, order-preserving; **not** `NanoTimeSerde`, which is signed and not
> order-preserving). All keys ≪ `Db.MAX_KEY_LENGTH` (511).
> - **Node** — key `[nodeUid:6][validFrom:6]` (12 B); value `{labelsBitset, propsBlob}` or TOMBSTONE.
> - **Out-edge** — key `[srcUid:6][edgeTypeUid:4][dstUid:6][validFrom:6]` (22 B); value `edgePropsBlob` or TOMBSTONE.
> - **In-edge** — key `[dstUid:6][edgeTypeUid:4][srcUid:6][validFrom:6]` (22 B); value as out-edge.
>   *Ordering refinement:* `dst` precedes `validFrom` so each edge's version history is a contiguous, floor-lookable run
>   — this refines the design doc's conceptual `[src][etype][validFrom][dst]` (rationale + trade-off in §3 P0.1 outcome).
> - **PropIndex** — key `[labelUid:4][propKeyUid:4][valueBytes…]`; value `[nodeUid:6]`. `valueBytes` reuses Plan B's
>   `VariableKeySerde` length discipline (DIRECT inline / UID-lookup >32 B / HASH-lookup >511 B). Tech: **Plan B STATE
>   sub-store** *(resolves D3)*.
> - **Interning**: node-id = `UidLookupDb` fixed **6 B**; label / edgeType / propKey = `UidLookupDb` fixed **4 B** each.
>   Cross-shard-stable content-hash node ids (`HashLookupDb`, 8 B, clash-handled) are a **P8 scale-out** refinement,
>   *not* v1 — a single-shard `UidLookupDb` already unifies the same external id across feeds (get-or-create), so
>   domain-type entity resolution (§5.6) works without a hash in v1.
> - **Partitioning**: v1 = by graph id (`GraphDbDoc`), fully co-located → **zero cross-shard hops**. Scale-out (P8) =
>   hash by `srcUid` (out-edge) / `dstUid` (in-edge). Cross-shard-hop **benchmark: a P8 input, not a v1 gate** (below).
>
> **P0.1 status:** the frozen layout is **accepted and signed off** — it is grounded in the verified Plan B serde
> encodings, so the throwaway prototype is **waived** (PoC.4's own tests prove the round-trip + as-of behaviour when
> storage lands). The cross-shard-hop **benchmark is not a v1 gate**: v1 is single-shard (zero cross-shard hops), so the
> number is only an input to the **P8 scale-out** phase and is scheduled there. Coding may proceed on this layout.

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
- **Outcome (recorded 2026-07-18 — decisions made; grammar not yet imported):**
  - **Grammar (D4):** the openCypher reference ANTLR4 grammar (standardised subset, not Neo4j-proprietary), Apache-2.0,
    trimmed to the subset below; generate with the same args as `StroomQL.g4`
    (`-visitor -no-listener -package stroom.query.grammar.antlr`). **Pin the exact upstream commit + re-confirm the
    licence header at import.** (Alternative considered: `antlr/grammars-v4` `cypher` — viable, more trimming needed.)
  - **v1 subset (out-of-subset = explicit error from day one).** IN: `MATCH` (single node/edge/node + fixed-length
    chains); bounded var-length `-[:T*min..max]->` with a **mandatory finite upper bound**; `WHERE`; `RETURN`
    (`DISTINCT`, `AS`); `WITH`; `ORDER BY`; `SKIP`; `LIMIT`; aggregations `count`/`sum`/`avg`/`min`/`max` (+`count(*)`);
    labels + inline property maps + relationship direction. OUT (rejected): all writes (`CREATE`/`MERGE`/`SET`/
    `DELETE`/`REMOVE` — the graph is **read-only**, mutation is via ingest), `CALL`/procedures, `UNION`, `UNWIND`,
    `OPTIONAL MATCH`, `FOREACH`, subqueries/`EXISTS{}`, comprehensions, map projections, `shortestPath`, unbounded `*`.
  - **Temporal clause attachment:** a Stroom-specific production (kept in a clearly-marked section / separate `.g4` so
    upstream bumps don't conflict), **one clause per query, after the MATCH pattern, before WHERE**:
    `temporalClause : 'AS' 'OF' instant | 'AROUND' instant '±' duration | 'BETWEEN' instant 'AND' instant ;` → parses to
    `AstTemporal(mode, instant, duration?, from?, to?)`, resolved into a `TemporalContext` before execution (see P0.3).
  - **Residual:** the grammar file, generated parser, and corpus test are PoC.1 code — not written in this design spike.

### Task 0.3 — Temporal semantics spike **(blocks PoC.6, all of P4)**
- **Goal**: pin how `AS OF <t>`, `AROUND <t> ± <d>`, `BETWEEN <t1> AND <t2>` map onto Plan B floor lookups and
  window scans — with worked examples, reviewed by a domain owner *before* any temporal execution code (design §8).
- **Questions this spike must answer**:
  1. `AS OF t`: one snapshot instant → the `TemporalStateDb.getState`-style floor lookup applied to every node
     version and adjacency edge. Confirm the exact mapping (reverse cursor from `[entity][t]`).
  2. `AROUND t ± d` / `BETWEEN`: a bounded window scan returning versions/edges whose validity interval **intersects**
     the window. Define "intersects" precisely (inclusive/exclusive bounds).
  3. **Multi-hop semantics**: is `AS OF` applied per-edge (each hop floor-looked-up at `t`) or per-path? Design §8
     flags this as "easy to get subtly wrong". Decide and document with the §6 worked example.
- **Deliverable**: a written temporal-semantics spec with ≥2 worked examples (the §6 device/account query is one),
  giving the exact floor-lookup/window-scan mapping and the multi-hop rule.
- **Done-when**: spec reviewed and signed off by a domain owner. **No code.**
- **Outcome (recorded 2026-07-18 — semantics decided; domain-owner sign-off is the residual gate):**
  - A version has a half-open validity interval `[validFrom, nextValidFrom)`; `nextValidFrom` is the adjacent key in
    its contiguous run (cheap thanks to the P0.1 `dst`-before-`validFrom` ordering), or `+∞`, or a TOMBSTONE.
  - **AS OF t (Q1):** the `TemporalStateDb.getState` reverse-cursor floor lookup applied per entity — node
    `[nodeUid][t]` reverse; single edge `[src][etype][dst][t]` reverse; **expand as of t** = prefix-scan `[src][etype]`,
    group by `dst`, take each group's greatest `validFrom ≤ t`, drop tombstoned/empty groups.
  - **AROUND/BETWEEN (Q2):** `AROUND t±d` → window `[t−d, t+d]`; `BETWEEN t1 AND t2` → `[t1, t2]`; **bounds inclusive**.
    A version `[vf, vnext)` **intersects** `[w1, w2]` iff `vf ≤ w2 AND vnext > w1` (so `vnext==w1` excludes, `vf==w2`
    includes). Tombstone versions never emitted.
  - **Multi-hop (Q3):** **AS OF is applied per-edge** — every hop floor-looked-up at the *same* instant t ("the graph
    as it was at t"); a path exists iff every edge/node on it was valid at t. Windows apply per-edge (each edge has a
    version intersecting the window; not required simultaneous). Per-path as-of is rejected as ambiguous for v1.
  - **Sign-off:** domain-owner sign-off (the plan's done-when) is **complete**.

**Phase 0 exit gate**: all three "Frozen model" / subset / temporal specs recorded and reviewed; the 0.1 prototype
demonstrates a real 1-hop as-of traversal. Only now does storage/grammar/execution coding begin.

> **Status: Phase 0 complete — coding may begin.** The three specs are recorded (frozen-model box above; §3 P0.2 / P0.3
> outcomes) and **signed off** (storage-owner + domain-owner). The P0.1 throwaway prototype is **waived** — the frozen
> layout is grounded in the verified serdes, and PoC.4's tests prove the round-trip + as-of behaviour when storage
> lands. The cross-shard-hop **benchmark is deferred to P8** — a scale-out input, not a v1 gate, since v1 is
> single-shard with zero cross-shard hops. No design work remains before P1 / PoC.

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
  - `stroom-core-shared/src/main/java/stroom/graphdb/shared/GraphDbDoc.java` — a serialisable doc extending
    `AbstractDoc` (mirror `PlanBDoc`), `TYPE = "GraphDb"`, carrying **only the user-configurable fields** (D8):
    `description`, a retention/temporal-precision policy, and the node/edge schema mapping (nullable — zero-config
    when derived from the domain-type catalogue). **No physical-store config fields.**
  - `stroom-graphdb-impl/.../GraphStores` — the object that, given a `GraphDbDoc` + its on-disk directory,
    provisions/opens the internal stores together (node DAO, out/in adjacency DAOs, the 4 `UidLookupDb` interning
    namespaces, the anchor index) and exposes them to the traversal engine + filter. Model the "owns an LMDB env +
    several DBIs" lifecycle on how `PlanBDoc`/`TemporalStateDb.create(...)` open their env; a graph just opens more
    sub-stores under one env (plus optionally a Lucene directory). Create / open / close / **rebuild** (drop +
    re-provision, for reprocess) / **delete** all act on the whole set.
  - Docstore + cache wiring: `GraphDbDocStore`/`GraphDbDocStoreImpl` + a `GraphDbDocCache`, mirroring
    `PlanBDocStore`/`PlanBDocCache`; bound in a module (PoC.6 / P5 does the `DocumentStoreBinder` +
    `RestResourcesBinder` + explorer-handler wiring).
  - **Module setup (per Golden rule 5):** `stroom-graphdb-impl/build.gradle` adds `implementation libs.jspecify`; each
    new package (`stroom.graphdb.impl.*`, and `stroom.graphdb.shared` in `stroom-core-shared`) gets a `@NullMarked`
    `package-info.java`. Every new type/method from here on carries the documented + runtime-checked contract from its
    first commit.
- **Contract**: creating a `GraphDbDoc` provisions an empty, queryable graph with **no** other document created or
  referenced; deleting it removes all internal stores. No internal store has its own `DocRef`/explorer node.
- **Done-when**: a test creates a `GraphDbDoc`, `GraphStores` provisions + reopens its internal stores against a
  temp-dir env, and deleting the doc removes them; the doc round-trips through Jackson exposing only the
  user-configurable fields.
- **Verify**: `./gradlew :stroom-core-shared:test :stroom-planb:stroom-planb-impl:test`.

### Task PoC.1 — `Cypher.g4` → AST (mirror the StroomQL pipeline)
- **Depends on**: 0.2 (the subset + chosen grammar).
- **Files**:
  - `stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4` — the openCypher reference grammar
    trimmed to the **P0.2 subset** (§3 Task 0.2 outcome; pin the upstream commit + confirm the Apache-2.0 licence at import).
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
  execution — resolved per the **P0.3** outcome: per-edge AS OF, inclusive-bound window intersection, one context/query).
- **Contract**: produces a `LogicalPlan` whose leaves reference the graph datasource; a query outside the PoC shape
  (multi-hop chains beyond one edge, var-length) throws a clear "not in PoC subset" error (tightened in P3).
- **Done-when**: unit tests: the §6-style single-hop query compiles to the expected `Project/Expand/NodeScan` tree;
  a `WHERE` predicate lands in a `Filter`; `AS OF` is captured.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test`.

### Task PoC.4 — Graph physical stores (node + single-direction adjacency + property index)
- **Depends on**: 0.1 (**the frozen model — do not start before**), PoC.0 (these DAOs are the internal stores
  `GraphStores` provisions/opens; they are not addressable except through the owning `GraphDbDoc`).
- **Files** (`stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/`):
  - Serdes for the **frozen key layouts** (node, out-edge, property index — §3 Frozen-model box) modelled on
    `UidLookupKeySerde` / `SpanKeySerde`: fixed-width UIDs via `UidLookupDb` + `StaticUnsignedBytesFactory`,
    `MillisecondTimeSerde` (6 B) for the time suffix, and `dst` before `validFrom` in edge keys.
  - `GraphNodeDb extends AbstractDb<…>` — node versions with an as-of `getNode(nodeUid, TemporalContext)` copying
    `TemporalStateDb.getState`'s reverse-`start` floor scan.
  - `GraphAdjacencyDb extends AbstractDb<…>` — `expandOut(srcUid, edgeTypeUid, TemporalContext, Consumer<neighbour>)`
    via `LmdbKeyRange.builder().prefix(...).build()` (the `TraceDb.findSpans` forward-scan pattern) + floor/window
    filter on the time component.
  - `GraphPropertyIndex` — `findAnchors(labelUid, propKeyUid, valueBytes) -> nodeUids` (a **Plan B STATE sub-store** —
    D3 resolved in P0.1; `valueBytes` via the `VariableKeySerde` length discipline). **This is the "main index" the
    `GraphDbDoc` wraps** — internal, hidden.
  - `GraphUids` — the 4 `UidLookupDb` namespaces (`UidLookupDb.put/get`), wired as `TemporalStateDb` wires its own.
  - All of the above are opened/owned by `GraphStores` (PoC.0), not created standalone.
- **Contract**: pure storage; no query engine. In-edge mirror + retention are P1 (not PoC).
- **Done-when**: a round-trip test (intern → write node + out-edge → `getNode` as-of + `expandOut`) reads back the
  expected neighbours and the correct as-of node version, against a real temp-dir LMDB env (the
  `TestSearchResultCreation`/`LmdbDataStoreFactory` temp-dir pattern).
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

### Task PoC.5 — Traversal executor (anchor → expand → project) over Plan B cursors
- **Depends on**: PoC.2, PoC.4.
- **Files** (`stroom-graphdb-impl/.../`): `GraphTraversalEngine` — given a compiled `LogicalPlan` (anchor +
  single `Expand`) + a `TemporalContext`, executes: (1) resolve temporal context once; (2) `GraphPropertyIndex.
  findAnchors`; (3) for each anchor, `GraphAdjacencyDb.expandOut`, dereference neighbour via `GraphNodeDb.getNode`,
  apply the `Filter` predicate (reuse `ExpressionPredicateFactory` exactly as `JoinSearchProvider.whereRowPredicate`
  does — build a name→`ValueFunctionFactory` accessor over the row); (4) emit `Val[]` rows for the `RETURN`/`Project`
  columns.
- **Contract**: single-hop only (var-length is P3); single-shard only (cross-shard is P8). Streams `Val[]` — does
  **not** itself build coprocessors (PoC.6 does).
- **Done-when**: unit test over PoC.4's fixtures: a single-hop `MATCH…RETURN` yields the expected `Val[]` rows;
  an `AS OF` variant yields the point-in-time-correct rows.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

### Task PoC.6 — `GraphSearchProvider` + Graph datasource + `CypherCompiler` seam
- **Depends on**: PoC.3, PoC.5; templates: `StateSearchProvider` (§1.5), `JoinSearchProvider` (§1.2).
- **Files**:
  - `stroom-graphdb-impl/.../GraphSearchProvider implements SearchProvider` — copy `StateSearchProvider`'s
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
  - Registration: a new `GraphDbModule` (Guice) in `stroom-graphdb-impl`, mirroring `PlanBModule` — triple-multibind
    `GraphSearchProvider` (`DataSourceProvider`, `SearchProvider`, `IndexFieldProvider`) one line each exactly as
    `StateSearchProvider`, plus the `DocumentStoreBinder` / `RestResourcesBinder` / doc-cache binds for `GraphDbDoc`.
    Install `GraphDbModule` alongside `PlanBModule` in the app assembly; **`PlanBModule` itself is not edited** (D1).
- **Contract**: a Cypher single-hop query submitted through the normal search REST path returns real rows; the
  `ModelChangeDetector` golden test (see the optimiser plan's Task 6.1a finding) will flag any `SearchRequest`-model
  change — treat a new `Query.graphSpec` field as an additive, minor-version change and update the portrait.
- **Done-when**: an in-module end-to-end test (real coprocessors, real `ResultStore`, PoC.4 graph fixtures) proves
  `MATCH (d:Device{id:'d-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id` returns the expected rows; an `AS OF`
  variant returns the point-in-time-correct rows.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.
- **Outcome (recorded 2026-07-18 — implemented and verified):** `GraphSpec` (a plain additive `Query` field,
  wire-safe sibling of `JoinSpec`) carries the Cypher source text rather than a serialised `LogicalPlan` —
  `stroom-query-planner` depends on `stroom-query-api`, so embedding the plan type itself would be a circular
  module dependency; re-parsing the short single-hop text at execution time is cheap. `CypherCompiler` attaches a
  `GraphSpec` after an eager parse+compile fail-fast check; it does not formally implement the `QueryCompiler`
  interface (dispatch routing between StroomQL/Cypher, and `ExplainPlan` support, are explicitly deferred — see
  "What remains" below). `GraphSearchProvider` resolves the doc via `GraphDbDocCache`, opens its stores via a new,
  deliberately minimal `GraphStoreManager` (no snapshotting/eviction, unlike `ShardManager`), and runs
  `GraphTraversalEngine` synchronously (mirroring `JoinSearchProvider`'s shape, not `StateSearchProvider`'s async
  one — the engine is a single-LMDB-txn call with no shard fan-out). Feeds real `Coprocessors`/`ResultStore` via a
  `buildFieldMapping`/`assembleRow` pair adapted from `JoinSearchProvider`'s: since the engine's `Val[]` output
  order is fixed to the compiled `Project` node's `RETURN`-item order, the mapping resolves each field's
  `FieldIndex` position by name rather than assuming the two orderings coincide. `GraphDbModule` registers the
  triple `DataSourceProvider`/`SearchProvider`/`IndexFieldProvider` binding plus the doc store/cache, mirroring
  `PlanBModule` (left unedited, per D1), and is installed into the app assembly (`CoreModule`). The in-module
  end-to-end test proves both the "latest" and `AS OF` variants of the device/account query return the expected
  rows through genuine coprocessors and a genuine `ResultStore`.
  - **What remains before this is reachable from a real dashboard/query REST call (deliberately out of this
    PoC's scope):** nothing routes an incoming query string to `CypherCompiler` vs the StroomQL compilers — that
    dispatch decision (by query syntax sniffing, by target doc type, or by an explicit query-language selector)
    is a P5+ concern; no REST resource or explorer wiring exists for `GraphDbDoc` yet (P5); ingest is still
    fixture-driven — there is no real `GraphFilter` writing into `GraphStores` from a pipeline (P2).

**PoC exit gate — reached (2026-07-18):** a 1-hop temporal Cypher query runs end-to-end from Cypher text to real
rows through genuine coprocessors and a genuine `ResultStore`, exactly as any StroomQL query would once compiled
to a `SearchRequest` — proven by the PoC.6 in-module test. The one piece of "exactly like any StroomQL query" not
yet true is *reachability*: nothing yet routes a query submitted through the actual dashboard/query REST path to
`CypherCompiler` (see PoC.6's "what remains" above) — that routing, plus real ingest, are the first P-phase
concerns. Ingest is still fixture-driven (real `GraphFilter` is P2); traversal is single-hop single-shard.

---

## 5. Test strategy (quick reference)
Mirror the optimiser project's altitudes:
- **Unit per component**: grammar (parse corpus, in/out-of-subset), Cypher→IR (`text→plan`), each graph DAO
  (round-trip write/read + as-of), traversal engine (`plan+fixtures→Val[]`), temporal correctness (as-of across
  hops, window intersection edge cases).
- **Contract / precondition tests** (Golden rule 5): assert that documented preconditions actually **throw** at
  runtime — null arguments (`requireNonNull`), out-of-range UID widths / bad buffer offsets, and over-length keys
  (`KeyLength.check`) — so a contract violation fails loudly in a test rather than silently corrupting a store.
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

### P1 — Physical graph model, completed (11–23 pw WBS row; narrower in practice — see note) — *after PoC proves the shape*

> **Scoping note (recorded 2026-07-19):** the design doc's §9.1 WBS row (11–23 pw) bundles node store (1.1,
> 2–4 pw), out-edge adjacency (1.2, 3–5 pw) and a first-cut property index (part of 1.5, 2–5 pw) into P1 — but
> PoC.4/PoC.5 already built and tested all three (`GraphNodeDb`, `GraphAdjacencyDb`, `GraphPropertyIndex`,
> `GraphTraversalEngine`). P1's actual remaining scope is narrower: the in-edge mirror (1.3, 1–2 pw), interning
> recorders (1.4, 2–4 pw), the property-index value-tiering gap (a slice of 1.5), and retention/condense/snapshot
> (1.6, 1–3 pw) — roughly **5–12 pw**, not the full WBS row.

Four tasks, each independently verifiable and committable, following the PoC's Depends-on/Files/Contract/
Done-when/Verify shape.

#### Task P1.1 — In-edge adjacency store + direction-aware traversal
- **Depends on**: PoC.4 (`GraphAdjacencyDb` as the structural template), PoC.5 (`GraphTraversalEngine`).
- **Gap this closes**: there is no in-edge DAO at all today — `GraphAdjacencyDb`'s own Javadoc says so explicitly
  ("the in-edge mirror ... is P1, not this class"). Separately, and more importantly, **`GraphTraversalEngine`
  today ignores `Expand.direction()` entirely** — `expandOneHop` unconditionally calls
  `stores.getOutEdges().expandOut(...)`, so a Cypher `<-[:TYPE]-` (`Direction.IN`) or `-[:TYPE]-` (`Direction.BOTH`)
  pattern currently executes as if it were `Direction.OUT` — silently wrong, not rejected. Fixing this is the real
  point of P1.1; the new DAO alone doesn't help until the engine uses it.
- **Files**:
  - `stroom-graphdb-impl/.../GraphInEdgeDb.java` (new) — structurally mirrors `GraphAdjacencyDb` exactly, with
    `srcUid`/`dstUid` swapped throughout: Dbi name `"graph-in-edge"`, key `[dstUid:6][edgeTypeUid:4][srcUid:6]
    [validFrom:6]` (design doc §5.1), value = 1-byte tombstone/present tag + `GraphPropsCodec`-encoded properties.
    `insert`/`delete` mirror `GraphAdjacencyDb`'s exactly (same tombstone-write-not-key-delete semantics — this is
    also an append-only temporal store). `expandIn(Txn readTxn, long dstUid, @Nullable Long edgeTypeUid, Instant
    asOf, Consumer<Neighbour> consumer)` mirrors `expandOut`: prefix-scan `[dstUid][edgeTypeUid]`, group by
    contiguous `srcUid` runs, emit each group's floor version. Same `Neighbour(long neighbourUid, Map<String,Val>
    edgeProperties)`-shaped record (rename the field generically since it's now the *source*, not the destination).
  - `GraphStores.java` (modified) — add an `inEdges` field + `getInEdges()` getter, opened alongside `outEdges` in
    `open(...)`.
  - `GraphTraversalEngine.java` (modified) — `expandOneHop` dispatches on `expand.direction()`:
    `Direction.OUT` → `stores.getOutEdges().expandOut(...)` (today's only path); `Direction.IN` →
    `stores.getInEdges().expandIn(...)`; `Direction.BOTH` → union the neighbour sets from both (a neighbour
    reachable via both an out-edge and an in-edge with the same edge type is one row per direction it's reachable
    by — Cypher's undirected pattern semantics match a relational union, not a distinct-neighbour dedup, matching
    how openCypher itself treats `-[:T]-`). Every edge write path (currently only test fixtures until P2's
    `GraphFilter` exists) must write **both** the out-edge and in-edge entry for a single logical edge — document
    this dual-write contract prominently on `GraphAdjacencyDb`/`GraphInEdgeDb`'s Javadoc now, since P2's
    `GraphFilter` will be the first real caller and must not forget the second write.
  - Tests: `TestGraphInEdgeDb` (round-trip write/read/as-of, mirroring `TestGraphPhysicalStores`'s out-edge
    coverage); extend `TestGraphTraversalEngine` with `<-[:TYPE]-` and `-[:TYPE]-` query cases proving they now
    return the correct (previously wrong) rows.
- **Contract**: for a single logical edge `(src, edgeType, dst, validFrom)`, both `GraphAdjacencyDb` (keyed by
  `src`) and `GraphInEdgeDb` (keyed by `dst`) hold a version with identical `validFrom`/properties — callers are
  responsible for writing both (no cross-DAO transactional enforcement is added here; a mismatch is a caller bug,
  not a storage-layer concern, exactly as Plan B trusts its own DAO callers).
- **Done-when**: a `<-[:CONNECTED_TO]-` query against the existing device/account fixture (reversed: seed the edge
  from account to device, query `MATCH (a:Account)<-[:CONNECTED_TO]-(d:Device...)`) returns the same row a
  forward query would for the mirror-image edge; a `-[:CONNECTED_TO]-` query returns the union.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

#### Task P1.2 — Interning hardening (used-lookups recorders)
- **Depends on**: P1.1 (so all three adjacency-shaped DAOs exist before wiring recorders into their retention
  passes — recorders are consumed by P1.4, but registered here since they're a per-namespace concern independent
  of retention's own scheduling).
- **Gap this closes**: none of `GraphStores`'s four `UidLookupDb` namespaces (`node-uid`, `label-uid`,
  `edge-type-uid`, `property-key-uid`) has a `UsedLookupsRecorder` today — a UID interned once (e.g. a label used
  by a single node that's since been retention-deleted) stays in the lookup table forever, an unbounded leak once
  P2 ingest starts writing continuously. Plan B's own DAOs (`TemporalStateDb` et al.) already solve this with
  `UidLookupRecorder`/`HashLookupRecorder` (`stroom.planb.impl.dao`) — mark-and-sweep: `recordUsed` during a
  retention pass marks a UID as still-referenced; `deleteUnused` afterwards walks the whole lookup table and
  drops anything unmarked.
- **Files**:
  - `GraphStores.java` (modified) — construct a `UidLookupRecorder` for each of the 4 `UidLookupDb`s (mirroring
    `TemporalStateDb`'s constructor pattern: `this.xUidRecorder = new UidLookupRecorder(env, "<name>", xUidDb)` —
    confirm the exact constructor shape against `UidLookupRecorder`'s real signature, not assumed here) and expose
    them (package-private getters, or pass them directly into the DAOs that need them at construction — prefer
    the latter, matching how Plan B DAOs take their recorders as constructor args rather than reaching back into
    a shared owner).
  - `GraphNodeDb.java` (modified) — its `deleteOldData`/`condense` (built in P1.4, but the call sites for
    `labelUidRecorder.recordUsed(...)`/`propertyKeyUidRecorder.recordUsed(...)` per surviving row belong here,
    since P1.4 depends on this task providing the recorders to call).
  - `GraphAdjacencyDb.java`/`GraphInEdgeDb.java` (modified) — same, for `edgeTypeUidRecorder` (and `nodeUidDb`'s
    recorder, since `srcUid`/`dstUid` are also interned node UIDs referenced from the adjacency stores, not just
    from `GraphNodeDb`'s own key prefix).
  - `GraphPropertyIndex.java` (modified) — `nodeUidRecorder` (the `nodeUid` suffix in its key is also an
    interning reference) plus whatever P1.3's tiering introduces (a `UID_LOOKUP`/`HASH_LOOKUP` tier needs its own
    recorder, via `VariableUsedLookupsRecorder` — see P1.3).
  - Tests: `TestGraphStores`/DAO-level tests proving a UID referenced only by now-deleted rows is actually swept
    after a `deleteUnused` pass, and a UID still referenced by a surviving row is not.
- **Contract**: `recordUsed` is called for every UID a *surviving* row references, before `deleteUnused` runs for
  that namespace; calling `deleteUnused` before all recorders have finished their `recordUsed` pass for a given
  retention cycle would incorrectly sweep still-live UIDs — document this ordering constraint clearly (mirrors
  Plan B's own `deleteOldData` pattern: record-then-sweep, never interleaved across DAOs).
- **Done-when**: a node/edge/label/property-key interned, then fully retention-deleted (no surviving reference
  anywhere), no longer appears in its `UidLookupDb` after a full retention pass across all four graph DAOs;
  a UID still referenced by any surviving row survives the sweep.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

#### Task P1.3 — Property-index value tiering
- **Depends on**: none beyond PoC.4 (`GraphPropertyIndex` already exists; this task modifies it in place).
- **Gap this closes**: `GraphPropertyIndex.insert` inlines `valueBytes` directly into the LMDB key unconditionally
  — its own Javadoc admits the composite key "must not exceed `Db.MAX_KEY_LENGTH` (511) — the caller is
  responsible for keeping anchor values short... a documented future hash-lookup escalation, not handled here."
  Any property value near/over that bound (long strings, e.g.) simply fails today rather than escalating. Plan
  B's `VariableKeySerde` (`stroom.planb.impl.serde.temporalkey`/`.keyprefix`) already solves exactly this with a
  3-tier scheme: **≤32 bytes → DIRECT inline; >32 and ≤511 bytes → `UidLookupDb`-backed `UID_LOOKUP` tier; >511
  bytes → `HashLookupDb`-backed `HASH_LOOKUP` tier** (a leading `VariableValType` tag byte selects the tier on
  read).
- **Files**:
  - `GraphPropertyIndex.java` (modified) — adopt the same tiering for the `valueBytes` component of its key: add a
    `UidLookupDb`/`HashLookupDb` pair (opened in `GraphStores`, passed in at construction, mirroring how
    `VariableKeySerde` is constructed against an owning `PlanBEnv`), encode/decode via the same `VariableValType`
    tag-byte discipline (reuse Plan B's tiering logic directly if it's exposed at a reusable granularity — check
    during implementation whether `VariableKeySerde`'s tiering can be called directly or must be adapted/copied,
    since it's currently coupled to a `TimeSerde` suffix the property index doesn't have). Wire a
    `VariableUsedLookupsRecorder` for the new lookup pair (consumed by P1.2/P1.4's retention sweep).
  - `GraphStores.java` (modified) — open the new `UidLookupDb`/`HashLookupDb` pair for the property index.
  - Tests: extend `TestGraphPhysicalStores` with a property value long enough to force each tier (a short string
    for DIRECT, a ~100-byte string for UID_LOOKUP, a >511-byte string for HASH_LOOKUP), proving `findAnchors`
    still resolves correctly at every tier.
- **Contract**: `findAnchors`'s external behaviour (candidates for a given `(labelUid, propKeyUid, valueBytes)`)
  is unchanged by tiering — this is purely an internal encoding change; no caller (`GraphTraversalEngine`) needs
  to change.
- **Scope limit (documented, not deferred silently)**: this task does **not** address property-index *retention*
  (stale anchors left behind after a node's property value is deleted/changed) — that's P1.4's concern, and P1.4
  documents its own scope limit there. This task is encoding only.
- **Done-when**: a property anchor lookup succeeds for values at all three tier boundaries (31/32/33 bytes,
  510/511/512 bytes) with `findAnchors` returning the expected node UID in each case.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

#### Task P1.4 — Retention / condense / snapshot
- **Depends on**: P1.1 (in-edge DAO must exist to be retained too), P1.2 (recorders must exist to be swept),
  D8 (the `GraphDbDoc` configurable surface — this task is D8's "retention/temporal-precision policy" bullet,
  made concrete), and a new decision **D9** (below).
- **Decision D9 — does graph retention reuse Plan B's `Shard`/`ShardManager` machinery, or get its own?**
  **Recommended and assumed by this task's design: no reuse.** `ShardManager` is Plan B's *distributed* shard
  abstraction (cross-node snapshot transfer, `FileTransferClient`, per-node shard placement) — machinery that
  exists because a Plan B state store can be sharded and replicated across the cluster. A `GraphDbDoc`'s
  `GraphStores` is a single `PlanBEnv` per doc, and P0.1 already fixed v1 partitioning as "by graph id, zero
  cross-shard hops" (the cross-node/P8 concern is explicitly deferred). Reusing `ShardManager` would mean
  standing up node-distribution/snapshot-transfer machinery this project doesn't need yet for a benefit (shared
  scheduling code) that a much smaller purpose-built job already gets. **Decide when implementing**: if this
  project's needs change (e.g. multi-node query fan-out lands before P8 reprioritises it), revisit.
- **Files**:
  - `stroom-core-shared/.../GraphDbDoc.java` (modified) — add a `RetentionSettings retention` field (reuse
    `stroom.planb.shared.RetentionSettings` directly rather than inventing a graph-specific type — same
    `enabled`/`duration`/`useStateTime` shape; `useStateTime` is likely always `false` for a graph doc since
    there's no separate "state time" axis here, but keeping the same wire type avoids a divergent, harder-to-
    reuse config surface for a distinction that doesn't cost anything to carry unused). This is D8's
    "retention... policy" field, made concrete.
  - `GraphNodeDb.java`/`GraphAdjacencyDb.java`/`GraphInEdgeDb.java` (modified) — each gets `deleteOldData(Instant
    deleteBefore)`: per-entity (node uid; or (src,edgeType,dst) / (dst,edgeType,src) triple) walk of that
    entity's own `validFrom` version run, keeping the single latest version at-or-before `deleteBefore` (the
    floor version still needed to answer `AS OF`/expand-as-of queries for any retained instant) and deleting
    every strictly-older version of that same entity — mirrors `TemporalStateDb.deleteOldData`'s per-key-prefix
    walk exactly. Each DAO calls its relevant `UidLookupRecorder.recordUsed(...)` (from P1.2) for every UID a
    *surviving* version still references.
  - `GraphStores.java` (modified) — `long deleteOldData(GraphDbDoc doc)`: if `doc.getRetention().isEnabled()`,
    compute `deleteBefore`, call each DAO's `deleteOldData(deleteBefore)` under a single write transaction (or a
    batched sequence, matching Plan B's `writer.shouldCommit()` batching for large stores), then — only after
    *all* DAOs have run their pass — call `deleteUnused` on all four UID recorders plus the P1.3 property-value
    lookup recorder (the record-then-sweep ordering P1.2 documents). Also `condense(GraphDbDoc doc)`, mirroring
    Plan B's condense semantics (merge/drop redundant same-value consecutive versions) if the graph's temporal
    model benefits from it in practice — **assess during implementation whether condense is worth building for
    v1**: unlike Plan B state (which can churn the same value every ingest tick), a graph's node/edge properties
    may change infrequently enough that condense's benefit is marginal; if so, ship `deleteOldData` only and
    record that decision here rather than building unused machinery.
  - **Property-index retention (documented scope limit, not a gap to silently leave)**: `GraphPropertyIndex`
    anchor entries for a value only referenced by a now-retention-deleted node version become stale (the anchor
    still points at a node whose *current* properties no longer match). This is **not a correctness bug** —
    `GraphTraversalEngine.resolveAnchors` already re-validates every candidate against the node's live,
    as-of-resolved properties (PoC.5), so a stale anchor is filtered out, never returned. It **is** an unbounded
    bloat source (dead anchors accumulate). For v1: rely on the already-existing `GraphStores.rebuild()` (full
    drop-and-reprovision-from-source-of-truth, built in PoC.0/PoC.4) as the compaction backstop, invoked
    operator-side/out-of-band, not on a schedule. True incremental anchor sweeping (diffing a node's old vs new
    property map on every version change and deleting only the now-orphaned anchors) is deferred — flag as a
    candidate for P1's own follow-up if bloat proves material in practice, not built speculatively here.
  - A scheduled job (mirroring Plan B's `ScheduledJobsBinder` pattern in `PlanBModule`, but new and
    graph-specific in `GraphDbModule`): iterate every `GraphDbDoc` via `GraphDbDocStore.list()`, resolve its
    `GraphStores` via `GraphStoreManager`, call `deleteOldData`(/`condense` if built). A single cron job is
    sufficient for v1 (no separate merge/snapshot-creation/snapshot-cleanup jobs — those exist in Plan B because
    of its distributed-shard model, explicitly not reused per D9). Suggest `EVERY_10_MINUTES` matching Plan B's
    maintain cadence, `.advanced(true)`.
  - Tests: `TestGraphStores`/DAO-level tests proving retention keeps the correct floor version and deletes
    everything older, across all three temporal DAOs; an integration-style test proving a full
    `deleteOldData(doc)` pass on `GraphStores` leaves `AS OF` queries for retained instants still correct while
    reclaiming space for fully-superseded old versions.
- **Contract**: retention never deletes the single version needed to answer a floor lookup for any instant `>=
  deleteBefore`; a UID sweep never removes a UID referenced by a version that survived retention.
- **Done-when**: seed a node/edge with 3+ versions spanning a retention boundary; after `deleteOldData`, `AS OF`
  queries for instants at-or-after the boundary return unchanged results, `AS OF` queries for instants before the
  boundary either return the floor version that survived (if one does) or nothing (if none did) — never a wrong
  answer; a UID referenced only by fully-purged versions is gone from its `UidLookupDb`.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

**P1 exit gate — reached (2026-07-19):** `<-[:TYPE]-` and `-[:TYPE]-` Cypher patterns traverse correctly (not
silently as `-[:TYPE]->`) via the new `GraphInEdgeDb` + direction-aware `GraphTraversalEngine.expandOneHop`
(P1.1); long property values no longer fail at the `Db.MAX_KEY_LENGTH` boundary via the DIRECT/UID_LOOKUP/
HASH_LOOKUP tiering (P1.3); a full retention pass (delete + UID sweep) across the three temporally-versioned
graph DAOs never corrupts a still-reachable floor lookup and reclaims space for fully-superseded data, gated
behind a new `GraphDbDoc.retention` field and its own scheduled job (P1.4, Decision D9). Interning hardening
(P1.2) covers the node/label/edge-type namespaces; the property-key-uid namespace and property-index anchor
bloat are a documented, accepted v1 limitation (backstopped by `GraphStores.rebuild()`), not a silent gap. A
separate, pre-existing property-index DIRECT-tier value-prefix-collision bug was discovered (not introduced) by
P1.3's boundary testing and is tracked as its own follow-up, not folded into this phase.

### P2 — Ingest `GraphFilter` (7–13 pw)

> **Scoping note (recorded 2026-07-19):** `PlanBFilter` is a *misleading* template in one important respect —
> it resolves its target doc dynamically per-XML-record (from a `<map>` element's text, via `ShardWriter.getDoc`),
> and writes into a **per-stream shard directory that gets zip-shipped and merged later** by `ShardManager`/
> `MergeProcessor` — a distributed-shard architecture P1's Decision D9 already declined to build for graphs (a
> `GraphDbDoc` is one long-lived, directly-opened `GraphStores`, not a shardable/mergeable store). `GraphFilter`
> instead resolves its **one** target `GraphDbDoc` via a `@PipelineProperty` `DocRef` (like `DynamicIndexingFilter`
> does for an index), exactly as design doc §2.1 already specifies: *"`GraphFilter` writes into the owning
> `GraphDbDoc`'s stores"* — and writes directly into that doc's live `GraphStores` via a single held-open
> `LmdbWriter`, no shard/merge/zip/transfer step at all.

Three tasks, each independently verifiable and committable, following the established Depends-on/Files/
Contract/Done-when/Verify shape.

#### Task P2.1 — Graph-mutation XML schema (resolves D7)
- **Depends on**: none (a schema-only task; P2.2 consumes it).
- **Decision D7 — resolved: a small new schema, not a `reference-data:2` convention.** `reference-data:2`'s XSD
  (`reference_data_v2_0_1...xsd`) is narrow — `referenceData` → `reference` → `map` + (`key`|`range`) + `value` —
  with no concept of edges, labels, multiple properties, or a `validFrom`/effective-time per record at all.
  Extending it to cover graph mutations would mean recreating most of what a purpose-built schema needs anyway
  (exactly the amount of extension `plan-b:2` already needed over plain ref-data), so "reuse" would only be a
  reuse of the root-element wrapper, not of any real vocabulary. Design doc §5.2's own wording ("a small new
  schema of node/edge upserts") already leans this way; the `reference-data:2` convention was offered only as a
  fallback, never elaborated. A new namespace is more honest than stretching an unrelated one.
- **Files**: `stroom-graphdb-impl/src/main/resources/.../graph_mutation_v1_0.xsd` (or wherever this project's
  convention places pipeline-element XSDs — check `plan_b_v2_0.xsd`'s location as the precedent) — the frozen v1
  mutation vocabulary:
  ```xml
  <graph xmlns="graph-mutation:1" version="1.0">
      <node id="d-42" validFrom="2026-01-01T00:00:00.000Z">
          <label>Device</label>
          <property name="serial">ABC123</property>
      </node>
      <edge type="CONNECTED_TO" validFrom="2026-01-01T00:00:00.000Z">
          <src>d-42</src>
          <dst>account-a</dst>
          <property name="channel">wifi</property>
      </edge>
      <node-delete id="d-42" validFrom="2026-02-01T00:00:00.000Z"/>
      <edge-delete type="CONNECTED_TO" validFrom="2026-02-01T00:00:00.000Z">
          <src>d-42</src>
          <dst>account-a</dst>
      </edge-delete>
  </graph>
  ```
  `node`/`edge` map 1:1 onto `GraphNodeDb.insert`/`GraphAdjacencyDb.insert`+`GraphInEdgeDb.insert`; `node-delete`/
  `edge-delete` onto their `delete` counterparts (design §5.4: "a supersede/delete writes a new [tombstone]
  version" - this schema has no in-place update, matching the frozen temporal model exactly).
  - `id`/`type`/`src`/`dst` are the *external* string identifiers interned via `GraphStores`'s `UidLookupDb`
    namespaces at ingest time - never raw UIDs in the XML.
  - `validFrom` is a **required** attribute (ISO-8601 instant) - v1 does not default it from event/receipt time;
    that would conflate two different "when did this happen" concepts (wall-clock ingest time vs the
    domain-modelled effective time a graph mutation is about) that deserve a deliberate decision, not a silent
    default. Document this as a documented v1 requirement, not gap.
  - `<property>` values are **string-only in v1** (plain element text, matching `GraphPropsCodec`'s existing
    `Val`-typed storage via `ValString.create(...)`) - a `type` attribute for numeric/boolean/date property values
    is a documented future extension (mirrors this project's repeated "documented escalation, not v1" pattern -
    e.g. P0.1's property-index hash-lookup tier, P1.3's tiering itself), not built speculatively here since
    nothing in the PoC/P1 traversal engine yet needs typed ingested properties beyond what test fixtures already
    construct directly in Java.
- **Contract**: the XSD is descriptive/validating only - `GraphFilter` (P2.2) parses by SAX local-name dispatch
  exactly as `PlanBFilter` does, not by generated JAXB bindings, so the XSD's job is schema validation in tests
  (mirroring `TestPlanBFilter`'s `javax.xml.validation.Schema` pattern) and as documentation for XSLT authors
  writing the "events → graph-mutation XML" transform (design §5.2 step 2) - it is not a runtime dependency of
  `GraphFilter` itself.
- **Done-when**: the XSD validates the example XML above (and rejects an `<edge>` missing `type`/`src`/`dst`, or
  a `node`/`edge` missing `validFrom`).
- **Verify**: a small XSD-only test using `javax.xml.validation.Schema`/`Validator` (no `GraphFilter` involved yet).

#### Task P2.2 — `GraphFilter` + pipeline wiring
- **Depends on**: P2.1 (the mutation vocabulary), all of P1 (writes through the hardened DAOs - interning
  recorders, direction-aware adjacency, tiered property index - so ingested data gets the same correctness/
  retention guarantees as test-fixture-seeded data).
- **Files**:
  - `stroom-graphdb-impl/.../pipeline/GraphFilter.java` (new) — `extends AbstractXMLFilter`, `@ConfigurableElement
    (type = "GraphFilter", category = FILTER, roles = {ROLE_TARGET, ROLE_HAS_TARGETS}, ...)`. One
    `@PipelineProperty` + `@PipelinePropertyDocRef(types = GraphDbDoc.TYPE)` setter (`setGraphDb(DocRef)`) -
    **not** `PlanBFilter`'s dynamic per-record `<map>`-name resolution (see this section's scoping note above).
    Constructor `@Inject`s `GraphDbDocCache`, `GraphStoreManager`. `startProcessing()`: resolve `doc =
    graphDbDocCache.get(graphDbRef.getName())`, `stores = graphStoreManager.getOrOpen(doc)`, `writer =
    stores.createWriter()` (new method - see below) - one writer held open for the whole stream, mirroring how
    `ShardWriter`/`WriterInstance` hold a writer open across a stream in the Plan B precedent, just without the
    shard/merge step. SAX dispatch on `endElement`, keyed on lower-cased local name (`node`/`edge`/`node-delete`/
    `edge-delete`), accumulating child element text (`label`/`property`/`src`/`dst`) into instance fields between
    `startElement`/`endElement` exactly as `PlanBFilter` does. Per record: intern `id`/`type`/label strings via
    `stores.getNodeUids()`/`getEdgeTypeUids()`/`getLabelUids()` (`UidLookupDb.put`, get-or-create), intern each
    `<property name="...">` name via `stores.getPropertyKeyUids()` **only if** the property is also meant to be
    anchor-indexed (see `GraphNodeTypeMapping`/design §5.6 for which properties are indexable - if that mapping
    doesn't exist yet for a given label, index every node property equality-style as a pragmatic v1 default and
    document the choice), build the `Map<String, Val>` via `ValString.create(...)`, then call
    `GraphNodeDb.insert`/`delete` or **both** `GraphAdjacencyDb.insert`/`delete` **and**
    `GraphInEdgeDb.insert`/`delete` for one `<edge>`/`<edge-delete>` (P1.1's dual-write contract - a single
    forgotten call here is exactly the bug class P1.1 fixed in the traversal engine, don't reintroduce it on the
    write side). Call `writer.tryCommit()` after each record (batches automatically past `LmdbWriter`'s internal
    10 000-change threshold - no manual batching logic needed). `endProcessing()`: `writer.close()` (commits any
    remainder), call `super.endProcessing()`.
  - `GraphStores.java` (modified) — add `public LmdbWriter createWriter()` delegating to `env.createWriter()` -
    the manually-open/close counterpart to the existing callback-wrapped `write(Function<LmdbWriter,T>)`, needed
    because `GraphFilter` holds one writer open across an entire SAX stream rather than one closed transaction
    per call.
  - `stroom-graphdb-impl/.../pipeline/GraphElementModule.java` (new) — `extends PipelineElementModule`;
    `configureElements()` calls `bindElement(GraphFilter.class)`, mirroring `PlanBElementModule` exactly.
  - `GraphDbModule.java` (modified) — `install(new GraphElementModule())` in `configure()` (mirrors
    `PlanBModule`'s `install(new PlanBElementModule())` - this line does not exist yet, confirmed by reading the
    current file).
  - Property-anchor indexing note: `GraphFilter` also calls `GraphPropertyIndex.insert` for whichever properties
    it decides to index (see above) - every `<node>` write must anchor-index at least the properties a later
    `MATCH (n:Label {prop: value})` will search by, or PoC.5's `resolveAnchors` (which requires at least one
    label + one property predicate, no full-label scan) will find nothing for that node.
- **Contract**: a `<node-delete>`/`<edge-delete>` writes a tombstone (never deletes a key), matching every DAO's
  existing `delete(...)` semantics; re-processing the same XML twice is idempotent for interning (`UidLookupDb.put`
  is get-or-create) but **not** for node/edge versions themselves - inserting the same `(entity, validFrom)`
  twice just overwrites that exact version in place (LMDB `put`), so reprocessing is safe, not merely tolerated.
- **Done-when**: a real pipeline element instance (constructed directly in a test, not via the full pipeline
  factory/XML-pipeline-config machinery - that integration is a different, heavier test altitude) processes the
  example XML from P2.1 end-to-end via real SAX events and the resulting `GraphStores` answers a `MATCH` query
  (via `GraphTraversalEngine`, reusing PoC.5/PoC.6) with the expected rows.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

#### Task P2.3 — Round-trip tests + rebuild-from-streams
- **Depends on**: P2.2.
- **Files**: `stroom-graphdb-impl/src/test/java/.../TestGraphFilter.java` (new) - drives `GraphFilter` with real
  SAX events (`startDocument`/`startElement`/`characters`/`endElement`/... - either hand-driven or via a real SAX
  parser over an XML string/fixture file, matching `TestPlanBFilter`'s own harness style) against a real
  `GraphStores` (temp-dir, no mocks beyond a fake `GraphDbDocCache`/`GraphStoreManager` resolving to that
  `GraphStores` - mirroring `TestGraphSearchProvider`'s existing fake-cache pattern). Covers: node upsert,
  node-delete (tombstone), edge upsert (both directions written, `GraphInEdgeDb` too), edge-delete, re-processing
  the same stream twice (idempotent), and a full "rebuild from streams" scenario: `GraphStores.rebuild(...)`
  (already built in PoC.0/PoC.4) then re-run `GraphFilter` over the same XML - proving the graph is genuinely a
  rebuildable materialized projection (design §5.2's closing claim), not silently dependent on incremental state
  a rebuild would lose.
- **Contract**: none beyond P2.2's - this task is test coverage, not new production code.
- **Done-when**: `MATCH (d:Device {id:'d-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id` (the same worked example
  used throughout PoC.5/PoC.6/P1's own tests) returns the expected rows when the graph was built entirely by
  `GraphFilter` from XML, not by direct DAO calls in test setup code - closing the gap every prior phase's tests
  left open (all of PoC.4-P1 seeded fixtures via direct `stores.getNodes().insert(...)` calls, never through a
  real ingest path). Reprocessing the same XML twice yields the same query result (idempotent). `rebuild()` +
  re-ingest from the same XML yields the same query result too (rebuild-from-streams works).
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.

**P2 exit gate — reached (2026-07-19):** a feed of graph-mutation XML (produced by an XSLT from raw events, per
design §5.2 - the XSLT step itself is out of scope here, this phase starts from already-transformed graph-
mutation XML) reprocesses via `GraphFilter` into a queryable graph indistinguishable, from the query engine's
perspective, from one seeded by direct test fixtures (`TestGraphFilter`'s
`ingestedGraph_answersTheSameMatchQuery_asDirectlySeededFixtures`, including the `AS OF` variant proving
`validFrom` is genuinely carried through the XML, not defaulted); `GraphStores.rebuild()` + re-ingest reproduces
the same queryable state (`rebuildFromStreams_reproducesTheSameQueryableState`), proving the graph is genuinely
the rebuildable materialized projection the design doc claims. Node-delete/edge-delete tombstones and idempotent
reprocessing are also covered. The XSLT (events → graph-mutation XML) authoring itself, and any real production
feed/pipeline configuration, remain explicitly out of scope - this phase proves the `GraphFilter` half of the
pipeline, not the XSLT half.

### P3 — Variable-length paths / fixpoint (part of the 9–18 pw graph-query line)

> **Scoping note (recorded 2026-07-19):** `CypherToLogicalPlan`'s own Javadoc already lists exactly what it
> deliberately does not lower, with "P3" against each: *"a path pattern with more than one hop; a hop with a
> variable-length (`*min..max`) edge; ... a hop's non-anchor (target) node pattern's own labels or inline
> properties, which ... are not yet represented in the compiled plan and are silently not enforced."* The last
> one is a real, already-present correctness gap (not a missing *feature* - a query with a target-node label/
> property constraint compiles today and silently ignores that constraint), so it is P3.1 below, ahead of
> multi-hop/var-length, both of which depend on it (a chain's middle nodes need the same constraint-carrying
> slot `Expand` currently lacks). The Cypher grammar/AST (PoC.1) already parses chains and bounded var-length
> fully - `AstPathPattern.hops()` is already a `List`, `AstVarLength` already exists - so none of this phase
> touches the grammar; it is entirely `CypherToLogicalPlan` (compile) and `GraphTraversalEngine` (execute) work.

Three tasks, each independently verifiable and committable, following the established Depends-on/Files/
Contract/Done-when/Verify shape.

#### Task P3.1 — Target node label/property constraints
- **Depends on**: none beyond PoC.3/PoC.5 (modifies both in place).
- **Gap this closes**: `MATCH (d:Device {id:'d-42'})-[:CONNECTED_TO]->(a:Account {status:'active'})` compiles
  and runs today, but `a`'s `{status:'active'}` constraint (and any label constraint on `a`) is silently never
  checked - every reachable `Account`, active or not, is returned. This is the sharpest kind of bug to leave
  open: a query that looks correct and returns a plausible-looking result set that is simply wrong.
- **Files**:
  - `stroom-query-planner/.../logical/Expand.java` (modified) — add `List<String> targetLabels` and
    `@Nullable ExpressionOperator targetPropertyPredicate` fields (mirroring `NodeScan`'s own label/property-
    predicate shape, minus the anchor-index-seek concern, since a hop's target is reached via the edge, never
    seeked). Update the record's compact constructor/Javadoc; update every exhaustive `switch` over
    `LogicalPlan`/`Expand` that pattern-matches its fields (check `LogicalPlanExplainer`,
    `OptimisingQueryCompiler`'s plan-walk helpers, `PushFiltersBelowJoinsRule`, `AutoWhereFilterSplitRule`,
    `PlanRewriteUtil` - the same call sites P1's research already catalogued as having exhaustive-switch cases
    for `Expand`/`NodeScan` added in PoC.2, still unreached by Cypher plans in practice but must stay
    compiling).
  - `CypherToLogicalPlan.compilePattern` (modified) — populate `targetLabels`/`targetPropertyPredicate` from
    `hop.node().labels()`/`hop.node().properties()` exactly as `compileNodeScan` already does for the anchor
    (reuse the same term-building code, factored out if that keeps both call sites simple).
  - `GraphTraversalEngine.acceptNeighbour` (modified) — after resolving the target node's version, reject it
    (do not add its row) unless its `labelUids` contain every required target label UID and its properties
    satisfy `targetPropertyPredicate` (reusing `ExpressionPredicateFactory`/`GraphRowValueFunctionFactory`
    exactly as the anchor and `WHERE` predicates already do - the same "bare unqualified field name" caveat
    from `resolveAnchors`'s own fix applies here too, since a target node's inline property map terms are also
    unqualified).
- **Contract**: a target node pattern with no labels/properties behaves exactly as before (an unconstrained
  slot, not a stricter default); a labelled/property-constrained target node filters out non-matching neighbours
  before they reach the `WHERE` predicate or `RETURN` projection.
- **Done-when**: `MATCH (d:Device {id:'d-42'})-[:CONNECTED_TO]->(a:Account {status:'active'})RETURN a.id` against
  a fixture with one active and one inactive reachable account returns only the active one.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `Expand`/`VarLengthExpand` both carry `targetLabels`/`targetPropertyPredicate`
  (added to both together, to touch `PlanRewriteUtil`/`PushFiltersBelowJoinsRule`/`AutoWhereFilterSplitRule`'s
  positional reconstructions only once). `CypherToLogicalPlan` populates them via a `compilePropertyPredicate`
  helper extracted from `compileNodeScan`'s existing term-building logic. `GraphTraversalEngine.acceptNeighbour`
  enforces them via a new `matchesTargetConstraint` method, mirroring `resolveAnchors`'s own bare-unqualified-
  field-name handling. Tests: `TestCypherToLogicalPlan` (compiles labels/predicate onto `Expand`, and the empty
  case), `TestLogicalPlan` (record contract - null-rejection, defensive copy), `TestGraphTraversalEngine` (three
  new execution tests: a real label match, an unknown-label no-match, and a property-value match) - all green.

#### Task P3.2 — Multi-hop fixed-length chains
- **Depends on**: P3.1 (a chain's non-terminal `Expand` nodes need the same target-constraint slot).
- **Gap this closes**: `CypherToLogicalPlan.compilePattern` throws `CypherCompileException` for any pattern with
  more than one hop; `GraphTraversalEngine`'s `PlanShape`/`unwrap` only recognise exactly one optional `Expand`
  between `Project`/`Filter` and the `NodeScan` leaf.
- **Files**:
  - `CypherToLogicalPlan.compilePattern` (modified) — remove the `hops().size() > 1` rejection; fold the hop
    list left-to-right into a chain of `Expand` nodes (`Expand(Expand(NodeScan(...), T1, ...), T2, ...)`),
    anchor-first, exactly matching source order - **not** re-ordered by any selectivity heuristic (see below).
  - `GraphTraversalEngine.java` (modified) — `PlanShape` changes from a single `@Nullable Expand expand` to a
    `List<Expand> hops` (possibly empty); `unwrap` walks a chain of nested `Expand`s (`while (below instanceof
    Expand e) { hops.add(0, e); below = e.input(); }`, since hops must be collected anchor-to-target order but
    are encountered target-to-anchor while unwrapping) down to the `NodeScan` leaf. `execute`'s per-anchor loop
    becomes an iterative fold over `hops`: at each step, expand the CURRENT frontier of rows through the next
    hop, discarding rows that fail that hop's target constraint (P3.1) or (if it's the pattern's last hop) the
    outer `WHERE` predicate - matching the existing single-hop code's own "test as you go" structure, not a
    build-then-filter two-pass (avoids materialising every intermediate hop's full unfiltered row set).
  - **Anchor-selectivity / start-node planner rules (from the rough outline) — deliberately not built**: Cypher
    chains in this v1 subset are always compiled anchor-first, left-to-right, and the anchor is always the one
    node with a property-index-seekable predicate (P3.1's constraints on OTHER hops are post-expand filters, not
    alternative seek points - only the true anchor has an index access path at all). There is no genuine "which
    node should I start from" choice to make yet, so no cost-based re-ordering is needed for v1; a future phase
    revisits this if/when non-anchor nodes gain their own seekable access paths.
  - Tests: extend `TestCypherToLogicalPlan` with a 2/3-hop chain compiling into the expected nested `Expand`
    shape; extend `TestGraphTraversalEngine`/fixtures with a 2-hop query (e.g. `Device -[:CONNECTED_TO]->
    Account -[:OWNED_BY]-> Person`) proving the correct end-to-end row set, including a case where a middle
    hop's target constraint (P3.1) prunes a path.
- **Contract**: an existing single-hop (0 or 1 `Expand`) compiled plan and its execution are bit-for-bit
  unchanged - the chain case is a strict generalisation, not a rewrite of the existing path.
- **Done-when**: a 2-hop and a 3-hop chain query against a hand-built fixture return exactly the hand-computed
  expected row set; a single-hop query's result is unchanged from before this task.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `CypherToLogicalPlan.compilePattern` now folds `pattern.hops()` left-to-right
  into nested `Expand`s (no size limit); `GraphTraversalEngine`'s `PlanShape.expand` became
  `List<Expand> hops`, `unwrap` collects the chain (prepending, since hops are encountered target-to-anchor while
  unwrapping), and `execute` replaced the single-hop `expandOneHop`/`acceptNeighbour` pair with a `Frontier`-based
  iterative fold (`expandChainHop`/`acceptChainNeighbour`): each step expands the current frontier through the
  next hop, discarding rows failing that hop's target constraint (P3.1), and only the pattern's LAST hop's
  acceptance also tests the outer `WHERE` predicate (a 0-hop bare anchor tests `WHERE` directly against the
  anchor frontier). No anchor-selectivity re-ordering was built, per the scoping note above. Tests: 2 new
  `TestCypherToLogicalPlan` cases (2-hop and 3-hop chains compile to the expected nested-`Expand` shape, in
  source order); 3 new `TestGraphTraversalEngine` cases against a dedicated 3-hop fixture (`Device
  -CONNECTED_TO-> Account -OWNED_BY-> Owner -EMPLOYED_BY-> Company`) - a 2-hop query's full row set, a 2-hop
  query where a middle hop's own label constraint (P3.1) prunes one of the two paths, and a 3-hop query's full
  row set (including a legitimate duplicate row where two distinct paths converge on the same company). All
  pre-existing single-hop/no-hop tests pass unchanged, confirming the contract that this is a strict
  generalisation, not a rewrite.

#### Task P3.3 — Bounded variable-length paths (BFS + cycle guard)
- **Depends on**: P3.1 (the var-length target variable needs the same label/property constraint slot -
  `VarLengthExpand` already has no such slot either; add it alongside `Expand`'s in P3.1, or here if P3.1 lands
  first without it - decide during implementation which task actually adds it to avoid duplicated work).
- **Gap this closes**: `CypherToLogicalPlan.compilePattern` throws for `edge.varLength() != null`;
  `VarLengthExpand` (built in PoC.2) has never been produced or consumed by anything - confirmed dead IR since
  its own Javadoc says so explicitly ("a compiled plan containing this node is rejected at compile time").
- **Files**:
  - `CypherToLogicalPlan.compilePattern` (modified) — when `edge.varLength() != null`, compile a
    `VarLengthExpand` instead of throwing: `minHops` = `varLength.min()` or Cypher's own default of 1 if absent,
    `maxHops` = `varLength.max()` (always present, per the grammar). A var-length hop is still rejected if it is
    not the pattern's *only* hop (chaining a var-length hop with fixed hops on either side is a real further
    generalisation of P3.2 - out of scope here, thrown with a clear message, not silently mishandled).
  - `GraphTraversalEngine.java` (modified) — a new execution path for `VarLengthExpand` (parallel to `Expand`'s):
    for each anchor, a **bounded BFS** over the adjacency store: maintain a frontier of `(nodeUid, pathVisited)`
    pairs (a fresh `Set<Long>` per path branch, not one global per-anchor set - a node reachable via two
    different, non-overlapping paths of different lengths within `[minHops, maxHops]` is two valid, distinct
    results, only a node repeating *within one path* is a cycle to guard against); at each depth, fully
    materialise that depth's neighbours via `GraphAdjacencyDb.expandOut`/`GraphInEdgeDb.expandIn`'s existing
    `Consumer`-driven, fully-synchronous-per-call shape (design doc §8's flagged cursor-lifetime risk is
    addressed by construction here: never open a nested cursor from inside another's callback - collect one
    depth's full neighbour list, close that call's cursor, *then* recurse into the next depth using the plain
    materialised list, exactly how `GraphAdjacencyDb.expandOut`'s own single-call contract is designed to be
    used); emit a row for every `(nodeUid, depth)` with `depth` in `[minHops, maxHops]` that also passes the
    target label/property constraint (P3.1) - matching literal Cypher path semantics (the same node reached at
    two different depths within range is two separate results), not de-duplicated by node identity.
  - Tests: a fixture with a genuine cycle (`a -> b -> c -> a`) proving `-[:T*1..5]->` terminates and returns the
    correct finite reachable set, not an infinite loop or a `StackOverflowError`; a fixture proving `minHops > 1`
    excludes closer neighbours; a fixture proving the same node reached at two different depths within range
    yields two rows.
- **Contract**: `VarLengthExpand`'s own documented precondition (`maxHops` always finite, enforced at
  construction) is the only bound needed for termination - no separate step-count safety valve is layered on top
  since the IR type itself cannot represent an unbounded request.
- **Done-when**: `-[:T*1..k]->` over a cyclic fixture graph returns the correct, hand-computed finite reachable
  set with no timeout/stack overflow; `-[:T*2..3]->` excludes 1-hop neighbours.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `VarLengthExpand` got the identical `targetLabels`/`targetPropertyPredicate`
  fields `Expand` got in P3.1 (decided during P3.1 to avoid a second pass over the same rewrite-rule call
  sites). `CypherToLogicalPlan.compilePattern` now compiles a single var-length hop to `VarLengthExpand`
  (`minHops` defaults to 1 per Cypher's own convention when `AstVarLength.min()` is absent); a var-length hop
  chained with any other hop still throws, with a message naming the actual restriction ("must be the pattern's
  only hop"). `GraphTraversalEngine`'s `PlanShape` gained a `@Nullable VarLengthExpand varLengthExpand` field
  (mutually exclusive with the fixed-length `hops` list, matching the compiler's own mutual exclusion);
  `expandVarLength` performs the bounded BFS exactly as designed - each depth's neighbours are fully
  materialised via one `expandOut`/`expandIn` call before recursing (never a nested cursor), a fresh
  per-path `Set<Long> visited` guards against cycles (only within-path repeats are excluded; the same node at
  two different depths, or via two different paths, is not deduplicated), and termination is guaranteed by the
  `depth <= maxHops` loop bound alone - the cycle guard exists purely for result correctness, not termination.
  `minHops == 0` (a zero-length path binding the target to the anchor itself) is also handled, though not
  required by the Done-when criteria. Tests: 3 new `TestCypherToLogicalPlan` cases (compiles to `VarLengthExpand`
  with the right bounds/labels, `min` omitted defaults to 1, chaining with another hop still throws); 3 new
  `TestGraphTraversalEngine` cases - a genuine 3-node cycle proving termination and the correct finite reachable
  set (no spurious repeated-cycle rows), a converging-paths fixture proving the same node at two depths yields
  two rows, and the same fixture with `minHops=2` proving closer neighbours are excluded. All pre-existing tests
  pass unchanged.

**P3 exit gate: met.** `-[:T*1..k]->` returns correct paths with cycle safety over a real cyclic fixture, matching
a hand-computed expected set; a fixed-length multi-hop chain (`-[:T1]->()-[:T2]->`) returns the correct row set;
a target (non-anchor) node's own label/property constraints are enforced, not silently ignored. Anchor-
selectivity/start-node re-ordering remains out of scope (no genuine choice to make yet in this v1 subset - see
Task P3.2's note). P3 (Tasks P3.1-P3.3) is complete; P4 (Temporal ranges) is next, pending explicit go-ahead.

### P4 — Temporal ranges (5–10 pw) — *after 0.3*

> **Scoping note (recorded 2026-07-19):** design doc &sect;5.5 lists five execution-engine pieces; items 4
> (the fixpoint/bounded-transitive-closure operator) and 5 (the as-of point-in-time lookup) are **already built** -
> item 4 by P3.3's BFS, item 5 by PoC.5's floor-lookup `GraphNodeDb.getNode`/`GraphAdjacencyDb.expandOut`/
> `GraphInEdgeDb.expandIn`, which `GraphTraversalEngine` already uses for every `AS OF`/no-clause query. So P4's
> real remaining scope is narrower than the phase heading suggests: **only `AROUND`/`BETWEEN` window-intersection
> execution**, which `GraphTraversalEngine.resolveAsOf` currently rejects outright
> (`UnsupportedOperationException`, "a P4 deliverable"). The &sect;6 worked example's prose ("expand `OWNS`
> reverse, as-of 09:00Z") reads as if it mixes a window hop with an as-of hop in one query - but `AstMatch` carries
> exactly one `AstTemporal` clause for the whole pattern, and P0.3's frozen rule is explicit that windows "apply
> per-edge" uniformly ("each edge has a version intersecting the window; not required simultaneous") - so the
> query's single `AROUND` clause resolves to one `[from, to]` window checked identically at *every* hop, not a
> per-hop-differing mode. There is no mixed-mode case to build (and the grammar has no syntax for one), so this
> phase needs no changes to `CypherToLogicalPlan`/the AST/`TemporalContext` - all of which already carry
> `AROUND`/`BETWEEN` end-to-end (`TemporalContext.window(...)`, resolved by `CypherToLogicalPlan.resolveTemporal`)
> - this phase is entirely `stroom-graphdb-impl` storage + execution work.
>
> **A window's canonical version, when more than one of an entity's versions intersects it (a decision P0.3 left
> open - it only defines "intersects", not "which one to project"):** take the version with the greatest
> `validFrom` among those intersecting (i.e. "the state as it stood by the end of the window, restricted to
> versions that were live at some point within it") - if that version happens to be a tombstone, the entity/edge
> counts as absent for the whole window, exactly as a tombstoned floor version already means "absent" for
> `AS OF`. This mirrors the existing floor-lookup's own "last-applicable-version-wins" rule (`expandOut`/`expandIn`
> already do this for `AS OF`; window mode is the same rule with an intersection test instead of a
> less-than-or-equal test), so implementation and code shape stay close to the existing floor-lookup methods.

Two tasks: storage-layer window access paths, then wiring them into the traversal engine (the same
storage-then-execution split P3 used for its own two execution-facing tasks).

#### Task P4.1 — Window-based physical store access paths
- **Depends on**: none beyond PoC.4/P1.1 (extends the existing DAOs; no schema/key-layout change - a window scan
  reads the exact same version runs the floor lookup already does).
- **Gap this closes**: `GraphNodeDb`/`GraphAdjacencyDb`/`GraphInEdgeDb` only expose an as-of floor lookup; there is
  no access path that can answer "does a version of this entity intersect `[from, to]`" at all.
- **Files**:
  - `GraphNodeDb.java` (modified) — add `Optional<NodeVersion> getNodeWindow(Txn<ByteBuffer> readTxn, long
    nodeUid, Instant from, Instant to)`: forward-scan the `nodeUid`-prefixed run (buffering its (validFrom, value)
    pairs first, since - unlike the reverse-bounded floor lookup - a window scan cannot early-exit and needs each
    entry's *next* `validFrom` to compute its own half-open interval's end), tracking the latest entry whose
    `[validFrom, nextValidFrom)` intersects `[from, to]` (`nextValidFrom` = the following entry's `validFrom`, or
    `Instant.MAX` for the run's last entry) using the P0.3 rule `validFrom <= to && nextValidFrom > from`; decode
    and return that entry if present, empty if none intersects or the latest intersecting entry is a tombstone.
  - `GraphAdjacencyDb.java` (modified) — add `void expandOutWindow(Txn<ByteBuffer> readTxn, long srcUid, long
    edgeTypeUid, Instant from, Instant to, Consumer<Neighbour> consumer)`: same `[srcUid][edgeTypeUid]` prefix
    scan as `expandOut`, grouped by `dstUid` exactly as today, but each group is buffered (small - typically 1-3
    versions per edge) and resolved with the same latest-intersecting-wins rule as `getNodeWindow`, instead of
    the streaming `validFrom <= asOf` check `expandOut` uses.
  - `GraphInEdgeDb.java` (modified) — add `void expandInWindow(Txn<ByteBuffer> readTxn, long dstUid, long
    edgeTypeUid, Instant from, Instant to, Consumer<Neighbour> consumer)`, the in-edge mirror of
    `expandOutWindow`, exactly as `expandIn` mirrors `expandOut` today.
  - Tests in `TestGraphPhysicalStores.java` (node/adjacency) and `TestGraphInEdgeDb.java` (in-edge): a version
    whose interval intersects a window is returned; a version entirely before or after the window is excluded;
    the boundary cases from the frozen rule (`nextValidFrom == from` excludes, `validFrom == to` includes); two
    versions both intersecting the window resolve to the later one; a tombstone that is the latest intersecting
    version means "absent" (empty/no emission), even though an earlier, present version also intersects.
- **Contract**: a window method's result depends only on the entity's own version run and `[from, to]` - it does
  not consult or affect the floor-lookup methods, which are unchanged (verified by re-running the existing
  floor-lookup tests unmodified).
- **Done-when**: every case in the Files section's test list passes against a real temp-dir LMDB env (mirroring
  `TestGraphPhysicalStores`'s existing style), and all pre-existing `GraphNodeDb`/`GraphAdjacencyDb`/
  `GraphInEdgeDb` tests still pass unchanged.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `getNodeWindow`/`expandOutWindow`/`expandInWindow` added exactly as scoped -
  each buffers its version run/group into a small in-memory list (a window scan cannot early-exit like the
  reverse-bounded floor lookup, since it needs every entry's own successor to know that entry's half-open
  interval) and resolves the latest entry whose interval intersects `[from, to]`, using the P0.3 rule verbatim.
  One genuine test bug found and fixed during this task: an "entirely outside the window" fixture edge with no
  tombstone has an unbounded `[validFrom, +inf)` interval by construction, so it actually intersects every window
  at or after its `validFrom` - not a DAO bug, a test-design error (fixed by tombstoning that fixture edge so it
  has a genuinely bounded interval, matching the intended "definitely gone by now" scenario). Tests: 2 new cases
  in `TestGraphPhysicalStores` for `getNodeWindow` (basic intersect/exclude/boundary cases, and the
  tombstone-wins case) + 2 more for `expandOutWindow`; 2 new cases in `TestGraphInEdgeDb` mirroring
  `expandInWindow`. All pre-existing floor-lookup tests pass unchanged.

#### Task P4.2 — Wire `AROUND`/`BETWEEN` into `GraphTraversalEngine`
- **Depends on**: P4.1 (needs the window DAO methods to call).
- **Gap this closes**: `GraphTraversalEngine.resolveAsOf` throws `UnsupportedOperationException` for
  `Mode.AROUND`/`Mode.BETWEEN`; every node-lookup and hop-expansion call site is hard-wired to the as-of floor
  lookup (`stores.getNodes().getNode(readTxn, uid, asOf)`, `expandOut`/`expandIn` with a single `Instant`).
- **Files**:
  - `GraphTraversalEngine.java` (modified) — introduce a small private `TemporalAccess` abstraction (three
    methods: `getNode`, `expandOut`, `expandIn`, each already-resolved to either the as-of or window DAO calls)
    built once per `execute()` call from the plan's `TemporalContext` (`Mode.AS_OF` → the existing floor-lookup
    calls at `resolveAsOf`'s resolved instant; `Mode.AROUND`/`Mode.BETWEEN` → the new `*Window` calls at
    `temporalContext.from()`/`temporalContext.to()`; no clause → the existing `LATEST` floor lookup, unchanged).
    Thread `TemporalAccess` through `resolveAnchors`, `expandChainHop`/`acceptChainNeighbour`,
    `expandVarLength`/`collectNeighbours`/`acceptVarLengthRow` in place of the raw `Instant asOf` parameter each
    currently takes - a mechanical substitution, not a behaviour change for `AS OF`/no-clause queries (same
    contract, same test results, proven by every pre-P4 test passing unchanged). Remove the
    `AROUND`/`BETWEEN` branch of `resolveAsOf`'s `switch` that throws; `resolveAsOf` (or its replacement) now
    only needs to produce the as-of `Instant` for `Mode.AS_OF`/absent - windowed modes skip straight to building
    the window `TemporalAccess` from `temporalContext.from()`/`to()` directly, never needing a single instant at
    all.
  - Tests in `TestGraphTraversalEngine.java`: a single-hop `AROUND t ± d` query returns only neighbours whose
    edge intersects the window (an edge entirely outside the window is excluded, mirroring
    `whereClause_filtersOutRowsThatDoNotMatch`'s style); the same for `BETWEEN t1 AND t2`; a query combining a
    window-filtered hop with the anchor's own window-based label/property re-validation (`resolveAnchors` must
    also switch to `TemporalAccess`, not just hop expansion); the `aroundClause_throwsNotYetSupported`/
    `BETWEEN`-equivalent tests are removed/replaced by these positive tests (the "not yet supported" behaviour
    they proved no longer exists after this task).
- **Contract**: an `AS_OF` or no-clause query's behaviour is bit-for-bit unchanged (every existing
  `TestGraphTraversalEngine` test - single-hop, chain, var-length, target-constraint - passes without
  modification); `AROUND`/`BETWEEN` queries now execute instead of throwing, using window intersection at every
  hop and at anchor re-validation.
- **Done-when**: the two new positive `AROUND`/`BETWEEN` tests pass against a fixture with an edge inside the
  window, one straddling a boundary, and one entirely outside it; every pre-existing test in the module still
  passes unmodified.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `TemporalAccess` added exactly as scoped (`getNode`/`expandOut`/`expandIn`),
  built once via `resolveAccess` (replacing `resolveAsOf`) and threaded through `resolveAnchors`,
  `expandChainHop`/`acceptChainNeighbour`, `expandVarLength`/`collectNeighbours` in place of the raw
  `Instant asOf` each used to take - a mechanical substitution confirmed by every pre-existing test passing
  unmodified. Tests: `aroundClause_returnsNeighboursWhoseEdgeIntersectsTheWindow` (a dedicated 3-account window
  fixture - one edge that ended before the window, one that intersects it, one that doesn't start until after
  it) and `aroundClause_windowUpperBoundLandingExactlyOnAVersionsValidFrom_includesIt` (an engine-level spot
  check of the P0.3 boundary rule already exhaustively unit-tested at the DAO level in P4.1) replace the old
  `aroundClause_throwsNotYetSupported`. A third test,
  `windowClause_reResolvesTheAnchorAgainstTheWindow_notLatest`, specifically proves `resolveAnchors` uses the
  window lookup and not a silent fallback to "latest" - a dedicated fixture gives the anchor device two
  different `id` values at two different times, so only a correctly-windowed anchor re-validation makes the
  query match at all.

**P4 exit gate: met.** An `AROUND ± d` query and a `BETWEEN` query both return the correct, hand-computed row set
against a fixture with edges inside, straddling, and outside the window; every `AS OF`/no-clause test from PoC.5
through P3.3 continues to pass unmodified, confirming window support is additive, not a behaviour change to
existing temporal modes. P4 (Tasks P4.1-P4.2) is complete; P5 (Query integration hardening) is next, pending
explicit go-ahead.

### P5 — Query integration hardening (5–10 pw)

> **Scoping note (recorded 2026-07-19):** the rough outline above bundles a cost-signal task and a broad
> "`GraphDbDoc` hardening" bucket. A research pass against the current code (not just the design doc) found this
> phase's real remaining scope is considerably narrower than the outline suggests - several items are already
> built, one item has no reusable pattern anywhere in the codebase to build against, and one has a documented,
> already-accepted gap from Task P1.4 rather than a silent one:
> - **`DocumentStoreBinder` is already bound** (`GraphDbModule.java`), and that alone already gives
>   `GraphDbDocStoreImpl` `ExplorerActionHandler`/`ImportExportActionHandler`/`ContentIndexable`/
>   `DocumentActionHandlerBinder` registration for free - `DocumentStore<D>` itself extends
>   `ExplorerActionHandler`, so this is generic docstore-module behaviour, not per-doc-type code. The "explorer
>   handler" bullet's *only* genuinely missing piece is `ExplorerFlags.DOC_TYPE_TO_DEFAULT_FLAG_MAP` not yet
>   listing `GraphDbDoc.TYPE` - a one-line addition, not a new handler class.
> - **Permission cascade is already generic infrastructure, not bespoke code**: `StoreImpl` checks
>   `DocumentPermission` uniformly for every doc type, and `GraphDbDocCacheImpl.get` already enforces
>   `DocumentPermission.USE`. Since internal stores have no `DocRef` of their own (by design, PoC.0) and are only
>   reachable via `GraphStoreManager.getOrOpen(doc)` fed an already-permission-checked doc, this bullet needs no
>   new code at all, only the REST-resource gap below closed (so there is a permission-checked path onto the doc
>   in the first place).
> - **`GraphStores.rebuild(directory, doc)` (drop + re-provision) already exists**, unit-tested in
>   `TestGraphStores`. "Re-run ingest over stored streams" - re-triggering the pipeline processor framework over
>   previously-ingested streams for a feed/pipeline - has **no equivalent anywhere in the codebase to mirror**
>   (Plan B, the acknowledged model for everything else in this plan, has no reprocess-rebuild mechanism either).
>   Building this from scratch is a cross-cutting Stroom capability (wiring into `ProcessorFilter`/meta-expression
>   creation, not a graph-specific concern) far outside a "hardening" task's scope - deferred to a future phase
>   once a real need for it is confirmed, not built speculatively here.
> - **Retention/condense already covers 3 of 5 interning namespaces as a unit**, wired to a live scheduled job
>   (`GraphDbModule.GraphRetentionRunnable`, Decision D9) - Task P1.4's own Javadoc already documents and accepts
>   that the property-value anchor index (which carries no time dimension at all, P0.1/P1.3) has no per-entry
>   staleness to sweep incrementally, with `rebuild()` as the accepted backstop. This is a signed-off decision
>   already on record, not a new gap for P5 to close.
> - **A genuine, previously-undiscovered correctness gap**: `GraphDbDocCacheImpl`'s `EntityAction.DELETE` handler
>   only evicts the doc cache - it never calls anything that removes the doc's on-disk LMDB stores, so deleting a
>   `GraphDbDoc` orphans its physical data on disk indefinitely. Not called out in the original outline at all,
>   but squarely a "full owned-store lifecycle" gap, and cheap to close now that P5.3 already touches this class.
>
> Net effect: three concrete, independently-verifiable tasks below, each far smaller than "5-10 pw" of the
> original two-bullet outline.

#### Task P5.1 — Graph datasource cost port + adapter
- **Depends on**: none beyond PoC.4 (`GraphNodeDb`)/PoC.6 (`GraphSearchProvider`).
- **Gap this closes**: `stroom-query-planner`'s `CostModel` (design doc &sect;2's port/adapter split) has three
  cost ports (`MetaStats`, `IndexShardStats`, `StateStoreStats`); none of them, and no graph-specific port, has
  ever been asked about a graph datasource - a repo-wide grep for `CostModel|StateStoreStats|FieldInfoSource`
  under `stroom-graphdb/` returns zero hits. `GraphSearchProvider` is completely invisible to query costing.
- **Files**:
  - `stroom-query-planner/src/main/java/stroom/query/planner/port/GraphStoreStats.java` (new) - mirrors
    `StateStoreStats` exactly in shape (`Optional<RowCountSignal> estimate(String graphName)`, "never null; the
    graph's name as it would appear in a `MATCH`'s datasource"), since a graph's anchor/adjacency access is also
    key-addressed point/prefix-lookup shaped, not a Lucene-style partitioned scan.
  - `GraphNodeDb.java` (modified) - add `long count(Txn<ByteBuffer> readTxn)` wrapping `dbi.stat(readTxn).entries`
    (the exact pattern `stroom-planb-impl`'s `AbstractDb.count()` and `stroom-lmdb`'s `LmdbDb`/`AbstractLmdbDb`
    already use for this). **Documented approximation** (mirroring how every other cost signal in this codebase
    documents its own approximations, e.g. `CostModel`'s placeholder throughput notes): this counts version
    *rows*, not distinct nodes - a node with N historical versions inflates the estimate by N. Acceptable for a
    cost *signal* (an order-of-magnitude scan-cost proxy, not an exact cardinality), not acceptable if ever
    mistaken for "number of nodes in the graph" - the Javadoc says so explicitly.
  - `stroom-graphdb-impl/.../GraphStoreStatsAdapter.java` (new) - implements `GraphStoreStats`, injecting
    `GraphDbDocCache` + `GraphStoreManager` (the exact resolve-by-name path `GraphSearchProvider` already uses:
    `graphDbDocCache.get(graphName)` &rarr; `graphStoreManager.getOrOpen(doc)` &rarr; `.getNodes().count(readTxn)`
    inside a read transaction). Catches the cache's own "no doc found for name" `NullPointerException` and
    returns `Optional.empty()` (matching every other port's "empty if not a known store" contract); a
    `PermissionException` from the cache's own check is allowed to propagate, not swallowed - an access-control
    signal, not an "unknown store" one.
  - `GraphDbModule.java` (modified) - `bind(GraphStoreStats.class).to(GraphStoreStatsAdapter.class)`, so the port
    is genuinely resolvable the moment something (a future `CostModel` binding site) asks Guice for it.
  - **Deliberately not done**: extending `CostModel`'s constructor with a 4th port. `CostModel` itself is not
    Guice-bound anywhere yet (`CostModel`'s own Javadoc: "Not wired into anything yet") and its two other
    non-`MetaStats` ports (`IndexShardStats`, `StateStoreStats`) are in the exact same "port exists, no adapter,
    not consulted yet" state this task leaves `GraphStoreStats` in - adding a 4th constructor parameter would
    force-update every one of `TestCostModel`'s ten-plus call sites in a different initiative's module for a
    class that isn't wired into anything regardless. Out of proportion for this task; revisit once `CostModel`
    itself gets bound into a real query path.
  - Tests: `GraphNodeDb.count` (in `TestGraphPhysicalStores`) - zero for an empty store, matches insert count,
    counts every version row (not deduplicated by `nodeUid`) so the documented approximation is itself verified,
    not just asserted in prose. `GraphStoreStatsAdapter` (new test file) - resolves a real `GraphStores` fixture's
    node count via a mocked `GraphDbDocCache`/real `GraphStoreManagerImpl`; returns empty for an unknown name
    (mocked cache throwing `NullPointerException`); a `PermissionException` from the cache propagates unchanged.
- **Contract**: `GraphStoreStats` never throws for an unknown graph name (empty instead); the count reflects the
  node store only (edges/property-index rows are out of scope for this first signal, matching how
  `StateStoreStats` only ever answers with one number per store, not a per-index-type breakdown).
- **Done-when**: the new adapter test resolves the correct node-version-row count against a real temp-dir
  `GraphStores` fixture with a mix of single- and multi-version nodes; `GraphDbModule`'s binding resolves via
  Guice without error.
- **Verify**: `./gradlew :stroom-query:stroom-query-planner:test :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `GraphStoreStats` added exactly as scoped; `GraphNodeDb.count` wraps
  `dbi.stat(readTxn).entries` (documented as a version-row count, not a distinct-node count);
  `GraphStoreStatsAdapter` resolves by name via `GraphDbDocCache`/`GraphStoreManager` exactly as
  `GraphSearchProvider` already does, bound in `GraphDbModule`. `CostModel` deliberately left unmodified, per the
  task's own scoping rationale. Tests: two new `GraphNodeDb.count` cases in `TestGraphPhysicalStores` (empty
  store, and a mix of single-/multi-version nodes proving the documented row-count approximation); a new
  `TestGraphStoreStatsAdapter` (known graph resolves the real count against a real `GraphStores` fixture, unknown
  name returns empty, a `PermissionException` from the cache propagates). All pre-existing tests pass unchanged.

#### Task P5.2 — `GraphDbDoc` REST resource + explorer registration
- **Depends on**: none beyond PoC.0.
- **Gap this closes**: no `@Path`-annotated REST resource exists for `GraphDbDoc` anywhere in the repo (verified
  by a repo-wide `find`), so a `GraphDbDoc` cannot be fetched/updated over the REST API the way every other doc
  type can - blocking any UI editor (P6) from existing at all. Separately, `GraphDbDoc.TYPE` is absent from
  `ExplorerFlags.DOC_TYPE_TO_DEFAULT_FLAG_MAP`, so the explorer tree doesn't know to flag it as a data source
  the way it already does for `PlanBDoc`/`LuceneIndexDoc`/etc.
- **Files**:
  - `stroom-core-shared/src/main/java/stroom/graphdb/shared/GraphDbResource.java` (new) - mirrors
    `stroom.planb.shared.PlanBDocResource` exactly: `@Path("/graphDb" + V1)`, `extends RestResource,
    DirectRestService, FetchWithUuid<GraphDbDoc>`, a `GET /{uuid}` `fetch` and a `PUT /{uuid}` `update`.
  - `stroom-graphdb-impl/.../GraphDbResourceImpl.java` (new) - mirrors `PlanBDocResourceImpl` exactly: delegates
    both methods to `DocumentResourceHelper` against a `Provider<GraphDbDocStore>`, `@AutoLogged`, `update`
    rejects a UUID mismatch between the path and the body via `EntityServiceException` (the same guard
    `PlanBDocResourceImpl.update` has).
  - `GraphDbModule.java` (modified) - `RestResourcesBinder.create(binder()).bind(GraphDbResourceImpl.class)`.
  - `stroom-explorer/stroom-explorer-impl/src/main/java/stroom/explorer/impl/ExplorerFlags.java` (modified) -
    add `DOC_TYPE_TO_DEFAULT_FLAG_MAP.put(GraphDbDoc.TYPE, NodeFlag.DATA_SOURCE)` alongside the existing entries
    (`stroom-explorer-impl` already depends on `stroom-core-shared`, where `GraphDbDoc` lives - no new build-graph
    edge).
  - Tests: none - mirrors `PlanBDocResourceImpl`, which itself has no dedicated unit test in this codebase (a
    thin pass-through to `DocumentResourceHelper`, exercised at a REST/integration level this repo checkout
    doesn't contain); writing one here would test framework plumbing, not graph-specific logic. Checked directly:
    a repo-wide search for `TestPlanBDocResource` returns nothing, confirming this is the established precedent,
    not an oversight to fix incidentally while here.
- **Contract**: identical wire contract to every other simple fetch/update doc resource in the codebase - no
  graph-specific validation beyond what `PlanBDocResource`'s pattern already provides.
- **Done-when**: `stroom-graphdb-impl`/`stroom-core-shared` compile with the new resource bound and Guice
  resolves `GraphDbResourceImpl`'s dependencies without error; `ExplorerFlags.getStandardFlagByDocType
  (GraphDbDoc.TYPE)` returns `NodeFlag.DATA_SOURCE`.
- **Verify**: `./gradlew :stroom-core-shared:test :stroom-graphdb:stroom-graphdb-impl:test
  :stroom-explorer:stroom-explorer-impl:test`.
- **Status: done (2026-07-19).** `GraphDbResource`/`GraphDbResourceImpl` added exactly mirroring
  `PlanBDocResource`/`PlanBDocResourceImpl`; required adding `stroom-event-logging-rs-api` and `restygwt` to
  `stroom-graphdb-impl`'s dependencies (both already used by the mirrored Plan B classes, just not previously
  needed by this module). Bound via `RestResourcesBinder` in `GraphDbModule`. `ExplorerFlags` gained the
  one-line `GraphDbDoc.TYPE -> NodeFlag.DATA_SOURCE` entry. Tests: none for the REST resource (matching the
  `PlanBDocResourceImpl` precedent - confirmed no such test exists in this codebase); a new `TestExplorerFlags`
  (this class had no test coverage at all before this task, for any doc type - added one covering the new entry,
  an existing entry as a sanity check, and the unknown-type empty case). All pre-existing tests across the three
  touched modules pass unchanged.

#### Task P5.3 — Doc-delete cleans up its physical stores
- **Depends on**: none beyond PoC.0/PoC.6.
- **Gap this closes**: `GraphDbDocCacheImpl`'s `EntityAction.DELETE` handler (`onChange`) only calls `clear()` -
  the doc-cache eviction all three of `DELETE`/`UPDATE`/`CLEAR_CACHE` already share. Nothing removes the deleted
  doc's on-disk LMDB environment (`GraphStoreManagerImpl`'s `openStores` map keeps it open indefinitely, and the
  directory is never deleted), so deleting a `GraphDbDoc` through the normal docstore/explorer delete flow
  silently orphans its physical data forever - a real "full owned-store lifecycle" gap the original P5 outline
  didn't even name.
- **Files**:
  - `GraphStoreManager.java` (modified) - add `void delete(String uuid)`: closes the cached `GraphStores` for
    `uuid` if open (removing it from the manager's map first, mirroring `getOrOpen`'s own keying) and always
    calls the static `GraphStores.delete(directory)` for that UUID's directory afterward, whether or not it was
    open (an unopened-but-still-on-disk store, e.g. after a restart, must still be removable).
  - `GraphStoreManagerImpl.java` (modified) - implements the above using the same `directoryFor(uuid)` helper
    `getOrOpen` already uses.
  - `GraphDbDocCacheImpl.java` (modified) - inject `GraphStoreManager`; in `onChange`, additionally call
    `graphStoreManager.delete(event.getDocRef().getUuid())` specifically for `EntityAction.DELETE` (not
    `UPDATE`/`CLEAR_CACHE`, which must keep the store open - only a real delete tears anything down).
  - Tests: `TestGraphStoreManagerImpl` - a new `delete` test proving the directory is removed and a subsequent
    `getOrOpen` for the same UUID provisions a fresh, empty store rather than reopening stale data; a case
    deleting a UUID that was never opened still removes its directory if present, and is a no-op (not an error)
    if the directory never existed. `TestGraphDbDocCacheImpl` - a new test asserting `onChange` with
    `EntityAction.DELETE` calls `graphStoreManager.delete` with the event's UUID, while the existing
    `onChange_forUpdateDeleteOrClearCache_...` test (parameterised only over cache-clearing behaviour) keeps
    proving `UPDATE`/`CLEAR_CACHE` do *not* trigger store deletion.
- **Contract**: `UPDATE`/`CLEAR_CACHE` behaviour is bit-for-bit unchanged (still cache-eviction only); only
  `DELETE` additionally tears down the physical store. Deleting a not-currently-open store is not an error.
- **Done-when**: the new `TestGraphStoreManagerImpl`/`TestGraphDbDocCacheImpl` cases pass; every pre-existing
  test in both files passes unmodified.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19).** `GraphStoreManager.delete(String uuid)` added exactly as scoped; wiring
  `GraphDbDocCacheImpl`'s `onChange` required injecting `GraphStoreManager` (a new constructor parameter, updated
  at all 6 test call sites) and adding `DELETE` as its own `switch` arm alongside the existing `UPDATE,
  CLEAR_CACHE` arm. A pre-existing test fixture (`TestGraphFilter`'s inline `GraphStoreManager` lambda) broke
  since the interface is no longer a single-abstract-method type - converted to an anonymous class implementing
  both methods. Tests: three new `TestGraphStoreManagerImpl` cases (closes + removes an open store's directory
  and a subsequent `getOrOpen` provisions fresh; a never-opened-by-this-instance directory from a prior manager
  is still removed, mirroring a restart; deleting a UUID with no directory at all is a no-op); a new
  `TestGraphDbDocCacheImpl` case proving `DELETE` calls `graphStoreManager.delete` with the event's UUID, plus a
  `never()` assertion added to the existing `UPDATE` test proving it does *not*. All pre-existing tests pass
  unchanged.

**P5 exit gate: met.** A `GraphDbDoc` can be fetched/updated over REST like any other document, appears as a data
source in the explorer tree, exposes a real (if approximate) cost signal to the query-planner port interface,
and no longer leaks on-disk data when deleted - all without a single internal store ever gaining its own
`DocRef`, REST endpoint, or explorer node (the encapsulation invariant, unchanged). Reprocess-rebuild-from-UI and
full anchor-index retention remain explicitly out of scope, per the scoping note above. P5 (Tasks P5.1-P5.3) is
complete; P6 (UI) is next, pending explicit go-ahead.

### P6 — UI (7–16 pw)

> **Scoping note (recorded 2026-07-19):** this is the first client-side (GWT) phase in the whole initiative - a
> real pivot from the Java backend work done through P5. A research pass against the current client code (not
> just the design doc) found:
> - **`GraphDbDoc` has zero client-side presence today** - no `DocumentPlugin`, no presenter, no
>   `DocumentTypeRegistry` entry, no icon. It cannot be created, opened, or shown in the explorer's "New" menu at
>   all. This is a total gap, but with an exact, proven, minimal template to mirror: `SqlTemporalStoreDoc`
>   (`stroom-core-client/src/main/java/stroom/sqlstore/client/`), a single-kind doc added on this same branch
>   with precisely the tab set this phase wants (Data/Settings/Documentation/Permissions, no Fields/Shards) - a
>   much closer analogue than the Lucene Index editor the rough outline named, which genuinely has extra
>   sub-resources (shards, fields) a graph doesn't.
> - **The wire format and execution side are already real** (`Query.graphSpec`/`GraphSpec`, `GraphSearchProvider`,
>   both live since PoC.6) - but **nothing today turns typed Cypher text into a request that reaches them**.
>   `CypherCompiler` (`stroom-graphdb-impl/.../CypherCompiler.java`) is invoked only from
>   `TestGraphSearchProvider`'s test setup - zero production call sites. The dashboard's generic search path
>   (`QueryServiceImpl.mapRequest`, `stroom-query-impl`) unconditionally compiles every query through the single
>   bound `QueryCompiler` (StroomQL), with no per-datasource-type branch at all. Wiring this dispatch seam is a
>   genuine prerequisite for the Data tab's default query (or any dashboard Cypher query) to do anything but fail
>   - so it is sequenced *before* the client editor task below, not after, so the editor's own Data tab is
>     verifiably functional the moment it lands rather than merely present.
> - **Cypher `AceEditorMode` (syntax highlighting), autocomplete, `explain`-for-Cypher, and any graph/network
>   visualisation are all real gaps this phase does not attempt** - not an oversight, a structural finding:
>   `QueryServiceImpl.getReferencedDataSource`/`getQueryHelpContext`/`explainQuery` (the autocomplete/explain call
>   paths) are invoked with *only* the raw query string, no doc-ref context whatsoever - unlike the dispatch seam
>   below (which piggybacks on a doc-ref the Data tab already carries), there is no equivalent hook these paths
>   could use to recognise a Cypher query at all without a further, separate design effort. Visualisation was
>   already flagged "a stretch" in the original outline, and no graph/network-visualisation library or code
>   exists anywhere in the main repo to build on (a legacy D3-v3 force-directed example exists only in a
>   separate, unbuilt content repo). None of these block the phase's own gate ("author + run a Cypher query and
>   see tabular results") - StroomQL's Ace mode merely won't colour Cypher syntax specially yet.
>
> Two tasks: the dispatch seam first (server-side, independently testable without any client code), then the
> client editor (which becomes end-to-end functional immediately because the seam already exists).

#### Task P6.1 — Cypher query dispatch seam
- **Depends on**: none beyond PoC.6 (`CypherCompiler`, `GraphSearchProvider`) and P5.2 (`GraphDbResource`, not
  used directly here but confirms the doc is otherwise addressable).
- **Gap this closes**: `QueryServiceImpl.mapRequest` (`stroom-query-impl/.../QueryServiceImpl.java`) always calls
  the single bound `QueryCompiler` (StroomQL) - there is no branch, no per-datasource dispatch, and `CypherCompiler`
  is never invoked outside its own test. A Cypher query submitted through the normal dashboard/Data-tab search
  path today does not reach `GraphSearchProvider` at all; StroomQL parsing simply fails on it.
- **Design decision (the datasource-identification problem):** Cypher has no `FROM`-equivalent clause (Decision
  D4), so - unlike StroomQL, where the target datasource is *extracted from the query text itself*
  (`QueryCompiler.extractDataSourceOnly`) - something else must tell the server which doc a Cypher query targets,
  *before* any compiler is chosen. The Data tab (and every other `AbstractQueryDataPresenter` subclass) already
  knows this doc-ref - it is a parameter to `onRead`/`getDefaultQuery` - but never threads it onto the wire
  request: `QueryModel.init(DocRef)` (`stroom-core-client/.../query/client/presenter/QueryModel.java`), which
  sets `SearchRequestSource.ownerDocRef` on every search this model runs, is **never called** by
  `AbstractQueryDataPresenter` today (confirmed by reading the whole class - `queryModel` is constructed but
  `.init(...)` has no call site there). `ownerDocRef` already exists precisely to mean "the doc this search was
  run from" (used today only for audit/display, e.g. `DashboardServiceImpl`'s audit lookups, `ResultStorePresenter`'s
  "Owner Doc" row) - for the Data-tab case the *owning* doc and the *target datasource* are the same doc, so
  reusing this existing field is a natural fit, not a semantic clash, and needs no new wire field at all.
- **Files**:
  - `stroom-core-client/.../query/client/presenter/AbstractQueryDataPresenter.java` (modified) - call
    `queryModel.init(docRef)` inside `onRead` (where `docRef` is already a parameter) - a one-line fix to a
    previously-inert gap. Every existing subclass (`IndexDataPresenter`, `PlanBDataPresenter`,
    `SqlTemporalStoreDataPresenter`) benefits identically (their own "Owner Doc" audit display starts being
    populated too, a harmless side effect, not a behaviour change to their search results).
  - `stroom-query/stroom-query-common/src/main/java/stroom/query/language/AlternativeQueryCompiler.java` (new) -
    a small port interface alongside the existing `QueryCompiler`: `boolean supports(DocRef dataSourceRef)` +
    `SearchRequest create(String query, SearchRequest in, ExpressionContext expressionContext)` (same `create`
    shape as `QueryCompiler`, deliberately *not* reusing that interface itself, to avoid any risk of an
    alternative implementation accidentally satisfying the "the one true StroomQL compiler" injection point
    elsewhere). Bound as an empty-by-default `Set` (`Multibinder`/`GuiceUtil.buildMultiBinder`) so
    `stroom-query-impl` needs no compile-time dependency on `stroom-graphdb-impl` at all - the same
    port/multibinder-discovery pattern already used for `SearchProvider`/`DataSourceProvider`/cost ports
    throughout this codebase (see Task P5.1).
  - `QueryServiceImpl.java` (modified) - inject `Set<AlternativeQueryCompiler> alternativeQueryCompilers`; in
    `mapRequest`, after resolving `ownerDocRef` from `searchRequest.getSearchRequestSource()`, look up the first
    (if any) alternative compiler whose `supports(ownerDocRef)` returns true (extracted into a small, directly
    unit-testable static helper - see Contract below, since `QueryServiceImpl` itself has 16 constructor
    dependencies and no existing test harness, disproportionate to stand up just to cover a small `if`/`else`).
    If one matches, pre-set `Query.dataSource(ownerDocRef)` on the sample request and call *that* compiler's
    `create(...)` instead of `queryCompiler.create(...)`; otherwise (no `ownerDocRef`, or none supports it) the
    existing call is completely unchanged - byte-for-byte identical to today for every existing caller, since
    none of them populate `ownerDocRef` for this flow yet (before this task's `AbstractQueryDataPresenter` fix).
  - `stroom-graphdb-impl/.../GraphCypherQueryCompiler.java` (new) - implements `AlternativeQueryCompiler`:
    `supports` checks `GraphDbDoc.TYPE.equals(dataSourceRef.getType())`; `create` delegates to `CypherCompiler`
    (finally giving it a real, live caller). Bound into the `Set<AlternativeQueryCompiler>` multibinder in
    `GraphDbModule`.
  - Tests: a pure-function unit test for the extracted "resolve which alternative compiler answers" helper
    (empty set → empty; a `DocRef` of a different type → empty; a `GraphDbDoc`-typed `DocRef` → the matching
    compiler) - no `QueryServiceImpl` construction needed. A new `TestGraphCypherQueryCompiler` proving `supports`
    is true only for `GraphDbDoc.TYPE` and `create` produces a `SearchRequest` with `Query.graphSpec` populated
    (mirroring `CypherCompiler`'s own existing test coverage, just through the new adapter). The
    `QueryServiceImpl.mapRequest` wiring itself is not separately integration-tested, matching the precedent set
    for `GraphDbResourceImpl`/`PlanBDocResourceImpl` (Task P5.2) - a thin, directly-reviewable dispatch line, not
    graph-specific logic, in a class with no pre-existing test infrastructure to extend proportionately.
- **Contract**: every existing StroomQL search (dashboard Query component, every non-graph Data tab, downloads,
  column-value lookups) is unaffected - `ownerDocRef` was already threaded through those paths' own call sites
  (`SearchModel.java` for the dashboard Query component) or remains unset (Data tabs, until this task's one-line
  fix, which is itself inert for non-`GraphDbDoc` owner types). Autocomplete/explain/"referenced data source"
  detection for Cypher remain unsupported (documented gap above, not attempted).
- **Done-when**: the extracted dispatch-resolution helper's unit tests pass; `TestGraphCypherQueryCompiler` passes;
  every pre-existing test across `stroom-query-common`/`stroom-query-impl`/`stroom-graphdb-impl` passes unmodified.
- **Verify**: `./gradlew :stroom-query:stroom-query-common:test :stroom-query:stroom-query-impl:test
  :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19)**. `AlternativeQueryCompiler`/`AlternativeQueryCompilerResolver` added to
  `stroom-query-common` with `TestAlternativeQueryCompilerResolver` (4 tests). `QueryModule` binds the empty-default
  `Set<AlternativeQueryCompiler>` multibinder; `QueryServiceImpl.mapRequest` resolves it from the (new)
  `ownerDocRef`-derived lookup and dispatches to the matching compiler when present, otherwise the pre-existing
  `queryCompiler.create(...)` call is untouched. `AbstractQueryDataPresenter.onRead` now calls `queryModel.init(docRef)`.
  `GraphCypherQueryCompiler` (new, `stroom-graphdb-impl`) adapts `CypherCompiler` to the port and is bound in
  `GraphDbModule`'s multibinder, giving `CypherCompiler` its first production caller. `TestGraphCypherQueryCompiler`
  (4 tests) passes. Full verification sweep green: `stroom-query-common:test`, `stroom-query-impl:test`,
  `stroom-graphdb-impl:test`, checkstyleMain/Test on all three, plus `stroom-core-client:compileJava` - all clean.

#### Task P6.2 — `GraphDbDoc` client plugin + editor
- **Depends on**: P6.1 (so the Data tab's default query is verifiably functional, not merely present).
- **Gap this closes**: `GraphDbDoc` cannot be created, opened, edited, or previewed from the UI at all -
  confirmed by an exhaustive grep of every `*-client*`/GWT module for `GraphDbDoc`/`stroom.graphdb`/`"GraphDb"`,
  and by `DocumentTypeRegistry` (`stroom-core-shared/.../docstore/shared/DocumentTypeRegistry.java`) having no
  entry for it at all (every other doc type - `PlanBDoc`, `SqlTemporalStoreDoc`, `LuceneIndexDoc`, etc. - does).
- **Files** (mirrors `stroom.sqlstore.client.*`/`SqlTemporalStoreDoc`'s exact file set and shape, file-for-file,
  since it is a single-kind doc with no sub-resource fan-out, exactly like `GraphDbDoc`):
  - `stroom-core-shared/.../docstore/shared/DocumentTypeRegistry.java` (modified) - add a
    `GRAPH_DB_DOCUMENT_TYPE` constant + its `put(...)` registration.
  - `stroom-core-shared/.../svg/shared/SvgImage.java` (modified) - add a `DOCUMENT_GRAPH_DB` icon constant;
    `stroom-app/src/main/resources/ui/images/document/GraphDb.svg` (new, + its `raw-images` mirror) - copy the
    `PlanB.svg`/`SqlTemporalStore.svg` pattern (an actual icon asset, not a placeholder - without one the doc
    would appear in "New" with a broken image).
  - `stroom-core-client/src/main/java/stroom/graphdb/client/GraphDbPlugin.java` (new) - `DocumentPlugin<GraphDbDoc>`,
    mirrors `SqlTemporalStorePlugin.java` exactly (`createEditor`/`load`/`save`/`getType`/`getDocRef` via
    `GraphDbResource`, already built in Task P5.2).
  - `stroom-core-client/.../graphdb/client/gin/GraphDbGinjector.java` + `GraphDbModule.java` (new, client-side
    Gin module - distinct from and unrelated to the server-side `stroom.graphdb.impl.GraphDbModule` name reused
    here only because `SqlTemporalStoreModule`/`PlanBModule` establish that as the client-side Gin module's
    conventional name) - mirrors `SqlTemporalStoreGinjector`/`SqlTemporalStoreModule` exactly.
  - `stroom-core-client/.../graphdb/client/presenter/GraphDbPresenter.java` (new) - `DocTabPresenter`, four tabs
    (Data/Settings/Documentation/Permissions, `selectTab(DATA)` default), mirrors `SqlTemporalStorePresenter.java`
    almost verbatim (`GraphDbDoc.getDescription()` already exists for the Documentation tab's markdown source).
  - `stroom-core-client/.../graphdb/client/presenter/GraphDbSettingsPresenter.java` + `view/GraphDbSettingsViewImpl.java`
    + `.ui.xml` (new) - **scoped down from `SqlTemporalStoreSettingsPresenter`'s own example**: a plain `description`
    text field only (mirroring the "small config surface, D8" framing literally) - deliberately **not** attempting
    a `temporalPrecision`/`retention`/`nodeTypeMappings` editing UI in this first pass (a real, separate UI-design
    effort each - `nodeTypeMappings` in particular is a full schema-mapping list editor with no existing widget
    to mirror; `retention`/`temporalPrecision` could reuse Plan B's existing `RetentionSettingsWidget`-family
    widgets, but wiring that is left for a follow-up rather than bundled speculatively into this task). Every one
    of these fields is already documented as nullable/optional on `GraphDbDoc` itself (null means "use the
    internal default"), so a graph is fully functional with only a description ever set via this tab.
  - `stroom-core-client/.../graphdb/client/presenter/GraphDbDataPresenter.java` + `view/GraphDbDataViewImpl.java`
    (new) - extends `AbstractQueryDataPresenter<GraphDbDataView, GraphDbDoc>` exactly as
    `SqlTemporalStoreDataPresenter`/`PlanBDataPresenter` do; `getDefaultQuery(DocRef, GraphDbDoc)` returns the
    design doc's own worked example verbatim: `"MATCH (n)-[r]->(m) RETURN labels(n), type(r), labels(m), n, m
    LIMIT 20"` (no `FROM`-equivalent clause needed - Task P6.1's dispatch seam resolves the target graph from the
    tab's own doc-ref, not from this text). Seeding the default query from the graph's own schema-mapping/
    domain-type catalogue (the original outline's stretch refinement) is not attempted - the fixed worked example
    is what the design doc itself already committed to as the baseline query.
  - `stroom-core-client/src/main/resources/stroom/graphdb/GraphDb.gwt.xml` (new) - copies
    `stroom/sqlstore/SqlTemporalStore.gwt.xml`'s two `<inherits>`/two `<source>` lines verbatim.
  - `stroom-app-gwt/src/main/resources/stroom/app/App.gwt.xml` (modified) - add
    `<inherits name="stroom.graphdb.GraphDb" />` alongside the existing `stroom.planb.PlanB`/
    `stroom.sqlstore.SqlTemporalStore` lines.
  - `stroom-app-gwt/src/main/java/stroom/app/client/gin/AppGinjectorUser.java` (modified) - add
    `GraphDbModule.class` to `@GinModules({...})` and `GraphDbGinjector` to the injector's `extends` list,
    exactly mirroring the existing `PlanBModule`/`PlanBGinjector` and `SqlTemporalStoreModule`/
    `SqlTemporalStoreGinjector` entries.
  - Tests: none of the mirrored presenter/plugin/view classes have direct unit tests for their own doc-type
    equivalents in this codebase (`SqlTemporalStorePresenter`/`SqlTemporalStorePlugin`/etc. are untested,
    confirmed by their absence from any `*-client*` test source set) - GWT client presenters in this codebase are
    exercised via manual/e2e testing, not unit tests, so none are added here either, consistent with precedent.
    Verification for this task is **`javac`-level only**: `./gradlew :stroom-core-client:compileJava
    :stroom-core-shared:compileJava :stroom-app-gwt:compileJava` (plain Java compilation of GWT client code,
    which is ordinary Java - this does *not* run the GWT cross-compiler or exercise the UI in a browser; this
    repo/environment has no way to launch the compiled GWT app for interactive verification, so feature-level UI
    correctness is asserted by careful mirroring of a proven template, not demonstrated).
- **Contract**: identical shape/behaviour to `SqlTemporalStoreDoc`'s own editor, scoped to `GraphDbDoc`'s fields;
  no internal graph store ever gains its own `DocRef`/explorer node (the encapsulation invariant, unchanged).
- **Done-when**: the three `compileJava` targets above succeed with the new files; `GraphDbDoc.TYPE` resolves to
  a `DocumentType` with an icon via `DocumentTypeRegistry`.
- **Verify**: `./gradlew :stroom-core-client:compileJava :stroom-core-shared:compileJava
  :stroom-app-gwt:compileJava` (see the honesty note above - this is a compilation check, not a UI test).
- **Status: done (2026-07-19)**. `GRAPH_DB_DOCUMENT_TYPE` added to `DocumentTypeRegistry` (group `INDEXING`,
  matching `PlanB`/`SqlTemporalStore`); `SvgImage.DOCUMENT_GRAPH_DB` + `document/GraphDb.svg` (both the
  `raw-images` source and the `images` runtime copy) added, reusing the `SqlTemporalStore` icon's shape with a
  distinct accent colour so it's visually distinguishable in the explorer. Full client file set added under
  `stroom.graphdb.client.*` (`GraphDbPlugin`, `gin/GraphDbGinjector`+`GraphDbModule`, `presenter/GraphDbPresenter`
  with the four standard tabs, `presenter/GraphDbSettingsPresenter`+`view/GraphDbSettingsViewImpl`+`.ui.xml`
  scoped to a plain `description` field per the task's own scoping note, `presenter/GraphDbDataPresenter`
  extending `AbstractQueryDataPresenter` with the design doc's worked-example Cypher query as the default and no
  preferred columns), `GraphDb.gwt.xml` (copied from `SqlTemporalStore.gwt.xml`), and registration in
  `App.gwt.xml`/`AppGinjectorUser.java` alongside the existing `PlanB`/`SqlTemporalStore` entries. All three
  `compileJava` targets pass; `stroom-core-shared`/`stroom-app-gwt` checkstyle pass clean;
  `stroom-core-client` checkstyle reports only the pre-existing, unrelated `FloorMapEditorHelp.java` trailing-
  whitespace violation (predates this session, confirmed via `git log`) - every new/edited file in this task was
  manually verified free of line-length/trailing-whitespace issues. As per the honesty note above, this is
  `javac`-level verification only - no browser-based interactive check was performed or is claimed.

**P6 exit gate**: a `GraphDbDoc` can be created from the explorer's "New" menu, opened in a tabbed editor, given a
description, and its Data tab's default Cypher query returns real tabular rows against a populated graph (via
the P6.1 dispatch seam) - all confirmed at the `javac`/unit-test level; interactive browser verification is out
of reach in this environment and is explicitly not claimed. Cypher syntax highlighting, autocomplete, explain,
and graph/network visualisation remain unbuilt, per the scoping note above.

### P7 — Security / permissions (3–6 pw)

> **Scoping note (2026-07-19).** A dedicated research pass read `StoreImpl`/`DocumentResourceHelperImpl`
> (`stroom-docstore-impl`), `GraphDbDocCacheImpl`/`GraphDbResourceImpl` alongside their exact `PlanBDocCacheImpl`/
> `PlanBDocResourceImpl` counterparts, and `GraphTraversalEngine` in full, to separate what the outline's "largely
> free" claim actually delivers today from what is a genuine gap:
>
> - **Document-level permissions: (a) already fully closed, zero gap.** `GraphDbModule`'s
>   `DocumentStoreBinder.create(binder()).bind(GraphDbDoc.TYPE, ...)` call gets the identical
>   `StoreImpl`/`SecurityContext`-backed `VIEW`/`EDIT`/`DELETE` enforcement every other doc type gets from
>   `StoreFactoryImpl` - not a GraphDb-specific mechanism, so there is nothing to add at that layer. The REST layer
>   (`GraphDbResourceImpl`, Task P5.2) delegates to the same `DocumentResourceHelper` Plan B's resource does -
>   confirmed by reading both side by side; neither ever had inline permission logic to omit. The query path has
>   its *own*, explicit defense-in-depth check: `GraphDbDocCacheImpl.get()` calls
>   `securityContext.hasDocumentPermission(docRef, DocumentPermission.USE)` and throws `PermissionException` if it
>   fails - mirroring `PlanBDocCacheImpl.get()` exactly - and this is **already covered by an existing test**,
>   `TestGraphDbDocCacheImpl.get_throwsWhenCallerLacksUsePermission` (added incidentally during P5.3's work). Task
>   P7.1 below closes this out with one additional regression test at the `GraphSearchProvider` level (the one
>   thing not yet directly asserted) rather than any new production code.
> - **Query guardrails: (c) entirely unbuilt - the substantive part of this phase.** `GraphTraversalEngine` has no
>   awareness of a compiled Cypher `LIMIT` at all (`unwrap()` walks past the `Limit` node without ever reading
>   `limit.values()`), no ceiling on `VarLengthExpand.maxHops()` (a legal `-[:T*1..100000]->` is attempted
>   verbatim - `Cypher.g4` only forbids the *unbounded* `-[:T*]->` form, not a very large explicit bound), no cap
>   on the total number of BFS path-states a variable-length hop can explore (a modest hop range against a
>   high-fan-out hub node can still blow up exponentially), and no wall-clock budget at all - `GraphSearchProvider`
>   runs a traversal synchronously on the calling thread by design (its own Javadoc explains why: a single LMDB
>   read transaction, no shard fan-out to dispatch asynchronously), so a pathological query simply runs to
>   completion with no way for anything to cancel it. Task P7.2 below closes this.
> - **Label-level filtering: explicitly deferred, per the outline's own "optional."** A repo-wide check confirms
>   zero precedent for sub-document permission granularity anywhere in this codebase - `DocumentPermission` only
>   models `OWNER/DELETE/EDIT/VIEW/USE` at the whole-doc level. Building this from scratch (a new permission
>   concept scoped to `GraphNodeTypeMapping` labels) is real, separate UI/security-model design work, not a small
>   addition to this phase - deferred, exactly as the outline flagged it as optional.
> - Async task-cancellation via `TaskContextFactory` (the pattern `StateSearchProvider` uses) was considered for
>   the wall-clock guardrail and rejected: it would mean restructuring `GraphSearchProvider.createResultStore`
>   from synchronous to asynchronous, directly reversing a documented, deliberate prior design decision (that
>   class's own Javadoc) for a guardrail that a much smaller, purely-synchronous deadline check already solves.

#### Task P7.1 — Document-permission regression test
- **Depends on**: P5.2/P5.3 (the doc cache's `USE`-permission check and REST resource already exist).
- **Gap this closes**: the query-path permission check (`GraphDbDocCacheImpl.get()`) already has direct test
  coverage, but nothing exercises the boundary a real user actually crosses: what happens to a
  `PermissionException` thrown while `GraphSearchProvider.createResultStore` resolves the target doc?
- **Files**: `stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphSearchProvider.java`
  (modified) - a new `providerWithThrowingDocCache()` fixture variant (mirrors the existing `provider(...)`
  fixture, swapping in a `GraphDbDocCache` mock whose `get(...)` throws) plus a test asserting
  `createResultStore(...)` propagates the `PermissionException` rather than swallowing it - no other production
  code changes.
- **Contract**: no behaviour change - this only adds assertion coverage for the existing, correct behaviour.
- **Done-when**: the new test passes; every pre-existing `stroom-graphdb-impl` test still passes.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test --tests "stroom.graphdb.impl.TestGraphSearchProvider"`.
- **Status: done (2026-07-19)**. **Corrects an assumption from this task's own scoping note above**: writing the
  test revealed `getGraphDbDoc(docRef)` in `GraphSearchProvider.createResultStore` runs *before* the method's own
  `try { ... } catch (RuntimeException e) { resultStore.addError(e); }` block (and before the `ResultStore` is
  even constructed) - so a `PermissionException` from doc resolution actually **propagates out of
  `createResultStore` uncaught**, it is not downgraded to a soft result-store error. Checking
  `JoinSearchProvider.createResultStore` confirms this is the established, consistent contract, not a GraphDb-
  specific gap: its own per-side `realiseSide(...)` doc/datasource resolution likewise runs before its try block
  - there is no `ResultStore` yet for a pre-resolution failure to attach itself to, so propagating to whatever
  called `createResultStore` (which has its own handling further up the stack) is correct, matching precedent
  exactly. The test (`permissionException_fromDocResolution_propagatesOutOfCreateResultStore`) was written to
  assert the corrected, actual contract rather than the (wrong) original assumption. Full
  `stroom-graphdb-impl:test` suite (39 tests) and `checkstyleTest` both pass.

#### Task P7.2 — Traversal engine guardrails
- **Depends on**: P3.2/P3.3 (the fixed-length chain loop and variable-length BFS this task adds ceilings to),
  P4.2 (`TemporalAccess`, unaffected by this task but threaded through the same methods).
- **Gap this closes**: see the scoping note above - `GraphTraversalEngine` has no row cap tied to a compiled
  `LIMIT`, no ceiling on `VarLengthExpand.maxHops()`, no cap on total BFS states explored, and no wall-clock
  budget.
- **Files**: `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphTraversalEngine.java`
  (modified):
  - `PlanShape` gains a `@Nullable Long limit` field; `unwrap()` now reads `Limit.values().getFirst()` while
    walking past the `Limit` node (previously discarded) instead of only walking past it.
  - `execute()` computes a `rowCap` (`shape.limit()`, or unbounded if absent) and a wall-clock `deadline`
    (`Instant.now().plus(MAX_TRAVERSAL_DURATION)`) once per call, threading both through `expandChainHop`/
    `acceptChainNeighbour` (fixed-length chains) and `expandVarLength` (variable-length): every hop/BFS-depth
    iteration now checks the deadline first, and every row-accumulation point stops once `rows.size() >= rowCap`
    - not merely trimming the result afterwards (the pre-existing `DataStoreSettings` store-size cap, applied
      after all traversal CPU/LMDB work is already done, is unaffected and unrelated).
  - `expandVarLength` rejects a `maxHops() > MAX_VAR_LENGTH_HOPS` (50) request immediately, before doing any BFS
    work, and now tracks a running total of path-states explored across every depth, aborting once
    `MAX_VAR_LENGTH_PATH_STATES` (200,000) is exceeded.
  - New `GraphTraversalLimitExceededException` (new file, in-package) - a plain `RuntimeException` subtype so it
    flows through `GraphSearchProvider.createResultStore`'s existing `catch (RuntimeException e)` block exactly
    like any other search error.
  - Tests (`TestGraphTraversalEngine.java`, modified): a `LIMIT`-carrying compiled plan against a fixture with
    more matching rows than the limit returns exactly the limited count; a `maxHops` above the ceiling throws
    `GraphTraversalLimitExceededException` immediately (no traversal attempted); a package-private, test-only
    3-arg `GraphTraversalEngine` constructor overload (taking an explicit path-state budget) lets a third test
    reach the path-state ceiling deterministically over a small fixture (a couple of edges, budget of 2) rather
    than needing to seed hundreds of thousands of edges to reach the real 200,000 production default.
- **Contract**: every existing query without a `LIMIT`, with a `maxHops` at or below the new ceiling, and whose
  BFS never approaches the state ceiling is byte-for-byte unaffected - these are pure additional safety bounds,
  not a change to which rows a query that stays within them returns.
- **Done-when**: the new tests pass; every pre-existing `stroom-graphdb-impl` test still passes.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19)**. All four guardrails implemented in `GraphTraversalEngine.java`: `PlanShape`
  gained a `limit` field read in `unwrap()`; `execute()` computes `rowCap`/`deadline` once and threads both
  through `expandChainHop`/`acceptChainNeighbour` and `expandVarLength`; `expandVarLength` rejects
  `maxHops() > MAX_VAR_LENGTH_HOPS` (50) up front and tracks a running path-state total against
  `maxVarLengthPathStates` (200,000 in production, overridable via the new test-only constructor). New
  `GraphTraversalLimitExceededException` added. Three new tests in `TestGraphTraversalEngine.java`
  (`limitClause_stopsAccumulatingRowsOnceSatisfied`, `variableLengthPath_hopRangeAboveTheCeiling_throwsImmediately`,
  `variableLengthPath_exceedingThePathStateBudget_throwsClearly`) all pass; full `stroom-graphdb-impl:test`
  suite (42 tests) and both `checkstyleMain`/`checkstyleTest` pass clean.

**P7 exit gate**: document-level permission enforcement for `GraphDbDoc` is verified end-to-end (the doc-cache's
`USE` check, and confirmation that a `PermissionException` from doc resolution propagates out of
`createResultStore` rather than being silently downgraded - consistent with `JoinSearchProvider`'s identical
contract); `GraphTraversalEngine` rejects or bounds every traversal shape identified as unbounded (row count via
`LIMIT`, hop-range, BFS path-state fan-out, wall-clock duration) rather than running an arbitrarily expensive
query to completion or hanging the calling thread. Label-level filtering remains unbuilt, per the outline's own
"optional" framing and the complete absence of any sub-document permission precedent in this codebase to build
it from.

### P8 — Scale-out & hardening (10–20 pw)

> **Scoping note (2026-07-19).** This phase's own outline splits into two very differently-sized pieces: cross-shard
> distributed traversal (the "largest scaling risk", per the outline itself) versus write-amplification/perf
> hardening of the existing single-shard architecture. The user explicitly chose to scope this pass to **hardening
> only** - cross-shard distributed traversal is **not attempted here**. It would mean reworking the P0.1 frozen,
> signed-off partitioning decision (currently "by graph id, fully co-located, zero cross-shard hops") into a
> hash-by-`srcUid`/`dstUid` scheme, plus a new distributed join/exchange execution layer extending
> `FederatedSearchExecutor` - a design effort of the same shape and weight as P0.1 itself (a dedicated spike,
> reviewed and signed off, *before* any execution code), not a task-sized addition to this phase. The outline's own
> "ship single-shard first" framing already anticipated this split. If/when cross-shard work is picked up, it
> should get its own dedicated design phase, mirroring P0.1's process, rather than being bolted onto this one.
>
> A research pass (reading `GraphFilter.java`, every graph DAO's `insert`, the P1.4 retention/condense writeup, and
> a repo-wide search for perf-benchmark precedent) found:
> - **Write amplification is real and countable, but already partially bounded.** One edge write is *always* 2
>   LMDB puts (the P1.1 in-edge mirror, a deliberate dual-write contract, not a bug). One node write is 1 put plus
>   **one property-index put per (label × property) pair**, unconditionally, on every version - even when most
>   fields are unchanged from the node's prior version (the realistic common case: one field updates, the rest
>   don't). This is the one concrete, fixable-without-a-redesign source found - **Task P8.1** below.
> - The already-documented, already-*accepted* limits (Task P1.4's own writeup: condense was considered and
>   explicitly not built since graph properties change far less often than Plan B state typically does; stale
>   property-index anchors for genuinely-*changed* values are never incrementally swept, only a full
>   `GraphStores.rebuild()` re-derives them from scratch) are **not** reopened here - re-litigating an already-made,
>   documented P1.4 decision is out of scope for a hardening pass, not a gap this phase failed to notice.
> - **No automated perf-benchmark gate exists anywhere in this codebase** - JMH is a build dependency
>   (`gradle/libs.versions.toml`) used by a handful of `@Benchmark` classes with no Gradle/CI wiring to run them at
>   all (hand-invoked only); the one real, *used* precedent is Plan B's own `TestStateDb`/`TestTemporalStateDb`
>   `@TestFactory` `...Performance()` methods - the same correctness test re-run at a larger data volume/iteration
>   count, with no elapsed-time assertion (CI hardware variance makes a hard ceiling the wrong idiom, and nothing
>   in this repo does that today). **Task P8.2** below mirrors this existing, used idiom rather than introducing an
>   unprecedented JMH/CI wiring effort disproportionate to a hardening pass.

#### Task P8.1 — Skip redundant property-index re-indexing on unchanged values
- **Depends on**: none beyond PoC.4/P1.3 (`GraphPropertyIndex`) and P2.2 (`GraphFilter`, the only caller of
  `GraphNodeDb.insert`/`GraphPropertyIndex.insert` for nodes).
- **Gap this closes**: `GraphFilter.addNode()` unconditionally re-indexes every (label × property) pair on every
  node version, even when the value is byte-for-byte unchanged from the node's immediately-preceding version -
  `GraphPropertyIndex.insert`'s own Javadoc already documents the operation as "idempotent: inserting the same
  (label, propKey, value, node) tuple twice is a no-op", i.e. this was always pure waste for the unchanged case,
  never a correctness requirement.
- **Files**: `stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/pipeline/GraphFilter.java`
  (modified) - `addNode()` looks up the node's previous version via `stores.getNodes().getNode(writer.getWriteTxn(),
  nodeUid, currentValidFrom)` *before* calling `insert(...)` (so the lookup still resolves to the prior version,
  not the one about to be written); for each `(labelUid, property)` pair, skips the `GraphPropertyIndex.insert` call
  only when **both** the label was already carried by the previous version **and** the property's value is
  unchanged from it - a label newly added this version has no pre-existing anchors under it at all and must
  always be (re-)indexed, regardless of whether the same value happens to already be anchored under some other
  label.
- **Contract**: no observable behaviour change - `findAnchors` still resolves every node it did before, since a
  skipped re-insert's prior-version anchor (same key, same node UID) is never deleted out from under it (per
  P1.4's own documented scope, only a full `rebuild()` re-derives anchors). This is purely fewer redundant LMDB
  writes for the unchanged-value case, not a change to what any query returns.
- **Done-when**: the skip/no-skip decision is proven directly (unit test) for all three cases (unchanged → skip;
  changed → index; label newly carried → always index, regardless of value); an ingest-level test proves an
  unchanged property's anchor still resolves via a real query after a second version; an ingest-level test proves
  a newly-added label's property is indexed and resolves even when the same value is already anchored under a
  different, pre-existing label.
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test`.
- **Status: done (2026-07-19)**. The skip/no-skip decision was extracted as a small, pure, package-private static
  function (`GraphFilter.anchorNeedsReindexing`) specifically because row-count assertions can't actually prove a
  redundant write was skipped: `GraphPropertyIndex.insert`'s own idempotence means re-inserting an unchanged
  (label, propKey, value, node) tuple leaves the store's entry count byte-for-byte identical to skipping it -
  the *decision logic* is what's testable, not the resulting row count. Four new tests in `TestGraphFilter.java`:
  a direct unit test of `anchorNeedsReindexing` covering all three cases, plus two ingest-level tests (via the
  real SAX/`GraphFilter` harness) proving an unchanged property still resolves after a second version, and a
  newly-added label is still indexed even when its value duplicates one already anchored under another label.
  Full `stroom-graphdb-impl:test` suite and both checkstyle tasks pass clean.

#### Task P8.2 — Perf benchmarking for graph traversal
- **Depends on**: PoC.5/P3.2/P3.3 (`GraphTraversalEngine`, the class being benchmarked).
- **Gap this closes**: no automated way to observe `GraphTraversalEngine`'s cost at a realistic data volume, or to
  give future cursor/buffer-reuse work (e.g. `GraphNodeDb.getNode` allocates a fresh direct `ByteBuffer` per
  neighbour dereferenced during a traversal - a real, cited-but-unfixed cost, not this task's job to fix) a
  baseline to measure against.
- **Files**: `stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphTraversalEnginePerformance.java`
  (new) - mirrors `stroom.planb.impl.dao.TestStateDb`'s own `...Performance()` idiom (the one real, used
  perf-test precedent in this codebase): builds a synthetic graph at a meaningfully larger scale than any existing
  fixture (thousands of nodes, multiple labels, fan-out edges) and runs representative fixed-length and
  variable-length traversal queries against it, logging elapsed time for a human to eyeball - **no hard timing
  assertion** (CI hardware variance makes a ceiling assertion the wrong idiom, matching every existing perf test
  in this repo, none of which assert on elapsed time either), but the correctness of the returned rows *is*
  asserted (a perf test that silently stopped returning correct rows would be worse than no perf test at all).
- **Contract**: purely additive (a new test file); no production code changes.
- **Done-when**: the new test passes (both on row-count/content correctness and by actually running to
  completion at the larger data volume without excessive real time or memory).
- **Verify**: `./gradlew :stroom-graphdb:stroom-graphdb-impl:test --tests "stroom.graphdb.impl.TestGraphTraversalEnginePerformance"`.

**P8 exit gate** (hardening-only scope, per the user's decision above): `GraphFilter`'s node-ingest path no longer
re-indexes unchanged property values on every version, with tests proving both the reduction and that no anchor
resolution regressed; a synthetic-data performance test exists for `GraphTraversalEngine`, giving future
buffer/cursor-reuse work a measurable baseline. Cross-shard distributed traversal remains entirely unbuilt and
undesigned - a future phase's own dedicated design spike, not a gap in this one.

### Cross-cutting
- **E2E tests + synthetic graph generator**; **docs** (Cypher-subset reference, temporal syntax, ops guide) — a
  graph analogue of the optimiser's user guide.

---

## 7. Open decisions to resolve (record the choice in the PR)
- **D1 — module placement *(resolved: a dedicated `stroom-graphdb-impl` module)*.** Graph code lives in
  `stroom-graphdb-impl` (`stroom.graphdb.impl.*`), under a `stroom-graphdb/` parent mirroring
  `stroom-planb/stroom-planb-impl`, depending on `stroom-planb-impl` + the query-core modules + `stroom-core-shared`
  (which still holds the shared `GraphDbDoc` type, package `stroom.graphdb.shared`). Its own `GraphDbModule` owns the
  Guice wiring. **Action from this choice:** expose the reused Plan B internals across the module boundary (see the §2.1
  D1 blockquote) — do the access audit in PoC.0.
- **D2 — packaging *(resolved: a `GraphDbDoc` that wraps everything)*.** The graph is a single `GraphDbDoc`
  document type that **owns and encapsulates** its internal stores (§2.1) — *not* a `StateType.GRAPH` under
  `PlanBDoc`, and *not* a user-assembled Plan B doc + Index doc. This is the defining decision (the user's explicit
  requirement: minimise the configurable surface so a graph is hard to misconfigure). Built in PoC.0.
- **D3 — property-index technology *(resolved in P0.1: a Plan B `STATE` sub-store)*.** An internal, hidden default
  (the `GraphDbDoc` owns it, §2.1 — not a user knob). Chosen a Plan B `STATE`-style sub-store over a wrapped Lucene
  index because it lives under the doc's single LMDB env + single lifecycle (the encapsulation the doc exists to
  provide), and equality/prefix anchors — all the v1 subset needs — are served by the frozen `PropIndex` layout (§3
  P0.1). A wrapped Lucene index (text/tokenised/range anchors) is the documented escalation path, deferred because it
  adds a second internal store technology to the owned lifecycle.
- **D4 — which openCypher grammar *(resolved in P0.2: the openCypher reference ANTLR grammar)*.** Adopt the openCypher
  project's reference ANTLR4 grammar (the standardised subset, not Neo4j-proprietary), trimmed to the v1 subset.
  Licence: Apache-2.0. **Pin the exact upstream commit at import time and re-confirm the licence header in that
  commit** — the revision is an import-time record, not fixable from this design pass. Alternative considered: the
  `antlr/grammars-v4` `cypher` grammar (also permissive) — viable but needs more trimming.
- **D5 — relationship (edge) types in the catalogue**: `DomainType` models entities, not edges. Either derive edges
  from convention (event co-occurrence) or add an optional `List<RelationshipType>` (`{type, from, to, directed}`)
  to `DomainTypeDoc` — the additive, `canAccept`-reusing extension sketched in design §11. Decide when P2 ingest
  mapping and/or P6 autocomplete need it; not on the PoC critical path.
- **D6 — graph-plan wire payload**: a new additive `Query.graphSpec` field (recommended, mirrors how `Query.joinSpec`
  was added — additive, `@JsonInclude(NON_NULL)`, minor-version bump, update `ModelChangeDetector` portrait) vs
  overloading an existing field. Decided in PoC.6.
- **D7 — graph-mutation XML *(resolved in P2.1: a small new `graph-mutation:1` schema)*.** `reference-data:2` has
  no vocabulary for edges/labels/multiple-properties/`validFrom` at all - extending it would mean recreating most
  of what a purpose-built schema needs anyway. See Task P2.1 for the frozen v1 element shape.
- **D8 — the `GraphDbDoc` user-configurable surface**: which fields the user *can* set (§2.1). Recommended minimum:
  name/description + a retention/temporal-precision policy + an optional node/edge schema mapping (zero-config when
  derived from the domain-type catalogue). Everything physical stays hidden. Keep this list as small as the genuine
  choices demand — every field added is a new way to misconfigure. Finalised in PoC.0 (extended in P1 with the
  `retention` field; extended further if P2/P4 surface a genuine required choice).
- **D9 — graph retention/maintenance job model *(resolved in P1: no reuse of Plan B's `Shard`/`ShardManager`)*.**
  A `GraphDbDoc`'s stores are a single per-doc `PlanBEnv`, not a distributed/sharded Plan B state store — v1
  partitioning (P0.1) is already "by graph id, zero cross-shard hops," so `ShardManager`'s cross-node
  snapshot-transfer/shard-placement machinery solves a problem the graph store doesn't have. P1 gives graph
  retention its own small, single scheduled job (iterate `GraphDbDocStore.list()`, call each doc's
  `GraphStores.deleteOldData(doc)`) rather than growing `ShardManager` to cover a second, structurally different
  store type. Revisit only if multi-node graph query fan-out lands before P8 reprioritises partitioning.

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
