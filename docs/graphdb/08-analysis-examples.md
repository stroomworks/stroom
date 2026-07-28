# Worked analysis examples

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** analysts.
**Scope:** end-to-end examples over two datasets, in both the table and graph views.
**Companion documents:** [06-language-reference.md](06-language-reference.md) (syntax),
[07-functions.md](07-functions.md) (functions), [05-querying.md](05-querying.md) (the two views).

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

> **Provenance of the results below.** Every query in [Part A](#part-a--the-pole-dataset) was **executed
> against a live Stroom instance** on 2026-07-28, and the outputs shown are what came back. The queries in
> [Part B](#part-b--stroom-event-data) are written against the model produced by
> [04-event-logging-xslt.md](04-event-logging-xslt.md) — that transform was verified, but these queries
> have **not** been run against a populated graph, so treat them as worked patterns rather than confirmed
> output.

---

## Part A — the POLE dataset

[POLE](https://github.com/neo4j-graph-examples/pole) (Person, Object, Location, Event) is Neo4j's crime
investigation example. A representative subset — 14 nodes, 17 edges — was loaded into a Graph DB to
produce everything below.

The model: `Person`, `Officer`, `Crime`, `Location` and `Object` nodes, connected by `PARTY_TO`,
`INVESTIGATED_BY`, `INVOLVED_IN`, `OCCURRED_AT`, `KNOWS` and `CURRENT_ADDRESS`.

Two elements are dated in the future relative to the rest (a drugs crime `c4` and its edges, from
2026-06-01) specifically so the temporal queries have something to reveal.

### Who was involved in what

The most basic traversal: from a person to their crimes.

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type
```

| `c.type` |
|---|
| Drugs |
| Drugs |

Two rows because Powell is party to two separate drugs crimes. Note the anchor carries both a label and a
property, so this is an index seek rather than a scan ([10-limits.md](10-limits.md)).

### Reversing the direction

Traversal is equally cheap either way, so ask the same question from the other end. Which officer
investigated the burglary:

```cypher
MATCH (c:Crime {type: 'Burglary'})-[:INVESTIGATED_BY]->(o:Officer) RETURN o.surname
```

| `o.surname` |
|---|
| Morse |

And everything a given officer is working on, using an incoming edge:

```cypher
MATCH (o:Officer {surname: 'Larive'})<-[:INVESTIGATED_BY]-(c:Crime) RETURN c.type
```

| `c.type` |
|---|
| Drugs |
| Drugs |
| Drugs |

Three cases, all drugs. Add `DISTINCT` to collapse them:

```cypher
MATCH (o:Officer {surname: 'Larive'})<-[:INVESTIGATED_BY]-(c:Crime) RETURN DISTINCT c.type
```

| `c.type` |
|---|
| Drugs |

### Filtering with WHERE

Narrow to open cases only:

```cypher
MATCH (o:Officer {surname: 'Larive'})<-[:INVESTIGATED_BY]-(c:Crime)
WHERE c.last_outcome = 'Under investigation'
RETURN c.type
```

| `c.type` |
|---|
| Drugs |
| Drugs |

Two of Larive's three cases are still open.

### Following a chain

Two hops: from an object, through the crime it was involved in, to where that crime happened.

```cypher
MATCH (o:Object {description: 'Knife'})-[:INVOLVED_IN]->(c:Crime)-[:OCCURRED_AT]->(l:Location)
RETURN l.address
```

| `l.address` |
|---|
| 5 Baker Street |

This is the kind of question that becomes a multi-way join in a relational model and stays one line here.

### Relationships of unknown depth

The classic graph question — who is reachable through friend-of-a-friend links:

```cypher
MATCH (p:Person {surname: 'Freeman'})-[:KNOWS*1..2]->(f:Person) RETURN f.surname
```

| `f.surname` |
|---|
| Powell |
| Walker |

Freeman knows Powell directly; Walker is two hops away. Compare with a single undirected hop:

```cypher
MATCH (p:Person {surname: 'Freeman'})-[:KNOWS]-(f:Person) RETURN f.surname
```

| `f.surname` |
|---|
| Powell |

Remember that the upper bound is mandatory, and that the exploration budget is per starting node — so
widening the range or broadening the anchor multiplies the work ([10-limits.md](10-limits.md)).

### Counting and ranking

Crime totals by type — POLE's own opening question:

```cypher
MATCH (c:Crime) RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC
```

| `crime_type` | `total` |
|---|---|
| Drugs | 3 |
| Burglary | 1 |

Note the label-only anchor (`(c:Crime)` with no property), which scans rather than seeks. That is
acceptable here because we genuinely want every crime, but it is the shape to avoid on a large graph.

Order by the **alias** (`total`), not by repeating `count(c)` — the latter is rejected.

Where crimes happen most:

```cypher
MATCH (l:Location)<-[:OCCURRED_AT]-(c:Crime)
RETURN l.address AS address, count(c) AS total ORDER BY total DESC
```

| `address` | `total` |
|---|---|
| 1 Coronation Street | 2 |
| 5 Baker Street | 1 |

A bare count over everything:

```cypher
MATCH (c:Crime) RETURN count(c) AS total_crimes
```

| `total_crimes` |
|---|
| 4 |

### Gathering values

There is no `collect()` — it is rejected rather than returning a comma-joined string
([07-functions.md](07-functions.md#aggregates)). Use `RETURN DISTINCT` to get the same information as one row per
value:

```cypher
MATCH (o:Officer {surname: 'Larive'})<-[:INVESTIGATED_BY]-(c:Crime)
RETURN DISTINCT o.surname AS officer, c.type AS crime_type
```

| `officer` | `crime_type` |
|---|---|
| Larive | Drugs |

> Larive investigated only drugs crimes, so there is one row. An officer spanning several crime types would
> produce one row each — which is the shape you want anyway if the result is going into a table, a join, or
> anything that would otherwise have to split a string back apart.

### Two-stage queries with WITH

`WITH` projects an intermediate result, which is how you filter on an aggregate:

```cypher
MATCH (c:Crime {type: 'Drugs'}) WITH c.last_outcome AS o RETURN o
```

| `o` |
|---|
| Under investigation |
| Charged |
| Under investigation |

### Missing properties

Absent properties return null rather than failing, which makes exploring an unfamiliar graph safe:

```cypher
MATCH (o:Officer {surname: 'Morse'}) RETURN o.surname, o.nhs_no
```

| `o.surname` | `o.nhs_no` |
|---|---|
| Morse | *(empty)* |

Officers have no `nhs_no`; the column comes back empty instead of erroring.

### Time travel — what Neo4j cannot do

This is Graph DB's distinctive capability. The same query, twice.

As things stand now:

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type
```

| `c.type` |
|---|
| Drugs |
| Drugs |

And as things stood at the start of 2021:

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime)
AS OF datetime('2021-01-01T00:00:00Z')
RETURN c.type
```

| `c.type` |
|---|
| Drugs |

One row, because the second crime is dated 2026-06-01 and did not exist at that instant. Nothing was
filtered out and no history table was joined — the query simply saw the graph as it was.

A window works the same way:

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime)
BETWEEN datetime('2020-01-01T00:00:00Z') AND datetime('2021-01-01T00:00:00Z')
RETURN c.type
```

| `c.type` |
|---|
| Drugs |

Time composes with aggregation, so "how did this count change" is one query run at two instants — or a
single `DIFF`.

### What POLE asks that Graph DB cannot answer

Being concrete about the boundary. POLE's tutorial also asks for:

- **Crimes within 500m of an address** — needs `point()` and `point.distance()`. No spatial support.
- **Shortest paths between suspects** — needs `allShortestPaths` and path variables. The Explore tab's
  browser-side shortest path is not equivalent.
- **"People not themselves criminal who know many criminals"** — needs a pattern predicate
  (`WHERE NOT (p)-[:PARTY_TO]->(:Crime)`). `EXISTS { … }` covers some of this shape but not negation
  inside a broader expression.
- **Triangle counting and centrality** — needs a graph-algorithms library.

See [09-gql-and-neo4j.md](09-gql-and-neo4j.md) for the full comparison.

## Part B — Stroom event data

These queries assume a graph built by the translation in
[04-event-logging-xslt.md](04-event-logging-xslt.md): `User`, `Device`, `Session`, `File` and
`Application` nodes joined by `USED`, `AUTHENTICATED_ON`, `STARTED_SESSION`, `SESSION_ON`, `ACCESSED`,
`HOSTED_ON` and `CONNECTED_TO`.

> Not executed against a populated graph — see the note at the top of this file.

### What did a user touch?

```cypher
MATCH (u:User {id: 'alice'})-[r:ACCESSED]->(f:File)
RETURN f.path, r.action, r.outcome
```

The audit question, one hop. Because the action and outcome ride on the edge, a single traversal answers
what was touched, how, and whether it worked.

### Who else used this machine?

```cypher
MATCH (d:Device {hostName: 'ws-001.example.org'})<-[:AUTHENTICATED_ON]-(u:User)
RETURN DISTINCT u.id
```

Shared-device analysis — anchored on the device, walking backwards to users.

### Failed access attempts, ranked

```cypher
MATCH (u:User)-[r:ACCESSED]->(f:File)
WHERE r.outcome = 'FAILURE'
RETURN u.id AS user, count(f) AS failures
ORDER BY failures DESC
```

The `User` anchor is label-only here, so this scans. Acceptable for a report; if you run it often, anchor
on something selective instead.

### Who touched a sensitive file?

```cypher
MATCH (f:File {path: '/finance/salaries.xlsx'})<-[r:ACCESSED]-(u:User)
RETURN u.id, r.action
```

Anchoring on the file rather than the user is both the natural question and the faster query — the file
path is a selective indexed value.

### Indirect connections between users

```cypher
MATCH (u:User {id: 'alice'})-[:AUTHENTICATED_ON*1..2]-(other:User)
RETURN DISTINCT other.id
```

Users who share a device with Alice, or share a device with someone who does. This is the shape that
justifies a graph: the equivalent SQL is a self-join whose depth is fixed at authoring time.

### Activity over time

```cypher
MATCH (u:User {id: 'alice'})-[r:ACCESSED]->(f:File)
RETURN stroom.floorHour(stroom.parseDate(r.when)) AS hour, count(f) AS accesses
ORDER BY hour
```

Requires the translation to have written a parseable timestamp property onto the edge. The bucketing
functions are in [07-functions.md](07-functions.md).

### What changed this week?

```cypher
MATCH (u:User)-[:ACCESSED]->(f:File)
DIFF FROM datetime('2026-07-01T00:00:00Z') TO datetime('2026-07-08T00:00:00Z')
RETURN u.id, f.path, changeKind
```

New access relationships appear as `ADDED`. For an access-control review this is a much more direct
question than diffing two exports.

## Part C — working in the graph view

Techniques that pay off, in rough order of usefulness.

**Start broad, then narrow.** Open with `MATCH (n) RETURN GRAPH` to see the shape, or use **Discover** to
get clickable per-label previews. Once you know the labels and edge types, write a specific query.

**Grow from a known point.** Anchor on one entity, then right-click → **Expand neighbours** repeatedly.
Expansion merges rather than replaces and keeps existing node positions, so the picture builds around what
you were already looking at. Capped at 50 neighbours per expansion.

**Use the legend as a filter.** Clicking a label or edge type in the legend hides that kind wholesale —
much faster than editing the query, and reversible.

**Turn on Size by degree** to find hubs. On event data the hubs are usually shared devices or service
accounts, which is often exactly what you are looking for.

**Set the node caption** to the property that identifies things in your dataset. The default is the label
or id, which is rarely what you want to read.

**Pin the nodes you care about** before expanding, so re-layout does not move them.

**Export** when you are done: PNG for a report, CSV or JSON of the element table to carry on elsewhere.

## Part D — time travel in the UI

The **Time travel** toggle drives the temporal clauses without writing them.

Set **From** and **To**, then drag the slider: each of its 20 positions re-runs the query `AS OF` that
instant. **Play** animates it, which is the quickest way to see a graph grow.

**Compare** runs a `DIFF` between the window's ends and colours the result by change kind — added, removed,
modified, unchanged. Unchanged elements are kept in the graph view because they are the context that makes
the changes legible.

Expanding a node while time-travelling stays at the same instant, so you can explore a past state
consistently rather than accidentally mixing it with the present.

## Part E — choosing the view

| Question shape | View | Why |
|---|---|---|
| "How many…", "top N…" | Data | Aggregates are rows, not shapes |
| "What is connected to X?" | Explore | The answer is a shape |
| "Show me everything about X" | Explore, then Focus | |
| "Which of these 5,000 rows…" | Data | The diagram caps at 2,000 elements |
| "What changed?" | Explore + Compare | Change kinds are colour-coded |
| "I don't know what's in here" | Explore + Discover | |
| Anything you need to export or hand on | Data | |

A workflow that works well: explore in the graph view until you understand the shape, then write the
precise question as an aggregate and read it in the Data tab.

## Next

- [06-language-reference.md](06-language-reference.md) — the full language
- [09-gql-and-neo4j.md](09-gql-and-neo4j.md) — what does not port from Neo4j
- [10-limits.md](10-limits.md) — keeping these queries fast
