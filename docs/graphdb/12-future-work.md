# Roadmap and known gaps

**Status:** Evaluation / proof of concept — not production ready. See the
[production-readiness blockers](README.md#production-readiness--known-blockers).
**Audience:** stakeholders planning work; users wondering whether a gap will close.
**Scope:** everything the rest of this documentation set describes as missing, with an estimate of
difficulty and risk. Canonical for roadmap status.
**Companion documents:** every other file links here whenever it says "not supported".

*Facts verified on 2026-07-28 against branch `sw-query-optimiser`.*

---

**Nothing on this page exists.** The rest of the documentation set describes only what is built; this file
is the counterpart, and no other file may describe any of it in the present tense.

Difficulty is engineering effort. Risk is the chance of getting it subtly wrong, breaking something
existing, or being unable to change it later.

## Production blockers

The items that stand between Graph DB and a production deployment. If only one section of this page is
acted on, it should be this one.

| # | Item | Why | Difficulty | Risk |
|---|---|---|---|---|
| 1 | **A configuration surface** — a `GraphDbConfig` covering store size, retention defaults and the traversal guardrails | Today an administrator has no controls at all. Every limit is a `private static final` | Medium | Low |
| 2 | **Tunable store size** — stop passing no size override, expose it per document or globally | The fixed 10 GiB cap is the hardest ceiling in the system, and unmovable | Easy | Low |
| 3 | **Loud ingest failures** — a strict mode that fails a stream rather than skipping records, plus schema validation | Silent partial data loss is the most dangerous current behaviour | Medium | Low |
| 4 | **Version condensing** — merge redundant identical versions during retention | Storage grows monotonically even with retention on | Medium | Medium |
| 5 | **Retention for the property index** — include it and the property-key table in the sweep | The index grows without bound regardless of retention | Medium | Medium |
| 6 | **Compaction without source streams** — an in-place rebuild that does not depend on reprocessing | Rebuild silently stops being possible once source streams age off | Hard | High |
| 7 | **Characterise clustering and HA** — establish and document the behaviour | Currently unknown, so unclaimable either way | Medium | Medium |
| 8 | **Ship the XSD** as an XMLSchema document so a `SchemaFilter` can validate in-pipeline | It exists only as a test resource today | Easy | Low |

## Data model

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Typed property values** — numbers, booleans, dates natively | Everything is a string, so ordering is lexical and comparison needs conversion. The single biggest usability gap | Hard | High — changes the storage format and needs a migration |
| **A real list type** so `collect()` returns a list | It returns a comma-joined string, which silently misleads anyone from Neo4j | Medium | Medium — needs a new value type through the whole stack |
| **Typed values in `graph-mutation`** — a version 1.1 or 2.0 of the ingest vocabulary | Prerequisite for typed properties | Medium | Medium |
| **Per-property versioning** instead of whole-snapshot versions | Would cut storage growth substantially for wide, slowly-changing nodes | Hard | High |

## Query language

Grouped by how likely you are to want them.

### Small and worthwhile

| Item | Difficulty | Risk |
|---|---|---|
| `SKIP` — needs an offset on the core's `Limit` node | Easy | Low |
| `ORDER BY` an aggregate expression rather than only its alias | Easy | Low |
| `ORDER BY` / `SKIP` / `LIMIT` on a `WITH` | Medium | Low |
| Aggregation inside a `DIFF` query | Medium | Medium |
| Filtering on `changeKind` / `before()` / `after()` in a `WHERE` | Medium | Low |

### Structural

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **A second `MATCH` clause**, and multi-pattern `MATCH (a), (b)` | Common Cypher shape; several Neo4j queries need it | Medium | Medium |
| **Relationship-type alternation** `[:A\|B]` | Adjacency is keyed per type, so this needs a union of scans | Medium | Medium |
| **Pattern predicates** — `WHERE NOT (a)-[:X]->(b)` | `EXISTS { … }` covers part of the need; negation in a broader expression is common | Medium | Medium |
| **Untyped hops** `-->` | Needs an index that does not exist; arguably should stay unsupported to keep costs predictable | Hard | High |
| **Chaining a variable-length hop with other hops** | Currently a var-length hop must be the only hop | Medium | Medium |
| **Multiple `WITH` clauses** (full pipelining) | Medium | Medium |

### Analytics

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Path variables and `RETURN p`** | Prerequisite for anything path-shaped | Hard | Medium |
| **`shortestPath` / `allShortestPaths`** server-side | The Explore tab's version is browser-side over loaded nodes only, and is easy to mistake for the real thing | Hard | Medium |
| **Graph algorithms** — centrality, community detection, triangle count | Needed to answer POLE-style "who matters most" questions in one query | Hard | Medium |
| **Spatial** — `point()`, `point.distance()` | Needs a spatial type and index | Hard | Medium |
| **`labels()` / `keys()` / `properties()`** | Blocked on the list/map type above | Medium | Low |
| **`UNWIND`, list comprehensions, map projections** | Blocked on the list/map type | Medium | Medium |

### Not planned

So that nobody waits for them:

- **Writes from the query language** (`CREATE`, `SET`, `MERGE`, `DELETE`). Ingest belongs to pipelines,
  which is what keeps provenance, permissions and reprocessing consistent with the rest of Stroom. This is
  a design decision, not a gap.
- **Cross-graph queries.** One query, one graph.
- **Full ISO GQL conformance.** GQL includes sessions, transactions, catalogues and schema statements that
  do not fit a read-only embedded store. See [09-gql-and-neo4j.md](09-gql-and-neo4j.md).
- **Stored procedures / `CALL`.**

## Performance and execution

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Asynchronous query execution** | Queries occupy a request thread for up to 30 s | Medium | Medium |
| **Cost-based anchor selection** — let the planner pick the cheapest start rather than always the first pattern | Removes the main performance footgun. Would need statistics | Hard | Medium |
| **Streaming results for sorted and aggregated queries** | Those must currently buffer, which is what the million-row ceiling protects | Hard | Medium |
| **Index on edge properties** | Edge property predicates are post-expand filters only | Medium | Medium |

## User interface

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Cypher-carrying dashboards** — make Create Dashboard work by dispatching the query component through the Cypher seam | The button is disabled today because dashboards parse query text as StroomQL | Medium | Medium |
| **Implement Temporal Precision**, or remove the control | It is editable and persisted but read by no code — the worst of both | Medium | Low |
| **Warn on the silent 100-node preview cap** | Every other limit reports itself; this one truncates quietly | Easy | Low |
| **Server-side layout for large graphs** | The 2,000-element render cap is a browser constraint | Hard | Medium |
| **Saved queries / query history** on the graph tabs | Medium | Low |

## Documentation and testing

| Item | Why | Difficulty | Risk |
|---|---|---|---|
| **Land the event-logging XSLT as a test** in `stroom-graphdb-impl`, with its sample corpus | It currently lives only in [04-event-logging-xslt.md](04-event-logging-xslt.md) and is unexercised by CI, so it will rot silently | Easy | Low |
| **Assert documented limits against the constants** in a test | [10-limits.md](10-limits.md) records ~15 values that a refactor can change with no doc-side signal | Easy | Low |
| **Assert documented error strings** against `CypherToLogicalPlan` | Same problem for the messages quoted in [06-language-reference.md](06-language-reference.md) | Easy | Low |

Those three are cheap and would convert a large part of this documentation set from prose into something CI
defends. They are the highest value-per-effort items on the page.

## Suggested order

If the goal is a production-capable Graph DB:

1. **Blockers 1, 2, 3, 8** — configuration, tunable size, loud ingest failures, ship the XSD. Mostly easy,
   low risk, and together they remove the "an operator has no controls and no warning" problem.
2. **Documentation tests** — cheap, and they stop the rest of this set drifting.
3. **Blockers 4, 5** — condensing and index retention, so storage is bounded rather than merely slowed.
4. **Typed property values** — the biggest usability win, and best done before there is much data to
   migrate.
5. **Blocker 6, then 7** — a compaction path independent of source streams, then characterising clustering.
6. Language and analytics features, driven by what users actually ask for.

## Next

- [README.md](README.md) — the blockers in their user-facing form
- [09-gql-and-neo4j.md](09-gql-and-neo4j.md) — how the gaps compare with Neo4j and GQL

### Further reading (engineering)

[`archive/cypher-language-feature-roadmap.md`](archive/cypher-language-feature-roadmap.md) surveys the unsupported language features with its own value and
cost estimates. `docs/query-graphdb-review-report.md` and `docs/query-graphdb-review-findings.md` record the
pre-production review and its findings.
