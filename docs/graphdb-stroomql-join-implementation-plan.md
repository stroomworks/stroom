# Implementation plan: a Graph DB (Cypher) sub-query as a StroomQL join side

**Status:** Build plan / proposal
**Audience:** Stroom engineering (query grammar, planner, search)
**Scope:** The task-by-task work to let a StroomQL `join` take a **Cypher sub-query over a Graph DB** as one side — the feature motivated and justified in the companion feasibility study.
**Companion documents:**
- [`graphdb-stroomql-join.html`](graphdb-stroomql-join.html) — the feasibility & design study (verdict, syntax, examples, risk profile). **Read this first.**
- [`cypher-from-clause-implementation-plan.md`](cypher-from-clause-implementation-plan.md) — the portable `from "GraphDb"` routing prefix. **A prerequisite** (Phase 1 depends on it).
- [`temporal-cypher-graph.html`](temporal-cypher-graph.html) / [`temporal-cypher-features.html`](graphdb/archive/temporal-cypher-features.html) — the Graph DB and its temporal clauses.
- [`join-scalability-implementation-plan.md`](join-scalability-implementation-plan.md) — the StroomQL join executor this feature rides on.
- [`coding-standards.md`](coding-standards.md) — the shared, build-enforced coding standards every task follows (Checkstyle, header, tests, CHANGELOG).

---

## Shape of the work

The feasibility study establishes that the **execution path is already source-independent**: the join executor opens each side by `DocRef.getType()` and reads `Val[]`, and the Graph DB is a registered `SearchProvider`. So this plan is **front-loaded at the compile layer** and adds almost nothing to execution.

The single load-bearing decision — **how a schemaless graph side advertises its columns** — is resolved by a spike (Phase 0) that gates all grammar work. The good news, confirmed against source: the Graph DB compiler already extracts the `RETURN` column list to build its result requests ([`CypherCompiler.buildResultRequests`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java)), so the projection needed to derive a join-side schema is already computed — the spike decides the *contract*, not a new extraction.

**Phasing at a glance:**

| Phase | Deliverable | Gates |
|---|---|---|
| **P0** | Schema-from-`RETURN` contract (spike) | **Gate: no grammar code before this closes** |
| **P1** | Grammar + AST: sub-query as a join side | needs the `from "GraphDb"` routing prefix |
| **P2** | Bind: schema from `RETURN`; resolve `alias.field` | P0, P1 |
| **P3** | Compile: emit the graph side as a `GraphSpec` request; relax `createJoin` | P2 |
| **P4** | Execute: reuse `openSide`/`JoinExecutor`; graph-side guardrails | P3 |
| **P5** | *(optional)* cost model + EXPLAIN for a graph side | P4 |
| **P6** | Correctness tests | P3–P4 |
| **P7** | User docs | P6 |

---

## P0 — Schema-from-`RETURN` contract (spike, gating)

**Why first.** The join binder resolves `alias.field` against a side's declared columns, but a Graph DB advertises none (`GraphSearchProvider.getFieldInfo` → empty, `getFieldCount` → 0, `getIndexField` → null). The whole feature rests on deriving the side's schema from the sub-query's `RETURN … AS` list. Freeze that meaning before touching the grammar.

**Decide and write down:**
- `AS` aliases **mandatory** on a graph join side's `RETURN` items (no positional/auto-named columns as join keys).
- Column **type** policy: infer from the projection where possible (literal/property/aggregate), otherwise an "unknown/any" type with conservative comparison — reuse the row-predicate semantics `GraphRowValueFunctionFactory` already applies.
- Reject shapes with no scalar schema as a join side (e.g. bare `RETURN n` returning a whole node) — with a clear, positioned error.
- Interaction with `DISTINCT`/aggregation in the graph side (allowed; the projected columns are still the schema).

**Acceptance:** a short written contract + worked examples reviewed by a query owner; every example maps a concrete `RETURN` list to a concrete `(name, type)` column set (and the rejected shapes to their error text).

---

## P1 — Grammar + AST: a sub-query as a join side

**Files:**
- [`StroomQL.g4`](../stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/StroomQL.g4) — `joinClause` currently takes `source=nameToken`.
- [`AstJoin.java`](../stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/AstJoin.java), [`AstFrom.java`](../stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/AstFrom.java), [`AstBuilder.java`](../stroom-query/stroom-query-grammar/src/main/java/stroom/query/grammar/ast/AstBuilder.java).

**Change:**
- Extend `joinClause` so a join source may be **either** a `nameToken` **or** a parenthesised sub-query: `joinClause : joinType? JOIN ( source=nameToken | '(' subQuery ')' ) (AS alias)? ON …`.
- Introduce a **"join source" AST** — a sealed choice `{ NamedSource(token) | SubQuerySource(rawText, position) }` — so `AstJoin` no longer hard-codes a bare name. Capture the sub-query's **raw source text** (the existing AST convention — `AstValue`/`AstSelectFunction` already preserve exact source slices), so the bracketed body can be routed and re-parsed by the appropriate grammar.
- Keep `alias` **mandatory** for a sub-query side (there is no datasource name to default from).

**Routing:** the bracketed body begins with its own `from "X"`; grammar selection for it reuses the type-driven dispatch from the `from "GraphDb"` plan (resolve the name → typed `DocRef` → Cypher vs StroomQL). No StroomQL-specific parsing of the graph body.

**Acceptance:** parser accepts the §4/§5 example queries into an `AstJoin` carrying a `SubQuerySource`; a sub-query side without an alias is a positioned parse error; existing name-source joins parse unchanged (regression).

---

## P2 — Bind: schema from `RETURN`; resolve join keys

**Files:**
- [`Binder.java`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/bind/Binder.java) — `bindFromAndJoins`, `resolveQualifiedFieldStrict`, `validateDomainTypeCompatibility`.
- The graph side's `RETURN`-column extraction to reuse: [`CypherCompiler`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/CypherCompiler.java) (`buildResultRequests`).

**Change:**
- When a join side is a `SubQuerySource` routed to Cypher, compile/parse its body far enough to obtain the `RETURN … AS` column list, and expose it as the side's **field metadata** (per the P0 contract) — this is the substitute for `FieldInfoSource.getFields(name)` on a schemaless graph.
- Resolve each `on alias.field = alias.field` against those derived columns (`resolveQualifiedFieldStrict` already requires `alias.field` qualification).
- Run the existing `validateDomainTypeCompatibility` check across the two keys where domain types are known; degrade gracefully when the graph column has none.

**Acceptance:** `ident.userId` / `reach.hostId` / `flag.acct` from the worked examples bind to the graph side's projected columns; a join key that names a column the `RETURN` doesn't project is a positioned bind error; a bare-node `RETURN` side is rejected per P0.

---

## P3 — Compile: emit the graph side as a `GraphSpec` request; relax `createJoin`

**Files:**
- [`OptimisingQueryCompiler.java`](../stroom-query/stroom-query-common/src/main/java/stroom/query/language/OptimisingQueryCompiler.java) — `createJoin`, `compileJoinSide`, `findScanAndFilter`.
- [`JoinSpec.java`](../stroom-query/stroom-query-api/src/main/java/stroom/query/api/JoinSpec.java) (sides are fully-compiled `SearchRequest`s), [`GraphSpec.java`](../stroom-query/stroom-query-api/src/main/java/stroom/query/api/GraphSpec.java), [`JoinDataSourceType`](../stroom-query/stroom-query-common/src/main/java/stroom/query/common/v2/JoinDataSourceType.java).

**Change:**
- Teach `createJoin` to accept a **graph sub-query operand** on a side, alongside the existing plain-`Scan` case (today it rejects anything that isn't a plain, optionally-filtered `Scan` — *"both sides must be plain datasource scans"*).
- Add a graph variant of `compileJoinSide`: instead of synthesising `from "<name>" select *`, build the side's `SearchRequest` with the Cypher text on a **`GraphSpec`** and its `Query.dataSource` pointing at the Graph DB `DocRef` (type `GraphDb`). This is all `JoinSpec` needs — its `right`/`left` are just `SearchRequest`s.
- Leave the outer request assembly intact: sentinel datasource `JoinDataSourceType.TYPE = "StroomQLJoin"` + a `JoinSpec` carrying both side requests + the equi-keys.
- **Keep the single-join / two-source limit** (do not lift N-way here) — a graph side inherits it.

**Acceptance:** compiling an example query yields an outer `StroomQLJoin` request whose graph side is a `SearchRequest` with a populated `GraphSpec` and `getDataSource().getType() == "GraphDb"`; the non-graph side is unchanged; N-way and non-scan/non-graph shapes still produce today's clear errors.

---

## P4 — Execute: reuse the join executor; graph-side guardrails

**Files:**
- [`JoinSearchProvider.java`](../stroom-search/stroom-searchable-impl/src/main/java/stroom/searchable/impl/JoinSearchProvider.java) — `openSide`, `joinAndFeedViaStreamingHashJoin`, `whereRowPredicate`.
- [`GraphSearchProvider.java`](../stroom-graphdb/stroom-graphdb-impl/src/main/java/stroom/graphdb/impl/GraphSearchProvider.java) (unchanged for the happy path — it already executes a `GraphSpec` into `Val[]`).

**Change (mostly verification):**
- Confirm `openSide` routes the graph side to `GraphSearchProvider` purely by `DocRef.getType()` (it resolves `sideRequest.getQuery().getDataSource()` through the registry) — **no code change expected** here.
- Confirm the streaming hash-join, spill, build-side selection, `LEFT` null-padding, and cross-side `where` (`whereRowPredicate`) all operate on the graph side's `Val[]` unchanged.
- **Add a graph-side result guardrail:** a cap on the graph side's row count as a join input, surfaced (logged / in-band error via `ResultStore.addError`) rather than silently truncated — the graph engine is single-shard/in-memory, so an unbounded `RETURN` is the realistic failure mode.

**Acceptance:** the two worked examples run end-to-end and return the documented rows; an oversized graph side raises a surfaced truncation/limit signal, not an OOM or a silent partial result; `LEFT` join null-pads where the graph has no match.

---

## P5 — Cost model + EXPLAIN for a graph side *(optional, deferrable)*

**Files:**
- [`CostModel.java`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cost/CostModel.java) (today takes meta/index/state ports only), [`GraphStoreStats`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/port/GraphStoreStats.java), `GraphStoreStatsAdapter` (graphdb-impl).

**Change:** wire the graph stats adapter into the join `CostModel` so a graph side has a cardinality signal for build-side / algorithm choice; render the graph side in the join `EXPLAIN`.

**Acceptance:** `EXPLAIN` of a graph join shows the graph side with an estimate; build-side selection uses it. Until done, P4's runtime side-selection is the fallback — this phase is a performance refinement, not correctness.

---

## P6 — Correctness tests

**Files:** join tests under `stroom-search/stroom-searchable-impl`; planner tests [`TestLogicalPlan.java`](../stroom-query/stroom-query-planner/src/test/java/stroom/query/planner/logical/TestLogicalPlan.java); graph tests [`TestGraphTraversalEngine.java`](../stroom-graphdb/stroom-graphdb-impl/src/test/java/stroom/graphdb/impl/TestGraphTraversalEngine.java).

**Cover:**
- `INNER` and `LEFT` with a graph side; null-padding on `LEFT` misses.
- Schema-from-`RETURN` binding (P0/P2): alias resolution, missing-column error, bare-node rejection, type-inference cases.
- Temporal `AS OF` inside the graph side (join against the graph as-of an instant).
- The semi-join effect of `INNER` (the §5.1 reachability filter) end-to-end.
- Guardrail / surfaced truncation on an oversized graph side.
- Regression: existing name-source `INNER`/`LEFT` joins and single-source queries unchanged.

**Acceptance:** the two worked examples exist as end-to-end tests; the binding and guardrail edge cases each have a demonstrating test.

---

## P7 — User docs

- A Cypher-join reference section (syntax, the `RETURN … AS` schema rule, equi-join-only, single graph side, temporal alignment) + the sharp edges from the feasibility study's risk/open-questions.
- The two worked examples with sample output.

---

## Dependencies & sequencing

- **P0 gates P1.** Freeze the schema contract before grammar work.
- **P1 depends on the `from "GraphDb"` routing prefix** ([cypher-from-clause plan](cypher-from-clause-implementation-plan.md)); the bracketed body relies on type-driven grammar dispatch.
- **P2→P3→P4** are the critical path; **P5** is independently schedulable after P4; **P6** tracks P3–P4.

## Risks

See the feasibility study's [risk profile](graphdb-stroomql-join.html#risks). In order: the schema-from-projection contract (P0), the grammar/compile surface (P1–P3), the unbounded graph side (P4 guardrail), the cost-model gap (P5), and the semantic edges (P2/P4 + docs).

## Out of scope

- **The reverse direction** (StroomQL rows *into* a Cypher query) — a separate, larger feature; see the feasibility study, [§12](graphdb-stroomql-join.html#reverse).
- **N-way joins**, **correlated / per-row (broadcast) graph lookup**, and **predicate push-down from StroomQL into the traversal** beyond what the analyst writes in the Cypher body.

---

*This document is a build plan for a proposal, not a commitment. The P0 schema contract is the piece to prototype first; the rest is grammar/compile plumbing over an execution path that already exists.*
