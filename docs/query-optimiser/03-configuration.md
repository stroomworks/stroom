# 3. Configuration

**Status:** Experimental, off by default. See [README.md](README.md#production-readiness).
**Audience:** administrators.
**Scope:** every configuration property the optimiser and the join engine read, their defaults, and the
recommended rollout. Canonical for property names and default values.
**Companion documents:** [10-limits.md](10-limits.md) for what each guardrail actually bounds,
[11-operations.md](11-operations.md) for monitoring and rollback.

---

## The mode flag

| Property | Type | Default |
|---|---|---|
| `stroom.query.optimiser.mode` | `OFF` \| `SHADOW` \| `ON` | `OFF` |

- **`OFF`** — the legacy compiler compiles and serves every query. The optimiser never runs. Byte-for-byte
  identical to the behaviour before the optimiser existed.
- **`SHADOW`** — legacy still compiles and serves every query; results are identical to `OFF`. The optimiser
  *also* compiles each query, best-effort, purely to log any divergence from legacy and its own estimated
  duration. **Zero risk to served results** — a bug in the optimiser, or in the comparison itself, cannot change
  what a user sees.
- **`ON`** — the optimiser compiles and serves every query. Legacy never runs.

The value is re-read on **every query**, so a change takes effect immediately and can be rolled back the same way.
No restart, no cache to clear.

### Where to set it

Via the Stroom **Properties** admin screen: search for `stroom.query.optimiser.mode` and set the value. This is
the recommended route — it takes effect at once and is trivially reversible.

Or in `config.yml` / `local.yml`:

```yaml
appConfig:
  query:
    optimiser:
      mode: SHADOW
```

No shipped YAML has ever set this property; it has only ever run at its Java-side `OFF` default.

### What each mode enables

| | `OFF` | `SHADOW` | `ON` |
|---|---|---|---|
| Who serves an ordinary query | legacy | legacy | optimiser |
| Legacy parser defects fixed | no | no — and **not logged** (see below) | **yes** |
| Time-range shard pruning from `where` | no | no (logged as a divergence) | **yes** |
| Automatic `where`/`filter` split | no | no (logged as a divergence) | **yes** |
| `join` queries | rejected | rejected | **run** |
| `EXPLAIN` returns a cost estimate | no | no | **yes** |
| Pre-run duration warning can fire | no | no | no — a client defect stops it firing in any mode ([08](08-explain-and-cost.md#the-pre-run-warning)) |
| Divergence logging | no | **yes** | no |

Two entries deserve emphasis. **`SHADOW` does not enable joins** — legacy serves, and legacy cannot compile a
`join`. And **`EXPLAIN` is served by the legacy compiler in both `OFF` and `SHADOW`**, where it returns a single
node naming the datasource and no estimate at all.

### Shadow mode

In `SHADOW`, for each `create` call, the dispatcher:

1. Compiles with legacy and keeps that result — it is what gets returned and served.
2. Compiles the same query with the optimiser, inside a `try`/`catch` that swallows every failure to `DEBUG`.
3. Serialises both to canonical JSON and compares them.
   - Identical → logged at `DEBUG`: `Shadow mode: optimising compiler matched legacy for query [...]`
   - Different → logged at **`INFO`**: `Shadow mode: optimising compiler diverged from legacy for query [...]`,
     followed by both compiled forms.
4. Asks the optimiser for an `EXPLAIN` estimate and, if any node carries an estimated duration, logs it at
   `INFO`: `Shadow mode: optimiser estimated <n>ms for query [...]`.

All of it is under the logger `stroom.query.language.DispatchingQueryCompiler`.

Every divergence you see should trace to a case in [04-behaviour-changes.md](04-behaviour-changes.md). Anything
else is a finding worth investigating before going further.

**What a soak costs.** `SHADOW` is zero risk to *results*, but it is not free. Steps 2–4 above all run
**synchronously, on the thread submitting the search**, and step 4 is the expensive one: the `EXPLAIN` estimate
drives the cost model, and `MetaStatsAdapter` answers it with `MetaService.getFeeds()` plus a
`getSelectionSummary(...)` — a **live aggregation query against the meta store** — for each scan in the plan. So
every query submission in `SHADOW` costs, on top of the legacy compile it would have paid anyway: a second full
compile, two canonical-JSON serialisations of complete `SearchRequest` objects (both are always produced — the
string comparison *is* the divergence check), a third parse-bind-rewrite-cost pass, and at least one meta-store
aggregation.

None of that can change a result, and every step is individually cheap. It is the multiplication that matters: on a
busy cluster with auto-refreshing dashboards, this is measurable extra submission latency and extra load on the
same meta store the searches themselves depend on. Two practical consequences:

- **Do not read performance numbers off a `SHADOW` environment.** Query submission is slower there than it will be
  in either `OFF` or `ON`. Take timing baselines in `OFF`, and re-take them in `ON`.
- **Watch the meta store** for the duration of the soak, not just the divergence log.

Making a soak cheap enough to leave on indefinitely is tracked in
[12-future-work.md](12-future-work.md#shadow-mode-overhead); the options are sampling and moving the work off the
request thread, neither of which is built today.

**What a soak cannot tell you.** Legacy compiles *first*, and if it throws, that exception is what the caller
gets — the optimiser is never asked. So a query legacy **rejects** and the optimiser would accept never produces
a divergence line. The two parser fixes, and every difference in error-message text, are therefore invisible
during a soak and appear only once the mode is `ON`. Plan for that: the queries that start working are the ones
you will have no shadow evidence about.

## Join guardrails

Four properties under `stroom.query.join`. They exist so that a join either completes or aborts with a clear
message — never an `OutOfMemoryError`, never an opaque 500.

| Property | Default | What it bounds |
|---|---|---|
| `stroom.query.join.maxHeapBuildRows` | 500,000 | Rows the build side keeps on the heap before spilling to a disk-backed store |
| `stroom.query.join.maxHeapBuildBytes` | 268,435,456 (256 MiB) | Approximate heap footprint the build side may reach before spilling — whichever trigger is hit first |
| `stroom.query.join.maxSideRows` | 10,000,000 | Absolute ceiling on build-side rows, heap **and** spilled. Breach aborts the search |
| `stroom.query.join.maxOutputRows` | 1,000,000 | Ceiling on joined output rows. Breach aborts the search |

```yaml
appConfig:
  query:
    join:
      maxHeapBuildRows: 500000
      maxHeapBuildBytes: 268435456
      maxSideRows: 10000000
      maxOutputRows: 1000000
```

Like the mode flag, these are read live — the next join honours a change with no restart.

Notes that matter:

- A **negative** value is rejected at construction with a clear message. A value of **`0`** is accepted and means
  what it says: `maxSideRows: 0` or `maxOutputRows: 0` disables joins entirely, because the first row breaches.
  There is deliberately no "unbounded" sentinel.
- `maxHeapBuildRows` should be less than or equal to `maxSideRows` to have any effect.
- **Raising the caps is safe from a heap perspective.** The build side spills and the probe side streams, so a
  higher ceiling trades disk and time, not memory. See [10-limits.md](10-limits.md#join-guardrails) for the one
  documented exception.

## Related properties you may need

These are not the optimiser's own, but a join depends on them.

| Property | Why it matters to a join |
|---|---|
| `stroom.search.resultStore.lmdb.localDir` | Where a join's build side spills. A join creates a temporary `join_<uuid>` sub-directory here and deletes it when it finishes |
| `stroom.search.resultStore.lmdb.maxStoreSize` | The size of that spill environment. Filling it aborts the join with *"Join build side too large to spill to disk … Increase the result-store LMDB maxStoreSize, or add a filter"* |
| `stroom.search.resultStore.offHeapResults` | Default `true`, and should stay that way. It is what keeps a large join *output* off the heap ([10-limits.md](10-limits.md#the-one-residual-heap-risk)) |

## Recommended rollout

1. **Start at `OFF`.** That is the default; you do not need to do anything.
2. **Move a non-production environment to `SHADOW`** and run your normal query workload against it for long
   enough to be representative. Watch for `Shadow mode: optimising compiler diverged` lines.
3. **Account for every divergence.** Each should match a case in
   [04-behaviour-changes.md](04-behaviour-changes.md). Anything undocumented is a bug to raise before proceeding.
4. **Flip that environment to `ON`.** Re-run a handful of representative queries and compare against what you
   recorded in step 2. Expect the `where`/`filter` fix to change results where it applies — that is the fix
   working, not a regression.
5. **Exercise joins**, if you want them, following [14-testing.md](14-testing.md#test-d--joins).
6. **Promote one environment at a time.** The flag is per environment, not global.

Rolling back is one property change, and takes effect on the next query.

## What is not configurable

- **The pre-run warning threshold** is a hard-coded 10 seconds in the query editor. Making it a property is
  tracked in [12-future-work.md](12-future-work.md#a-configurable-warning-threshold).
- **The cost model's constants** — selectivity multipliers, fallback throughput, the state-lookup fixed cost — are
  compiled-in. They are documented defaults, and exposing them before they have been calibrated against real
  numbers would be a false promise ([08-explain-and-cost.md](08-explain-and-cost.md#how-good-are-the-numbers)).
- **Which rewrite rules run.** The pipeline is a fixed, ordered sequence of four rules
  ([05-optimisations.md](05-optimisations.md)).
- **The join execution strategy.** It is chosen structurally from the shape of the query, not from configuration
  or from the cost model ([06-joins.md](06-joins.md#execution-two-strategies)).
