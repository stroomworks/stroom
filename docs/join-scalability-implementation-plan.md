# StroomQL join scalability — implementation plan (v1)

*Scope: the items selected from [stroomql-join-scalability-report.md](stroomql-join-scalability-report.md) —
**Lever A** in full (A1 push-down, A2 projection pruning, A3 time-partition pruning, A4 semi-join/bloom,
A5 late materialisation, A6 selectivity-aware side ordering), **B1** (enrichment `BROADCAST_LOOKUP`), **B5**
(restrict semantics + honest rejection), **C1–C5** (spill hash join, streaming, sort-merge, guardrails,
off-heap/columnar), and **D1** (broadcast join for a small side). Grounded in a code investigation of the current
executor, compiler, cost model, Plan B lookup API, and distributed-search machinery.*

---

## 0. Current state (what we're changing)

- **Compile:** `OptimisingQueryCompiler.createJoin` builds each side as a bare `select *` sub-query and passes a
  **`null` filter** to `compileJoinSide` ([OptimisingQueryCompiler.java:184-185](../stroom-query/stroom-query-common/src/main/java/stroom/query/language/OptimisingQueryCompiler.java)); the whole `WHERE` is kept on the outer request and applied post-join
  (design comment, :178-183). The per-side filter-injection plumbing **already exists but is dead code**
  (`compileJoinSide` :426-445).
- **Execute:** `JoinSearchProvider.createResultStore` runs each side to completion and **copies every row of both
  sides onto the heap** (`realiseSide`, [JoinSearchProvider.java:254-291](../stroom-search/stroom-searchable-impl/src/main/java/stroom/searchable/impl/JoinSearchProvider.java)), then calls a **hard-coded `HASH_JOIN`** (:164) that builds an
  on-heap `HashMap` over the entire right side (`JoinExecutor.hashJoin`, [JoinExecutor.java:104-121](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/join/JoinExecutor.java)). No streaming, no
  spill, no caps. The outer `WHERE` is applied per combined row afterwards (`whereRowPredicate`, :218-246).
- **Cost model** (`CostModel`, `JoinCostModel`) exists but is **only used by `EXPLAIN`**; index/state adapters are
  NoOp stubs, so it is blind in production and never consulted at execution.
- **Reusable substrate that the join bypasses:** the async cluster fan-out
  (`FederatedSearchExecutor`/`NodeSearchTaskCreator`, per-node shard assignment, time-partition pruning), the
  off-heap disk-backed `LmdbDataStore`, and coprocessor merge/early-termination.

The overall arc of this plan: **make the compiler emit smaller sides (Lever A), replace the materialise-both-on-heap
executor with a streaming/spilling one (C), add the enrichment fast path (B1), and then distribute (D1)** — with
guardrails and honest limits (C4/B5) landing first so nothing ships that can silently OOM.

---

## 1. Phasing & sequencing

Dependencies drive the order. Each phase is independently shippable and leaves joins correct.

| Phase | Items | Theme | Depends on |
|---|---|---|---|
| **0 — Safety** | C4, B5 | Guardrails + honest rejection | — |
| **1 — Reduce inputs (compile-time)** | A1, A2, A3 | Push-down, projection, time-pruning | 0 |
| **2 — Executor rework** | C2, C1, C5 | Streaming + spill + off-heap (one epic) | 0 |
| **3 — Algorithm selection & alts** | A6, C3, B1 | Build-side choice, sort-merge, enrichment | 2 (runtime sizing) |
| **4 — Advanced reduction** | A5, A4 | Late materialisation, semi-join/bloom | 1, 2 |
| **5 — Distribution** | D1 | Broadcast join | 2, 3 |

A3 largely *falls out* of A1 (a pushed time predicate reaches the side scan, which already prunes shards). A5 is
grouped late because it interacts with both push-down (1) and the executor rework (2).

---

## 2. Phase 0 — Safety net (C4, B5)

### C4 — Memory guardrails & backpressure  *(risk: LOW)*
- Add configurable caps to the join path: max realised rows/bytes per side, max output rows, wall-clock budget.
  Enforce in `JoinSearchProvider.realiseSide` (the `dataStore.fetch(... UNBOUNDED ...)` loop) and in the
  `JoinExecutor` output accumulation.
- On breach: abort with a clear `"join input exceeded N rows — add a filter or use an enrichment (Plan B) side"`
  error, surfaced as a search `errorMessage` (not a 500).
- **Why first:** today there are *zero* guardrails; this converts silent OOM into an actionable message and makes
  every later phase safe to ship incrementally.

### B5 — Restrict semantics + honest early rejection  *(risk: LOW)*
- Keep the existing up-front rejections (single-join-only, both-sides-plain-scans, INNER/LEFT-only) and add clear
  compile-time errors for the shapes v1 still won't execute (e.g. big⋈big with no reducing predicate once
  push-down exists — see A1 — can be *warned* or rejected under a config flag).
- Pair with C4: prefer a clear "unsupported / too large" error over an attempted impossible plan.

---

## 3. Phase 1 — Reduce inputs at compile time (A1, A2, A3)

This is the highest-ROI phase and is almost entirely in `OptimisingQueryCompiler`.

### A1 — Per-side predicate push-down  *(risk: MEDIUM — correctness-sensitive)*
- **Where:** `createJoin` :184-185 (stop passing `null`) and `compileJoinSide` :418-446 (the injection slot at
  :431-435 already sets `Query.expression`).
- **New helper — a per-conjunct WHERE splitter.** Assemble from existing parts:
  `AliasCollector`/`classify` ([PushFiltersBelowJoinsRule.java:117-194](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/rewrite/PushFiltersBelowJoinsRule.java)),
  `aliasOf` ([PlanRewriteUtil.java:139-142](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/rewrite/PlanRewriteUtil.java)),
  `isIndexEligible`/`lookupField` ([AutoWhereFilterSplitRule.java:131-158](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/rewrite/AutoWhereFilterSplitRule.java)).
  Given `(where, leftAlias, rightAlias)` it returns **(leftPush, rightPush, residual)** `ExpressionOperator`s:
  decompose only a top-level `AND`; for each *enabled* conjunct, if all-one-side + no unqualified fields +
  index-eligible → push (alias-stripped via `term.copy().field(strip)`); else keep in residual.
- **Correctness caveats (must-implement, not optional):**
  - **LEFT-join asymmetry:** for `A LEFT JOIN B`, pushing a predicate onto **B** (the null-supplying side) changes
    results (turns it into an inner join). For `JoinType.LEFT`, **only push preserved-side (left) predicates**;
    right-side predicates stay in the residual. (`PushFiltersBelowJoinsRule` does *not* check join type and is
    INNER-only — do **not** reuse it verbatim.)
  - **MatchNoDocs hazard:** only push **index-eligible** terms (`queryable()` + `ConditionSet.supportsCondition`);
    a non-eligible term pushed into a side's `Query.expression` can silently zero that side
    (`OptimisingQueryCompiler` :298-303). Non-eligible single-side terms may instead go to the side's
    `TableSettings.valueFilter` (existing path, `compileJoinSide` :436-444) so they still pre-filter post-extraction.
  - **No double application:** any conjunct pushed must be removed from the residual (harmless on INNER, *wrong* on
    LEFT).
- **Tests:** INNER push both sides; LEFT pushes left only; non-eligible term stays residual; OR/NOT spanning sides
  stays residual; **differential parity** vs today's post-join result (same rows).

### A2 — Projection pruning  *(risk: LOW–MEDIUM)*
- Replace the `select *` at `compileJoinSide` :422 with an explicit `select` of exactly: the side's **equi-key
  field(s)** (`JoinSpec.JoinEquiKey`), plus every field referenced by the **outer select / residual WHERE / group /
  having / sort**. Fewer columns ⇒ a smaller `FieldIndex` ⇒ fewer fields extracted by the scan
  (`SearchableSearchProvider` passes `coprocessors.getFieldIndex()` into `searchable.search`).
- **Caveat:** dropping a referenced column makes `buildFieldMapping` yield `-1` → `ValNull` → wrong results.
  Retain every column named anywhere downstream; add an assertion.

### A3 — Time-partition pruning on each side  *(risk: LOW)*
- Mostly a *consequence* of A1: a pushed time predicate reaches the side's ordinary `SearchProvider`, which already
  prunes shards by partition time (`NodeSearchTaskCreator.getPartitionTimeRange`). Verify the side sub-query
  inherits the outer time range / `QueryContext` so pruning actually fires; add a test that a time-bounded side
  scans fewer shards.

---

## 4. Phase 2 — Executor rework: streaming + spill + off-heap (C2, C1, C5)

Do these three as **one coordinated epic** — they all rewrite the same materialise-both-on-heap code in
`JoinSearchProvider`/`JoinExecutor`, and doing them separately would mean rewriting it three times.

### C2 — Streaming / pipelined execution  *(risk: MEDIUM)*
- Restructure `createResultStore` so the **probe side streams** (iterate `dataStore.fetch` incrementally) and
  joined rows are fed to `coprocessors.accept(...)` as they're produced, instead of realising both sides into
  `ArrayList`s first. Only the **build side** need be resident (or spilled — C1).
- This also unlocks `LIMIT`/early-termination (coprocessors can already signal completion) and yields a **runtime
  size signal** for the build side (count as you build) that Phase 3 uses instead of a cost model.

### C1 — Spill-to-disk hash join  *(risk: MEDIUM–HIGH)*
- Replace the on-heap `HashMap`/`ArrayList` build with a **disk-backed** structure. Reuse the existing off-heap,
  disk-backed `LmdbDataStore` machinery (already used by ordinary searches) as the build-side keyed store, or
  implement a **grace hash join** (partition both sides into disk buckets by key hash, join bucket-by-bucket).
- Bounded memory ⇒ "slower but finishes" instead of OOM. Manage the LMDB lifecycle (temp store per query, cleaned
  up on completion/abort) alongside C4's caps.

### C5 — Off-heap / columnar row storage  *(risk: MEDIUM)*
- Store join rows off-heap / in a compact columnar buffer to cut GC pressure and per-row object overhead. Largely
  **subsumed by C1** if the build side lives in LMDB; the remaining win is the probe/output buffers. Keep as a
  tuning step within the same epic rather than a separate operator.

---

## 5. Phase 3 — Algorithm selection & alternatives (A6, C3, B1)

### A6 — Selectivity-aware side ordering  *(risk: LOW–MEDIUM; depends on a size signal)*
- Choose the **build side = smaller side after filtering**, rather than always building the right side (today even
  the cost model's build-side choice is ignored). **v1 approach: runtime-measured** — realise/count the cheaper
  side first (or sample), decide, then build. This avoids needing the full cost model (see §7).
- Wire the decision into `JoinSearchProvider` where `HASH_JOIN` is hard-coded (:164).

### C3 — Sort-merge join  *(risk: MEDIUM–HIGH)*
- Add a merge-join operator for the both-sides-huge case: produce each side sorted on the join key (key-ordered
  index read where possible, else an external/LMDB-backed sort) and merge in one streaming pass with near-constant
  memory. Selected by A6/§7 when neither side is small and neither is a keyed lookup. Composes with `ORDER BY` on
  the key.

### B1 — Enrichment join via `BROADCAST_LOOKUP`  *(risk: MEDIUM–HIGH; highest value)*
- **Primitive:** `StateFetcher.getState(String map, String key, long effectiveTimeMs) → Val` (bound in
  `PlanBModule`; engine `PlanBQueryService.getVal` → `ShardManager.get` → `Db.getState`). Inject `StateFetcher`
  into `JoinSearchProvider` (new dependency).
- **New streaming executor** (the current `JoinExecutor.join(Side, Side, …)` signature can't host it — it throws
  for `BROADCAST_LOOKUP` at :99-101). For each **probe-side** row: build the key string from the equi-key
  positions (stringified like `JoinExecutor.keyOf`), call `getState(map, key, effectiveTime)`; non-`ValNull` →
  emit combined row; miss → drop (INNER) or null-pad (LEFT). Output straight to `coprocessors.accept(...)`. **Do
  not realise the build side.**
- **Detection (v1 = structural, avoids the missing stats adapter):** the build side's `DocRef.getType() ==
  PlanBDoc.TYPE` ("PlanB") **and** its equi-key is the store's `Key` field (`StateFields.KEY`) — the only
  point-lookup-addressable column. (The planner-driven route via `StateLookup`/`JoinCostModel` needs a real
  `StateStoreStats` adapter — see §7 — defer it.)
- **Known constraints to decide for v1:**
  - **Single-value output:** `getState` returns one `Val` (the store's `Value` column), *not* a multi-column row.
    v1 either (a) supports single-value enrichment only, or (b) does per-column lookups, or (c) adds a
    multi-column fetch API. Recommend (a) for v1, documented.
  - **Effective time:** choose the probe row's event time vs now for `TEMPORAL_*`/`SESSION` stores; make it
    explicit.
  - Today a Plan B side reaches the join as a **full scan** via `StateSearchProvider` + HASH_JOIN — B1 replaces
    that with the point-lookup path when detected.

---

## 6. Phases 4–5 — Advanced reduction & distribution (A5, A4, D1)

### A5 — Late materialisation  *(risk: MEDIUM)*
- Join on `(key, rowRef)` first; fetch wide columns only for rows surviving the join + residual `WHERE`. Keeps
  large columns out of the build/hash entirely. Interacts with A2 (projection) and the C-epic (the build store
  holds only keys+refs). Sequence after Phases 1–2.

### A4 — Semi-join / bloom-filter reduction  *(risk: MEDIUM–HIGH)*
- Compute the distinct key set (or a bloom filter) from the smaller/selective side, then push it as an
  `IN`/bloom predicate into the large side's scan (sideways information passing). Bloom false positives are
  re-checked by the actual join, so correctness is preserved. This is the most novel operator in Lever A;
  highest effort/risk within A. Depends on push-down (A1) and streaming (C2).

### D1 — Broadcast (replicated) join for a small side  *(risk: HIGH)*
- When one side is small after filtering (A1) and under a threshold (A6/§7), broadcast it to every node and join
  locally against each node's shards of the big side — fully parallel, no shuffle. Requires wiring the join into
  the **cluster fan-out** (`FederatedSearchExecutor`/`FederatedSearchTaskHandler`/`NodeSearchTaskCreator`) and
  per-node local execution, which the join path does not use today. Highest architectural risk of the selected
  set; sequence last and build on the streaming executor (C2) and build-side sizing (A6).

---

## 7. Are these low risk? — per-item assessment

**No — the set spans low to high.** Grouped:

| Risk | Items | Why |
|---|---|---|
| **Low** | C4 (guardrails), B5 (rejection), A3 (time pruning) | Additive caps/errors; A3 rides existing shard pruning. |
| **Low–Medium** | A2 (projection), A6 (build-side ordering) | Mechanical, but must retain referenced columns / needs a size signal. |
| **Medium** | A1 (push-down), C2 (streaming), C5 (off-heap), A5 (late materialisation) | Correctness care (LEFT-join asymmetry, MatchNoDocs), executor restructure. |
| **Medium–High** | C1 (spill), C3 (sort-merge), B1 (enrichment), A4 (semi-join/bloom) | New operators; disk lifecycle; multi-column/temporal gaps; new SIP path. |
| **High** | D1 (broadcast) | Requires distributed execution wiring the join has never used. |

The single most correctness-sensitive item is **A1** (the LEFT-join push-down asymmetry — get it wrong and LEFT
joins silently return inner-join results). The single highest-architecture-risk item is **D1**. The highest
value-for-risk is **A1 + B1**.

---

## 8. Other items that should be in v1 (gaps the selected set implies)

The selected items have soft dependencies and gaps that v1 should cover explicitly:

1. **A build-side / broadcast *size signal* — but not the full cost model.** A6, C1's build-side choice, and D1's
   "is the side small enough to broadcast?" all need to know relative side sizes. **Recommendation:** use
   **runtime-measured sizing** (count/sample as the streaming executor realises a side — falls out of C2) rather
   than blocking on the cost-model adapters. This is lower risk than implementing `IndexShardStats`/
   `StateStoreStats` and is robust to bad estimates on 10¹¹-row data.
2. **A single algorithm-selection seam.** Today `JoinSearchProvider` hard-codes `HASH_JOIN` (:164). Add one
   decision point (`chooseJoinStrategy`) that picks {broadcast-lookup (B1) | broadcast (D1) | hash/spill (C1) |
   sort-merge (C3)} from the runtime size signal + structural detection. Everything in Phases 3–5 plugs into it.
   *(This is the minimal slice of report-item E23 — wiring selection into execution — without the full cost model.)*
3. **A join correctness/differential test harness.** Because push-down and the executor rework change how results
   are produced, add tests that assert **row-for-row parity** with today's (correct) HASH_JOIN output across
   INNER/LEFT, null keys, non-matching rows, and the LEFT-push asymmetry. This is effectively mandatory to ship
   A1/C-epic safely.
4. **Decide the B1 multi-column enrichment story for v1** (single-`Val` output vs per-column vs new fetch API) and
   the **effective-time semantics** for temporal stores — don't leave them implicit.
5. **Guardrails + honest rejection first (C4/B5).** Already in the set, but call out the sequencing: they must land
   in Phase 0 so every subsequent phase is safe to ship behind them.

**Explicitly deferred (report items not selected), and fine to defer:** full cost-model adapters (E22) and
cost-based planning (beyond the selection seam), shuffle/partitioned hash join (D2), key-co-located ingest (D3),
denormalisation/materialised views (B2/B3), and the approximate/top-K family (F). None block v1; several (E22, D2)
become worthwhile once the runtime-sizing seam proves insufficient.

---

## 9. v1 cut line — ACCEPTED (2026-07-20)

**Agreed v1 scope: Phase 0 (C4, B5) + Phase 1 (A1, A2, A3) + B1.** Everything else in this document is deferred
to a later version. Implementation proceeds phase by phase (0 → 1 → B1), each independently shippable, behind the
Phase-0 guardrails, with the differential/parity test harness (§8.3) as a gate.

If v1 must be a subset: **Phase 0 (C4, B5) + Phase 1 (A1, A2, A3) + B1** delivers the two dominant real-world
cases — *"big events ⋈ small reference/Plan B data"* (B1) and *"big⋈big made small by a selective predicate"*
(A1) — with a safety net, at Medium risk and no distributed-execution work. Phase 2 (streaming/spill) is the next
increment for the remaining big⋈big cases; D1 (distribution) is a deliberate, later, High-risk step.

### Phase 0 — DONE (2026-07-20)

- **C4** — new `JoinConfig` (`stroom.query.join.maxSideRows` / `.maxOutputRows`, default 1,000,000 each) nested
  into `QueryConfig`; `ConfigProvidersModule` and the `expected.yaml` fixture regenerated. `JoinExecutor` gained a
  row-capped `join(...)` overload (checked per emitted row, not just at the end) throwing the new
  `JoinLimitExceededException`; `JoinSearchProvider.realiseSide` enforces the per-side cap the same way.
  `JoinSearchProvider.createResultStore` was restructured to build the outer `ResultStore` *before* realising
  either side (matching `SearchableSearchProvider`'s established pattern), so any failure — including a guardrail
  breach — is captured via `resultStore.addError(...)` instead of propagating as an exception or OOM.
- **B5** — no new production code was needed: the grammar already only parses `LEFT`/`INNER` (a `RIGHT`/`FULL`
  join is a plain parse error, not a silent misexecution) and `OptimisingQueryCompiler.createJoin` already rejects
  N-way chains and non-scan sides clearly. Added a regression test locking in the `RIGHT JOIN` parse rejection.
  C4's `JoinLimitExceededException` messages serve as the "too large" honest rejection this item called for.
- Tests: `TestJoinConfig` (10), `TestJoinExecutor` (+10, now 25), `TestJoinSearchProvider` (+3, now 16),
  `TestOptimisingQueryCompilerJoin` (+1, now 5) — all green, plus the existing config-tree test suites.

---

## 10. Decisions (resolved) — v1 hand-off

These close the open choices so the plan is self-contained for an implementer. All are **firm for v1**.

**D1 — Guardrail configuration.** Add a new `JoinConfig` (immutable, `@JsonCreator`, `AbstractConfig` +
`IsStroomConfig`, modelled on `QueryOptimiserConfig`) nested into `QueryConfig` under property `join`, giving:
- `stroom.query.join.maxSideRows` — max rows realised per side before abort. Default **1,000,000**.
- `stroom.query.join.maxOutputRows` — max joined output rows before abort. Default **1,000,000**.

Bytes and wall-clock budgets are deferred (rows are the OOM guard for v1). After adding it, **run
`GenerateConfigProvidersModule`** to regenerate `ConfigProvidersModule`, and ensure `TestConfigProvidersModule`
and the `TestAppConfig*` config-tree tests are green. Enforce both caps in `JoinSearchProvider` — in
`realiseSide`'s fetch loop (per side) and the output feed loop — and on breach abort with a clear
`errorMessage` via `resultStore.addError(...)` (never an OOM or HTTP 500).

**D2 — Non-index-eligible single-side terms** stay in the **residual** (post-join) predicate for v1. Do **not**
route them to `TableSettings.valueFilter` yet (that optimisation is deferred).

**D3 — The WHERE splitter is a new, per-conjunct helper.** Do **not** reuse `PushFiltersBelowJoinsRule` (it is
INNER-only and classifies the whole predicate slot, not per-conjunct). The new helper may borrow `AliasCollector`,
`aliasOf`, and `isIndexEligible`/`lookupField`. It decomposes only a top-level `AND`; each enabled conjunct that
is all-one-side, has no unqualified field, and is index-eligible is pushed (alias-stripped); everything else stays
residual. For `JoinType.LEFT`, only preserved-(left-)side conjuncts may be pushed.

**D4 — A2 retained-column set** for each side = its equi-key field(s) ∪ every field that side's alias contributes
to the outer `select` ∪ the residual `WHERE` ∪ any `group`/`having`/`sort`. Gather these by walking the outer
compiled request. Add a retain-all assertion: dropping a referenced column makes `buildFieldMapping` return `-1`
→ `ValNull` → wrong results, so a missing column must fail loudly, not silently.

**D5 — B1 output shape: single enriched value.** `StateFetcher.getState` returns one `Val`, so model the Plan B
lookup side as exactly two synthetic columns `[Key, Value]` (Key = the probe row's join-key value; Value = the
looked-up `Val`). Multi-column enrichment is deferred.

**D6 — B1 store types: `STATE` and `TEMPORAL_STATE` only.** Reject `RANGED_STATE`, `TEMPORAL_RANGED_STATE`,
`SESSION`, and the rest up front with a clear "not supported for join enrichment in this version" error (they need
different key parsing / return handling).

**D7 — B1 effective time** = the search's effective time (`QueryContext` time-range end, else `now()`). It is
passed to `getState(map, key, effectiveTimeMs)`; for non-temporal `STATE` stores it is ignored by the engine.

**D8 — B1 strategy seam.** Replace the hard-coded `JoinAlgorithm.HASH_JOIN` at
`JoinSearchProvider.createResultStore` (:164) with a `chooseJoinStrategy(...)` returning `{HASH_JOIN,
BROADCAST_LOOKUP}`. Detection is **structural**: a side whose `DocRef.getType()` equals `PlanBDoc.TYPE` **and**
whose equi-key field equals the store's key column (`StateFields.KEY`). Restructure `createResultStore` to branch
**before** realising the lookup side — for `BROADCAST_LOOKUP`, realise only the probe side and stream it. Add a new
streaming method on `JoinExecutor` (or a sibling operator) with signature roughly
`join(Iterator<Val[]> probe, int[] probeKeyPositions, StateFetcher fetcher, String mapName, long effectiveTimeMs,
JoinType joinType, Consumer<Val[]> out)`; per probe row: build the key string (same stringification as
`JoinExecutor.keyOf`), look up, emit `[probe…, Value]` on hit, drop (INNER) or null-pad (LEFT) on miss.

### Phase 1 — DONE (2026-07-20)

- **A1 (push-down)** — new `JoinPredicateSplitter` (per-conjunct, deliberately independent of the existing
  `PushFiltersBelowJoinsRule`, which is whole-slot and not `JoinType`-aware). Only a bare, enabled top-level
  `ExpressionTerm` conjunct is ever pushed; a nested operator, an unqualified field, or a non-index-eligible field
  always stays in the residual (D2). **`JoinType.LEFT` never pushes to the right (null-supplying) side** - the
  single most correctness-sensitive rule in this plan, proven both at the unit level (`TestJoinPredicateSplitter`)
  and end-to-end (`TestOptimisingQueryCompilerJoin`). Wired into `OptimisingQueryCompiler.createJoin`: the outer
  `where` is split once, each side's pushed predicate is alias-stripped and passed into `compileJoinSide`, and the
  residual replaces the outer `Query.expression`.
- **A2 (projection pruning)** — new `JoinProjectionAnalyzer.fieldsNeededFor`: each side now selects exactly its
  equi-key field(s) plus every field the outer query references it by (select columns - scanned by a conservative
  regex over the raw expression text, since a `Column.getExpression()` can be an arbitrary formula, not just a
  bare field - plus the residual `where`, `valueFilter`, and `aggregateFilter`), instead of `select *`. Wired via a
  new required `selectFields` parameter on `compileJoinSide`.
- **A3 (time-partition pruning)** — turned out to need a real code change, not just verification:
  `NodeSearchTaskCreator.getPartitionTimeRange` only ever reads `Query.timeRange`, never derives bounds from
  `Query.expression` directly, so a pushed time predicate would filter rows but never prune shards without this.
  `createJoin` now reuses the existing Task 5.2 `applyTimeRange` helper on each side (only when something was
  actually pushed to it), promoting a pushed time-bound predicate into that side's `Query.timeRange`.
- Tests: `TestJoinPredicateSplitter` (13, new), `TestJoinProjectionAnalyzer` (11, new),
  `TestOptimisingQueryCompilerJoin` (10, +5 new), `TestOptimisingQueryCompilerJoinSideCompilation` (6, updated for
  the new `selectFields` parameter) - all green, plus a full regression sweep of `stroom-query-common`,
  `stroom-query-planner`, and `stroom-search:stroom-searchable-impl` (including the differential parity suites
  `TestQueryCompilerParity`/`TestQueryCompilerGenerativeParity`) with no regressions.

### B1 — DONE (2026-07-20)

- New `JoinExecutor.broadcastLookupJoin`: streams a probe side, doing one `StateFetcher.getState(...)` point
  lookup per row instead of materialising the lookup side. Combined row is always `[probe…, Key, Value]`
  (decision D5); `INNER` drops a miss/null-key, `LEFT` null-pads it; guarded by the same `maxOutputRows` cap as
  `join(...)`, checked per row.
- `JoinSearchProvider.detectPlanBLookupSide`: **structural** detection (decision D8) - exactly one equi-key, that
  side's `DocRef.getType() == "PlanB"`, and the equi-key field is exactly `"Key"` - both constants duplicated as
  literals rather than adding a `stroom-planb-impl` module dependency (the `StateFetcher` *interface* is already
  visible via `stroom-query-language`; Guice resolves the real binding from `stroom-planb-impl`'s module without
  a compile-time dependency). `joinAndFeed` dispatches to `joinAndFeedViaBroadcastLookup` (realises only the
  probe side) or falls back to the original `joinAndFeedViaHashJoin`.
- Known v1 limitations, both explicitly documented in code: (a) the underlying store's actual `stateType` isn't
  checked (decision D6) - a `RANGED_STATE`/`SESSION` store would still be "detected" and then fail with whatever
  exception `StateFetcher` throws for a mismatched key, always safely captured, just a less specific message;
  (b) effective time (decision D7) is always `System.currentTimeMillis()` - "the query's own effective time" is a
  documented follow-up, not implemented.
- Tests: `TestJoinExecutor` (+10, now 35), `TestJoinSearchProvider` (+9, now 25) covering matching/miss/INNER/
  LEFT/lookup-on-either-`JoinSpec`-side/`where`-clause-on-the-looked-up-value/both guardrails/non-`"Key"`-field
  fallback/composite-key fallback - all green.

### Task #18 — differential/parity test harness — DONE (2026-07-20)

New `TestJoinPushDownDifferential` (5 tests): every other join test proves correctness against a fake
`SearchProvider` that *ignores* the sub-request it's handed, which can never catch a bug in *what gets pushed*.
This harness instead uses a `realisticFakeProvider` that genuinely evaluates each side's compiled `where` clause
and column selection against a fixed in-memory table, then compares an "unoptimised" baseline (`select *`, no
push, full residual) against the "optimised" shape (pushed filter + pruned select) for the same logical query,
asserting byte-identical joined rows. Covers left-push/right-push under `INNER`, left-push under `LEFT` (the
correctness-critical case), projection pruning alone, and push+prune combined. **Sanity-checked live**:
deliberately corrupted a pushed predicate's value, confirmed the harness failed (caught it), then reverted and
confirmed green - proving it is a real check, not a vacuous one.

**v1 scope (Phase 0 + Phase 1 + B1 + the test harness) is now complete.**

---

## 11. Coding standards for implementation (required)

All new and modified code in this plan must meet these; they are acceptance criteria, not suggestions.

- **Readability.** Small, single-purpose methods; names and structure matching the surrounding code; comment
  density and idiom consistent with the file being edited (e.g. the existing `JoinSearchProvider` /
  `OptimisingQueryCompiler` style). No clever one-liners where a named helper is clearer.
- **Javadoc on every new/changed class and every public or package-private method**, explicitly stating:
  - **Preconditions** — including which parameters must be non-null and any value constraints.
  - **Postconditions** — including the return value's semantics and whether it can be null/empty.
  - **Null status** — of every parameter and the return.
  Follow the existing house style (see `AstToSearchRequestMapper` / `AlternativeQueryCompilerResolver`, whose
  Javadoc already documents pre/postconditions and null status).
- **Enforce the contract in code, don't just document it.** Check preconditions at method entry
  (`Objects.requireNonNull(x, "x")` for non-null args with a message; throw `IllegalArgumentException` or a clear
  domain exception for invalid values), mirroring the constructor checks already in `JoinSearchProvider`. Where a
  postcondition/invariant is cheap to verify (e.g. the A2 retain-all check in D4, mapping-array lengths), assert or
  validate it rather than assuming it.
- **Null status via JSpecify on server-side code.** Use `org.jspecify.annotations.@Nullable` / `@NonNull`,
  consistent with existing usage (e.g. `AlternativeQueryCompilerResolver` already imports
  `org.jspecify.annotations.Nullable`). Prefer marking the package `@NullMarked` (via `package-info.java`) and
  annotating only the nullable exceptions, where the module has adopted that; otherwise annotate members
  explicitly. Keep annotations consistent within a file.
- **Tests are part of "done."** The differential/parity harness (task #18) gates A1 and the executor changes;
  additionally unit-test the WHERE splitter (D3), the guardrail caps (D1), the A2 retained-column computation
  (D4), and the B1 structural detection + streaming lookup (D5–D8). Follow the repo's existing test style
  (`TestOptimisingQueryCompiler*`, `TestJoinCostModel`, `Test…` dynamic-test factories).

