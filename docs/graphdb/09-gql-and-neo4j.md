# Comparison with ISO GQL and Neo4j

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness).
**Audience:** evaluators, and anyone porting queries from Neo4j.
**Scope:** how Graph DB's language relates to the ISO GQL standard and to Neo4j's Cypher, and what that
means in practice. Canonical for the GQL conformance summary.
**Companion documents:** [06-language-reference.md](06-language-reference.md) (what is supported),
[12-future-work.md](12-future-work.md) (what may change).

*Facts verified on 2026-07-29 against branch `sw-query-optimiser`. Every Cypher example here is compiled by
`TestDocumentationQueries` on each build, which is what caught the `ORDER BY count(c)` example still being
marked as rejected after it began working.*

---

## The short version

Graph DB implements a **read-only subset of openCypher**, not ISO GQL. The two languages share ancestry —
GQL (ISO/IEC 39075:2024) was heavily influenced by Cypher — but they differ in syntax and Graph DB targets
neither completely.

## Conformance against GQL's mandatory areas

Comparing by capability rather than syntax, against the mandatory feature areas of ISO/IEC 39075:2024:

| GQL mandatory area | Status |
|---|---|
| Pattern matching (`MATCH`, `OPTIONAL MATCH`) | Supported — `OPTIONAL MATCH` only as a single-hop continuation |
| Pattern elements (nodes, edges, direction, labels, property maps) | Supported |
| Variable-length paths | Supported, bounded only. The upper bound is mandatory |
| `WHERE` filtering | Supported, including `IN`, `IS NULL`, string operators and `EXISTS { }` |
| Result projection (`RETURN`, `DISTINCT`, aliases) | Supported — but not a bare node or `RETURN *` |
| Ordering and paging (`ORDER BY`, `LIMIT`) | **Partial** — `ORDER BY` and `LIMIT` yes, **`SKIP` is rejected**, so there is no paging |
| Comparison predicates, `IS NULL` / `IS NOT NULL` | Supported |
| `CASE` value expressions | Supported, both simple and searched forms |
| `EXISTS { pattern }` | Supported, for one correlated typed hop |
| Aggregate functions | Supported — `count`, `sum`, `avg`, `min`, `max`. No `collect` |
| Arithmetic expressions | Supported: `+ - * / ^`. Modulo `%` parses but does not render |
| Scalar, string and date functions | Supported — 21 bare Cypher names (14 mapped one-to-one, 7 signature-adapted) plus 67 in the `stroom.` namespace ([07](07-functions.md)) |
| Mandatory data types (string, boolean, integer, float) | Supported — `string`, `long`, `double`, `boolean` and `dateTime` are declared per property at ingest ([03-ingest.md](03-ingest.md#property-value-types)). A property left undeclared is a string |
| Set operations (`UNION`, `UNION ALL`) | Supported |
| Graph selection / `CURRENT_GRAPH` | **Partial** — one implicit graph per query, chosen by a leading `from "Name"` clause; no in-query switching |
| Path variables and path finding | Not supported |
| `SELECT` (GQL's tabular statement form) | Not supported — this is Cypher, not GQL syntax |
| Data modification (`INSERT` / `SET` / `DELETE`) | Not supported, by design |
| Catalogue and schema statements | Not supported — the store is schemaless |
| Sessions and transactions | Not supported — an embedded, read-only engine |
| **Temporal querying** | **Beyond both standards** — no GQL or Cypher equivalent |

The honest summary: **the traversal core is there, the analytics layer largely is not, and writes never
will be from the query language.**

## Where Graph DB goes beyond both

Worth stating first, because it is the reason the subset exists at all.

Every node and edge version carries a `validFrom`, and the language exposes that directly:

```cypher
MATCH (p:Person {surname: 'Powell'})-[:PARTY_TO]->(c:Crime)
AS OF datetime('2021-01-01T00:00:00Z')
RETURN c.type
```

Neither Cypher nor GQL has an equivalent. In Neo4j, history is something you model yourself — usually with
`validFrom`/`validTo` properties and predicates on every hop, which is verbose and easy to get wrong.
`DIFF FROM … TO …`, which classifies elements as added, removed, modified or unchanged, has no counterpart
at all.

If temporal analysis is central to what you are doing, this is the trade the subset is buying.

## Porting from Neo4j

### What ports unchanged

Simple anchored traversals, which is more than it sounds — **provided no label, edge type or property name
collides with a grammar keyword**. `Order` as a label and `CONTAINS` as an edge type are the two that bite in practice, `ORDER BY` and `CONTAINS` both being part of the language; see
[06-language-reference.md](06-language-reference.md#names-that-will-not-parse).

```cypher
MATCH (p:Person {name: 'Alice'})-[:KNOWS]->(f:Person) RETURN f.name

MATCH (a:Account {id: '123'})<-[:OWNS]-(c:Customer) RETURN c.name, c.email

MATCH (u:User {id: 'x'})-[:MEMBER_OF*1..3]->(g:Group) RETURN DISTINCT g.name

MATCH (b:Basket {id: '9'})-[:HOLDS]->(i:Item)-[:MADE_BY]->(m:Maker) RETURN m.name
```

Aggregation ports unchanged, including ordering by the aggregate call itself:

```cypher
MATCH (c:Crime) RETURN c.type, count(c) AS total ORDER BY count(c) DESC

-- Ordering by the alias works too, and means the same thing
MATCH (c:Crime) RETURN c.type AS crime_type, count(c) AS total ORDER BY total DESC
```

The aggregate must be one the `RETURN` produces — `ORDER BY sum(c.value)` over a `RETURN` that counts is
rejected rather than silently sorting on nothing.

### What needs rewriting

| Neo4j | Graph DB | Fix |
|---|---|---|
| `RETURN n` | Rejected | Name the properties, or use `RETURN GRAPH` |
| `RETURN *` | Rejected | List the columns |
| `SKIP 10 LIMIT 10` | `SKIP` rejected | `LIMIT` only; no pagination |
| `MATCH (a), (b)` | Rejected | One `MATCH`, one pattern |
| `MATCH (a) MATCH (b)` | Rejected | Combine, or use `OPTIONAL MATCH`/`WITH` |
| `-[:A\|B]->` | Rejected | Separate queries plus `UNION` |
| `-[:R*]->` | Rejected | Give the upper bound: `-[:R*1..5]->` |
| `-->` as an access path | Rejected | Name the edge type |
| `WHERE NOT (a)-[:R]->(b)` | Rejected | `EXISTS { … }` where the shape allows |
| `labels(n)`, `keys(n)` | Rejected | Use `RETURN GRAPH` |
| `collect(x)` as a list | **Rejected** — fails to compile | `RETURN DISTINCT` for one row per value, or aggregate with `count` |

### What has no equivalent

- **Writes** — `CREATE`, `SET`, `MERGE`, `DELETE`. Data enters only through pipelines.
- **Path finding** — `shortestPath`, `allShortestPaths`, path variables (`p = (a)-->(b)`), `RETURN p`.
- **Graph algorithms** — no GDS equivalent: no centrality, community detection or triangle counting
  server-side. The Explore tab computes degree, pageRank and betweenness in the browser over whatever is
  currently drawn, which is a visual aid rather than an analytic result.
- **Spatial** — no `point()`, no `point.distance()`.
- **Procedures** — no `CALL`, no `db.schema.visualization()`. The Explore tab's **Discover** panel serves
  the schema-inspection need.
- **List and map values** — no `UNWIND`, no comprehensions, no map projections.

### On scale, Neo4j is closer than it looks

A Neo4j user may assume they are giving up scalability by moving to Graph DB. On the query language, yes.
On write scaling, less than expected: Neo4j's cluster architecture elects a **single leader** that applies all
writes, with read replicas adding read capacity only, so write throughput scales vertically there too.
Sharding via Fabric exists but requires queries to be written with the shard layout in mind.

**Graph DB also replicates whole copies now.** Every node named in `graphdb.nodeList` holds the entire graph
and can complete any traversal locally ([02-architecture.md](02-architecture.md#how-a-graph-spans-a-cluster)).
Neo4j's implementation is mature and Graph DB's is new, but the shape is the same, and for the same reason:
a traversal crosses whatever boundary you draw, so replicating whole beats partitioning.

The differences that remain are **maturity** and **capacity**. Neo4j has no fixed per-database size ceiling;
a Graph DB store is capped at creation, and because every node holds a whole copy, one graph can never exceed
one node's disk. A survey of how the wider market handles this is in
[11-operations.md](11-operations.md#how-other-graph-databases-handle-this).

### Behavioural differences that will not raise an error

These are the dangerous ones, because the query runs and returns something plausible.

| Difference | Consequence |
|---|---|
| **`collect()` is rejected** | A ported query using it fails to compile, with a message saying why. It used to return a comma-joined string and no error, which was worse |
| **An undeclared property is a string** | Declare `type="long"` or `type="double"` at ingest to get a number back. Ordering is *not* the hazard — Stroom compares strings numeric-first, so `"9"` already sorts before `"10"` — the hazard is that anything reading the value back sees text |
| **Equality on decimals is exact** | As it is everywhere in Stroom. A value computed before ingest may not match a literal that looks identical, and returns no rows rather than an error |
| **A node version replaces, it does not merge** | Re-loading a node without a property removes it, rather than leaving the old value |
| **Deleted data stays visible to historical queries** | Correct behaviour, but surprising if you expect a delete to be final |
| **Variable-length cycles are guarded by node, not relationship** | Cypher forbids reusing a *relationship* within a path; Graph DB forbids revisiting a *node*. So `a→b→a→c` is returned by Neo4j and not by Graph DB. It returns fewer paths, silently. **Deliberate** — relationship uniqueness is combinatorial in a dense subgraph and would trip the path-state ceiling far more often. Re-check any ported variable-length query rather than trusting it ([06](06-language-reference.md#variable-length-hops)) |

**No longer on that list:** the whole-graph preview's 100-node cap. It still truncates — Neo4j has no such cap —
but it now reports a warning alongside the rows rather than looking complete ([10-limits.md](10-limits.md)).

### Structural differences

**Anchoring matters.** Neo4j's planner picks a start point and may reorder your pattern. Graph DB folds
strictly left to right from the first node pattern and never reorders, so the anchor you write is the
anchor it uses. Rewriting a pattern to start from an indexed value is the main performance lever
([10-limits.md](10-limits.md)).

**One query, one graph.** There is no `USE` clause and no cross-graph query. A query names its graph with a
leading `from "Name"` clause, or inherits it from the document it is run in.

**Read-only, always.** Not a temporary limitation: ingest is the pipeline's job, which keeps provenance,
permissions and reprocessing consistent with the rest of Stroom.

## Neo4j's example datasets

The Neo4j example graphs are a useful yardstick. None of them load directly — they ship as Neo4j dump files
or `LOAD CSV` scripts, and Graph DB has neither importer, so any dataset must be converted to
`graph-mutation:1` first ([03-ingest.md](03-ingest.md)).

Once loaded, roughly:

| Dataset | How it fares |
|---|---|
| **Movies** | Most queries port. Recommendation queries needing `collect()` as a list do not — they now fail loudly rather than returning a string |
| **POLE** | Structure and traversals port; counting ports with the alias edit. Spatial and path-finding queries do not — see [08-analysis-examples.md](08-analysis-examples.md) |
| **Northwind** | Aggregation-heavy; ports with alias edits |
| **Fraud detection** | Depends on path finding and shared-attribute rings — largely does not port |
| **Recommendations** | Depends on `collect()` lists and GDS similarity — does not port |

The pattern is consistent: **datasets whose questions are "what is connected to what" port well; datasets
whose questions are "score, rank or find the best path" do not.**

## Choosing between them

**Graph DB suits you if** your data already flows through Stroom, temporal analysis matters, your graphs
are modest, and your questions are traversal-shaped.

**Neo4j (or another mature graph database) suits you if** you need writes from the query language, path
finding or graph algorithms, spatial queries, large graphs, or production-grade operational guarantees —
see the [blockers](README.md#production-readiness).

They are not really competitors. Graph DB adds a graph-shaped view of data that is already in Stroom,
with a temporal dimension no general-purpose graph database offers.

## Next

- [12-future-work.md](12-future-work.md) — which of these gaps may close
- [06-language-reference.md](06-language-reference.md) — the supported language in full

### A note on sources

This comparison was originally derived from a clause-by-clause engineering analysis against
ISO/IEC 39075:2024, which also surveyed Neo4j's example-graph catalogue. That analysis has been absorbed
here and retired; where it and this page differed, this page was corrected against the code.
