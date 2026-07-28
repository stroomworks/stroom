# Proposed analytic functions for Stroom Graph DB

*Follow-on from [pole-on-stroom-graphdb.md](pole-on-stroom-graphdb.md). That report showed Stroom's Cypher
PoC reproduces POLE's traversals cleanly but rejects every query that needs aggregation, spatial functions,
or path-finding. This report proposes a small, low-risk slice of analytics to add next — the ones with the
best payoff for the least engineering and architectural risk.*

---

## TL;DR

Add four capabilities, in this priority order:

1. **`count()`** — count matches or non-null values per group.
2. **`GROUP BY` via bare `RETURN` columns** (Cypher's implicit grouping) — bucket results by a returned field.
3. **`collect()`** — gather grouped values into a list.
4. **`min()` / `max()` / `avg()` / `sum()`** over a single numeric property.

These four cover the large majority of the aggregation gaps in the POLE report (q2, q3, and the aggregated
variants of q4/q5/q7/q14) without touching spatial types, path storage, or write support — the parts of the
gap list that carry real architectural risk. They are additive to the existing traversal engine: each is a
row-reduction step applied *after* the traversal already produces its rows, so nothing about pattern matching,
indexing, or the temporal (`AS OF`) model needs to change.

---

## Why these four, and not the rest of the gap list

The [gap table in §5 of the POLE report](pole-on-stroom-graphdb.md#5-what-didnt-work--and-why-all-fail-with-a-clear-compile-error-not-a-500)
lists seven missing capabilities. Splitting them by engineering risk:

| Capability | Engineering shape | Risk |
|---|---|---|
| **Aggregation** (`count`, `collect`, `min/max/avg/sum`) | Reduce the rows a traversal already returns | **Low** — no new storage, no new indexes, no new query planner logic beyond a group/reduce step |
| Spatial (`point`, `point.distance`) | New property type, new distance math, new predicate pushdown | Medium-high |
| Path-finding (`allShortestPaths`, multi-type variable-length) | New traversal algorithm (BFS/Dijkstra over arbitrary width), cost/termination guarantees | High |
| `RETURN path` / whole-node returns | New serialization format for nodes/paths, larger response payloads | Medium |
| Pattern-predicate `WHERE NOT (a)-[:X]->(b)` | Sub-query planning inside a `WHERE` clause | Medium |
| `SET` / writes | Mutation through the query path, conflicting with the existing mutation-XML ingest path, concurrency/locking | High |

Aggregation is the standout: it sits entirely downstream of a traversal that already works today, touches no
new engine subsystem, and is exactly what several POLE queries were blocked on (count of crimes per person,
frequency of crime types, distinct officers per crime). It's also the smallest scope to test and reason
about — a reduce over already-validated rows.

---

## The four functions

### 1. `count()`

Counts rows in a group, or non-null occurrences of an expression.

```cypher
MATCH (p:Person {surname:'Powell'})-[:PARTY_TO]->(c:Crime)
RETURN p.surname, count(c) AS crimeCount
```

Answers "how many X per Y" — the single most common analytic question in the POLE guide (q2, q3, and the
counting variants of q4/q5/q7/q14).

### 2. Grouping via `RETURN` columns

Cypher has no explicit `GROUP BY` — any non-aggregate expression in `RETURN` becomes the implicit grouping
key once an aggregate function appears alongside it. This needs no new syntax, just execution support for the
existing grammar:

```cypher
MATCH (c:Crime)-[:INVESTIGATED_BY]->(o:Officer)
RETURN o.surname, count(c) AS caseload
ORDER BY caseload DESC
```

Bundling this with `count()` is what turns "list of rows" into "one row per officer with their caseload" —
the shape almost every POLE aggregation query needs.

### 3. `collect()`

Gathers grouped values into a list instead of counting them — useful when the *count* isn't the answer but
the *members* are (e.g. "which crime types has this person been party to").

```cypher
MATCH (p:Person {surname:'Powell'})-[:PARTY_TO]->(c:Crime)
RETURN p.surname, collect(c.type) AS crimeTypes
```

Same execution shape as `count()` (reduce over a group) — the incremental cost of adding this once `count()`
and grouping exist is small, and it directly answers several POLE questions that `count()` alone can't
(returning *which* things, not just *how many*).

### 4. `min()` / `max()` / `avg()` / `sum()`

Standard numeric reductions over a single property in a group. None of the POLE data in this exercise has a
numeric property to demonstrate on, but any future dataset with a numeric field (loss amount, age, distance
travelled, case duration) will need these immediately — they're the same reduce shape as `count()`/`collect()`
and are cheap to add once that machinery exists.

```cypher
MATCH (c:Crime)-[:OCCURRED_AT]->(l:Location)
RETURN l.postcode, count(c) AS crimes, min(c.reportedDay) AS earliest, max(c.reportedDay) AS latest
```

---

## Why these are low risk

- **No new storage or property types.** All four operate on properties and rows the traversal engine already
  produces and returns today.
- **No new indexes or predicate pushdown.** Aggregation happens after the anchored, indexed pattern match —
  the part of the engine the POLE report already validated as correct.
- **Fails safe.** Like every other unsupported construct in the current PoC, if a query outgrows what these
  four can express (e.g. combining aggregation with a pattern-predicate), the parser can keep rejecting it
  with a clear compile error, not a wrong answer or a 500 — the same contract the POLE report praised the PoC
  for keeping today.
- **No interaction with time-travel.** `AS OF` / `BETWEEN` / `AROUND` continue to filter which nodes/edges are
  visible before the traversal runs; aggregation is a pure post-processing step on the resulting rows, so the
  two features compose without new design work.
- **Small, independently shippable.** `count()` + grouping alone unblocks most of the POLE gap list; `collect()`
  and the numeric reductions can follow once the reduce machinery exists, rather than needing to land together.

## What's deliberately left out

Spatial functions, path-finding, whole-node/path returns, pattern-predicates, and writes remain out of scope
for this proposal — each requires new engine subsystems (spatial math, path algorithms, mutation-through-query,
sub-query planning) with materially higher design and testing cost. They're better suited to their own,
dedicated design work rather than being bundled in with this low-risk aggregation slice.
