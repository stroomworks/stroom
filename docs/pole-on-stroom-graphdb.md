# Reproducing the Neo4j POLE exercise on Stroom Graph DB

*What this is: an attempt to reproduce Neo4j's **POLE** (Person–Object–Location–Event) crime-graph tutorial
([neo4j-graph-examples/pole](https://github.com/neo4j-graph-examples/pole/blob/main/documentation/pole-workspace-guide.adoc))
on Stroom's temporal-Cypher Graph DB, with an honest record of what worked, what didn't, and step-by-step
instructions so a human can replicate it. Run 2026-07-20 against a live single-node Stroom via the REST API.*

---

## TL;DR

- **The POLE data model reproduces cleanly.** Persons, Officers, Crimes, Locations, Objects and their
  relationships (`PARTY_TO`, `INVESTIGATED_BY`, `INVOLVED_IN`, `OCCURRED_AT`, `KNOWS`, `CURRENT_ADDRESS`) all
  ingest into a Stroom Graph DB via `graph-mutation:1` XML with no errors.
- **The graph *traversals* at the heart of POLE work** — anchored one-hop and fixed-length multi-hop patterns,
  both `->` and `<-` directions, undirected `-`, variable-length single hops (`*1..2`), `WHERE` on a hopped
  node, and `ORDER BY` / `DISTINCT` / `LIMIT`.
- **But most POLE *queries as written* do not run**, because the guide is built on three capabilities Stroom's
  PoC Cypher deliberately omits: **aggregation** (`count`, `collect`, group-by), **spatial** (`point`,
  `point.distance`), and **path-finding** (`allShortestPaths`, multi-type variable-length paths, `RETURN path`),
  plus **pattern-predicates** (`WHERE NOT (a)-[:X]->(b)`) and **writes** (`SET`).
- **Every unsupported query fails with a clear compile error, never a 500 or a wrong answer** — which is exactly
  the PoC's stated contract.
- **Stroom adds something POLE can't do: time.** Every node/edge has a `validFrom`, so the same traversal can be
  run `AS OF` a past instant and returns the graph as it was then.

Net: you can reproduce POLE's **structure and traversals**, not its **analytics** (aggregation/spatial/paths).

---

## 1. How to replicate (step by step)

You need admin access to a running Stroom that includes the `stroom-graphdb` modules, and an **API key**
(User menu → API Keys → New) for the REST calls. All UUIDs below are from this run — yours will differ.

### 1.1 Create the documents
In the Explorer (or via the MCP `createDocument` tool):
1. A **Folder**, e.g. `GraphDb Test`.
2. A **GraphDb** doc, e.g. `POLE Graph`. *(Give it a **unique name** — the Graph Filter resolves the target
   store by name, so two GraphDb docs sharing a name make ingest fail with "found more than one graph db doc".)*
3. A **Feed**, e.g. `GRAPH-TEST` (status `RECEIVE`, `Raw Events`, UTF-8 — the defaults).
4. A **Pipeline**, e.g. `Graph Test Pipeline`, with three elements wired `Source → XMLParser → GraphFilter`, and
   the Graph Filter's **`graphDb`** property pointing at the `POLE Graph` doc.

### 1.2 Ingest the POLE data
Upload the `graph-mutation:1` XML in [§2](#2-the-pole-dataset-used) to the `GRAPH-TEST` feed (UI **Upload**
button, or MCP `uploadToFeed`, or send to the feed's receipt URL). Then create a **processor filter** on the
pipeline for that feed. Processing is asynchronous; verify success by confirming **no `Error` stream** appears
for the feed (the Graph Filter writes into the store, so there is no `Events` output stream).

### 1.3 Run Cypher queries
Two options:
- **UI:** open the `POLE Graph` doc's **Data** tab and type Cypher.
- **REST:** `POST /api/query/v1/search` with a Bearer token. The body must set `searchRequestSource.ownerDocRef`
  to the GraphDb doc (this is how Stroom routes the text to the Cypher compiler — Cypher has no `FROM` clause):

```bash
curl -s -H "Authorization: Bearer <API_KEY>" -H "Content-Type: application/json" \
  -X POST http://localhost:8080/api/query/v1/search -d '{
    "searchRequestSource": {"sourceType":"QUERY_UI",
      "ownerDocRef": {"type":"GraphDb","uuid":"<POLE_GRAPH_UUID>","name":"POLE Graph"}},
    "query": "MATCH (p:Person {surname:'\''Powell'\''})-[:PARTY_TO]->(c:Crime) RETURN c.type",
    "queryContext": {}, "incremental": true, "timeout": 30000,
    "requestedRange": {"offset":0,"length":100}
  }'
```
Send with `queryKey` **omitted** on the first call (the server mints one and returns complete results); results
are at `results[].rows[].values`.

> Note: `POST` with a **session cookie** (e.g. the Swagger "Try it out" button) is rejected **403** by CSRF
> protection — use a **Bearer API key**, which is exempt.

---

## 2. The POLE dataset used

A representative subset (14 nodes, 17 edges). Note the two **later-dated** edges (`validFrom` 2026-06-01) used to
demonstrate temporal queries.

```xml
<graph xmlns="graph-mutation:1" version="1.0">
  <node id="p-jack"  validFrom="2020-01-01T00:00:00.000Z"><label>Person</label><property name="name">Jack</property><property name="surname">Powell</property><property name="nhs_no">NHS001</property></node>
  <node id="p-ray"   validFrom="2020-01-01T00:00:00.000Z"><label>Person</label><property name="name">Raymond</property><property name="surname">Walker</property><property name="nhs_no">NHS002</property></node>
  <node id="p-anne"  validFrom="2020-01-01T00:00:00.000Z"><label>Person</label><property name="name">Anne</property><property name="surname">Freeman</property><property name="nhs_no">NHS003</property></node>
  <node id="p-mary"  validFrom="2020-01-01T00:00:00.000Z"><label>Person</label><property name="name">Mary</property><property name="surname">Smith</property><property name="nhs_no">NHS004</property></node>
  <node id="o-morse" validFrom="2020-01-01T00:00:00.000Z"><label>Officer</label><property name="surname">Morse</property></node>
  <node id="o-larive" validFrom="2020-01-01T00:00:00.000Z"><label>Officer</label><property name="surname">Larive</property></node>
  <node id="c1" validFrom="2020-01-01T00:00:00.000Z"><label>Crime</label><property name="type">Drugs</property><property name="last_outcome">Under investigation</property></node>
  <node id="c2" validFrom="2020-01-01T00:00:00.000Z"><label>Crime</label><property name="type">Burglary</property><property name="last_outcome">Under investigation</property></node>
  <node id="c3" validFrom="2020-01-01T00:00:00.000Z"><label>Crime</label><property name="type">Drugs</property><property name="last_outcome">Charged</property></node>
  <node id="c4" validFrom="2026-06-01T00:00:00.000Z"><label>Crime</label><property name="type">Drugs</property><property name="last_outcome">Under investigation</property></node>
  <node id="l1" validFrom="2020-01-01T00:00:00.000Z"><label>Location</label><property name="address">1 Coronation Street</property><property name="postcode">M60 1AA</property></node>
  <node id="l2" validFrom="2020-01-01T00:00:00.000Z"><label>Location</label><property name="address">5 Baker Street</property><property name="postcode">NW1 6XE</property></node>
  <node id="ob1" validFrom="2020-01-01T00:00:00.000Z"><label>Object</label><property name="description">Knife</property></node>
  <node id="ob2" validFrom="2020-01-01T00:00:00.000Z"><label>Object</label><property name="description">Phone</property></node>
  <edge type="PARTY_TO" validFrom="2020-01-01T00:00:00.000Z"><src>p-jack</src><dst>c1</dst></edge>
  <edge type="PARTY_TO" validFrom="2020-01-01T00:00:00.000Z"><src>p-ray</src><dst>c3</dst></edge>
  <edge type="PARTY_TO" validFrom="2020-01-01T00:00:00.000Z"><src>p-mary</src><dst>c2</dst></edge>
  <edge type="INVESTIGATED_BY" validFrom="2020-01-01T00:00:00.000Z"><src>c1</src><dst>o-larive</dst></edge>
  <edge type="INVESTIGATED_BY" validFrom="2020-01-01T00:00:00.000Z"><src>c3</src><dst>o-larive</dst></edge>
  <edge type="INVESTIGATED_BY" validFrom="2020-01-01T00:00:00.000Z"><src>c2</src><dst>o-morse</dst></edge>
  <edge type="INVOLVED_IN" validFrom="2020-01-01T00:00:00.000Z"><src>ob1</src><dst>c2</dst></edge>
  <edge type="INVOLVED_IN" validFrom="2020-01-01T00:00:00.000Z"><src>ob2</src><dst>c1</dst></edge>
  <edge type="OCCURRED_AT" validFrom="2020-01-01T00:00:00.000Z"><src>c1</src><dst>l1</dst></edge>
  <edge type="OCCURRED_AT" validFrom="2020-01-01T00:00:00.000Z"><src>c2</src><dst>l2</dst></edge>
  <edge type="OCCURRED_AT" validFrom="2020-01-01T00:00:00.000Z"><src>c3</src><dst>l1</dst></edge>
  <edge type="KNOWS" validFrom="2020-01-01T00:00:00.000Z"><src>p-jack</src><dst>p-ray</dst></edge>
  <edge type="KNOWS" validFrom="2020-01-01T00:00:00.000Z"><src>p-anne</src><dst>p-jack</dst></edge>
  <edge type="CURRENT_ADDRESS" validFrom="2020-01-01T00:00:00.000Z"><src>p-anne</src><dst>l1</dst></edge>
  <edge type="CURRENT_ADDRESS" validFrom="2020-01-01T00:00:00.000Z"><src>p-jack</src><dst>l2</dst></edge>
  <edge type="PARTY_TO" validFrom="2026-06-01T00:00:00.000Z"><src>p-jack</src><dst>c4</dst></edge>
  <edge type="INVESTIGATED_BY" validFrom="2026-06-01T00:00:00.000Z"><src>c4</src><dst>o-larive</dst></edge>
</graph>
```

---

## 3. What worked — traversals (verified live)

| POLE idea | Stroom Cypher run | Result |
|---|---|---|
| Objects involved in a crime (q4) | `MATCH (o:Object {description:'Knife'})-[:INVOLVED_IN]->(c:Crime) RETURN c.type` | `Burglary` |
| Persons party to crimes (q5) | `MATCH (p:Person {surname:'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type` | `Drugs, Drugs` |
| Crime → investigating officer (q13/15) | `MATCH (c:Crime {type:'Burglary'})-[:INVESTIGATED_BY]->(o:Officer) RETURN o.surname` | `Morse` |
| Crimes at a location (q7) | `MATCH (c:Crime {type:'Drugs'})-[:OCCURRED_AT]->(l:Location) RETURN l.address` | `1 Coronation Street ×2` |
| Fixed 2-hop chain (q17/18 skeleton) | `MATCH (o:Object {description:'Knife'})-[:INVOLVED_IN]->(c:Crime)-[:OCCURRED_AT]->(l:Location) RETURN l.address` | `5 Baker Street` |
| **Incoming direction** `<-` (q13) | `MATCH (o:Officer {surname:'Larive'})<-[:INVESTIGATED_BY]-(c:Crime) RETURN c.type` | `Drugs ×3` |
| Undirected hop (q16/19 style) | `MATCH (p:Person {surname:'Freeman'})-[:KNOWS]-(f:Person) RETURN f.surname` | `Powell` |
| **Variable-length** single hop (q19/20) | `MATCH (p:Person {surname:'Freeman'})-[:KNOWS*1..2]->(f:Person) RETURN f.surname` | `Powell, Walker` |
| `WHERE` field vs literal (q14) | `… <-[:INVESTIGATED_BY]-(c:Crime) WHERE c.last_outcome = 'Under investigation' RETURN c.type` | `Drugs ×2` |
| `ORDER BY` + `LIMIT` | `… RETURN c.type ORDER BY c.type LIMIT 5` | `Drugs ×3` |
| `DISTINCT` | `… RETURN DISTINCT c.type` | `Drugs` (deduped) |

## 4. What Stroom adds that POLE doesn't — time

Every edge carries `validFrom`; the later-dated crime `c4` (2026-06-01) is invisible before it existed:

| Query | Result |
|---|---|
| `MATCH (p:Person {surname:'Powell'})-[:PARTY_TO]->(c:Crime) RETURN c.type` (now) | `Drugs, Drugs` |
| `… RETURN c.type` **`AS OF datetime('2021-01-01T00:00:00Z')`** | `Drugs` (only `c1`) |

`BETWEEN` and `AROUND` windowed forms are also supported. This temporal dimension has no equivalent in the POLE
Neo4j guide.

## 5. What didn't work — and why (all fail with a clear compile error, not a 500)

| POLE query | Stroom outcome | Missing capability |
|---|---|---|
| Schema visualization (q1) | n/a | No `db.schema.visualization()` procedure |
| Count / frequency (q2, q3) | rejected | (a) anchor needs a **property predicate** (`MATCH (c:Crime)` alone is rejected: *"a label-only scan… is not indexed"*), and (b) **aggregation not supported**: *"aggregates… not wired to a graph traversal row"* |
| Aggregated variants of q4, q5, q7, q14 | traversal works; `count`/`collect` rejected | **Aggregation / group-by** not implemented for graph traversals |
| Create point property (q6) | rejected | No **writes** (`SET`) and no spatial types in the read-only PoC subset |
| Spatial distance / radius / nearest (q8–q12) | rejected: *mismatched input '('* | No **`point` / `point.distance`** functions |
| `RETURN *` / `RETURN path` (q13, q15, q16, q17, q18, q21) | rejected | `RETURN` must be `variable.property`; **whole-node / path returns** not supported |
| `allShortestPaths` (q16) | parse error | No **shortest-path** procedures |
| Multi-type variable-length `[:KNOWS|FAMILY_REL*..3]` (q16, q17) | parse error at `'|'` | **Alternation of relationship types** not supported (single type per hop) |
| Pattern-predicate `WHERE NOT (a)-[:X]->(b)` (q19, q20, q22, q23) | parse error | `WHERE` supports **field-vs-literal only**, not pattern existence |
| Multi-pattern `MATCH a, b` (q23) | rejected | **One `MATCH` clause / one pattern** only |

### The pattern behind the gaps
POLE is, at its core, an **analytics** tutorial: it ranks by `count`, measures `point.distance`, and finds
`allShortestPaths`. Stroom's Cypher today is a **traversal** engine — it retrieves connected nodes by anchored
pattern, with time-travel, and leaves aggregation/scoring/spatial to (a) a later Cypher phase or (b) feeding the
traversal output into StroomQL/dashboards for the counting and ranking. So POLE's *questions* are reproducible in
spirit, but the *single-query* Neo4j formulations that fold traversal + aggregation + spatial into one statement
are not.

---

## 6. Notes, gotchas & caveats for replicators

- **Unique GraphDb name.** The Graph Filter resolves the target store by **name**; a duplicate name anywhere in
  the tree causes a FATAL ingest error. (This is consistent with how other Stroom stores are keyed by name.)
- **Anchor must have a label *and* a property predicate.** `MATCH (c:Crime {type:'Drugs'})` works;
  `MATCH (c:Crime)` does not — there is no "scan all nodes of a label" access path.
- **`RETURN variable.property`**, not whole nodes or paths. To see a related entity, return its properties.
- **One `MATCH`, one pattern, single relationship type per hop.** Fixed-length multi-hop chains are fine; one
  variable-length hop (`*a..b`, bounded) is fine as the sole hop.
- **Aggregation, spatial, shortest-path, pattern-predicates, and writes are out of scope** in the current PoC —
  expect a clear "not yet supported"/parse error.
- **Route via `ownerDocRef`** (REST) or the GraphDb **Data tab** (UI); a plain StroomQL surface won't parse
  Cypher. *(A proposed `from "GraphDb"` prefix would let Cypher run from any surface — see
  [cypher-from-clause-implementation-plan.md](cypher-from-clause-implementation-plan.md).)*
- **Use a Bearer API key**, not a session cookie, for REST `POST`s (CSRF).

---

## 7. Verdict

You can stand up the POLE graph on Stroom and answer POLE's underlying **relationship questions** through anchored
traversals — with the bonus of **temporal** "as of" analysis that the Neo4j guide has no equivalent for. You
cannot, today, reproduce POLE's **aggregation, spatial, and path-finding** queries as written; those are the
concrete, clearly-signposted boundaries of the current Cypher subset and the natural candidates for the next phase
of graph-query work.
