# 12. Future work

**Status:** Roadmap. Nothing on this page exists today.
**Audience:** stakeholders and developers.
**Scope:** what remains, in rough priority order, with what each item actually buys and what it would cost.
Canonical for the roadmap.
**Companion documents:** `docs/query-optimiser-joins-future.md` is the longer plain-English case for the join
items; `docs/join-scalability-implementation-plan.md` records the reasoning behind the ones already deferred.

---

## The honest summary

Two categories, and they need different things.

**The compiler** is essentially finished for its stated scope. Single-source output is at parity, the four rewrite
rules that were designed and are buildable are built, and the two remaining designed rules each need a new port
rather than new thinking. What the compiler lacks is *evidence*, not features — see
[README.md](README.md#what-would-make-it-ready).

**The join** is a working, memory-safe, single-node engine. Everything below it in the priority order is about
making it *faster* or *bigger*, not making it correct. The one thing that would change its character rather than
its ceiling is cluster-parallel execution.

---

## Evidence, not code

These are first because they are cheap and because nothing else should be sequenced ahead of them.

### Soak in `SHADOW`

Free, zero-risk, and never done. Every day an environment runs in `SHADOW` is a day of real-traffic evidence about
whether the two compilers agree. **This is the single highest-value item on this page and it is not an engineering
task.**

### Run the join live, end to end

Live testing has confirmed a join gets past compile, start and execute, and found three bugs doing it — a Guice
startup cycle, a null query key on a side's sub-request, and a placeholder time filter one row-key shape rejects.
All are fixed. What has not been recorded since is a pass confirming the **rows are right**, across two different
datasource providers, on a live deployment. See [14-testing.md](14-testing.md#test-d--joins).

### Characterise concurrent join load

A join holds a request thread for its whole duration and produces no incremental results. Nobody has measured what
that costs under realistic concurrency. The number that matters is the join duration multiplied by the request
thread pool.

---

## Cost model

### Real cost adapters

**What:** replace the stubbed index-shard and state-store cost signals with real ones — summing shard document
counts over the pruned partition range, reading state-store key counts.

**Buys:** `EXPLAIN` starts reporting measured numbers instead of a zero-confidence fallback, and the pre-run
warning becomes capable of firing. Today both are wired end to end and effectively inert.

**Cost:** each adapter must live *inside* the index and Plan B modules — putting it in the query modules would
close a dependency cycle — and be bound in place of the stub. Mechanically small, but it crosses module ownership
boundaries, which is why it has not happened.

### Actual-vs-estimated correlation

**What:** record how long a query really took and correlate it with the estimate logged at compile time.

**Buys:** the feedback loop the cost model needs. Without it there is no way to calibrate anything, and the
selectivity and throughput constants stay guesses forever.

**Cost:** needs a completion-time hook in the shared, engine-agnostic result-store manager and a way to correlate
back to the compile-time estimate given a query key. A real change to core search infrastructure, not a same-day
addition.

### Calibration

**What:** tune the selectivity multipliers (0.01 / 0.1 / 1.0) and the throughput constant (1,000 rows/ms) against
observed outcomes.

**Buys:** estimates worth acting on. Gated entirely on the item above.

### Distinct-key statistics

**What:** real per-field distinct-key counts, so a join between two non-keyed sides can estimate cardinality
rather than falling back to the full cross-product.

**Cost:** a new cost port, plus whatever collects the statistics.

### Cluster-size modelling

**What:** divide estimated durations by node parallelism.

**Cost:** a new port. Deliberately not invented so far, because a cluster-size number with no live signal behind it
would make the estimate look calibrated when it is not.

---

## Joins

Roughly in value-for-effort order. The first two change the ceiling; the third changes the character.

### Semi-join reduction

**What:** compute the smaller side's set of matching keys first, then tell the larger side's scan "only bring back
rows whose key is possibly in this set" — filtering at the source without the user writing any extra `where`.

**Buys:** this is the lever for the common unfiltered case — "today's events joined to yesterday's active users" —
where the only correlation is the join key itself, so predicate push-down has nothing to push. How much it saves
depends entirely on how much the two sides' key sets overlap: possibly modest, possibly dramatic. Unlike
push-down, it applies automatically to a large class of real queries.

**Cost, and why it is deferred with a reason rather than merely unscheduled:** Stroom's expression model can only
carry a value set as a space-delimited `in` string — unsafe for arbitrary keys, because there is no escaping — or
as a *persisted* dictionary document. And the pushed predicate is evaluated by each side's own provider, with
different `in` and analysed-versus-keyword semantics. A post-scan filter would save no scan I/O and defeat the
purpose. Doing it correctly needs its own design pass — a keyword-field guard, or a small expression-model
extension — not a bolt-on.

### Cluster-parallel (broadcast) join

**What:** replicate one small-enough side to every node, so each node joins it locally against its own slice of
the larger side, all at once. This is the first option that uses the cluster fan-out ordinary search already has
and joins never have.

**Buys:** the first genuine parallel throughput, rather than a higher ceiling. If a join currently takes one
core's worth of time, spreading it across ten nodes could realistically approach a tenth of the time for the heavy
scanning and matching — coordination and final assembly do not speed up as cleanly, so the whole query will not be
ten times faster, but the dominant cost usually will be.

**Cost:** the highest on this page. The join path has never been wired into the distributed-search machinery.

### Cost-driven strategy selection

**What:** actually consult the cost model at execution time, rather than choosing structurally.

**Buys:** not speed — **avoiding bad decisions**, such as broadcasting a side that turns out to be far larger than
expected. It only becomes valuable once there is more than one real strategy to choose between, which is why it is
sequenced after broadcast rather than before it. It also depends on the real cost adapters above.

### N-way joins

**What:** extend beyond a single two-source join to left-deep chains.

**Buys:** expressiveness. Today an N-way chain is rejected with a clear message, which is the honest behaviour but
still a wall.

**Cost:** moderate — the binder, the splitter, the projection analyser and the executor all assume exactly two
sides.

### Domain-type discovery and relationships

**What:** two related steps. First, *discover* enrichment candidates by domain type — inferring that a join to a
keyed store is an enrichment — rather than detecting it structurally as today. Second, extend domain types from
"same entity" equi-joins to *relationship-mediated* ones, such as `User.id --OWNS--> Account.number`, routed
through whatever store materialises the relationship.

**Buys:** joins that express intent rather than mechanics. The second half is shared with the temporal graph
initiative.

**Cost:** the first is small; the second is a feature in its own right, needing a relationship type on the domain
type document and routing to match.

### Multi-column and query-time enrichment

**What:** let an enrichment lookup return more than one value, and evaluate it as of the query's own time rather
than `now()`.

**Buys:** removes two of the three things that catch people out about the enrichment fast path
([06-joins.md](06-joins.md#the-enrichment-fast-path)). The single-value limit comes from the lookup interface
itself; the time is a v1 simplification.

### Swapping a `LEFT` join's build side

**What:** let a `LEFT` join build its smaller side, as an `INNER` join already does.

**Buys:** speed on a skewed `LEFT` join. Needs outer-join bookkeeping so unmatched preserved-side rows are still
emitted — which is exactly what keeping probe = left avoids today. Worth doing only if the case proves common.

### Efficiency refinements

Asynchronous side execution, pipelining the two sides against each other, and `limit` early-termination of the
probe scan. Each is a small win on its own; together they remove the "a join does all the work and then discards
most of it" wart.

### Sort-merge and shuffle-hash joins

**What:** the classical techniques for two genuinely enormous sides with no useful filtering or asymmetry —
sorting both sides and merging in one pass, or hash-partitioning both across the cluster so matching keys land on
the same machine.

**Buys:** the extreme case only. Worth investing in **only if real usage shows the options above hitting a genuine
wall** — most real joins have some filtering or size asymmetry that the cheaper options already exploit.

---

## Rewrite rules

Two of the six originally designed rules are not built, each waiting on one thing:

- **Dictionary expansion** — needs a dictionary-lookup port.
- **Time-range extraction as a rule** — needs a time-range predicate slot on the scan node. Time-range extraction
  itself *is* implemented, directly in the compiler rather than as a rule
  ([05-optimisations.md](05-optimisations.md#time-range-pruning)), so this is a refactor for uniformity rather
  than a missing capability.

---

## What the UI still needs

The backend is well ahead of the query editor.

### Surface the `EXPLAIN` plan

The server already returns a full plan tree with per-node estimates. The editor renders none of it — only a
duration warning. A collapsible plan-tree side panel (the pattern already exists for expression trees) would let
users see the chosen access paths, what got pushed where, and the join annotation before running. This was
deliberately deferred, not forgotten.

### A configurable warning threshold

The 10-second threshold is a compiled-in constant. It should be a property.

### A mode indicator

When an environment is in `SHADOW` or `ON`, show it. Ideally, surface shadow divergences to administrators rather
than only in the log.

### Surfacing shadow divergence

Nothing counts divergences, and nothing shows them outside the log. A metric and an admin view would turn a soak
from "grep the logs" into something an operator can actually run.

### Join authoring help

The query editor's structure help does not know `join` exists — no autocomplete for `join … on …`, and no
domain-type-aware suggestion of compatible keys across sources.

### Presenting rejections well

The compiler's rejection messages are deliberately clear and specific. The editor presents them as raw errors.
