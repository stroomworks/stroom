# 2. Architecture

**Status:** Experimental, off by default. See [README.md](README.md#production-readiness).
**Audience:** analysts who want to know what happens to their query, and administrators.
**Scope:** the compile pipeline, the dispatcher, the ports the planner depends on, and how a join is routed at
execution time. Canonical for the pipeline description and the module layout.
**Companion documents:** [05-optimisations.md](05-optimisations.md) for what the rewrite rules do,
[06-joins.md](06-joins.md) for join execution, [13-developer-guide.md](13-developer-guide.md) for the code.

---

## The two compilers, and who chooses

Everything that compiles StroomQL goes through one interface, `QueryCompiler`, with three methods: `create` (query
text → `SearchRequest`), `extractDataSourceOnly` (query text → the datasource it names, without a full compile),
and `explain` (query text → a plan tree).

Three implementations exist:

- **`LegacyQueryCompiler`** delegates verbatim to `SearchRequestFactory`. It must stay behaviourally
  indistinguishable from calling that factory directly, because it is the oracle everything else is compared
  against. Its `explain` has no plan pipeline to reuse, so it degrades gracefully: one node naming the datasource,
  and no cost estimate at all.
- **`OptimisingQueryCompiler`** is the grammar-driven route described below.
- **`DispatchingQueryCompiler`** is what everything else is actually injected with. It reads
  `stroom.query.optimiser.mode` **on every call** — not once at construction — so a configuration change takes
  effect on the next query with no restart.

```
                      ┌── mode == ON ──────────────► OptimisingQueryCompiler  ──► SearchRequest
  create(query)  ─────┤
                      └── mode == OFF or SHADOW ───► LegacyQueryCompiler      ──► SearchRequest (served)
                                                          │
                                    mode == SHADOW only:  └─► also run OptimisingQueryCompiler,
                                                              compare, and log — never served
```

`extractDataSourceOnly` and `explain` treat `SHADOW` the same as `OFF`: there is nothing to shadow-diff for a
datasource extraction, and `explain` is advisory already. **This is why `EXPLAIN` returns no cost estimate unless
the mode is `ON`.**

Shadow comparison is best-effort and fail-open in the strongest sense: the optimiser's compile runs inside a
`try`/`catch` that swallows every `RuntimeException` to `DEBUG`. It cannot affect the served result, including by
throwing.

## The compile pipeline

```
  query text
      │
      ▼
  ┌─────────────────┐   StroomQL.g4, ANTLR-generated lexer + parser
  │  parse          │   syntax only — no knowledge of fields or datasources
  └────────┬────────┘
           ▼  AstQuery  (typed AST: AstFrom, AstJoin, AstWhereClause, AstSelectClause, …)
           │
     ┌─────┴──────────────────────────────────┐
     │                                         │
     ▼  single source                          ▼  has a join clause
  ┌──────────────────────┐              ┌──────────────────────────┐
  │ AstToSearchRequest   │              │ Binder                    │  strict: resolves every
  │ Mapper               │              │  ↓                        │  alias.field, validates
  │  (byte-parity with   │              │ RewritePipeline           │  conditions and domain
  │   legacy)            │              │  ↓                        │  types, rejects unknown
  └──────────┬───────────┘              │ split · project · compile │  and ambiguous fields
             │                          │ each side                 │
             ▼                          └────────────┬──────────────┘
  ┌──────────────────────┐                           ▼
  │ applyPlanEnhancements│              SearchRequest with a JoinSpec and a
  │  (fail-open)         │              sentinel "StroomQLJoin" datasource type
  │  Binder              │
  │  RewritePipeline     │
  │  → time range        │
  │  → where/filter split│
  └──────────┬───────────┘
             ▼
      SearchRequest
```

### Parsing

`StroomQL.g4` deliberately does **not** constrain clause order or cardinality. Legacy accepts any clause after
`from` and validates ordering semantically against a shared token-order table; the binder and the mapper re-run
that exact same check against the same shared table. Leaving ordering out of the grammar is what guarantees
identical accept/reject decisions.

Two other grammar decisions matter to users:

- **`alias.field` is one token, not three.** Legacy's bareword character class already includes `.`, so unquoted
  dotted names — and decimal numbers — tokenise as a single run. Splitting on `.` is a binder concern. This is why
  a field name containing a dot can collide with a join alias ([10-limits.md](10-limits.md#language-limits)).
- **A bracketed join source is captured as opaque, bracket-balanced text.** The StroomQL grammar does not parse
  what is inside `join ( … ) as g`; it only finds the matching close bracket. The binder re-parses that text with
  the Cypher grammar. This is the same "delineate a span, interpret it elsewhere" approach the grammar already
  uses for `eval`/`select` expressions.

### Single-source compilation: parity first, enhancement second

For a query with no `join`, `AstToSearchRequestMapper` walks the AST and builds a `SearchRequest`. Its entire
design goal is to produce **byte-identical JSON** to what legacy produces for the same query — the fold shape of
nested `AND`/`OR` operators, the column ids, the reserved navigation columns, all of it. That parity is enforced
by a differential test over the whole legacy corpus plus a seeded fuzzer
([14-testing.md](14-testing.md#the-parity-suites)).

Only then does `applyPlanEnhancements` run. It binds and rewrites the same query text a second time, through the
planner pipeline, and uses the result to parameterise the already-compiled request in two specific ways —
deriving a time range, and moving index-ineligible terms to extraction-time filtering.

This step is **fail-open**. The binder validates far more strictly than the mapper does — it rejects unknown and
ambiguous field references, which the mapper passes through as text — so a query the mapper compiled happily may
fail to bind. If anything at all goes wrong, `create` logs at `DEBUG` and returns the unenhanced request. Turning
the optimiser on must never make a query worse than it was.

The practical consequence: **the binder's stricter validation only ever produces a user-visible error for a join
query or for `EXPLAIN`.** An ordinary single-source query silently loses its enhancements instead.

### Join compilation: strict, because there is no prior behaviour to protect

A query with a `join` clause takes a different route, and it is deliberately *not* fail-open. Every join query
used to throw outright, so there is no behaviour to preserve; an unsupported shape, an incompatible equi-key or an
invalid Cypher sub-query propagates as a clear error.

The steps, in order:

1. **Reject more than one join.** N-way chains are not supported.
2. **Bind** the AST into a logical plan, resolving each side's alias and validating the equi-keys — including
   [domain-type compatibility](07-domain-types.md).
3. **Rewrite** the plan through the standard pipeline.
4. **Classify each side** as either a plain scan (optionally wrapped in a filter the rewrite pipeline pushed down)
   or a Cypher graph sub-query. Anything else is rejected.
5. **Compile the outer query** from the raw text to obtain its `where`, `select`, `group`, `having`, `sort` and
   `limit`.
6. **Split the outer `where`** into per-side pushable conjuncts and a residual.
7. **Compile each side** into its own ordinary single-source `SearchRequest`, selecting only the columns that side
   actually needs, with its pushed predicate applied and any time bound promoted into its time range.
8. **Assemble** a `JoinSpec` carrying both side requests, the join type and the equi-keys, and hang it on an outer
   request whose datasource is a **sentinel** `DocRef` of type `StroomQLJoin`.

The sentinel is the whole routing trick. `SearchProviderRegistry` resolves a provider purely by `DocRef.getType`,
so registering `JoinSearchProvider` under that type needs no special-casing anywhere else.

## Execution: how a join is routed

```
  SearchRequest (dataSource type = "StroomQLJoin", carrying a JoinSpec)
      │
      ▼
  JoinSearchProvider.createResultStore
      │  create the outer coprocessors and result store FIRST (they depend only on the request)
      │  then everything below runs inside one try/catch that reports failure via resultStore.addError
      ▼
   is one side a keyed Plan B / State store?  (type "PlanB", single equi-key, key field exactly "Key")
      │
      ├── yes ──► Enrichment fast path
      │            open the other side, stream it one row at a time,
      │            one point lookup per row against the state store —
      │            the store is never scanned or materialised
      │
      └── no ───► Streaming hash join
                   open both sides; INNER builds the smaller (by row count), LEFT always builds the right;
                   realise the build side into a lookup that spills from heap to LMDB past a threshold;
                   stream the probe side through it
      │
      ▼
  for each combined row:  apply the residual `where`  →  reorder into the outer field index  →  accept()
      │
      ▼
  the ordinary coprocessor / result-store machinery applies select, group, having, sort and limit
```

Each side is opened by looking up its own `SearchProvider` by datasource type and running its request to
completion — so an index side goes through the index provider, a searchable side through the searchable provider,
and a Cypher side through the Graph DB provider, all unchanged. A side's intermediate result store is always
destroyed, on success or failure.

A breach of any guardrail throws a `JoinLimitExceededException`, which is captured on the result store as an
in-band error. An oversized join fails with a clear message rather than an `OutOfMemoryError` or an opaque HTTP
500.

## The ports

The planner deliberately knows nothing about indexes, feeds or state stores. It asks four interfaces, each
implemented in a module that *can* know:

| Port | What it answers | Implementation today |
|---|---|---|
| `FieldInfoSource` | The fields a named datasource exposes, and which is its time field | Real — adapts the datasource registry |
| `MetaStats` | An estimated row count for a **feed**, optionally within a time range | Real — wraps the meta service's selection summary |
| `IndexShardStats` | Document count, byte size and throughput for an index over a partition range | **Stub** — always answers "no signal" |
| `StateStoreStats` | The key count of a state store | **Stub** — always answers "no signal" |

The two stubs are the reason almost every `EXPLAIN` reports `confidence: 0.0`. They are stubs for a structural
reason, not an oversight: a real adapter cannot live in the query modules without closing a dependency cycle back
through the index and Plan B modules, so it has to be written inside those modules and bound in place of the stub.
See [12-future-work.md](12-future-work.md#real-cost-adapters).

## Module layout

| Module | Contains |
|---|---|
| `stroom-query-grammar` | `StroomQL.g4`, `Cypher.g4`, the generated parsers, and the typed AST plus its builders |
| `stroom-query-planner` | The logical plan, the binder, the rewrite rules, the cost model, the join executor, and the port interfaces. Pure JVM logic — no I/O, no Stroom infrastructure |
| `stroom-query-common` | The compilers (`Legacy`, `Optimising`, `Dispatching`), the AST-to-request mapper, the join predicate splitter and projection analyser, the port adapters, the configuration classes, and the LMDB join spill store |
| `stroom-searchable-impl` | `JoinSearchProvider` — join execution and routing |
| `stroom-query-impl` | The REST resource and service, and the Guice bindings that select the implementations |
| `stroom-core-client` | The query editor's pre-run estimate call |

`stroom-query-planner` having no infrastructure dependencies is what lets the cost model, the rewrite rules and
the join executor be unit-tested in isolation — and it is why the cost adapters have to be injected as ports
rather than called directly.
