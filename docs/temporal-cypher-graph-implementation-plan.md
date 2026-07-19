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

**P1 exit gate**: `<-[:TYPE]-` and `-[:TYPE]-` Cypher patterns traverse correctly (not silently as `-[:TYPE]->`);
long property values no longer fail at the `Db.MAX_KEY_LENGTH` boundary; a full retention pass (delete + UID
sweep) across all four graph DAOs never corrupts a still-reachable floor lookup and actually reclaims space for
fully-superseded data. Property-index anchor bloat from retention is a documented, accepted v1 limitation (backstopped
by full rebuild), not a silent gap.

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
- **`AROUND ± d` / `BETWEEN`** window scans (interval intersection over the adjacency store's version runs, per P0.3's
  frozen rule); **as-of join** reusing the core's State-lookup path + Plan B floor lookup for multi-hop temporal
  (design §5.5 items 4–5).
- **Temporal correctness tests**: as-of across multi-hop, interval intersection edges.
- *Gate*: the §6 worked example (window + as-of in one query) returns the domain-owner-approved result.

### P5 — Query integration hardening (5–10 pw)
- **Graph datasource cost signals** (row/key counts, adjacency access-path costing) implementing a
  `stroom-query-planner.port` interface via an adapter in `stroom-graphdb-impl` (the port/adapter split, §2).
- **`GraphDbDoc` hardening** (PoC.0 built the doc + store-ownership scaffold): REST resource + **explorer handler**
  (the explorer-handler wiring that lives outside the doc-store module, §1.5); full owned-store lifecycle — **reprocess-rebuild** (drop + re-provision
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
- **D7 — graph-mutation XML**: a new schema vs a `reference-data:2` convention. Decided in P2.
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
