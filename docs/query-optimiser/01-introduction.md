# 1. Introduction

**Status:** Experimental, off by default. See [README.md](README.md#production-readiness).
**Audience:** analysts and evaluators.
**Scope:** what the query optimiser is, what it buys you, when not to use it, and the vocabulary the rest of this
set assumes. Canonical for the glossary.
**Companion documents:** [02-architecture.md](02-architecture.md) for how it is built,
[03-configuration.md](03-configuration.md) for how to switch it on.

---

## What it is

StroomQL has always been compiled by a hand-written engine: a regular-expression tokeniser, a structure builder,
and a factory that walks the resulting token groups and assembles a `SearchRequest`. That engine works, it is
well exercised, and it is what runs today.

The **query optimiser** is a second compiler for the same language. It parses with a real ANTLR grammar, builds a
typed abstract syntax tree, binds that tree into a logical plan with field metadata attached, runs a small set of
rewrite rules over the plan, costs it, and emits a `SearchRequest` for exactly the same search infrastructure. It
is selected by one configuration property, `stroom.query.optimiser.mode`, and it is `OFF` by default.

Nothing about your data, your indexes, your pipelines or your saved queries changes when you turn it on. It is a
different route from query text to a compiled request, not a different way of storing or searching data.

## What it buys you

### A language that can join

`join` is the headline. One StroomQL query can correlate two datasources:

```
from "Events" as a
join "Users" as b on a.UserId = b.Id
select a.EventTime, a.Status, b.Name
```

…or correlate an ordinary datasource with a graph traversal, by putting a Cypher sub-query on one side:

```
from "AuthEvents" as e
inner join ( from "CorpGraph" match (u:User)-[:MEMBER_OF]->(g:Group)
             return u.id as userId, g.name as groupName ) as ident
  on e.user = ident.userId
select e.time, e.user, ident.groupName
```

The legacy compiler rejects both — the grammar reserves `join`, and the legacy engine has nothing to compile it
into. See [06-joins.md](06-joins.md).

### Queries that scan less

Two optimisations apply automatically to ordinary single-source queries:

- **Time-range pruning from `where`.** If a query bounds the datasource's time field in its `where` clause but no
  explicit time range was set, the optimiser derives one and uses it to prune which index shards are searched.
  Legacy only ever pruned from an explicit picker value.
- **Automatic `where`/`filter` routing.** A term the index cannot evaluate is moved to extraction-time filtering
  instead of being sent to the index — which fixes a real footgun, described below.

See [05-optimisations.md](05-optimisations.md).

### Three legacy defects fixed

- `(not StreamId = 1)` — a bracket immediately against a logical keyword — is rejected by legacy and accepted
  here. Adding a space made legacy work, which was never a rule, only an artefact of per-chunk regex tagging.
- `field is null` / `field is not null` is rejected by legacy everywhere, even though its tokeniser recognises the
  syntax. The term builder's "a term needs three tokens" rule was never special-cased for the two conditions that
  take no value. The optimiser accepts both.
- A bare `where` that mixes an index-eligible term with one the index cannot evaluate **silently returns zero
  rows** under legacy — indistinguishable from "no data exists". The optimiser returns the rows it should have all
  along.

See [04-behaviour-changes.md](04-behaviour-changes.md).

### Better syntax errors

A grammar-driven parser reports a syntax error at a line and column, from a parser that knows what it expected.
The legacy tokeniser reports character offsets from a regex pass, and occasionally reports a semantic failure
where a syntactic one would have been clearer.

### A way to ask before you run

`POST /api/query/v1/explainQuery` returns the plan tree the optimiser would compile, annotated with a cost
estimate wherever one is available. The query editor already calls it and warns if the estimate looks slow.

Be honest with yourself about this one: the cost model's constants are documented guesses and two of its three
data sources are stubs, so in most environments the estimate is a zero-confidence fallback. The **plan tree** is
genuinely useful today — the access path chosen for each scan, whether a join was recognised, what got pushed
where. The **numbers** are not yet. See [08-explain-and-cost.md](08-explain-and-cost.md).

### A safe way to evaluate all of the above

`SHADOW` mode compiles every query twice: legacy serves the result, exactly as in `OFF`, and the optimiser
compiles the same query alongside it purely to log any divergence. A bug in the optimiser cannot affect what a
user sees. This is the recommended way to build confidence, and it is the step most likely to be skipped.

Safe is not the same as free: the second compile and its cost estimate run synchronously on the thread submitting
the search, so a soak adds submission latency and meta-store load. Run it on a non-production environment for a
bounded period rather than leaving it on ([03-configuration.md](03-configuration.md#shadow-mode)).

## When not to use it

- **On production traffic, today.** See [README.md](README.md#production-readiness). The blocker is missing
  evidence, not a known defect, but that is still a blocker.
- **When you need `EXPLAIN` cost numbers to be right.** They are honest about being unmeasured, which is not the
  same as being useful.
- **When you need a join across more than two sources**, or a `RIGHT`/`FULL` join, or `select *` in a join. All
  are rejected up front with a clear message rather than mis-executed.
- **When you need incremental results from a join.** A join runs to completion before it returns anything, and
  `limit` does not stop it early.
- **When you need to open the source event behind a join result row.** The reserved navigation columns come back
  null.

## What has not changed

It is worth being explicit about the size of the surface this does *not* touch:

- The **search infrastructure** is unchanged. A compiled `SearchRequest` is executed by exactly the same
  coprocessors, result stores and search providers as before.
- **Expressions** inside `eval` and `select` are still parsed by the existing `ExpressionParser`. The grammar
  delineates where such an expression starts and ends; it does not reinterpret its contents.
- **Function semantics, formatting, sorting and grouping** are untouched.
- **Permissions** are unchanged: a datasource is resolved through the same registry, under the same security
  context, whether it is named in a `from` clause or on one side of a `join`.

## Glossary

| Term | Meaning |
|---|---|
| **Legacy compiler** | `SearchRequestFactory` — the hand-written tokeniser/structure-builder/factory that compiles StroomQL today, and the oracle the optimiser is held against |
| **Optimising compiler** | `OptimisingQueryCompiler` — the grammar-driven route: ANTLR → AST → logical plan → rewrite → `SearchRequest` |
| **Dispatcher** | `DispatchingQueryCompiler` — chooses between the two on every call, based on the live value of the mode flag |
| **Mode** | `OFF` / `SHADOW` / `ON`. See [03-configuration.md](03-configuration.md) |
| **AST** | The typed tree the ANTLR parse produces — still purely syntactic, with no knowledge of what fields exist |
| **Binder** | Attaches meaning to the AST: resolves each field reference against its datasource's metadata, validates conditions, and produces a logical plan |
| **Logical plan** | A tree of relational operators — `Scan`, `Filter`, `Join`, `Project`, `Aggregate`, `Having`, `Window`, `Sort`, `Limit`, plus graph-specific nodes |
| **Rewrite rule** | A plan-to-plan transformation. Four run today, in a fixed order. See [05-optimisations.md](05-optimisations.md) |
| **Cost model** | Estimates rows, bytes, duration and a confidence for a scan, and a cardinality and algorithm for a join. Advisory only |
| **Access path** | How a scan reads its source: `FullScan`, `IndexScan` or `StateLookup` |
| **Equi-key** | A join condition of the form `a.field = b.field`. The only join condition the grammar allows |
| **Build side / probe side** | In a hash join, the side read into a lookup structure, and the side streamed against it |
| **Enrichment join** | A join to a keyed Plan B / State store on its own `Key` field, executed as one point lookup per probe row rather than by scanning the store |
| **Residual** | The part of a join's `where` clause that could not be pushed onto either side, and is therefore evaluated on each combined row |
| **Fail-open** | The optimiser's policy for plan enhancement: if anything goes wrong, return the unenhanced, legacy-identical result rather than failing |
| **Port** | An interface the planner depends on for outside information — field metadata, meta-store counts, index shard statistics, state-store counts — implemented outside the planner module |
