# 13. Developer guide

**Status:** Experimental. See [README.md](README.md#production-readiness).
**Audience:** developers.
**Scope:** where the code lives, the invariants that constrain changes to it, and how to add a rewrite rule, a
cost adapter, a clause or a join strategy. Canonical for the code map.
**Companion documents:** [02-architecture.md](02-architecture.md) for the pipeline,
[14-testing.md](14-testing.md) for the gates any change must pass.

---

## Code map

### `stroom-query-grammar`

| Path | What |
|---|---|
| `src/main/antlr/…/StroomQL.g4` | The StroomQL grammar. Its file header records *why* it looks the way it does — read that before changing it |
| `src/main/antlr/…/Cypher.g4` | The Cypher subset, used for graph queries and graph join sides |
| `stroom.query.grammar.ast` | The typed StroomQL AST (`AstQuery`, `AstFrom`, `AstJoin`, `AstWhereClause`, …) and `AstBuilder` |
| `stroom.query.grammar.ast.cypher` | The Cypher AST |
| `stroom.query.grammar.parse` | `StroomQlParser`, `CypherQueryParser`, and the syntax-error listener |

### `stroom-query-planner`

Pure JVM logic. No I/O, no Stroom infrastructure, no Guice. This is what makes it unit-testable in isolation, and
it is worth preserving.

| Package | What |
|---|---|
| `logical` | The plan nodes: `Scan`, `Filter`, `Join`, `Project`, `Aggregate`, `Having`, `Window`, `Sort`, `Limit`, plus `NodeScan`/`Expand`/`VarLengthExpand` for graph plans and `GraphJoinSource` for a Cypher join side |
| `bind` | `Binder` — AST to logical plan, with field resolution, condition validation and domain-type checking |
| `rewrite` | The four rules and `RewritePipeline.standard` |
| `cost` | `CostModel`, `JoinCostModel`, `Selectivity`, the access-path types and `CostEstimate` |
| `join` | `JoinExecutor`, `BuildSideLookup`, `HeapBuildSideLookup`, and the two join exceptions |
| `cypher` | Cypher-to-plan compilation and `CypherJoinSchema` (the join-side schema contract) |
| `port` | The four outward-facing interfaces: `FieldInfoSource`, `MetaStats`, `IndexShardStats`, `StateStoreStats` |

### `stroom-query-common`

| Class | What |
|---|---|
| `QueryCompiler` | The three-method interface everything compiles through |
| `LegacyQueryCompiler` | Delegates verbatim to `SearchRequestFactory`. The parity oracle |
| `OptimisingQueryCompiler` | The grammar-driven route, including `createJoin` |
| `DispatchingQueryCompiler` | Reads the mode flag per call and picks one |
| `AstToSearchRequestMapper` | AST to `SearchRequest`, held to byte-parity with legacy |
| `JoinPredicateSplitter` | Per-conjunct, join-type-aware `where` splitting for real execution |
| `JoinProjectionAnalyzer` | Which fields a join side actually needs |
| `ScanTimeRangeExtractor` / `ScanTimeBounds` | Deriving a time bound from a filter above a scan |
| `LogicalPlanExplainer` | Logical plan to `ExplainPlan` wire tree |
| `FieldInfoSourceAdapter`, `MetaStatsAdapter` | Real port adapters |
| `NoOpIndexShardStats`, `NoOpStateStoreStats` | Stub port adapters — replace these |
| `QueryOptimiserConfig`, `QueryOptimiserMode`, `JoinConfig`, `QueryConfig` | Configuration |
| `LmdbJoinBuildStore`, `SpillingBuildSideLookup`, `JoinBuildSideLookupFactory` | The join spill store and its wiring |
| `AlternativeQueryCompiler`, `AlternativeQueryCompilerResolver` | A seam letting a module (today, Graph DB) claim a query whose owning document it recognises |

### Elsewhere

| Module | Class | What |
|---|---|---|
| `stroom-searchable-impl` | `JoinSearchProvider` | Join routing and execution |
| `stroom-query-impl` | `QueryModule` | The Guice bindings that select implementations |
| `stroom-query-impl` | `QueryResourceImpl`, `QueryServiceImpl` | The `explainQuery` endpoint |
| `stroom-query-api` | `ExplainPlan`, `JoinSpec`, `GraphSpec` | Wire types |
| `stroom-core-client` | `QueryModel` | The pre-run estimate call |

---

## Invariants

Break one of these and something subtle goes wrong. Each exists for a traceable reason.

### Parity is the gate, not a goal

`AstToSearchRequestMapper` must produce **byte-identical** JSON to `SearchRequestFactory` for every query legacy
compiles correctly. Two test suites enforce it. A deliberate divergence must be added to
[04-behaviour-changes.md](04-behaviour-changes.md) *and* excluded from those suites with the asymmetry asserted in
both directions — never silently skipped.

Two consequences that look like oddities until you know why:

- The boolean expression grammar folds each repetition **pairwise, left-associatively**, into nested operator
  nodes, because that is the shape legacy produces. A flat n-ary `AND` would not match.
- Clause **order and cardinality are not constrained by the grammar**. Both the mapper and the binder re-run
  legacy's own shared token-order check, which is what guarantees identical accept/reject decisions.

### Plan enhancement is fail-open; join compilation is not

`applyPlanEnhancements` catches every `RuntimeException` and returns the unenhanced request. Turning the optimiser
on must never make a query worse than it was. `createJoin` deliberately does the opposite — every join query used
to throw, so there is no behaviour to protect, and a real failure should surface.

If you add an enhancement, put it inside the fail-open block. If you add a join capability, let it throw.

### The planner has no infrastructure dependencies

`stroom-query-planner` depends on nothing that does I/O. New outside information arrives through a **port** in
`stroom.query.planner.port`, implemented in a module that can see the thing being asked about. This is not
stylistic: a real index cost adapter placed in the query modules closes a dependency cycle back through the index
module, which is precisely why the stub exists.

### Key derivation is single-sourced

`JoinExecutor.keyOf` is public so that every producer of a build-side key derives it **identically** to how the
probe key is derived. If the two ever diverge, rows silently stop matching. Do not reimplement it.

### The algorithm never changes the answer

Hash join, nested loop and broadcast lookup share the same matching semantics, including numeric canonicalisation
and SQL-null handling. A different algorithm choice may only change how fast a match is found.

### `LEFT` joins never pre-filter the right side

In `JoinPredicateSplitter` (execution) and in `PushFiltersBelowJoinsRule` (explain). Getting this wrong turns a
`LEFT` join into an `INNER` one, silently. It is the most correctness-sensitive rule in the engine and it is
tested at unit and end-to-end level.

### Rewrite rules never guess

An unknown field, a missing condition set, a top-level operator that is not `AND` — anything a rule cannot resolve
with confidence is treated as *not eligible* and left exactly where it was. A wrong "leave it alone" is safe if
suboptimal; a wrong "push it" can zero a result set.

### Positional, not named, inside the join executor

`whereRowPredicate` and `buildFieldMapping` take "left" and "right" parameters, but they are purely **positional**:
the first slice of the combined row and the second. That is what lets the same code serve both build-side
orientations and the broadcast-lookup path, whose combined row is always `[probe…, Key, Value]` regardless of
which `JoinSpec` slot the lookup occupies.

### Everything a join opens is destroyed

Each side's intermediate result store, and the build-side lookup with its spill directory, are released on every
path — success, failure, and failure part-way through opening the second side.

---

## How to…

### Add a rewrite rule

1. Implement `RewriteRule` in `stroom.query.planner.rewrite`. Handle **every** plan node in the switch — including
   the graph nodes, which usually means recursing through and leaving them unchanged.
2. Add it to `RewritePipeline.standard`, in a position that reflects what it needs from the rules before it.
3. Decide, and document in the class Javadoc, what it does when it cannot resolve something. The answer is
   "nothing".
4. Test the rule in isolation, then add a pipeline test that shows it composing.
5. If it changes compiled output for a single-source query, it is a divergence: document it in
   [04-behaviour-changes.md](04-behaviour-changes.md) and handle it in the parity suites.

### Write a real cost adapter

1. Implement `IndexShardStats` (or `StateStoreStats`) **inside the module that owns the data** — the index module,
   the Plan B module. Not in `stroom-query-common`.
2. Return `Optional.empty()` when there is genuinely no signal; do not fabricate one.
3. Populate throughput where it exists — that is what lifts confidence from 0.5 to 1.0 for an unfiltered scan.
4. Rebind in `QueryModule` in place of the stub.
5. Expect the pre-run warning to start firing. That is the point, but it is a user-visible change.

### Add a StroomQL clause

1. Add the parser rule to `StroomQL.g4` and the AST node plus its builder case.
2. Handle it in `AstToSearchRequestMapper` — this is what determines compiled output — including its position in
   the shared token-order check.
3. Handle it in `Binder`, producing whatever plan node it maps to. If there is no sensible mapping, reject it
   explicitly, as `show` does, rather than ignoring it.
4. Handle it in every rewrite rule's switch and in `LogicalPlanExplainer`.
5. Add it to the parity corpus, and to the generator if it can be produced randomly.

### Add a join strategy

1. The dispatch point is `JoinSearchProvider.joinAndFeed`. Detection happens **before** either side is realised —
   that is what lets the enrichment path avoid materialising its store at all.
2. Reuse `whereRowPredicate` and `buildFieldMapping` by passing your combined row's slices positionally.
3. Enforce `maxOutputRows` as each row is produced, not at the end.
4. Derive keys with `JoinExecutor.keyOf`.
5. Prove the new strategy returns byte-identical rows to the existing one for a case both can run. That harness
   already exists — see [14-testing.md](14-testing.md#the-differential-harness).

### Debug a compile difference

1. Set the mode to `SHADOW` and run the query. The `INFO` divergence line contains both compiled forms as
   canonical JSON — diff them.
2. If the optimiser threw rather than diverging, the failure is at `DEBUG` under `DispatchingQueryCompiler`.
3. If the query compiled but got no enhancements, look for `Unable to enhance compiled SearchRequest` at `DEBUG`
   under `OptimisingQueryCompiler` — that usually means the binder rejected something the mapper accepted.

---

## Known code smells, documented rather than fixed

Recorded here so nobody rediscovers them as bugs:

- **`create` parses the query text twice** for a single-source query — once in the mapper, once again inside
  `applyPlanEnhancements`. The mapper's parse is not currently exposed for reuse. Cheap to live with, mildly risky
  to change.
- **`compileJoinSide`'s filter-applying branch is dead from the join route.** Per-side predicates now arrive via
  `JoinPredicateSplitter` and are applied through the same method's `Filter` parameter, but the specific path that
  re-derives them is not reachable in production.
- **`JoinExecutor.join`'s list-returning entry point always materialises the right side**, regardless of what the
  cost model would prefer. Production uses the streaming path instead, which does honour the build-side choice.
- **The graph-side push-down sentinel** is a deliberately unmatchable datasource name, used to make the predicate
  splitter treat every predicate on a graph side's alias as non-pushable via the existing "unknown field is never
  eligible" default, rather than adding a special case.
