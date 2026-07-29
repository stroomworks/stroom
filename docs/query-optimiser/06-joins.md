# 6. Joins

**Status:** Experimental. Requires `stroom.query.optimiser.mode: ON`. See
[README.md](README.md#production-readiness).
**Audience:** analysts.
**Scope:** the `join` clause — syntax, what each side may be, how it executes, what it costs, and everything it
rejects. Canonical for join semantics and the execution strategies.
**Companion documents:** [07-domain-types.md](07-domain-types.md) for key validation,
[10-limits.md](10-limits.md) for the guardrail values, [09-examples.md](09-examples.md) for worked queries.

---

## Syntax

```
from <source> [as <alias>]
  [left | inner] join <source> [as <alias>] on <alias>.<field> = <alias>.<field>
                                            [and <alias>.<field> = <alias>.<field>]…
[where …] [eval …] [group by …] [having …] [sort by …] [limit …]
select <alias>.<field>[, …]
```

- **`inner` is the default.** `left` keeps every row of the left source, null-padding the right-hand columns where
  there is no match.
- **`right` and `full` do not exist.** They are parse errors, not runtime rejections.
- **The `on` condition is equi-keys only** — `=` between two alias-qualified field references. Several may be
  ANDed together to form a composite key, matched as an ordered tuple. No other operator, and no expression.
- **Both fields in an `on` condition must be alias-qualified.** A bare field name is rejected with a message
  telling you what to write instead.
- **Every selected column must be listed explicitly.** `select *` in a join is rejected: *"'select \*' (or a
  starred select-param) is not supported for join queries - list fields explicitly."*
- **An alias may not be reused.** Duplicate aliases are rejected at bind time.
- **Exactly one `join`.** A chain of two or more is rejected: *"Only a single join is supported for now - N-way
  join chains are not yet enabled."*

A source with no explicit alias takes its own name as its alias, so `join "Users" on a.UserId = Users.Id` is
legal — but naming both sides explicitly is clearer and is what the rest of this page assumes.

### The `where` clause in a join

A join's `where` may reference fields from either side, and is evaluated across the combined row. Where possible
the compiler pushes individual conjuncts down onto the side that owns them, so that side filters before the join
runs; whatever cannot be pushed is evaluated afterwards. Either way the answer is the same — see
[Push-down](#push-down).

---

## What a side may be

### A named datasource

Any datasource with a registered search provider: an Index, a Searchable, a Plan B / State store, a Graph DB. Each
side is compiled into its own ordinary single-source request and run through that datasource's own provider,
unchanged — including extraction pipelines, permissions and shard pruning.

### A Cypher sub-query against a Graph DB

A bracketed sub-query lets one side be a graph traversal:

```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (u:User)-[:MEMBER_OF]->(g:Group)
             return u.id as userId, g.name as groupName ) as ident
  on e.user = ident.userId
select e.time, e.user, ident.groupName
```

Rules for a graph side:

- **It must carry an alias.** There is no name to fall back on.
- **It must name its graph** with a leading `from "…"` inside the brackets. A join side has no owning document to
  infer it from. *"Join side 'x' has no target Graph DB - add a leading from \"...\" clause inside the brackets"*.
- **That name must resolve to a Graph DB.** *"Join side 'x' must be a Graph DB - \"y\" is a `<type>`"*.
- **Every returned item needs an `AS` alias.** The `RETURN` list *is* the side's schema — a Graph DB exposes no
  field metadata of its own — so there is nothing for a join key to bind to without one. `return u.id` is
  rejected; `return u.id as userId` is what works.
- **A `RETURN` with no scalar shape is rejected.** `return n` — a bare pattern variable, a whole matched node —
  has no single value to join on, even if aliased.
- **`DISTINCT` and aggregation are fine.** Whatever the projection produces is the schema.

The bracketed body is captured by the StroomQL grammar as opaque, bracket-balanced text and re-parsed with the
Cypher grammar. An invalid body is reported against the opening bracket, because the inner grammar's own line and
column are relative to the extracted text rather than to your query.

Two consequences of a graph side worth knowing up front:

- **Nothing is pushed into it.** There is no mechanism to rewrite a Cypher `WHERE` or `RETURN` from StroomQL, so a
  predicate on a graph side's alias always ends up in the residual, evaluated after the join. Narrowing a graph
  side is your job, in the Cypher text.
- **Its columns are typed "unknown".** No domain type (so key validation degrades gracefully rather than
  rejecting) and no condition-set restriction (so an outer clause referencing it is never rejected for using an
  unsupported condition). Comparison happens generically at execution time.

---

## What happens when you run one

### Compile time: make each side as small as possible

Three reductions run before either side executes.

#### Push-down

Each top-level `AND` conjunct of the outer `where` is considered independently. A conjunct is pushed onto a side's
own sub-query when **all** of the following hold:

1. It is a bare, enabled term — not a nested `AND`/`OR`/`NOT`.
2. Its field is qualified by exactly one side's alias.
3. That field is index-eligible on that side's datasource: known, queryable, with a condition set that supports
   the condition ([05-optimisations.md](05-optimisations.md#the-wherefilter-split) describes the same test).
4. **For a `LEFT` join, the side is the left one.** A predicate is never pushed onto the null-supplying side.

The alias prefix is stripped on the way down — a side's own sub-query knows the field as `StreamId`, not
`a.StreamId`.

Everything else stays in the **residual**, evaluated on each combined row. A conjunct appears in exactly one
place: pushed to one side, or residual. Never duplicated, never dropped.

> **Why `LEFT` never pushes right.** Pre-filtering the null-supplying side removes candidate matches from
> consideration entirely. For an `INNER` join that is harmless — a removed candidate was never going to produce a
> row. For a `LEFT` join it changes which left rows survive as unmatched, silently turning the query into an inner
> join. This is the single most correctness-sensitive rule in the join engine.

#### Projection pruning

Each side selects only the columns it actually needs: its own equi-key fields, plus every field the outer query
references it by — in `select`, in the residual `where`, in a value filter or aggregate filter. Not `select *`.

Field references are found structurally in predicate trees, but a `select` column is an arbitrary expression
(`sum(a.Amount)`, `concat(a.Name, b.Name)`), so those are scanned textually for `alias.field`-shaped tokens. That
scan is deliberately conservative: it can only ever over-match and retain a column that was not needed, never miss
one buried inside a function call. Retaining an extra column is harmless; dropping a needed one would produce
wrong results.

#### Time-range promotion

If a pushed conjunct bounds that side's time field, the bound is promoted into the side's own time range so its
scan prunes shards — the same pruning an ordinary time-bounded query gets. See
[05-optimisations.md](05-optimisations.md#time-range-pruning).

### Execution: two strategies

The choice is **structural**, made from the shape of the query before either side is realised. The cost model is
not consulted at execution time — what `EXPLAIN` names is advisory
([08-explain-and-cost.md](08-explain-and-cost.md#what-the-plan-does-not-tell-you)).

```
   is one side a keyed Plan B / State store?
   (datasource type "PlanB", exactly one equi-key, and that side's key field is exactly "Key")
      │
      ├── yes ──►  Enrichment fast path (broadcast lookup)
      └── no  ──►  Streaming hash join
```

#### The enrichment fast path

The reference store is **never scanned or materialised**. The other side is streamed one row at a time, and each
row does a single keyed point lookup. This is the shape most "enrich events with reference data" queries take, and
it is effectively free regardless of how large the reference store is.

Three things about it will catch you out:

- **The looked-up side contributes exactly two columns, named `Key` and `Value`** — the probe row's own key echoed
  back, and the single value returned. Not the store's own field names. `select b.Value` is how you read the
  enrichment; `select b.Name` finds nothing.
- **One value per lookup.** There is no multi-column enrichment; the lookup interface returns a single value.
- **The lookup is evaluated as of "now"**, not as of the query's time range. For a temporal state store that is a
  different answer, and nothing warns you.

A miss is a null: dropped for `INNER`, null-padded for `LEFT`. A lookup *failure* — a permission denial, or a key
shape the store cannot use — is a real error and aborts the whole search rather than being embedded as a value or
quietly treated as a miss.

Detection does not check the store's actual state type. A ranged, temporal-ranged or session store uses a
different key shape internally and would be detected as lookup-eligible here, then fail with whatever error the
lookup itself raises — always captured cleanly, just with a less specific message than a purpose-built rejection
would give.

#### The streaming hash join

Everything else. One side is read into a lookup structure — an on-heap hash map while it is small, transparently
spilling to a disk-backed LMDB store once it crosses a threshold — and the other is streamed through it one row at
a time, with surviving rows fed straight out.

**Which side is built:**

- For an `INNER` join, the **smaller** side, by realised row count. The result does not depend on which side is
  built, so building the smaller one is cheaper and can never be worse than always building the right.
- For a `LEFT` join, **always the right side**. The preserved (left) side must be the probe side so unmatched rows
  are emitted inline, null-padded, with no outer-join bookkeeping.
- A tie keeps the default: build the right.

The probe side is never collected into a list, so its size does not affect heap at all.

### How keys are matched

Each equi-key value is rendered to a canonical string, and the two sides must agree:

| | Behaviour |
|---|---|
| **Numbers** | Canonicalised. `5` (long), `5.0` (double) and `"5"` (string) all match. An integral floating-point value renders in long form; a genuinely fractional one keeps its own text |
| **Strings** | Rendered as-is, never reinterpreted as numbers. The string `"5.0"` does **not** match the number `5` |
| **Dates and durations** | Rendered as-is. Two sources spelling the same instant differently do **not** match. This is a documented residual, not a fix in progress |
| **Nulls** | A row whose key is null never joins — SQL `NULL != NULL`. It is not stored as a probe target and never matches another null-keyed row. A null-keyed left row is still emitted, null-padded, for a `LEFT` join |
| **Composite keys** | Matched as an ordered tuple; if *any* component is null the whole key is null |

The same derivation is used for both sides and for both strategies, so the choice of algorithm can never change
which rows match — only how quickly the match is found.

### What you lose in a join result

Stroom adds reserved `StreamId`, `EventId` and annotation columns to an ungrouped result table, so a user can open
the underlying event or attach an annotation. A joined row has no single source event, so those columns have no
value to take: they map to null.

**Data navigation and annotation do not work from a join result.** If you need them, query the side directly.

---

## Guardrails

A join either completes or aborts with a clear, in-band error. It cannot exhaust the heap on any input shape under
default configuration — that was the explicit goal of the memory-safety work.

| Guardrail | Default | What breaches it |
|---|---|---|
| `maxHeapBuildRows` | 500,000 | Not an error — the build side spills to disk from here |
| `maxHeapBuildBytes` | 256 MiB | Not an error — the same spill, triggered by estimated width instead of row count |
| `maxSideRows` | 10,000,000 | The build side growing past this, heap *and* spilled. Aborts |
| `maxOutputRows` | 1,000,000 | Joined output rows. Checked *as each row is produced*, so a single hot key's fan-out is bounded rather than accumulated first. Aborts |

Two asymmetries worth knowing:

- **The probe side is not row-capped.** It never accumulates, so it is memory-safe by construction — an enrichment
  join over an arbitrarily large event stream is fine.
- **A Graph DB side is capped by `maxSideRows` regardless of which role it plays.** The graph engine realises its
  entire traversal result in memory before the join sees it, so the memory cost has already been paid whether or
  not the rows are ever streamed onward.

Filling the LMDB spill store aborts with an actionable message naming the map size and telling you to raise it or
narrow the join. A join key whose encoded form exceeds the LMDB key limit (511 bytes, including an 8-byte row
sequence suffix) is rejected with a similarly specific message.

See [10-limits.md](10-limits.md#join-guardrails) for the full table and
[03-configuration.md](03-configuration.md#join-guardrails) for how to change them.

---

## What a join does not do yet

Capability and scale gaps, not safety gaps.

| Gap | What it costs you |
|---|---|
| **Single-node execution** | The join never uses the cluster fan-out that ordinary search does. One node scans and probes everything. This is the main throughput ceiling |
| **Two sources only** | No N-way chains |
| **Sides run sequentially, and the join runs to completion before returning** | No incremental results. A slow join holds a request thread for its whole duration |
| **`limit` does not stop the scan early** | The work is done and then discarded. Wasted effort on a limited query, not a wrong answer |
| **No semi-join reduction** | A big-⋈-big join whose only correlation is the join key itself — "today's events joined to yesterday's active users" — has nothing to push, and still scans both sides in full |
| **A `LEFT` join cannot swap its build side** | Its preserved side must be the probe side |
| **Enrichment is single-value, as of now()** | See [the enrichment fast path](#the-enrichment-fast-path) |
| **No autocomplete or editor help for `join`** | The query editor's structure help does not know the clause exists |

Where each of these is going, and what it would buy, is in [12-future-work.md](12-future-work.md).

---

## Every rejection, and what it means

| Message | Cause |
|---|---|
| *Only a single join is supported for now - N-way join chains are not yet enabled.* | Two or more `join` clauses |
| *This join shape is not yet supported - both sides must be plain datasource scans (optionally filtered) or a Cypher graph sub-query.* | A side bound to something else |
| *'select \*' (or a starred select-param) is not supported for join queries - list fields explicitly.* | `select *` anywhere in a join query |
| *Join condition field 'x' must be qualified with a source alias, e.g. 'a.x'* | An unqualified field in `on` |
| *Join condition fields must be an alias-qualified reference, e.g. 'a.field'* | A quoted or parameterised field in `on` |
| *Unknown alias 'x'* | An `on` field qualified by an alias no source declares |
| *Unknown field 'x' on 'a'* | An alias-qualified reference to a field that side does not expose |
| *Ambiguous field 'x' - present on multiple sources (…); qualify with a source alias* | An unqualified field both sides have |
| *Duplicate source alias 'x'* | The same alias used twice |
| *Join key domain types are incompatible: 'a.k' is X, 'b.k' is Y* | [Domain-type validation](07-domain-types.md) |
| *Join side 'x' is not a valid Cypher sub-query …* | The bracketed body failed to parse or compile as Cypher |
| *Join side 'x' has no target Graph DB - add a leading from "..." clause inside the brackets* | A graph side with no `from` |
| *Join side 'x' must be a Graph DB - "y" is a `<type>`* | A graph side naming something else |
| *Join side 'x': …* (missing `AS`, no scalar shape) | The Cypher `RETURN` violates the join-side schema contract |
| *show is not yet supported by the optimising binder* | `show as …` in a join query, or in a query being explained |
| *join build side row count / join output row count exceeded* | A [guardrail](#guardrails) |
| *Join build side too large to spill to disk …* | The LMDB spill store filled |
| *Join key too large to spill to disk …* | An encoded key over the LMDB key limit |

A `right` or `full` join, or any other operator in `on`, fails earlier still, as a syntax error from the grammar.
