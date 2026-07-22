# StroomQL query optimiser — user guide

This guide explains the grammar-driven StroomQL parser and cost-based query optimiser: how to turn it on, what
it does (and deliberately doesn't) do today, why you might want it, the legacy bugs it fixes, a full worked
example including joins, how to use domain types, what the UI still needs, and where the system goes next.

> **Status: experimental, off by default.** The optimiser ships behind a configuration flag that defaults to
> `OFF`. With the flag off, StroomQL is compiled by the legacy engine exactly as before — nothing in this guide
> changes existing behaviour until you opt in. See the companion documents for the design and build detail:
> [`query-optimiser-plan.md`](query-optimiser-plan.md) (design), 
> [`query-optimiser-implementation-plan.md`](query-optimiser-implementation-plan.md) (build tasks + findings),
> [`query-optimiser-known-differences.md`](query-optimiser-known-differences.md) (deliberate legacy divergences).

---

## 1. Setup / configuration

The optimiser is controlled by a single configuration property:

| Property | Type | Default |
|---|---|---|
| `stroom.query.optimiser.mode` | `OFF` \| `SHADOW` \| `ON` | `OFF` |

The three modes:

- **`OFF`** — the legacy `SearchRequestFactory` compiles and serves every query. The optimiser never runs. This
  is the default and is byte-for-byte identical to the behaviour before the optimiser existed.
- **`SHADOW`** — legacy still compiles and serves every query (results are identical to `OFF`), but the optimiser
  *also* compiles each query in the background, purely to log any divergence from legacy and an estimated
  duration. **Zero risk to served results** — a bug in the optimiser or the comparison can never affect what a
  user sees. This is the recommended way to build confidence before flipping an environment to `ON`.
- **`ON`** — the optimiser compiles and serves every query; legacy never runs.

### Where to set it

The property lives under the `stroom.query` config node. In `local.yml` / `config.yml`:

```yaml
appConfig:
  query:
    optimiser:
      mode: SHADOW
```

Or via the Stroom **Properties** admin screen (search for `stroom.query.optimiser.mode`). The value is re-read on
every query, so a change takes effect immediately — no restart required — and can be rolled back the same way.

### Recommended rollout

1. Start at `OFF` (the default).
2. Move a non-production environment to `SHADOW` and run your normal query workload. Watch the logs for
   `Shadow mode: optimising compiler diverged from legacy` lines. Every divergence should trace to a documented
   difference (see [§4](#4-legacy-bugs-the-optimiser-fixes) and `query-optimiser-known-differences.md`); anything
   else is a bug to investigate before proceeding.
3. Once shadow soak is clean, flip that environment to `ON`. It is reversible by config alone at any time.
4. Promote per environment — the flag is not global.

---

## 2. What it does — and what it doesn't (yet)

### What it does

When `ON`, StroomQL is compiled through a new pipeline: an **ANTLR grammar** → a typed **AST** → a **logical
plan** → **rewrite rules** → a **cost model** → a `SearchRequest` for the existing search infrastructure. Concretely:

- **Grammar-driven parsing.** A real lexer/parser replaces the legacy regex-and-hand-rolled tokeniser, giving
  precise syntax errors with line/column positions, and fixing several legacy parsing bugs ([§4](#4-legacy-bugs-the-optimiser-fixes)).
- **Single-source parity.** For an ordinary single-datasource query, the optimiser is held to producing the same
  `SearchRequest` as legacy (enforced by a differential parity test over a large corpus, plus a property-based
  fuzzer), except for the deliberate bug fixes.
- **Rewrite rules** — constant folding, redundant-term pruning, automatic `where`/`filter` split, and
  push-filters-below-joins.
- **Time-range shard pruning from the `where` clause.** If your query says `where EventTime > ...` but you didn't
  set a UI time-range picker, the optimiser derives a time range from the predicate and uses it to prune which
  index shards are searched — something the legacy engine only ever did from the explicit picker.
- **Automatic `where`/`filter` routing.** Terms that an index can't evaluate are automatically routed to
  extraction-time filtering instead of being sent to the index (which fixes a real legacy footgun — see
  [§4](#4-legacy-bugs-the-optimiser-fixes)).
- **`EXPLAIN` / pre-run cost estimate.** A new server endpoint returns a plan tree with a cost estimate (row
  count, duration, confidence) where one is available; the query editor uses it to warn before you run a query
  that looks expensive.
- **Joins.** StroomQL `join` is parsed, bound, cost-modelled, and — for the common case — executed
  ([§5.6](#56-joins)).

### What it doesn't do yet

Be aware of the current boundaries:

- **Only a single join** (two sources). N-way join chains are rejected with a clear message.
- **`alias.field` needs a distinct alias.** A field written `a.b` is read as *alias `a`, field `b`* whenever `a`
  is a join alias in scope — there is no escape for a field whose literal name contains a dot that collides with
  an alias (rare, but it would mis-bind). Avoid dotted field names in a query that also aliases a source `a`.
- **Joins realise each side in full before filtering.** A join's `where` clause is evaluated *after* the two
  sides are combined, not pushed down to pre-filter each side — correct, but each side scans everything first
  (an efficiency optimisation, not a correctness gap; see [§8](#8-where-the-system-goes-next)).
- **No enrichment / broadcast-lookup joins yet** against State/Plan B stores.
- **Index and State cost signals are placeholders.** The cost model has a real adapter for stream/meta counts,
  but the per-index-shard and per-state-store cost adapters are stubs today, so `EXPLAIN` for index/state scans
  returns a low-confidence fallback estimate rather than a measured one.
- **No domain-relationship (multi-entity) joins**, no statistics-calibrated selectivity — future work. Equi-keys
  *are* canonicalised for numeric types (so `5`, `5.0` and `"5"` match across sources); divergent **date** formats
  across sources are not canonicalised.
- **Cost duration ignores cluster parallelism** — it's modelled as if on a single node.

None of these produce *wrong* results — they either reject cleanly (with a message) or degrade to a
lower-confidence estimate. Nothing is silently mis-executed.

---

## 3. Why you might want to use it

- **Correctness.** It fixes real legacy parsing bugs and a silent zero-rows footgun ([§4](#4-legacy-bugs-the-optimiser-fixes)).
- **Better error messages.** Grammar-driven parsing points at the exact line/column of a syntax error.
- **Faster queries on large indexes.** Deriving a time range from the `where` clause prunes shards the legacy
  engine would have scanned in full.
- **Know before you run.** The pre-run estimate warns you off an accidentally huge query before it executes.
- **Joins.** Correlate two datasources in a single StroomQL query.
- **A safe way to evaluate all of the above** — `SHADOW` mode lets you compare the two engines on real traffic
  with zero user-facing risk.

---

## 4. Legacy bugs the optimiser fixes

These are queries the *old* engine handles incorrectly and the *new* engine fixes. Each is demonstrated by a
dedicated test (`TestLegacyBugFixes` / `TestOptimisingQueryCompilerWhereFilterSplit`) and recorded in
[`query-optimiser-known-differences.md`](query-optimiser-known-differences.md). These are the divergences you'll
see logged in `SHADOW` mode.

### 4.1 Bracket immediately adjacent to `not` / `and` / `or`

```
from "index_view" where (not StreamId = 1) select StreamId
```

- **Legacy:** *rejected* — `Expected condition token`. Adding a space (`( not StreamId = 1)`) makes it work,
  which is clearly an accident of the regex tokeniser, not a rule.
- **Optimiser:** accepted, producing `NOT {StreamId = 1}` — the same result as the spaced version.

### 4.2 `is null` / `is not null`

```
from "index_view" where StreamId is null select StreamId
```

- **Legacy:** *rejected* — `Incomplete term`. The tokeniser recognises `is null`, but the term builder's
  "a term needs 3 tokens" rule was never special-cased for the value-less conditions, so the feature is wired up
  everywhere except the one place that builds the term.
- **Optimiser:** accepted, producing `ExpressionTerm{field=StreamId, condition=IS_NULL}` (no value).

### 4.3 A bare `where` mixing index-eligible and index-ineligible terms

```
from "Events" where StreamId = 1 and FreeText = 'needle' select StreamId
```

…where `FreeText` is a field the index cannot evaluate (not indexed / unsupported condition).

- **Legacy:** the *entire* predicate is sent to the index. A term the index can't evaluate compiles to a
  "match nothing" clause, and because it's ANDed with the rest, the **whole query silently returns zero rows** —
  indistinguishable from "no data exists".
- **Optimiser:** the `AutoWhereFilterSplitRule` recognises `FreeText = 'needle'` as index-ineligible and routes
  it to extraction-time filtering (`TableSettings.valueFilter`), leaving only `StreamId = 1` at the index. The
  query returns the rows it should have all along.

> This one is a genuine behaviour change, not just a parsing fix — call it out when you enable the optimiser. A
> query that already puts ineligible terms in an explicit `filter` clause is unaffected.

---

## 5. A full worked example — from data to result

This section builds a small end-to-end setup so you can see what the new features do on real data. The Stroom
building blocks are: a **Feed** (a named channel data is sent to), the **stream/meta store** (records each
batch of received data), an **Index** document (defines searchable fields), an **indexing pipeline** (parses raw
data and writes index documents), and an **extraction pipeline** (at search time, pulls the full event back for
columns not stored in the index). StroomQL queries an index by name.

> The setup steps below are conceptual — consult the main Stroom documentation for the exact UI mechanics of
> creating each document. The point here is the shape of the data and the query behaviour you'll observe.

### 5.1 Feed and data

Create a feed `WEB_ACCESS` and send it some CSV web-access events:

```
time,user,streamId,eventId,status,message
2024-01-01T09:15:00.000Z,alice,1001,1,200,GET /home
2024-01-01T09:16:30.000Z,bob,1001,2,404,GET /missing
2024-02-01T10:00:00.000Z,alice,1002,1,500,POST /order failed to reach payment gateway
```

Each send becomes a **stream** recorded in the meta store against feed `WEB_ACCESS`, tagged with a create time.

### 5.2 Indexing pipeline and Index

Create an **Index** document `Events` with these fields:

| Field | Type | Indexed / queryable | Notes |
|---|---|---|---|
| `EventTime` | Date | yes | the index's *time field* (drives shard partitioning) |
| `StreamId` | Id | yes | |
| `EventId` | Id | yes | |
| `User` | Text | yes | |
| `Status` | Integer | yes | |
| `Message` | Text | **no** (extraction only) | large free text, pulled back at search time |

Create an **indexing pipeline** that parses the CSV, translates it to `records:record` events, and writes each
event to the `Events` index. Create an **extraction pipeline** that, at search time, re-reads the source event
and emits the fields (including `Message`, which isn't stored in the index) so they can appear in results.

Set `Events` up so `EventTime` is the **time field** — this is what lets time-range pruning select shards.

### 5.3 A query that shows time-range shard pruning

```
from "Events"
where EventTime > 2024-01-15T00:00:00.000Z
select EventTime, User, Status
```

- **Legacy:** `EventTime > ...` is placed in the query expression, but because no UI time-range picker was set,
  *every* shard of the index is searched and the time filter is applied per-document.
- **Optimiser:** derives a time range (`from = 2024-01-15…`) from the `where` clause and uses it to prune shards
  whose partition window ends before that time. On our data, the January streams' shards are skipped entirely;
  only the February event is scanned.

Result (same rows either way — the difference is *how much was scanned*):

| EventTime | User | Status |
|---|---|---|
| 2024-02-01T10:00:00.000Z | alice | 500 |

### 5.4 A query that shows the `where`/`filter` fix

`Message` is extraction-only (not indexed), so the index cannot evaluate a predicate on it:

```
from "Events"
where Status = 500 and Message = 'POST /order failed to reach payment gateway'
select EventTime, User, Message
```

- **Legacy:** sends the whole predicate to the index; the `Message` term matches nothing at the index level, and
  ANDed with `Status = 500` the query returns **zero rows**.
- **Optimiser:** keeps `Status = 500` at the index, routes `Message = '…'` to extraction-time filtering, and
  returns:

| EventTime | User | Message |
|---|---|---|
| 2024-02-01T10:00:00.000Z | alice | POST /order failed to reach payment gateway |

### 5.5 Extending the query with the full clause set

The grammar supports the full StroomQL clause set. A richer query over the same index:

```
from "Events"
where EventTime between 2024-01-01T00:00:00.000Z and 2024-03-01T00:00:00.000Z
eval statusClass = if(Status >= 500, 'error', 'ok')
group by User, statusClass
select User, statusClass, count() as hits
having hits > 0
sort by hits desc
limit 100
```

- `eval` computes derived columns, `group by` aggregates, `having` filters aggregates, `sort`/`limit` order and
  cap. `window` (hopping time windows) and `show` (visualisations) are also supported.

Result:

| User | statusClass | hits |
|---|---|---|
| alice | ok | 1 |
| alice | error | 1 |
| bob | error | 1 |

### 5.6 Joins

Add a second datasource — say a State/lookup source or a second index `Users` with fields `Id` and `Name` — and
correlate:

```
from "Events" as a
join "Users" as b on a.User = b.Id
select a.EventTime, a.Status, b.Name
```

What happens under the covers when the optimiser executes this:

1. Each side is compiled into its own ordinary single-source query and run to completion.
2. The two row sets are combined in memory by the join key (`a.User = b.Id`), honouring `INNER` (default) or
   `LEFT` semantics.
3. The combined rows are fed into the outer query's result pipeline, which applies `select` (and any
   `group`/`having`/`sort`/`limit`) exactly as for a single-source query.

Result (INNER join — only users present in both):

| a.EventTime | a.Status | b.Name |
|---|---|---|
| 2024-01-01T09:15:00.000Z | 200 | Alice Smith |
| 2024-02-01T10:00:00.000Z | 500 | Alice Smith |

A `where` clause on a join works too — it's evaluated across the combined rows, so it can reference fields from
either side:

```
from "Events" as a
join "Users" as b on a.User = b.Id
where a.Status >= 500
select a.EventTime, a.Status, b.Name
```

returns only the joined rows where `a.Status >= 500` (numeric comparison — the optimiser evaluates the predicate
with the right type, not as a string).

> **`LEFT` joins** pad unmatched left rows with nulls on the right-hand columns. Each side is currently realised
> in full before the join, so a `where` clause narrows the *result*, not the amount each side scans (see
> [§8](#8-where-the-system-goes-next)).

---

## 6. Domain types — getting the most out of joins

A **domain type** is a semantic label on a field — a `class.attribute` string such as `Host.ipaddress` or
`User.id` — that says what a value *means*, independent of its physical type. Stroom already carries a
`domainType` on index/query fields and has a catalogue document type (`DomainType`) plus a wildcard matcher.

The optimiser's first use of domain types is **join-key validation**: when you write `join B on a.k = b.k`, it
checks the two keys' domain types are compatible (either may hold a single-segment `*` wildcard, e.g.
`*.ipaddress` accepts `Host.ipaddress`). A semantically nonsensical join — e.g. joining an IP address to an asset
id, both physically strings — is rejected at plan time with:

```
Join key domain types are incompatible: 'a.k' is <type> ...
```

rather than silently returning garbage at run time. The check is **advisory and degrades gracefully**: if either
key has no domain type, it's allowed through unchanged (so domain types are opt-in, never a new obstacle).

### How to set them up

1. On each **Index field** (and equivalently on State/query fields), set its `domainType` to a `class.attribute`
   value that describes its meaning, e.g. `User.id`, `Host.ipaddress`, `Asset.serial`.
2. Use the **same domain type across differently-named columns that mean the same thing** — e.g. `src_ip` on one
   source and `ipAddress` on another both tagged `Host.ipaddress`. This is what lets the optimiser confirm a join
   "by meaning" even when the column names differ.
3. Optionally create `DomainType` catalogue documents to record the vocabulary your organisation uses.

Wildcards give you flexibility: tag a generic id column `*.id` so it accepts a join against any specific
`<Something>.id`.

> Matching is deliberately blunt — two segments, one optional `*`, no subtyping — strong enough to *validate* a
> join (so auto-inference always **confirms** rather than silently rewrites), not a full ontology.

---

## 7. What the UI still needs

The backend is well ahead of the query-editor UI. What exists and what's still needed:

**Already wired:**
- A **pre-run duration warning.** When you press *Run*, the editor asks the server for a cost estimate and, if
  the estimated duration crosses a threshold (~10s today), pops a warning via the standard alert mechanism. This
  is intentionally minimal — a warning only, reusing existing UI machinery, no new panel.

**Still needed to make the new features easy to use:**
- **Surface the `EXPLAIN` plan.** The server already returns a full plan tree with per-node cost estimates
  (`explainQuery`). The editor should render it — a collapsible plan-tree side panel (the pattern exists
  elsewhere for expression trees) so users can see the chosen access paths, join algorithm, and estimates before
  running. This was deliberately deferred; only the duration warning was built.
- **A configurable, not-hardcoded warning threshold.** The ~10s threshold is a constant today; it should become
  a UI/config property.
- **A mode indicator.** When an environment is in `SHADOW`/`ON`, show it, and ideally surface shadow-mode
  divergences to admins rather than only in logs.
- **Join authoring help.** Autocomplete for `join … on …`, and domain-type-aware suggestions of compatible join
  keys across sources.
- **Clear surfacing of the deliberate rejections.** When an N-way join (or another unsupported shape) is
  rejected, the editor should present the (already clear) message helpfully rather than as a raw error.

---

## 8. Where the system goes next

The remaining work, roughly in priority order. Full detail (with the research findings behind each) is in
[`query-optimiser-implementation-plan.md`](query-optimiser-implementation-plan.md), Phase 6.

- **Per-side filter push-down through the join.** `where` across joins now works (§5.6) — but each side is
  realised in full and the predicate applied afterward. Terms referencing only one side could instead be pushed
  into that side's sub-query so it pre-filters before the join — a real efficiency win. It needs the pushed
  predicate's alias stripped (a single-source side knows the field as `field`, not `alias.field`).
- **N-way joins.** Extend beyond a single two-source join to left-deep chains.
- **Enrichment / broadcast-lookup joins.** A join whose build side is a State/Plan B store should reuse the
  existing single-key lookup functions (`GetState`) instead of materialising that side in full — with candidate
  sources *discovered by domain type* rather than hard-wired.
- **Real cost adapters.** Replace the placeholder index-shard and state-store cost signals with real ones
  (summing shard document counts over the pruned partition range, reading state-store key counts) so `EXPLAIN`
  gives measured, high-confidence estimates for index/state scans.
- **`EXPLAIN` for joins.** Annotate the plan tree with the chosen join algorithm and per-side/combined cardinality
  estimates using real distinct-key counts.
- **Domain relationships.** Extend domain types from "same entity" equi-joins to *relationship-mediated*
  enrichment joins (e.g. `User.id --OWNS--> Account.number`), routed through the store that materialises the
  relationship. Shared with the temporal Cypher graph initiative.
- **Async join execution and cluster-parallel cost modelling** — efficiency refinements.
- **Cost-model calibration.** The selectivity/throughput constants are documented, unmeasured defaults today;
  `SHADOW`-mode actual-vs-estimated logging is the first step toward calibrating them against real numbers.

---

## Quick reference

```
# Turn it on (per environment, reversible, no restart)
stroom.query.optimiser.mode = OFF | SHADOW | ON     # default OFF

# Recommended path
OFF  →  SHADOW (soak, watch divergence logs)  →  ON
```

| Capability | Status |
|---|---|
| Single-source parity with legacy | ✅ enforced by differential + generative tests |
| Legacy parsing-bug fixes | ✅ (§4) |
| `where`-clause time-range shard pruning | ✅ |
| Automatic `where`/`filter` split | ✅ |
| `EXPLAIN` / pre-run cost estimate (backend) | ✅ |
| Pre-run duration warning (UI) | ✅ minimal |
| Two-source join (INNER/LEFT), with or without a `where` clause | ✅ executes, returns real rows |
| Per-side filter push-down through the join (efficiency) | ⛔ future |
| N-way joins, enrichment joins, domain relationships | ⛔ future |
| Real index/state cost adapters, join `EXPLAIN` | ⛔ future |
