# Graph DB Cypher: language reference

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** analysts writing queries.
**Scope:** the complete supported query language, and every construct it rejects. Canonical for syntax and
for compile-error messages. Functions have their own file.
**Companion documents:** [07-functions.md](07-functions.md) (aggregates and scalar functions),
[05-querying.md](05-querying.md) (where to type queries),
[10-limits.md](10-limits.md) (runtime ceilings), [09-gql-and-neo4j.md](09-gql-and-neo4j.md)
(differences from Neo4j).

*Rejection messages, element-row columns and clause constraints re-verified against the code on 2026-07-29, branch `sw-query-optimiser`.*

---

Graph DB implements a **read-only subset of openCypher**, extended with temporal clauses that have no
Cypher equivalent. The subset is deliberately narrow: anything not described here is rejected at compile
time with a clear message, never silently misinterpreted.

## Query shape

```
[ from "GraphName" ]
MATCH pattern [ temporal-clause ] [ WHERE predicate ]
[ OPTIONAL MATCH pattern | WITH items [ WHERE predicate ] ]
RETURN [ DISTINCT ] items [ ORDER BY … ] [ LIMIT n ]
```

Or, for a graph-shaped result:

```
[ from "GraphName" ] MATCH pattern [ temporal-clause ] [ WHERE predicate ] RETURN GRAPH [ LIMIT n ]
```

A query has **one `MATCH`**, optionally followed by **either one `OPTIONAL MATCH` or one `WITH`**, and ends
in exactly one `RETURN`. Several queries may be combined with `UNION`.

### Choosing the graph: `from "…"`

When you run a query from a GraphDb document's own tabs, the target graph is implied and no `from` clause
is needed. Everywhere else — a Query document, a dashboard, the CSV search endpoint, the API — begin with:

```cypher
from "POLE Graph"
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type
```

The name is the GraphDb document's name (a UUID also works). This is a Stroom extension, and it is what
lets the same query text run from any text-driven surface.

## `MATCH` and patterns

### Node patterns

```cypher
()                                   -- any node (only legal as a non-anchor, or in MATCH (n) RETURN GRAPH)
(p)                                  -- bound to the variable p
(p:Person)                           -- carrying the label Person
(p:Person:Employee)                  -- carrying both labels
(p:Person {surname: 'Powell'})       -- with a property predicate
```

The **first** node pattern is the **anchor**, and how you write it determines how the query finds its
starting points:

| Anchor | Access path |
|---|---|
| Label **and** property — `(p:Person {nhs_no: 'NHS001'})` | Index seek. Fast |
| Label only — `(p:Person)` | Full scan of the graph, filtered by label |
| Neither — `(n)` | Rejected, except in `MATCH (n) RETURN GRAPH` |

This is the most important performance decision in any query. See [10-limits.md](10-limits.md).

### Edge patterns

```cypher
(a)-[:KNOWS]->(b)      -- outgoing
(a)<-[:KNOWS]-(b)      -- incoming
(a)-[:KNOWS]-(b)       -- either direction
(a)-[r:KNOWS]->(b)     -- bound to the variable r
(a)-[:KNOWS {since: '2020'}]->(b)   -- with a property predicate
```

An edge pattern must name **exactly one type**. There is no untyped or wildcard hop, and no alternation
(`[:KNOWS|FAMILY_REL]`) — adjacency is stored per edge type, so those forms have no access path.

Chains are written as you would expect, and fold left to right from the anchor:

```cypher
MATCH (o:Object {description: 'Knife'})-[:INVOLVED_IN]->(c:Crime)-[:OCCURRED_AT]->(l:Location)
RETURN l.address
```

Labels and properties on later nodes are filters applied on arrival, not alternative starting points. The
engine does not reorder your pattern.

### Variable-length hops

```cypher
MATCH (p:Person {surname: 'Freeman'})-[:KNOWS*1..2]->(f:Person) RETURN f.surname
```

`*min..max` repeats the hop. **The maximum is mandatory** — unbounded `*` and `*2..` are syntax errors by
design. `*..3` is allowed and means `*1..3`.

A variable-length hop must be the pattern's **only** hop; it cannot be chained with others. The maximum
permitted range is 50, and exploration is budgeted per starting node — see [10-limits.md](10-limits.md).

> **Cycles are guarded by node, not by relationship — a deliberate divergence from Cypher.** A single path will
> never visit the same node twice. Cypher's rule is weaker: it forbids reusing the same *relationship* but allows
> a path through the same node more than once.
>
> Concretely, given `a→b`, `b→a` and `a→c`, the pattern `(a)-[:R*1..3]->(x)` will **not** return the route
> `a→b→a→c`. Neo4j does return it — those are three distinct relationships, and only the node `a` repeats.
> Graph DB is therefore stricter and returns **fewer** paths, and **nothing reports it**: the results are simply
> narrower.
>
> **Why it is this way.** Node uniqueness bounds a path at the number of nodes in the graph. Relationship
> uniqueness is combinatorial in a dense subgraph, so adopting Cypher's rule would make the 200,000 path-state
> ceiling ([10-limits.md](10-limits.md)) far easier to hit — trading a narrower answer for a failed one. The
> divergence is a considered choice about which failure is preferable, not an oversight.
>
> **What to do about it.** A query ported from Neo4j that uses a variable-length hop over a cyclic subgraph must
> be re-checked against expected results rather than trusted. There is no runtime signal, because knowing a query
> *would have* matched more paths requires doing the wider traversal.
>
> Note that a node reached by two genuinely different paths is still two results — the restriction applies only
> within one path.

## Temporal clauses

The Stroom extension. One clause per `MATCH`, written after the pattern and before any `WHERE`. With no
temporal clause a query sees the graph as it is **now**.

### `AS OF` — a point in time

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime)
AS OF datetime('2021-01-01T00:00:00Z')
RETURN c.type
```

Evaluates the whole pattern at that instant: nodes and edges created later are invisible, and entities
since deleted are visible again.

### `BETWEEN … AND …` — a window

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime)
BETWEEN datetime('2020-01-01T00:00:00Z') AND datetime('2021-01-01T00:00:00Z')
RETURN c.type
```

Matches entities present at **any point** within the window. Costs more than `AS OF` on entities with long
histories, because it cannot stop at the first version it finds.

### `AROUND … ± …` — a window around an instant

```cypher
MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account)
AROUND datetime('2026-01-01T12:00:00Z') +/- duration('PT24H')
RETURN a.id
```

`±` and the ASCII `+/-` are both accepted. Equivalent to a `BETWEEN` centred on the instant.

### `DIFF FROM … TO …` — comparing two instants

```cypher
MATCH (c:Crime {last_outcome: 'Under investigation'})
DIFF FROM datetime('2020-01-01T00:00:00Z') TO datetime('2026-12-31T00:00:00Z')
RETURN c.type, changeKind
```

Evaluates the pattern at both instants and classifies each result:

| `changeKind` | Meaning |
|---|---|
| `ADDED` | Absent at the first instant, present at the second |
| `REMOVED` | Present at the first, absent at the second |
| `MODIFIED` | Present at both, but some property differs |
| `UNCHANGED` | Present at both, identical |

`UNCHANGED` rows are omitted from an ordinary `RETURN` and kept by `RETURN GRAPH`, where they provide the
connective context.

Inside a `DIFF` query only, two accessors read the two snapshots:

```cypher
RETURN c.type, before(c.last_outcome), after(c.last_outcome), changeKind
```

`before()`, `after()` and `changeKind` are valid in `RETURN` only — not in `WHERE`, and not outside a
`DIFF` query. The baseline must be earlier than the comparison.

**Instants and durations** are written `datetime('<ISO-8601 instant>')` and `duration('<ISO-8601
duration>')`. A bare ISO-8601 string also works. These are recognised only inside a temporal clause; they
are not general-purpose functions.

## `WHERE`

Filters after the pattern has matched.

```cypher
MATCH (o:Officer {surname: 'Larive'})<-[:INVESTIGATED_BY]-(c:Crime)
WHERE c.last_outcome = 'Under investigation'
RETURN c.type
```

**Comparison:** `=` `<>` `!=` `<` `<=` `>` `>=`
**String:** `STARTS WITH` `ENDS WITH` `CONTAINS` `=~` (regular expression)
**Other:** `IN` with a literal list, `IS NULL` / `IS NOT NULL`, `EXISTS { … }`
**Combining:** `AND` `OR` `NOT`, with parentheses

```cypher
WHERE c.type IN ['Drugs', 'Burglary']
WHERE p.surname STARTS WITH 'Pow'
WHERE p.nickname IS NULL
WHERE NOT c.last_outcome = 'Charged'
```

Comparisons are between a property (or variable) and a literal. Comparing **two properties** to each other
is supported only as a top-level `AND`-ed conjunct, with the six ordinary comparison operators — not nested
inside `OR`/`NOT`, and not with the string operators.

> **A function call cannot appear on either side.** `WHERE toInteger(f.size) > 1000` is rejected — *"the left
> side of a WHERE comparison must be a property access or variable reference"* — and the right side accepts
> only literals. Conversion functions are for `RETURN`, not for filtering; to filter numerically, declare the
> property's type at ingest and compare it directly ([03-ingest.md](03-ingest.md#property-value-types)).

### `EXISTS { … }`

Tests whether a relationship exists, without returning it:

```cypher
MATCH (p:Person {surname: 'Freeman'})
WHERE EXISTS { (p)-[:KNOWS]->(:Person) }
RETURN p.surname
```

Constrained: it must be a top-level conjunct of an ordinary query's `WHERE`, must start from a variable
already bound by the `MATCH`, must be exactly one hop, and must name an edge type.

## `RETURN`

```cypher
RETURN c.type
RETURN c.type AS crime_type
RETURN DISTINCT c.type
RETURN p.surname, count(c) AS total
RETURN c.type ORDER BY c.type LIMIT 5
```

Return items are **property accesses** (`c.type`), optionally aliased with `AS`. Expressions, `CASE` and
function calls are permitted.

> **Returning a whole node is not supported.** `RETURN c` or `RETURN *` is rejected — return the properties
> you want, or use `RETURN GRAPH`.

`ORDER BY` takes a property access or an alias. Note that when a query aggregates, you must order by the
**alias**, not by repeating the aggregate call:

```cypher
RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC      -- works
RETURN c.type AS crime_type, count(c) AS total ORDER BY count(c) DESC   -- rejected
```

`LIMIT` follows the return items, as in standard Cypher. (This differs from StroomQL, where `limit`
precedes `select`.) **`SKIP` is not supported at all.**

### Aggregation

`count`, `sum`, `avg`, `min` and `max` are available. Every non-aggregate return item becomes an
implicit grouping key, as in Cypher:

```cypher
MATCH (c:Crime) RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC
```

> **`collect()` is rejected**, not merely limited. It parses, but fails to compile because there is no list
> value type yet - it previously returned a comma-joined string, which is a wrong answer rather than a partial
> one. See [07-functions.md](07-functions.md) for what to use instead and
> [12a-list-value-type.md](12a-list-value-type.md) for why.

Aggregate arguments must be a property access — or, for `count` only, a bare variable or `*`.

## `RETURN GRAPH`

Returns the matched subgraph as nodes and edges rather than as scalar rows. This is what the **Explore**
tab renders.

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN GRAPH
```

The result is a fixed six-column **element table**, one row per node or edge:

| Column | Node row | Edge row |
|---|---|---|
| `kind` | `NODE` | `EDGE` |
| `id` | the node's id | `src\|type\|dst` |
| `labels` | comma-joined labels | the edge type |
| `source` | empty | source node id |
| `target` | empty | target node id |
| `properties` | a JSON object string | a JSON object string |

Values inside that JSON carry their **declared type**: a `long`, `double` or `boolean` property renders
unquoted, everything else quoted. So `{"qty":42,"active":true,"name":"widget"}`, not
`{"qty":"42","active":"true","name":"widget"}`. A `dateTime` renders as a quoted ISO-8601 string.

A `DIFF … RETURN GRAPH` adds a seventh column, `changeKind`.

`LIMIT` here caps the number of **nodes**, and edges are included only where both endpoints survive, so you
never get a dangling edge. With no `LIMIT`, the whole-graph preview form `MATCH (n) RETURN GRAPH` caps at
100 nodes.

`RETURN GRAPH` cannot be combined with a variable-length pattern, with `WITH`, or with `OPTIONAL MATCH`.

## `OPTIONAL MATCH`

Extends the preceding `MATCH` with a hop that may or may not exist; where it does not, the optional
variables are null.

```cypher
MATCH (p:Person {surname: 'Powell'})
OPTIONAL MATCH (p)-[:CURRENT_ADDRESS]->(l:Location)
RETURN p.surname, l.address
```

Tightly constrained: it must follow a `MATCH` (never open a query), start from a bare variable already
bound by it, extend that `MATCH`'s final variable, be exactly one hop, be fixed-length, and carry no
`WHERE` or temporal clause of its own. It cannot combine with `RETURN GRAPH` or `DIFF`.

## `WITH`

Projects an intermediate result, principally so you can aggregate and then filter on the aggregate — the
equivalent of SQL's `HAVING`.

```cypher
MATCH (p:Person)-[:PARTY_TO]->(c:Crime)
WITH p.surname AS surname, count(c) AS crimes
WHERE crimes > 1
RETURN surname, crimes
```

Every `WITH` item must be aliased. Only its projected columns are in scope afterwards — you cannot reach
back to `p.name` once the `WITH` has projected only `p.surname`. A query may have one `WITH`, it cannot
carry `ORDER BY`/`SKIP`/`LIMIT`, and the `RETURN` after it cannot aggregate again or use `ORDER BY`.

## `UNION`

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type AS t
UNION
MATCH (p:Person {surname: 'Walker'})-[:PARTY_TO]->(c:Crime) RETURN c.type AS t
```

`UNION` de-duplicates; `UNION ALL` does not. Every branch must return the same columns in the same order.
No branch may use `RETURN GRAPH` or `DIFF`.

## `CASE`

Both forms are supported:

```cypher
RETURN CASE c.type WHEN 'Drugs' THEN 'narcotics' ELSE 'other' END AS category
RETURN CASE WHEN c.type = 'Drugs' THEN 'narcotics' ELSE 'other' END AS category
```

`IN` and the string operators are not permitted inside a `WHEN` condition.

## Names that will not parse

**A grammar keyword cannot be used as a label, an edge type or a property name** — matched
case-insensitively, so `Order`, `order` and `ORDER` are equally unusable. The parse fails before anything else
is checked, with a raw message that does not explain itself: *"mismatched input 'Order' expecting NAME"*, or
*"no viable alternative at input 'r.when'"*.

The keywords are the clause and operator words of the language:

```
AFTER ALL AND AROUND AS ASC AVG BEFORE BETWEEN BY CASE COLLECT CONTAINS COUNT DESC DIFF
DISTINCT ELSE END ENDS EXISTS FALSE FROM GRAPH IN IS LIMIT MATCH MAX MIN NOT NULL OF
OPTIONAL OR ORDER RETURN SKIP STARTS SUM THEN TO TRUE UNION WHEN WHERE WITH
```

**`Order` is the one that bites in practice** — a natural label in any commerce model, and `ORDER BY` is a
clause. `when` and `count` are the common property-name casualties. Names that look risky but are fine
include `type`, `size`, `timestamp`, `id`, `name`, `path`, `node`, `edge` and `user`.

**There is no quoting or escaping syntax**, so a name in that list is unreachable from a query: the data is
stored, and nothing can ask for it. Choose names at ingest with this list to hand — renaming later means
re-ingesting.

## What is not supported

Every construct below is rejected with the message shown — most at compile time, a few when the engine tries
to run them. This is the design: the language either understands a query or refuses it, and never guesses.

### Not in the language at all

| Construct | What happens |
|---|---|
| Writes — `CREATE`, `SET`, `DELETE`, `MERGE` | Syntax error. Not keywords; data enters only through pipelines |
| `CALL`, `UNWIND`, `FOREACH`, subqueries, list comprehensions, map projections | Syntax error |
| `shortestPath()`, `allShortestPaths()`, path variables (`p = (a)-->(b)`) | Syntax error |
| Unbounded variable length — `*`, `*2..` | Syntax error; the maximum is mandatory |
| Relationship-type alternation — `[:A\|B]` | `token recognition error at: '\|'` |
| Spatial — `point()`, `point.distance()` | Not recognised |

### Rejected with a message

Two things reject a query, and it is worth knowing which is which. The **compiler** refuses before any work
is done. The **engine** refuses a handful of shapes the grammar accepts, at the moment it tries to execute
them — those are marked *(runtime)* below.

| You wrote | Message | Instead |
|---|---|---|
| `SKIP n` | `not in PoC subset: SKIP is not yet compiled (the core's Limit node has no offset slot)` | Use `LIMIT` only |
| `RETURN *` or `RETURN c` | *(runtime)* `not yet supported: RETURN item names bare pattern variable …` | Name the properties, or use `RETURN GRAPH` |
| `ORDER BY count(c)` | `not in PoC subset: an ORDER BY item must be a property access or variable reference` | `ORDER BY` the alias |
| Two `MATCH` clauses, or `MATCH a, b` | `not in PoC subset: only a single MATCH, optionally followed by one OPTIONAL MATCH or one WITH, is supported …` | Restructure as one pattern |
| `WHERE NOT (a)-[:X]->(b)` | Syntax error at the pattern | Use `EXISTS { … }` under `NOT` |
| A variable-length hop chained with others | `not in PoC subset: chaining a variable-length hop with other hops in the same pattern is not yet compiled …` | Make it the only hop |
| An untyped hop — `(a)-->(b)` as an access path | *(runtime)* `not yet supported: an untyped edge pattern (matching any edge type) has no access path …` | Name the edge type |
| An anchor with no label — `MATCH (n) WHERE …` | *(runtime)* `not yet supported: an anchor MATCH requires at least one label …` | Add a label |
| `labels(n)`, `keys(n)`, `properties(n)` | `… returns a list/map, which needs the list-valued Val type (a later phase)` | — |
| `SKIP`/`LIMIT`/`ORDER BY` on a `WITH` | `not supported in this version: ORDER BY / SKIP / LIMIT on a WITH` | Move to the final `RETURN` |
| `before()`/`after()`/`changeKind` in a `WHERE` | `not supported in this version: … in a DIFF WHERE clause (filtering on it is a later phase); it is supported in RETURN` | Filter downstream |

The compiler contains **65** such rejection messages and the engine a further **five**; the table lists those
you are most likely to meet. A compiler message begins `not in PoC subset:` or
`not supported in this version:`; an engine one begins `not yet supported:`. All name the construct, and
usually the alternative.

### Runtime limits

Distinct from the above: a query that compiles can still be stopped while running if it explores too much.
Those messages and how to avoid them are in [10-limits.md](10-limits.md).

## Next

- [07-functions.md](07-functions.md) — every function in detail
- [10-limits.md](10-limits.md) — runtime ceilings and query tuning
- [08-analysis-examples.md](08-analysis-examples.md) — these constructs applied to real questions

### Further reading

[09-gql-and-neo4j.md](09-gql-and-neo4j.md) compares this subset against ISO GQL and Neo4j clause by
clause. [12-future-work.md](12-future-work.md) records which of the gaps above are likely to close.
