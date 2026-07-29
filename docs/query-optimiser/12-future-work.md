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

Zero-risk to served results, and never done. Every day an environment runs in `SHADOW` is a day of real-traffic
evidence about whether the two compilers agree. **This is the single highest-value item on this page and it is not
an engineering task.**

It is not, however, *free*: a soak adds a second compile, two whole-request JSON serialisations and a meta-store
aggregation to every query submission, synchronously
([03-configuration.md](03-configuration.md#shadow-mode)). That is affordable for a bounded soak on a
non-production environment — which is what the rollout guidance asks for — and it is why the mode cannot simply be
left on everywhere as free telemetry. See [Shadow-mode overhead](#shadow-mode-overhead) below.

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

## Shadow-mode overhead

`SHADOW` is the mode designed to be safe to run on real traffic, and it is the mode that adds the most work per
query. That is the wrong way round. Nothing here is a correctness problem — the shadow work is genuinely
fail-open and cannot affect a served result — but the cost is what stops `SHADOW` being left on indefinitely as
free divergence telemetry, which is what the design wanted it to be.

The per-query cost is described in [03-configuration.md](03-configuration.md#shadow-mode). In short: a second
full compile, two canonical-JSON serialisations of complete `SearchRequest` objects, a third
parse-bind-rewrite-cost pass, and at least one live meta-store aggregation — all synchronous, on the thread
submitting the search. Everything below is measured against one goal: **make a soak cheap enough that an operator
would leave it on.**

The options are not mutually exclusive. Sampling and the background executor compose well, and together are
probably the whole answer.

### Sample instead of compiling every query

**What:** shadow-compile one query in *N* (a configurable rate; `1` preserves today's behaviour) rather than all of
them.

**Buys:** the largest saving for the least code, and it costs almost nothing in evidence. Divergence detection is a
statistical exercise, not an audit — a systematic divergence shows up within minutes of normal traffic at a 1%
rate, because the divergences worth finding are the ones a *class* of query produces, not a single unlucky one. A
soak's purpose is to answer "do these two compilers agree on the shapes of query my users write", and sampling
answers that.

**Cost:** one config property and a counter in `DispatchingQueryCompiler`. The honest caveat: a rare hand-written
query that diverges may never be sampled, so sampling weakens the "account for every logged line" discipline the
rollout guidance asks for ([11-operations.md](11-operations.md)). Mitigate by sampling at `1` for a short
supervised window and then backing off, rather than picking one rate forever.

### Move the shadow work off the request thread

**What:** submit `shadowCompileAndLog` and `logEstimatedDuration` to a bounded executor and return immediately,
instead of running them inline before `create` returns.

**Buys:** removes shadow work from submission latency entirely, which is the part users feel. Combined with
sampling, `SHADOW` becomes close to free.

**Cost:** more than it first looks, and this is the real reason it was not done. The work needs the `SearchRequest`,
the `ExpressionContext` and the legacy result — so either they must be safe to hand to another thread, or the
compile must be re-entrant with respect to them; that needs checking rather than assuming, and
`AstToSearchRequestMapper` is explicitly single-use per instance. A bounded queue also needs a drop policy
(dropping shadow work under load is correct, but should be counted, not silent), and the log lines lose their
natural ordering against the request that produced them, so they need the query key attaching to stay readable.

### Split the two halves

**What:** treat the divergence check and the duration estimate as separately switchable, rather than as one mode.

**Buys:** the divergence check is the valuable half and the *cheap* half. `logEstimatedDuration` is what reaches
the meta store, and it currently logs one side of a comparison whose other side does not exist —
`DispatchingQueryCompiler`'s own Javadoc records that the "actual duration" half needs a completion-time hook in
`ResultStoreManager` that has not been built ([Actual-vs-estimated correlation](#actual-vs-estimated-correlation)).
So today it pays a live database query per scan to produce a number nothing is compared against. Gating it behind
`DEBUG`, or behind its own property, costs no evidence at all until that correlation work lands.

**Cost:** a property or a log-level check. This is the cheapest item on the page and the one to do first if only
one gets done.

### Make the cost model's meta-store access cheaper

**What:** cache `MetaService.getFeeds()` (it is asked for the whole feed list on every estimate, only to test one
name for membership), and give `getSelectionSummary(...)` results a short TTL cache keyed by feed and time range.

**Buys:** helps `ON` as much as `SHADOW` — the same estimate runs on the serving path once the mode is `ON`, so
this is not shadow-specific tuning. Repeated dashboard refreshes of the same query would hit the cache.

**Cost:** a cache means a staleness window on cardinality estimates. That is almost certainly fine — the estimates
feed plan choice, not results, and are already documented as uncalibrated guesses
([08-explain-and-cost.md](08-explain-and-cost.md#how-good-are-the-numbers)) — but it should be a deliberate
decision with a stated TTL rather than an accident. Note also that a feed-existence check does not need the full
feed list; a targeted "does this feed exist" query would be better than caching the wrong call.

### Measure it before choosing

**What:** record how long the shadow work actually adds, before optimising it.

**Buys:** none of the above is worth sequencing without a number. The overhead is currently *reasoned* rather than
*measured* — this page should not claim a magnitude it has not observed. A timer around the two shadow calls, and
the meta-store query count per submission, would say whether this is a few milliseconds (document it and move on)
or tens (fix it before anyone soaks).

**Cost:** trivial, and it belongs with [Characterise concurrent join load](#characterise-concurrent-join-load) as
the same kind of missing measurement.

---

## Cost model

### Real cost adapters

**What:** replace the stubbed index-shard and state-store cost signals with real ones — summing shard document
counts over the pruned partition range, reading state-store key counts.

**Buys:** `EXPLAIN` starts reporting measured numbers instead of a zero-confidence fallback. Today it is wired end
to end and effectively inert.

**Does not buy:** the pre-run duration warning. That is blocked by a separate client-side defect — the estimate is
only ever set on a leaf `Scan` node and the editor reads the plan root — so real adapters would produce real
numbers that still never reach the comparison. See
[Make the pre-run warning able to fire](#make-the-pre-run-warning-able-to-fire).

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

**Precondition — two defects in `JoinCostModel.chooseAlgorithm` must be fixed first, and landing the real cost
adapters without fixing them is the specific sequence that turns an inert advisory into a live wrong decision:**

1. **Unknown is currently encoded as "smallest".** `CostModel`'s no-signal fallback is `CostEstimate(0, 0, 0, 0.0,
   …)` — *zero* rows — and `chooseAlgorithm` degrades to `NESTED_LOOP` only when **both** sides have zero
   confidence. So when exactly one side is unknown it wins the `rows() <= rows()` comparison and is nominated as
   the build side: the side nobody knows anything about becomes the one held in memory. Unknown must mean
   "assume large", never "smallest".
2. **`chooseAlgorithm` does not know the join type.** Its signature takes two `CostedAccessPath`s and nothing else,
   so it can nominate the *left* side of a `LEFT` join as the build side — which `JoinExecutor`'s own scope note
   says "would need extra bookkeeping to still emit its unmatched rows". `JoinSearchProvider`'s A6 selection
   already refuses to swap for a `LEFT` join for exactly this reason; the cost model has no equivalent guard.

Neither is a live defect today, because nothing on the execution path consults `chooseAlgorithm` — its only caller
is `LogicalPlanExplainer`. Both **do** make today's `EXPLAIN` output wrong (it reports a confident `build side` and
a cardinality of `0` for a side it has no signal for), so they are worth fixing on that basis alone. Tracked as
Tasks 7.1 and 7.2 in
[query-optimiser-implementation-plan.md](../query-optimiser-implementation-plan.md#10-phase-7--cost-model-advisory-hardening-post-phase-6).

**Also note:** the executor today chooses its build side from `DataStore.getSize()` — a *measured* count available
because both sides' sub-searches have already completed — which is strictly better than any estimate. Honouring the
cost model only pays off if the choice is made *before* materialising both sides, which is a reshaping of
`JoinSearchProvider`, not a matter of reading `JoinPlan.buildSide()`. See Task 7.3.

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

### Make the pre-run warning able to fire

**What:** the slow-query warning is dead code. `LogicalPlanExplainer` sets `estimatedDurationMs` only on a leaf
`Scan` node; `QueryModel.estimateQuery` reads it off the plan **root**, which is at least a `Project` for any query
with a `select`. The tested value is therefore always `null`
([08-explain-and-cost.md](08-explain-and-cost.md#the-pre-run-warning)).

**Buys:** the feature, at all. Note this is *not* waiting on [real cost adapters](#real-cost-adapters) — landing
those would put real numbers on the `Scan` and change nothing here.

**Cost:** small, but pick the right end deliberately. Walking the tree client-side is the smaller change and the
server already contains the exact function (`DispatchingQueryCompiler.findEstimatedDurationMs`) — though "first
non-null descendant" is only correct while a plan has at most one costed scan, which stops being true with joins.
Rolling estimates up in `wrap`/`explainJoin` so each node reports the cost of its whole subtree is the more honest
model, makes the whole plan tree meaningful for the panel below, and is what a reader of `ExplainPlan` would
already assume — but it needs a defined rule for combining children (sum for a join, max for a pass-through) and
for combining their confidences.

### A configurable warning threshold

The 10-second threshold is a compiled-in constant. It should be a property. Note it currently has nothing to
compare against — see above.

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
