# StroomQL query optimiser — documentation index

**Status:** Experimental — **off by default**, and not yet recommended for production traffic. See
[Production readiness](#production-readiness) below.
**Audience:** everyone.
**Scope:** the index for the query optimiser user documentation set. Canonical for the reading paths and the
production-readiness assessment; every other fact lives in one of the files listed below.
**Companion documents:** all of `docs/query-optimiser/`. This set is self-contained; the design proposals,
implementation plans and review reports written during development live outside it (see
[Relationship to the engineering documents](#relationship-to-the-engineering-documents)).

*Facts verified on 2026-07-29 against branch `sw-query-optimiser`.*

---

Stroom's **query optimiser** is a second, grammar-driven compiler for StroomQL. It parses a query with a real
ANTLR lexer and parser, binds it into a typed logical plan, rewrites that plan, costs it, and emits the same
`SearchRequest` the existing search infrastructure has always executed. It is selected by a single configuration
property and can be turned off again at any time.

Three things come with it that the legacy compiler cannot do:

- **`join`** — correlate two datasources, or an ordinary datasource and a Cypher sub-query against a Graph DB, in
  one StroomQL query.
- **`EXPLAIN`** — ask the server how a query would be compiled, and what it would cost, without running it.
- **Plan-derived pruning** — a time bound written in `where` prunes index shards; a predicate an index cannot
  evaluate is routed to extraction-time filtering instead of silently zeroing the result set.

It also fixes three defects in the legacy parser. Everything else is held to producing a byte-identical
`SearchRequest`.

---

## Production readiness

**The optimiser is not ready to serve production query traffic.** It is ready for evaluation, for shadow-mode
soak on real traffic (which carries no risk to served results at all), and for the `join` capability on
non-critical analysis where you can check the answers.

The reason is not a list of known defects. Single-source compilation is held to byte-for-byte parity with the
legacy compiler by a differential test over the whole legacy corpus plus a seeded fuzzer, and the join engine has
been through a dedicated memory-safety programme — it cannot exhaust heap on any input shape under default
configuration. What stands in the way is **verification breadth**: the flag has never been left on in anger, the
join has been exercised live only in narrow cases, and the cost model behind `EXPLAIN` is built out of documented
placeholder constants rather than measurements.

Read this section before anything else in this set:

1. [Why the answer is still no](#why-the-answer-is-still-no) — what has not been verified.
2. [What would make it ready](#what-would-make-it-ready) — the specific steps.
3. [Before you turn it on](#before-you-turn-it-on) — obligations that will surprise you if missed.
4. [Open issues](#open-issues) — what is still wrong or missing, and what it costs you.
5. [Accepted limitations](#accepted-limitations) — deliberate choices that will not change soon.

### Why the answer is still no

| Gap | Why it matters |
|---|---|
| **No environment has soaked in `SHADOW` mode** | `SHADOW` is the whole point of the design: legacy still serves every result, and the optimiser compiles alongside it purely to log divergence. It is evidence nobody has collected, at no risk to served results — though not free, since it adds a second compile and a meta-store aggregation per query submission. **This is the largest single gap**, and the cheapest to close ([03-configuration.md](03-configuration.md#shadow-mode)) |
| **The join has been run live only in narrow cases** | Live testing on a real instance found three bugs no in-module test could catch — a Guice startup cycle, a null query key on a side's sub-request, and a placeholder time filter one row-key shape rejects. All fixed. What has *not* been recorded since is a full pass confirming the *returned rows* are correct end-to-end across two different providers on a live deployment ([14-testing.md](14-testing.md#test-d--joins)) |
| **The cost model is uncalibrated, and two of its three inputs are stubs** | Selectivity (0.01 / 0.1 / 1.0) and throughput (1,000 rows/ms) are documented guesses. The index-shard and state-store cost adapters return nothing at all, so almost every real `EXPLAIN` reports `confidence: 0.0`. The numbers are honest about being unmeasured, but they are not yet useful ([08-explain-and-cost.md](08-explain-and-cost.md#how-good-are-the-numbers)) |
| **The pre-run duration warning cannot fire** | Not the stubbed adapters, as was previously recorded here: `estimatedDurationMs` is set only on a leaf `Scan` node, and the editor reads it off the plan **root**, which every `select` puts a `Project` above. So the value it tests is always `null`. A client-side defect that real cost adapters would not fix ([08-explain-and-cost.md](08-explain-and-cost.md#the-pre-run-warning)) |
| **Nothing measures actual against estimated** | `SHADOW` logs the estimate. Correlating it with what the query really took needs a completion-time hook in shared search infrastructure that does not exist yet, so the calibration loop the cost model needs is not closed |
| **The join runs on a single node** | Ordinary Stroom search fans out across the cluster; the join never has. One node scans and probes everything. This is a throughput ceiling, not a correctness problem, and it has not been measured against a realistic workload ([06-joins.md](06-joins.md#what-a-join-does-not-do-yet)) |

### What would make it ready

In order. Steps 1–3 are evidence-gathering rather than code, which is the honest summary of where this stands.

1. **Soak a non-production environment in `SHADOW`** under a normal query workload, and account for every logged
   divergence against [04-behaviour-changes.md](04-behaviour-changes.md). An undocumented divergence is a bug.
2. **Run [14-testing.md](14-testing.md)'s Test D live**, on a deployment with two different datasource providers,
   and record the actual rows — not just "it no longer throws".
3. **Flip one environment to `ON`** and leave it there long enough to be boring.
4. **Write the real cost adapters** for index shards and state stores, so `EXPLAIN` reports measured numbers
   rather than a zero-confidence fallback ([12-future-work.md](12-future-work.md#real-cost-adapters)).
5. **Close the actual-vs-estimated loop**, then calibrate the selectivity and throughput constants against it.
6. **Measure a join against a realistic workload** before deciding whether cluster-parallel execution is the next
   investment or merely the most interesting one.

#### What it is ready for now

Evaluation; shadow soak on live traffic; `EXPLAIN` as a plan-shape inspector (rather than a cost oracle); and
`join` for analysis you can sanity-check. The optimiser's single-source output is deliberately unremarkable — the
interesting surface is joins, and joins are new.

### Before you turn it on

| Obligation | Why |
|---|---|
| **Go through `SHADOW` first**, per environment | It cannot affect a served result, it is the only mode that compares the two engines on your own queries, and the flag is per environment rather than global. Budget for the per-query overhead it adds, and do not take timing baselines while it is on ([03-configuration.md](03-configuration.md#shadow-mode)) |
| **Expect the `where`/`filter` fix to change results** | A bare `where` mixing an index-eligible term with one the index cannot evaluate returns **zero rows** under legacy and the correct rows under the optimiser. This is the fix working, and it is the one change users will notice ([04-behaviour-changes.md](04-behaviour-changes.md#3-a-bare-where-mixing-eligible-and-ineligible-terms)) |
| **Know that `join` needs `mode: ON`** | `SHADOW` serves legacy results, and legacy cannot compile a `join`. There is no way to try joins without also serving every other query through the optimiser |
| **Size `stroom.search.resultStore`'s LMDB directory for join spill** | A join's build side spills into a temporary sub-directory of the ordinary result-store LMDB directory. It is deleted when the join finishes, but it must fit while the join runs ([11-operations.md](11-operations.md#disk-used-by-a-join)) |
| **Leave `stroom.search.resultStore.offHeapResults` at its default** if you raise `stroom.query.join.maxOutputRows` | Off-heap result storage is what keeps a very large join *output* off the heap. Disabling it *and* raising the output cap is the one documented way to make a join pressure heap ([10-limits.md](10-limits.md#the-one-residual-heap-risk)) |
| **Do not expect data navigation from join results** | The reserved `StreamId`/`EventId`/annotation columns Stroom adds to an ungrouped table have no source in a joined row, so they come back null. Opening the underlying event from a join result does not work ([06-joins.md](06-joins.md#what-you-lose-in-a-join-result)) |

### Open issues

#### Correctness surprises

| Issue | Consequence |
|---|---|
| **Enrichment joins expose the looked-up value as a column called `Value`** | A join to a keyed Plan B / State store contributes exactly two synthetic columns, `Key` and `Value` — not the store's own field names. `select b.Name` finds nothing; `select b.Value` is what works. One value per lookup; there is no multi-column enrichment ([06-joins.md](06-joins.md#the-enrichment-fast-path)) |
| **An enrichment lookup is evaluated as of "now"** | Not as of the query's own time range. For a temporal state store that is a different answer, and nothing warns ([06-joins.md](06-joins.md#the-enrichment-fast-path)) |
| **Join keys canonicalise numbers but not dates** | `5`, `5.0` and `"5"` all match across sources. Two sources spelling the same instant differently do **not** match, and the string `"5.0"` does not match the number `5` ([06-joins.md](06-joins.md#how-keys-are-matched)) |
| **In a join query a dotted field name is split at its first dot** | If the prefix happens to be a join alias in scope, the reference binds as `alias.field`. There is no escape. Avoid a source alias that collides with the first segment of a real dotted field name ([10-limits.md](10-limits.md#language-limits)) |
| **`EXPLAIN`'s named join algorithm is not what executes** | The plan tree may say `HASH_JOIN` or `NESTED_LOOP` with a build side; execution chooses structurally and ignores it. Advisory only, and never a correctness difference — but reading the plan as a statement of what ran is wrong ([08-explain-and-cost.md](08-explain-and-cost.md#what-the-plan-does-not-tell-you)) |

#### Operational

| Issue | Consequence |
|---|---|
| **A join blocks its request thread until it has finished completely** | Sides run one after the other and every joined row is fed before the result store is returned. There are no incremental results and `limit` does not stop the scan early — the work is done and then discarded ([06-joins.md](06-joins.md#what-a-join-does-not-do-yet)) |
| **Join guardrails abort work in flight** — 10,000,000 build-side rows, 1,000,000 output rows | A legitimate but very broad join fails with a clear message rather than running slowly. Both are configurable, and raising them is safe from a heap perspective — it trades disk and time for a higher ceiling ([10-limits.md](10-limits.md#join-guardrails)) |
| **`EXPLAIN` is served by the legacy compiler unless `mode: ON`** | In `OFF` and `SHADOW` it returns a single node naming the datasource and *no estimate at all*. An earlier engineering note claiming the endpoint is mode-independent was wrong ([08-explain-and-cost.md](08-explain-and-cost.md)) |
| **Divergences are only visible in the log** | `SHADOW` writes them at `INFO` under `DispatchingQueryCompiler`. Nothing surfaces them to an administrator, and there is no metric ([12-future-work.md](12-future-work.md#surfacing-shadow-divergence)) |

#### Expressiveness

The optimiser accepts everything legacy accepts, plus `join`, plus the two parser fixes. Within `join`, the
supported shape is deliberately narrow: **exactly one** join of **exactly two** sources, `INNER` or `LEFT` only
(`RIGHT`/`FULL` are parse errors), equi-keys only (`on a.f = b.g`, optionally several ANDed together), every
column listed explicitly (`select *` is rejected), and each side either a named datasource or a bracketed Cypher
sub-query. `show` is not supported by the binder, so it cannot be used in a join query or explained.

Detail: [06-joins.md](06-joins.md), [10-limits.md](10-limits.md).

### Accepted limitations

Deliberate, and not on anyone's near-term list. They are here because each will surprise someone.

| Limitation | Why it is accepted |
|---|---|
| **A `LEFT` join always builds its right side** | Its preserved (left) side must be the probe side so unmatched rows null-pad inline with no outer-join bookkeeping. An `INNER` join does pick the smaller side. Swapping a `LEFT` join is possible but buys less than the bookkeeping costs |
| **A `LEFT` join never pre-filters its right side** | Pre-filtering the null-supplying side changes which left rows survive as unmatched — it would silently turn a `LEFT` join into an `INNER` one. Such a predicate is always evaluated after the join instead. This is the single most correctness-sensitive rule in the join engine |
| **A predicate is never pushed into a Cypher sub-query side** | There is no mechanism to rewrite a Cypher `RETURN` or `WHERE` from StroomQL, and inventing one would be a separate feature. Narrowing a graph side is the analyst's job, in the Cypher text itself |
| **The rewrite rules never guess** | An unknown field, a missing condition set, a predicate whose top-level operator is not `AND` — anything the optimiser cannot resolve with confidence stays exactly where it was. A wrong "leave it alone" changes nothing; a wrong "push it" can zero a result set |
| **Plan enhancement is fail-open** | If binding or rewriting a single-source query fails for any reason, `create` returns the unenhanced, legacy-identical request and logs at `DEBUG`. Turning the optimiser on must never make a query *worse* than it was |

### Where the rest is tracked

Everything above, plus the work nobody has asked for yet, is in [12-future-work.md](12-future-work.md).

---

## The documentation set

| File | What it covers | Audience |
|---|---|---|
| [01-introduction.md](01-introduction.md) | What the optimiser is, what it buys you, when not to use it, glossary | Analysts, evaluators |
| [02-architecture.md](02-architecture.md) | The compile pipeline, the dispatcher, where each piece lives | Analysts, administrators |
| [03-configuration.md](03-configuration.md) | The mode flag, the join guardrails, and how to roll out safely | Administrators |
| [04-behaviour-changes.md](04-behaviour-changes.md) | Every way the optimiser's output differs from legacy, and why each is a fix | Analysts, administrators |
| [05-optimisations.md](05-optimisations.md) | The rewrite rules, time-range pruning and the `where`/`filter` split | Analysts |
| [06-joins.md](06-joins.md) | The `join` clause: syntax, execution, guardrails, and everything it rejects | Analysts |
| [07-domain-types.md](07-domain-types.md) | Semantic join-key validation, and how to set domain types up | Analysts, administrators |
| [08-explain-and-cost.md](08-explain-and-cost.md) | The `EXPLAIN` endpoint, the plan tree, the cost model and how far to trust it | Analysts, evaluators |
| [09-examples.md](09-examples.md) | Worked examples over a small dataset, from setup to result | Analysts |
| [10-limits.md](10-limits.md) | Every limit, its exact value, and how to stay within it | Analysts, administrators |
| [11-operations.md](11-operations.md) | Rollout, monitoring, disk, permissions, failure modes, rollback | Administrators |
| [12-future-work.md](12-future-work.md) | Roadmap, with what each step actually buys | Stakeholders |
| [13-developer-guide.md](13-developer-guide.md) | Code structure, the ports, and how to extend the optimiser | Developers |
| [14-testing.md](14-testing.md) | An acceptance protocol: configuration, cases and expected results | Developers, testers |

## Reading paths

- **Evaluating it** — [01](01-introduction.md) → [04](04-behaviour-changes.md) → [12](12-future-work.md), plus
  the readiness assessment above.
- **Turning it on** — [03](03-configuration.md) → [04](04-behaviour-changes.md) → [11](11-operations.md)
  → [14](14-testing.md).
- **Writing joins** — [06](06-joins.md) → [07](07-domain-types.md) → [09](09-examples.md) → [10](10-limits.md).
- **Making queries faster** — [05](05-optimisations.md) → [08](08-explain-and-cost.md) → [10](10-limits.md).
- **Extending it** — [02](02-architecture.md) → [13](13-developer-guide.md) → [14](14-testing.md).

## Relationship to the engineering documents

The files in `docs/` outside this directory are **engineering records**, not user documentation: design proposals,
implementation plans and survey reports written during development. They remain accurate about intent and design
rationale, but they describe work in progress and are pinned to the date they were written on.

**Where this set and an engineering record disagree, this set is correct.**

The records still present:

| Document | Still useful for |
|---|---|
| `docs/query-optimiser-plan.md` (and `.html`) | The original design rationale for the pipeline and the cost model |
| `docs/query-optimiser-implementation-plan.md` | The per-task build log, decisions and research findings |
| `docs/join-scalability-implementation-plan.md` | The join memory-safety programme: what shipped, in what order, and why |
| `docs/stroomql-join-scalability-report.md` | The original survey of join execution strategies and their risks |
| `docs/query-engine-developer-guide.html` | How StroomQL and Cypher share one query core — broader than this set, and the best orientation for the whole query stack |

### Documents absorbed into this set

Five earlier documents have been **deleted**; their content is here, corrected. They remain in git history
(`git log --diff-filter=D -- docs/<name>` finds the commit that removed one).

| Deleted | Where it went | Why it went |
|---|---|---|
| `query-optimiser-user-guide.md` | This set, throughout | Fully superseded |
| `query-optimiser-known-differences.md` | [04-behaviour-changes.md](04-behaviour-changes.md) | Absorbed. Its root-cause traces to specific lines of legacy code are the one thing not carried over verbatim |
| `query-optimiser-joins-future.md` | [12-future-work.md](12-future-work.md) | Absorbed |
| `query-optimiser-testing-protocol.md` | [14-testing.md](14-testing.md) | Absorbed, and **wrong** in two places: it claimed `EXPLAIN` is independent of the mode flag, and that per-side push-down did not exist |
| `query-optimiser-code-review.md` | Nothing — it was a dated snapshot | **Stale.** Written 2026-07-19/20, before the join scalability programme; it reports enrichment joins, per-side push-down, projection pruning and disk spilling as not started, when all four shipped within days |
