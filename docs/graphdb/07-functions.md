# Function reference

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** analysts writing queries.
**Scope:** every aggregate and scalar function callable from a Graph DB Cypher query. Canonical for
function names and signatures.
**Companion documents:** [06-language-reference.md](06-language-reference.md) (the surrounding syntax),
[08-analysis-examples.md](08-analysis-examples.md) (functions in use).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

Two vocabularies are available, and they do not clash:

- **Bare Cypher names** — `toUpper(x)`, `round(x)` — a small set of standard Cypher functions.
- **The `stroom.` namespace** — `stroom.formatDate(x)` — 67 of Stroom's own expression functions, the same
  ones available in dashboards and StroomQL.

Any other namespace is rejected. So is a Stroom function called without its prefix: `formatDate(...)`
produces *"'formatDate' is a Stroom extension - call it as stroom.formatDate(...)"*.

## Aggregates

Six, and they follow Cypher's grouping rule: **every non-aggregate item in your `RETURN` becomes an
implicit grouping key**.

| Function | Forms | Returns | Empty group |
|---|---|---|---|
| `count` | `count(*)`, `count(v)`, `count(a.p)`, `count(DISTINCT a.p)` | Integer | `0` |
| `sum` | `sum(a.p)` | Number | `0` |
| `avg` | `avg(a.p)` | Number | `null` |
| `min` | `min(a.p)` | Same type as input | `null` |
| `max` | `max(a.p)` | Same type as input | `null` |
| ~~`collect`~~ | — | **Unavailable** — see below | — |

```cypher
MATCH (c:Crime) RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC
```

`count(*)` and `count(v)` count rows. `count(a.p)` counts rows where that property is present and
non-null — so it is also a "how many of these have this property" test.

`DISTINCT` is accepted on `count` only.

Aggregate arguments must be a **property access** (`c.type`). The one exception is `count`, which also
accepts `*` or a bare variable. `sum(c)` over a whole node is rejected — a node has no single value to sum.

> **`collect()` is not available.** A query using it fails to compile:
>
> ```
> not supported in this version: collect(...) is unavailable because there is no list value type yet
> ```
>
> It previously returned a comma-joined string — `"Drugs, Burglary"` — which is not a list: you could not index
> it, take its `size()`, or unwind it, and a two-element list was indistinguishable from a one-element list whose
> value contained a comma. That made it a **silently wrong** answer for anyone arriving from Neo4j, so it now
> fails instead of misleading.
>
> **What to do instead:** aggregate with `count`/`sum`/`avg`/`min`/`max`, or return one row per value and group
> in whatever consumes the results. For the POLE example above:
>
> ```cypher
> MATCH (o:Officer {surname:'Larive'})<-[:INVESTIGATED_BY]-(c:Crime)
> RETURN o.surname AS officer, c.type AS crime_type, count(c) AS crimes
> ```
>
> The analysis behind the deferral, and what re-enabling involves, is in
> [12a-list-value-type.md](12a-list-value-type.md).

### Ordering aggregated results

`ORDER BY` must name the **alias**, not repeat the aggregate call:

```cypher
RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC      -- works
RETURN c.type AS crime_type, count(c) AS total ORDER BY count(c) DESC   -- rejected
```

## Bare Cypher functions

### Directly supported

Each maps onto a Stroom function of the same meaning.

| Function | Meaning |
|---|---|
| `toUpper(s)` / `toLower(s)` | Case conversion |
| `toString(x)` | Convert to string |
| `toInteger(x)` / `toFloat(x)` / `toBoolean(x)` | Type conversion — useful because properties are stored as strings |
| `abs(n)`, `sign(n)`, `sqrt(n)`, `exp(n)`, `log(n)` | Arithmetic |
| `round(n)`, `floor(n)`, `ceil(n)` | Rounding |

### Adapted

These exist but their signatures differ slightly from Neo4j, or they are rewritten internally.

| Function | Notes |
|---|---|
| `substring(s, start)` / `substring(s, start, length)` | Both arities supported |
| `left(s, n)` / `right(s, n)` | First / last `n` characters |
| `size(s)` | **String length only.** It does not give the size of a list or map |
| `coalesce(a, b, …)` | First non-null argument |
| `id(v)` | The node's external id. Requires a bare pattern variable — `id(p)`, not `id(p.x)` |
| `type(r)` | The edge's type. Requires a bare pattern variable |

`id()` and `type()` are the only supported way to get at an element's identity from an ordinary `RETURN`,
since returning a whole node is not permitted.

### Not available

`labels(n)`, `keys(n)` and `properties(n)` are recognised but rejected: all three return a list or map, and
there is no list-valued type yet. `RETURN GRAPH` exposes labels and properties if you need them
([06-language-reference.md](06-language-reference.md)).

## The `stroom.` namespace

Sixty-seven functions, called with the `stroom.` prefix. These are Stroom's own expression functions, so
they behave identically here and in a dashboard.

### Strings

`upperCase` · `lowerCase` · `substring` · `substringBefore` · `substringAfter` · `replace` ·
`stringLength` · `concat` · `indexOf` · `lastIndexOf` · `contains` · `toString` · `decode` · `encodeUrl` ·
`decodeUrl` · `hash`

### Arithmetic

`add` · `round` · `floor` · `ceiling` · `negate` · `abs` · `sqrt` · `sign` · `exp` · `log`

### Type conversion and tests

`toBoolean` · `toDouble` · `toFloat` · `toInteger` · `toLong` · `typeOf` · `isNull` · `isValue` ·
`isNumber` · `isString` · `isBoolean`

### Conditional

`if` · `case` · `match`

### Dates and durations

`formatDate` · `parseDate` · `formatDuration` · `parseDuration` · `now` · `isWeekend`

### Time bucketing

Three families of seven, for grouping timestamps into intervals:

| | Second | Minute | Hour | Day | Week | Month | Year |
|---|---|---|---|---|---|---|---|
| **floor** | `floorSecond` | `floorMinute` | `floorHour` | `floorDay` | `floorWeek` | `floorMonth` | `floorYear` |
| **round** | `roundSecond` | `roundMinute` | `roundHour` | `roundDay` | `roundWeek` | `roundMonth` | `roundYear` |
| **ceiling** | `ceilingSecond` | `ceilingMinute` | `ceilingHour` | `ceilingDay` | `ceilingWeek` | `ceilingMonth` | `ceilingYear` |

These are the most useful functions here, because "activity per hour" is the commonest analytic question
and there is no other way to express it:

```cypher
MATCH (u:User {id: 'alice'})-[r:ACCESSED]->(f:File)
RETURN stroom.floorHour(stroom.parseDate(r.timestamp)) AS hour, count(f) AS accesses
ORDER BY hour
```

Note this depends on `r.timestamp` having been stored in a parseable form at ingest — see
[03-ingest.md](03-ingest.md).

### Deliberately excluded

Stroom functions tied to a dashboard or a user context — annotations, links, the current user, state
lookups, file IO — are not exposed, because they have no meaning inside a graph traversal. So are Stroom's
bare `year`/`month`/`day` functions, which truncate the *current* time rather than extracting a component
from a value, and would be badly misleading under those names.

## Working with string properties

Because all property values are strings ([03-ingest.md](03-ingest.md)), conversion functions do more work
here than in Neo4j.

```cypher
-- numeric comparison on a string property
MATCH (f:File {name: 'salaries.xlsx'}) WHERE toInteger(f.size) > 1000 RETURN f.path

-- a date stored as ISO-8601
MATCH (u:User {id: 'alice'})-[r:ACCESSED]->(f:File)
RETURN stroom.formatDate(stroom.parseDate(r.when), 'yyyy-MM-dd') AS day, count(f) AS n
```

One caveat worth internalising: **conversion happens per row, during evaluation.** It cannot help the
anchor find its starting nodes, so `WHERE toInteger(n.id) = 42` still scans. If you need to filter on a
value efficiently, store it in the form you will match on ([10-limits.md](10-limits.md)).

## Errors you may see

| Message | Cause |
|---|---|
| `'X' is a Stroom extension - call it as stroom.X(...)` | Missing the `stroom.` prefix |
| `'stroom.X' is not a recognised Stroom function (available: …)` | Not in the 67 |
| `unknown function namespace 'X' - use a bare Cypher function or the stroom.* namespace` | Only `stroom.` exists |
| `X() returns a list/map, which needs the list-valued Val type (a later phase)` | `labels()`, `keys()`, `properties()` |
| `an aggregate or before()/after() cannot be an argument to a scalar function` | e.g. `toString(count(c))` |
| `X(...) requires a bare pattern variable, e.g. X(a)` | `id()`/`type()` given a property access |
| `substring takes 2 or 3 arguments: substring(string, start[, length])` | Wrong arity |

## Next

- [06-language-reference.md](06-language-reference.md) — the syntax around these functions
- [08-analysis-examples.md](08-analysis-examples.md) — worked queries
- [10-limits.md](10-limits.md) — why conversion does not help the anchor
