# StroomQL joins — status & roadmap

*A guide to how StroomQL `join` executes today, what it can and can't do, whether it is memory-safe, and where it
could go next. Origin analysis: [stroomql-join-scalability-report.md](stroomql-join-scalability-report.md);
human-facing "why" write-up of the future work: [query-optimiser-joins-future.md](query-optimiser-joins-future.md).
The detailed build-log, decisions, and coding standards are in the [Reference](#reference) section at the end.*

**TL;DR** — A two-source `INNER`/`LEFT` join works end-to-end. The compiler shrinks each side (push-down,
projection & time pruning); a keyed Plan B/State side is enriched by point lookup without being scanned; every
other join streams one side and spills the other to disk. **Under default configuration it cannot exhaust heap on
any input shape** — it either completes or aborts with a clear error. What's *not* done is scale-out: the join
still runs on a **single node**, always builds the right side, and has no semi-join reduction.

---

## How a join executes today

A `join` query is compiled by `OptimisingQueryCompiler.createJoin` and executed by `JoinSearchProvider`, which
picks one of two paths per query:

```
                          ┌─ one side is a keyed Plan B/State store (type "PlanB", equi-key = "Key")?
   compile & reduce ──────┤
   each side (A1/A2/A3)   ├─ YES → Enrichment fast path  (BROADCAST_LOOKUP)
                          │         stream the other side; one point lookup per row; never scan the store
                          │
                          └─ NO  → Streaming hash join
                                    build RIGHT into a spillable store; stream LEFT through it
```

**Compile-time reduction (runs for both paths).** Each side becomes its own single-source sub-query, made as
small as possible before it executes:
- **A1 push-down** — per-side `WHERE` conjuncts are pushed onto the side that owns them (never onto a `LEFT`
  join's right/null-supplying side); the rest stays as a residual applied to the combined row.
- **A2 projection pruning** — each side selects only the columns actually needed (its equi-key plus fields the
  outer query references), not `select *`.
- **A3 time pruning** — a pushed time-bound predicate is promoted into the side's time range so its scan prunes
  shards.

**Enrichment fast path** (`joinAndFeedViaBroadcastLookup`). The probe side is streamed; each row does one
`StateFetcher.getState(map, key, time)` lookup. The lookup store is never scanned or materialised. Output row is
`[probe columns…, Key, Value]` — single-value enrichment. INNER drops a miss; LEFT null-pads it.

**Streaming hash join** (`joinAndFeedViaStreamingHashJoin`). One side is read into a `SpillingBuildSideLookup`
(an on-heap hash map while small, spilling to a disk-backed `LmdbJoinBuildStore` once it crosses a threshold); the
other is streamed one row at a time, matched against the build side, and surviving rows are fed straight to the
result store — the probe side is never held in memory. **Build-side selection (A6):** for an `INNER` join the
**smaller** side (by `DataStore.getSize()`) is built and the larger streamed; a `LEFT` join always builds the
right side / streams the left, because its preserved (left) side must be the probe side so unmatched rows are
null-padded inline with no bookkeeping.

---

## What works now

| Capability | Status | Notes |
|---|---|---|
| Two-source `INNER` / `LEFT` join | ✅ | `RIGHT`/`FULL` are a grammar parse error; N-way chains rejected at compile |
| Per-side predicate push-down (A1) | ✅ | `LEFT`-safe (never pushes to the null-supplying side) |
| Projection pruning (A2) | ✅ | each side scans only the columns needed |
| Time-partition pruning (A3) | ✅ | a pushed time bound prunes the side's shards |
| Enrichment via keyed Plan B/State lookup (B1) | ✅ | streams the probe; never scans the store; single-value output |
| Streaming probe + disk-spilling build (C2/C1) | ✅ | big⋈big completes by spilling instead of failing |
| Adaptive heap→disk spill by rows *and* bytes | ✅ | spills on `maxHeapBuildRows` **or** `maxHeapBuildBytes` |
| Build-side selection (A6) | ✅ | `INNER` builds the smaller side (via `DataStore.getSize()`); `LEFT` keeps build = right |
| Memory guardrails + honest rejection (C4/B5) | ✅ | clean error, never a silent OOM or HTTP 500 |
| Differential/parity test harness | ✅ | asserts byte-identical rows vs. the pre-optimisation baseline |

---

## Is it memory-safe? (10¹¹ rows per side)

**Yes, under default configuration it cannot heap-OOM on any input shape** — this was the explicit goal of the
safety work. At extreme size it aborts cleanly rather than crashing:

- **Build (right) side** spills to disk past `maxHeapBuildRows` / `maxHeapBuildBytes`, so it doesn't sit in heap;
  it aborts with a clear `JoinLimitExceededException` only if it exceeds the absolute `maxSideRows` ceiling, or
  fails with an actionable "increase the map size or add a filter" message if it fills the LMDB spill map.
- **Probe (left) side** is streamed, so its size never affects heap — 10¹¹ probe rows flow through in bounded
  memory (both the hash-join and the enrichment path).
- **A single hot key** (huge fan-out on one join key) is bounded by `maxOutputRows`, checked *during* the fan-out
  — the matching group is streamed one row at a time, never materialised.
- **Wide rows** (few but large) trip the byte-based spill trigger, so they spill instead of filling heap under the
  row count.

**The one documented residual:** if an operator both disables off-heap result storage
(`stroom.search.resultStore.offHeapResults=false`) *and* raises `maxOutputRows` far above the default, a very
large join *output* could pressure heap. That is a deliberate global-config choice, noted on `JoinConfig`, not a
join defect.

### Configuration knobs (`stroom.query.join.*`)

| Property | Default | Meaning |
|---|---|---|
| `maxHeapBuildRows` | 500,000 | Build side stays on heap until this many rows, then spills to disk. |
| `maxHeapBuildBytes` | 256 MiB | …or this much estimated heap, whichever is hit first (catches wide rows). |
| `maxSideRows` | 10,000,000 | Absolute ceiling on build-side rows (heap **and** spilled). Breach → clean abort. |
| `maxOutputRows` | 1,000,000 | Cap on joined output rows. Breach → clean abort. |

Raising the caps is safe from a heap perspective (the build spills, the probe streams); it mainly trades disk/time
for a higher ceiling.

---

## Current limits (not done yet)

These are **capability/scale** gaps, not safety gaps:

- **Single-node execution** — the join never uses the cluster fan-out that ordinary search does; one node scans
  and probes everything. This is the main throughput ceiling.
- **`LEFT` joins always build the right side** — `INNER` joins pick the smaller side (A6), but a `LEFT` join can't
  swap (its preserved side must be the probe side); swapping a `LEFT` join would need outer-join bookkeeping and
  is deferred.
- **No semi-join reduction** — a big⋈big join whose only correlation is the join key itself (no literal `WHERE`
  to push) still scans both sides in full.
- **Two-source only** — no N-way joins.
- **Enrichment is single-value** — one `Val` per lookup, and the effective time is "now" rather than the query's
  own time (a documented follow-up).
- **Sub-searches run sequentially** and `LIMIT` doesn't early-terminate the probe scan (wasted work on a
  limited query, not a correctness or memory issue).

---

## Where we could go (roadmap)

Ordered by value-for-effort now that safety is settled. "Buys" says what each step actually improves.

| # | Step | Buys | Status | Risk |
|---|---|---|---|---|
| 1 | Streaming + disk spill (C2+C1) | Higher ceiling: big⋈big completes instead of failing | ✅ Done | — |
| — | OOM-risk reduction | Memory-safe on every shape, incl. key skew & wide rows | ✅ Done | — |
| 2 | Build-side selection (A6 swap) | Speed on skewed joins: build the *smaller* side (`INNER`) | ✅ Done | — |
| 3 | Semi-join / bloom reduction (A4) | Speed on unfiltered big⋈big (push one side's keys into the other) | ⏳ Deferred* | Med–High |
| 4 | Broadcast join across the cluster (D1) | First real parallel throughput | ⏳ Future | High |
| 5 | Cost-model wiring (E22/E23) | Robustness: avoid *bad* strategy choices | ⏳ Future | Med |
| 6 | Sort-merge (C3) / shuffle hash (D2) | Extreme big⋈big where neither side is small | ⏳ Future | High |

\* A4 is deferred with a specific reason (see below), not merely unscheduled.

**Near-term detail:**

- **Build-side selection (2)** is **done** for `INNER` joins — the smaller side (by `DataStore.getSize()`) is
  built and the larger streamed, a real win for skewed joins and never a larger build side than before. `LEFT`
  joins still build the right side (they can't swap without outer-join bookkeeping). The next increment here would
  be swapping `LEFT` joins too, if that case proves worth the bookkeeping.
- **Semi-join (3)** is the lever for "today's events ⋈ yesterday's active users" — correlated only by the key, so
  A1 has nothing to push. It's **deferred with rationale**: Stroom's expression model can only carry a value set
  as a space-delimited `IN` string (unsafe for arbitrary keys — no escaping) or a *persisted* dictionary doc, and
  the pushed predicate is evaluated by each side's own provider with different `IN`/field-type semantics. Doing it
  correctly needs its own design pass (a keyword-field guard, or a small expression-model extension), not a
  bolt-on.
- **Broadcast join (4)** is the first genuinely distributed step: replicate a small-but-not-tiny side to every
  node and join locally against each node's shards. It rides the existing cluster fan-out the join path has never
  used — the highest-value, highest-risk near-term item.

**Honest summary.** Steps 1 + OOM-reduction bought **robustness and a much higher ceiling** (flat or slightly
slower for joins that already fit in heap). Build-side selection (2) bought **speed on skew** for `INNER` joins.
Semi-join (3) buys **real speed** on the common unfiltered big⋈big case. Broadcast (4) is the first step that buys **actual parallel
throughput** rather than "doesn't fall over." Re-measure real workloads after (4) before committing to (5)–(6) —
they only pay off if genuinely unfiltered, unskewed big⋈big queries show up in practice.

---

## Reference

The origin analysis and the per-item risk assessment live in
[stroomql-join-scalability-report.md](stroomql-join-scalability-report.md). What follows is the build-log,
the resolved design decisions, and the coding standards — kept for anyone extending the join.

### Risk assessment (per item, from the original plan)

| Risk | Items | Why |
|---|---|---|
| Low | C4 (guardrails), B5 (rejection), A3 (time pruning) | Additive caps/errors; A3 rides existing shard pruning. |
| Low–Medium | A2 (projection), A6 (build-side ordering) | Mechanical, but must retain referenced columns / needs a size signal. |
| Medium | A1 (push-down), C2 (streaming), C5 (off-heap), A5 (late materialisation) | Correctness care (LEFT-join asymmetry, MatchNoDocs), executor restructure. |
| Medium–High | C1 (spill), C3 (sort-merge), B1 (enrichment), A4 (semi-join/bloom) | New operators; disk lifecycle; multi-column/temporal gaps; new SIP path. |
| High | D1 (broadcast) | Requires distributed execution wiring the join has never used. |

The single most correctness-sensitive item was **A1** (LEFT-join push-down asymmetry — get it wrong and LEFT
joins silently return inner-join results). Highest architecture risk is **D1**. Highest value-for-risk was
**A1 + B1**.

### Build log (what shipped, and why)

**Phase 0 — guardrails & honest rejection (C4, B5) — DONE (2026-07-20).** New `JoinConfig`
(`stroom.query.join.maxSideRows`/`.maxOutputRows`) nested into `QueryConfig`; `JoinExecutor`/`JoinSearchProvider`
enforce the caps per row and report a breach via `resultStore.addError(...)` (never OOM/500). `RIGHT`/`FULL` are
already grammar parse errors; N-way and non-scan sides already rejected at compile.

**Phase 1 — compile-time reduction (A1, A2, A3) — DONE (2026-07-20).**
- **A1** — new `JoinPredicateSplitter` (per-conjunct, `JoinType`-aware; deliberately *not* the whole-slot,
  INNER-only `PushFiltersBelowJoinsRule`). Only a bare, enabled, single-side, index-eligible top-level
  `ExpressionTerm` is ever pushed; everything else stays residual. **`LEFT` never pushes to the right side** — the
  most correctness-sensitive rule, proven at unit and end-to-end level.
- **A2** — new `JoinProjectionAnalyzer.fieldsNeededFor`: each side selects its equi-key(s) plus every field the
  outer query references (select/residual-where/valueFilter/aggregateFilter), instead of `select *`.
- **A3** — `createJoin` promotes a pushed time-bound predicate into the side's `Query.timeRange` (needed because
  shard pruning reads `timeRange`, not the expression).

**B1 — enrichment via `BROADCAST_LOOKUP` — DONE (2026-07-20).** `JoinExecutor.broadcastLookupJoin` streams the
probe side, doing one `StateFetcher.getState(...)` lookup per row; output `[probe…, Key, Value]`.
`JoinSearchProvider.detectPlanBLookupSide` is **structural** (type `"PlanB"` + equi-key field `"Key"`), avoiding a
`stroom-planb-impl` dependency. Known v1 limits (documented in code): store `stateType` isn't checked up front
(a mismatched store fails safely, just less specifically); effective time is `now()`, not the query's own time.

**Differential/parity harness (task #18) — DONE (2026-07-20).** `TestJoinPushDownDifferential` uses a provider
that genuinely evaluates each side's pushed `where`/projection against a fixed table, and asserts byte-identical
rows between the unoptimised baseline and the optimised shape. Sanity-checked live (deliberately broken → caught).

**Streaming + spill (C2+C1) and the A6 size signal — DONE (2026-07-21).**
- Orientation kept **build = right / probe = left** (today's proven shape) — unmatched `LEFT` rows emit inline
  with zero bookkeeping.
- New pure `BuildSideLookup` interface + `HeapBuildSideLookup` (planner); `JoinExecutor.streamingHashJoin`/
  `streamingProbe` — the list-returning `hashJoin` delegates to the same loop (one implementation).
- `LmdbJoinBuildStore` (query-common) — a bespoke keyed multimap on the low-level `stroom.lmdb2` primitives (not
  `LmdbDataStore`, which collapses grouped rows and so can't preserve join rows). One non-DUPSORT DB, key =
  length-prefixed `encode(joinKey)` ++ 8-byte sequence (duplicates preserved; over-long keys fail clearly), value
  = `ValSerialiser` bytes; retrieval by prefix scan. `SpillingBuildSideLookup` is on-heap until the threshold then
  drains once to the LMDB store. `JoinBuildSideLookupFactory` owns the LMDB wiring so `stroom-searchable-impl`
  needs no LMDB dependency.
- **A6** delivered as the heap→spill decision (driven by the build side's live row count), **not** a left/right
  swap — a cheap pre-scan size oracle was confirmed not to exist (`MapDataStore.getByteSize()` serialises the
  whole dataset; no store exposes an O(1) count), so cost-based side selection stays deferred.
- `maxSideRows` default raised 1M → 10M (now the absolute build-side ceiling incl. spilled rows, not the heap
  guard). Not done: cross-side pipelining and `LIMIT` early-termination.

**OOM-risk reduction — DONE (2026-07-21).** Closed the residual heap-OOM paths a review found:
- **Build-side key skew** — `get(key):List` replaced by streaming `boolean forEachMatch(key, Consumer)`;
  `LmdbJoinBuildStore` hands over one prefix-scanned row at a time; `streamingProbe` emits per match, so the
  output cap fires *during* a hot key's fan-out. `get()` removed (no re-materialise foot-gun).
- **B1 probe side now streams** — new `JoinExecutor.broadcastLookupProbe(...)`;
  `joinAndFeedViaBroadcastLookup` uses `openSide`+`fetchRows` instead of `realiseSide` (deleted, with
  `RealisedSide`), so enrichment over a huge event stream is bounded and no longer row-capped.
- **Width-aware spill** — new `maxHeapBuildBytes`; `SpillingBuildSideLookup` spills on rows **or** an
  over-estimating `estimateHeapBytes` (a spill trigger only, never a correctness input).
- **Clearer failure** — `LmdbJoinBuildStore` translates LMDB `MapFullException` into an actionable message.

**Build-side selection (A6) — DONE (2026-07-21).** For an `INNER` join `JoinSearchProvider` now builds the
**smaller** side and streams the larger, instead of always building the right side. The size signal is a new O(1)
`DataStore.getSize()` (the cumulative received-row count, exposed from the store's existing `totalResultCount`;
thoroughly documented — relative signal, never a correctness input). A `LEFT` join never swaps — its preserved
side must stay the probe side so unmatched rows null-pad inline. The existing `whereRowPredicate`/
`buildFieldMapping` are positional, so they were reused for either orientation (as the broadcast-lookup path
already did). Pareto-safe: the built side is always `min(left, right) ≤` the old always-right choice, so it can
only reduce spilling/`maxSideRows` aborts. Tests: `TestJoinSearchProvider` (builds-left / builds-right /
LEFT-never-swaps), a swap-vs-no-swap byte-identical parity case in `TestJoinPushDownDifferential`, and
`getSize()` coverage for both stores via `AbstractDataStoreTest`.

**A4 (semi-join / bloom) — DEFERRED, with rationale (2026-07-21).** A correct semi-join *push* depends on
machinery the expression model doesn't offer safely: the only value-set conditions are a space-delimited `IN`
string (no escaping → corrupts keys with spaces) or `IN_DICTIONARY` (needs a persisted `DictionaryDoc`), and the
pushed predicate is evaluated by each side's own provider with different `IN`/analysed-vs-keyword semantics. A
post-scan filter would save no scan I/O and defeat the purpose. Needs its own design pass.

### Resolved design decisions (firm)

- **D2 — Non-index-eligible single-side terms** stay in the residual (post-join) predicate; not routed to
  `TableSettings.valueFilter` (deferred).
- **D3 — The WHERE splitter is a new per-conjunct helper** (`JoinPredicateSplitter`), not `PushFiltersBelowJoinsRule`
  (whole-slot, INNER-only). Decomposes only a top-level `AND`; pushes an all-one-side, unqualified-field-free,
  index-eligible conjunct (alias-stripped); everything else residual. `LEFT` pushes only preserved-(left-)side
  conjuncts.
- **D4 — A2 retained columns** per side = equi-key(s) ∪ outer `select` ∪ residual `WHERE` ∪ `group`/`having`/`sort`,
  with a retain-all assertion (a dropped column would map to `ValNull` → wrong results, so it must fail loudly).
- **D5 — B1 output shape** = two synthetic columns `[Key, Value]` (single enriched value; multi-column deferred).
- **D6 — B1 store types** intended for `STATE`/`TEMPORAL_STATE`; others fail safely rather than being pre-rejected
  (structural detection doesn't read `stateType`).
- **D7 — B1 effective time** = `now()` for the shipped version (query-time is a documented follow-up).
- **D8 — Strategy seam** — `JoinSearchProvider` branches on structural detection before realising the lookup side:
  `BROADCAST_LOOKUP` when a side is type `"PlanB"` with equi-key `"Key"`, else the streaming hash join.

### Coding standards (acceptance criteria for further work)

- **Readability** — small single-purpose methods; names/structure/comment-density matching the file being edited.
- **Javadoc on every new/changed class and public/package-private method**, stating preconditions (incl. non-null),
  postconditions (incl. return semantics/nullability), and null status. House style: see `AstToSearchRequestMapper`
  / `AlternativeQueryCompilerResolver`.
- **Enforce the contract in code** — `Objects.requireNonNull(x, "x")`, throw `IllegalArgumentException`/a clear
  domain exception for invalid values; assert cheap invariants (e.g. A2 retain-all, mapping-array lengths).
- **Null status via JSpecify** (`org.jspecify.annotations.@Nullable`/`@NonNull`), consistent within a file.
- **Tests are part of "done"** — the differential/parity harness gates any change to push-down or the executor;
  unit-test new helpers, the guardrail caps, and any new strategy. Follow the existing `Test…` style.

### Deferred report items (fine to defer)

Full cost-model adapters (E22) and cost-based planning beyond a selection seam, shuffle/partitioned hash join
(D2), key-co-located ingest (D3), denormalisation/materialised views (B2/B3), late materialisation (A5), and the
approximate/top-K family (F). None block current functionality; several (E22, D2) become worthwhile only once the
runtime-sizing seam proves insufficient in practice.
