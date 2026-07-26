# Stroom graph query engine vs. the GQL mandatory feature set

**Date:** 2026-07-26 · **Branch:** `sw-query-optimiser-graph-backend` · **Commit:** `b1c50565d5`
**Standard compared against:** ISO/IEC 39075:2024 (GQL — Graph Query Language)

---

## Read this first: what is (and isn't) being compared

Stroom's graph backend implements a **read-only subset of openCypher**. GQL
(ISO/IEC 39075:2024) is a **different language** — a full, read-write ISO
standard with its own syntax, catalog model, session model and transaction
model. The two share a common ancestry (both use ASCII-art `(node)-[edge]->()`
patterns, and GQL borrowed heavily from Cypher), but **Stroom does not parse GQL
syntax and makes no claim of GQL conformance.**

So this document is **not** a conformance audit. A conformance audit would score
zero on syntax grounds alone. Instead it is a **capability comparison**: for each
area the GQL standard marks *mandatory*, can a Stroom user express the equivalent
query intent today? That is the question a human actually cares about when asking
"how far are we from a standard graph query language?"

Two structural facts dominate everything below:

1. **Stroom's engine is read-only.** It matches patterns and projects results.
   GQL is a full CRUD language (`INSERT`, `SET`, `REMOVE`, `DELETE`) plus catalog
   and schema management. Those whole categories are **out of scope by design**,
   not partially-implemented gaps.
2. **Stroom is an embedded query overlay,** not a database server. GQL's
   mandatory *session* and *transaction* control statements (`START
   TRANSACTION`, `COMMIT`, `ROLLBACK`, session parameters) have no surface in a
   query-only engine — queries run inside a read transaction managed by the host.

With those framed, the interesting comparison is the **reading/querying core**,
where Stroom is genuinely strong.

---

## Executive summary

| GQL mandatory area | Stroom status |
|---|---|
| Pattern matching (`MATCH`, `OPTIONAL MATCH`) | ✅ Supported |
| Graph pattern elements (nodes, edges, direction, labels, property maps, paths) | ✅ Supported |
| Variable-length paths | ✅ Supported (bounded) |
| `WHERE` filtering | ✅ Supported (rich) |
| Result projection (`RETURN`, `DISTINCT`, aliases) | ✅ Supported |
| Result ordering & paging (`ORDER BY`, `SKIP`, `LIMIT`) | ✅ Supported |
| Comparison predicates, `IS NULL` / `IS NOT NULL` | ✅ Supported |
| `CASE` value expression | ✅ Supported (`CASE WHEN …` searched form and simple form) |
| `EXISTS { pattern }` subqueries | ✅ Supported (correlated single typed hop; `NOT EXISTS` too) |
| Aggregate functions (`count`, `sum`, `avg`, `min`, `max`) | ✅ Supported (+ `collect`) |
| Arithmetic value expressions | ✅ Supported (`+ - * / ^ %`) |
| Scalar / string / date functions | ✅ Supported (Stroom's 230+ function library) |
| Mandatory data types (string, bool, int, float) | ✅ Supported at the value level |
| `CURRENT_GRAPH` / graph selection | ⚠️ Single implicit graph; no in-query graph selection |
| `SELECT` statement (GQL's tabular form) | ❌ Not supported |
| Session management | ❌ Out of scope (embedded engine) |
| Transaction statements (`START TRANSACTION` / `COMMIT` / `ROLLBACK`) | ❌ Out of scope (host-managed read txn) |
| Data modification (`INSERT` / `SET` / `DELETE`) | ❌ Out of scope (read-only engine) |
| Catalog & schema (graph types, `CREATE GRAPH`, element types) | ❌ Out of scope (schemaless store) |

**Bottom line:** within the **read/query half** of GQL's mandatory surface,
Stroom covers the great majority of the capability — pattern matching, filtering,
projection, ordering/paging, aggregation, arithmetic and `CASE` are all present. The
distance to "a standard graph query language" is dominated by the **write half**
(data modification, catalog, sessions, transactions), which Stroom's architecture
deliberately does not address, plus a handful of query-side items (`EXISTS`
subqueries, `SELECT`, list/`UNWIND`).

---

## Detailed comparison by GQL mandatory subclause

GQL's mandatory features are the base language constructs the standard requires of
any conforming implementation (they carry no optional "Feature ID"). They are
organised by subclause of ISO/IEC 39075:2024. The mapping below follows the
subclause grouping summarised in the public conformance write-ups (see Sources).

### Subclause 7 — Session management · ❌ Out of scope
GQL requires session-level statements (session set/reset, session parameters).
Stroom is an embedded query engine invoked by the host application; there is no
user-facing session language. **Not applicable to a query-only overlay.**

### Subclause 8 — Transaction management (`START TRANSACTION`, `COMMIT`, `ROLLBACK`) · ❌ Out of scope
Queries execute inside a **read transaction** opened by the host (`PlanBEnv.read`
in the graph store). There is no in-query transaction control, and — being
read-only — no write transactions to commit or roll back.

### Subclause 11 — Object expressions (`CURRENT_GRAPH`) · ⚠️ Partial
Stroom queries run against a **single, implicit graph** (the selected graph
doc/store). There is no in-query notion of "the current graph" that can be
switched or referenced, because there is no multi-graph `USE` clause. The
capability is effectively "always the one graph you opened."

### Subclause 14.4 — `MATCH`, `OPTIONAL MATCH` · ✅ Supported
Both are implemented. `MATCH` supports fixed-length chains and bounded
variable-length paths; `OPTIONAL MATCH` extends a matched pattern with a single
optional hop, null-padding unmatched rows (Cypher's left-join semantics).
A label-only pattern such as `MATCH (n:Person) RETURN n.id` (no property
anchor) is supported — the engine scans the node store for nodes carrying the
label (bounded by the fail-loud in-memory ceiling). A *fully unlabelled* scan
(`MATCH (n) RETURN …`, no label at all) is still rejected — add a label.

### Subclause 14.9 — Order and paging (`ORDER BY`, offset/limit) · ✅ Supported
`ORDER BY` (multi-key, asc/desc), `SKIP n` and `LIMIT n` are all supported on the
top-level `RETURN`.

### Subclauses 14.10 / 14.11 — Primitive result / `RETURN` · ✅ Supported
`RETURN` with scalar items, `AS` aliases, `DISTINCT`, arithmetic expressions and
function calls. (Stroom also has a non-standard `RETURN GRAPH` for whole-subgraph
preview, which is an extension, not a GQL feature.)

### Subclause 14.12 — `SELECT` statement · ❌ Not supported
GQL's SQL-flavoured tabular `SELECT` form is not implemented (Cypher-family
engines generally express the same intent through `MATCH … RETURN`). The query
*intent* is reachable; the `SELECT` *syntax* is not.

### Subclause 16 — Graph pattern elements · ✅ Supported
Node patterns, edge patterns with direction (`->`, `<-`, undirected), label
predicates, inline property maps, path patterns, element-variable references and
pattern-scoped `WHERE` are all present. **Quantified path patterns:** bounded
variable-length (`-[:R*1..3]->`) is supported; the full GQL quantified-path-
pattern grammar is not.

### Subclauses 19–20 — Predicates & value expressions · ✅ Mostly supported
- **Comparison** `= <> < > <= >=` — ✅ (including field-vs-field, e.g. `a.x > b.y`)
- **`IS NULL` / `IS NOT NULL`** — ✅
- **`IN` list membership** — ✅
- **String predicates** `STARTS WITH` / `CONTAINS` / `ENDS WITH` / `=~` — ✅ (a Cypher extension beyond bare GQL)
- **`CASE`** — ✅ both the searched form (`CASE WHEN <cond> THEN … ELSE … END`, with `AND`/`OR`/`NOT` and `IS [NOT] NULL` conditions) and the simple form (`CASE <input> WHEN <value> THEN … END`); a missing `ELSE` yields null. (Also still reachable as the `stroom.case(...)` function.) *String predicates / `IN` inside a `CASE` condition are the only rejected sub-cases.*
- **`EXISTS { pattern }`** (existential subquery) — ✅ correlated `[NOT] EXISTS { (x)-[:T]->(y) }`: a single typed hop from an outer-bound node, with optional inner target labels/properties. *Deferred: multi-hop / inner-WHERE existence patterns.*
- **Aggregates** `count` / `sum` / `avg` / `min` / `max` — ✅ (plus `collect`, and `count(DISTINCT …)`)
- **Arithmetic** — ✅ `+ - * / ^ %` with correct precedence and parentheses

### Data types (mandatory minimal set: string, bool, int, float) · ✅ Supported
The engine's value model carries string, boolean, integer/long and
double/float values, which covers GQL's mandatory minimal type set at the value
level. (Stroom's type system is dynamic/schemaless rather than the declared,
catalogued types GQL assumes — see catalog below.)

### Data modification (`INSERT`, `SET`, `REMOVE`, `DELETE`) · ❌ Out of scope
The engine is **read-only**. Graph data is written through Stroom's ingest/
state-store pipeline, not through the query language.

### Catalog & schema (graph types, element types, `CREATE GRAPH`) · ❌ Out of scope
The underlying store is **schemaless** (properties are open per node/edge). GQL's
mandatory closed/open graph-type machinery, element-type names and label-set keys
have no counterpart, by design.

---

## Where the real gaps are (query-side, actionable)

Setting aside the deliberately out-of-scope write/catalog/session categories,
these are the query-side items closest to worth doing, roughly in value order:

1. **True list values + `UNWIND`.** Today `collect()` returns a delimited string,
   not a real list; `UNWIND`, `keys()`, `labels()`, `range()`, list slicing all
   wait on a first-class list value type threaded through the value model.
   *Higher effort — a cross-cutting change touching shared search/dashboard code.
   This is now the last remaining query-side gap of any size.*

*Recently closed:* the `CASE WHEN` expression (both forms), the modulo (`%`)
operator, the **label-only node scan** (`MATCH (n:Person) RETURN …`),
**`UNION` / `UNION ALL`**, and **`EXISTS { pattern }`** correlated subqueries are
now implemented — they previously headed this list.

None of these block the common analytic queries the engine is built for; they are
the standards-completeness tail.

*(This list predates the broader dataset survey below, which surfaces several
additional — and in practice more frequently used — gaps such as procedure
calls and path values; see
["Missing features observed in real-world Cypher content"](#missing-features-observed-in-real-world-cypher-content)
for the fuller, evidence-based picture.)*

---

## Worked example: the Neo4j "Movie Graph" tutorial

As a concrete check on the analysis above, every query in Neo4j's own
["Get started with Cypher"](https://neo4j.com/docs/getting-started/cypher/intro-tutorial/)
tutorial was tried against Stroom's grammar/compiler/engine. The tutorial builds
a small movies-and-actors graph, then walks through progressively richer
`MATCH`/`RETURN` queries. It's a good stress test precisely because it wasn't
written with Stroom in mind.

### Data loading — not possible (by design)
The whole "Create the Movie Graph" section (`CREATE CONSTRAINT ... IS UNIQUE`,
`MERGE (x:Label {...}) ON CREATE SET ...`, relationship writes) is out of scope:
no `MERGE`/`CREATE`/`SET`/`DELETE` exist in the grammar (read-only engine), and
`CREATE CONSTRAINT` is schema/DDL for a store that is deliberately schemaless.
This is the same "write half is out of scope" conclusion as the rest of this
document, just concretely instantiated.

### Query section — verdict per query

| Query (paraphrased) | Verdict | Why |
|---|---|---|
| `MATCH (tom:Person {name:"Tom Hanks"}) RETURN tom` | ⚠️ Partial | `MATCH` fine; `RETURN tom` (a bare node) isn't — no single-value representation for "a whole matched node" yet. `RETURN tom.name` works. |
| `MATCH (cloudAtlas:Movie) WHERE cloudAtlas.title = "Cloud Atlas" RETURN cloudAtlas` | ⚠️ Partial | Label-only scan + `WHERE` works; same bare-`RETURN` issue. |
| `MATCH (p:Person) RETURN p.name LIMIT 10` | ✅ Works as-is | |
| `MATCH (nineties:Movie) WHERE nineties.released > 1990 AND nineties.released < 2000 RETURN nineties.title` | ✅ Works as-is | |
| `MATCH (tom:Person {name:"Tom Hanks"})-[r]->(tomHanksMovies:Movie) RETURN tom,r,tomHanksMovies` | ❌ Not possible | `-[r]->` has **no relationship type** — Stroom's edges are stored per-type-keyed, so an untyped edge pattern has no access path (throws at execution). Bare-variable `RETURN` compounds it. |
| `MATCH (cloudAtlas:Movie {title:"Cloud Atlas"})<-[:DIRECTED]-(directors) RETURN directors.name` | ✅ Works as-is | Typed edge, incoming direction, unlabelled target, property-access `RETURN` — squarely in scope. |
| `MATCH (tom)-[a:ACTED_IN]->(m:Movie)<-[b:ACTED_IN]-(coActors) RETURN tom, m, a, b, coActors` | ⚠️ Partial | The two-hop typed-edge `MATCH` works; only the all-bare-variable `RETURN` fails. `RETURN tom.name, m.title, coActors.name` would work today. |
| `MATCH (people:Person)-[relatedTo]-(:Movie {title:"Cloud Atlas"}) RETURN people.name, type(relatedTo), relatedTo` | ❌ Not possible | Untyped **and** undirected edge — same per-type-store limitation, fails before `RETURN` is reached. |
| `MATCH (bacon:Person {name:"Kevin Bacon"})-[*1..3]-(a:Person) RETURN DISTINCT bacon, a` | ❌ Not possible | Untyped variable-length hop — same limitation applies to `VarLengthExpand` too; plus bare-variable `RETURN`. |
| `MATCH p = (bacon)-[*1..3]-(a:Person) RETURN DISTINCT bacon, a, p` | ❌ Not possible | No `p = pattern` path-variable syntax exists in the grammar at all, and no path value type to return. |
| `MATCH p = shortestPath((bacon)-[*]-(meg)) RETURN p` | ❌ Not possible | `shortestPath()` isn't implemented — no path-finding algorithms. |
| `MATCH (n) DETACH DELETE n` | ❌ Not possible | No `DELETE`; read-only engine. |
| `MATCH (n) RETURN count(*)` | ❌ Not possible | A **fully unlabelled** `MATCH (n)` is explicitly rejected ("requires at least one label") — label-only scans work (`MATCH (n:Person)`), a zero-label whole-store scan doesn't. |

### The pattern underneath
Three limitations account for nearly every "not possible" above, and none of
them are in the query-side gap list (they're structural, not missing features
to be added incrementally):
1. **Read-only** — no write/DDL surface (kills the load section + cleanup).
2. **No untyped-edge or path-value support** — edges are stored per-type, so
   `-[r]->` / `-->` / `[*]` with no relationship type has no access path, and
   there is no path (`p = ...`) value or `shortestPath()`.
3. **No "whole node/relationship" return value** — every `RETURN` item must
   name a property (`x.prop`) or a scalar function; a bare `RETURN tom` isn't
   representable yet.

Every query that avoids those three — anchored/labelled property lookups,
typed fixed-length hops in any direction, `WHERE`, aggregates, `LIMIT` — works
as demonstrated, including the "Directors of Cloud Atlas" query completely
unchanged.

---

## Broader survey: Neo4j's example-graph catalogue

One tutorial is a thin slice. Neo4j publishes a
[catalogue of ~17 example datasets](https://neo4j.com/docs/getting-started/appendix/example-data/)
used across its own guides, sandboxes and documentation — a much broader sample
of "what real Cypher looks like in the wild." Every dataset with a genuine,
independently-fetchable source (a linked GitHub repository) was pulled and read
in full: the import script, the README, and every "browser guide"/`.adoc`
walkthrough document, extracting every distinct Cypher query shown.

**Methodology and coverage.** 11 of the catalogue's datasets link to a GitHub
repository and were fully reviewed this way: **Northwind**, **StackOverflow**,
**Movie recommendations**, **Neoflix**, **Crime investigation (POLE)**,
**FinCEN**, **Panama/Paradise Papers**, **Game of Thrones**, **Twitter**,
**London public transportation**, and **IT management (network-management)**.
The remaining 6 — **Healthcare analysis**, **BBC recipes**, **UK companies**,
**Airbnb listings**, **Football transfers**, **WordNet** — are reachable only
via a live demo-server bolt connection or a Neo4j Browser `:guide` command, not
a fetchable page, so they could not be independently verified and are not
covered below (no claims are made about them one way or the other). ("Movies"
on the catalogue page links, apparently by a documentation error, to the
`recommendations` repo rather than a dedicated movies repo; the actual Movies
dataset is the one already covered in the worked example above.)

### Universal finding: none of these are loadable

Every one of the 11 reviewed datasets is distributed as either a **write-Cypher
script** (`MERGE`/`CREATE ... SET`, sometimes generated procedurally with
`UNWIND`/`FOREACH`/`CASE` as in `network-management`'s 8,000-machine synthetic
datacenter), a **`LOAD CSV` import** (Northwind, the CSV variant of FinCEN,
London transport), an **APOC JSON/REST import** (`apoc.load.json`/
`apoc.load.jsonArray`, used by StackOverflow, Game of Thrones, and FinCEN's JSON
variant), or a **binary Neo4j dump file** loaded via `neo4j-admin database load`
(the fallback/primary path for nearly all of them). None of these mechanisms
runs on a read-only engine — this simply re-confirms, at a larger sample size,
that Stroom's Cypher layer cannot load *any* of these datasets via their
published scripts; graph data has to arrive through Stroom's own ingest
pipeline instead.

### What each dataset's queries actually exercise

| Dataset | Notable query-language features demonstrated |
|---|---|
| **Northwind** | Aggregation (`SUM`, `collect(distinct …)`), a variable-length category hierarchy (`[:PARENT*0..]`), a named path variable (`path=(...)`). |
| **StackOverflow** | `allShortestPaths()`/`shortestPath()` over an untyped `[*]` wildcard, heavy APOC use (`apoc.load.json`, `apoc.date.format`, `apoc.create.vRelationship`), native procedure `db.schema.visualization`, negated-pattern predicates (`NOT (q)<-[:ANSWERED]-()`), `UNWIND`. |
| **Movie recommendations** | Extensive list comprehensions (Jaccard similarity), `UNWIND`, `reduce()`, `count{ }` subqueries, and (unrendered but authored) a full GDS pipeline — `gds.graph.project` → `gds.fastRP.mutate` → `gds.knn.write` — plus live `gds.similarity.cosine`/`gds.similarity.pearson`. |
| **Neoflix** | A thin app layer over the recommendations dataset; only plain CRUD (`MATCH`/`MERGE`/`DELETE`) and map-projection syntax (`m {.*, favorite: …}`) — no algorithms of its own. |
| **Crime investigation (POLE)** | The heaviest user of path features: `allshortestpaths()`/`shortestpath()`, multi-type variable-length edges (`[:KNOWS\|KNOWS_LW\|KNOWS_SN\|FAMILY_REL\|KNOWS_PHONE*..3]`), ~9 path variables, `WHERE NOT (pattern)` idioms, spatial `point()`/`point.distance()`, and a full GDS pipeline (`gds.graph.project`, `gds.triangleCount.stream`, `gds.betweenness.stream`, `gds.alpha.graph.project`). |
| **FinCEN** | `LOAD CSV`/`apoc.load.json` import, `OPTIONAL MATCH`, and (notebook-only) GDS **PageRank** and **Louvain** community detection over a Spark-loaded weighted graph. |
| **Panama/Paradise Papers** | The heaviest user of path variables (~15+ occurrences) and multi-type variable-length edges (`[:OFFICER_OF\|INTERMEDIARY_OF\|REGISTERED_ADDRESS*..10]`); a full **native full-text index** (`db.index.fulltext.createNodeIndex`/`queryNodes`, incl. fuzzy/boolean Lucene syntax) combined with graph pattern matching; `USING JOIN ON` planner hints; list comprehensions and list slicing; GDS PageRank. |
| **Game of Thrones** | APOC-driven JSON import (`apoc.load.jsonArray`, `apoc.map.*`, `apoc.convert.toMap`), `CASE` expressions, list comprehensions, `UNWIND`, one variable-length path with a path variable. No centrality/community-detection queries (those live in third-party repos this one merely links to). |
| **Twitter** | Notably plain — fixed-length typed-edge chains, aggregation, negated-pattern recommendation logic; no APOC, no GDS, no full-text, no `shortestPath`, no path variables, despite being a social graph. |
| **London public transportation** | Unfinished/WIP — `LOAD CSV` + `apoc.create.relationship` (to give each edge a *data-driven* relationship-type name) is as far as it goes; no route-finding query exists despite being a transit network. |
| **IT management (network-management)** | The clearest "impact analysis" narrative: a resilience story (cut a cable → `allShortestPaths` route count drops → add redundancy → recovers) built entirely on **native** `allShortestPaths()`/variable-length paths, no GDS; also `OPTIONAL MATCH`, list comprehensions, `CASE`, map-literal event construction, and one APOC call (`apoc.date.format`). |

### Missing features observed in real-world Cypher content

Consolidating every gap actually hit across all 11 datasets (not just guessed
at) gives a much more complete and better-prioritised picture than the
single-tutorial worked example above. Two tiers, matching the document's
existing "out of scope by design" vs. "could be added" framing:

**Tier 1 — structural (procedure/algorithm/index ecosystem; not a short-term roadmap item, closer in kind to the write/catalog/session gaps already out of scope)**

| Feature | Stroom status | Evidence (datasets) |
|---|---|---|
| **Any procedure call (`CALL ...`)** | ❌ `CALL` does not exist anywhere in the grammar — confirmed by inspection, not just absence of examples. This single gap blocks APOC, the Graph Data Science library, *and* Neo4j's own built-in procedures (`db.schema.visualization`, `db.index.fulltext.*`) all at once — it is the single most pervasive missing feature in this survey. | 8 of 11 datasets use `CALL` for *something*: StackOverflow, FinCEN, Game of Thrones, Panama Papers, POLE, London transport, network-management, recommendations (all APOC and/or GDS); StackOverflow, Panama Papers, and network-management also call native (non-APOC) procedures. Only Northwind, Neoflix, and Twitter use no procedure calls at all. |
| **Graph algorithms** (PageRank, Louvain, betweenness centrality, triangle count, kNN/FastRP embeddings, similarity functions) | ❌ No algorithm library of any kind — a consequence of no `CALL`, but even if `CALL` existed, none of these algorithms are implemented. | FinCEN (PageRank, Louvain), POLE (triangle count, betweenness), recommendations (kNN, FastRP, cosine/Pearson similarity), Panama Papers (PageRank). |
| **`shortestPath()` / `allShortestPaths()`** | ❌ Not implemented (this is core openCypher, not GDS) — no path-finding algorithm exists in the engine. | StackOverflow, POLE, Panama Papers, network-management (network-management's entire "impact analysis" story depends on it, run 4 times). |
| **Full-text search** (`db.index.fulltext.createNodeIndex`/`queryNodes`) | ❌ No index-management DDL and no full-text query syntax exposed through Cypher. | Panama Papers (extensively — simple, field-scoped, fuzzy, and boolean Lucene-syntax searches). |
| **Vector indexes / embedding search** | ❌ No vector index concept. *(Weak evidence of need: the recommendations dataset ships pre-computed embeddings, but no example query in any reviewed guide actually queries them via a vector index — this is a shipped-but-unused capability even in Neo4j's own materials.)* | recommendations (data only, no query). |
| **Spatial types/functions** (`point()`, `point.distance()`) | ❌ No point/spatial value type. | POLE (proximity search around a location). |
| **`LOAD CSV` / write Cypher / `CREATE CONSTRAINT`+index DDL** | ❌ Out of scope by design (read-only engine, schemaless store) — already documented above; reinforced by every single dataset in this survey. | All 11. |

**Tier 2 — query-language surface gaps (pure read-side Cypher features; could in principle be added to the subset)**

| Feature | Stroom status | Evidence (datasets) |
|---|---|---|
| **Path variables** (`MATCH p = (a)-[]->(b) RETURN p`) | ❌ No `p = pattern` syntax in the grammar at all, and no path value type to return — confirmed by inspection. This is arguably the single most common idiom across real Neo4j Browser guides, used purely to visualize "show me this relationship as a graph." | Northwind, POLE (~9 occurrences), Panama Papers (~15+, nearly every sample query), Game of Thrones, network-management (~6). |
| **Multi-type edge patterns** (`-[:TYPE_A\|TYPE_B\|TYPE_C]->`) | ❌ The grammar's `edgeDetail` rule allows only a single `edgeType=NAME` after the colon — no `\|`-alternation exists at all. Distinct from (and in addition to) the already-documented "fully untyped edge" gap. | POLE (5-type social-network traversal), Panama Papers (3-type ownership-chain traversal), recommendations (3-type similarity traversal), network-management (3-type infrastructure traversal). |
| **Untyped / wildcard-type edges** (`-->`, `-[r]-`, `[*]`) | ❌ Already documented — edges are stored per-type-keyed, so an untyped pattern has no access path. | Movie tutorial, StackOverflow (`allShortestPaths` over `[*]`), FinCEN (`(f)--(e)`). |
| **Whole node/relationship values** (`RETURN n`, `RETURN r`, `RETURN *`) | ❌ Already documented (no single-value representation for a matched node/edge). Newly confirmed: `RETURN *` itself has no grammar production — only an explicit item list or `RETURN GRAPH`. | Used constantly for graph-visualization results in literally every dataset surveyed. |
| **`UNWIND` + true list values** | ❌ Already the documented "last remaining query-side gap"; this survey shows it is also one of the most-used. `collect()` still returns a delimited string, not a real list. | StackOverflow (import), POLE (double-`UNWIND` idiom), recommendations (Pearson similarity), Game of Thrones (pagination), network-management (event ingestion via `UNWIND $events`). |
| **List comprehensions** (`[x IN list \| expr]`, `[x IN list WHERE cond]`) | ❌ No list value type at all (same root cause as `UNWIND`). | recommendations (Jaccard similarity, extensively), Panama Papers, Game of Thrones, network-management. |
| **Map-literal expressions as a value** (`RETURN {a:1, b:2}`, map projections `m {.*, x: y}`) | ❌ No map-literal expression grammar — property maps only exist inside a `MATCH` node/edge pattern, never as a general `RETURN`-able value. | network-management (event-simulation query), Neoflix (map-projection app-layer style). |
| **Chained / multi-hop `OPTIONAL MATCH`** | ⚠️ Partial — Stroom's `OPTIONAL MATCH` is deliberately v1-restricted to exactly *one* optional hop extending the prior mandatory `MATCH`'s terminal variable, with no `WHERE` of its own. Real guides sometimes chain a second `OPTIONAL MATCH` off the first's result. | network-management (OS-version-chain query: `OPTIONAL MATCH (v)<-[:PREVIOUS]-(vnext)` after an earlier `OPTIONAL MATCH`), FinCEN, recommendations. |
| **`USING JOIN ON` planner hints** | ❌ No planner-hint syntax of any kind. | Panama Papers ("joint involvement" queries). |
| **Restated label/property on an already-bound `EXISTS` anchor** | ⚠️ Partial — a nuance on Stroom's own `EXISTS` support (added this session): the anchor must be a *completely bare* variable, since it's required to already be bound by the outer `MATCH`. Real Cypher commonly (redundantly but validly) restates the label anyway, e.g. `WHERE NOT (p:Person)-[:PARTY_TO]->(:Crime)` where `p` was already matched as `:Person`. Dropping the restated label (`WHERE NOT (p)-[:PARTY_TO]->(:Crime)`) makes the same query work today. | POLE's "vulnerable persons" / "dangerous friends" queries. |

None of Tier 2 is a small effort — several (path values, list values) are the
same underlying value-model gap already flagged as cross-cutting — but they
are the concrete shape of "what would need to change" if Stroom's Cypher subset
were ever pushed further toward general-purpose graph exploration rather than
its current analytic-query focus.

---

## Stroom-native alternatives to `CALL`

Implementing general `CALL` syntax means building a procedure ecosystem — an
open-ended commitment (Tier 1 above). But every *specific* `CALL` use case
actually observed across the 11 surveyed datasets can be considered on its own,
the same way `RETURN GRAPH` is already a Stroom-native extension rather than
borrowed Cypher syntax. Some turn out to be cheap (the data they need is
already sitting in Stroom's stores); one category is a different kind of
engineering effort entirely. Ranked from cheapest to most expensive:

**Already solved — no gap at all.** `apoc.date.format`/`apoc.date.parse`
(StackOverflow, network-management, FinCEN) have direct Stroom-native
equivalents already wired into Cypher — `stroom.formatDate(...)`/
`stroom.parseDate(...)` (Phase 12, this branch) — just via an ordinary function
call instead of `CALL`. Nothing to build.

**Not applicable — out of scope by design, not a fresh gap.** The import-time
procedures (`apoc.load.json`/`apoc.load.jsonArray`, `apoc.util.sleep`,
`apoc.create.relationship` for data-driven relationship types — StackOverflow,
Game of Thrones, FinCEN, London transport) are all write/import concerns.
Stroom loads graph data through its own ingest pipeline, never through Cypher,
so there's nothing here to "replace" — this is the same read-only-engine
boundary already documented, just instantiated by the loading step rather than
the query step.

**Cheap — the data already exists, this is assembly, not new capability.**
Schema/metagraph discovery (`db.schema.visualization()`, `apoc.meta.graphSample()`
— StackOverflow, POLE, Panama Papers) answers "what labels and relationship
types exist in this graph, and how do they connect?" — pure exploration, no
computation. Stroom already interns every label, edge type, and property key
name into its own lookup store (`GraphStores.getLabelUids()`/`getEdgeTypeUids()`/
`getPropertyKeyUids()`), and `UidLookupDb.forEachName` — which already exists —
carries a doc comment anticipating exactly this: *"for callers that want the
names rather than the ids (e.g. schema discovery)"*. A small dedicated feature
(a "Schema" view on the GraphDb doc, or a synthetic metagraph — one node per
label, one edge per observed label-pair/edge-type combination — riding the
existing `RETURN GRAPH`/`GraphElementTable`/Cytoscape pipeline) would assemble
data that is already sitting there, with no new storage or algorithm work.

**Cheap-to-medium — one layer is already built.** Virtual/synthetic
relationships for visualization (`apoc.create.vRelationship` — StackOverflow,
Panama Papers) let a computed result (e.g. tag co-occurrence counts from an
aggregation) be drawn as a graph edge without writing anything. Checked
directly against the client code: the wire format (`GraphElementTable` — a
plain `columns`/`rows` table) and the rendering JS
(`stroom-app/.../ui/graph.js`) already treat *any* row with non-empty
`source`/`target` columns as an edge, auto-creating placeholder nodes for
endpoints they haven't seen (`ensureNode`) — they have no concept of "real vs.
synthetic" and need no changes. The only missing piece is upstream: a way for
`RETURN` to emit an element-table-shaped row from an ordinary computed result
(a `MATCH ... WITH t1, t2, count(*) AS freq`-style aggregation) rather than
only from `GraphElementExecutor`'s real-traversal path — most naturally another
`RETURN GRAPH`-style extension, compiler/engine work only.

**Medium — bridges to capability that exists elsewhere in the platform, but
not yet reachable from Cypher.** Full-text/fuzzy search
(`db.index.fulltext.createNodeIndex`/`queryNodes`, incl. fuzzy `~` and boolean
`+` Lucene syntax — Panama Papers) is partially covered already: Stroom's
`CONTAINS`/`STARTS WITH`/`ENDS WITH`/`=~` string predicates (Phase 1) handle
exact substring/regex matching. True ranked fuzzy search would mean bridging
to Stroom's own Lucene-based full-text search capability (central to its core
log-search product) for property/node lookup — a real but bounded integration
task, not new search technology.

**No extra cost — piggybacks on an already-planned gap.** Manual similarity
computation (cosine/Pearson similarity via plain arithmetic — the
`recommendations` dataset's *non-GDS* fallback queries, using `reduce()`,
`sqrt()`, `^`, and list collection) isn't actually a `CALL`/procedure gap at
all: Stroom already has the needed arithmetic (`+ - * / ^ %`, `sqrt` from this
branch's general-maths phase) and aggregates. It's blocked purely by the
already-documented Tier 2 gap — list values/`UNWIND` — so once that lands,
this specific class of "algorithm" becomes plain Cypher with no `CALL` needed.

**Expensive — a different category of work, not a query-language feature.**
True graph algorithms requiring global/iterative computation — PageRank,
Louvain community detection, betweenness centrality, triangle count,
kNN/FastRP embeddings (FinCEN, POLE, Panama Papers, recommendations) — need
multi-pass, fixed-point, or matrix-style computation over the *whole* graph
(or a projected subgraph), fundamentally unlike Stroom's per-row traversal
engine. A genuine Stroom-native equivalent would mean building actual
graph-algorithm implementations, either into `GraphTraversalEngine` or as a
companion algorithm engine — architecturally a different kind of project from
everything else in this document (an algorithm library, not a query-language
feature), and one that would warrant its own dedicated design effort rather
than being bolted onto the Cypher subset. Not recommended without a specific,
strong use case driving it.

---

## Honest verdict

Stroom's graph engine is best understood as **"a capable read/query slice of a
graph query language,"** not a GQL implementation and not on a path to GQL
conformance without a fundamentally larger scope (write statements, catalog,
sessions). Judged on the fair question — *can a user express standard graph
queries?* — it covers the **majority of GQL's mandatory querying capability**:
pattern matching, optional matching, rich filtering, projection with distinct/
alias, ordering and paging, the full mandatory aggregate set, arithmetic,
`CASE`, label-only scans, `UNION`/`UNION ALL` and `EXISTS` subqueries. The
broader dataset survey above sharpens where the boundary actually sits in
practice: the single biggest gap by far is that **`CALL` doesn't exist** —
Stroom has no procedure ecosystem at all, so APOC, the Graph Data Science
library, and Neo4j's own built-in procedures are equally out of reach, which in
turn is why algorithmic staples like `shortestPath()`, PageRank, and full-text
search are all absent too. On the pure query-language surface, path values
(`p = pattern`), multi-type edge patterns (`[:A|B|C]`), returning a whole
node/relationship, and real list values/`UNWIND` are the gaps that would
actually get hit first by someone porting real-world Cypher content in; the
`SELECT` tabular form, by contrast, is rarely used in practice (none of the
12 real-world datasets surveyed used it).

---

## Sources

- [ISO/IEC 39075:2024 — Information technology — Database languages — GQL (ISO catalogue)](https://www.iso.org/standard/76120.html)
- [ISO/IEC 39075:2024(en) — online browsing platform](https://www.iso.org/obp/ui/en/#!iso:std:76120:en)
- [GQL Conformance — Ultipa documentation (mandatory-feature-by-subclause summary)](https://www.ultipa.com/docs/gql/gql-conformance)
- [Spanner Graph and ISO standards — Google Cloud (mandatory minimal data-type set)](https://docs.cloud.google.com/spanner/docs/graph/iso-standards)
- [gqlstandards.org — GQL standard overview](https://www.gqlstandards.org/)
- [Get started with Cypher — Neo4j intro tutorial (source of the worked example above)](https://neo4j.com/docs/getting-started/cypher/intro-tutorial/)
- [Example datasets — Neo4j Getting Started (source of the broader survey above)](https://neo4j.com/docs/getting-started/appendix/example-data/), and the 11 GitHub repositories it links to that were reviewed: [Northwind](https://github.com/neo4j-graph-examples/northwind), [StackOverflow](https://github.com/neo4j-graph-examples/stackoverflow), [Movie recommendations](https://github.com/neo4j-graph-examples/recommendations), [Neoflix](https://github.com/adam-cowley/neoflix), [Crime investigation (POLE)](https://github.com/neo4j-graph-examples/pole), [FinCEN](https://github.com/jexp/fincen), [Panama/Paradise Papers](https://github.com/neo4j-graph-examples/icij-paradise-papers), [Game of Thrones](https://github.com/neo4j-examples/game-of-thrones), [Twitter](https://github.com/neo4j-graph-examples/twitter-v2), [London public transportation](https://github.com/neo4j-partners/neo4j-transport-for-london), [IT management](https://github.com/neo4j-graph-examples/network-management)

*Stroom-side capability claims in this document are drawn from the branch's Cypher
grammar (`Cypher.g4`), the compiler (`CypherToLogicalPlan`) and the graph engine
(`GraphTraversalEngine`) as of commit `b1c50565d5`; see also
`docs/cypher-language-feature-roadmap.md`.*
