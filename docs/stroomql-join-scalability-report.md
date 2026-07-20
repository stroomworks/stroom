# Scaling StroomQL joins — a performance & scalability options report

*Audience: engineers and architects deciding where to invest in the StroomQL join engine.*
*Scale assumption: a typical datasource holds ~**100,000,000,000 (10¹¹) records**, so any join whose inputs are
not aggressively reduced first is, in practice, unbounded.*

---

## 1. Executive summary

The join engine works and is **correct**, but it was built as a single-node, in-memory proof of correctness, not
for scale. Today a join:

- runs **both sides to completion as `select *` sub-queries**, then **copies every row of both sides onto the JVM
  heap** (`ArrayList<Val[]>`), builds an on-heap `HashMap` over the **entire right side**, and accumulates all
  output rows on-heap;
- does **no per-side filter push-down** — each side is scanned and returned in full, then the `WHERE` is applied
  *after* the join;
- runs **synchronously on one node**, ignoring the cluster fan-out, shard parallelism, and disk-backed result
  stores that ordinary single-source searches already use;
- **hard-codes `HASH_JOIN`** and never consults the cost model, which is in any case blind for index/state
  sources (its cost adapters are stubs).

At 10¹¹ rows this OOMs long before completion: a single side does not fit in heap, let alone two plus a hash
table plus the output. **No single change fixes this** — scaling joins is a portfolio decision across four levers:
*reduce the inputs*, *avoid the join*, *distribute the work*, and *make each node degrade gracefully (spill)*.
The rest of this report lays out every option we found, grouped by lever, with trade-offs and a suggested
sequencing.

The single most important framing: **at 10¹¹ rows the only joins that can ever be cheap are ones where at least
one side is tiny after filtering, or one side is a keyed lookup.** Most of the high-value options below are about
guaranteeing that condition or exploiting it.

---

## 2. Where the time and memory actually go (current state)

| Stage | What happens now | Why it doesn't scale |
|---|---|---|
| Compile | Single-join only; both sides must be plain scans; INNER/LEFT only. Outer `WHERE` kept whole, **not** pushed to sides. | Sides fetch everything; filtering happens too late. |
| Realise sides | Each side run as a full `select *` sub-query, **blocked to completion**, then **every row copied into on-heap `ArrayList<Val[]>`**. | Both sides fully in heap → impossible at 10¹¹. |
| Combine | Hard-coded `HASH_JOIN`: `HashMap` over the **whole right side**, probe with left; output to on-heap `ArrayList`. **No streaming, no spill, no caps.** | Hash table + output both unbounded on-heap. |
| Post-process | Outer `WHERE`/select/group/sort/limit applied **after** the join via coprocessors. | Work is done on the full cross-matched set, not a reduced one. |
| Distribution | **Single node, synchronous.** Does not use `FederatedSearchExecutor` / per-node shard fan-out / `LmdbDataStore`. | No horizontal scaling; one JVM's heap is the ceiling. |
| Costing | `CostModel`/`JoinCostModel` exist but are **only used by `EXPLAIN`**; index/state cost adapters are NoOp stubs. | Planner can't pick a good plan; cardinality defaults to the saturated cross-product. |

**The good news:** the machinery a scalable join needs *already exists for single-source search* — async
cluster fan-out with per-node shard assignment and time-partition pruning, early termination, coprocessors, and
an **off-heap, disk-backed LMDB result store**. The join path simply bypasses all of it. Much of the work below
is *connecting the join to infrastructure Stroom already has*, not inventing new infrastructure.

---

## 3. Lever A — Reduce the inputs before the join (highest ROI)

At 10¹¹ rows, shrinking each side is worth more than any clever algorithm. These are mostly "make the scans do
less" changes.

1. **Per-side predicate push-down.** Split the outer `WHERE` and push each side's own terms into that side's
   sub-query so filtering happens *in the index scan*, not after the join. This is the single biggest win and is
   currently explicitly *not* done (the compiler passes a `null` filter to each side). A selective term
   (`a.StreamId = 1`) can turn a 10¹¹-row scan into thousands of rows.
2. **Projection pruning.** Sides are fetched as `select *`; instead fetch only the join key(s) + columns the outer
   `select`/`WHERE` actually reference. Less I/O, less heap, smaller hash entries.
3. **Time-partition pruning on each side.** Lucene indexes are partitioned by time (DAY/WEEK/MONTH/YEAR). Ensure a
   time constraint on a side prunes shards *before* scanning (single-source search already does this via
   `NodeSearchTaskCreator`; the join sides must inherit it). A one-month window on a 10-year index is a ~120×
   reduction for free.
4. **Semi-join / bloom-filter reduction (sideways information passing).** Compute the set (or a bloom filter) of
   join-key values from the *smaller/more-selective* side, then push that set as an `IN`/bloom predicate into the
   *large* side's scan so it only returns rows that can possibly match. Classic and hugely effective when one side
   is selective; converts a blind large scan into a targeted one.
5. **Key-only first pass (late materialisation).** Join on `(key, rowRef)` pairs first, then fetch full rows only
   for the rows that survive the join + outer `WHERE`. Keeps the expensive wide columns out of the join entirely.
6. **Selectivity-aware side ordering.** Always build/broadcast the side that is *smaller after filtering*, not a
   fixed side. Requires cost signal (Lever E).

---

## 4. Lever B — Avoid the general join (turn it into something cheaper)

Often the best join is no join. These reshape the problem so the 10¹¹ side is never fully joined.

7. **Enrichment join via keyed lookup (`BROADCAST_LOOKUP`).** When one side is a keyed **Plan B / State** store,
   don't materialise it — probe it once per row of the streaming side (a point lookup). This is the *designed*
   fast path (`JoinAlgorithm.BROADCAST_LOOKUP`, `StateLookup`, `JoinCostModel` already selects it) but it has **no
   executor** (`JoinExecutor` throws `UnsupportedOperationException`) and no live cost signal. Implementing a
   `StateFetcher`-based streaming lookup operator is arguably the highest-value single feature: it makes the
   overwhelmingly common "big events ⋈ small reference data" case scale to 10¹¹ with bounded memory.
8. **Denormalise at ingest (reference-data decoration).** For stable dimensions (device → owner, user → org), fold
   the looked-up attributes into events **at index time** using Stroom's existing reference-data pipeline, so the
   query needs no join at all. Trades storage + ingest cost for query-time scalability; unbeatable when the
   dimension is slow-moving.
9. **Materialised join views / pre-aggregation.** Precompute and store the join (or a rolled-up form of it) on a
   schedule or continuously (analytic rules), so interactive queries read a much smaller derived datasource.
10. **Pre-join at write time via a shared surrogate key.** If two feeds are known to be joined, emit a common key
    and co-locate at ingest so the "join" becomes a same-partition merge (see Lever D, co-location).
11. **Restrict join semantics to the scalable cases and reject the rest early.** Explicitly support (a)
    enrichment lookups and (b) small-side broadcast, and give a clear "materialise a smaller side first" error for
    big⋈big without a reducing predicate — rather than attempting an impossible plan. Honest limits beat OOM.

---

## 5. Lever C — Make each node degrade gracefully (streaming + spill)

For the work that *does* land on a node, remove the on-heap ceilings.

12. **Spill-to-disk (grace / hybrid) hash join.** Replace the on-heap `HashMap`/`ArrayList` with a disk-backed
    build side. Stroom already has an off-heap, disk-backed **`LmdbDataStore`** used by ordinary searches — build
    the hash/lookup side in LMDB (or partition both sides into disk buckets and join bucket-by-bucket, i.e. grace
    hash join). Converts "OOM" into "slower but finishes."
13. **Streaming / pipelined execution.** Don't block both sides to completion and copy into lists. Stream the
    probe side and emit joined rows incrementally into the coprocessor/`ResultStore`, so memory is bounded by the
    build side (or a bucket) rather than the whole input, and `LIMIT`/early-termination can stop work early.
14. **Sort-merge join.** If both sides can be produced sorted on the join key (e.g. key-ordered index reads or an
    external merge sort backed by LMDB), merge-join them in a single streaming pass with near-constant memory.
    Better than hash join when both sides are huge and neither fits, and it composes with `ORDER BY` on the key.
15. **Memory guardrails & backpressure.** Add row/byte/time caps and a clear "join too large — add a filter"
    error, plus spill thresholds. Today there are *no* guardrails anywhere in the join path; even before full
    scalability, guardrails turn silent OOMs into actionable messages.
16. **Off-heap / columnar row storage.** Store join rows off-heap (or in a columnar buffer) to cut GC pressure and
    per-row object overhead, raising the in-memory ceiling before spill is needed.

---

## 6. Lever D — Distribute the join across the cluster

Single-node is the hard ceiling. Stroom's single-source search is already cluster-parallel; the join should ride
the same rails.

17. **Broadcast (replicated) join for a small side.** When one side is small after filtering, broadcast it to
    every node and join it locally against each node's shards of the big side — fully parallel, no shuffle. The
    common and cheapest distributed shape; pairs naturally with Lever A push-down making one side small.
18. **Partitioned / shuffle hash join for big⋈big.** Hash-partition **both** sides by the join key across nodes so
    matching keys co-locate, then each node joins its partition independently. The standard way to join two huge
    datasets; requires a shuffle/exchange operator Stroom doesn't have yet, but the per-node fan-out
    (`FederatedSearchTaskHandler`) and result-merge layer are a starting substrate.
19. **Co-located (partition-wise) join by key.** If datasources were **hash-partitioned on the join key at ingest**
    (Stroom indexes today partition only by *time*), matching keys already live on the same node and the join
    needs no shuffle at all — the cheapest big⋈big option, paid for at write time.
20. **Exploit time co-partitioning.** When the join key correlates with time (e.g. joining two event streams
    within a time window), the existing time-partition pruning already co-locates candidate rows; a
    partition-wise join over aligned time shards is achievable without new hash partitioning.
21. **Push the join into per-node result assembly.** Run each side's per-node partial search, and perform the
    combine as part of the coordinator's existing coprocessor merge (which is already disk-backed via LMDB),
    rather than a separate single-node post-step.

---

## 7. Lever E — Wire the cost model into planning (make the engine choose)

The scaffolding exists; it's just disconnected.

22. **Implement the cost adapters.** `IndexShardStats` and `StateStoreStats` are NoOp stubs, so index/state scans
    report `confidence = 0.0` and cardinality defaults to the saturated cross-product. Real adapters (index doc
    counts per shard/partition; State store key counts) give the planner sight.
23. **Consult the cost model at execution time.** `JoinSearchProvider` hard-codes `HASH_JOIN` and never calls
    `JoinCostModel.chooseAlgorithm`. Wire the chosen algorithm and **build-side** selection through to execution
    (today even the build-side choice is ignored — hash join always builds the right side).
24. **Distinct-key / cardinality estimation for all sides.** Currently only a keyed State side gets a distinct-key
    estimate; extend to index sides (from index stats) so `estimateCardinality` stops degrading to cross-product.
25. **Adaptive / runtime re-planning.** Start executing, measure the first side's true size, and switch strategy
    (broadcast vs shuffle vs spill) when estimates are wrong — valuable because cost estimates on 10¹¹-row data
    are inherently uncertain.
26. **Selectivity feedback from push-down.** Feed the post-filter row counts (Lever A) back into build-side and
    algorithm choice, rather than costing pre-filter cardinalities.

---

## 8. Lever F — Approximate, bounded, and interactive-friendly options

When exact full results aren't required, bound the cost.

27. **Early termination with `LIMIT`.** Push `LIMIT` into the join so it stops once enough joined rows exist
    (single-source search already supports coprocessor-driven early termination; the synchronous join path can't
    yet).
28. **Top-K / ranked joins.** For "top N by score/time" queries, produce results in key/score order and stop
    early, never materialising the full join.
29. **Sampling / approximate joins.** Offer sampled or approximate (e.g. bloom-based, probabilistic-count) joins
    for exploratory analytics where exactness is negotiable.
30. **Time-boxed / progressive results.** Return best-effort partial results within a time budget with a
    "truncated" indicator, matching Stroom's incremental search UX.

---

## 9. Lever G — Operational & infrastructure levers (no query-engine change)

31. **Vertical headroom + off-heap config** for the coordinator node (more RAM, larger LMDB maps) raises the spill
    threshold.
32. **Dedicated join/search worker pools** so large joins don't starve ingest/indexing.
33. **Result caching / store reuse** for repeated joins (Stroom already has result stores; cache the realised
    small side across queries).
34. **Guidance & templates** — ship example patterns (enrichment via Plan B, filtered small-side joins) and
    document the anti-patterns (big⋈big with no predicate) so users write scalable queries.

---

## 10. Suggested sequencing (biggest impact per unit effort)

**Phase 1 — stop the bleeding & win the common cases (weeks):**
- Per-side **predicate push-down** + **projection pruning** (Lever A #1–2) — turns most real joins scalable.
- **Memory guardrails / clear errors** (#15) — no more silent OOM.
- **Time-partition pruning** inherited by join sides (#3).

**Phase 2 — the enrichment fast path (the highest-value feature):**
- Implement **`BROADCAST_LOOKUP`** streaming lookup against Plan B/State (#7) + the **State cost adapter** (#22),
  and wire the **cost model into execution** (#23). Makes "big events ⋈ small reference data" scale to 10¹¹.

**Phase 3 — graceful single-node scale:**
- **Spill-to-disk hash join via LMDB** + **streaming execution** (#12–13); **semi-join/bloom reduction** (#4).

**Phase 4 — true distribution:**
- **Broadcast join** for small sides (#17), then **partitioned/shuffle** (#18) and, if warranted,
  **key-partitioned ingest for co-located joins** (#19).

**Cross-cutting:** implement the **index cost adapter** (#22) and **adaptive re-planning** (#25) as the cost model
matures; keep **denormalisation-at-ingest** (#8) on the table as the pragmatic escape hatch for slow-moving
dimensions.

---

## 11. Trade-off cheat sheet

| Option | Scales big⋈big? | Memory bound | Effort | Notes |
|---|---|---|---|---|
| Predicate push-down (#1) | Only if selective | Much lower | Low | Do first; prerequisite for everything |
| Enrichment `BROADCAST_LOOKUP` (#7) | Yes (one side keyed) | Bounded | Medium | Highest value; needs executor + State stats |
| Broadcast join (#17) | Yes (one side small) | Small side only | Medium | Needs cluster wiring |
| Spill hash join (#12) | Yes (slow) | Disk-bound | Medium | Reuse LMDB; removes OOM |
| Sort-merge join (#14) | Yes | Near-constant | Medium-High | Needs sorted inputs / external sort |
| Shuffle hash join (#18) | Yes | Per-partition | High | Needs an exchange operator |
| Co-located by key (#19) | Yes (cheapest) | Per-partition | High | Ingest-time cost; new partitioning scheme |
| Denormalise at ingest (#8) | N/A (no join) | N/A | Medium | Storage + ingest cost; best for stable dims |
| Cost model wired in (#22–23) | Enables all | — | Medium | Turns guesses into plans |

---

## 12. Open questions for the humans

- **Which join shapes must be exact vs. may be approximate/bounded?** Decides how much of Lever F applies.
- **How often is a side a keyed reference/State store?** If common, prioritise `BROADCAST_LOOKUP` (#7) above all.
- **Is ingest-time cost acceptable** to buy query-time scale (denormalisation #8, key-partitioned ingest #19)?
- **What's the acceptable latency/interactivity target** for a big join — sub-second (needs broadcast/enrichment)
  or batch-OK (spill/shuffle acceptable)?
- **Is exact-once distributed correctness required** across nodes for LEFT joins (null-padding semantics under a
  shuffle need care)?
