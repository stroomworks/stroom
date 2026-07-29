# 14. Testing

> **The manual protocol below has not been executed end to end.** Tests A–C have been run in pieces during
> development; Test D has been run live once, found three blocking bugs (all fixed), and has **not** been re-run
> since to confirm the returned rows are correct. The expected results here are derived by construction from the
> documented behaviour, not observed. Derived expectations are exactly the kind that can be self-consistently
> wrong.

**Status:** Experimental. See [README.md](README.md#production-readiness).
**Audience:** developers and testers.
**Scope:** the automated suites that gate a change, and a manual acceptance protocol anyone with an
administrator account can run. Canonical for the acceptance cases.
**Companion documents:** [09-examples.md](09-examples.md) for the same queries with explanations,
[04-behaviour-changes.md](04-behaviour-changes.md) for what a divergence should look like.

---

## The automated suites

Roughly 65 test classes across the four modules involved. These are the ones that act as gates.

### The parity suites

The hard gate on any change to compiled output.

| Suite | What it does |
|---|---|
| `TestQueryCompilerParity` | Compiles the **entire legacy corpus** through both engines and requires byte-identical JSON. Reports every mismatch in one run rather than stopping at the first |
| `TestQueryCompilerGenerativeParity` | Fuzzes beyond the hand corpus: 200 random-but-valid StroomQL queries per run, from a **fixed seed** so a failure is reproducible from the file alone. Seeds that have ever produced a mismatch are pinned permanently |

**Every valid query in the corpus must match exactly — no exceptions.** A small, explicitly enumerated set of the
corpus's deliberately malformed queries is checked more loosely: both engines must still reject, but the exception
text is not compared, because legacy embeds token offsets the grammar has no equivalent for. Each entry names its
reason.

The one deliberate asymmetry the generator can produce — a bracket immediately against a logical keyword — is
special-cased and asserted **explicitly in both directions**, rather than skipped. A regression in either engine
fails loudly.

### The divergence suites

| Suite | What it proves |
|---|---|
| `TestLegacyBugFixes` | Each of the two parser fixes, asserting both directions: legacy still rejects, the optimiser does not. Plus a control showing the two agree once the legacy-only obstacle is removed |
| `TestOptimisingQueryCompilerWhereFilterSplit` | The mixed-eligibility split moves the ineligible term to a value filter, and the two no-op controls: all-eligible is untouched, and an explicit `filter` clause is untouched |

### The join suites

| Suite | What it covers |
|---|---|
| `TestOptimisingQueryCompilerJoin` | Compilation: the sentinel datasource and populated join spec, `LEFT` mapping, `select *` rejection, N-way rejection, `right join` rejected at parse time, per-side push-down including *`LEFT` never pushes right*, and time-bound promotion |
| `TestOptimisingQueryCompilerGraphJoinSide` | A Cypher sub-query side compiles to a graph-spec request targeting the resolved Graph DB, and every rejection around it |
| `TestOptimisingQueryCompilerJoinSideCompilation` | The synthetic per-side sub-query |
| `TestJoinPredicateSplitter`, `TestJoinProjectionAnalyzer` | The two compile-time reductions, in isolation |
| `TestJoinExecutor` | Matching semantics: null keys, composite keys, numeric canonicalisation, output caps, both algorithms |
| `TestJoinSearchProvider` | Execution end to end against test doubles: both strategies, build-side selection, guardrail breaches, the enrichment path never realising its store |
| `TestJoinPushDownDifferential` | **The differential harness** — see below |
| `TestLmdbJoinBuildStore` | The spill store: duplicates preserved, prefix retrieval, over-long keys, a full map |

### The differential harness

`TestJoinPushDownDifferential` is the gate on any change to push-down or the executor. It uses a provider that
*genuinely* evaluates each side's pushed predicate and projection against a fixed table, then asserts
**byte-identical rows** between the unoptimised baseline and the optimised shape:

- a pushed left predicate on an `INNER` join
- a pushed right predicate on an `INNER` join
- a `LEFT` join with only a left predicate pushed
- pruned select columns versus selecting every column
- push and prune combined
- a **spilled** build side versus the on-heap baseline
- build-side **swap** versus no swap

The harness has been sanity-checked by deliberately breaking the implementation and confirming it catches it.

### What the automated tests cannot catch

Worth knowing, because it is exactly where the live bugs came from. The join suites build their providers and
registries directly with test doubles — none goes through the real Guice injector or a real LMDB-backed data
store. The three bugs live testing found (a Guice startup cycle, a null query key on a side's sub-request, a
placeholder time filter one row-key shape rejects) were each invisible to every in-module test. A smoke-style
integration test that boots the real injector and runs one join against real data stores would close that class
of gap; it does not exist.

Two coverage gaps are deliberately left open as too brittle to assert: shadow-mode diff-outcome logging, and
estimated-duration logging. Both assert log output; the surrounding behaviour (returns legacy, calls the
optimiser, fails open) is tested.

---

## The manual protocol

Everything below needs an administrator account on a running Stroom. Nothing needs a code change.

### Test A — `EXPLAIN` (no data needed)

The most self-contained test: it compiles a query and returns the plan without executing anything.

```
POST /api/query/v1/explainQuery
Content-Type: application/json
Authorization: Bearer <api-key>

from "MyIndex" where EventTime > now() - 1d select StreamId, EventTime
```

**The body is the raw StroomQL text, sent verbatim** — not a JSON-quoted string. Wrapping it in quotes makes the
parser treat the quotes as part of the query and fail with a missing-`from` error.

Authenticate with an **API key or bearer token**. A session cookie alone is rejected with **403** by the CSRF
filter, so the Swagger "Try it out" button fails.

| Check | Expected |
|---|---|
| Mode `OFF` or `SHADOW` | A single node: `Scan MyIndex (legacy engine - no cost estimate available)`. **No children, no numbers** |
| Mode `ON`, well-formed query | A nested tree whose leaf is `Scan <datasource> as <alias> (<AccessPath>)` |
| Mode `ON`, index-backed source | `confidence: 0.0` and the note `no cost signal available for '<name>'`. This is **expected**, not a failure |
| Malformed query (`select foo`) | HTTP **400** with the syntax error — not 500. The JSON body's `code` matches the status |
| Empty body | HTTP **400** |

### Test B — parity

**Goal:** with the optimiser serving, an ordinary single-source query returns exactly what it did under legacy.

Any populated datasource will do.

1. At `mode: OFF`, run a representative query in a Query doc or Dashboard and record the result table.
2. Set `mode: ON`.
3. Re-run the same query.

**Expected:** identical rows and columns.

**The one documented exception:** a bare `where` mixing an index-eligible term with a term the index cannot
evaluate returns **zero rows** under legacy and the actually-matching rows under the optimiser. Differing output
there is correct. See
[04-behaviour-changes.md](04-behaviour-changes.md#3-a-bare-where-mixing-eligible-and-ineligible-terms).

Also worth confirming, at `mode: ON`, that each of these now runs where legacy rejected it:

- `from "X" where (not Field = 1) select Field`
- `from "X" where Field is null select Field`
- `from "X" where Field is not null select Field`

### Test C — shadow soak

**Goal:** confirm the two engines agree on real traffic before ever serving with the new one. **This is the
highest-value test on this page.**

1. Set `mode: SHADOW`.
2. Drive normal query traffic — your usual dashboards and saved queries — for long enough to be representative.
3. Inspect logs for `stroom.query.language.DispatchingQueryCompiler`.

**Expected:**

- Divergences at `INFO`, with the query text and both compiled forms. Matches at `DEBUG`.
- **Every divergence traces to a documented case.** Anything else is a finding to report.
- Served results are unchanged from `OFF` — legacy serves them.

Turn on `DEBUG` for that logger for at least part of the soak. A shadow compile *failure* is logged there and
nowhere else, and it is the strongest available signal that flipping to `ON` would break something.

**A soak covers only queries legacy already compiles.** Legacy runs first and its exception is what the caller
gets, so a query legacy rejects never reaches the shadow compile — the two parser fixes and every
error-message difference stay invisible until the mode is `ON`. Cover those explicitly in Test B rather than
expecting the soak to surface them.

### Test D — joins

`join` runs only at `mode: ON`.

**Test data:** two datasources sharing a joinable key — an events index with a user column, and a users
reference source. Two indexes work; two searchables work; an index and a Plan B store exercises the enrichment
path.

```
from "Events" as a
join "Users" as b on a.UserId = b.Id
where a.StreamId = 1
select a.StreamId, b.Name
```

| Case | Expected |
|---|---|
| `join` (INNER) | One combined row per matching pair; unmatched rows on either side dropped |
| `left join` | Every left row kept; unmatched left rows have right-side columns null |
| A null key on either side | Never matches another null. Dropped (INNER) or null-padded (LEFT), never cross-producted |
| `where` on one side only | Same rows as without it, minus the ones it excludes. It should be pushed into that side |
| `where` on a `left join`'s right side | Same answer, evaluated after the join. **A `left join` must not degrade to an inner join** |
| `select *` | Rejected: *list fields explicitly* |
| Two or more `join` clauses | Rejected: *Only a single join is supported for now* |
| `right join` | Syntax error |
| An unqualified field in `on` | Rejected, naming what to write instead |
| The result's `StreamId`/`EventId` columns | **Null.** Expected — a joined row has no single source event |

**Enrichment path**, with a Plan B / State store as one side:

```
from "Events" as a
join "Users" as b on a.UserId = b.Key
select a.UserId, b.Value
```

Note `b.Key` in the condition and `b.Value` in the select — the lookup side contributes exactly those two
synthetic columns, not the store's own field names.

**Cypher sub-query side**, with a Graph DB:

```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (u:User)-[:MEMBER_OF]->(g:Group)
             return u.id as userId, g.name as groupName ) as ident
  on e.user = ident.userId
select e.time, e.user, ident.groupName
```

Confirm that dropping an `AS` alias, or returning a bare pattern variable, is rejected with a clear message.

**`EXPLAIN` a join** via Test A: a `Join (…)` node over two children. With no cost signals — the common case — it
carries the note *"distinct-key counts unknown - cardinality is the pessimistic upper bound (full
cross-product)"*. **The algorithm it names is not what executes.**

### Test E — guardrails

**Goal:** confirm an oversized join fails cleanly rather than crashing.

The cheapest way is to lower a cap rather than build a huge dataset:

1. Set `stroom.query.join.maxOutputRows` below the number of rows your test join produces.
2. Run the join.

**Expected:** the search reports an error naming *join output row count*, the cap and the count that breached it.
No HTTP 500, no `OutOfMemoryError`, no partial result presented as complete.

Repeat with `maxSideRows`, and with `maxHeapBuildRows` set very low — the last should **not** error, it should
spill to disk and still return the right rows. Confirm the `join_<uuid>` directory under the result-store LMDB
directory appears while the join runs and is gone afterwards.

Restore the defaults when you are done.

---

## Reporting

For any failure, capture:

- the exact StroomQL,
- the value of `stroom.query.optimiser.mode`,
- the result, or the error and its HTTP status,
- for a parity or shadow divergence, **both compiled forms** from the log.

A divergence matching a case in [04-behaviour-changes.md](04-behaviour-changes.md) is expected. Anything else is a
bug.
