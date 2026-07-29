# 10. Limits

**Status:** Experimental. See [README.md](README.md#production-readiness).
**Audience:** analysts and administrators.
**Scope:** every limit the optimiser and join engine impose, its exact value, what breaching it does, and how to
stay within it. Canonical for the numbers.
**Companion documents:** [03-configuration.md](03-configuration.md) for how to change the configurable ones,
[06-joins.md](06-joins.md) for the behaviour behind them.

---

## Join guardrails

All four are configurable under `stroom.query.join`, read live, and rejected at startup if negative.

| Limit | Default | Applies to | Breach |
|---|---|---|---|
| `maxHeapBuildRows` | 500,000 | Build-side rows held on the heap | **Not an error.** The build side spills to a disk-backed store |
| `maxHeapBuildBytes` | 268,435,456 (256 MiB) | Estimated heap footprint of the build side | **Not an error.** The same spill, on whichever trigger is hit first |
| `maxSideRows` | 10,000,000 | Build-side rows, heap **and** spilled | Aborts: *join build side row count* |
| `maxOutputRows` | 1,000,000 | Joined output rows | Aborts: *join output row count* |

A value of `0` is legal and means what it says — `maxSideRows: 0` or `maxOutputRows: 0` disables joins entirely,
because the first row breaches. There is deliberately no "unbounded" sentinel.

**What each does not cover:**

- **The probe side is not row-capped.** It never accumulates, so it is memory-safe by construction. An enrichment
  join, or a hash join's streamed side, can be arbitrarily large.
- **`maxSideRows` normally bounds only the build side** — but a **Graph DB side is capped regardless of which
  role it plays**, because the graph engine realises its whole traversal result in memory before the join sees it.
- **`maxOutputRows` is checked as each row is produced**, not once at the end, so a single hot key with an
  enormous fan-out aborts at the cap rather than being accumulated first.

**Raising them is safe from a heap perspective.** The build side spills and the probe side streams, so a higher
ceiling trades disk and time for a higher ceiling — with one exception, below.

### The one residual heap risk

A join's *output* is streamed straight to the coprocessors, and with the default
`stroom.search.resultStore.offHeapResults: true` that store is off-heap. If an operator both **disables off-heap
results** and **raises `maxOutputRows` substantially**, a very large join output could pressure heap. That is a
deliberate global-configuration choice, not a join defect — but it is the only documented way to make a join
threaten the heap.

---

## Join spill-store limits

Not configurable on the join itself; they come from the shared result-store LMDB environment.

| Limit | Value | Breach |
|---|---|---|
| Spill environment size | `stroom.search.resultStore.lmdb.maxStoreSize` | *Join build side too large to spill to disk: the LMDB spill store's map size (…) is full. Increase the result-store LMDB maxStoreSize, or add a filter / narrow the join so its build side is smaller.* |
| Encoded join-key size | **511 bytes**, including an 8-byte row-sequence suffix — so 503 usable | *Join key too large to spill to disk … Reduce the join key size, or add a filter so the join fits in memory without spilling.* |

The key limit only bites once the build side has spilled; an in-heap join has no such restriction. In practice it
matters only for a composite key over long text fields.

---

## Query and traversal limits

| Limit | Value | Note |
|---|---|---|
| Joins per query | **1** | An N-way chain is rejected at compile time |
| Sources per join | **2** | Follows from the above |
| Join types | `INNER`, `LEFT` | `RIGHT` and `FULL` are grammar parse errors |
| Join conditions | Equi-keys only | `on a.f = b.g`, optionally several ANDed. No other operator, no expressions |
| Enrichment fast path | **Exactly one** equi-key | A composite key is not representable as a single point lookup, so such a join takes the hash-join path instead |
| Pre-run warning threshold | **10 seconds** | Hard-coded in the query editor, not a property |
| Fuzzer iterations per parity run | 200, fixed seed | See [14-testing.md](14-testing.md#the-parity-suites) |

---

## Language limits

Things the grammar or binder will not accept, or accepts in a way that may surprise you.

| Limit | Detail |
|---|---|
| **`select *` in a join** | Rejected. List columns explicitly |
| **`show` in a join, or in an explained query** | The binder does not support it: *show is not yet supported by the optimising binder*. It compiles fine in an ordinary single-source query |
| **`SKIP`** | Not in StroomQL, and not added |
| **A dotted field name in a join query** | A field reference is split at its **first** dot. If the prefix is a join alias in scope, it binds as `alias.field`, and there is no escape. Avoid an alias that collides with the first segment of a real dotted field name |
| **A field literally named `and`, `or` or `not`** | These are reserved keywords everywhere in the grammar, unlike legacy, where they are keywords only inside `where`/`filter`/`having`. A *function call* named `and(…)` still works — that case is in the parity corpus. A bare *field* with one of those names is an accepted, documented deviation, not exercised by the corpus |
| **An unqualified field present on both sides of a join** | Rejected as ambiguous rather than guessed at: *Ambiguous field 'x' - present on multiple sources (…); qualify with a source alias* |
| **A duplicate source alias** | Rejected |
| **A Cypher join side without `AS` aliases, or returning a bare pattern variable** | Rejected. The `RETURN` list *is* the schema |
| **A Cypher join side without a leading `from "…"`** | Rejected. A join side has no owning document to infer its graph from |

---

## Cost-model limits

Not errors — limits on how much the numbers are worth.

| Limit | Consequence |
|---|---|
| Index shard statistics are a **stub** | Every index-backed scan reports `confidence: 0.0` and a zero estimate |
| State-store statistics are a **stub** | Same, for state-backed scans |
| Meta-store counts match on **feed name** | A datasource whose name is not a feed name gets no signal, even though the meta store could in principle count it |
| Selectivity constants (0.01 / 0.1 / 1.0) are unmeasured | Any filtered estimate is capped at confidence 0.5 |
| Throughput constant (1,000 rows/ms) is unmeasured | Any duration derived from it is capped at confidence 0.5 |
| Cluster parallelism is not modelled | Every duration assumes a single node |
| Distinct-key counts exist only for a state-lookup side | Every other join cardinality is the pessimistic full cross-product |
| Nothing correlates actual with estimated | The calibration loop is not closed |

See [08-explain-and-cost.md](08-explain-and-cost.md#how-good-are-the-numbers).

---

## Execution limits

| Limit | Consequence |
|---|---|
| A join runs on **one node** | No cluster fan-out. The main throughput ceiling |
| A join's sides run **sequentially** | No overlap between the two sub-searches |
| A join **completes before returning** | No incremental results; a request thread is held for the whole duration |
| `limit` does not stop a join's scan early | The work is done and then discarded |
| No semi-join reduction | A join correlated only by its key still scans both sides in full |
| No predicate or projection push-down into a Cypher side | Narrow the traversal in the Cypher text |
| The reserved `StreamId` / `EventId` / annotation columns are null in a join result | Data navigation and annotation do not work from joined rows |
| An enrichment lookup is evaluated as of **now()** | Not as of the query's own time range |
| An enrichment lookup returns **one value** | No multi-column enrichment |

---

## Matching limits

Where two values that "look equal" may not be.

| Case | Behaviour |
|---|---|
| `5` (long) vs `5.0` (double) vs `"5"` (string) | **Match.** Numeric types are canonicalised, and a string of digits renders identically |
| `"5.0"` (string) vs `5` (number) | **Do not match.** A string is never reinterpreted as a number |
| The same instant, spelled differently on two sources | **Do not match.** Dates and durations are not canonicalised. A documented residual |
| `null` vs `null` | **Do not match**, on either side. SQL semantics. A null-keyed left row is still null-padded for a `LEFT` join |
| A composite key with any null component | The whole key is null, so the row never joins |
| A very large integral double, outside long range | Keeps its own text rather than being canonicalised through `long` |
