# Cypher language feature roadmap for Stroom Graph DB

*A survey of the Cypher features Stroom's Graph DB does **not** yet support, what each would be worth to users,
and what it would cost (engineering difficulty and architectural risk) to add. Written to help decide **what to
build next**, not how to build it — each promising item has, or can get, its own implementation plan (see the
existing [`graphdb-analytic-functions-implementation-plan.md`](graphdb-analytic-functions-implementation-plan.md)
for the shape those take).*

*Companion to [`pole-on-stroom-graphdb.md`](pole-on-stroom-graphdb.md) (the motivating real-world query set) and
[`graphdb-analytic-functions-proposal.md`](graphdb-analytic-functions-proposal.md) (the first slice, now largely
built). Grammar of record: [`Cypher.g4`](../stroom-query/stroom-query-grammar/src/main/antlr/stroom/query/grammar/antlr/Cypher.g4);
compiler of record: [`CypherToLogicalPlan.java`](../stroom-query/stroom-query-planner/src/main/java/stroom/query/planner/cypher/CypherToLogicalPlan.java).*

---

## TL;DR

Stroom implements a deliberate, well-chosen **read-only subset** of openCypher plus a Stroom-specific temporal
extension. The subset is generous where it counts (multi-hop and bounded variable-length traversal, implicit
`GROUP BY` aggregation, temporal `AS OF`/`DIFF`) and honest everywhere else: anything out of subset is *absent
from the grammar*, so it fails to parse rather than returning a wrong answer.

The next features worth adding, in priority order:

| # | Feature | Utility | Difficulty | Risk | Verdict |
|---|---|---|---|---|---|
| 1 | **String predicates** (`STARTS WITH`, `CONTAINS`, `ENDS WITH`, `=~`) | High | Low | Low | ✅ Implemented |
| 2 | **`IN` list membership** + **`IS NULL`/`IS NOT NULL`** | High | Low | Low | ✅ Implemented |
| 3 | **`collect()`** + **`count(DISTINCT x)`** | High | Low | Low | ✅ Implemented |
| 4 | **Field-vs-field `WHERE`** (`a.x > b.y`) | Medium-High | Low-Med | Low | ✅ Implemented |
| 5 | **`OPTIONAL MATCH`** (left-outer patterns) | High | Medium | Medium | ✅ Implemented (v1) |
| 6 | **Multi-stage `WITH`** (single-pipe `HAVING`) | High | Medium | Medium | ✅ Implemented (v1) |
| 7 | **Scalar & string functions** (`toUpper`, `coalesce`, `CASE`, …) — wire Stroom's existing engine | High | Low-Med | Low | **Build soon** |
| 8 | **`UNWIND`** (list → rows) | Medium | Medium | Low-Med | Opportunistic |
| 9 | **`UNION` / `UNION ALL`** | Medium | Medium | Low | Opportunistic |
| 10 | **Multi-type relationships** (`-[:A\|B]->`) | Medium | Medium | Medium | Defer |
| 11 | **`shortestPath` / `allShortestPaths`** | High (niche) | High | High | Defer, scope hard |
| 12 | **Unbounded variable-length** (`-[*]->`) | Low | Low (parse) / High (safety) | High | Deliberately never |
| 13 | **Writes** (`CREATE`/`MERGE`/`SET`/`DELETE`), `CALL` | Low here | High | High | Out of scope |

*Function-library depth (planned as build-plan Phases 10–12, after the scalar-function base in row 7):*

| # | Feature | Utility | Difficulty | Risk | Verdict |
|---|---|---|---|---|---|
| 14 | **Arithmetic operators** (`+ - * / ^`) in expressions | High | Medium | Low | ✅ Implemented (Phase 10) |
| 15 | **Graph-identity functions** (`id`, `type`; `labels`/`keys`/`properties` gated on `ValList`) | High | Medium-High | Medium | ✅ Implemented — `id`/`type` (Phase 11) |
| 16 | **Date/time functions** (`formatDate`/`floorDay`/`now`/…) | Medium | Low | Low | ✅ Implemented (Phase 12) |
| 17 | **`stroom.` function namespace** (bare = Cypher-standard; extensions namespaced) | Medium | Low-Med | Low | Planned (build-plan Phase 13) |

Rows 1–7 are **implemented** (see [`cypher-subset-extension-implementation-plan.md`](cypher-subset-extension-implementation-plan.md), Phases 0–9) — row 6 as a single-pipe `WITH … WHERE` (HAVING), row 7 (scalar functions) in *both flavours* (Stroom-native + Cypher-exact) by *wiring an engine Stroom already has* (§ Tier 4 item 7). The implemented rows were largely *downstream of the traversal engine* — filters, reducers, and post-aggregation steps over rows it already produces — which is why they carried so little risk. Rows 14–16 extend the function library further: **14 (arithmetic)** is the standout (the engine already does the maths — it is grammar + rendering only); **15 (graph-identity)** is the most graph-native but needs the traversal to keep node identity/edge type in its rows; **16 (date/time)** is a cheap wire of Stroom's date functions. Everything else from row 8 down touches the planner, engine, or storage model and needs a design conversation first.

---

## What the subset covers today (baseline)

So this document is self-contained, here is the executable surface as of this branch — verified against the
grammar and `CypherToLogicalPlan`:

- **Reading:** a **single** `MATCH` with a node/relationship pattern — a fixed-length multi-hop chain
  (`(a)-[:R]->(b)-[:S]->(c)`) **or** a **bounded** variable-length hop (`-[:R*1..3]->`). One relationship **type**
  per hop, one arrow direction, inline label(s) and property-map equality (`(p:Person {surname:'Powell'})`).
- **Filtering:** `WHERE` over ANDed/ORed/NOT/parenthesised comparisons, each comparing a **property or `$param`
  against a literal** with `= <> != < <= >= `.
- **Projection:** `RETURN [DISTINCT] expr [AS alias], …` with `ORDER BY`/`SKIP`/`LIMIT`.
- **Aggregation:** `count/sum/avg/min/max` with **implicit `GROUP BY`** (every non-aggregate `RETURN` item is a
  grouping key). `count(*)` supported.
- **Temporal (Stroom extension):** `AS OF <t>`, `AROUND <t> ± <duration>`, `BETWEEN <t1> AND <t2>`, and
  `DIFF FROM <t1> TO <t2>` with `before(...)`/`after(...)` accessors and a `changeKind` column.
- **Graph output (Stroom extension):** `RETURN GRAPH [LIMIT n]` — one row per distinct matched node/edge, for the
  Cytoscape Data tab.
- **Portability (Stroom extension):** an optional leading `FROM "graph-name"` datasource selector.

**Parsed but not yet compiled:** more than one reading clause (multiple `MATCH`, or any `WITH` pipelining) — the
grammar accepts it, `CypherToLogicalPlan` rejects it with a clear "not yet supported" error.

**Guiding principle to preserve:** *fail loud, never wrong.* Out-of-subset input must keep producing a precise
parse/compile error with a position — never a 500 and never a silently wrong answer.

---

## Tier 1 — build next (high utility, low risk, downstream of the engine)

These are pure row-level filters or reducers. None touches storage, indexing, the planner's traversal shape, or
the temporal model. Each is additive to a step that already exists.

### 1. String predicates — `STARTS WITH`, `CONTAINS`, `ENDS WITH`, `=~`

```cypher
MATCH (p:Person) WHERE p.surname STARTS WITH 'Pow' RETURN p.surname
MATCH (c:Crime)  WHERE c.description CONTAINS 'vehicle' RETURN c.id
```

- **Utility: High.** Free-text and prefix matching over string properties is one of the most common real filters
  and today has *no* expression at all — users can only test exact equality. Prefix (`STARTS WITH`) in particular
  is the natural way to search names/identifiers.
- **Difficulty: Low.** New comparison operators in the grammar's `comparisonOp`/`comparisonPredicate`, a new
  predicate kind in the WHERE lowering, and a string test in the row filter. No new access path.
- **Risk: Low.** `=~` (regex) is the only wrinkle — bound it (compile the pattern once, guard against catastrophic
  backtracking / cap input length) or ship the three literal operators first and add `=~` behind that guard.

### 2. `IN` list membership, and `IS NULL` / `IS NOT NULL`

```cypher
MATCH (p:Person) WHERE p.surname IN ['Powell','Smith','Jones'] RETURN p
MATCH (c:Crime)  WHERE c.closedDate IS NULL RETURN c.id
```

- **Utility: High.** `IN` replaces long `OR` chains (and is how a UI naturally sends a multi-select filter).
  `IS NULL`/`IS NOT NULL` is the *only* correct way to test presence of an optional property — right now there is
  no null-aware predicate at all, so "crimes with no close date" is inexpressible.
- **Difficulty: Low.** `IN` needs a list literal (`['a','b']`) in the `value` rule plus a membership test;
  `IS NULL` is a unary predicate. Both are grammar + WHERE-lowering only.
- **Risk: Low.** Mostly a semantics decision: define three-valued-logic behaviour (Cypher's `null` propagation) up
  front and document it, so `WHERE p.x = 'y'` on a missing property behaves predictably.

### 3. `collect()` and `count(DISTINCT x)`

```cypher
MATCH (p:Person)-[:PARTY_TO]->(c:Crime)
RETURN p.surname, collect(c.id) AS crimes, count(DISTINCT c.type) AS distinctTypes
```

- **Utility: High.** `collect()` turns "N rows per group" into "one row per group with a list" — the natural shape
  for "all crimes for this person", "all officers on this case". `count(DISTINCT …)` answers "how many *distinct*
  X" (distinct crime types, distinct officers), which plain `count()` cannot.
- **Difficulty: Low.** The aggregation framework (implicit `GROUP BY`, per-group reduce in the engine's
  post-traversal step) **already exists** — `collect` is another reducer alongside `count/sum/avg/min/max`, and
  `DISTINCT` is a per-aggregate flag. The proposal doc already earmarked `collect()` as "a later phase".
- **Risk: Low.** Only real questions are the output type for a list column (how a list renders in the table/CSV
  surfaces) and an optional cap on list size to bound memory.

> **Why these three first:** they sit entirely downstream of a traversal that already works, add no subsystem, and
> each is individually small to test. Together they close most of the *expressiveness* gaps that make current
> queries feel cramped, without going near the planner or storage.

---

## Tier 2 — build soon (high utility, low-to-medium risk)

### 4. Field-vs-field comparison in `WHERE` (`a.x > b.y`)

```cypher
MATCH (a:Account)-[:TRANSFER]->(b:Account) WHERE a.balance > b.balance RETURN a, b
```

- **Utility: Medium-High.** Comparing two matched elements' properties is a common relational question the current
  literal-only `WHERE` explicitly rejects (`CypherToLogicalPlan` raises "comparing two field references … is not
  yet supported"). Enables "transfers to a richer account", "child older than parent"-style checks.
- **Difficulty: Low-Medium.** The grammar already parses it; the work is in WHERE lowering — evaluate both sides
  per row instead of assuming one side is a constant. No storage or index change.
- **Risk: Low.** Contained to the filter step. Interacts cleanly with the rest of the subset.

---

## Tier 3 — plan carefully (high utility, real planner work)

These change the *shape* of a query plan, not just a row filter. Each deserves its own design note before build.

### 5. `OPTIONAL MATCH` (left-outer patterns)

```cypher
MATCH (p:Person)
OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime)
RETURN p.surname, count(c) AS crimeCount   // people with zero crimes still appear, count 0
```

- **Utility: High.** "Include the anchor even when the optional pattern matches nothing" is a genuinely different
  answer set (left-outer vs inner join) and is needed for any "…including those with none" report. Very frequently
  wanted once basic traversal exists.
- **Difficulty: Medium.** Needs outer-join semantics in the planner and null-padding of the optional side's
  variables — which in turn *depends on* null-aware expressions (Tier 1 item 2) to be useful downstream.
- **Risk: Medium.** Null propagation through `RETURN`/`WHERE`/aggregation must be correct and consistent; this is
  where three-valued logic earns its keep. Best sequenced *after* `IS NULL` lands.

### 6. Multi-stage `WITH` / multiple `MATCH` (already parses)

```cypher
MATCH (p:Person)-[:PARTY_TO]->(c:Crime)
WITH p, count(c) AS crimes WHERE crimes > 5
MATCH (p)-[:LIVES_AT]->(a:Address)
RETURN p.surname, crimes, a.postcode
```

- **Utility: High.** `WITH` is Cypher's pipe — aggregate-then-filter (`HAVING`), narrow-then-expand, stage a
  computed value for the next `MATCH`. It is the single biggest expressiveness multiplier in the language, and the
  **grammar already accepts it** (only the compiler rejects >1 reading clause).
- **Difficulty: Medium.** The planner must thread the projected row set of one stage into the next as a driving
  input, and aggregation/ordering must compose across stages. Real work in `CypherToLogicalPlan` and the logical
  plan, but on foundations that exist.
- **Risk: Medium.** Scope discipline: land single-`WITH` (one pipe) first, then general chains. Interacts with
  temporal scoping — decide whether a temporal clause applies per-`MATCH` or per-query and keep the "one temporal
  clause" invariant explicit.

---

## Tier 4 — opportunistic (moderate utility, do when adjacent work makes them cheap)

### 7. Scalar & string functions — wire Stroom's existing expression engine

```cypher
MATCH (p:Person) OPTIONAL MATCH (p)-[:PARTY_TO]->(c:Crime)
RETURN toUpper(p.surname), coalesce(c.type, 'none'), substring(p.postcode, 0, 4)
```

- **Utility: High.** Everyday transforms (`toUpper`/`toLower`/`substring`/`replace`/`trim`/`split`/`toString`),
  null handling (`coalesce`, `CASE`), and `size` for `collect()` results. `coalesce`/`CASE` are *especially*
  valuable now that `OPTIONAL MATCH` (item 5) produces nulls. Today the graph projection supports only bare
  property/variable references — any function in `RETURN` throws.
- **Difficulty: Low-Medium — and this is the key insight.** Stroom **already ships a 232-function expression
  library** (`stroom.query.language.functions`: `UpperCase`, `Substring`, `Replace`, `Case`, `Decode`, `Round`,
  `Concat`, …) plus an `ExpressionParser`, and the Cypher compiler **already renders `RETURN` items into that
  engine's exact syntax** (property refs as `${a.name}`, calls as `name(...)`). The graph projection's
  `evaluate()` even names the gap: *"function calls need the full ExpressionParser, not wired to a graph traversal
  row."* So this is **connecting two existing components**, not reimplementing Cypher's stdlib. The real work is a
  small **Cypher-name → Stroom-name alias layer** (`toUpper`→`upperCase`, `size`→`stringLength`, …) over a
  **curated allowlist** (most of the 232 — dashboard/annotation/link/current-user functions — are irrelevant or
  unsafe for graph queries).
- **Risk: Low.** Contained to the projection step; keep the allowlist to pure, deterministic functions so it
  cannot destabilise the engine or the temporal model. `RETURN`-side first (biggest win, lowest risk); functions
  in `WHERE` are a follow-on through a second evaluator.
- **Status: implemented — both flavours (Phases 8 + 9).** Phase 8 exposed Stroom's functions under Stroom
  names/semantics; Phase 9 added Cypher's own names with Cypher-exact semantics (`substring(s, start, length)` →
  `'ell'`, `coalesce`, `size`, `left`/`right`, `ceil`, `toUpper`) via compile-time argument adaptation. Both coexist
  (Stroom's end-index substring stays reachable as `stroom_substring`). Still deferred: `replace` (regex-vs-literal)
  and the list-returning Cypher functions (`split`, `keys`, `range`, …), which wait on a real `ValList` (Tier 1 item
  3's deferred half).

### 8. `UNWIND` (list → rows)

```cypher
UNWIND ['Powell','Smith'] AS name
MATCH (p:Person {surname: name}) RETURN p
```

- **Utility: Medium.** `UNWIND` (list → rows) pairs with `collect()` and with `IN`.
- **Difficulty: Medium.** A row generator — a new source in the planner. Self-contained but non-trivial.
- **Risk: Low-Medium.** Interacts with the frontier-seeding model; cleanest once multi-stage plumbing (item 6)
  exists.

### 9. `UNION` / `UNION ALL`

```cypher
MATCH (p:Person {city:'Leeds'})  RETURN p.surname
UNION
MATCH (p:Person {city:'York'})   RETURN p.surname
```

- **Utility: Medium.** Combining result sets is occasionally essential and hard to emulate otherwise.
- **Difficulty: Medium.** Column-compatibility checking + concatenation (+ de-dup for `UNION`). Mostly a
  result-assembly concern, little storage/traversal impact.
- **Risk: Low.** Well-contained once multi-clause plumbing (Tier 3) exists.

### 10. Multi-type relationships (`-[:A|B]->`)

```cypher
MATCH (p:Person)-[:PARTY_TO|WITNESSED]->(c:Crime) RETURN p, c
```

- **Utility: Medium.** Convenient, and common in real schemas.
- **Difficulty: Medium.** The adjacency stores are **keyed per edge type** (design doc §5.1), so a multi-type hop
  must fan out into multiple physical scans and merge — a real feature, deliberately deferred, not a grammar tweak.
- **Risk: Medium.** Touches the traversal engine's scan planning and result de-duplication.

---

## Tier 5 — defer / hard-scope (high cost, or fundamentally at odds with the model)

### 11. `shortestPath` / `allShortestPaths`

- **Utility: High but niche.** Genuinely valuable for investigative "how is A connected to B" questions, but a
  minority of workloads.
- **Difficulty: High.** A new traversal *algorithm* (BFS/bidirectional/Dijkstra) with cost and termination
  guarantees — not a reducer over existing rows. Needs path materialisation and a `RETURN path` representation.
- **Risk: High.** Unbounded search space; needs hard depth/breadth/time budgets to be safe on a large graph.
  Worth a dedicated feasibility spike, not a casual add.

### 12. Unbounded variable-length (`-[:R*]->`)

- **Utility: Low** (and dangerous). Bounded var-length already covers the safe, useful cases.
- **Difficulty:** trivial to *parse*, but…
- **Risk: High.** The grammar **deliberately** requires a finite upper bound precisely so unbounded traversal is a
  parse-time error from day one. Removing that bound reintroduces unbounded fan-out. **Recommend keeping the bound
  mandatory**; if ever added, gate behind a hard cost budget, not a grammar relaxation.

### 13. Writes (`CREATE`/`MERGE`/`SET`/`DELETE`) and `CALL` procedures

- **Utility: Low in Stroom's model.** The Graph DB is populated by the **mutation-XML ingest pipeline**, not by ad
  hoc query-language writes. A write path through Cypher would conflict with that ingest path (concurrency,
  locking, temporal versioning) for little user gain.
- **Difficulty / Risk: High.** Mutation semantics, transactionality, and interaction with the temporal store.
- **Verdict: Out of scope.** Keep Stroom's Cypher read-only. `CALL`/procedures likewise — no procedure surface to
  expose today.

---

## Recommended sequence

1. ~~**Tier 1 (items 1–3)** — string predicates, `IN`/`IS NULL`, `collect()`/`count(DISTINCT)`.~~ **Done** (Phases 0–3).
2. ~~**Field-vs-field `WHERE` (4)**.~~ **Done** (Phase 5).
3. ~~**`IS NULL` → `OPTIONAL MATCH` (2 → 5)** as a pair.~~ **Done** (Phases 2, 6) — `IS NULL` landed first, exactly as
   this pairing recommended.
4. **Single-`WITH` pipelining (6)** — unlocks aggregate-then-filter (`HAVING`) and multi-stage queries; the grammar
   is already waiting for it. *(next planned phase)*
5. **Scalar & string functions (7)** — the best remaining payoff-per-effort, because it mostly *wires an engine
   Stroom already has* (`coalesce`/`CASE` pair naturally with the now-shipped `OPTIONAL MATCH` nulls). Fits cleanly
   after single-`WITH`.
6. Everything below the line (8–13) on demand, each with its own design note. Hold the line on **no writes** and
   **no unbounded traversal**.

Throughout, preserve the subset's defining contract: **out-of-subset input fails loud with a precise, positioned
error — never a wrong answer.**
