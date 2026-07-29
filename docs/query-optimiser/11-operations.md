# 11. Operations

**Status:** Experimental, off by default. See [README.md](README.md#production-readiness).
**Audience:** administrators.
**Scope:** rolling the optimiser out, what to watch, what resources a join consumes, how failures surface, and how
to roll back. Canonical for the operational procedures.
**Companion documents:** [03-configuration.md](03-configuration.md) for the properties themselves,
[14-testing.md](14-testing.md) for an acceptance protocol.

---

## Rolling out

The flag is **per environment**, not global, and reversible with a single property change that takes effect on the
next query. There is no state to migrate, no data to rebuild, and nothing to clean up if you turn it off again.

### The procedure

1. **Confirm you are at `OFF`.** That is the default; nothing has ever shipped a YAML value for it.
2. **Record a baseline.** Run a handful of representative queries — the ones your users actually run — and save
   the results.
3. **Set a non-production environment to `SHADOW`** and leave it under normal query load for long enough to be
   representative. Days, not minutes. `SHADOW` cannot change a result, but it does add a second compile and a
   meta-store aggregation to every query submission, synchronously — so watch the meta store alongside the
   divergence log, and treat any timing you observe in this mode as unrepresentative of both `OFF` and `ON`
   ([03-configuration.md](03-configuration.md#shadow-mode)).
4. **Read the divergence log** (below). Account for every line against
   [04-behaviour-changes.md](04-behaviour-changes.md).
5. **Flip to `ON`.** Re-run the baseline queries and compare.
6. **Expect the `where`/`filter` fix to change results** where a bare `where` mixed index-eligible and
   index-ineligible terms. That is the fix working, not a regression — but tell your users before they find it.
7. **Exercise joins**, if you want them, per [14-testing.md](14-testing.md#test-d--joins).
8. **Promote one environment at a time.**

### Rolling back

Set `stroom.query.optimiser.mode` back to `OFF`. The next query is compiled by legacy. No restart, no cleanup.

The only thing that stops working is anything that *depended* on the optimiser: `join` queries fail to compile,
and `EXPLAIN` reverts to a single node with no estimate. Ordinary queries are unaffected — that is the point of
the parity requirement.

---

## What to watch

### Shadow-mode divergence

Logger: `stroom.query.language.DispatchingQueryCompiler`.

| Level | Line | Meaning |
|---|---|---|
| `INFO` | `Shadow mode: optimising compiler diverged from legacy for query [...]` followed by both compiled forms | The two engines produced different `SearchRequest`s |
| `DEBUG` | `Shadow mode: optimising compiler matched legacy for query [...]` | Agreement. Enable this only if you want positive confirmation |
| `INFO` | `Shadow mode: optimiser estimated <n>ms for query [...]` | The cost estimate. Only logged when some node carries one |
| `DEBUG` | `Shadow mode: optimising compiler failed for query [...]` | The optimiser threw. Swallowed — the legacy result was still served |

That last one is worth raising to `DEBUG` visibility during a soak. A shadow compile failure is invisible at
default log levels by design, and it is exactly the signal you want before flipping to `ON`, because in `ON` the
same query would fail outright.

To classify a divergence, see
[the table in 04-behaviour-changes.md](04-behaviour-changes.md#reading-a-shadow-mode-divergence).

**Know what a soak cannot show you.** Legacy compiles first and its exception is what the caller gets, so a query
legacy *rejects* never reaches the shadow compile. The two parser fixes, and every error-message difference, are
therefore silent during a soak and only appear once the mode is `ON` — where those queries start working. A clean
soak is good evidence about queries that already compile, and no evidence at all about the ones that do not.

### Plan-enhancement failures

Logger: `stroom.query.language.OptimisingQueryCompiler`, at `DEBUG`:

```
Unable to enhance compiled SearchRequest for query [...]: <message>
```

This means the query compiled and ran correctly, but silently got none of the time-range pruning or `where`/
`filter` routing — usually because the binder's stricter validation rejected something the mapper passed through.
Harmless individually; a sustained stream of them means the optimisations you turned this on for are not
happening.

### Join failures

A breached guardrail or a failed join surfaces **in band**, on the result store, as an error the user sees — not
as an HTTP 500 and not as an `OutOfMemoryError`. The messages are listed in
[06-joins.md](06-joins.md#every-rejection-and-what-it-means).

### What is not instrumented

Be aware of the gaps:

- **No metrics.** Nothing counts divergences, join aborts, spill events or estimate accuracy.
- **No admin surface.** Divergences appear only in the log.
- **No actual-vs-estimated correlation.** The estimate is logged; what the query really took is not.

All three are tracked in [12-future-work.md](12-future-work.md).

---

## Resources a join consumes

### Threads

A join is executed **synchronously on the calling request thread**. Its two sides run one after the other, every
joined row is fed, and only then does the result store come back complete. A slow join therefore occupies a
request thread for its whole duration, and there are no incremental results to show in the meantime.

Nobody has characterised what a realistic concurrent join load costs. If you expose joins to users, watch your
request-thread pool.

### Heap

Under default configuration a join **cannot exhaust the heap on any input shape**. That was the explicit goal of
the memory-safety work:

- The build side spills from heap to disk past `maxHeapBuildRows` **or** `maxHeapBuildBytes`, so a large or a
  merely *wide* build side does not sit in memory.
- The probe side is streamed one row at a time and never accumulated.
- A single hot key's fan-out is streamed and bounded by `maxOutputRows` as it is produced.
- The output goes to the ordinary result store, which is off-heap by default.

The one documented exception is in [10-limits.md](10-limits.md#the-one-residual-heap-risk).

### Disk used by a join

A join that spills creates a temporary LMDB environment in a `join_<uuid>` sub-directory of the configured
result-store LMDB directory (`stroom.search.resultStore.lmdb.localDir`), sized by that same configuration's
`maxStoreSize`. It is deleted when the join finishes — on success or on failure — but it must fit while the join
runs.

**Size the result-store volume for concurrent joins, not just one.** Each concurrent join that spills gets its own
directory.

Filling the environment aborts the join with an actionable message telling you to raise `maxStoreSize` or narrow
the join.

### Network and cluster

None beyond what each side's own datasource already does. The join itself runs on one node.

---

## Permissions

Unchanged, and worth stating explicitly because a join reads two things at once:

- Each side's datasource is resolved through the same registry, under the same security context, as any other
  query. A user who cannot see a datasource gets *"Data source \"X\" not found. You may not have permission to use
  it."* — the same message as for a single-source query.
- Each side is executed by its **own** search provider, so that provider's own access controls apply exactly as
  they would if the side were run on its own.
- A join grants no visibility a user did not already have. There is no elevation anywhere in the join path.
- An enrichment lookup that is denied returns an error value, and the join treats that as a **real failure** that
  aborts the search — deliberately, so a permission denial can never be silently downgraded to "no match".

---

## Failure modes and what they look like

| Symptom | Cause | Action |
|---|---|---|
| A query that used to return zero rows now returns rows | The `where`/`filter` fix | Expected. [04-behaviour-changes.md](04-behaviour-changes.md#3-a-bare-where-mixing-eligible-and-ineligible-terms) |
| A query that used to be rejected now runs | A legacy parser defect fixed | Expected. [04-behaviour-changes.md](04-behaviour-changes.md) |
| `join` fails to compile | Mode is not `ON` | `SHADOW` serves legacy, and legacy cannot compile a join |
| `EXPLAIN` returns one node and no numbers | Mode is not `ON` | Expected |
| `EXPLAIN` returns `confidence: 0.0` everywhere | The index and state cost adapters are stubs | Expected today. [08-explain-and-cost.md](08-explain-and-cost.md#how-good-are-the-numbers) |
| The pre-run warning never appears | A client defect: the estimate is only ever set on a leaf `Scan` node, and the editor reads it off the plan root | Expected today, for **every** query and every mode. Not fixed by real cost adapters ([08-explain-and-cost.md](08-explain-and-cost.md#the-pre-run-warning)) |
| *join build side row count* / *join output row count* | A guardrail | Narrow the query, or raise the cap ([03](03-configuration.md#join-guardrails)) |
| *Join build side too large to spill to disk* | The LMDB spill environment filled | Raise `stroom.search.resultStore.lmdb.maxStoreSize`, or narrow the join |
| *Join key too large to spill to disk* | An encoded key over 503 usable bytes | Use a shorter key, or narrow the join so it fits in heap |
| A join result's `StreamId`/`EventId` columns are null | No single source event for a joined row | Expected. Query the side directly if you need navigation |
| A `left join` behaving like an inner join | **Should not happen** — never pushing a predicate onto the null-supplying side is the engine's most carefully guarded rule. Report it |
| Sustained `Unable to enhance compiled SearchRequest` at `DEBUG` | The binder rejects something the mapper accepts | Investigate — the optimisations are silently not happening |

---

## Upgrade and compatibility notes

- **Nothing persists.** The optimiser has no store, no state and no on-disk format. Turning it on and off leaves
  nothing behind.
- **Saved queries and dashboards are unaffected.** They store query text, which both engines compile.
- **The join spill directory is transient.** If a node is killed mid-join, an orphaned `join_<uuid>` directory can
  be left behind in the result-store LMDB directory. It is safe to delete when no join is running.
- **The `SearchRequest` wire format gained a `joinSpec` field** and a sentinel datasource type. A request carrying
  one is only ever produced by the optimiser and only ever consumed by the join provider.
